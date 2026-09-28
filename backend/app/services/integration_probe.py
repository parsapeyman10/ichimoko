"""Bounded, secret-free live probes for configured integrations.

Probes validate connectivity and the outer response shape only. They never return provider
bodies, URLs with query strings, API keys, or publisher text. A failed probe is a diagnostic;
it must not be used as a data fallback and it never changes the market/news caches.
"""
from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

import httpx

from app.config import Settings, _safe_https_base
from app.services.forex_calendar import CALENDAR_URL, MAX_BYTES, parse_calendar
from app.services.persian_news import MAX_FEED_BYTES, parse_news_xml, validated_feed_url
from app.services.spot_feed import _fetch_gold_api, _fetch_swissquote
from app.services.web_news import WEB_SOURCES


Probe = Callable[[httpx.AsyncClient], Awaitable[dict[str, Any]]]


class ProviderAuthError(Exception):
    pass


class ProviderQuotaError(Exception):
    pass


def _failure(exc: Exception) -> dict[str, Any]:
    if isinstance(exc, ProviderAuthError):
        return {"state": "error", "error": "کلید یا اعتبارنامهٔ provider رد شد"}
    if isinstance(exc, ProviderQuotaError):
        return {"state": "error", "error": "سهمیه یا محدودیت نرخ provider فعال است"}
    if isinstance(exc, PermissionError):
        return {"state": "error", "error": "کلید یا اعتبارنامهٔ provider رد شد"}
    if isinstance(exc, httpx.HTTPStatusError):
        status = exc.response.status_code
        if status in (401, 403):
            error = "اعتبارنامه یا دسترسی provider رد شد"
        elif status == 429:
            error = "سهمیه یا محدودیت نرخ provider فعال است"
        elif 500 <= status <= 599:
            error = "provider موقتاً خطای سمت سرور برگرداند"
        else:
            error = f"provider پاسخ HTTP {status} برگرداند"
        return {"state": "error", "error": error, "http_status": status}
    if isinstance(exc, (httpx.TimeoutException, TimeoutError)):
        return {"state": "error", "error": "مهلت اتصال به provider تمام شد"}
    if isinstance(exc, json.JSONDecodeError):
        return {"state": "error", "error": "provider JSON معتبر برنگرداند"}
    if isinstance(exc, (httpx.TransportError, OSError)):
        return {"state": "error", "error": "اتصال شبکه به provider برقرار نشد"}
    return {"state": "error", "error": "پاسخ provider از قرارداد مورد انتظار پیروی نکرد"}


def _ok(**details: Any) -> dict[str, Any]:
    return {"state": "ok", **details}


def _reject_error_envelope(payload: Any) -> None:
    """Classify provider error envelopes without returning their message or request details."""
    if not isinstance(payload, dict):
        return
    fields = [payload.get(name) for name in ("error", "Error Message", "message", "detail")]
    marker = " ".join(str(value).lower() for value in fields if value is not None)
    if marker:
        if any(word in marker for word in ("quota", "limit", "rate", "too many")):
            raise ProviderQuotaError("provider quota")
        raise ProviderAuthError("provider rejected credentials or request")


async def _json(client: httpx.AsyncClient, url: str, **kwargs: Any) -> tuple[httpx.Response, Any]:
    response = await client.get(url, **kwargs)
    response.raise_for_status()
    if len(response.content) > 3_000_000:
        raise ValueError("response too large")
    return response, response.json()


