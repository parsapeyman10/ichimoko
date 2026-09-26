from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app.config import Settings
from app.services.persian_news import parse_news_xml
from app.services.web_news import WebNewsFeed, WebSource
from app.services.forex_calendar import CALENDAR_URL


class HealthyCalendar:
    async def snapshot(self, hold_minutes=45):
        now = datetime.now(timezone.utc)
        return {"status": "online", "source": CALENDAR_URL, "checked_at": now.isoformat(),
                "events": [{"title": "Example", "country": "USD", "impact": "Low", "at": now.isoformat()}],
                "guard": {"state": "CLEAR", "reason": "test fixture", "until": None}}


def rss(title: str, host: str, time: datetime, summary: str = "متن کوتاه") -> bytes:
    return (f"<rss><channel><item><title>{title}</title><description>{summary}</description>"
            f"<link>https://{host}/story</link><pubDate>{time.strftime('%a, %d %b %Y %H:%M:%S GMT')}"
            "</pubDate></item></channel></rss>").encode()


def test_multilingual_feed_parsing_keeps_attribution_and_safe_links():
    now = datetime.now(timezone.utc)
    xml = rss("CPI inflation update", "example.com", now, "x" * 500)
    article = parse_news_xml(xml, "Publisher", language="en", allowed_host="example.com")[0]
    assert article.payload()["language"] == "en"
    assert article.payload()["url"] == "https://example.com/story"
    assert len(article.summary) == 280
    assert not parse_news_xml(xml, "Publisher", language="fa")
    assert parse_news_xml(xml, "Publisher", language="en", allowed_host="elsewhere.com")[0].url is None
    evil = b'<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&xxe;</title></item></channel></rss>'
    try:
        parse_news_xml(evil, "Publisher", language="en")
    except Exception:
        pass  # cannot expand the entity
    else:
        raise AssertionError("external entities must be rejected")


def test_web_news_route_partial_failure_does_not_clear_guard(monkeypatch):
    from app import main
    now = datetime.now(timezone.utc)
    a = WebSource("Verified Publisher", "https://example.com/rss", "en")
    b = WebSource("Other Publisher", "https://other.com/rss", "fa")
    feed = WebNewsFeed(Settings(), (a, b), HealthyCalendar())

    async def request(source):
        if source == b:
            raise OSError("no unauthorized fallback")
        return rss("Markets update", "example.com", now)

    monkeypatch.setattr(feed, "_request", request)
    monkeypatch.setattr(main, "web_news", feed)
    client = TestClient(main.app)
    payload = client.get("/api/v1/news/web").json()
    assert payload["status"]["state"] == "partial"
    assert payload["guard"]["state"] == "UNKNOWN"
    assert len(payload["articles"]) == 1
    assert payload["articles"][0]["source"] == "Verified Publisher"
    assert payload["status"]["sources"][1]["state"] == "unavailable"
    assert "unauthorized" not in str(payload)

    async def both(source):
        return rss("CPI inflation release" if source == a else "نرخ تورم آمریکا", source.host, now)

    feed._last_attempt = 0
    monkeypatch.setattr(feed, "_request", both)
    complete = client.get("/api/v1/news/web").json()
    assert complete["status"]["state"] == "online"
    assert complete["guard"]["state"] == "BLOCKED"
    assert len(complete["articles"]) == 2

    async def failure(source):
        raise OSError("unavailable")

    feed._last_attempt = 0
    monkeypatch.setattr(feed, "_request", failure)
    offline = client.get("/api/v1/news/web").json()
    assert offline["status"]["state"] == "partial"  # calendar works but RSS does not
    assert offline["articles"] == []  # previous successful news is not returned as current
    assert offline["guard"]["state"] == "UNKNOWN"


def test_feed_with_wrong_host_or_stale_date_is_not_an_approved_source(monkeypatch):
    from app import main
    now = datetime.now(timezone.utc)
    source = WebSource("Verified Publisher", "https://example.com/rss", "en")
    feed = WebNewsFeed(Settings(), (source,), HealthyCalendar())

    async def bad(_):
        return rss("Markets update", "attacker.example.com", now)

    monkeypatch.setattr(feed, "_request", bad)
    monkeypatch.setattr(main, "web_news", feed)
    payload = TestClient(main.app).get("/api/v1/news/web").json()
    assert payload["status"]["state"] == "partial"  # calendar works but publisher is invalid
    assert payload["articles"] == []

    async def old(_):
        return rss("CPI inflation release", "example.com", now - timedelta(days=7))

    feed._last_attempt = 0
    monkeypatch.setattr(feed, "_request", old)
    older = TestClient(main.app).get("/api/v1/news/web").json()
    assert older["status"]["state"] == "partial"
    assert older["guard"]["state"] == "UNKNOWN"  # stale publisher ≠ no major news
    assert older["articles"] == []

    async def monthly(_):
        return rss("CPI inflation release", "example.com", now - timedelta(days=12))

    feed = WebNewsFeed(Settings(), (WebSource("BLS", "https://example.com/rss", "en", max_age_hours=45 * 24),), HealthyCalendar())
    monkeypatch.setattr(feed, "_request", monthly)
    monkeypatch.setattr(main, "web_news", feed)
    monthly_report = TestClient(main.app).get("/api/v1/news/web").json()
    assert monthly_report["status"]["state"] == "online"
    assert monthly_report["guard"]["state"] == "CLEAR"  # official feed and calendar checked
