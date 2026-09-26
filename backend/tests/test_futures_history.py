"""COMEX Gold Futures (GC=F) is a real, keyless, deep-history backtest data source — but it must
never be silently relabeled as XAU/USD spot anywhere."""
from __future__ import annotations

import asyncio
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest

BACKEND_ROOT = Path(__file__).resolve().parent.parent
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.models import Timeframe  # noqa: E402
from app.services.futures_history import SYMBOL, fetch_futures_history  # noqa: E402
from app.services.history import DataUnavailable  # noqa: E402


def _yahoo_payload(now: datetime, bars: int, step_seconds: int, drop_last_close: bool = False):
    timestamps, opens, highs, lows, closes, volumes = [], [], [], [], [], []
    price = 2650.0
    for i in range(bars):
        ts = int((now - timedelta(seconds=step_seconds * (bars - i))).timestamp())
        o, c = price, price + 0.5
        timestamps.append(ts)
        opens.append(o)
        highs.append(max(o, c) + 0.2)
        lows.append(min(o, c) - 0.2)
        closes.append(c if not (drop_last_close and i == bars - 1) else None)
        volumes.append(100.0)
        price = c
    return {
        "chart": {
            "result": [{
                "timestamp": timestamps,
                "indicators": {"quote": [{"open": opens, "high": highs, "low": lows, "close": closes, "volume": volumes}]},
            }],
            "error": None,
        }
    }


def _client(handler):
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def test_fetch_futures_history_parses_real_yahoo_shaped_payload_and_labels_gc_f():
    now = datetime.now(timezone.utc)
    payload = _yahoo_payload(now, 30, 300)

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    async def run():
        async with _client(handler) as client:
            return await fetch_futures_history(client, Timeframe.M5, limit=1500)

    candles = asyncio.run(run())
    assert len(candles) >= 2
    # It must never claim to be spot XAU/USD.
    assert all(c.symbol == SYMBOL for c in candles)
    assert all(c.timeframe is Timeframe.M5 for c in candles)


def test_fetch_futures_history_skips_illiquid_null_bars_without_interpolating():
    now = datetime.now(timezone.utc)
    payload = _yahoo_payload(now, 10, 300, drop_last_close=True)

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    async def run():
        async with _client(handler) as client:
            return await fetch_futures_history(client, Timeframe.M5, limit=1500)

    candles = asyncio.run(run())
    assert len(candles) == 9  # the null-close bar is dropped, never invented


def test_fetch_futures_history_raises_on_malformed_response():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"chart": {"result": None, "error": {"code": "Not Found"}}})

    async def run():
        async with _client(handler) as client:
            return await fetch_futures_history(client, Timeframe.M5)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


def test_fetch_futures_history_raises_on_http_error():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    async def run():
        async with _client(handler) as client:
            return await fetch_futures_history(client, Timeframe.M5)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


def test_fetch_futures_history_rejects_unsupported_timeframe():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("should not be called for an unsupported timeframe")

    async def run():
        async with _client(handler) as client:
            return await fetch_futures_history(client, Timeframe.M3)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


def test_fetch_futures_history_resamples_h1_into_h4_without_relabeling_symbol():
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    payload = _yahoo_payload(now, 16, 3600)

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params.get("interval") == "60m"
        return httpx.Response(200, json=payload)

    async def run():
        async with _client(handler) as client:
            return await fetch_futures_history(client, Timeframe.H4, limit=1500)

    candles = asyncio.run(run())
    assert len(candles) >= 2
    assert all(c.timeframe is Timeframe.H4 for c in candles)
    assert all(c.symbol == SYMBOL for c in candles)
