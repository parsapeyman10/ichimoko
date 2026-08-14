from datetime import datetime, timezone
from enum import StrEnum
from pydantic import BaseModel, Field, model_validator


class Timeframe(StrEnum):
    M1 = "1m"
    M5 = "5m"

    @property
    def seconds(self) -> int:
        return 60 if self is Timeframe.M1 else 300


class Tick(BaseModel):
    symbol: str = "XAU/USD"
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    bid: float
    ask: float
    provider: str = "synthetic"

    @property
    def mid(self) -> float:
        return (self.bid + self.ask) / 2


class Candle(BaseModel):
    symbol: str = "XAU/USD"
    timeframe: Timeframe
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float = 0
    complete: bool = False

    @model_validator(mode="after")
    def validate_ohlc(self):
        if self.high < max(self.open, self.close) or self.low > min(self.open, self.close):
            raise ValueError("Invalid OHLC range")
        return self


class Direction(StrEnum):
    BUY = "BUY"
    SELL = "SELL"
    NEUTRAL = "NEUTRAL"
    NO_TRADE = "NO_TRADE"


class Impact(StrEnum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"


class SentimentResult(BaseModel):
    direction: Direction
    confidence: float = Field(ge=0, le=100)
    impact: Impact
    rationale: str
    source: str = "rules"
    analyzed_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class NewsRequest(BaseModel):
    headline: str = Field(min_length=3, max_length=500)
    body: str = Field(default="", max_length=20_000)
    source: str = "unknown"
    published_at: datetime | None = None


class StrategyContext(BaseModel):
    news: SentimentResult | None = None
    event_risk: bool = False
    spread: float = Field(default=0.18, ge=0)
    typical_spread: float = Field(default=0.18, gt=0)
    higher_timeframe_bias: Direction = Direction.NEUTRAL


class TradeSignal(BaseModel):
    action: Direction
    confidence: float = Field(ge=0, le=100)
    entry: float | None = None
    stop_loss: float | None = None
    take_profit: float | None = None
    risk_reward: float | None = None
    expires_after_seconds: int = 0
    reasons: list[str] = Field(default_factory=list)
    blockers: list[str] = Field(default_factory=list)
    generated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class StrategyRequest(BaseModel):
    candles: list[Candle] = Field(min_length=200, max_length=2000)
    context: StrategyContext = Field(default_factory=StrategyContext)
