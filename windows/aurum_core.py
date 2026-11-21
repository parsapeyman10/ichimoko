"""Pure-Python core for the Aurum Edge Windows desktop application.

This module is the replacement for the former C++/Qt core.  It deliberately uses
only the Python standard library so the packaged program has one runtime
requirement: the Python interpreter while building.  It is a historical-analysis
and paper-journal tool; it contains no broker credentials or order-routing code.
"""

from __future__ import annotations

import calendar
import csv
import json
import math
import re
import sqlite3
from collections import Counter, deque
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from statistics import median
from typing import Callable, Iterable, Literal, Optional, Sequence

EPSILON = 1e-9
IntervalLabel = Literal["1m", "3m", "5m", "15m", "30m", "1H", "4H", "1D"]
INTERVAL_MS: dict[IntervalLabel, int] = {
    "1m": 60_000, "3m": 180_000, "5m": 300_000, "15m": 900_000,
    "30m": 1_800_000, "1H": 3_600_000, "4H": 14_400_000, "1D": 86_400_000,
}


class CsvError(ValueError):
    """Raised when a source file cannot safely become an OHLCV series."""


@dataclass(frozen=True)
class Candle:
    time_ms: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0
    closed: bool = True

    def valid(self) -> bool:
        values = (self.open, self.high, self.low, self.close, self.volume)
        return (
            self.time_ms > 0 and all(math.isfinite(value) for value in values)
            and self.open > 0 and self.close > 0 and self.high >= self.low
            and self.low <= self.open <= self.high and self.low <= self.close <= self.high
            and self.volume >= 0
        )


@dataclass
class CsvAudit:
    total_rows: int = 0
    accepted_rows: int = 0
    skipped_rows: int = 0
    duplicate_rows: int = 0
    flat_rows_dropped: int = 0
    source_kind: str = "candles"
    inferred_interval: str = "—"
    dominant_step_ms: int = 0
    suggested_symbol: str = ""
    first_time_ms: int = 0
    last_time_ms: int = 0


@dataclass(frozen=True)
class IchimokuPoint:
    tenkan: float = 0.0
    kijun: float = 0.0
    span_a: float = 0.0
    span_b: float = 0.0
    atr14: float = 0.0
    valid: bool = False


@dataclass
class BacktestConfig:
    starting_equity: float = 1000.0
    risk_fraction: float = 0.0025
    fee_per_side: float = 0.001
    slippage_per_side: float = 0.0001
    target_r: float = 2.0
    allow_long: bool = True
    allow_short: bool = True
    trend_runner_enabled: bool = True
    trend_runner_activation_r: float = 2.85
    trend_runner_atr_multiplier: float = 1.2

    def validate(self) -> None:
        if not (self.starting_equity > 0 and 0 < self.risk_fraction <= 0.02):
            raise ValueError("Starting equity and risk fraction are invalid.")
        if self.fee_per_side < 0 or self.slippage_per_side < 0 or self.target_r <= 0:
            raise ValueError("Fee, slippage and target R must be non-negative/positive.")


@dataclass
class Trade:
    side: Literal["BUY", "SELL"]
    entry_time_ms: int
    exit_time_ms: int
    entry: float
    stop: float
    target: float
    exit: float
    quantity: float
    fees: float
    net_pnl: float
    r_multiple: float
    equity_after: float
    reason: str


@dataclass
class BacktestStats:
    trades: int = 0
    wins: int = 0
    losses: int = 0
    win_rate: float = 0.0
    net_pnl: float = 0.0
    profit_factor: float = 0.0
    expectancy_r: float = 0.0
    final_equity: float = 0.0
    max_drawdown_pct: float = 0.0
    total_fees: float = 0.0


@dataclass
class BacktestResult:
    trades: list[Trade]
    stats: BacktestStats
    model_name: str = "Ichimoku 8/24/72 TK-cross baseline (Python port; not Peyman parity)"


@dataclass(frozen=True)
class VenueSpec:
    leverage_cap: int
    tick_size: float
    commission_per_unit: float = 0.0
    commission_bps_per_side: float = 0.0
    spread_price: float = 0.0
    spread_bps: float = 0.0
    min_order_quantity: float = 0.0001
    quantity_step: float = 0.0001
    min_notional_usd: float = 1.0
    contract_size_units: float = 1.0

    def spread_cost_usd(self, price: float, quantity: float) -> float:
        return price * quantity * self.spread_bps / 10_000 if self.spread_bps else self.spread_price * quantity

    def commission_usd(self, price: float, quantity: float) -> float:
        return price * quantity * self.commission_bps_per_side / 10_000 if self.commission_bps_per_side else self.commission_per_unit * quantity

    def min_stop_distance(self, price: float) -> float:
        spread = price * self.spread_bps / 10_000 if self.spread_bps else self.spread_price
        return max(2 * spread, 2 * self.tick_size)


@dataclass(frozen=True)
class TradePlanBounds:
    min_stop_pct: float
    max_stop_pct: float
    max_target_pct: float
    atr_factor_min: float = 0.80
    atr_factor_max: float = 2.10
    max_target_atr: float = 3.40
    venue_leverage_cap: int = 1


@dataclass
class GuardedTradePlan:
    symbol: str = ""
    side: Literal["BUY", "SELL"] = "BUY"
    interval_minutes: int = 15
    entry: float = 0.0
    stop_loss: float = 0.0
    take_profit: float = 0.0
    stop_distance: float = 0.0
    target_distance: float = 0.0
    risk_reward: float = 0.0
    stop_pct: float = 0.0
    target_pct: float = 0.0
    atr: float = 0.0
    recommended_leverage: int = 1
    liquidation_price: float = 0.0
    liquidation_buffer_r: float = 0.0
    usable: bool = False
    adjusted: bool = False
    note: str = ""


