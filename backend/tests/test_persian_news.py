from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.services.persian_news import PersianNewsFeed, news_guard, parse_news_xml, validated_feed_url


NOW = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)


def rss(title: str, published: str | None = None) -> bytes:
    date = f"<pubDate>{published}</pubDate>" if published else ""
    return ("<rss><channel><item><title>" + title + "</title>" + date +
            "<link>https://news.example.org/story</link><description>متن خبر</description></item></channel></rss>").encode()


def test_feed_must_be_licensed_https_and_host_allowlisted():
    assert validated_feed_url(Settings(fa_news_rss_url=None, fa_news_allowed_host=None)) is None
    with pytest.raises(ValueError):
        validated_feed_url(Settings(fa_news_rss_url="http://news.example.org/feed", fa_news_allowed_host="news.example.org"))
    with pytest.raises(ValueError):
        validated_feed_url(Settings(fa_news_rss_url="https://127.0.0.1/feed", fa_news_allowed_host="127.0.0.1"))
    with pytest.raises(ValueError):
        validated_feed_url(Settings(fa_news_rss_url="https://news.example.org/feed", fa_news_allowed_host="other.example.org"))
    assert validated_feed_url(Settings(fa_news_rss_url="https://news.example.org/feed", fa_news_allowed_host="news.example.org"))


def test_real_persian_feed_parsing_and_impact_guard():
    articles = parse_news_xml(rss("تورم آمریکا", "Wed, 23 Sep 2026 11:50:00 GMT"), "مجاز")
    assert len(articles) == 1
    article = articles[0]
    assert article.headline == "تورم آمریکا"
    assert article.source == "مجاز"
    assert article.url == "https://news.example.org/story"
    assert article.published_at == NOW - timedelta(minutes=10)
    assert article.analysis["impact"] == "HIGH"
    guard = news_guard(articles, online=True, now=NOW)
    assert guard["state"] == "BLOCKED"
    assert guard["until"] == (NOW + timedelta(minutes=35)).isoformat()
    assert news_guard(articles, online=False, now=NOW)["state"] == "UNKNOWN"
    assert news_guard(articles, online=True, now=NOW + timedelta(hours=1))["state"] == "CLEAR"


def test_missing_date_and_unconfigured_news_fail_closed():
    articles = parse_news_xml(rss("خبر عادی بازار"), "مجاز")
    assert articles[0].published_at is None  # never substitute the current time
    assert news_guard(articles, online=True, now=NOW)["state"] == "UNKNOWN"
    assert news_guard([], online=True, now=NOW)["state"] == "UNKNOWN"
    assert not parse_news_xml(rss("US inflation data"), "مجاز")


def test_news_route_reports_real_feed_or_explicit_unavailability(monkeypatch):
    from app import main

    feed = PersianNewsFeed(Settings(
        fa_news_rss_url="https://news.example.org/rss", fa_news_allowed_host="news.example.org", fa_news_source="مجاز",
    ))

    async def stub(_url):
        return rss("تورم آمریکا", datetime.now(timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT"))

    monkeypatch.setattr(feed, "_request", stub)
    monkeypatch.setattr(main, "persian_news", feed)
    client = TestClient(main.app)
    response = client.get("/api/v1/news/fa")
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"]["state"] == "online"
    assert payload["guard"]["state"] == "BLOCKED"
    assert payload["articles"][0]["headline"] == "تورم آمریکا"
    assert "checked_at" in payload

    async def failed(_url):
        raise OSError("do not expose query-string keys in errors")

    feed._last_attempt = 0
    monkeypatch.setattr(feed, "_request", failed)
    degraded = client.get("/api/v1/news/fa").json()
    assert degraded["status"]["cached"] is True
    assert degraded["guard"]["state"] == "UNKNOWN"
    assert "query-string" not in degraded["status"]["error"]

    monkeypatch.setattr(main, "persian_news", PersianNewsFeed(Settings(fa_news_rss_url=None, fa_news_allowed_host=None)))
    empty = client.get("/api/v1/news/fa").json()
    assert empty["articles"] == [] and empty["guard"]["state"] == "UNKNOWN"
