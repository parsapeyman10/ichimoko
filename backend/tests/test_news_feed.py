import asyncio

import httpx

from app.config import Settings
from app.services import news_feed
from app.services.news_feed import NewsAggregator


def _patch_client(monkeypatch, payload=None, status=200):
    real_client = httpx.AsyncClient

    def handler(request):
        # The key is allowed in the provider request contract, but it must never return in an
        # exception/status payload.
        assert request.url.params["apikey"] == "private-test-key"
        return httpx.Response(status, json=payload if payload is not None else {})

    monkeypatch.setattr(
        news_feed.httpx,
        "AsyncClient",
        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs),
    )


def test_news_provider_error_is_sanitized(monkeypatch):
    _patch_client(monkeypatch, {"message": "private-test-key"}, status=403)
    aggregator = NewsAggregator(Settings(_env_file=None, fmp_api_key=" private-test-key "))

    assert asyncio.run(aggregator.fetch()) == []
    assert "private-test-key" not in (aggregator.last_error or "")
    assert "HTTP 403" in (aggregator.last_error or "")


def test_provider_timestamp_is_unknown_when_missing_or_invalid(monkeypatch):
    _patch_client(monkeypatch, [
        {"title": "Gold market update", "text": "provider text", "site": "FMP"},
        {"title": "Dollar market update", "text": "provider text", "site": "FMP", "publishedDate": "bad"},
    ])
    aggregator = NewsAggregator(Settings(_env_file=None, fmp_api_key="private-test-key"))

    articles = asyncio.run(aggregator.fetch())
    assert len(articles) == 2
    assert all(article.published_at is None for article in articles)


def test_malformed_provider_rows_are_skipped_without_inventing_headlines(monkeypatch):
    _patch_client(monkeypatch, [None, {"title": 12}, {"title": "Valid market update", "text": 3}])
    aggregator = NewsAggregator(Settings(_env_file=None, fmp_api_key="private-test-key"))

    articles = asyncio.run(aggregator.fetch())
    assert [article.headline for article in articles] == ["Valid market update"]
    assert articles[0].body == ""


def test_legacy_sentiment_respects_external_text_consent(monkeypatch):
    from app.models import NewsRequest
    from app.services.sentiment import SentimentEngine

    async def scenario():
        engine = SentimentEngine(Settings(
            _env_file=None,
            openai_api_key="private-test-key",
            ai_news_external_consent=False,
        ))

        async def forbidden(_news):
            raise AssertionError("external model call was not consented")

        monkeypatch.setattr(engine, "_analyze_openai", forbidden)
        result = await engine.analyze(NewsRequest(headline="Gold market update"))
        assert result.source == "deterministic-fallback"

    asyncio.run(scenario())
