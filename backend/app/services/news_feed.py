import hashlib
from datetime import datetime, timezone
import httpx
from app.config import Settings
from app.models import NewsRequest


class NewsAggregator:
    """Licensed API aggregation only; do not scrape restricted publishers."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.seen: set[str] = set()

    async def fetch_fmp(self) -> list[NewsRequest]:
        if not self.settings.fmp_api_key:
            return []
        params = {"tickers": "GCUSD", "limit": 30, "apikey": self.settings.fmp_api_key}
        async with httpx.AsyncClient(timeout=8) as client:
            response = await client.get("https://financialmodelingprep.com/stable/news/general-latest", params=params)
            response.raise_for_status()
        articles = []
        for item in response.json():
            article = NewsRequest(
                headline=item.get("title", "Untitled"), body=item.get("text", ""),
                source=item.get("site", "FMP"), published_at=self._parse_time(item.get("publishedDate")),
            )
            if self._accept(article):
                articles.append(article)
        return articles

    def _accept(self, article: NewsRequest) -> bool:
        fingerprint = hashlib.sha256(f"{article.source}:{article.headline.lower().strip()}".encode()).hexdigest()
        if fingerprint in self.seen:
            return False
        self.seen.add(fingerprint)
        return True

    @staticmethod
    def _parse_time(value: str | None) -> datetime:
        if not value:
            return datetime.now(timezone.utc)
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return datetime.now(timezone.utc)