@dataclass
class RiskCalculation:
    symbol: str
    side: Literal["BUY", "SELL"]
    risk_budget_usd: float
    stop_distance: float
    target_distance: float
    risk_reward: float
    position_units: float
    standard_lots: float
    leverage: int
    notional_usd: float
    margin_required_usd: float
    commission_usd: float
    spread_cost_usd: float
    gross_profit_at_tp_usd: float
    gross_loss_at_sl_usd: float
    liquidation_price: float
    liquidation_buffer_r: float
    stop_pct_of_price: float
    target_pct_of_price: float
    plan_valid: bool
    plan_note: str


@dataclass(frozen=True)
class NeoWavePivot:
    index: int
    time_ms: int
    price: float
    is_high: bool


@dataclass
class NeoWaveVerdict:
    usable: bool = False
    complete: bool = False
    confirmed: bool = False
    hard_breach: bool = False
    expected_direction: int = 0
    rules_passed: int = 0
    rules_total: int = 0
    confidence_pct: float = 0.0
    badge: str = ""
    pattern: str = ""
    invalidation_price: Optional[float] = None
    next_target: Optional[float] = None
    monowave_count: int = 0
    structure_height: float = 0.0
    structure_label: str = ""
    retracement_rule: int = 0
    post_constructive_note: str = ""
    summary: str = ""


@dataclass
class NeuralTrainingReport:
    samples: int = 0
    wins: int = 0
    losses: int = 0
    average_r: float = 0.0
    predicted_win_rate_pct: float = 0.0
    in_sample_accuracy_pct: float = 0.0
    weights: list[float] = field(default_factory=lambda: [0.0] * 16)


def normalize_symbol(symbol: str) -> str:
    return "".join(symbol.upper().split())


def asset_class(symbol: str) -> str:
    clean = normalize_symbol(symbol)
    if clean.startswith(("XAU", "XAG", "WTI", "BRENT", "USOIL", "UKOIL", "NATGAS", "COPPER")) or clean in {"GOLD", "SILVER"}:
        return "commodity"
    if "/" in clean and len(clean.replace("/", "")) == 6:
        base, quote = clean.split("/", 1)
        if base in {"USD", "EUR", "JPY", "GBP", "CAD", "CHF", "AUD", "NZD"} and quote in {"USD", "EUR", "JPY", "GBP", "CAD", "CHF", "AUD", "NZD"}:
            return "forex"
    if clean.endswith(("USDT", "USDC", "/USD")) or clean.startswith(("BTC", "ETH", "SOL")):
        return "crypto"
    return "stock"


