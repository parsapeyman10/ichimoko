"""Licensed Persian RSS/Atom ingestion and a conservative *read-only* news pause signal.

A configured feed is not a complete economic calendar. CLEAR means only that this feed
returned dated articles and no recent high-impact article was detected; it never authorizes
live trading. Missing configuration, network failure or undated news is UNKNOWN (fail closed).
"""
from __future__ import annotations

import asyncio
import hashlib
import html
import ipaddress
import re
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

import httpx
from defusedxml import ElementTree

from app.config import Settings
from app.models import Impact, NewsRequest
from app.services.sentiment import SentimentEngine

MAX_FEED_BYTES = 1_000_000
CACHE_TTL_SECONDS = 60


@dataclass(frozen=True)
class Headline:
    id: str
    headline: str
    summary: str
    source: str
    url: str | None
    published_at: datetime | None
    analysis: dict
    language: str = "fa"

    def payload(self) -> dict:
        return {
            "id": self.id,
            "headline": self.headline,
            "summary": self.summary,
            "source": self.source,
            "url": self.url,
            "published_at": self.published_at.isoformat() if self.published_at else None,
            "analysis": self.analysis,
            "language": self.language,
        }


def validated_feed_url(settings: Settings) -> str | None:
    url = settings.fa_news_rss_url
    host = settings.fa_news_allowed_host
    if not url and not host:
        return None
    if not url or not host:
        raise ValueError("آدرس و دامنهٔ مجاز خبر فارسی هر دو لازم‌اند")
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.port not in (None, 443):
        raise ValueError("فید خبری باید HTTPS روی درگاه ۴۴۳ باشد")
    if parts.username or parts.password or parts.fragment or parts.hostname.lower() != host.strip().lower():
        raise ValueError("دامنهٔ آدرس خبر با دامنهٔ مجاز یکسان نیست")
    try:
        ipaddress.ip_address(parts.hostname)
    except ValueError:
        pass
    else:
        raise ValueError("دامنهٔ فید نباید آدرس IP باشد")
    return url


def parse_news_xml(raw: bytes, source: str, *, language: str = "fa",
                   allowed_host: str | None = None) -> list[Headline]:
    """Extract only feed-provided titles/excerpts (not article pages or copyrighted full text)."""
    if len(raw) > MAX_FEED_BYTES:
        raise ValueError("پاسخ فید بیش از حد بزرگ است")
    if language not in {"fa", "en"}:
        raise ValueError("زبان خوراک پشتیبانی نمی‌شود")
    root = ElementTree.fromstring(raw)  # defusedxml rejects entities / expansion
    nodes = [element for element in root.iter() if element.tag.rsplit("}", 1)[-1] in {"item", "entry"}]
    seen: set[str] = set()
    articles: list[Headline] = []
    for node in nodes[:100]:
        values = {child.tag.rsplit("}", 1)[-1]: child for child in node}
        title = " ".join(html.unescape(re.sub(r"<[^>]*>", " ", _text(values.get("title")))).split())[:240]
        if len(title) < 3 or (language == "fa" and not any("\u0600" <= letter <= "\u06ff" for letter in title)):
            continue  # do not label English-only headlines as Persian

        def field(*names: str):
            return next((values[name] for name in names if name in values), None)

        summary = re.sub(r"<[^>]*>", " ", _text(field("description", "summary", "content")))
        summary = " ".join(html.unescape(summary).split())[:280]
        link = field("link")
        link_text = _text(link) or (link.get("href", "") if link is not None else "")
        parts = urlsplit(link_text)
        host = (parts.hostname or "").lower()
        try:
            safe_port = parts.port in (None, 443)
        except ValueError:
            safe_port = False
        expected = allowed_host.lower() if allowed_host else None
        url = link_text if (parts.scheme == "https" and not parts.username and not parts.password and
                            safe_port and (expected is None or
                                           host in {expected, expected.removeprefix("www."),
                                                    "www." + expected.removeprefix("www.")})) else None
        published = _parse_date(_text(field("pubDate", "published", "updated")))
        uid = hashlib.sha256(f"{source}:{title}:{url}".encode()).hexdigest()[:20]
        if uid in seen:
            continue
        seen.add(uid)
        analysis = SentimentEngine._analyze_rules(
            NewsRequest(headline=title, body=summary, source=source, published_at=published))
        articles.append(Headline(uid, title, summary, source, url, published,
            analysis.model_dump(mode="json"), language))
        if len(articles) == 30:
            break
    return sorted(articles, key=lambda a: a.published_at or datetime.min.replace(tzinfo=timezone.utc), reverse=True)


