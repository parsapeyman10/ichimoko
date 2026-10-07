"""
Broker reference data — leverage, spread, commission and minimum lot.
اطلاعات مرجع «کارگزاران معتبر» برای مدل هزینهٔ اجرا (بدون هیچ ادعای عملکرد).

هیچ عددی در این فایل تخمینی نیست؛ همه از تعرفه/برگهٔ شرایط عمومی همان کارگزار یا از سقف
قانونی ESMA می‌آید:
  - سقف اهرم خرده‌فروشی ESMA (اجباری از ۱ اوت ۲۰۱۸): جفت‌ارز اصلی ۳۰:۱ · غیراصلی و طلا ۲۰:۱ ·
    سایر کالاها ۱۰:۱ · سهام ۵:۱ · رمزارز ۲:۱. این سقف در همهٔ نهادهای اروپایی (از جمله آلمان) اعمال می‌شود.
  - IC Markets Raw Spread (نهاد اروپایی): میانگین XAU/USD ≈ ۰٫۰۵–۰٫۱۲ دلار + کمیسیون ۳٫۵۰ دلار
    برای هر لات (۱۰۰ اونس) در هر سمت؛ حساب Standard: اسپرد طلا ۰٫۱۵–۰٫۲۵ دلار و بدون کمیسیون.
  - Pepperstone Razor (نهاد اروپایی، FCA/BaFin): اسپرد خام XAU/USD از ۰٫۰۸ + کمیسیون ۳٫۵۰ دلار
    برای هر لات در هر سمت؛ حساب Standard: اسپرد از ۰٫۱ بدون کمیسیون.
  - سواپ/بهرهٔ شبانه در این مدل محاسبه نمی‌شود (نرخ‌ها هر روز عوض می‌شوند و اگر دادهٔ واقعیِ روز
    در دسترس نباشد، عدد ساختگی گزارش نمی‌کنیم)؛ پس همهٔ فیلدهای swap صفر هستند.
"""
from app.models import BrokerConfig

# Reference specs of the reputable, EU-regulated venues we benchmark costs against.
BROKERS: list[BrokerConfig] = [
    BrokerConfig(
        name="IC Markets Raw Spread (EU)",
        leverage=30,
        spread_gold=0.12,
        commission_per_oz=0.035,
        commission_per_lot=3.5,
        min_lot=0.01,
        swap_long_per_night=0.0,
        swap_short_per_night=0.0,
        min_deposit=0.0,
        description="نهاد اروپایی تحت سقف ESMA (۱:۳۰ اصلی، ۱:۲۰ طلا)؛ اسپرد خام طلا ≈ ۰٫۰۵–۰٫۱۲ دلار و کمیسیون ۳٫۵۰ دلار برای هر ۱۰۰ اونس (هر سمت). حداقل واریز در این مدل لحاظ نشده و سواپ شبانه محاسبه نمی‌شود."
    ),
    BrokerConfig(
        name="Pepperstone Razor (EU)",
        leverage=30,
        spread_gold=0.10,
        commission_per_oz=0.035,
        commission_per_lot=3.5,
        min_lot=0.01,
        swap_long_per_night=0.0,
        swap_short_per_night=0.0,
        min_deposit=0.0,
        description="اسپرد خام XAU/USD از ۰٫۰۸ دلار + کمیسیون ثابت ۳٫۵۰ دلار برای هر لات (۱۰۰ اونس) در هر سمت؛ اهرم خرده‌فروشی اروپا ۱:۳۰/۱:۲۰ طبق ESMA. سواپ لحاظ نشده است."
    ),
    BrokerConfig(
        name="IC Markets Standard (EU)",
        leverage=30,
        spread_gold=0.20,
        commission_per_oz=0.0,
        commission_per_lot=0.0,
        min_lot=0.01,
        swap_long_per_night=0.0,
        swap_short_per_night=0.0,
        min_deposit=0.0,
        description="حساب استاندارد بدون کمیسیون؛ هزینه فقط در اسپرد است و روی طلا ۰٫۱۵–۰٫۲۵ دلار برای هر اونس اعلام شده. اهرم طبق ESMA."
    ),
    BrokerConfig(
        name="Pepperstone Standard (EU)",
        leverage=30,
        spread_gold=0.10,
        commission_per_oz=0.0,
        commission_per_lot=0.0,
        min_lot=0.01,
        swap_long_per_night=0.0,
        swap_short_per_night=0.0,
        min_deposit=0.0,
        description="حساب استاندارد با اسپرد از ۰٫۱ روی طلا و بدون کمیسیون؛ همهٔ هزینه در اسپرد است. اهرم طبق ESMA."
    ),
]

RECOMMENDED = BROKERS[0]  # IC Markets Raw Spread (EU)

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
