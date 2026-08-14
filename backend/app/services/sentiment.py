import asyncio
import json
import re
from typing import Any
from app.config import Settings
from app.models import Direction, Impact, NewsRequest, SentimentResult

HIGH_IMPACT = {"fomc", "federal reserve", "powell", "nfp", "nonfarm", "cpi", "inflation", "rate decision", "war", "tariff"}
MEDIUM_IMPACT = {"pce", "ppi", "retail sales", "jobless", "yield", "dxy", "dollar", "central bank", "geopolitical"}
BULLISH_GOLD = {"rate cut", "dovish", "weak dollar", "yields fall", "recession", "safe haven", "ceasefire fails", "central bank buying"}
BEARISH_GOLD = {"rate hike", "hawkish", "strong dollar", "yields rise", "hot inflation", "risk-on", "peace deal", "gold outflow"}


class SentimentEngine:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._finbert: Any = None

    async def analyze(self, news: NewsRequest) -> SentimentResult:
        if self.settings.openai_api_key:
            try:
                return await self._analyze_openai(news)
            except Exception:
                # The service remains available; production emits a metric and DLQ event here.
                pass
        return await asyncio.to_thread(self._analyze_rules, news)

    async def _analyze_openai(self, news: NewsRequest) -> SentimentResult:
        from openai import AsyncOpenAI
        client = AsyncOpenAI(api_key=self.settings.openai_api_key, timeout=5, max_retries=1)
        prompt = f"""You classify immediate XAU/USD price impact, not general article tone.
Return JSON only: {{"direction":"BUY|SELL|NEUTRAL","confidence":0-100,"impact":"HIGH|MEDIUM|LOW","rationale":"max 25 words"}}.
Headline: {news.headline}
Body: {news.body[:5000]}
Source: {news.source}"""
        response = await client.chat.completions.create(
            model=self.settings.openai_model,
            messages=[{"role": "system", "content": "You are a conservative gold macro-news classifier. Never give trading advice."}, {"role": "user", "content": prompt}],
            temperature=0,
            response_format={"type": "json_object"},
        )
        payload = json.loads(response.choices[0].message.content or "{}")
        return SentimentResult(
            direction=Direction(payload.get("direction", "NEUTRAL")),
            confidence=float(payload.get("confidence", 50)),
            impact=Impact(payload.get("impact", "LOW")),
            rationale=str(payload.get("rationale", "No rationale returned")), source=self.settings.openai_model,
        )

    async def analyze_finbert(self, news: NewsRequest) -> SentimentResult:
        """Optional local path. Run this service on a GPU worker, never the API event loop."""
        if self._finbert is None:
            from transformers import pipeline
            self._finbert = pipeline("text-classification", model="ProsusAI/finbert", truncation=True)
        prediction = await asyncio.to_thread(self._finbert, f"{news.headline}. {news.body[:1500]}")
        top = prediction[0]
        mapping = {"positive": Direction.BUY, "negative": Direction.SELL, "neutral": Direction.NEUTRAL}
        result = self._analyze_rules(news)
        return result.model_copy(update={"direction": mapping[top["label"].lower()], "confidence": top["score"] * 100, "source": "ProsusAI/finbert"})

    def _analyze_rules(self, news: NewsRequest) -> SentimentResult:
        text = re.sub(r"\s+", " ", f"{news.headline} {news.body}").lower()
        bullish = sum(phrase in text for phrase in BULLISH_GOLD)
        bearish = sum(phrase in text for phrase in BEARISH_GOLD)
        direction = Direction.BUY if bullish > bearish else Direction.SELL if bearish > bullish else Direction.NEUTRAL
        matched = max(bullish, bearish)
        confidence = min(88, 48 + matched * 14) if direction is not Direction.NEUTRAL else 45
        impact = Impact.HIGH if any(k in text for k in HIGH_IMPACT) else Impact.MEDIUM if any(k in text for k in MEDIUM_IMPACT) else Impact.LOW
        return SentimentResult(
            direction=direction, confidence=confidence, impact=impact,
            rationale="Conservative keyword fallback; await model verification." if matched else "No unambiguous near-term gold catalyst detected.",
            source="deterministic-fallback",
        )