def _text(node) -> str:
    return (node.text or "").strip() if node is not None else ""


def _parse_date(value: str) -> datetime | None:
    if not value:
        return None
    try:
        parsed = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    return parsed.astimezone(timezone.utc) if parsed.tzinfo else None


def news_guard(articles: list[Headline], online: bool, now: datetime, hold_minutes: int = 45) -> dict:
    if not online or not articles:
        return {"state": "UNKNOWN", "reason": "منبع خبری معتبر در دسترس نیست؛ نبود خبر اثبات نشده است", "until": None}
    if any(article.published_at is None or article.published_at > now + timedelta(minutes=15) for article in articles):
        return {"state": "UNKNOWN", "reason": "زمان انتشار بعضی خبرها نامعتبر است", "until": None}
    recent = [item for item in articles if item.published_at and
              now - timedelta(minutes=max(1, min(hold_minutes, 180))) <= item.published_at <= now + timedelta(minutes=15) and
              item.analysis.get("impact") == Impact.HIGH.value]
    if recent:
        until = max(item.published_at for item in recent) + timedelta(minutes=max(1, min(hold_minutes, 180)))
        return {"state": "BLOCKED", "reason": "خبر پراثر تازه منتشر شده؛ ورود جدید متوقف است", "until": until.isoformat()}
    return {"state": "CLEAR", "reason": "در همین فید خبر پراثر تازه یافت نشد؛ پوشش همهٔ خبرها تضمین نیست", "until": None}


class PersianNewsFeed:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._lock = asyncio.Lock()
        self._last_attempt = 0.0
        self._last_success: datetime | None = None
        self._articles: list[Headline] = []
        self._online = False
        self._error: str | None = None

    async def _request(self, url: str) -> bytes:
        async with httpx.AsyncClient(timeout=10, follow_redirects=False) as client:
            async with client.stream("GET", url, headers={"Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml"}) as response:
                response.raise_for_status()  # redirects are rejected, not followed with credentials
                chunks = []
                size = 0
                async for chunk in response.aiter_bytes():
                    size += len(chunk)
                    if size > MAX_FEED_BYTES:
                        raise ValueError("پاسخ فید بیش از حد بزرگ است")
                    chunks.append(chunk)
                return b"".join(chunks)

    async def snapshot(self) -> dict:
        try:
            url = validated_feed_url(self.settings)
        except ValueError as exc:
            url = None
            self._error = str(exc)
        if url is None:
            return self._payload(False)
        async with self._lock:
            if time.monotonic() - self._last_attempt >= CACHE_TTL_SECONDS:
                self._last_attempt = time.monotonic()
                try:
                    parsed = parse_news_xml(await self._request(url), self.settings.fa_news_source)
                    self._articles = parsed
                    self._last_success = datetime.now(timezone.utc)
                    self._online = True
                    self._error = None
                except Exception as exc:
                    self._online = False
                    # Do not log/return the URL: it might include a provider's query-string key.
                    self._error = f"خطای فید خبری ({type(exc).__name__})"
            return self._payload(True)

    def _payload(self, configured: bool) -> dict:
        now = datetime.now(timezone.utc)
        return {
            "status": {
                "provider": self.settings.fa_news_source if configured else None,
                "configured": configured,
                "state": "online" if self._online and configured else "unavailable" if configured else "unconfigured",
                "last_success_at": self._last_success.isoformat() if self._last_success else None,
                "error": self._error,
                "cached": bool(self._articles) and not self._online,
            },
            "articles": [article.payload() for article in self._articles] if configured else [],
            "guard": news_guard(self._articles, configured and self._online, now, self.settings.news_hold_minutes),
            "checked_at": now.isoformat(),
        }
