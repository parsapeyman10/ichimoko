"""All AI responses are fixtures. No model key, live call or invented price is used in tests."""
from datetime import datetime, timedelta, timezone

import asyncio
from fastapi.testclient import TestClient

from app.config import Settings
from app.services.ai_news import AiNewsAnalyzer
from app.services.persian_news import Headline
from app.services.web_news import WebNewsFeed, WebSource
from app.services.forex_calendar import CALENDAR_URL


class HealthyCalendar:
    async def snapshot(self, hold_minutes=45):
        now = datetime.now(timezone.utc)
        return {"status": "online", "source": CALENDAR_URL, "checked_at": now.isoformat(),
                "events": [{"title": "Example", "country": "USD", "impact": "Low", "at": now.isoformat()}],
                "guard": {"state": "CLEAR", "reason": "test fixture", "until": None}}


def item(now: datetime, *, headline="Dollar yields fall as gold rises", offset=0) -> Headline:
    return Headline("publisher-1", headline, "US monetary policy update", "Verified Publisher",
                    "https://publisher.example/news", now + timedelta(minutes=offset),
                    {"impact": "LOW"}, "en")


def enabled() -> Settings:
    return Settings(openai_api_key="test-only-not-sent", ai_news_external_consent=True)


def test_ai_requires_explicit_permission_full_coverage_and_fresh_relevant_source(monkeypatch):
    async def run_case():
        now = datetime.now(timezone.utc)
        a = item(now)
        off = AiNewsAnalyzer(Settings(openai_api_key="key-but-no-consent", ai_news_external_consent=False))

        async def must_not_call(_):
            raise AssertionError("unconsented or incomplete RSS must never go to model")

        monkeypatch.setattr(off, "_request", must_not_call)
        assert (await off.analyze([a], "online", {"state": "CLEAR"}, now))["status"] == "UNKNOWN"
        on = AiNewsAnalyzer(enabled())
        monkeypatch.setattr(on, "_request", must_not_call)
        for source, guard, articles in [
            ("partial", {"state": "CLEAR"}, [a]),
            ("online", {"state": "BLOCKED"}, [a]),
            ("online", {"state": "UNKNOWN"}, [a]),
            ("online", {"state": "CLEAR"}, [item(now, offset=-181)]),
            ("online", {"state": "CLEAR"}, [item(now, offset=1)]),
            ("online", {"state": "CLEAR"}, [item(now, headline="Sports update")]),
        ]:
            assert (await on.analyze(articles, source, guard, now))["status"] == "UNKNOWN"

    asyncio.run(run_case())

def test_model_must_cite_real_publisher_ids_and_not_claim_ai_on_failure(monkeypatch):
    async def run_case():
        now = datetime.now(timezone.utc)
        analyzer = AiNewsAnalyzer(enabled())
        answers = [
            {"direction": "BUY", "impact": "LOW", "confidence": 93, "evidence_ids": ["made-up"], "rationale": "gold up"},
            {"direction": "BUY", "impact": "HIGH", "confidence": 93, "evidence_ids": ["publisher-1"], "rationale": "risk"},
            {"direction": "BUY", "impact": "LOW", "confidence": 79, "evidence_ids": ["publisher-1"], "rationale": "weak"},
            {"direction": "BUY", "impact": "LOW", "confidence": float("nan"), "evidence_ids": ["publisher-1"], "rationale": "bad"},
            {"direction": "SELL", "impact": "MEDIUM", "confidence": 91, "evidence_ids": ["publisher-1"], "rationale": "Dollar strength may pressure gold"},
        ]

        async def scripted(_):
            return answers.pop(0)

        monkeypatch.setattr(analyzer, "_request", scripted)
        for _ in range(4):
            verdict = await analyzer.analyze([item(now)], "online", {"state": "CLEAR"}, now)
            assert verdict["status"] == "UNKNOWN"
            assert verdict["evidence_ids"] == []
            assert verdict["model"] is None  # never disguise a keyword fallback as an LLM
            analyzer._cached_until = now  # force the next scripted model response
        valid = await analyzer.analyze([item(now)], "online", {"state": "CLEAR"}, now)
        assert valid["status"] == "AVAILABLE"
        assert valid["direction"] == "SELL"
        assert valid["evidence_ids"] == ["publisher-1"]
        assert valid["model"] == enabled().openai_model
        assert not answers
        cached = await analyzer.analyze([item(now)], "online", {"state": "CLEAR"}, now)
        assert cached == valid  # no per-refresh model billing for an unchanged short-lived snapshot

    asyncio.run(run_case())

def test_web_route_exposes_ai_result_only_when_entire_rss_pipeline_valid(monkeypatch):
    from app import main
    now = datetime.now(timezone.utc)
    feed = WebNewsFeed(enabled(), (WebSource("Verified Publisher", "https://publisher.example/rss", "en"),), HealthyCalendar())
    async def rss(_):
        return ("<rss><channel><item><title>Gold reacts to weaker dollar</title>"
                "<link>https://publisher.example/news</link>"
                f"<pubDate>{now.strftime('%a, %d %b %Y %H:%M:%S GMT')}</pubDate>"
                "</item></channel></rss>").encode()

    async def model(candidates):
        assert len(candidates) == 1
        return {"direction": "BUY", "confidence": 84, "impact": "LOW",
                "evidence_ids": [candidates[0].id], "rationale": "weaker dollar"}

    monkeypatch.setattr(feed, "_request", rss)
    monkeypatch.setattr(feed.ai, "_request", model)
    monkeypatch.setattr(main, "web_news", feed)
    payload = TestClient(main.app).get("/api/v1/news/web").json()
    assert payload["status"]["state"] == "online"
    assert payload["ai_confluence"]["status"] == "AVAILABLE"
    assert payload["ai_confluence"]["evidence_ids"] == [payload["articles"][0]["id"]]

    async def failed(_):
        raise OSError("publisher failed")

    feed._last_attempt = 0
    monkeypatch.setattr(feed, "_request", failed)
    new = TestClient(main.app).get("/api/v1/news/web").json()
    assert new["status"]["state"] == "partial"  # calendar remains online but publisher failed
    assert new["ai_confluence"]["status"] == "UNKNOWN"
    assert new["ai_confluence"]["evidence_ids"] == []
