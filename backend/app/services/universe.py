"""
Tradable-symbol universe + multi-symbol real candle loader.

Two independent, real sources — nothing here is generated:

* Crypto — Binance public market host `data-api.binance.vision`. `/exchangeInfo` gives the
  complete list of SPOT symbols with their real tick size; `/klines` gives real OHLCV with
  deep history, keyless. This is the same host the existing pump scanner already trusts.
* Forex / metals — Twelve Data `/time_series` when `AURUM_TWELVE_DATA_API_KEY` is set.
  Without a key there is **no** keyless source of real intraday FX *history*, so this module
  raises `DataUnavailable` with an explicit Persian message instead of fabricating bars.
  Swissquote's public BBO still provides a real live *quote* for any FX pair, which is
  exposed separately (`live_quote`) and used only for spread/freshness checks.

Every series is passed through the same `_validate_candles` contract used by the
single-symbol pipeline, so a bad row from either venue fails loudly.
"""
from __future__ import annotations

import asyncio
import math
import time
from datetime import datetime, timezone

import httpx

from app.config import Settings
from app.models import Candle, Timeframe
from app.services import instruments as inst
from app.services.history import (
    DataUnavailable,
    MAX_POINTS_PER_REQUEST,
    _request as _twelve_data_request,
    _validate_candles,
    resample,
)

BINANCE = "https://data-api.binance.vision/api/v3"
SWISSQUOTE = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument"

BINANCE_INTERVAL: dict[Timeframe, str] = {
    Timeframe.M1: "1m",
    Timeframe.M3: "3m",
    Timeframe.M5: "5m",
    Timeframe.M15: "15m",
    Timeframe.H1: "1h",
    Timeframe.H4: "4h",
    Timeframe.D1: "1d",
}

_EXCHANGE_INFO_TTL = 900  # seconds — the symbol list barely changes
_CANDLE_TTL = {
    Timeframe.M1: 20, Timeframe.M3: 40, Timeframe.M5: 45,
    Timeframe.M15: 90, Timeframe.H1: 300, Timeframe.H4: 900, Timeframe.D1: 1800,
}

_exchange_cache: tuple[float, list[inst.InstrumentSpec]] | None = None
_candle_cache: dict[str, tuple[float, list[Candle]]] = {}
_exchange_lock = asyncio.Lock()


# ─────────────────────────────────────────────────────────────────────────────
# Binance universe
# ─────────────────────────────────────────────────────────────────────────────

def _tick_from_filters(filters: object) -> float | None:
    if not isinstance(filters, list):
        return None
    for item in filters:
        if isinstance(item, dict) and item.get("filterType") == "PRICE_FILTER":
            try:
                tick = float(item["tickSize"])
            except (KeyError, TypeError, ValueError):
                return None
            return tick if math.isfinite(tick) and tick > 0 else None
    return None


async def load_binance_universe(force: bool = False) -> list[inst.InstrumentSpec]:
    """Every Binance SPOT symbol that is actually TRADING, with its real tick size."""
    global _exchange_cache
    async with _exchange_lock:
        if not force and _exchange_cache and time.time() - _exchange_cache[0] < _EXCHANGE_INFO_TTL:
            return _exchange_cache[1]
        try:
            async with httpx.AsyncClient(timeout=25, follow_redirects=False) as client:
                response = await client.get(f"{BINANCE}/exchangeInfo", params={"permissions": "SPOT"})
                response.raise_for_status()
                payload = response.json()
        except Exception as exc:
            if _exchange_cache:
                return _exchange_cache[1]
            raise DataUnavailable(f"فهرست نمادهای بایننس در دسترس نیست: {type(exc).__name__}") from None

        symbols = payload.get("symbols") if isinstance(payload, dict) else None
        if not isinstance(symbols, list) or not symbols:
            raise DataUnavailable("پاسخ exchangeInfo بایننس معتبر نیست")

        out: list[inst.InstrumentSpec] = []
        for row in symbols:
            if not isinstance(row, dict) or row.get("status") != "TRADING":
                continue
            if not row.get("isSpotTradingAllowed", False):
                continue
            symbol = str(row.get("symbol", "")).upper()
            base = str(row.get("baseAsset", "")).upper()
            quote = str(row.get("quoteAsset", "")).upper()
            if not symbol or not base or not quote or quote not in inst.BINANCE_QUOTES:
                continue
            tick = _tick_from_filters(row.get("filters"))
            if tick is None:
                continue
            out.append(inst.crypto_spec(symbol, base, quote, tick))

        if not out:
            raise DataUnavailable("هیچ نماد SPOT فعالی از بایننس دریافت نشد")
        out.sort(key=lambda s: (s.quote != "USDT", s.symbol))
        _exchange_cache = (time.time(), out)
        return out


