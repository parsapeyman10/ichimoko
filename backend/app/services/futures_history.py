"""
Optional, explicitly-labeled historical backtest data: COMEX Gold Futures (GC=F).

There is no free, legal, keyless source of *historical spot* XAU/USD OHLC anywhere (checked:
gold-api.com, Swissquote, goldprice.org, stooq.com — every one of them either has no history
endpoint at all, or requires a paid/keyed plan for it). Waiting for the self-aggregating spot
fallback (see app.services.spot_feed) to build up real minute history from scratch is the only
100% spot-accurate option, but it takes real wall-clock time.

COMEX Gold Futures (ticker GC=F) is a DIFFERENT, though extremely tightly correlated, real
instrument with public, free, keyless intraday and daily history on Yahoo Finance. Historically
it trades a few to a few tens of dollars away from spot (a time-varying cost-of-carry "basis"),
so it is never silently relabeled as XAU/USD spot anywhere in this codebase — every candle this
module returns carries symbol="GC=F", and every caller must show that distinction to the user.

This exists purely so the strategy/backtest engine can be validated against deep, real,
immediately-available history while the live spot feed is still collecting its own honest
history in the background. Nothing here is interpolated, synthesized, or backfilled — a gap in
the provider's response is skipped, never invented.
"""
from __future__ import annotations

import math
from datetime import datetime, timezone

import httpx

from app.models import Candle, Timeframe
from app.services.history import DataUnavailable

SYMBOL = "GC=F"
BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/GC=F"

# Yahoo's own retention window per interval; requesting more than this just gets clamped by them.
YAHOO_INTERVALS: dict[Timeframe, tuple[str, str]] = {
    Timeframe.M1: ("1m", "7d"),
    Timeframe.M5: ("5m", "60d"),
    Timeframe.M15: ("15m", "60d"),
    Timeframe.H1: ("60m", "730d"),
    Timeframe.D1: ("1d", "10y"),
}


def _resample_hourly_to_h4(hourly: list[Candle]) -> list[Candle]:
    """history.resample() assumes 1-minute source bars; hourly source bars need their own bucketing."""
    buckets: dict[int, list[Candle]] = {}
    span = Timeframe.H4.seconds
    for candle in hourly:
        bucket = int(candle.timestamp.timestamp()) // span * span
        buckets.setdefault(bucket, []).append(candle)
    out: list[Candle] = []
    for bucket in sorted(buckets):
        group = sorted(buckets[bucket], key=lambda c: c.timestamp)
        out.append(
            Candle(
                symbol=group[0].symbol,
                timeframe=Timeframe.H4,
                timestamp=datetime.fromtimestamp(bucket, timezone.utc),
                open=group[0].open,
                high=max(c.high for c in group),
                low=min(c.low for c in group),
                close=group[-1].close,
                volume=sum(c.volume for c in group),
                complete=len(group) >= 4 and group[-1].complete,
            )
        )
    return out


async def fetch_futures_history(
    client: httpx.AsyncClient, timeframe: Timeframe, limit: int = 1500
) -> list[Candle]:
    """Real COMEX gold futures OHLC (Yahoo Finance chart API). 4h bars are resampled from 1h."""
    base_timeframe = Timeframe.H1 if timeframe is Timeframe.H4 else timeframe
    mapping = YAHOO_INTERVALS.get(base_timeframe)
    if mapping is None:
        raise DataUnavailable("این بازه برای دادهٔ تاریخی فیوچرز پشتیبانی نمی‌شود")
    interval, range_ = mapping

    try:
        response = await client.get(
            BASE_URL,
            params={"interval": interval, "range": range_},
            headers={"User-Agent": "Mozilla/5.0 (compatible; AurumEdge/1.0)"},
        )
        response.raise_for_status()
        payload = response.json()
    except DataUnavailable:
        raise
    except Exception as exc:
        raise DataUnavailable(f"اتصال به منبع تاریخچهٔ فیوچرز طلا برقرار نشد: {type(exc).__name__}") from None

    try:
        result = payload["chart"]["result"][0]
        timestamps = result["timestamp"]
        quote = result["indicators"]["quote"][0]
        opens, highs, lows, closes = quote["open"], quote["high"], quote["low"], quote["close"]
        volumes = quote.get("volume") or [0] * len(timestamps)
    except (KeyError, IndexError, TypeError):
        raise DataUnavailable("ساختار پاسخ منبع تاریخچهٔ فیوچرز طلا معتبر نیست") from None

    now = datetime.now(timezone.utc)
    rows: list[Candle] = []
    for ts, o, h, l, c, v in zip(timestamps, opens, highs, lows, closes, volumes):
        if ts is None or o is None or h is None or l is None or c is None:
            continue  # illiquid/missing bar in the provider's own data — never interpolated
        try:
            o, h, l, c = float(o), float(h), float(l), float(c)
            v = float(v) if v is not None else 0.0
            timestamp = datetime.fromtimestamp(float(ts), timezone.utc)
        except (TypeError, ValueError, OverflowError, OSError):
            continue
        if not all(math.isfinite(x) and x > 0 for x in (o, h, l, c)) or v < 0 or timestamp > now:
            continue
        if h < max(o, c) or l > min(o, c):
            continue
        is_closed = (now - timestamp).total_seconds() >= base_timeframe.seconds
        rows.append(
            Candle(
                symbol=SYMBOL, timeframe=base_timeframe, timestamp=timestamp,
                open=o, high=h, low=l, close=c, volume=v, complete=is_closed,
            )
        )

    rows.sort(key=lambda row: row.timestamp)
    deduped: list[Candle] = []
    seen: set[datetime] = set()
    for row in rows:
        if row.timestamp in seen:
            continue
        seen.add(row.timestamp)
        deduped.append(row)

    if len(deduped) < 2:
        raise DataUnavailable("منبع تاریخچهٔ فیوچرز طلا دادهٔ کافی برای این بازه برنگرداند")

    if timeframe is Timeframe.H4:
        deduped = _resample_hourly_to_h4(deduped)

    return deduped[-limit:] if limit else deduped
