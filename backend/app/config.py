from __future__ import annotations

import re
from functools import lru_cache
from urllib.parse import urlsplit

from pydantic_settings import BaseSettings, SettingsConfigDict


OPTIONAL_SECRET_FIELDS = (
    "twelve_data_api_key",
    "fmp_api_key",
    "coingecko_demo_api_key",
    "gemini_api_key",
    "openai_api_key",
)


def _clean_secret(value: object) -> str | None:
    """Normalize env-file values once; never expose the value in diagnostics."""
    if value is None:
        return None
    cleaned = str(value).strip()
    return cleaned or None


def _safe_https_base(value: str | None) -> bool:
    if not value:
        return True
    try:
        parsed = urlsplit(value.strip())
        hostname = parsed.hostname
        safe_port = parsed.port in (None, 443)
    except ValueError:
        return False
    return (
        parsed.scheme == "https"
        and bool(hostname)
        and parsed.username is None
        and parsed.password is None
        and safe_port
        and not parsed.query
        and not parsed.fragment
    )


def _redacted_url(value: str | None) -> str | None:
    """Keep diagnostics useful while dropping credentials/query tokens from configured URLs."""
    if not value:
        return None
    try:
        parsed = urlsplit(value.strip())
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        return "<invalid-url>"
    if not parsed.scheme or not host:
        return "<invalid-url>"
    authority = host if port in (None, 443) else f"{host}:{port}"
    # Do not echo path segments either: some relay URLs embed a token in the path.
    return f"{parsed.scheme}://{authority}"