async def resolve_spec(symbol: str) -> inst.InstrumentSpec:
    """Spec for any symbol; crypto ticks come from the live Binance exchangeInfo, not a guess."""
    normalized = inst.normalize(symbol)
    if "/" in normalized:
        return inst.get_spec(normalized)
    try:
        universe = await load_binance_universe()
    except DataUnavailable:
        return inst.get_spec(normalized)
    for spec in universe:
        if spec.symbol == normalized:
            return spec
    raise DataUnavailable(f"نماد {normalized} در بازار SPOT بایننس فعال نیست")


async def search_universe(
    settings: Settings,
    kind: str = "all",
    query: str = "",
    limit: int = 400,
) -> dict:
    """Combined forex/metal + Binance crypto universe, filtered and annotated."""
    specs: list[inst.InstrumentSpec] = []
    crypto_error: str | None = None

    if kind in ("all", "forex", "metal"):
        for spec in inst.static_forex_universe():
            if kind == "forex" and spec.kind != inst.FOREX:
                continue
            if kind == "metal" and spec.kind != inst.METAL:
                continue
            specs.append(spec)

    if kind in ("all", "crypto"):
        try:
            specs.extend(await load_binance_universe())
        except DataUnavailable as exc:
            crypto_error = str(exc)

    needle = inst.normalize(query).replace("/", "")
    if needle:
        specs = [s for s in specs if needle in s.symbol.replace("/", "") or needle in s.base]

    total = len(specs)
    specs = specs[: max(1, min(limit, 3000))]

    return {
        "total": total,
        "returned": len(specs),
        "crypto_error": crypto_error,
        "forex_history_available": settings.has_market_key,
        "forex_history_note": (
            "تاریخچهٔ کندل فارکس از Twelve Data خوانده می‌شود."
            if settings.has_market_key
            else "کلید Twelve Data تنظیم نشده است؛ تاریخچهٔ کندل جفت‌ارزها در دسترس نیست "
                 "(کندل ساختگی ساخته نمی‌شود). کریپتوی بایننس بدون کلید کار می‌کند."
        ),
        "instruments": [inst.spec_to_dict(s) for s in specs],
    }


# ─────────────────────────────────────────────────────────────────────────────
# Candles
# ─────────────────────────────────────────────────────────────────────────────

def _parse_klines(rows: object, spec: inst.InstrumentSpec, timeframe: Timeframe) -> list[Candle]:
    if not isinstance(rows, list) or not rows:
        raise DataUnavailable("پاسخ کندل بایننس خالی یا نامعتبر است")
    now = datetime.now(timezone.utc)
    candles: list[Candle] = []
    for row in rows:
        if not isinstance(row, list) or len(row) < 7:
            raise DataUnavailable("ردیف کندل بایننس نامعتبر است")
        try:
            open_ms = int(row[0])
            close_ms = int(row[6])
            o, h, low, c, v = (float(row[1]), float(row[2]), float(row[3]), float(row[4]), float(row[5]))
        except (TypeError, ValueError):
            raise DataUnavailable("مقادیر کندل بایننس عددی نیستند") from None
        timestamp = datetime.fromtimestamp(open_ms / 1000, timezone.utc)
        # Drop the still-forming bar: the strategy is closed-bar only.
        if close_ms / 1000 > now.timestamp():
            continue
        if any(not math.isfinite(x) or x <= 0 for x in (o, h, low, c)) or v < 0:
            raise DataUnavailable("کندل بایننس دارای قیمت نامعتبر است")
        candles.append(Candle(
            symbol=spec.symbol, timeframe=timeframe, timestamp=timestamp,
            open=o, high=h, low=low, close=c, volume=v, complete=True,
        ))
    if not candles:
        raise DataUnavailable("هیچ کندل بسته‌ای از بایننس دریافت نشد")
    return _validate_candles(candles, spec.symbol, timeframe)


async def _binance_candles(spec: inst.InstrumentSpec, timeframe: Timeframe, limit: int) -> list[Candle]:
    interval = BINANCE_INTERVAL.get(timeframe)
    if not interval:
        raise DataUnavailable("بازهٔ زمانی برای بایننس پشتیبانی نمی‌شود")
    # +1 covers the forming bar we discard.
    want = min(max(limit + 1, 10), 1000)
    try:
        async with httpx.AsyncClient(timeout=25, follow_redirects=False) as client:
            response = await client.get(
                f"{BINANCE}/klines",
                params={"symbol": spec.symbol, "interval": interval, "limit": want},
            )
            if response.status_code == 400:
                raise DataUnavailable(f"بایننس نماد {spec.symbol} را نمی‌شناسد")
            response.raise_for_status()
            payload = response.json()
    except DataUnavailable:
        raise
    except Exception as exc:
        raise DataUnavailable(f"اتصال به بایننس برقرار نشد: {type(exc).__name__}") from None
    return _parse_klines(payload, spec, timeframe)


