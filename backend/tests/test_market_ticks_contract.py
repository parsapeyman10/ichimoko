"""Streaming and REST fallback must preserve the provider's symbol, timestamp and provenance."""
import asyncio
import json
from datetime import datetime, timedelta, timezone

import pytest

from app.config import Settings
from app.models import Candle, Timeframe
from app.services import market_feed
from app.services.history import DataUnavailable

SETTINGS = Settings(twelve_data_api_key="test-only-placeholder", market_symbol="XAU/USD")


class FakeSocket:
    def __init__(self, messages):
        self.messages = messages

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return None

    async def send(self, payload):
        assert "subscribe" in payload or "heartbeat" in payload

    async def __aiter__(self):
        for message in self.messages:
            yield json.dumps(message)


def test_websocket_ignores_unverified_quotes_without_inventing_update_time(monkeypatch):
    now = datetime.now(timezone.utc)
    stamp = int(now.timestamp())
    messages = [
        {"event": "price", "symbol": "BTC/USD", "price": "3100", "timestamp": stamp},
        {"event": "price", "symbol": "XAU/USD", "price": "3100"},
        {"event": "price", "symbol": "XAU/USD", "price": "3100", "timestamp": stamp - 600},
        {"event": "price", "symbol": "XAU/USD", "price": "NaN", "timestamp": stamp},
        {"event": "price", "symbol": "XAU/USD", "price": "3100", "timestamp": stamp},
    ]
    monkeypatch.setattr(market_feed.websockets, "connect", lambda *a, **kw: FakeSocket(messages))

    async def read():
        return [tick async for tick in market_feed.twelve_data_ticks(SETTINGS)]

    ticks = asyncio.run(read())
    assert len(ticks) == 1
    assert ticks[0].symbol == "XAU/USD" and ticks[0].timestamp == datetime.fromtimestamp(stamp, timezone.utc)
    assert ticks[0].provider == "twelve_data:ws"


def test_websocket_provider_error_must_not_reflect_api_key(monkeypatch):
    monkeypatch.setattr(market_feed.websockets, "connect", lambda *a, **kw: FakeSocket([
        {"event": "error", "message": "test-only-placeholder"}
    ]))

    async def read():
        return [tick async for tick in market_feed.twelve_data_ticks(SETTINGS)]

    with pytest.raises(DataUnavailable) as exc:
        asyncio.run(read())
    assert "test-only-placeholder" not in str(exc.value)


def test_rest_poll_does_not_emit_delayed_history_as_live_tick(monkeypatch):
    async def load(*args, **kwargs):
        return [Candle(symbol="XAU/USD", timeframe=Timeframe.M1,
                       timestamp=datetime.now(timezone.utc) - timedelta(days=1),
                       open=3100, high=3101, low=3099, close=3100)]

    monkeypatch.setattr(market_feed, "load_history", load)

    async def read():
        stream = market_feed.twelve_data_poll_ticks(SETTINGS)
        try:
            await asyncio.wait_for(anext(stream), timeout=0.15)
        finally:
            await stream.aclose()

    with pytest.raises(DataUnavailable):
        asyncio.run(read())
