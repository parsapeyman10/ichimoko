"""The public feed state must not stay live after quotes stop or REST replaces WS."""
import asyncio
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app import main
from app.models import Tick


def test_public_feed_state_expires_without_new_ticks(monkeypatch):
    hub = main.MarketHub()
    monkeypatch.setattr(main, "hub", hub)
    now = datetime.now(timezone.utc)
    hub.feed_status.update({"state": "live", "last_tick_at": now - timedelta(minutes=3)})
    assert hub.public_feed_status(now)["state"] == "stale"
    assert "تیک" in hub.public_feed_status(now)["detail"]
    assert TestClient(main.app).get("/api/v1/data/status").json()["feed"]["state"] == "stale"
    hub.feed_status["last_tick_at"] = now
    assert hub.public_feed_status(now)["state"] == "live"


def test_rest_tick_is_polling_not_streaming_and_errors_do_not_echo_key(monkeypatch):
    hub = main.MarketHub()
    monkeypatch.setattr(main, "hub", hub)
    notifications = hub.subscribe()

    async def source(_settings):
        yield Tick(symbol="XAU/USD", timestamp=datetime.now(timezone.utc), bid=3100, ask=3100,
                   provider="twelve_data:rest")
        raise RuntimeError("http://provider.invalid/?apikey=test-only-placeholder")

    async def interrupt(_seconds):
        raise asyncio.CancelledError

    monkeypatch.setattr(main, "market_ticks", source)
    monkeypatch.setattr(main.asyncio, "sleep", interrupt)
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main.run_market_pipeline())
    assert hub.feed_status["state"] == "error"
    assert "test-only-placeholder" not in hub.feed_status["detail"]
    assert hub.last_tick is not None and hub.last_tick["provider"] == "twelve_data:rest"
    messages = []
    while not notifications.empty():
        messages.append(notifications.get_nowait())
    assert any(msg.get("type") == "feed.status" and msg.get("status") == "polling" for msg in messages)