def venue_spec(symbol: str) -> VenueSpec:
    clean = normalize_symbol(symbol)
    category = asset_class(clean)
    if category == "crypto":
        major = clean.startswith(("SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "LINK", "TON", "DOT", "TRX", "LTC", "BCH", "SUI", "NEAR"))
        leverage = 10 if clean.startswith(("BTC", "ETH")) else 7 if major else 5
        tick = 0.01 if clean.startswith(("BTC", "ETH", "BNB", "SOL")) else 0.0001
        return VenueSpec(leverage, tick, commission_bps_per_side=10.0, spread_bps=1.0, min_notional_usd=5.0)
    if category == "forex":
        jpy = clean.endswith("JPY")
        return VenueSpec(10, 0.001 if jpy else 0.00001, commission_per_unit=0.000035, spread_price=0.001 if jpy else 0.00001, min_order_quantity=1000, quantity_step=100, min_notional_usd=10, contract_size_units=100000)
    if category == "commodity":
        if clean.startswith("XAU") or clean == "GOLD":
            return VenueSpec(10, 0.01, commission_per_unit=0.035, spread_price=0.12, min_order_quantity=0.01, quantity_step=0.01, min_notional_usd=10, contract_size_units=100)
        if clean.startswith("XAG") or clean == "SILVER":
            return VenueSpec(10, 0.001, commission_per_unit=0.0007, spread_price=0.02, min_order_quantity=0.1, quantity_step=0.01, min_notional_usd=10, contract_size_units=5000)
        return VenueSpec(10, 0.01, spread_price=0.03, min_order_quantity=0.1, quantity_step=0.01, min_notional_usd=10, contract_size_units=1000)
    return VenueSpec(10, 0.01, spread_price=0.01, quantity_step=0.01)


def quote_pnl_to_usd(symbol: str, quote_pnl: float, price: float) -> float:
    clean = normalize_symbol(symbol)
    return quote_pnl / price if clean.startswith("USD/") and clean.endswith(("JPY", "CHF", "CAD")) and price > 0 else quote_pnl


def round_to_tick(symbol: str, price: float) -> float:
    if not math.isfinite(price):
        return price
    tick = max(venue_spec(symbol).tick_size, 1e-8)
    return round(price / tick) * tick


def safe_leverage(symbol: str, side: Literal["BUY", "SELL"], entry: float, stop_distance: float) -> tuple[int, float, float]:
    spec = venue_spec(symbol)
    safe_entry = entry if math.isfinite(entry) and entry > 0 else 1.0
    safe_stop = stop_distance if math.isfinite(stop_distance) and stop_distance > 0 else safe_entry * 0.01
    maintenance = 0.005 if asset_class(symbol) == "crypto" else 0.004
    fee_pct = (spec.commission_bps_per_side * 2 + spec.spread_bps) / 10_000
    denominator = safe_stop / safe_entry * 2.5 + maintenance + fee_pct
    maximum = math.floor(1 / denominator) if denominator > 0 else 10
    leverage = max(1, min(10, spec.leverage_cap, maximum))
    distance_pct = min(0.999, max(0.005, 1 / leverage - 0.005))
    liquidation = safe_entry * (1 + distance_pct) if side == "SELL" else max(0.00001, safe_entry * (1 - distance_pct))
    liquidation = round_to_tick(symbol, liquidation)
    return leverage, liquidation, round(abs(liquidation - safe_entry) / safe_stop, 2)


def calculate_position(symbol: str, account_balance: float, risk_percent: float, entry: float, stop_loss: float, take_profit: float) -> Optional[RiskCalculation]:
    values = (account_balance, risk_percent, entry, stop_loss, take_profit)
    if not all(math.isfinite(value) for value in values) or min(values) <= 0:
        return None
    buy = stop_loss < entry < take_profit
    sell = take_profit < entry < stop_loss
    if not (buy or sell):
        return None
    stop_distance, target_distance = abs(entry - stop_loss), abs(take_profit - entry)
    if stop_distance <= EPSILON or target_distance <= EPSILON:
        return None
    clean, spec = normalize_symbol(symbol), venue_spec(symbol)
    risk_budget = account_balance * risk_percent / 100
    unit_loss = quote_pnl_to_usd(clean, stop_distance, entry)
    if unit_loss <= EPSILON:
        return None
    units = round(max(0.0001, risk_budget / unit_loss), 4)
    notional = quote_pnl_to_usd(clean, units * entry, entry)
    side: Literal["BUY", "SELL"] = "BUY" if buy else "SELL"
    leverage, liquidation, buffer_r = safe_leverage(clean, side, entry, stop_distance)
    rr, stop_pct, target_pct = target_distance / stop_distance, stop_distance / entry, target_distance / entry
    max_stop = {"crypto": 0.016, "forex": 0.0028, "commodity": 0.0055, "stock": 0.005}[asset_class(clean)]
    valid = 1.5 - 1e-6 <= rr <= 3.0 + 1e-6 and stop_pct <= max_stop * 1.02 and stop_distance >= spec.min_stop_distance(entry)
    note = "پلن از نظر جهت، R:R و محدودهٔ پایهٔ نوسان معتبر است." if valid else "R:R باید بین ۱٫۵ تا ۳ باشد و حد ضرر باید بیرون از نویز و داخل دالان نوسان باشد."
    if valid and notional / leverage > account_balance * 0.30:
        note = "مارجین از ۳۰٪ موجودی بیشتر است؛ حجم را کم کنید."
    return RiskCalculation(clean, side, round(risk_budget, 2), stop_distance, target_distance, round(rr, 2), units, round(units / spec.contract_size_units, 4), leverage, round(notional, 2), round(notional / leverage, 2), round(spec.commission_usd(entry, units) * 2, 2), round(spec.spread_cost_usd(entry, units), 2), round(risk_budget * rr, 2), round(risk_budget, 2), liquidation, buffer_r, round(stop_pct, 4), round(target_pct, 4), valid, note)


def trade_plan_bounds(symbol: str, interval_minutes: int = 15) -> TradePlanBounds:
    raw_min, raw_max = {"crypto": (0.0016, 0.016), "forex": (0.0003, 0.0028), "commodity": (0.0007, 0.0055), "stock": (0.0006, 0.005)}[asset_class(symbol)]
    scale = math.sqrt(max(1, interval_minutes) / 15)
    maximum = min(0.065, max(0.00036, raw_max * scale))
    minimum = min(maximum * 0.4, max(0.00018, raw_min * scale))
    return TradePlanBounds(minimum, maximum, maximum * 1.8, venue_leverage_cap=min(10, venue_spec(symbol).leverage_cap))


def robust_atr(symbol: str, price: float, atr_value: float, interval_minutes: int = 15) -> float:
    if not math.isfinite(price) or price <= 0:
        return 0.0
    bounds = trade_plan_bounds(symbol, interval_minutes)
    floor, ceiling, fallback = price * bounds.max_stop_pct / 9, price * bounds.max_stop_pct / 1.6, price * bounds.max_stop_pct / 2.6
    raw = atr_value if math.isfinite(atr_value) and atr_value > 0 else fallback
    return min(ceiling, max(floor, raw))


def min_safe_structural_stop_distance(symbol: str, price: float, trigger_atr: float, strategy_atr: float = 0.0, five_minute: bool = True) -> float:
    clean = normalize_symbol(symbol)
    safe_price = price if math.isfinite(price) and price > 0 else 1.0
    trigger = trigger_atr if math.isfinite(trigger_atr) and trigger_atr > 0 else safe_price * 0.002
    strategy = strategy_atr if math.isfinite(strategy_atr) and strategy_atr > 0 else trigger
    venue = venue_spec(clean).min_stop_distance(safe_price)
    kind = asset_class(clean)
    if kind == "crypto":
        major = clean.startswith(("BTC", "ETH"))
        floor = 0.0016 if major and five_minute else 0.0020 if major else 0.0020 if five_minute else 0.0025
        return max(venue * 3, safe_price * floor, trigger * 0.82, strategy * 0.28)
    if kind == "commodity":
        return max(venue * 2.5, safe_price * (0.0014 if clean.startswith("XAG") else 0.0009), trigger * 0.78, strategy * 0.25)
    if kind == "forex":
        return max(venue * 2.5, safe_price * 0.0004, trigger * 0.75, strategy * 0.24)
    return max(venue * 2.5, safe_price * 0.0009, trigger * 0.78, strategy * 0.25)


def sanitize_trade_plan(symbol: str, side: Literal["BUY", "SELL"], entry: float, candidate_stop: Optional[float], candidate_target: Optional[float], desired_rr: float = 2.0, reference_atr: float = 0.0, interval_minutes: int = 15, strategy_atr: float = 0.0, anchor_entry: Optional[float] = None) -> GuardedTradePlan:
    clean = normalize_symbol(symbol)
    rejected = GuardedTradePlan(symbol=clean, side=side, interval_minutes=interval_minutes, entry=entry, note="قیمت ورود نامعتبر است.")
    if not math.isfinite(entry) or entry <= 0:
        return rejected
    bounds = trade_plan_bounds(clean, interval_minutes)
    atr_input = reference_atr if reference_atr > 0 else strategy_atr
    atr = robust_atr(clean, entry, atr_input, interval_minutes)
    structural = min_safe_structural_stop_distance(clean, entry, atr, strategy_atr or atr, interval_minutes == 5)
    corridor = entry * bounds.max_stop_pct
    venue = min(venue_spec(clean).min_stop_distance(entry) * 2, corridor)
    floor = min(corridor, max(min(structural, corridor), bounds.min_stop_pct * entry, bounds.atr_factor_min * atr, venue))
    ceiling = max(floor, min(corridor, bounds.atr_factor_max * atr))
    direction = 1 if side == "BUY" else -1
    anchor = anchor_entry if anchor_entry and anchor_entry > 0 else entry
    raw_stop = abs(anchor - candidate_stop) if candidate_stop and ((direction > 0 and candidate_stop < anchor) or (direction < 0 and candidate_stop > anchor)) else 0.0
    rr = min(3.0, max(1.5, desired_rr))
    target_ceiling = max(floor * 1.5, min(bounds.max_target_pct * entry, bounds.max_target_atr * atr) * 0.995)
    stop_upper = max(floor, min(ceiling, target_ceiling / rr) * 0.995)
    resolved = raw_stop or floor
    stop_distance = min(stop_upper, max(floor, resolved))
    raw_target = abs(candidate_target - anchor) if candidate_target and ((direction > 0 and candidate_target > anchor) or (direction < 0 and candidate_target < anchor)) else 0.0
    structured = raw_target if raw_target and raw_target / stop_distance >= 1.5 else stop_distance * rr
    target_upper = max(stop_distance * 1.5, min(stop_distance * 3, target_ceiling))
    target_distance = min(target_upper, max(stop_distance * 1.5, structured))
    stop = round_to_tick(clean, max(0.00001, entry - direction * stop_distance))
    target = round_to_tick(clean, max(0.00001, entry + direction * target_distance))
    final_stop, final_target = abs(entry - stop), abs(target - entry)
    leverage, liquidation, buffer = safe_leverage(clean, side, entry, final_stop)
    adjusted = abs(final_stop - resolved) / max(resolved, EPSILON) > 0.01 or abs(final_target - structured) / max(structured, EPSILON) > 0.03
    note = "پلن داخل دالان نوسان و R:R معتبر است." if not adjusted else "حد ضرر یا حد سود برای دالان نوسان و R:R معتبر اصلاح شد."
    return GuardedTradePlan(clean, side, interval_minutes, round_to_tick(clean, entry), stop, target, final_stop, final_target, round(min(3, max(1.5, final_target / max(final_stop, EPSILON))), 2), final_stop / entry, final_target / entry, atr, leverage, liquidation, buffer, True, adjusted, note)


def _number(value: str) -> float:
    return float(value.strip().replace(",", ""))


def _parse_time(value: str) -> int:
    text = value.strip()
    try:
        parsed = float(text)
        if math.isfinite(parsed) and parsed > 0:
            return int(parsed * 1000 if parsed < 10_000_000_000 else parsed)
    except ValueError:
        pass
    try:
        parsed_time = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed_time.tzinfo is None:
            parsed_time = parsed_time.replace(tzinfo=timezone.utc)
        return int(parsed_time.timestamp() * 1000)
    except ValueError as error:
        raise CsvError(f"Invalid timestamp: {value!r}") from error


def _symbol_from_filename(name: str) -> str:
    match = re.match(r"([A-Za-z]{6})-", name)
    return f"{match.group(1)[:3].upper()}/{match.group(1)[3:].upper()}" if match else ""


def _nearest_interval(step_ms: int) -> str:
    if step_ms <= 0:
        return "—"
    return min(INTERVAL_MS, key=lambda label: abs(INTERVAL_MS[label] - step_ms))


def read_csv(path: Path, drop_flat_bars: bool = True) -> tuple[list[Candle], CsvAudit]:
    if not path.is_file():
        raise CsvError("CSV file was not found.")
    try:
        handle = path.open("r", encoding="utf-8-sig", newline="")
    except UnicodeDecodeError:
        handle = path.open("r", encoding="latin-1", newline="")
    with handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames:
            raise CsvError("CSV header is missing.")
        columns = {name.strip().lower(): name for name in reader.fieldnames if name}
        def column(*names: str) -> Optional[str]:
            return next((columns[name] for name in names if name in columns), None)
        timestamp, open_, high, low, close = column("timestamp", "time", "datetime", "date"), column("open", "o"), column("high", "h"), column("low", "l"), column("close", "c")
        ask, bid, volume = column("askprice", "ask"), column("bidprice", "bid"), column("volume", "v")
        candles = all((timestamp, open_, high, low, close))
        ticks = timestamp is not None and (ask is not None or bid is not None)
        if not timestamp or not (candles or ticks):
            raise CsvError("CSV needs timestamp/time and OHLC columns (or bid/ask tick columns).")
        audit = CsvAudit(source_kind="candles" if candles else "ticks", suggested_symbol=_symbol_from_filename(path.name))
        seen: set[int] = set()
        result: list[Candle] = []
        for row in reader:
            audit.total_rows += 1
            try:
                time_ms = _parse_time(row[timestamp])
                if candles:
                    item = Candle(time_ms, _number(row[open_]), _number(row[high]), _number(row[low]), _number(row[close]), _number(row[volume]) if volume and row.get(volume, "").strip() else 0.0)
                else:
                    ask_value = _number(row[ask]) if ask and row.get(ask, "").strip() else 0.0
                    bid_value = _number(row[bid]) if bid and row.get(bid, "").strip() else 0.0
                    midpoint = (ask_value + bid_value) / 2 if ask_value > 0 and bid_value > 0 else max(ask_value, bid_value)
                    item = Candle(time_ms, midpoint, midpoint, midpoint, midpoint, _number(row[volume]) if volume and row.get(volume, "").strip() else 0.0)
                if not item.valid():
                    raise CsvError("invalid OHLC")
            except (ValueError, KeyError, CsvError):
                audit.skipped_rows += 1
                continue
            if item.time_ms in seen:
                audit.duplicate_rows += 1
                continue
            seen.add(item.time_ms)
            result.append(item)
    result.sort(key=lambda bar: bar.time_ms)
    if drop_flat_bars and volume:
        before = len(result)
        result = [bar for bar in result if bar.volume > 0]
        audit.flat_rows_dropped = before - len(result)
    if not result:
        raise CsvError("No valid non-flat rows remained in the CSV.")
    steps = [result[index].time_ms - result[index - 1].time_ms for index in range(1, len(result)) if result[index].time_ms > result[index - 1].time_ms]
    audit.dominant_step_ms = Counter(steps).most_common(1)[0][0] if steps else 0
    audit.inferred_interval = _nearest_interval(audit.dominant_step_ms)
    audit.accepted_rows, audit.first_time_ms, audit.last_time_ms = len(result), result[0].time_ms, result[-1].time_ms
    return result, audit


def _rolling_midpoint(bars: Sequence[Candle], period: int, value: str) -> list[float]:
    maximum: deque[int] = deque()
    minimum: deque[int] = deque()
    output = [0.0] * len(bars)
    for index, bar in enumerate(bars):
        value_high, value_low = getattr(bar, "high"), getattr(bar, "low")
        while maximum and getattr(bars[maximum[-1]], "high") <= value_high:
            maximum.pop()
        while minimum and getattr(bars[minimum[-1]], "low") >= value_low:
            minimum.pop()
        maximum.append(index); minimum.append(index)
        while maximum[0] <= index - period: maximum.popleft()
        while minimum[0] <= index - period: minimum.popleft()
        if index + 1 >= period:
            output[index] = (getattr(bars[maximum[0]], "high") + getattr(bars[minimum[0]], "low")) / 2
    return output


def ichimoku(bars: Sequence[Candle], tenkan_period: int = 8, kijun_period: int = 24, span_b_period: int = 72) -> list[IchimokuPoint]:
    tenkan = _rolling_midpoint(bars, tenkan_period, "high")
    kijun = _rolling_midpoint(bars, kijun_period, "high")
    span_b = _rolling_midpoint(bars, span_b_period, "high")
    points: list[IchimokuPoint] = []
    atr_values: list[float] = []
    for index, bar in enumerate(bars):
        previous = bars[index - 1].close if index else bar.close
        true_range = max(bar.high - bar.low, abs(bar.high - previous), abs(bar.low - previous))
        atr_values.append(true_range)
        atr = sum(atr_values[max(0, index - 13): index + 1]) / min(14, index + 1)
        valid = index + 1 >= span_b_period
        points.append(IchimokuPoint(tenkan[index], kijun[index], (tenkan[index] + kijun[index]) / 2, span_b[index], atr, valid))
    return points


def _trend_runner(side: str, entry: float, initial_risk: float, current_stop: float, closed_bar: Candle, now: IchimokuPoint, before: IchimokuPoint, activation_r: float, atr_multiplier: float) -> tuple[bool, float]:
    direction = 1 if side == "BUY" else -1
    unrealized_r = direction * (closed_bar.close - entry) / initial_risk if initial_risk > EPSILON else 0.0
    if not closed_bar.closed or not now.valid or not before.valid or now.atr14 <= 0 or unrealized_r < activation_r:
        return False, current_stop
    cloud_high, cloud_low = max(now.span_a, now.span_b), min(now.span_a, now.span_b)
    healthy = (closed_bar.close > cloud_high and closed_bar.close > now.kijun and now.tenkan >= now.kijun and now.tenkan >= before.tenkan) if side == "BUY" else (closed_bar.close < cloud_low and closed_bar.close < now.kijun and now.tenkan <= now.kijun and now.tenkan <= before.tenkan)
    if not healthy:
        return False, current_stop
    volatility_stop = closed_bar.close - direction * max(0.1, atr_multiplier) * now.atr14
    structure_stop = now.kijun - direction * 0.2 * now.atr14
    candidate = max(volatility_stop, structure_stop) if side == "BUY" else min(volatility_stop, structure_stop)
    trailing = max(current_stop, candidate) if side == "BUY" else min(current_stop, candidate)
    return True, current_stop if (trailing >= closed_bar.close if side == "BUY" else trailing <= closed_bar.close) else trailing


def run_ichimoku_baseline(bars: Sequence[Candle], config: Optional[BacktestConfig] = None, progress: Optional[Callable[[int, int], None]] = None) -> BacktestResult:
    if len(bars) < 80:
        raise ValueError("At least 80 candles are required for the 8/24/72 warm-up.")
    config = config or BacktestConfig()
    config.validate()
    points = ichimoku(bars)
    trades: list[Trade] = []
    equity = peak = config.starting_equity
    max_drawdown = 0.0
    position: Optional[dict[str, float | int | bool | str]] = None
    pending: Optional[str] = None

    def close(position_to_close: dict[str, float | int | bool | str], raw_exit: float, time_ms: int, reason: str) -> None:
        nonlocal equity, peak, max_drawdown
        side = str(position_to_close["side"])
        direction = 1 if side == "BUY" else -1
        entry, quantity, entry_fee = float(position_to_close["entry"]), float(position_to_close["quantity"]), float(position_to_close["entry_fee"])
        exit_price = raw_exit * (1 - direction * config.slippage_per_side)
        exit_fee = exit_price * quantity * config.fee_per_side
        net = direction * (exit_price - entry) * quantity - entry_fee - exit_fee
        equity += net
        risk = max(float(position_to_close["risk_budget"]), EPSILON)
        trades.append(Trade(side, int(position_to_close["entry_time"]), time_ms, entry, float(position_to_close["stop"]), float(position_to_close["target"]), exit_price, quantity, entry_fee + exit_fee, net, net / risk, equity, reason))
        peak = max(peak, equity)
        if peak > 0:
            max_drawdown = max(max_drawdown, (peak - equity) / peak * 100)

    for index in range(1, len(bars)):
        if progress and (index % 250 == 0 or index == len(bars) - 1):
            progress(index + 1, len(bars))
        bar = bars[index]
        if position:
            side, direction = str(position["side"]), 1 if position["side"] == "BUY" else -1
            if config.trend_runner_enabled and index >= 2:
                active, trailing = _trend_runner(side, float(position["entry"]), float(position["initial_risk"]), float(position["stop"]), bars[index - 1], points[index - 1], points[index - 2], config.trend_runner_activation_r, config.trend_runner_atr_multiplier)
                position["stop"], position["runner_active"] = trailing, bool(position["runner_active"]) or active
            stop, target = float(position["stop"]), float(position["target"])
            stop_hit = bar.low <= stop if side == "BUY" else bar.high >= stop
            target_hit = not bool(position["runner_active"]) and (bar.high >= target if side == "BUY" else bar.low <= target)
            adverse = bar.low if side == "BUY" else bar.high
            mtm = equity + direction * (adverse - float(position["entry"])) * float(position["quantity"]) - float(position["entry_fee"])
            if peak > 0:
                max_drawdown = max(max_drawdown, (peak - mtm) / peak * 100)
            if stop_hit:
                close(position, min(bar.open, stop) if side == "BUY" else max(bar.open, stop), bar.time_ms, "TREND_EXIT" if bool(position["runner_active"]) else "STOP_LOSS")
                position, pending = None, None
                continue
            if target_hit:
                close(position, max(bar.open, target) if side == "BUY" else min(bar.open, target), bar.time_ms, "TAKE_PROFIT")
                position, pending = None, None
                continue
        if position is None and pending:
            side, direction = pending, 1 if pending == "BUY" else -1
            entry = bar.open * (1 + direction * config.slippage_per_side)
            previous, atr = bars[index - 1], points[index - 1].atr14
            recent = bars[max(0, index - 8):index]
            extreme = min(item.low for item in recent) if side == "BUY" else max(item.high for item in recent)
            stop = extreme - direction * 0.1 * atr
            risk_distance = direction * (entry - stop)
            if risk_distance > EPSILON:
                risk_budget = equity * config.risk_fraction
                stop_exit = stop * (1 - direction * config.slippage_per_side)
                unit_loss = abs(entry - stop_exit) + config.fee_per_side * (entry + stop_exit)
                quantity = risk_budget / unit_loss
                entry_fee = entry * quantity * config.fee_per_side
                planned_r = max(3.0, config.target_r) if config.trend_runner_enabled else config.target_r
                position = {"side": side, "entry_time": bar.time_ms, "entry": entry, "stop": stop, "target": entry + direction * planned_r * risk_distance, "quantity": quantity, "entry_fee": entry_fee, "risk_budget": risk_budget, "initial_risk": risk_distance, "runner_active": False}
                stop_hit = bar.low <= stop if side == "BUY" else bar.high >= stop
                target_hit = bar.high >= float(position["target"]) if side == "BUY" else bar.low <= float(position["target"])
                if stop_hit:
                    close(position, stop, bar.time_ms, "STOP_LOSS"); position = None
                elif target_hit:
                    close(position, float(position["target"]), bar.time_ms, "TAKE_PROFIT"); position = None
            pending = None
            continue
        if position or index < 2 or not points[index - 1].valid or not points[index - 2].valid:
            continue
        now, before, signal_bar = points[index - 1], points[index - 2], bars[index - 1]
        cloud_high, cloud_low = max(now.span_a, now.span_b), min(now.span_a, now.span_b)
        crossed_up = before.tenkan <= before.kijun and now.tenkan > now.kijun
        crossed_down = before.tenkan >= before.kijun and now.tenkan < now.kijun
        if config.allow_long and crossed_up and signal_bar.close > cloud_high:
            pending = "BUY"
        elif config.allow_short and crossed_down and signal_bar.close < cloud_low:
            pending = "SELL"
    if position and equity > 0:
        close(position, bars[-1].close, bars[-1].time_ms, "END_OF_DATA")
    gross_wins = sum(trade.net_pnl for trade in trades if trade.net_pnl > 0)
    gross_losses = -sum(trade.net_pnl for trade in trades if trade.net_pnl < 0)
    stats = BacktestStats(len(trades), sum(trade.net_pnl > 0 for trade in trades), sum(trade.net_pnl < 0 for trade in trades), 100 * sum(trade.net_pnl > 0 for trade in trades) / len(trades) if trades else 0, equity - config.starting_equity, gross_wins / gross_losses if gross_losses else (1e9 if gross_wins else 0), sum(trade.r_multiple for trade in trades) / len(trades) if trades else 0, equity, max_drawdown, sum(trade.fees for trade in trades))
    return BacktestResult(trades, stats)


def neo_wave_atr(candles: Sequence[Candle], period: int = 14) -> float:
    if len(candles) < 2:
        return max(0.0, candles[0].high - candles[0].low) if candles else 0.0
    tail = candles[max(1, len(candles) - max(2, min(50, period))):]
    values = [max(item.high - item.low, abs(item.high - candles[index].close), abs(item.low - candles[index].close)) for index, item in enumerate(tail, start=max(1, len(candles) - max(2, min(50, period))))]
    return sum(values) / len(values) if values else 0.0


def neo_wave_pivots(candles: Sequence[Candle], amplitude_atr: float = 0.55, window: int = 3) -> list[NeoWavePivot]:
    bars = [bar for bar in candles if bar.closed][-240:] or list(candles)[-240:]
    if len(bars) < 45:
        return []
    threshold = max(neo_wave_atr(bars) * min(2.0, max(0.2, amplitude_atr)), bars[-1].close * 0.0005)
    width = min(5, max(1, window))
    raw: list[NeoWavePivot] = []
    for index in range(width, len(bars) - width):
        neighbors = bars[index - width:index + width + 1]
        if all(bars[index].high >= item.high for item in neighbors): raw.append(NeoWavePivot(index, bars[index].time_ms, bars[index].high, True))
        if all(bars[index].low <= item.low for item in neighbors): raw.append(NeoWavePivot(index, bars[index].time_ms, bars[index].low, False))
    raw.sort(key=lambda pivot: pivot.index)
    merged: list[NeoWavePivot] = []
    for pivot in raw:
        if not merged or merged[-1].is_high != pivot.is_high:
            merged.append(pivot)
        elif (pivot.is_high and pivot.price >= merged[-1].price) or (not pivot.is_high and pivot.price <= merged[-1].price):
            merged[-1] = pivot
    result: list[NeoWavePivot] = []
    for pivot in merged:
        if not result or abs(pivot.price - result[-1].price) >= threshold:
            result.append(pivot)
    return result


def neo_wave_dynamic_amplitude(candles: Sequence[Candle], base_amplitude: float = 0.55) -> float:
    bars = [bar for bar in candles if bar.closed][-240:] or list(candles)[-240:]
    if len(bars) < 60:
        return min(1.2, max(0.35, base_amplitude))
    short, long = neo_wave_atr(bars[-40:]), neo_wave_atr(bars[-160:])
    multiplier = min(1.2, max(0.85, short / long)) if short > 0 and long > 0 else 1.0
    return min(1.2, max(0.35, base_amplitude * multiplier))


def neo_wave_retracement_rule(ratio: float) -> int:
    return 1 if ratio < .382 else 2 if ratio < .618 else 3 if ratio < .66 else 4 if ratio < 1 else 5 if ratio < 1.618 else 6 if ratio <= 2.618 else 7


def analyze_neo_wave(candles: Sequence[Candle], amplitude_atr: float = 0.55) -> Optional[NeoWaveVerdict]:
    bars = [bar for bar in candles if bar.closed][-240:] or list(candles)[-240:]
    pivots = neo_wave_pivots(bars, amplitude_atr)
    if len(pivots) < 4:
        return None
    legs = [(pivots[index - 1], pivots[index], abs(pivots[index].price - pivots[index - 1].price), 1 if pivots[index].price > pivots[index - 1].price else -1) for index in range(1, len(pivots))]
    if len(legs) < 3:
        return None
    last = legs[-5:]
    directions = [leg[3] for leg in last]
    impulse = len(last) == 5 and directions[0] == directions[2] == directions[4] and directions[1] == directions[3] == -directions[0]
    candidate = last if impulse else legs[-3:]
    lengths = [item[2] for item in candidate]
    direction = -candidate[-1][3] if impulse else candidate[-1][3]
    retracement = lengths[1] / lengths[0] if len(lengths) > 1 and lengths[0] > EPSILON else 0.0
    passed = int(0.236 <= retracement <= 1.382) + int(lengths[-1] >= lengths[0] * 0.618) + int(all(item[2] > EPSILON for item in candidate)) + int(abs(candidate[-1][1].index - candidate[0][0].index) >= len(candidate))
    total = 4
    height = abs(candidate[-1][1].price - candidate[0][0].price)
    live_move = bars[-1].close - pivots[-1].price
    confirmed = live_move * direction >= height * .236
    pattern = "IMPULSE" if impulse else "CORRECTIVE"
    confidence = max(0.0, min(100.0, (passed / total * 100) - (0 if confirmed else 10)))
    target = pivots[-1].price + direction * max(lengths) * .618
    return NeoWaveVerdict(True, impulse, confirmed, False, direction, passed, total, round(confidence, 2), pattern, pattern, pivots[-1].price, target, len(legs), height, ":L5" if impulse else ":c3", neo_wave_retracement_rule(retracement), "حرکت پس از ساختار تأیید شده است." if confirmed else "ساختار هنوز تأیید کافی ندارد.", f"{pattern} | {passed}/{total} rules | {'confirmed' if confirmed else 'unconfirmed'} | {'BUY' if direction > 0 else 'SELL'}")


def train_neural_baseline(candles: Sequence[Candle], trades: Sequence[Trade], symbol: str) -> NeuralTrainingReport:
    report = NeuralTrainingReport()
    if not candles or not trades:
        return report
    points = ichimoku(candles)
    by_time = {bar.time_ms: index for index, bar in enumerate(candles)}
    samples: list[list[float]] = []; targets: list[int] = []
    for trade in trades:
        index = by_time.get(trade.entry_time_ms)
        if index is None or index == 0: continue
        bar, point = candles[index], points[index]
        start = max(0, index - 14); path = sum(abs(candles[item].close - candles[item - 1].close) for item in range(start + 1, index + 1))
        efficiency = abs(bar.close - candles[start].close) / path if path else 0.0
        candle_range = max(EPSILON, bar.high - bar.low)
        adverse_wick = ((bar.high - max(bar.open, bar.close)) if trade.side == "BUY" else (min(bar.open, bar.close) - bar.low)) / candle_range
        rr = abs(trade.target - trade.entry) / max(EPSILON, abs(trade.entry - trade.stop))
        kind = asset_class(symbol)
        clip = lambda value: min(1.0, max(-1.0, value))
        features = [1.0 if kind == name else -1.0 for name in ("crypto", "commodity", "forex", "stock")] + [1.0, 1.0 if trade.side == "BUY" else -1.0, -1.0, -1.0, -1.0, -1.0, clip(efficiency * 2 - 1), clip(abs(point.span_a - point.span_b) / point.atr14 - .8) if point.valid and point.atr14 else .15, clip((abs(bar.close - point.kijun) / point.atr14 - 1.2) / 1.5) if point.valid and point.atr14 else -.2, clip((adverse_wick - .25) * 2.5), clip((max(rr, 1.5) - 2) / 1.5), 0.0]
        samples.append(features); targets.append(int(trade.net_pnl > 0)); report.average_r += trade.r_multiple
    if not samples:
        return report
    report.samples, report.wins, report.losses = len(samples), sum(targets), len(samples) - sum(targets)
    report.average_r /= len(samples)
    bias = 0.0
    sigmoid = lambda value: 1 / (1 + math.exp(-max(-20, min(20, value))))
    for _ in range(12):
        for features, target in zip(samples, targets):
            probability = sigmoid(bias + sum(weight * feature for weight, feature in zip(report.weights, features)))
            error = probability - target
            report.weights = [weight - .035 * (error * feature + .0005 * weight) for weight, feature in zip(report.weights, features)]
            bias -= .035 * error
    probabilities = [sigmoid(bias + sum(weight * feature for weight, feature in zip(report.weights, features))) for features in samples]
    report.predicted_win_rate_pct = sum(probabilities) * 100 / len(probabilities)
    report.in_sample_accuracy_pct = sum((probability >= .5) == bool(target) for probability, target in zip(probabilities, targets)) * 100 / len(probabilities)
    return report


class JournalStore:
    """SQLite journal replacing the former QtSql implementation."""
    def __init__(self, database_path: Path) -> None:
        database_path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(database_path)
        self.connection.execute("CREATE TABLE IF NOT EXISTS journal_trades (id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, side TEXT NOT NULL, entry REAL NOT NULL, stop REAL NOT NULL, target REAL NOT NULL, exit REAL NOT NULL, net_pnl REAL NOT NULL, closed_at_ms INTEGER NOT NULL)")
        self.connection.commit()

    def add_trade(self, symbol: str, side: str, entry: float, stop: float, target: float, exit_price: float, net_pnl: float, closed_at_ms: Optional[int] = None) -> None:
        if not normalize_symbol(symbol) or entry <= 0 or exit_price <= 0:
            raise ValueError("Symbol, entry and exit must be valid.")
        closed_at_ms = closed_at_ms or int(datetime.now(tz=timezone.utc).timestamp() * 1000)
        self.connection.execute("INSERT INTO journal_trades(symbol,side,entry,stop,target,exit,net_pnl,closed_at_ms) VALUES(?,?,?,?,?,?,?,?)", (normalize_symbol(symbol), "SELL" if side.upper() == "SELL" else "BUY", entry, stop, target, exit_price, net_pnl, closed_at_ms))
        self.connection.commit()

    def recent(self, limit: int = 200) -> list[dict[str, object]]:
        rows = self.connection.execute("SELECT symbol,side,entry,stop,target,exit,net_pnl,closed_at_ms FROM journal_trades ORDER BY closed_at_ms DESC LIMIT ?", (max(1, min(limit, 1000)),)).fetchall()
        return [dict(zip(("symbol", "side", "entry", "stop", "target", "exit", "net_pnl", "closed_at_ms"), row)) for row in rows]

    def between(self, start_ms: int, end_ms: int) -> list[dict[str, object]]:
        rows = self.connection.execute("SELECT symbol,side,entry,stop,target,exit,net_pnl,closed_at_ms FROM journal_trades WHERE closed_at_ms >= ? AND closed_at_ms < ? ORDER BY closed_at_ms DESC", (start_ms, end_ms)).fetchall()
        return [dict(zip(("symbol", "side", "entry", "stop", "target", "exit", "net_pnl", "closed_at_ms"), row)) for row in rows]

    def close(self) -> None:
        self.connection.close()


def monthly_calendar(store: JournalStore, year: int, month: int) -> dict[str, object]:
    start = datetime(year, month, 1, tzinfo=timezone.utc)
    end = datetime(year + 1, 1, 1, tzinfo=timezone.utc) if month == 12 else datetime(year, month + 1, 1, tzinfo=timezone.utc)
    rows = store.between(int(start.timestamp() * 1000), int(end.timestamp() * 1000))
    daily: dict[int, dict[str, float | int]] = {}
    for row in rows:
        day = datetime.fromtimestamp(int(row["closed_at_ms"]) / 1000, tz=timezone.utc).day
        item = daily.setdefault(day, {"net": 0.0, "count": 0})
        item["net"] = float(item["net"]) + float(row["net_pnl"]); item["count"] = int(item["count"]) + 1
    entries = [{"day": day, "net": round(float(daily.get(day, {}).get("net", 0.0)), 2), "count": int(daily.get(day, {}).get("count", 0))} for day in range(1, calendar.monthrange(year, month)[1] + 1)]
    net = sum(float(row["net_pnl"]) for row in rows); wins = sum(float(row["net_pnl"]) > 0 for row in rows)
    return {"entries": entries, "net": net, "wins": wins, "losses": len(rows) - wins, "win_rate": wins * 100 / len(rows) if rows else 0.0, "winning_days": sum(entry["net"] > 0 for entry in entries), "losing_days": sum(entry["net"] < 0 for entry in entries)}


def export_journal(rows: Sequence[dict[str, object]], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    suffix = destination.suffix.lower()
    if suffix == ".json":
        destination.write_text(json.dumps(list(rows), ensure_ascii=False, indent=2), encoding="utf-8")
    elif suffix == ".pdf":
        # Minimal one-page PDF using only the PDF built-in Helvetica font.
        lines = ["Aurum Edge - Journal"] + [f"{row['symbol']} {row['side']} pnl={float(row['net_pnl']):.2f}" for row in rows[:42]]
        stream = "BT /F1 11 Tf 44 790 Td " + " ".join(f"({line.replace('(', '[').replace(')', ']')}) Tj 0 -16 Td" for line in lines) + " ET"
        objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>", "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", f"<< /Length {len(stream.encode('latin-1', 'replace'))} >>\nstream\n{stream}\nendstream"]
        output = "%PDF-1.4\n"; offsets = [0]
        for index, object_ in enumerate(objects, 1):
            offsets.append(len(output.encode("latin-1", "replace"))); output += f"{index} 0 obj\n{object_}\nendobj\n"
        xref = len(output.encode("latin-1", "replace")); output += f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n" + "".join(f"{offset:010d} 00000 n \n" for offset in offsets[1:]) + f"trailer << /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
        destination.write_bytes(output.encode("latin-1", "replace"))
    else:
        with destination.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["symbol", "side", "entry", "stop", "target", "exit", "net_pnl", "closed_at_ms"])
            writer.writeheader(); writer.writerows(rows)


def export_candles(candles: Sequence[Candle], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.suffix.lower() == ".json":
        destination.write_text(json.dumps([asdict(candle) for candle in candles], ensure_ascii=False, indent=2), encoding="utf-8")
        return
    with destination.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["timestamp", "open", "high", "low", "close", "volume", "closed"])
        writer.writeheader(); writer.writerows({"timestamp": item.time_ms, "open": item.open, "high": item.high, "low": item.low, "close": item.close, "volume": item.volume, "closed": int(item.closed)} for item in candles)
