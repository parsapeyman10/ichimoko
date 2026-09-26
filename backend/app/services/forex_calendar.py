"""Read-only Forex Factory weekly JSON export. High-impact USD events veto gold entries.

The calendar is a *schedule*, not an AI verdict or evidence of the reported result. We keep
no stale cache after network/schema failure, never follow redirects, and never scrape behind
login/captcha. A missing calendar is UNKNOWN, not a claim that there are no risky events.
"""
from __future__ import annotations

import asyncio
import re
import time
from datetime import datetime, timedelta, timezone

import httpx

CALENDAR_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
MAX_BYTES = 512_000
MAX_EVENTS = 300


def parse_calendar(data: bytes, now: datetime) -> list[dict]:
    if not 0 < len(data) <= MAX_BYTES:
        raise ValueError("calendar byte limit")
    import json

    root = json.loads(data)
    if not isinstance(root, list) or len(root) not in range(1, MAX_EVENTS + 1):
        raise ValueError("calendar event count")
    events: list[dict] = []
    for obj in root:
        if not isinstance(obj, dict):
            raise ValueError("calendar event shape")
        title, country, impact, when = (obj.get(field) for field in ("title", "country", "impact", "date"))
        if not isinstance(title, str) or len(title.strip()) not in range(3, 161) or "<" in title:
            raise ValueError("calendar event title")
        if not isinstance(country, str) or not re.fullmatch(r"[A-Z]{3}", country):
            raise ValueError("calendar currency")
        if impact not in {"High", "Medium", "Low", "Holiday", "Non-Economic"}:
            raise ValueError("calendar impact")
        try:
            at = datetime.fromisoformat(when.replace("Z", "+00:00"))
        except (ValueError, AttributeError):
            raise ValueError("calendar date") from None
        if at.tzinfo is None or abs((at.astimezone(timezone.utc) - now).total_seconds()) > 8 * 86400:
            raise ValueError("calendar timezone/weekly coverage")
        events.append({"title": title.strip(), "country": country, "impact": impact,
                       "at": at.astimezone(timezone.utc).isoformat()})
    if not any(event["country"] == "USD" for event in events):
        raise ValueError("USD schedule missing")
    return sorted(events, key=lambda item: item["at"])


def calendar_guard(events: list[dict], online: bool, now: datetime, hold_minutes: int = 45) -> dict:
    if not online or not events:
        return {"state": "UNKNOWN", "reason": "تقویم اقتصادی Forex Factory در دسترس/تازه نیست", "until": None}
    hold = timedelta(minutes=max(1, min(hold_minutes, 180)))
    before = timedelta(minutes=30)
    in_window = [event for event in events if event["country"] == "USD" and event["impact"] == "High" and
                 now - hold <= datetime.fromisoformat(event["at"]) <= now + before]
    if in_window:
        until = max(datetime.fromisoformat(item["at"]) for item in in_window) + hold
        return {"state": "BLOCKED", "reason": "رویداد پراثر USD در بازهٔ ۳۰ دقیقه پیش / بعد از انتشار؛ ورود جدید متوقف", "until": until.isoformat()}
    return {"state": "CLEAR", "reason": "در تقویم هفتگی بررسی‌شده، رویداد پراثر USD در بازهٔ توقف نیست", "until": None}


class ForexCalendarFeed:
    def __init__(self):
        self._lock = asyncio.Lock()
        self._last_attempt = 0.0
        self._last_success: datetime | None = None
        self._events: list[dict] = []
        self._online = False

    async def _request(self) -> bytes:
        async with httpx.AsyncClient(timeout=12, follow_redirects=False) as client:
            async with client.stream("GET", CALENDAR_URL, headers={
                "Accept": "application/json", "User-Agent": "AurumEdge/1.0 (public weekly calendar)",
            }) as response:
                response.raise_for_status()  # do not follow redirects to an unapproved host
                parts: list[bytes] = []
                size = 0
                async for chunk in response.aiter_bytes():
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise ValueError("calendar byte limit")
                    parts.append(chunk)
                return b"".join(parts)

    async def snapshot(self, now: datetime | None = None, hold_minutes: int = 45) -> dict:
        now = now or datetime.now(timezone.utc)
        async with self._lock:
            # Respect the export's traffic cap: success every 15m; failures retry in 60s.
            interval = 900 if self._online else 60
            if not self._last_attempt or time.monotonic() - self._last_attempt >= interval:
                self._last_attempt = time.monotonic()
                try:
                    self._events = parse_calendar(await self._request(), now)
                    self._online = True
                    self._last_success = now
                except Exception:
                    self._events = []  # prior results must not authorize a new trade
                    self._online = False
            online = self._online and self._last_success is not None and (
                timedelta(0) <= now - self._last_success <= timedelta(minutes=20) and
                bool(self._events) and any(abs((datetime.fromisoformat(item["at"]) - now).total_seconds()) <= 7 * 86400
                                           for item in self._events))
            return {
                "status": "online" if online else "unavailable",
                "source": CALENDAR_URL,
                "checked_at": self._last_success.isoformat() if online else None,
                "events": self._events[:MAX_EVENTS] if online else [],
                "guard": calendar_guard(self._events, online, now, hold_minutes),
            }
