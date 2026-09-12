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

    # Optional licensed news / calendar provider
    fmp_api_key: str | None = None

    # Optional NLP stage (only used when a key is configured)
    openai_api_key: str | None = None
    openai_model: str = "gpt-4o-mini"

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
