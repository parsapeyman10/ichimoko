from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


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
    default_history_bars: int = 1500
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
    ai_news_external_consent: bool = False

    model_config = SettingsConfigDict(env_file=".env", env_prefix="AURUM_", extra="ignore")

    @property
    def allowed_origins(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def has_market_key(self) -> bool:
        return bool(self.twelve_data_api_key and self.twelve_data_api_key.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