async def _probe_twelve(settings: Settings, client: httpx.AsyncClient) -> dict[str, Any]:
    if not settings.twelve_data_api_key:
        return {"state": "disabled", "reason": "کلید تنظیم نشده است"}
    response, payload = await _json(
        client,
        "https://api.twelvedata.com/time_series",
        params={
            "symbol": settings.market_symbol,
            "interval": "1day",
            "outputsize": 1,
            "timezone": "UTC",
            "apikey": settings.twelve_data_api_key,
        },
    )
    if not isinstance(payload, dict):
        raise ValueError("invalid Twelve Data envelope")
    if payload.get("status") == "error" or ("code" in payload and "values" not in payload):
        code = str(payload.get("code", ""))
        if code in {"401", "403"}:
            raise ProviderAuthError("invalid key")
        if code == "429":
            raise ProviderQuotaError("rate limit")
        raise ValueError("Twelve Data rejected request")
    values = payload.get("values")
    meta = payload.get("meta")
    if not isinstance(meta, dict) or not isinstance(values, list) or not values:
        raise ValueError("invalid Twelve Data response")
    if str(meta.get("symbol", "")).casefold() != settings.market_symbol.casefold() or meta.get("interval") != "1day":
        raise ValueError("Twelve Data identity mismatch")
    return _ok(http_status=response.status_code, sample_count=len(values))


async def _probe_fmp(settings: Settings, client: httpx.AsyncClient) -> dict[str, Any]:
    if not settings.fmp_api_key:
        return {"state": "disabled", "reason": "کلید تنظیم نشده است"}
    response, payload = await _json(
        client,
        "https://financialmodelingprep.com/stable/news/general-latest",
        params={"tickers": "GCUSD", "limit": 1, "apikey": settings.fmp_api_key},
    )
    if not isinstance(payload, list):
        _reject_error_envelope(payload)
        raise ValueError("FMP did not return a news list")
    return _ok(http_status=response.status_code, sample_count=len(payload))


async def _probe_coingecko(settings: Settings, client: httpx.AsyncClient) -> dict[str, Any]:
    if not settings.coingecko_demo_api_key:
        return {"state": "disabled", "reason": "کلید تنظیم نشده است"}
    response, payload = await _json(
        client,
        "https://api.coingecko.com/api/v3/coins/markets",
        headers={"x-cg-demo-api-key": settings.coingecko_demo_api_key},
        params={"vs_currency": "usd", "order": "market_cap_desc", "per_page": 1, "page": 1},
    )
    if not isinstance(payload, list):
        _reject_error_envelope(payload)
        raise ValueError("CoinGecko did not return a market list")
    return _ok(http_status=response.status_code, sample_count=len(payload))


async def _probe_gemini(settings: Settings, client: httpx.AsyncClient) -> dict[str, Any]:
    if not settings.gemini_api_key:
        return {"state": "disabled", "reason": "کلید تنظیم نشده است"}
    response, payload = await _json(
        client,
        "https://generativelanguage.googleapis.com/v1beta/models",
        headers={"x-goog-api-key": settings.gemini_api_key},
    )
    models = payload.get("models") if isinstance(payload, dict) else None
    if not isinstance(models, list):
        _reject_error_envelope(payload)
        raise ValueError("Gemini did not return a model list")
    return _ok(http_status=response.status_code, model_count=len(models))


async def _probe_openai(settings: Settings, client: httpx.AsyncClient) -> dict[str, Any]:
    if not settings.openai_api_key:
        return {"state": "disabled", "reason": "کلید تنظیم نشده است"}
    if settings.openai_base_url and not _safe_https_base(settings.openai_base_url):
        return {"state": "error", "error": "مقصد OpenAI-compatible از نظر HTTPS و URL امن نیست"}
    base = (settings.openai_base_url or "https://api.openai.com/v1").rstrip("/")
    response, payload = await _json(
        client,
        f"{base}/models",
        headers={"Authorization": f"Bearer {settings.openai_api_key}"},
    )
    models = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(models, list):
        _reject_error_envelope(payload)
        raise ValueError("OpenAI-compatible endpoint did not return models")
    return _ok(http_status=response.status_code, model_count=len(models))