class Settings(BaseSettings):
    """
    Runtime configuration.

    There is deliberately no "use synthetic feed" switch: the platform only ever publishes
    data that came from a licensed provider. When the provider is unreachable the API reports
    it and returns no candles — it never substitutes generated data.
    """

    environment: str = "development"
    cors_origins: str = (
        "http://localhost:5173,http://127.0.0.1:5173,"
        "http://localhost:3000,http://127.0.0.1:3000"
    )

    # Real market data (required for every market endpoint)
    twelve_data_api_key: str | None = None
    market_symbol: str = "XAU/USD"
    # Research/replay callers request the full real-data window by default. A provider returning
    # fewer rows is reported as incomplete; no padding is permitted.
    default_history_bars: int = 3000
    # Without a Twelve Data key the backend automatically self-aggregates real candles from a
    # free, keyless spot XAU/USD feed (Swissquote, with Gold-API as a backup) instead of going
    # dark. Set to false to force the strict "no key -> unavailable" behaviour instead.
    market_free_fallback_enabled: bool = True

    # Optional licensed news / calendar provider
    fmp_api_key: str | None = None
    # Legacy /news/fa: user-provided licensed Persian feed with an exact allowed host.
    # Separate /news/web reads only the publishers' advertised public RSS headlines.
    fa_news_rss_url: str | None = None
    fa_news_allowed_host: str | None = None
    fa_news_source: str = "منبع خبری دارای مجوز"
    news_hold_minutes: int = 45

    # Read-only CoinGecko Demo API key. Keep on server, never ship in an APK.
    # Public/keyless requests may be rate-limited; provider failures leave the scan unavailable.
    coingecko_demo_api_key: str | None = None

    # Optional free-tier Gemini key stays SERVER-SIDE. Free-tier traffic may be used by the
    # provider to improve products; review publisher rights before enabling external consent.
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-2.5-flash-lite"

    # Optional legacy paid NLP stage. Web RSS titles/excerpts are NEVER shared with any model
    # unless the operator explicitly accepts publisher terms. No model key is shipped in an APK.
    openai_api_key: str | None = None
    openai_model: str = "gpt-4o-mini"
    # Optional custom OpenAI-COMPATIBLE endpoint (e.g. a self-hosted or third-party relay/gateway
    # instead of api.openai.com directly). When set, publisher headline text is sent to THIS host,
    # not OpenAI — that is a distinct trust decision from the operator, reviewed like any other.
    openai_base_url: str | None = None
    ai_news_external_consent: bool = False

    model_config = SettingsConfigDict(env_file=".env", env_prefix="AURUM_", extra="ignore")

    def __init__(self, **values):
        super().__init__(**values)
        for field in OPTIONAL_SECRET_FIELDS:
            setattr(self, field, _clean_secret(getattr(self, field)))
        self.environment = self.environment.strip() or "development"
        self.market_symbol = self.market_symbol.strip().upper()
        self.gemini_model = self.gemini_model.strip()
        self.openai_model = self.openai_model.strip()
        self.cors_origins = self.cors_origins.strip()
        if self.openai_base_url:
            self.openai_base_url = self.openai_base_url.strip().rstrip("/")
        if self.fa_news_rss_url:
            self.fa_news_rss_url = self.fa_news_rss_url.strip()
        if self.fa_news_allowed_host:
            self.fa_news_allowed_host = self.fa_news_allowed_host.strip().lower().rstrip(".")

    @property
    def allowed_origins(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def has_market_key(self) -> bool:
        return bool(self.twelve_data_api_key)

    @property
    def configuration_errors(self) -> list[str]:
        """Static, secret-free checks used by health/config diagnostics before network probes."""
        errors: list[str] = []
        if not re.fullmatch(r"[A-Z]{3,6}/[A-Z]{3,6}", self.market_symbol):
            errors.append("AURUM_MARKET_SYMBOL باید به شکل BASE/QUOTE معتبر باشد")
        if bool(self.fa_news_rss_url) != bool(self.fa_news_allowed_host):
            errors.append("AURUM_FA_NEWS_RSS_URL و AURUM_FA_NEWS_ALLOWED_HOST باید با هم تنظیم شوند")
        if self.fa_news_rss_url:
            try:
                parsed = urlsplit(self.fa_news_rss_url)
                safe_port = parsed.port in (None, 443)
                valid_host = parsed.hostname and parsed.hostname.lower() == self.fa_news_allowed_host
            except ValueError:
                parsed = None
                safe_port = False
                valid_host = False
            if (parsed is None or parsed.scheme != "https" or not valid_host or parsed.username or
                    parsed.password or not safe_port or parsed.fragment):
                errors.append("فید فارسی فقط با HTTPS، دامنهٔ مجاز همسان و بدون credentials معتبر است")
        if self.openai_base_url and not _safe_https_base(self.openai_base_url):
            errors.append("AURUM_OPENAI_BASE_URL باید HTTPS، بدون credentials/query/fragment و درگاه ۴۴۳ باشد")
        if self.ai_news_external_consent and not (self.gemini_api_key or self.openai_api_key):
            errors.append("رضایت ارسال خبر فعال است اما هیچ کلید Gemini/OpenAI تنظیم نشده است")
        if self.gemini_model and not re.fullmatch(r"gemini-[a-z0-9.-]{4,70}", self.gemini_model):
            errors.append("AURUM_GEMINI_MODEL شناسهٔ مدل Gemini معتبر نیست")
        return errors

    @property
    def integration_status(self) -> dict[str, dict[str, object]]:
        """Show where configured values go without ever returning a secret or its length."""
        return {
            "twelve_data": {
                "configured": self.has_market_key,
                "endpoint": "https://api.twelvedata.com/time_series",
                "websocket": "wss://ws.twelvedata.com/v1/quotes/price",
                "key_location": "server_env",
            },
            "spot_fallback": {
                "configured": self.market_free_fallback_enabled,
                "endpoints": [
                    "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD",
                    "https://api.gold-api.com/price/XAU",
                ],
                "key_location": "none",
            },
            "fmp_news": {
                "configured": bool(self.fmp_api_key),
                "endpoint": "https://financialmodelingprep.com/stable/news/general-latest",
                "key_location": "server_env",
            },
            "persian_news": {
                "configured": bool(self.fa_news_rss_url and self.fa_news_allowed_host),
                "endpoint": _redacted_url(self.fa_news_rss_url),
                "key_location": "provider_url_if_licensed",
            },
            "public_news_and_calendar": {
                "configured": True,
                "endpoints": [
                    "https://www.irib-news.ir/fa/rss/6",
                    "https://www.yjc.ir/fa/rss/6",
                    "https://eghtesaad24.ir/fa/rss/12",
                    "https://www.coindesk.com/arc/outboundfeeds/rss",
                    "https://www.bls.gov/feed/cpi.rss",
                    "https://www.bls.gov/feed/empsit.rss",
                    "https://nfs.faireconomy.media/ff_calendar_thisweek.json",
                ],
                "key_location": "none",
            },
            "coingecko": {
                "configured": bool(self.coingecko_demo_api_key),
                "endpoint": "https://api.coingecko.com/api/v3/coins/markets",
                "key_location": "server_header",
            },
            "gemini": {
                "configured": bool(self.gemini_api_key),
                "endpoint": "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                "key_location": "server_header",
            },
            "openai": {
                "configured": bool(self.openai_api_key),
                "endpoint": _redacted_url(self.openai_base_url) or "https://api.openai.com/v1",
                "key_location": "server_sdk_header",
            },
        }


@lru_cache
def get_settings() -> Settings:
    return Settings()
