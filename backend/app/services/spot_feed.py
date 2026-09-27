"""
Automatic, keyless spot-gold fallback feed.

When AURUM_TWELVE_DATA_API_KEY is not configured, the platform must not invent a price —
but it should also not sit completely idle waiting for someone to paste in a paid
provider's key. This module polls two independent, unauthenticated public endpoints that
already publish real, tradable XAU/USD spot quotes with no registration and no key:

  1. Swissquote's public BBO feed — real bid/ask, the same feed their own retail platform
     uses (https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD).
  2. gold-api.com — a free real-time spot mid price, used only if Swissquote is unreachable
     (https://api.gold-api.com/price/XAU).

Every quote is validated (finite, positive, fresh, correct symbol) before use — exactly like
the Twelve Data path. Accepted quotes are aggregated into real 1-minute bars (CandleBuilder)
and persisted to disk so a restart does not wipe out hours of already-collected genuine
history. This is a lower guarantee than a paid, licensed feed (no SLA, ~5s refresh instead of
tick-by-tick, and history only as deep as what this process has actually observed) — it
exists purely so the terminal has *some* automatic, real, live data with zero manual setup.
Adding AURUM_TWELVE_DATA_API_KEY later switches the whole pipeline back to true streaming
ticks and instant deep history; nothing else needs to change.

Never invents a price, never backfills a gap, never rewrites an already-closed bar.
"""
from __future__ import annotations

import asyncio
import json
import math
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from app.config import Settings
from app.models import Candle, Tick, Timeframe
from app.services.candle_builder import CandleBuilder
from app.services.history import DataUnavailable, _validate_candles

POLL_SECONDS = 5
MAX_QUOTE_AGE_SECONDS = 45
MAX_STORED_BARS = 60 * 24 * 45  # ~45 days of real 1-minute bars; bounds the cache file size

STORE_PATH = Path(__file__).resolve().parent.parent / "data" / "cache" / "spot_fallback_m1.json"

SWISSQUOTE_URL = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD"
GOLD_API_URL = "https://api.gold-api.com/price/XAU"

SPOT_SYMBOL = "XAU/USD"


async def _fetch_swissquote(client: httpx.AsyncClient) -> Tick:
    response = await client.get(SWISSQUOTE_URL)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, list) or not payload or not isinstance(payload[0], dict):
        raise DataUnavailable("پاسخ Swissquote معتبر نیست")
    quote = payload[0]
    profiles = quote.get("spreadProfilePrices")
    if not isinstance(profiles, list) or not profiles:
        raise DataUnavailable("پاسخ Swissquote فاقد قیمت است")
    prime = next((p for p in profiles if isinstance(p, dict) and p.get("spreadProfile") == "prime"), profiles[0])
    try:
        bid, ask = float(prime["bid"]), float(prime["ask"])
        timestamp = datetime.fromtimestamp(float(quote["ts"]) / 1000, timezone.utc)
    except (KeyError, TypeError, ValueError):
        raise DataUnavailable("bid/ask یا زمان Swissquote معتبر نیست") from None
    if not (math.isfinite(bid) and math.isfinite(ask) and bid > 0 and ask >= bid):
        raise DataUnavailable("bid/ask دریافتی از Swissquote نامعتبر است")
    age = (datetime.now(timezone.utc) - timestamp).total_seconds()
    if not -10 <= age <= MAX_QUOTE_AGE_SECONDS:
        raise DataUnavailable("قیمت Swissquote قدیمی یا با زمان نامعتبر است")
    return Tick(symbol=SPOT_SYMBOL, timestamp=timestamp, bid=bid, ask=ask, provider="spot_fallback:swissquote")


async def _fetch_gold_api(client: httpx.AsyncClient) -> Tick:
    response = await client.get(GOLD_API_URL)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict) or str(payload.get("symbol", "")).upper() != "XAU":
        raise DataUnavailable("پاسخ gold-api.com معتبر نیست")
    try:
        price = float(payload["price"])
        timestamp = datetime.fromisoformat(str(payload["updatedAt"]).replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError):
        raise DataUnavailable("قیمت یا زمان gold-api.com معتبر نیست") from None
    if not (math.isfinite(price) and price > 0):
        raise DataUnavailable("قیمت gold-api.com نامعتبر است")
    age = (datetime.now(timezone.utc) - timestamp).total_seconds()
    if not -10 <= age <= MAX_QUOTE_AGE_SECONDS:
        raise DataUnavailable("قیمت gold-api.com قدیمی است")
    return Tick(symbol=SPOT_SYMBOL, timestamp=timestamp, bid=price, ask=price, provider="spot_fallback:gold-api")