async def _probe_persian(settings: Settings, client: httpx.AsyncClient) -> dict[str, Any]:
    if not settings.fa_news_rss_url and not settings.fa_news_allowed_host:
        return {"state": "disabled", "reason": "فید فارسی تنظیم نشده است"}
    try:
        url = validated_feed_url(settings)
    except ValueError:
        return {"state": "error", "error": "فید فارسی از نظر HTTPS/دامنهٔ مجاز معتبر نیست"}
    if not url:
        return {"state": "error", "error": "فید فارسی ناقص تنظیم شده است"}
    response = await client.get(url, headers={"Accept": "application/rss+xml, application/atom+xml, application/xml"})
    response.raise_for_status()
    if len(response.content) > MAX_FEED_BYTES:
        raise ValueError("feed too large")
    articles = parse_news_xml(response.content, settings.fa_news_source, allowed_host=settings.fa_news_allowed_host)
    if not articles:
        raise ValueError("feed has no valid articles")
    return _ok(http_status=response.status_code, sample_count=len(articles))


async def _probe_spot(client: httpx.AsyncClient) -> dict[str, Any]:
    try:
        tick = await _fetch_swissquote(client)
        return _ok(provider=tick.provider)
    except Exception:
        tick = await _fetch_gold_api(client)
        return _ok(provider=tick.provider)


async def _probe_public_feed(source, client: httpx.AsyncClient) -> dict[str, Any]:
    response = await client.get(source.feed, headers={"Accept": "application/rss+xml, application/xml, text/xml"})
    response.raise_for_status()
    if len(response.content) > MAX_FEED_BYTES:
        raise ValueError("feed too large")
    articles = parse_news_xml(response.content, source.name, language=source.language, allowed_host=source.host)
    dated = [article for article in articles if article.published_at and article.url]
    if not dated:
        raise ValueError("feed has no dated linked articles")
    return _ok(http_status=response.status_code, sample_count=len(dated))


async def _probe_calendar(client: httpx.AsyncClient) -> dict[str, Any]:
    response = await client.get(CALENDAR_URL, headers={"Accept": "application/json"})
    response.raise_for_status()
    if len(response.content) > MAX_BYTES:
        raise ValueError("calendar too large")
    events = parse_calendar(response.content, datetime.now(timezone.utc))
    return _ok(http_status=response.status_code, sample_count=len(events))


async def _run(name: str, probe: Probe, client: httpx.AsyncClient) -> tuple[str, dict[str, Any]]:
    try:
        return name, await probe(client)
    except Exception as exc:
        return name, _failure(exc)


async def probe_integrations(settings: Settings) -> dict[str, Any]:
    """Probe each configured key and fixed public URL concurrently with bounded timeouts."""
    probes: list[tuple[str, Probe]] = [
        ("twelve_data", lambda client: _probe_twelve(settings, client)),
        ("fmp_news", lambda client: _probe_fmp(settings, client)),
        ("coingecko", lambda client: _probe_coingecko(settings, client)),
        ("gemini", lambda client: _probe_gemini(settings, client)),
        ("openai", lambda client: _probe_openai(settings, client)),
        ("persian_news", lambda client: _probe_persian(settings, client)),
        ("spot_fallback", _probe_spot),
        *[(f"public_news_{index}", lambda client, source=source: _probe_public_feed(source, client))
          for index, source in enumerate(WEB_SOURCES)],
        ("forex_calendar", _probe_calendar),
    ]
    async with httpx.AsyncClient(timeout=8, follow_redirects=False) as client:
        rows = await asyncio.gather(*(_run(name, probe, client) for name, probe in probes))
    integrations = dict(rows)
    failed = [name for name, row in integrations.items() if row.get("state") == "error"]
    return {
        "state": "ok" if not failed else "degraded",
        "errors": failed,
        "integrations": integrations,
        "checked_at": datetime.now(timezone.utc),
        "note": "این بررسی مقدار کلید یا بدنهٔ پاسخ را نمایش نمی‌دهد و نتیجهٔ آن جایگزین دادهٔ بازار/خبر نمی‌شود.",
    }
