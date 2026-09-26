"""Recorded schema fixtures; no live news or prediction is substituted in tests."""
import asyncio
import json
from datetime import datetime, timedelta, timezone

import pytest

from app.config import Settings
from app.services.forex_calendar import ForexCalendarFeed, calendar_guard, parse_calendar
from app.services.web_news import WebNewsFeed, WebSource

NOW = datetime(2026, 9, 24, 12, 0, tzinfo=timezone.utc)


def payload(now=NOW):
    return json.dumps([
        {"title": "Non-Farm Payrolls", "country": "USD", "date": (now + timedelta(minutes=20)).isoformat(),
         "impact": "High", "forecast": "120K", "previous": "90K"},
        {"title": "ECB Interest Rate", "country": "EUR", "date": now.isoformat(),
         "impact": "High", "forecast": "", "previous": ""},
        {"title": "US minor report", "country": "USD", "date": now.isoformat(),
         "impact": "Low", "forecast": "", "previous": ""},
    ]).encode()


def test_only_high_usd_with_verified_offset_blocks_a_new_gold_entry():
    events = parse_calendar(payload(), NOW)
    assert all(item["at"].endswith("+00:00") for item in events)
    assert calendar_guard(events, True, NOW)["state"] == "BLOCKED"  # 30m BEFORE release
    assert calendar_guard(events, True, NOW + timedelta(minutes=64))["state"] == "BLOCKED"
    assert calendar_guard(events, True, NOW + timedelta(minutes=66))["state"] == "CLEAR"
    assert calendar_guard(events, False, NOW)["state"] == "UNKNOWN"
    chf_only = [item for item in events if item["country"] == "EUR"]
    assert calendar_guard(chf_only, True, NOW)["state"] == "CLEAR"


def test_malformed_or_future_calendar_never_becomes_clear():
    with pytest.raises(ValueError):
        parse_calendar(b'[{"title":"High"}]', NOW)
    for change in [b'"date": "2030-01-01T00:00:00-04:00"', b'"date": "2026-09-24T08:20:00"']:
        raw = payload().replace(b'"date": "2026-09-24T12:20:00+00:00"', change)
        with pytest.raises(ValueError):
            parse_calendar(raw, NOW)
    with pytest.raises(ValueError):
        parse_calendar(payload() + b" " * 512_001, NOW)


def test_feed_failure_expires_evidence_without_stale_clear(monkeypatch):
    async def verify():
        feed = ForexCalendarFeed()

        async def success():
            return payload()

        monkeypatch.setattr(feed, "_request", success)
        good = await feed.snapshot(NOW)
        assert good["status"] == "online"
        assert good["guard"]["state"] == "BLOCKED"
        feed._last_success = NOW - timedelta(minutes=21)
        assert (await feed.snapshot(NOW))["status"] == "unavailable"

        async def fail():
            raise OSError("calendar disconnected")

        feed._last_attempt = 0
        monkeypatch.setattr(feed, "_request", fail)
        bad = await feed.snapshot(NOW)
        assert bad["events"] == []
        assert bad["guard"]["state"] == "UNKNOWN"
    asyncio.run(verify())


def test_rss_clear_never_overrides_missing_or_high_calendar(monkeypatch):
    async def verify():
        feed = WebNewsFeed(Settings(), (WebSource("Publisher", "https://source.example/rss", "en"),))

        async def rss(_):
            return (f'<rss><channel><item><title>Markets update</title>'
                    f'<link>https://source.example/story</link>'
                    f'<pubDate>{datetime.now(timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")}</pubDate>'
                    f'</item></channel></rss>').encode()

        async def calendar_missing(hold_minutes=45):
            return {"status": "unavailable", "events": [], "guard": {"state": "UNKNOWN"}}

        monkeypatch.setattr(feed, "_request", rss)
        monkeypatch.setattr(feed.calendar, "snapshot", calendar_missing)
        result = await feed.snapshot()
        assert result["status"]["state"] == "partial"
        assert result["guard"]["state"] == "UNKNOWN"
        assert result["ai_confluence"]["status"] == "UNKNOWN"

        async def calendar_high(hold_minutes=45):
            return {"status": "online", "events": [{"title": "NFP", "impact": "High", "country": "USD"}],
                    "guard": {"state": "BLOCKED", "reason": "high", "until": None}}

        monkeypatch.setattr(feed.calendar, "snapshot", calendar_high)
        veto = await feed.snapshot()
        assert veto["guard"]["state"] == "BLOCKED"
        assert veto["ai_confluence"]["status"] == "UNKNOWN"
    asyncio.run(verify())
