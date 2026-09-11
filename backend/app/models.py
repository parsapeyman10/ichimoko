from datetime import datetime, timezone
from enum import StrEnum
from pydantic import BaseModel, Field, model_validator


class Timeframe(StrEnum):
    M1 = "1m"
    M5 = "5m"
    M15 = "15m"
    H1 = "1h"
    H4 = "4h"
    D1 = "1d"

    @property
    def seconds(self) -> int:
        return {
            "1m": 60,
            "5m": 300,
            "15m": 900,
            "1h": 3600,
            "4h": 14400,
            "1d": 86400,
        }[self.value]


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
    # enriched fields for complementary methods
    confluence: list[dict] = Field(default_factory=list)
    exit_hint: str | None = None


class StrategyRequest(BaseModel):
    candles: list[Candle] = Field(min_length=200, max_length=2000)
    context: StrategyContext = Field(default_factory=StrategyContext)


# ─── Journal / Resume ───────────────────────────────────────────────────
class JournalEntry(BaseModel):
    id: str
    symbol: str = "XAU/USD"
    timeframe: Timeframe
    action: Direction
    entry: float
    stop_loss: float
    take_profit: float
    confidence: float
    risk_reward: float
    opened_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    closed_at: datetime | None = None
    exit_price: float | None = None
    exit_reason: str | None = None
    pnl: float | None = None  # in USD for 1oz
    pnl_percent: float | None = None
    status: str = "OPEN"  # OPEN, CLOSED, CANCELLED
    reasons: list[str] = Field(default_factory=list)
    blockers: list[str] = Field(default_factory=list)


class SmallAccountConfig(BaseModel):
    balance: float = Field(ge=10, le=100000, description="Account balance in USD")
    risk_percent: float = Field(default=0.5, ge=0.1, le=5, description="Risk per trade %")
    contract_size: float = Field(default=1, description="oz per lot, 1 for micro-friendly")


class RiskCalculation(BaseModel):
    balance: float
    risk_percent: float
    risk_amount: float
    entry: float
    stop_loss: float
    stop_distance: float
    position_oz: float
    position_lots: float
    notional: float
    is_micro_allowed: bool
    warning: str | None = None


class PredictionResult(BaseModel):
    prob_buy: float
    prob_sell: float
    prob_neutral: float
    expected_direction: Direction
    confidence: float
    expected_value_R: float
    score: float
    drivers: list[dict]
    horizon: str
    features_used: int
    is_actionable: bool
    advisory: str
    explanation: str | None = None
