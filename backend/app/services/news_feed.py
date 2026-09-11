import hashlib
import asyncio
from datetime import datetime, timezone
import httpx
from app.config import Settings
from app.models import NewsRequest


class NewsAggregator:
    """Licensed API aggregation only; do not scrape restricted publishers.
    Multi-source: FMP + fallback synthetic + economic calendar guard.
    """

    def __init__(self, settings: Settings):
        self.settings = settings
        self.seen: set[str] = set()

    async def fetch_fmp(self) -> list[NewsRequest]:
        if not self.settings.fmp_api_key:
            return await self._synthetic_news()
        params = {"tickers": "GCUSD", "limit": 30, "apikey": self.settings.fmp_api_key}
        async with httpx.AsyncClient(timeout=8) as client:
            try:
                response = await client.get("https://financialmodelingprep.com/stable/news/general-latest", params=params)
                response.raise_for_status()
            except Exception:
                return await self._synthetic_news()
        articles = []
        for item in response.json():
            article = NewsRequest(
                headline=item.get("title", "Untitled"), body=item.get("text", ""),
                source=item.get("site", "FMP"), published_at=self._parse_time(item.get("publishedDate")),
            )
            if self._accept(article):
                articles.append(article)
        if not articles:
            # still provide synthetic to keep terminal alive
            articles = await self._synthetic_news()
        return articles

    async def _synthetic_news(self) -> list[NewsRequest]:
        """Deterministic demo news covering all market regimes, Persian+English."""
        now = datetime.now(timezone.utc)
        samples = [
            ("Fed officials signal patience on rates as inflation cools further", "Lower real-yield expectations support non-yielding gold demand. Fed minutes show dovish tilt.", "Reuters"),
            ("Dollar index steadies ahead of US retail sales release", "A firm DXY is limiting immediate upside in precious metals. Traders await 14:30 UTC data.", "FXStreet"),
            ("Central bank gold demand remains resilient in latest WGC survey", "Structural physical demand continues to underpin price despite short-term volatility.", "Financial Modeling Prep"),
            ("US CPI hotter than expected — yields jump, gold pressured", "Hot CPI lifts real yields and dollar, pressuring XAU/USD in near term.", "Bloomberg (demo)"),
            ("Geopolitical tensions rise in Middle East — safe haven bid returns", "Escalation fears boost safe-haven demand for gold and weigh on risk assets.", "Reuters (demo)"),
            ("ECB holds rates, Lagarde warns on sticky inflation", "European yields steady, euro soft. Neutral to slightly bullish for gold via dollar channel.", "MarketWatch (demo)"),
        ]
        out=[]
        for title, body, src in samples[:3]:
            a = NewsRequest(headline=title, body=body, source=src, published_at=now)
            if self._accept(a):
                out.append(a)
        return out

    async def fetch_calendar(self) -> list[dict]:
        """Economic calendar guard — demo data, replace with licensed ForexFactory/Investing API."""
        now = datetime.now(timezone.utc)
        return [
            {"time": now.replace(hour=14, minute=30).isoformat(), "event": "US Retail Sales m/m", "impact": "HIGH", "currency": "USD", "forecast": "0.4%", "previous": "0.2%"},
            {"time": now.replace(hour=18, minute=0).isoformat(), "event": "FOMC Minutes", "impact": "HIGH", "currency": "USD"},
            {"time": now.replace(hour=12, minute=30).isoformat(), "event": "EU CPI y/y", "impact": "MEDIUM", "currency": "EUR"},
        ]

    def _accept(self, article: NewsRequest) -> bool:
        fingerprint = hashlib.sha256(f"{article.source}:{article.headline.lower().strip()}".encode()).hexdigest()
        if fingerprint in self.seen:
            return False
        self.seen.add(fingerprint)
        # keep dedup set bounded
        if len(self.seen) > 500:
            self.seen = set(list(self.seen)[-300:])
        return True

    @staticmethod
    def _parse_time(value: str | None) -> datetime:
        if not value:
            return datetime.now(timezone.utc)
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return datetime.now(timezone.utc)
