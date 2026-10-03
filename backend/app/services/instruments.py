"""
Instrument registry — per-symbol contract specs and per-symbol strategy models.

WHY THIS MODULE EXISTS
----------------------
The original engine was written for exactly one instrument: XAU/USD. Gold-specific
constants were baked straight into the signal code — `round(entry, 2)`, a `0.01` minimum
candle range, `atr < 5` as a "sane volatility" test, `stop_dist < 0.01` as the trailing
guard, position size in *troy ounces*. Those numbers are correct for a $4,000 instrument
quoted to 2 decimals, and catastrophically wrong for EUR/USD at 1.08345 (quoted to 5
decimals) or for SHIBUSDT at 0.00000912.

Concretely, on EUR/USD the old code produced entry=1.08, stop=1.08, target=1.08 — the whole
trade plan collapsed to a single number, risk distance became 0, and nothing could ever be
executed or trailed. That is the root cause of "no trades happen on the candles that get
downloaded for pairs other than gold".

So: every number that depends on *what is being traded* now lives here, in one place, and
the strategy reads it from the spec instead of hardcoding a gold constant.

A spec also carries a per-symbol strategy *model* (`SymbolModel`): different instruments
genuinely need different entry rules. Gold and FX majors respect London/NY killzones and
weekend gaps; crypto trades 24/7 and must not be penalised for being "outside the session".
JPY crosses move in 0.01 pips, exotics have 5–10x the spread of EUR/USD and need a wider
stop floor. These are declared, not guessed at runtime.

No price, candle or quote is ever invented here — this module only describes contracts.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field, replace

# ─────────────────────────────────────────────────────────────────────────────
# Asset classes
# ─────────────────────────────────────────────────────────────────────────────

FOREX = "forex"
METAL = "metal"
CRYPTO = "crypto"


@dataclass(frozen=True)
class SymbolModel:
    """Per-symbol entry/exit model knobs. Different pair → genuinely different rules."""

    # Minimum confluence score required to open, per timeframe.
    thresholds: dict[str, float] = field(
        default_factory=lambda: {"1m": 72.0, "3m": 74.0, "5m": 75.0, "15m": 76.0}
    )
    # Session filter. Empty tuple = instrument trades around the clock (crypto):
    # the killzone penalty is then disabled entirely instead of vetoing 16h/day.
    killzones_utc: tuple[tuple[float, float], ...] = ((8.0, 11.0), (13.0, 17.0))
    # Friday-night gap risk only exists on venues that actually close.
    weekend_gap_risk: bool = True
    # Trend-strength floor. Majors are cleaner, so they can demand more; exotics/crypto chop.
    min_adx: float = 18.0
    # Stop distance is clamped into [low, high] × ATR.
    stop_atr_low: float = 0.90
    stop_atr_high: float = 1.40
    # Reward multiples by conviction bucket: (base, strong, runner, chop).
    rr_base: float = 1.55
    rr_strong: float = 1.80
    rr_runner: float = 2.20
    rr_chop: float = 1.35
    # Scalping is only *offered* where the cost structure makes it survivable.
    scalp_enabled: bool = True
    # Round-trip cost in pips used to sanity-check that a target clears the spread.
    # FX quotes a spread in pips because the pip is a fixed fraction of the price. Crypto
    # cannot: one "tick" is $0.01 on BTC and $0.00000001 on SHIB, so a pip-denominated
    # spread is meaningless across the venue. Crypto therefore declares its costs in basis
    # points of price (scale-free), via `typical_spread_bps` below.
    typical_spread_pips: float = 1.0
    # When set, costs are measured in bps of price instead of pips (crypto).
    typical_spread_bps: float | None = None
    # Exchange commission per side, in bps. On Binance spot this is ~10 bps — roughly a
    # thousand times the BTCUSDT spread, so ignoring it would make every crypto scalp look
    # profitable on paper and lose money in practice. FX pairs price their cost in the
    # spread itself, so this stays 0 there.
    taker_fee_bps: float = 0.0
    # Tick volume is unreliable/absent on some feeds; skip the volume gate when False.
    volume_reliable: bool = True
    # Notional leverage that is *normal* for this instrument at a sane risk-%.
    # This is not a recommendation, it is the scale at which the liquidation-distance gate
    # stops being meaningless. A 0.5% risk on EUR/USD with a 1.5-pip stop mathematically
    # requires ~30x notional — that is simply what spot FX is. Judging it against gold's
    # 20x ceiling vetoed *every single FX signal*, which is exactly the reported symptom.
    max_leverage: float = 20.0

    def threshold(self, timeframe: str) -> float:
        return self.thresholds.get(timeframe, self.thresholds.get("5m", 75.0))

    def in_killzone(self, hour_utc: float) -> bool:
        """True when the session filter should NOT penalise. 24/7 instruments: always True."""
        if not self.killzones_utc:
            return True
        return any(start <= hour_utc < end for start, end in self.killzones_utc)


@dataclass(frozen=True)
class InstrumentSpec:
    """Everything the engine needs to price, size and round a trade on one symbol."""

    symbol: str
    kind: str
    base: str
    quote: str
    price_precision: int      # decimals used when rounding entry/stop/target
    tick_size: float          # smallest price increment (also the min sane candle range)
    pip_size: float           # 1 pip in price units (FX: 0.0001 / JPY 0.01 / gold 0.1)
    contract_unit: str        # "oz", "lot", "coin" — what 1 unit of position means
    unit_label: str           # human label for the sizing panel
    display: str              # pretty name
    model: SymbolModel = field(default_factory=SymbolModel)
    venue: str = ""           # where candles come from ("twelve_data", "binance", ...)

    # ── derived helpers, all symbol-aware ──────────────────────────────────

    def round_price(self, value: float) -> float:
        """Round to the instrument's real quoting precision.

        This single call replaces the hardcoded `round(x, 2)` that flattened every FX
        entry/stop/target onto the same number.
        """
        return round(value, self.price_precision)

    def to_pips(self, price_distance: float) -> float:
        return price_distance / self.pip_size if self.pip_size > 0 else 0.0

    def from_pips(self, pips: float) -> float:
        return pips * self.pip_size

    def min_candle_range(self) -> float:
        """Floor for a candle's high-low, used in body-quality maths.

        Must be the instrument's tick, not gold's 0.01 — on EUR/USD a 0.01 floor is 100
        pips, so every real candle looked like a 0% body and got penalised.
        """
        return self.tick_size

    def volatility_is_sane(self, atr_value: float, price: float) -> bool:
        """Replaces the gold-only `atr < 5` check with a scale-free one."""
        if price <= 0 or not math.isfinite(atr_value):
            return False
        return (atr_value / price) < 0.012  # <1.2% of price per bar

    @property
    def cost_unit(self) -> str:
        """'pip' for FX/metals, 'bp' (basis point) for crypto."""
        return "bp" if self.model.typical_spread_bps is not None else "pip"

    def to_cost_units(self, price_distance: float, price: float | None = None) -> float:
        """Express a price distance in the unit this instrument quotes its costs in."""
        if self.model.typical_spread_bps is not None:
            if not price or price <= 0:
                return 0.0
            return price_distance / price * 10_000
        return self.to_pips(price_distance)

    def default_spread(self, price: float | None = None) -> float:
        """Typical half-turn spread in price units.

        Crypto needs the current price to convert its bps-denominated spread; FX does not.
        """
        if self.model.typical_spread_bps is not None:
            if not price or price <= 0:
                # No price to scale against: fall back to one tick rather than guessing.
                return self.tick_size
            return price * (self.model.typical_spread_bps / 10_000)
        return self.from_pips(self.model.typical_spread_pips)

    def round_trip_cost(self, price: float | None = None) -> float:
        """Full in-and-out cost: spread both ways plus commission both ways.

        This is what a scalp target has to clear to be worth taking. For Binance spot the
        fee term dominates: a 1-minute BTC scalp often cannot cover 20 bps of commission,
        and the engine should say so instead of issuing the signal.
        """
        spread = self.default_spread(price) * 2
        fee = 0.0
        if self.model.taker_fee_bps and price and price > 0:
            fee = price * (self.model.taker_fee_bps / 10_000) * 2
        return spread + fee

    def is_scalpable(self) -> bool:
        return self.model.scalp_enabled


# ─────────────────────────────────────────────────────────────────────────────
# Strategy models per asset class
# ─────────────────────────────────────────────────────────────────────────────

MAJOR_FX_MODEL = SymbolModel(
    thresholds={"1m": 70.0, "3m": 72.0, "5m": 73.0, "15m": 75.0},
    killzones_utc=((7.0, 11.0), (12.5, 17.0)),
    weekend_gap_risk=True,
    min_adx=18.0,
    stop_atr_low=0.85, stop_atr_high=1.35,
    rr_base=1.55, rr_strong=1.85, rr_runner=2.30, rr_chop=1.30,
    scalp_enabled=True,
    typical_spread_pips=0.9,
    volume_reliable=False,  # FX has no central tape; "volume" is broker tick count
    max_leverage=200.0,
)

# JPY pairs: same liquidity tier, but quoted in 0.01 pips and more session-skewed
# (Tokyo matters, and they trend hard in the London overlap).
JPY_FX_MODEL = replace(
    MAJOR_FX_MODEL,
    killzones_utc=((0.0, 2.0), (7.0, 11.0), (12.5, 17.0)),
    typical_spread_pips=1.1,
    rr_runner=2.40,
)

# Crosses / minors: wider spread, more whipsaw → demand more trend, take profit sooner.
MINOR_FX_MODEL = replace(
    MAJOR_FX_MODEL,
    thresholds={"1m": 73.0, "3m": 75.0, "5m": 76.0, "15m": 77.0},
    min_adx=20.0,
    stop_atr_low=0.95, stop_atr_high=1.50,
    rr_base=1.45, rr_strong=1.70, rr_runner=2.00, rr_chop=1.25,
    typical_spread_pips=2.0,
    max_leverage=100.0,
)

# Exotics: spread eats a scalp alive. Data and signals yes, scalping no.
EXOTIC_FX_MODEL = replace(
    MINOR_FX_MODEL,
    thresholds={"1m": 78.0, "3m": 79.0, "5m": 80.0, "15m": 80.0},
    min_adx=23.0,
    scalp_enabled=False,
    typical_spread_pips=8.0,
    max_leverage=50.0,
)

GOLD_MODEL = SymbolModel(
    thresholds={"1m": 72.0, "3m": 74.0, "5m": 75.0, "15m": 76.0},
    killzones_utc=((8.0, 11.0), (13.0, 17.0)),
    weekend_gap_risk=True,
    min_adx=18.0,
    stop_atr_low=0.90, stop_atr_high=1.40,
    rr_base=1.55, rr_strong=1.80, rr_runner=2.20, rr_chop=1.35,
    scalp_enabled=False,   # user's rule: scalp FX only; gold stays signal/backtest only
    typical_spread_pips=2.8,  # 0.28 USD at pip 0.1
    volume_reliable=True,
    max_leverage=20.0,
)

SILVER_MODEL = replace(GOLD_MODEL, min_adx=20.0, typical_spread_pips=3.0)

# Crypto trades 24/7 — no killzone, no weekend gap. Volume is a real exchange tape.
CRYPTO_MAJOR_MODEL = SymbolModel(
    thresholds={"1m": 75.0, "3m": 75.0, "5m": 74.0, "15m": 75.0},
    killzones_utc=(),
    weekend_gap_risk=False,
    min_adx=19.0,
    stop_atr_low=1.00, stop_atr_high=1.60,
    rr_base=1.60, rr_strong=1.90, rr_runner=2.50, rr_chop=1.35,
    scalp_enabled=True,
    typical_spread_pips=1.0,
    typical_spread_bps=2.0,    # BTC/ETH books are ~1-2 bps wide
    taker_fee_bps=10.0,        # Binance spot standard taker fee
    volume_reliable=True,
    max_leverage=10.0,
)

CRYPTO_ALT_MODEL = replace(
    CRYPTO_MAJOR_MODEL,
    thresholds={"1m": 78.0, "3m": 78.0, "5m": 77.0, "15m": 77.0},
    min_adx=22.0,
    stop_atr_low=1.10, stop_atr_high=1.80,
    rr_base=1.70, rr_strong=2.00, rr_runner=2.40, rr_chop=1.40,
    typical_spread_bps=8.0,    # thinner books, wider quotes
    taker_fee_bps=10.0,
    max_leverage=8.0,
)


# ─────────────────────────────────────────────────────────────────────────────
# Static FX universe (no key needed to enumerate it)
# ─────────────────────────────────────────────────────────────────────────────

MAJORS = ("EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD")

MINORS = (
    "EUR/GBP", "EUR/JPY", "EUR/CHF", "EUR/AUD", "EUR/CAD", "EUR/NZD",
    "GBP/JPY", "GBP/CHF", "GBP/AUD", "GBP/CAD", "GBP/NZD",
    "AUD/JPY", "AUD/CHF", "AUD/CAD", "AUD/NZD",
    "NZD/JPY", "NZD/CHF", "NZD/CAD",
    "CAD/JPY", "CAD/CHF", "CHF/JPY",
)

EXOTICS = (
    "USD/TRY", "USD/ZAR", "USD/MXN", "USD/SEK", "USD/NOK", "USD/DKK",
    "USD/PLN", "USD/HUF", "USD/CZK", "USD/SGD", "USD/HKD", "USD/CNH",
    "USD/THB", "USD/INR", "USD/KRW", "USD/ILS", "USD/RUB",
    "EUR/TRY", "EUR/SEK", "EUR/NOK", "EUR/PLN", "EUR/HUF", "EUR/CZK", "EUR/ZAR",
    "GBP/SEK", "GBP/NOK", "GBP/TRY", "GBP/ZAR", "GBP/SGD",
)

METALS = ("XAU/USD", "XAG/USD", "XPT/USD", "XPD/USD")

# Quote currencies that are conventionally quoted to 3 decimals (pip = 0.01).
_TWO_DECIMAL_QUOTES = {"JPY", "KRW", "HUF", "CLP", "IDR", "VND"}

_CRYPTO_MAJOR_BASES = {"BTC", "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "AVAX", "LINK", "DOT", "TRX", "LTC", "BCH", "MATIC", "TON"}

# Dollar-pegged assets. A stable/stable pair barely moves, so an ATR-based scalp on it is
# noise; and a coin quoted in BTC/ETH carries a second, uncontrolled price exposure.
_CRYPTO_STABLES = {"USDT", "USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USDP", "PYUSD"}
# Quote assets liquid and dollar-denominated enough to scalp against.
_SCALPABLE_CRYPTO_QUOTES = {"USDT", "FDUSD", "USDC"}
# Binance leveraged-token naming. These do not track spot linearly and must never be scalped.
_LEVERAGED_SUFFIXES = ("UPUSDT", "DOWNUSDT", "BULLUSDT", "BEARUSDT")


def crypto_is_scalpable(base: str, quote: str, symbol: str) -> bool:
    """Only dollar-quoted, non-stable, non-leveraged spot pairs get scalp entries."""
    base, quote, symbol = base.upper(), quote.upper(), symbol.upper()
    if quote not in _SCALPABLE_CRYPTO_QUOTES:
        return False
    if base in _CRYPTO_STABLES:          # USDC/USDT and friends: no real range
        return False
    if symbol.endswith(_LEVERAGED_SUFFIXES):
        return False
    return True


def _fx_quote_precision(quote: str) -> tuple[int, float, float]:
    """(price_precision, tick_size, pip_size) for an FX quote currency."""
    if quote.upper() in _TWO_DECIMAL_QUOTES:
        return 3, 0.001, 0.01
    return 5, 0.00001, 0.0001


def _fx_model(symbol: str) -> SymbolModel:
    quote = symbol.partition("/")[2].upper()
    if symbol in MAJORS:
        return JPY_FX_MODEL if quote == "JPY" else MAJOR_FX_MODEL
    if symbol in MINORS:
        return replace(JPY_FX_MODEL, **_minor_overrides()) if quote == "JPY" else MINOR_FX_MODEL
    return EXOTIC_FX_MODEL


def _minor_overrides() -> dict:
    return {
        "thresholds": MINOR_FX_MODEL.thresholds,
        "min_adx": MINOR_FX_MODEL.min_adx,
        "stop_atr_low": MINOR_FX_MODEL.stop_atr_low,
        "stop_atr_high": MINOR_FX_MODEL.stop_atr_high,
        "rr_base": MINOR_FX_MODEL.rr_base,
        "rr_strong": MINOR_FX_MODEL.rr_strong,
        "rr_chop": MINOR_FX_MODEL.rr_chop,
        "typical_spread_pips": MINOR_FX_MODEL.typical_spread_pips,
    }


_METAL_SPECS: dict[str, tuple[int, float, float, SymbolModel]] = {
    "XAU/USD": (2, 0.01, 0.10, GOLD_MODEL),
    "XAG/USD": (3, 0.001, 0.01, SILVER_MODEL),
    "XPT/USD": (2, 0.01, 0.10, SILVER_MODEL),
    "XPD/USD": (2, 0.01, 0.10, SILVER_MODEL),
}

_FX_SYMBOL_RE = re.compile(r"^[A-Z]{3,4}/[A-Z]{3,4}$")
_CRYPTO_SYMBOL_RE = re.compile(r"^[A-Z0-9]{2,20}$")

# Quote assets we accept from Binance (keeps the universe meaningful and USD-comparable).
BINANCE_QUOTES = ("USDT", "FDUSD", "USDC", "BTC", "ETH", "BNB", "TUSD", "EUR", "TRY")


def normalize(symbol: str) -> str:
    return (symbol or "").strip().upper().replace("\\", "/")


def decimals_from_tick(tick: float) -> int:
    """Binance gives a tickSize like '0.00001000'; derive the display precision from it."""
    if tick <= 0 or not math.isfinite(tick):
        return 8
    text = f"{tick:.12f}".rstrip("0")
    if "." not in text:
        return 0
    return max(0, min(12, len(text.split(".")[1])))


def crypto_spec(
    symbol: str,
    base: str,
    quote: str,
    tick_size: float,
    venue: str = "binance",
) -> InstrumentSpec:
    """Build a spec for a Binance SPOT symbol from its real exchange filters."""
    precision = decimals_from_tick(tick_size)
    model = CRYPTO_MAJOR_MODEL if base.upper() in _CRYPTO_MAJOR_BASES else CRYPTO_ALT_MODEL
    model = replace(model, scalp_enabled=crypto_is_scalpable(base, quote, symbol))
    return InstrumentSpec(
        symbol=symbol.upper(),
        kind=CRYPTO,
        base=base.upper(),
        quote=quote.upper(),
        price_precision=precision,
        tick_size=tick_size,
        # Crypto has no "pip" convention; 1 pip := 1 tick keeps pip maths coherent.
        pip_size=tick_size,
        contract_unit="coin",
        unit_label=base.upper(),
        display=f"{base.upper()}/{quote.upper()}",
        model=model,
        venue=venue,
    )


def forex_spec(symbol: str) -> InstrumentSpec:
    symbol = normalize(symbol)
    base, _, quote = symbol.partition("/")
    precision, tick, pip = _fx_quote_precision(quote)
    return InstrumentSpec(
        symbol=symbol,
        kind=FOREX,
        base=base,
        quote=quote,
        price_precision=precision,
        tick_size=tick,
        pip_size=pip,
        contract_unit="lot",
        unit_label="lot (100k)",
        display=symbol,
        model=_fx_model(symbol),
        venue="twelve_data",
    )


def metal_spec(symbol: str) -> InstrumentSpec:
    symbol = normalize(symbol)
    precision, tick, pip, model = _METAL_SPECS[symbol]
    base, _, quote = symbol.partition("/")
    return InstrumentSpec(
        symbol=symbol,
        kind=METAL,
        base=base,
        quote=quote,
        price_precision=precision,
        tick_size=tick,
        pip_size=pip,
        contract_unit="oz",
        unit_label="troy ounce",
        display=f"{base}/{quote}",
        model=model,
        venue="twelve_data",
    )


# Default spec used for backward compatibility with the single-symbol code paths.
GOLD_SPEC = metal_spec("XAU/USD")


def get_spec(symbol: str, *, crypto_tick: float | None = None) -> InstrumentSpec:
    """Resolve a spec for any supported symbol.

    `crypto_tick` lets a caller inject the real Binance tickSize it already fetched;
    without it a conservative precision is derived from the symbol's typical price range
    only when the caller explicitly asks for a crypto symbol.
    """
    symbol = normalize(symbol)
    if not symbol:
        raise ValueError("نماد خالی است")
    if symbol in _METAL_SPECS:
        return metal_spec(symbol)
    if _FX_SYMBOL_RE.fullmatch(symbol):
        return forex_spec(symbol)
    if _CRYPTO_SYMBOL_RE.fullmatch(symbol):
        quote = next((q for q in BINANCE_QUOTES if symbol.endswith(q) and len(symbol) > len(q)), None)
        if not quote:
            raise ValueError(f"نماد پشتیبانی نمی‌شود: {symbol}")
        base = symbol[: -len(quote)]
        return crypto_spec(symbol, base, quote, crypto_tick if crypto_tick and crypto_tick > 0 else 0.00000001)
    raise ValueError(f"نماد پشتیبانی نمی‌شود: {symbol}")


def static_forex_universe() -> list[InstrumentSpec]:
    """Every FX pair and metal this build knows about, without touching the network."""
    out = [metal_spec(s) for s in METALS]
    out += [forex_spec(s) for s in MAJORS + MINORS + EXOTICS]
    return out


def spec_to_dict(spec: InstrumentSpec) -> dict:
    return {
        "symbol": spec.symbol,
        "display": spec.display,
        "kind": spec.kind,
        "base": spec.base,
        "quote": spec.quote,
        "venue": spec.venue,
        "price_precision": spec.price_precision,
        "tick_size": spec.tick_size,
        "pip_size": spec.pip_size,
        "contract_unit": spec.contract_unit,
        "unit_label": spec.unit_label,
        "scalp_enabled": spec.model.scalp_enabled,
        "model": {
            "thresholds": spec.model.thresholds,
            "killzones_utc": [list(z) for z in spec.model.killzones_utc],
            "session_24h": not spec.model.killzones_utc,
            "weekend_gap_risk": spec.model.weekend_gap_risk,
            "min_adx": spec.model.min_adx,
            "stop_atr_low": spec.model.stop_atr_low,
            "stop_atr_high": spec.model.stop_atr_high,
            "rr_base": spec.model.rr_base,
            "rr_strong": spec.model.rr_strong,
            "rr_runner": spec.model.rr_runner,
            "rr_chop": spec.model.rr_chop,
            "typical_spread_pips": spec.model.typical_spread_pips,
            "typical_spread_bps": spec.model.typical_spread_bps,
            "taker_fee_bps": spec.model.taker_fee_bps,
            "cost_unit": spec.cost_unit,
            "max_leverage": spec.model.max_leverage,
            "volume_reliable": spec.model.volume_reliable,
        },
    }