async def _forex_candles(
    settings: Settings, spec: inst.InstrumentSpec, timeframe: Timeframe, limit: int
) -> list[Candle]:
    if spec.symbol.casefold() == settings.market_symbol.casefold():
        # Reuse the existing, cached single-symbol pipeline (incl. the keyless gold fallback).
        from app.services.history import load_history
        return await load_history(settings, timeframe, output_size=limit)

    if not settings.has_market_key:
        raise DataUnavailable(
            f"تاریخچهٔ {spec.symbol} در دسترس نیست: کلید AURUM_TWELVE_DATA_API_KEY تنظیم نشده و "
            "هیچ منبع رایگانِ کندل تاریخی برای این جفت‌ارز وجود ندارد. طبق سیاست پروژه کندل "
            "ساختگی ساخته نمی‌شود. کریپتوی بایننس بدون کلید کار می‌کند."
        )

    # Twelve Data's fetcher is bound to settings.market_symbol; swap the symbol on a copy.
    scoped = settings.model_copy(update={"market_symbol": spec.symbol})
    from app.services.history import NATIVE_INTERVALS

    if timeframe is Timeframe.M3:
        base = await _twelve_data_request(
            scoped, NATIVE_INTERVALS[Timeframe.M1],
            min(limit * 3, MAX_POINTS_PER_REQUEST), None, None,
        )
        base = [c.model_copy(update={"timeframe": Timeframe.M1}) for c in base]
        return resample(base, 3, Timeframe.M3)
    return await _twelve_data_request(scoped, NATIVE_INTERVALS[timeframe], limit, None, None)


async def load_candles(
    settings: Settings,
    symbol: str,
    timeframe: Timeframe,
    limit: int = 500,
    use_cache: bool = True,
) -> tuple[list[Candle], inst.InstrumentSpec]:
    """Real closed candles for ANY supported symbol, plus the instrument spec to price them."""
    spec = await resolve_spec(symbol)
    limit = max(10, min(limit, MAX_POINTS_PER_REQUEST))
    key = f"{spec.symbol}|{timeframe.value}|{limit}"
    ttl = _CANDLE_TTL.get(timeframe, 60)

    if use_cache:
        cached = _candle_cache.get(key)
        if cached and time.time() - cached[0] < ttl:
            return cached[1], spec

    if spec.kind == inst.CRYPTO:
        candles = await _binance_candles(spec, timeframe, limit)
    else:
        candles = await _forex_candles(settings, spec, timeframe, limit)

    _candle_cache[key] = (time.time(), candles)
    if len(_candle_cache) > 400:  # bound memory; oldest-first eviction
        for stale in sorted(_candle_cache, key=lambda k: _candle_cache[k][0])[:100]:
            _candle_cache.pop(stale, None)
    return candles, spec


# ─────────────────────────────────────────────────────────────────────────────
# Live quote (spread check only — never a candle)
# ─────────────────────────────────────────────────────────────────────────────

async def live_quote(spec: inst.InstrumentSpec) -> dict | None:
    """Real bid/ask for spread validation. Returns None when no source answers."""
    try:
        async with httpx.AsyncClient(timeout=12, follow_redirects=False) as client:
            if spec.kind == inst.CRYPTO:
                response = await client.get(f"{BINANCE}/ticker/bookTicker", params={"symbol": spec.symbol})
                response.raise_for_status()
                payload = response.json()
                bid, ask = float(payload["bidPrice"]), float(payload["askPrice"])
                at = datetime.now(timezone.utc)
            else:
                response = await client.get(f"{SWISSQUOTE}/{spec.base}/{spec.quote}")
                response.raise_for_status()
                payload = response.json()
                if not isinstance(payload, list) or not payload:
                    return None
                quote = payload[0]
                profiles = quote.get("spreadProfilePrices") or []
                prime = next((p for p in profiles if p.get("spreadProfile") == "prime"), profiles[0] if profiles else None)
                if not prime:
                    return None
                bid, ask = float(prime["bid"]), float(prime["ask"])
                at = datetime.fromtimestamp(float(quote["ts"]) / 1000, timezone.utc)
    except Exception:
        return None

    if not (math.isfinite(bid) and math.isfinite(ask) and bid > 0 and ask >= bid):
        return None
    age = (datetime.now(timezone.utc) - at).total_seconds()
    if not -10 <= age <= 120:
        return None
    spread = ask - bid
    return {
        "bid": bid,
        "ask": ask,
        "mid": (bid + ask) / 2,
        "spread": spread,
        "spread_pips": round(spec.to_pips(spread), 2),
        "at": at.isoformat(),
        "source": "binance:bookTicker" if spec.kind == inst.CRYPTO else "swissquote:bbo",
    }