async def fetch_spot_quote(client: httpx.AsyncClient) -> Tick:
    """Real, free, keyless spot XAU/USD: Swissquote first, gold-api.com as a backup. Never invented."""
    try:
        return await _fetch_swissquote(client)
    except Exception as primary_exc:
        try:
            return await _fetch_gold_api(client)
        except Exception as secondary_exc:
            raise DataUnavailable(
                f"هیچ‌کدام از منابع رایگان قیمت طلا در دسترس نیستند "
                f"(Swissquote: {type(primary_exc).__name__}, Gold-API: {type(secondary_exc).__name__})"
            ) from secondary_exc


def _resample(bars: list[Candle], timeframe: Timeframe) -> list[Candle]:
    """Aggregate real 1-minute bars into a higher timeframe — same real bars, just bucketed."""
    span = timeframe.seconds
    buckets: dict[int, list[Candle]] = {}
    for bar in bars:
        bucket = int(bar.timestamp.timestamp()) // span * span
        buckets.setdefault(bucket, []).append(bar)
    now_bucket = int(datetime.now(timezone.utc).timestamp()) // span * span
    out: list[Candle] = []
    for bucket in sorted(buckets):
        group = buckets[bucket]
        out.append(Candle(
            symbol=group[0].symbol, timeframe=timeframe,
            timestamp=datetime.fromtimestamp(bucket, timezone.utc),
            open=group[0].open, high=max(c.high for c in group), low=min(c.low for c in group),
            close=group[-1].close, volume=sum(c.volume for c in group),
            complete=bucket < now_bucket,
        ))
    return out


class SpotHistoryStore:
    """Persists real, validated 1-minute bars built from the free fallback ticks."""

    def __init__(self, path: Path = STORE_PATH):
        self.path = path
        self._bars: list[Candle] = self._load()

    def _load(self) -> list[Candle]:
        try:
            raw: Any = json.loads(self.path.read_text())
        except Exception:
            return []
        if not isinstance(raw, list):
            return []
        bars: list[Candle] = []
        for item in raw:
            try:
                bars.append(Candle(
                    symbol=item["symbol"], timeframe=Timeframe.M1,
                    timestamp=datetime.fromisoformat(item["timestamp"]),
                    open=item["open"], high=item["high"], low=item["low"], close=item["close"],
                    volume=item.get("volume", 0), complete=True,
                ))
            except Exception:
                continue
        try:
            return _validate_candles(bars, SPOT_SYMBOL, Timeframe.M1)[-MAX_STORED_BARS:]
        except DataUnavailable:
            return []

    def _persist(self) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps([
                {"symbol": c.symbol, "timestamp": c.timestamp.isoformat(), "open": c.open,
                 "high": c.high, "low": c.low, "close": c.close, "volume": c.volume}
                for c in self._bars
            ]))
        except Exception:
            pass  # a disk hiccup must never crash the live feed

    def record(self, bar: Candle) -> None:
        """Append one real, already-closed 1-minute bar. Never rewrites or backfills."""
        if self._bars and bar.timestamp <= self._bars[-1].timestamp:
            return
        self._bars.append(bar)
        if len(self._bars) > MAX_STORED_BARS:
            self._bars = self._bars[-MAX_STORED_BARS:]
        self._persist()

    def history(self, timeframe: Timeframe, limit: int) -> list[Candle]:
        if not self._bars:
            return []
        bars = self._bars if timeframe is Timeframe.M1 else _resample(self._bars, timeframe)
        try:
            return _validate_candles(bars, SPOT_SYMBOL, timeframe)[-limit:]
        except DataUnavailable:
            return []


store = SpotHistoryStore()


def get_history(timeframe: Timeframe, limit: int) -> list[Candle]:
    """Whatever real history the free fallback has genuinely observed so far (never fabricated)."""
    return store.history(timeframe, limit)


async def spot_fallback_ticks(settings: Settings) -> AsyncIterator[Tick]:
    """Infinite real-tick stream from free public spot quotes; aggregates + persists real M1 bars."""
    builder = CandleBuilder(Timeframe.M1)
    async with httpx.AsyncClient(timeout=10) as client:
        while True:
            try:
                tick = await fetch_spot_quote(client)
            except Exception as exc:
                raise DataUnavailable(
                    f"منابع رایگان قیمت لحظه‌ای طلا (Swissquote/Gold-API) در دسترس نیستند ({type(exc).__name__})"
                ) from exc
            if tick.symbol.casefold() == settings.market_symbol.casefold():
                try:
                    completed, _active = builder.ingest(tick)
                except ValueError:
                    completed = None
                if completed is not None:
                    store.record(completed)
            yield tick
            await asyncio.sleep(POLL_SECONDS)
