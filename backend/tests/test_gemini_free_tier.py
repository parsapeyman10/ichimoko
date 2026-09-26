"""Gemini REST integration uses local MockTransport. No live key, charge, or invented provider result."""
import asyncio
import json
from datetime import datetime, timezone

import httpx

from app.config import Settings
from app.services.ai_news import AiNewsAnalyzer
from app.services.persian_news import Headline


def article(now):
    return Headline("publisher-42", "USD yields and gold update", "Fed policy report",
                    "Publisher", "https://publisher.example/news", now,
                    {"impact": "LOW"}, "en")


def envelope(text, finish="STOP"):
    return {"candidates": [{"finishReason": finish, "content": {"parts": [{"text": json.dumps(text)}]}}]}


def test_free_tier_request_contract_and_model_verdict_are_evidence_bound():
    async def scenario():
        now = datetime.now(timezone.utc)
        calls = []

        def handler(request):
            calls.append(request)
            assert request.method == "POST"
            assert request.url.host == "generativelanguage.googleapis.com"
            assert request.url.path == "/v1beta/models/gemini-2.5-flash-lite:generateContent"
            assert not request.url.query  # secret must never enter URL or logs
            assert request.headers["x-goog-api-key"] == "test-private-key"
            body = json.loads(request.content)
            assert body["generationConfig"]["responseMimeType"] == "application/json"
            assert body["generationConfig"]["thinkingConfig"]["thinkingBudget"] == 0
            assert body["generationConfig"]["responseJsonSchema"]["required"] == [
                "direction", "impact", "confidence", "evidence_ids", "rationale"]
            inputs = json.loads(body["contents"][0]["parts"][0]["text"])
            assert [row["id"] for row in inputs] == ["publisher-42"]
            return httpx.Response(200, json=envelope({"direction": "SELL", "impact": "LOW",
                "confidence": 91, "evidence_ids": ["publisher-42"], "rationale": "stronger dollar"}))

        settings = Settings(gemini_api_key="test-private-key", openai_api_key="not-used",
                            ai_news_external_consent=True)
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            analyzer = AiNewsAnalyzer(settings, gemini_http=http)
            verdict = await analyzer.analyze([article(now)], "online", {"state": "CLEAR"}, now)
            assert verdict["status"] == "AVAILABLE"
            assert verdict["model"] == "gemini-2.5-flash-lite"
            assert verdict["evidence_ids"] == ["publisher-42"]
            assert len(calls) == 1
            assert await analyzer.analyze([article(now)], "online", {"state": "CLEAR"}, now) == verdict
            assert len(calls) == 1  # no extra paid/free request on same snapshot
    asyncio.run(scenario())


def test_missing_permission_or_bad_response_never_unlocks_ninth_confluence():
    async def scenario():
        now = datetime.now(timezone.utc)
        responses = [
            httpx.Response(429, json={"error": "quota", "private": "must not leak"}),
            httpx.Response(200, json=envelope({"direction": "BUY", "impact": "LOW",
                "confidence": 99, "evidence_ids": ["made-up"], "rationale": "invented"})),
            httpx.Response(200, json=envelope({"direction": "BUY", "impact": "LOW",
                "confidence": 99, "evidence_ids": ["publisher-42"], "rationale": "blocked"}, "SAFETY")),
            httpx.Response(200, content=b"x" * 16_001),
        ]
        async def handler(_request):
            return responses.pop(0)
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            no_consent = AiNewsAnalyzer(Settings(gemini_api_key="not-sent", ai_news_external_consent=False), http)
            verdict = await no_consent.analyze([article(now)], "online", {"state": "CLEAR"}, now)
            assert verdict["status"] == "UNKNOWN" and len(responses) == 4
            for _ in range(4):
                analyzer = AiNewsAnalyzer(Settings(gemini_api_key="test-private-key",
                    ai_news_external_consent=True), http)
                result = await analyzer.analyze([article(now)], "online", {"state": "CLEAR"}, now)
                assert result["status"] == "UNKNOWN"
                assert result["model"] is None and result["evidence_ids"] == []
                assert "private" not in str(result) and "test-private-key" not in str(result)
            assert not responses
    asyncio.run(scenario())
