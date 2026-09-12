"""
Real market history — Twelve Data REST only.

There is no synthetic generator, no anchor interpolation and no Monte-Carlo price path
anywhere in this module. If the provider cannot be reached, or no API key is configured,
the caller gets [DataUnavailable] and the API returns an explicit error. Inventing a
candle to keep the UI alive is never an option.
"""
from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from app.config import Settings
from app.models import Candle, Timeframe

CACHE_DIR = Path(__file__).resolve().parent.parent / "data" / "cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

# Twelve Data supports these native intervals. 3m is built by aggregating real 1m bars.
NATIVE_INTERVALS: dict[Timeframe, str] = {
    Timeframe.M1: "1min",
    Timeframe.M5: "5min",
    Timeframe.M15: "15min",
    Timeframe.H1: "1h",
    Timeframe.H4: "4h",
    Timeframe.D1: "1day",
}

# How long a fetched series may be reused (protects the provider credit budget).
TTL_SECONDS: dict[Timeframe, int] = {
    Timeframe.M1: 30,
    Timeframe.M3: 60,
    Timeframe.M5: 60,
    Timeframe.M15: 120,
    Timeframe.H1: 600,
    Timeframe.H4: 1800,
    Timeframe.D1: 1800,
}

MAX_POINTS_PER_REQUEST = 5000
_memory: dict[str, tuple[float, list[Candle]]] = {}


class DataUnavailable(RuntimeError):
    """Raised whenever real data cannot be delivered. Never replaced by generated data."""


def _cache_key(symbol: str, timeframe: Timeframe, output_size: int, start: str | None, end: str | None) -> str:
    return f"{symbol.replace('/', '_')}_{timeframe.value}_{output_size}_{start or 'live'}_{end or 'live'}"


def _cache_path(key: str) -> Path:
    return CACHE_DIR / f"{key}.json"


def _serialize(candles: list[Candle]) -> str:
    return json.dumps(
        [
            {
                "symbol": c.symbol,
                "timeframe": c.timeframe.value,
                "timestamp": c.timestamp.isoformat(),
                "open": c.open,
                "high": c.high,
                "low": c.low,
                "close": c.close,
                "volume": c.volume,
                "complete": c.complete,
            }
            for c in candles
        ]
    )


def _deserialize(raw: str, timeframe: Timeframe) -> list[Candle]:
    out: list[Candle] = []
    for item in json.loads(raw):
        out.append(
            Candle(
                symbol=item["symbol"],
                timeframe=timeframe,
                timestamp=datetime.fromisoformat(item["timestamp"]),
                open=item["open"],
                high=item["high"],
                low=item["low"],
                close=item["close"],
                volume=item.get("volume", 0),
                complete=item.get("complete", True),
            )
        )
    return out


def _read_disk(key: str, timeframe: Timeframe, ttl: int) -> list[Candle] | None:
    path = _cache_path(key)
    if not path.exists():
        return None
    if time.time() - path.stat().st_mtime > ttl:
        return None
    try:
        return _deserialize(path.read_text(), timeframe)
    except Exception:
        return None


def _write_disk(key: str, candles: list[Candle]) -> None:
    try:
        _cache_path(key).write_text(_serialize(candles))
    except Exception:
        pass


def resample(candles: list[Candle], minutes: int, timeframe: Timeframe) -> list[Candle]:
    """Aggregate real candles into a higher timeframe (real bars, just bucketed)."""
    buckets: dict[int, list[Candle]] = {}
    span = minutes * 60
    for candle in candles:
        bucket = int(candle.timestamp.timestamp()) // span * span
        buckets.setdefault(bucket, []).append(candle)
    out: list[Candle] = []
    for bucket in sorted(buckets):
        group = buckets[bucket]
        out.append(
            Candle(
                symbol=group[0].symbol,
                timeframe=timeframe,
                timestamp=datetime.fromtimestamp(bucket, timezone.utc),
                open=group[0].open,
                high=max(c.high for c in group),
                low=min(c.low for c in group),
                close=group[-1].close,
                volume=sum(c.volume for c in group),
                complete=len(group) >= minutes,
            )
        )
    return out


