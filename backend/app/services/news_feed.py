"""
News aggregation — licensed sources only.

The previous version returned a hard-coded "demo" headline set and a fabricated economic
calendar. Both are removed: when no licensed provider is configured the aggregator returns
an empty list and a status explaining why. No headline is ever invented.
"""
from __future__ import annotations

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
        self.last_error: str | None = None

    @property
    def configured(self) -> bool:
        return bool(self.settings.fmp_api_key)

    def status(self) -> dict:
        return {
            "provider": "financialmodelingprep" if self.configured else None,
            "configured": self.configured,
            "last_error": self.last_error,
            "note": (
                "بدون کلید خبری مجاز، لیست اخبار خالی می‌ماند؛ هیچ تیتر یا تقویم ساختگی ساخته نمی‌شود."
                if not self.configured
                else "اخبار فقط از منبع مجاز دریافت می‌شود."
            ),
        }

    async def fetch(self) -> list[NewsRequest]:
        if not self.configured:
            return []
        params = {"tickers": "GCUSD", "limit": 30, "apikey": self.settings.fmp_api_key}
        try:
            async with httpx.AsyncClient(timeout=8) as client:
                response = await client.get(
                    "https://financialmodelingprep.com/stable/news/general-latest", params=params
                )
                response.raise_for_status()
                payload = response.json()
        except httpx.HTTPStatusError as exc:
            # The request URL contains the FMP key; never expose exception text through /health.
            self.last_error = f"سرویس خبری پاسخ معتبر نداد (HTTP {exc.response.status_code})"
            return []
        except Exception as exc:
            # DNS/transport errors are still useful for diagnostics, but the exception message
            # may echo a URL/query string containing the provider key.
            self.last_error = f"اتصال سرویس خبری برقرار نشد ({type(exc).__name__})"
            return []

        articles: list[NewsRequest] = []
        for item in payload if isinstance(payload, list) else []:
            # Provider JSON is untrusted: malformed rows are skipped, never coerced into a
            # headline or allowed to crash the route with a 500 response.
            if not isinstance(item, dict):
                continue
            headline = item.get("title")
            if not isinstance(headline, str):
                continue
            headline = headline.strip()
            if not 3 <= len(headline) <= 500:
                continue
            body = item.get("text", "") or item.get("content", "")
            source = item.get("site", "FMP")
            article = NewsRequest(
                headline=headline,
                body=(body[:20_000] if isinstance(body, str) else ""),
                source=source if isinstance(source, str) and source.strip() else "FMP",
                published_at=self._parse_time(item.get("publishedDate")),
            )
            if self._accept(article):
                articles.append(article)
        if not articles:
            self.last_error = self.last_error or "پاسخ سرویس خبری خالی بود"
        return articles

    async def fetch_calendar(self) -> list[dict]:
        """
        Economic calendar entries.

        Only implemented when a licensed calendar provider is configured. Without one the list
        stays empty — the platform must not fabricate release times that traders act on.
        """
        self.last_error = self.last_error or "منبع تقویم اقتصادی مجاز تنظیم نشده است"
        return []

    def _accept(self, article: NewsRequest) -> bool:
        fingerprint = hashlib.sha256(
            f"{article.source}:{article.headline.lower().strip()}".encode()
        ).hexdigest()
        if fingerprint in self.seen:
            return False
        self.seen.add(fingerprint)
        if len(self.seen) > 500:
            self.seen = set(list(self.seen)[-300:])
        return True

    @staticmethod
    def _parse_time(value: str | None) -> datetime | None:
        """Missing/bad provider timestamps stay unknown; never replace them with now()."""
        if not isinstance(value, str) or not value.strip():
            return None
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
