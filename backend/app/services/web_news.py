"""Web collection from publishers' *own advertised* RSS/Atom feeds.

Only headline, short feed excerpt, publication time and a publisher link are returned. Never
crawl article HTML or defeat access controls. An optional, explicitly consented server-side
model can assess short headlines/excerpts; unconfigured/failed AI is UNKNOWN, never a rule-based
'AI' substitute. A partial/failed feed cannot clear either the guard or AI confluence.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit

import httpx

from app.config import Settings
from app.services.ai_news import AiNewsAnalyzer
from app.services.forex_calendar import CALENDAR_URL, ForexCalendarFeed
from app.services.persian_news import Headline, MAX_FEED_BYTES, news_guard, parse_news_xml


@dataclass(frozen=True)
class WebSource:
    name: str
    feed: str
    language: str
    max_items: int = 8
    max_age_hours: int = 48

    @property
    def host(self) -> str:
        return urlsplit(self.feed).hostname or ""


# Every URL is linked from its own publisher's RSS directory (sources documented in README).
# URLs are constants, not client-supplied: no user-controlled SSRF or page scraping.
WEB_SOURCES = (
    WebSource("خبرگزاری صدا و سیما · اقتصاد", "https://www.irib-news.ir/fa/rss/6", "fa", max_age_hours=24),
    WebSource("باشگاه خبرنگاران جوان · اقتصاد", "https://www.yjc.ir/fa/rss/6", "fa", max_age_hours=24),
    WebSource("اقتصاد۲۴ · ارز", "https://eghtesaad24.ir/fa/rss/12", "fa", max_age_hours=72),
    WebSource("CoinDesk · Crypto", "https://www.coindesk.com/arc/outboundfeeds/rss", "en", max_age_hours=24),
    WebSource("BLS · CPI", "https://www.bls.gov/feed/cpi.rss", "en", max_items=1, max_age_hours=45 * 24),
    WebSource("BLS · Employment", "https://www.bls.gov/feed/empsit.rss", "en", max_items=1, max_age_hours=45 * 24),
)


class WebNewsFeed:
    def __init__(self, settings: Settings, sources: tuple[WebSource, ...] = WEB_SOURCES,
                 calendar: ForexCalendarFeed | None = None):
        self.settings = settings
        self.sources = sources
        self.calendar = calendar if calendar is not None else ForexCalendarFeed()
        self._lock = asyncio.Lock()
        self._last_attempt = 0.0
        self._last_success: datetime | None = None
        self._articles: list[Headline] = []
        self._source_status: list[dict] = []
        self.ai = AiNewsAnalyzer(settings)

    async def _request(self, source: WebSource) -> bytes:
        async with httpx.AsyncClient(timeout=10, follow_redirects=False) as client:
            async with client.stream("GET", source.feed, headers={
                "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml",
                "User-Agent": "AurumEdge/1.0 (+https://github.com/parsapeyman10/ichimoko; publisher RSS reader)",
            }) as response:
                response.raise_for_status()  # including 3xx; do not follow to an unapproved host
                size = 0
                chunks = []
                async for chunk in response.aiter_bytes():
                    size += len(chunk)
                    if size > MAX_FEED_BYTES:
                        raise ValueError("خوراک بیش از حد بزرگ است")
                    chunks.append(chunk)
                return b"".join(chunks)

    async def _collect_one(self, source: WebSource) -> tuple[list[Headline], dict]:
        status = {"name": source.name, "feed": source.feed, "language": source.language}
        try:
            parsed = parse_news_xml(await self._request(source), source.name,
                                    language=source.language, allowed_host=source.host)
            # Dated entries and a publisher-domain HTTPS link are needed to display an item.
            # If the entire feed is malformed, do not treat that source as successful.
            dated = [item for item in parsed if item.published_at and item.url]
            if not dated:
                raise ValueError("خوراک بدون تیتر تاریخ‌دار/لینک معتبر است")
            newest = max(item.published_at for item in dated)
            age = datetime.now(timezone.utc) - newest
            if not -timedelta(minutes=15) <= age <= timedelta(hours=source.max_age_hours):
                raise ValueError("منبع خبری کهنه یا دارای تاریخ آینده است")
            status.update(state="online", count=len(parsed[:source.max_items]), error=None)
            return parsed[:source.max_items], status
        except Exception as exc:
            # Never return a URL, response text or a token that might be embedded in an error.
            status.update(state="unavailable", count=0, error=type(exc).__name__)
            return [], status

    async def snapshot(self) -> dict:
        async with self._lock:
            if time.monotonic() - self._last_attempt >= 120:
                self._last_attempt = time.monotonic()
                results = await asyncio.gather(*(self._collect_one(source) for source in self.sources))
                self._source_status = [status for _, status in results]
                self._articles = sorted(
                    (item for batch, _ in results for item in batch),
                    key=lambda item: item.published_at or datetime.min.replace(tzinfo=timezone.utc),
                    reverse=True,
                )[:40]
                if self._source_status and all(s["state"] == "online" for s in self._source_status):
                    self._last_success = datetime.now(timezone.utc)
            now = datetime.now(timezone.utc)
            calendar = await self.calendar.snapshot(hold_minutes=self.settings.news_hold_minutes)
            online_count = sum(s["state"] == "online" for s in self._source_status)
            all_rss = online_count == len(self.sources) and online_count > 0
            cal_online = calendar["status"] == "online" and calendar["guard"]["state"] in {"CLEAR", "BLOCKED"}
            state = "online" if all_rss and cal_online else (
                "partial" if online_count or cal_online else "unavailable")
            rss_guard = news_guard(self._articles, all_rss, now, self.settings.news_hold_minutes)
            cal_guard = calendar["guard"]
            if rss_guard["state"] == "BLOCKED" or cal_guard["state"] == "BLOCKED":
                guard = rss_guard if rss_guard["state"] == "BLOCKED" else cal_guard
            elif state == "online" and rss_guard["state"] == cal_guard["state"] == "CLEAR":
                guard = {"state": "CLEAR", "reason": "خبر RSS و رویداد USD تقویم هفتگی بررسی شد؛ پوشش کامل تضمین نیست", "until": None}
            else:
                guard = {"state": "UNKNOWN", "reason": "فید ناشر یا تقویم Forex Factory کامل/تازه نیست", "until": None}
            ai_confluence = await self.ai.analyze(self._articles, state, guard, now)
            sources = self._source_status + [{"name": "Forex Factory · تقویم", "feed": CALENDAR_URL,
                                              "language": "en", "state": calendar["status"],
                                              "count": len(calendar["events"])}]
            return {
                "status": {
                    "provider": "RSS عمومی ناشران + تقویم هفتگی Forex Factory",
                    "configured": True,
                    "state": state,
                    "last_success_at": self._last_success.isoformat() if self._last_success else None,
                    "error": "دسترسی به بعضی ناشران/تقویم ممکن نیست؛ پوشش کامل تایید نشد" if state != "online" else None,
                    "cached": False,  # failed publishers' earlier items are NEVER reused
                    "sources": sources,
                },
                "articles": [article.payload() for article in self._articles],
                "calendar": calendar,
                "guard": guard,
                "ai_confluence": ai_confluence,
                "checked_at": datetime.now(timezone.utc).isoformat(),
                "notice": "تقویم فقط رویدادهای هفتگیِ منتشرشده است؛ اگر منبع/AI قطع شود خبر UNKNOWN است. رویداد پراثر USD از ۳۰ دقیقه پیش تا دورهٔ توقف پس از آن ورود جدید را می‌بندد.",
            }
