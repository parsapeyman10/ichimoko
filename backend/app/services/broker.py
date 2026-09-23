"""
Broker reference data — leverage, spread, commission and minimum lot.
اطلاعات مرجع بروکرها برای محاسبه دقیق هزینه اجرا (بدون هیچ ادعای عملکرد)
"""
from app.models import BrokerConfig

# Reference specs of brokers with micro lots (fees are inputs for the cost model)
BROKERS: list[BrokerConfig] = [
    BrokerConfig(
        name="RoboForex Prime",
        leverage=500,
        spread_gold=0.28,
        commission_per_oz=0.05,
        commission_per_lot=5.0,
        min_lot=0.01,
        swap_long_per_night=-0.018,
        swap_short_per_night=0.006,
        min_deposit=10,
        description="لوریج 1:500، اسپرد طلا 0.28، کمیسیون $5/لات، حداقل 0.01 لات (1oz) — مناسب حساب کوچک"
    ),
    BrokerConfig(
        name="Exness Standard",
        leverage=2000,
        spread_gold=0.32,
        commission_per_oz=0.00,
        commission_per_lot=0.0,
        min_lot=0.01,
        swap_long_per_night=-0.015,
        swap_short_per_night=0.004,
        min_deposit=1,
        description="اسپرد شناور 0.32 بدون کمیسیون، لوریج 1:2000، حداقل 1oz — سواپ فری اسلامی موجود"
    ),
    BrokerConfig(
        name="FBS LevelUp $140 Bonus",
        leverage=500,
        spread_gold=0.35,
        commission_per_oz=0.06,
        commission_per_lot=6.0,
        min_lot=0.01,
        swap_long_per_night=-0.022,
        swap_short_per_night=0.008,
        min_deposit=0,
        description="$140 بونوس بدون واریز — عملاً $100 تستی رایگان، لوریج 1:500، بعد از بونوس سود قابل برداشت"
    ),
    BrokerConfig(
        name="Alpari ECN",
        leverage=500,
        spread_gold=0.25,
        commission_per_oz=0.06,
        commission_per_lot=6.0,
        min_lot=0.01,
        swap_long_per_night=-0.02,
        swap_short_per_night=0.007,
        min_deposit=5,
        description="ECN اسپرد خام 0.25، کمیسیون $6/لات، لوریج 1:500 — اجرای سریع برای 5m"
    ),
]

RECOMMENDED = BROKERS[0]  # RoboForex Prime

def get_broker(name: str | None = None) -> BrokerConfig:
    if not name:
        return RECOMMENDED
    for b in BROKERS:
        if b.name.lower().startswith(name.lower()) or name.lower() in b.name.lower():
            return b
    return RECOMMENDED

def calculate_execution_cost(position_oz: float, entry: float, broker: BrokerConfig, holds_days: int = 0, direction: str = "BUY") -> dict:
    """
    دقیق: اسپرد + کمیسیون + سواپ + مارجین
    Returns breakdown in USD
    """
    spread_cost = broker.spread_gold * position_oz  # round-trip spread
    # Actually roundtrip spread = spread_gold * oz
    # کمیسیون هر دو طرف: commission_per_oz * 2
    commission = broker.commission_per_oz * position_oz * 2
    notional = position_oz * entry
    margin = notional / broker.leverage
    swap = 0.0
    if holds_days > 0:
        # swap per night % of notional
        rate = broker.swap_long_per_night if direction == "BUY" else broker.swap_short_per_night
        swap = notional * (rate/100) * holds_days
    total_fee = spread_cost + commission + abs(swap) if swap<0 else spread_cost + commission - swap
    # For short positive swap is profit (reduce cost)
    net_fee = spread_cost + commission + swap  # swap negative = cost
    free_margin_pct = 0  # to be calc by caller
    return {
        "spread_cost": round(spread_cost, 4),
        "commission": round(commission, 4),
        "swap": round(swap, 4),
        "total_fee_roundtrip": round(spread_cost + commission, 4),
        "total_with_swap": round(net_fee, 4),
        "notional": round(notional, 2),
        "margin_required": round(margin, 2),
        "leverage_used": round(notional / 100, 2),
    }