async def _request(settings: Settings, interval: str, output_size: int, start: str | None, end: str | None) -> list[Candle]:
    if not settings.twelve_data_api_key:
        raise DataUnavailable(
            "AURUM_TWELVE_DATA_API_KEY تنظیم نشده است — بدون کلید، هیچ داده‌ای ساخته نمی‌شود"
        )
    params = {
        "symbol": settings.market_symbol,
        "interval": interval,
        "outputsize": min(max(output_size, 10), MAX_POINTS_PER_REQUEST),
        "order": "ASC",
        "timezone": "UTC",
        "apikey": settings.twelve_data_api_key,
    }
    if start:
        params["start_date"] = start
    if end:
        params["end_date"] = end
    try:
        async with httpx.AsyncClient(timeout=25) as client:
            response = await client.get("https://api.twelvedata.com/time_series", params=params)
            payload = response.json()
    except Exception as exc:  # network / parse failure
        raise DataUnavailable(f"اتصال به Twelve Data برقرار نشد: {type(exc).__name__}") from exc

    status = payload.get("status")
    if status == "error" or "code" in payload and status is None and "values" not in payload:
        code = str(payload.get("code", ""))
        message = payload.get("message", "خطای سرویس‌دهنده")
        if code in ("401", "403"):
            raise DataUnavailable("کلید Twelve Data نامعتبر یا غیرفعال است")
        if code == "429":
            raise DataUnavailable("سهمیه درخواست Twelve Data تمام شده است (محدودیت پلن)")
        raise DataUnavailable(message)
    values = payload.get("values") or []
    if not values:
        raise DataUnavailable("سرویس‌دهنده هیچ کندلی برای این نماد/تایم‌فریم برنگرداند")

    timeframe = _timeframe_for_interval(interval)
    candles: list[Candle] = []
    for item in values:
        try:
            candles.append(
                Candle(
                    symbol=settings.market_symbol,
                    timeframe=timeframe,
                    timestamp=_parse_datetime(item["datetime"]),
                    open=float(item["open"]),
                    high=float(item["high"]),
                    low=float(item["low"]),
                    close=float(item["close"]),
                    volume=float(item.get("volume") or 0),
                    complete=True,
                )
            )
        except Exception:
            continue
    if not candles:
        raise DataUnavailable("کندل قابل‌استفاده‌ای در پاسخ سرویس‌دهنده نبود")
    candles.sort(key=lambda c: c.timestamp)
    return candles


def _timeframe_for_interval(interval: str) -> Timeframe:
    for timeframe, native in NATIVE_INTERVALS.items():
        if native == interval:
            return timeframe
    return Timeframe.M1


def _parse_datetime(raw: str) -> datetime:
    cleaned = raw.strip().replace("T", " ").removesuffix("Z")
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d %H", "%Y-%m-%d"):
        try:
            return datetime.strptime(cleaned[: len(fmt)], fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    raise ValueError(f"unparsable datetime: {raw}")


async def load_history(
    settings: Settings,
    timeframe: Timeframe,
    output_size: int = 1500,
    start_date: str | None = None,
    end_date: str | None = None,
    use_cache: bool = True,
) -> list[Candle]:
    """Return real candles for [timeframe]. Raises [DataUnavailable] on any failure."""
    ttl = TTL_SECONDS.get(timeframe, 60)
    key = _cache_key(settings.market_symbol, timeframe, output_size, start_date, end_date)

    if use_cache:
        cached = _memory.get(key)
        if cached and time.time() - cached[0] < ttl:
            return cached[1]
        disk = _read_disk(key, timeframe, ttl)
        if disk:
            _memory[key] = (time.time(), disk)
            return disk

    if timeframe is Timeframe.M3:
        # No native 3-minute interval: aggregate real 1-minute bars.
        base = await _request(settings, NATIVE_INTERVALS[Timeframe.M1], min(output_size * 3, MAX_POINTS_PER_REQUEST), start_date, end_date)
        base = [c.model_copy(update={"timeframe": Timeframe.M1}) for c in base]
        candles = resample(base, 3, Timeframe.M3)
    else:
        candles = await _request(settings, NATIVE_INTERVALS[timeframe], output_size, start_date, end_date)

    if not candles:
        raise DataUnavailable("داده واقعی در دسترس نیست")
    _memory[key] = (time.time(), candles)
    _write_disk(key, candles)
    return candles


def cached_history(timeframe: Timeframe, output_size: int = 1500) -> list[Candle] | None:
    """Last fetched real series from disk, if still present (used only to report state)."""
    for path in CACHE_DIR.glob(f"*_{timeframe.value}_{output_size}_*.json"):
        try:
            return _deserialize(path.read_text(), timeframe)
        except Exception:
            continue
    return None
