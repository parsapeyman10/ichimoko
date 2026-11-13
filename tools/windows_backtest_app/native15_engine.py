#!/usr/bin/env python3
"""Dependency-free native-15m Ichimoku backtest engine.

This module backs the Windows Python desktop application in this directory.
It intentionally models the current native-15m Lipi strategy only: no Android,
Qt, exchange API, 30m, 1H, or live-order functionality is required.
"""

from __future__ import annotations

import csv
import json
import math
from collections import Counter
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Optional, Sequence

M15_MS = 15 * 60 * 1000
EPS = 1e-12


class CsvValidationError(ValueError):
    """Raised when a CSV cannot safely be replayed as 15-minute candles."""


@dataclass(frozen=True)
class Candle:
    time: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0


@dataclass
class CsvAudit:
    input_rows: int = 0
    accepted_rows: int = 0
    skipped_invalid: int = 0
    skipped_duplicate: int = 0
    gaps: int = 0
    first_time: Optional[int] = None
    last_time: Optional[int] = None


@dataclass
class Settings:
    symbol: str = "XAUUSDT"
    start_equity: float = 1000.0
    quantity: float = 1.0
    target_rr: float = 2.25
    round_trip_cost_fraction: float = 0.0021
    max_kijun_extension_atr: float = 1.80
    sl_buffer_atr: float = 0.25
    smart_exit_enabled: bool = True
    smart_exit_confirmations: int = 2
    allow_gaps: bool = False

    def normalized(self) -> "Settings":
        return Settings(
            symbol=self.symbol.strip().upper() or "SYMBOL",
            start_equity=max(0.0, float(self.start_equity)),
            quantity=max(EPS, float(self.quantity)),
            target_rr=min(3.0, max(1.5, float(self.target_rr))),
            round_trip_cost_fraction=max(0.0, float(self.round_trip_cost_fraction)),
            max_kijun_extension_atr=max(0.5, float(self.max_kijun_extension_atr)),
            sl_buffer_atr=max(0.01, float(self.sl_buffer_atr)),
            smart_exit_enabled=bool(self.smart_exit_enabled),
            smart_exit_confirmations=min(3, max(2, int(self.smart_exit_confirmations))),
            allow_gaps=bool(self.allow_gaps),
        )


@dataclass
class Signal:
    time: int
    side: str  # LONG or SHORT
    trigger: str
    stop: float
    target: float
    confidence: float
    passed_conditions: int
    gate_percent: float


@dataclass
class OpenTrade:
    signal_time: int
    entry_time: int
    side: str
    trigger: str
    entry: float
    stop: float
    target: float
    initial_stop: float
    planned_rr: float
    confidence: float
    bars_held: int = 0


@dataclass
class ClosedTrade:
    number: int
    signal_time: int
    entry_time: int
    exit_time: int
    side: str
    trigger: str
    entry: float
    stop: float
    target: float
    exit: float
    exit_reason: str
    quantity: float
    bars_held: int
    planned_rr: float
    confidence: float
    gross_pnl: float
    fees: float
    net_pnl: float
    r_multiple: float
    equity_after: float


@dataclass
class BacktestResult:
    settings: Settings
    candles: list[Candle]
    audit: CsvAudit
    trades: list[ClosedTrade]
    signals: list[Signal]
    equity_curve: list[dict[str, float | int]]
    summary: dict[str, float | int | str]
    diagnostics: dict[str, int | float | str]
    open_trade: Optional[OpenTrade] = None


@dataclass
class Indicators:
    tenkan: list[Optional[float]]
    kijun: list[Optional[float]]
    span_a: list[Optional[float]]
    span_b: list[Optional[float]]
    current_a: list[Optional[float]]
    current_b: list[Optional[float]]
    atr: list[Optional[float]]
    prior_high: list[Optional[float]]
    prior_low: list[Optional[float]]
    chikou_peak: list[Optional[float]]
    chikou_trough: list[Optional[float]]
    volume_sma: list[Optional[float]]
    er: list[Optional[float]]


HEADER_ALIASES = {
    "time": ("timestamp", "time", "datetime", "date", "date_time", "open_time"),
    "open": ("open", "o"),
    "high": ("high", "h"),
    "low": ("low", "l"),
    "close": ("close", "c", "last"),
    "volume": ("volume", "vol", "v", "base_volume"),
}


def _canonical_header(value: str) -> str:
    return "".join(ch for ch in value.strip().lower() if ch.isalnum() or ch == "_")


def _number(value: object) -> float:
    text = str(value).strip().replace(",", "")
    if not text:
        raise ValueError("empty numeric value")
    number = float(text)
    if not math.isfinite(number):
        raise ValueError("non-finite numeric value")
    return number


def _time_ms(value: object) -> int:
    text = str(value).strip()
    if not text:
        raise ValueError("empty timestamp")
    try:
        raw = float(text)
        if not math.isfinite(raw):
            raise ValueError("non-finite timestamp")
        return int(round(raw * 1000 if abs(raw) < 100_000_000_000 else raw))
    except ValueError:
        pass
    normalized = text.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def _find_columns(fieldnames: Sequence[str]) -> dict[str, Optional[str]]:
    lookup = {_canonical_header(name): name for name in fieldnames if name}
    result: dict[str, Optional[str]] = {}
    for logical, aliases in HEADER_ALIASES.items():
        result[logical] = next((lookup[alias] for alias in aliases if alias in lookup), None)
    missing = [name for name in ("time", "open", "high", "low", "close") if result[name] is None]
    if missing:
        raise CsvValidationError("CSV columns missing: " + ", ".join(missing))
    return result


def read_15m_csv(path: Path, *, allow_gaps: bool = False) -> tuple[list[Candle], CsvAudit]:
    """Read common GoCharting-style OHLCV CSV files without external packages."""
    if not path.is_file():
        raise CsvValidationError(f"CSV not found: {path}")
    audit = CsvAudit()
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        sample = source.read(4096)
        source.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=",;\t")
        except csv.Error:
            dialect = csv.excel
        reader = csv.DictReader(source, dialect=dialect)
        if not reader.fieldnames:
            raise CsvValidationError("CSV has no header row")
        columns = _find_columns(reader.fieldnames)
        accepted: dict[int, Candle] = {}
        for row in reader:
            audit.input_rows += 1
            try:
                timestamp = _time_ms(row[columns["time"]])  # type: ignore[index]
                candle = Candle(
                    time=timestamp,
                    open=_number(row[columns["open"]]),  # type: ignore[index]
                    high=_number(row[columns["high"]]),  # type: ignore[index]
                    low=_number(row[columns["low"]]),  # type: ignore[index]
                    close=_number(row[columns["close"]]),  # type: ignore[index]
                    volume=_number(row[columns["volume"]]) if columns["volume"] else 0.0,
                )
                if candle.low > min(candle.open, candle.close) or candle.high < max(candle.open, candle.close) or candle.high < candle.low:
                    raise ValueError("invalid OHLC range")
                if candle.time % M15_MS:
                    raise CsvValidationError("CSV timestamps must align to 15-minute boundaries")
                if candle.time in accepted:
                    audit.skipped_duplicate += 1
                else:
                    accepted[candle.time] = candle
            except CsvValidationError:
                raise
            except (TypeError, ValueError, OverflowError):
                audit.skipped_invalid += 1
    candles = [accepted[key] for key in sorted(accepted)]
    if len(candles) < 80:
        raise CsvValidationError("At least 80 valid 15-minute candles are required")
    for previous, current in zip(candles, candles[1:]):
        if current.time - previous.time != M15_MS:
            audit.gaps += 1
    if audit.gaps and not allow_gaps:
        raise CsvValidationError("15-minute gaps detected. Re-export continuous data or enable Allow gaps")
    audit.accepted_rows = len(candles)
    audit.first_time = candles[0].time
    audit.last_time = candles[-1].time
    return candles, audit


def iso_utc(timestamp_ms: int) -> str:
    return datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def _midpoint(candles: Sequence[Candle], end: int, period: int) -> Optional[float]:
    start = end - period + 1
    if start < 0:
        return None
    window = candles[start : end + 1]
    return (max(item.high for item in window) + min(item.low for item in window)) / 2.0


def _sma(values: Sequence[float], end: int, period: int) -> Optional[float]:
    start = end - period + 1
    if start < 0:
        return None
    return sum(values[start : end + 1]) / period


def build_indicators(candles: Sequence[Candle]) -> Indicators:
    count = len(candles)
    tenkan: list[Optional[float]] = [None] * count
    kijun: list[Optional[float]] = [None] * count
    span_a: list[Optional[float]] = [None] * count
    span_b: list[Optional[float]] = [None] * count
    current_a: list[Optional[float]] = [None] * count
    current_b: list[Optional[float]] = [None] * count
    atr: list[Optional[float]] = [None] * count
    prior_high: list[Optional[float]] = [None] * count
    prior_low: list[Optional[float]] = [None] * count
    chikou_peak: list[Optional[float]] = [None] * count
    chikou_trough: list[Optional[float]] = [None] * count
    volume_sma: list[Optional[float]] = [None] * count
    er: list[Optional[float]] = [None] * count

    true_ranges: list[float] = []
    previous_atr: Optional[float] = None
    volumes = [item.volume for item in candles]
    for index, candle in enumerate(candles):
        tenkan[index] = _midpoint(candles, index, 9)
        kijun[index] = _midpoint(candles, index, 26)
        span_b[index] = _midpoint(candles, index, 52)
        if tenkan[index] is not None and kijun[index] is not None:
            span_a[index] = (tenkan[index] + kijun[index]) / 2.0
        displaced = index - 25
        if displaced >= 0:
            current_a[index] = span_a[displaced]
            current_b[index] = span_b[displaced]
        previous_close = candles[index - 1].close if index else candle.close
        tr = max(candle.high - candle.low, abs(candle.high - previous_close), abs(candle.low - previous_close))
        true_ranges.append(tr)
        if index == 13:
            previous_atr = sum(true_ranges[:14]) / 14.0
            atr[index] = previous_atr
        elif index > 13 and previous_atr is not None:
            previous_atr = (previous_atr * 13.0 + tr) / 14.0
            atr[index] = previous_atr
        # The native Lipi script floors ATR by 0.05% of close. It prevents a
        # nearly-flat price series from creating an implausibly small bracket.
        if atr[index] is not None:
            atr[index] = max(atr[index], candle.close * 0.0005)
        if index >= 20:
            prior = candles[index - 20 : index]
            prior_high[index] = max(item.high for item in prior)
            prior_low[index] = min(item.low for item in prior)
        if index >= 26:
            prior_close_window = [item.close for item in candles[index - 26 : index]]
            chikou_peak[index] = max(prior_close_window)
            chikou_trough[index] = min(prior_close_window)
        volume_sma[index] = _sma(volumes, index, 20)
        if index >= 14:
            path = sum(abs(candles[pos].close - candles[pos - 1].close) for pos in range(index - 13, index + 1))
            er[index] = min(1.0, abs(candle.close - candles[index - 14].close) / path) if path > EPS else 0.5
    return Indicators(tenkan, kijun, span_a, span_b, current_a, current_b, atr, prior_high, prior_low, chikou_peak, chikou_trough, volume_sma, er)


def _ready_value(value: Optional[float]) -> float:
    if value is None:
        raise ValueError("indicator is not ready")
    return value


def _cloud_state(candles: Sequence[Candle], indicators: Indicators, index: int) -> Optional[tuple[float, float, float, float, float, float, float]]:
    """Return native values needed by one closed 15m decision, or None in warmup."""
    # Match the visible warm-up contract: decision number one may use the
    # 80th completed native-15m candle, never an earlier partial history.
    if index < 79:
        return None
    values = (
        indicators.tenkan[index],
        indicators.kijun[index],
        indicators.span_a[index],
        indicators.span_b[index],
        indicators.current_a[index],
        indicators.current_b[index],
        indicators.atr[index],
        indicators.prior_high[index],
        indicators.prior_low[index],
        indicators.chikou_peak[index],
        indicators.chikou_trough[index],
        indicators.volume_sma[index],
        indicators.er[index],
    )
    if any(item is None for item in values):
        return None
    return (
        _ready_value(indicators.tenkan[index]),
        _ready_value(indicators.kijun[index]),
        _ready_value(indicators.current_a[index]),
        _ready_value(indicators.current_b[index]),
        _ready_value(indicators.span_a[index]),
        _ready_value(indicators.span_b[index]),
        _ready_value(indicators.atr[index]),
    )


def evaluate_signal(candles: Sequence[Candle], indicators: Indicators, index: int, settings: Settings) -> Optional[Signal]:
    values = _cloud_state(candles, indicators, index)
    if values is None:
        return None
    tenkan, kijun, current_a, current_b, future_a, future_b, atr = values
    previous_tenkan = indicators.tenkan[index - 1]
    previous_kijun = indicators.kijun[index - 1]
    previous_atr = indicators.atr[index - 1]
    older_tenkan = indicators.tenkan[index - 2]
    older_kijun = indicators.kijun[index - 2]
    older_atr = indicators.atr[index - 2]
    previous_future_a = indicators.span_a[index - 1]
    if any(item is None for item in (previous_tenkan, previous_kijun, previous_atr, older_tenkan, older_kijun, older_atr, previous_future_a)):
        return None
    candle = candles[index]
    top, bottom = max(current_a, current_b), min(current_a, current_b)
    bullish = candle.close > top
    bearish = candle.close < bottom
    is_long = bullish if bullish != bearish else tenkan >= kijun
    flat_tolerance = max(candle.close * 0.0003, atr * 0.04)
    previous_current_b = indicators.current_b[index - 1]
    kijun_flat = abs(kijun - _ready_value(indicators.kijun[index - 1])) <= flat_tolerance
    ssb_flat = previous_current_b is not None and abs(current_b - previous_current_b) <= flat_tolerance
    range_value = max(candle.high - candle.low, EPS)
    body = abs(candle.close - candle.open) / range_value
    clv = min(1.0, max(0.0, (candle.close - candle.low) / range_value))
    upper_wick = max(0.0, (candle.high - max(candle.open, candle.close)) / range_value)
    lower_wick = max(0.0, (min(candle.open, candle.close) - candle.low) / range_value)
    volume_avg = _ready_value(indicators.volume_sma[index])
    volume_ratio = candle.volume / volume_avg if volume_avg > EPS and candle.volume > 0.0 else 1.0
    er = _ready_value(indicators.er[index])
    prior_high = _ready_value(indicators.prior_high[index])
    prior_low = _ready_value(indicators.prior_low[index])
    chikou_peak = _ready_value(indicators.chikou_peak[index])
    chikou_trough = _ready_value(indicators.chikou_trough[index])
    chikou_start = index - 31
    chikou_end = index - 24
    chikou_high = max(item.high for item in candles[chikou_start:chikou_end])
    chikou_low = min(item.low for item in candles[chikou_start:chikou_end])
    chikou_bull = candle.close > chikou_high
    chikou_bear = candle.close < chikou_low
    prior_tenkan = _ready_value(previous_tenkan)
    prior_kijun = _ready_value(previous_kijun)
    prior_atr = _ready_value(previous_atr)
    older_tenkan_value = _ready_value(older_tenkan)
    older_kijun_value = _ready_value(older_kijun)
    older_atr_value = _ready_value(older_atr)
    tk_gap = abs(tenkan - kijun) / max(atr, EPS)
    previous_tk_gap = abs(prior_tenkan - prior_kijun) / max(prior_atr, EPS)
    older_tk_gap = abs(older_tenkan_value - older_kijun_value) / max(older_atr_value, EPS)
    tk_gap_expanding = tk_gap > previous_tk_gap and previous_tk_gap <= older_tk_gap

    c1 = bullish if is_long else bearish
    c2 = tenkan >= kijun if is_long else tenkan <= kijun
    c3 = tenkan >= prior_tenkan - atr * 0.05 if is_long else tenkan <= prior_tenkan + atr * 0.05
    c4 = (future_a > future_b and future_a >= _ready_value(previous_future_a)) if is_long else (future_a < future_b and future_a <= _ready_value(previous_future_a))
    # Fresh TK expansion is the primary line-geometry gate. The Kumo width is
    # measured at the price's current chart location, not 26 bars ahead.
    c5 = tk_gap_expanding and abs(current_a - current_b) / max(atr, EPS) >= 0.05
    c6 = chikou_bull if is_long else chikou_bear
    c7 = candle.close >= chikou_peak - atr * 0.15 if is_long else candle.close <= chikou_trough + atr * 0.15
    c8 = abs(candle.close - kijun) / max(atr, EPS) <= settings.max_kijun_extension_atr and not (kijun_flat and ssb_flat)
    c9 = candle.close >= top + (atr * 0.15 if ssb_flat else 0.0) if is_long else candle.close <= bottom - (atr * 0.15 if ssb_flat else 0.0)
    c10 = not (kijun_flat and ssb_flat)
    directional = (candle.close > candle.open and clv >= 0.45 and body >= 0.22) if is_long else (candle.close < candle.open and clv <= 0.55 and body >= 0.22)
    c11 = directional and volume_ratio >= 0.60
    c13 = er >= 0.20 and not kijun_flat
    c14 = (upper_wick <= 0.45 and (body >= 0.22 or lower_wick >= 0.25)) if is_long else (lower_wick <= 0.45 and (body >= 0.22 or upper_wick >= 0.25))
    c15 = c13 and c8 and (tenkan >= kijun if is_long else tenkan <= kijun)

    cross_up = tenkan > kijun and prior_tenkan <= prior_kijun
    cross_down = tenkan < kijun and prior_tenkan >= prior_kijun
    trigger_katana = (cross_up and candle.close > top and candle.close > candle.open) if is_long else (cross_down and candle.close < bottom and candle.close < candle.open)
    trigger_pullback = (candle.low <= max(tenkan, kijun) + atr * 0.25 and candle.close > tenkan and candle.close > top and candle.close > candle.open) if is_long else (candle.high >= min(tenkan, kijun) - atr * 0.25 and candle.close < tenkan and candle.close < bottom and candle.close < candle.open)
    trigger_breakout = (candle.close > prior_high and candle.close > top + atr * 0.10 and candle.close >= chikou_peak and candle.close > candle.open) if is_long else (candle.close < prior_low and candle.close < bottom - atr * 0.10 and candle.close <= chikou_trough and candle.close < candle.open)
    trigger_expansion = (c5 and candle.close > top and candle.close > tenkan and candle.close > candle.open) if is_long else (c5 and candle.close < bottom and candle.close < tenkan and candle.close < candle.open)
    trigger = "KATANA" if trigger_katana else "PULLBACK" if trigger_pullback else "BREAKOUT" if trigger_breakout else "EXPANSION" if trigger_expansion else ""

    stop_floor = max(candle.close * 0.0025, atr * 0.80)
    stop_ceiling = max(stop_floor, atr * 2.10)
    raw_stop = kijun - atr * settings.sl_buffer_atr if is_long else kijun + atr * settings.sl_buffer_atr
    stop_distance = min(max(abs(candle.close - raw_stop), stop_floor), stop_ceiling)
    target_distance = stop_distance * settings.target_rr
    stop = candle.close - stop_distance if is_long else candle.close + stop_distance
    target = candle.close + target_distance if is_long else candle.close - target_distance
    c12 = 1.5 <= settings.target_rr <= 3.0 and target_distance >= candle.close * settings.round_trip_cost_fraction * 2.0
    c16 = abs(candle.close - tenkan) / max(atr, EPS) <= 0.85
    cost_to_risk = candle.close * settings.round_trip_cost_fraction / max(stop_distance, EPS)
    c17 = cost_to_risk <= 0.45

    conditions = [c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17]
    regime = (1.0 if c1 else 0.0) + (1.0 if c3 else 0.0) + (1.0 if c4 else 0.0) + (1.0 if c6 else 0.0) + (1.2 if c17 else 0.0)
    quality = (0.6 if c5 else 0.0) + (1.0 if c8 else 0.0) + (1.0 if c9 else 0.0) + (1.0 if c13 else 0.0) + (0.6 if c14 else 0.0) + (1.0 if c15 else 0.0)
    trigger_score = (0.6 if c2 else 0.0) + (0.6 if c7 else 0.0) + (0.8 if c10 else 0.0) + (1.2 if c11 else 0.0) + (1.0 if c12 else 0.0) + (1.2 if c16 else 0.0) + (1.2 if c17 else 0.0)
    gate = (regime + quality + trigger_score) / 17.0 * 100.0
    # Price/Kumo placement, TK direction and a fresh TK expansion are the
    # non-negotiable native-15m geometry. Future-cloud and Chikou contribute
    # to quality, rather than delaying early expansion entries as hard vetoes.
    hard_veto = c1 and c2 and c3 and c5 and c17
    book_fires = hard_veto and quality / 5.2 * 100.0 >= 60.0 and trigger_score / 6.6 * 100.0 >= 60.0 and gate >= 70.0
    if not trigger or not book_fires:
        return None
    return Signal(
        time=candle.time + M15_MS,
        side="LONG" if is_long else "SHORT",
        trigger=trigger,
        stop=stop,
        target=target,
        confidence=round(gate, 2),
        passed_conditions=sum(1 for item in conditions if item),
        gate_percent=round(gate, 2),
    )


def smart_exit(candles: Sequence[Candle], indicators: Indicators, index: int, trade: OpenTrade, settings: Settings) -> bool:
    """Require adverse native-15m structure plus TK contraction/cross confirmation."""
    values = _cloud_state(candles, indicators, index)
    if values is None or not settings.smart_exit_enabled:
        return False
    tenkan, kijun, current_a, current_b, _future_a, _future_b, atr = values
    previous_tenkan = indicators.tenkan[index - 1]
    previous_kijun = indicators.kijun[index - 1]
    previous_atr = indicators.atr[index - 1]
    older_tenkan = indicators.tenkan[index - 2]
    older_kijun = indicators.kijun[index - 2]
    older_atr = indicators.atr[index - 2]
    if any(item is None for item in (previous_tenkan, previous_kijun, previous_atr, older_tenkan, older_kijun, older_atr)):
        return False
    tk_gap = abs(tenkan - kijun) / max(atr, EPS)
    previous_tk_gap = abs(_ready_value(previous_tenkan) - _ready_value(previous_kijun)) / max(_ready_value(previous_atr), EPS)
    older_tk_gap = abs(_ready_value(older_tenkan) - _ready_value(older_kijun)) / max(_ready_value(older_atr), EPS)
    tk_gap_contracting = tk_gap < previous_tk_gap and previous_tk_gap >= older_tk_gap
    top, bottom = max(current_a, current_b), min(current_a, current_b)
    close = candles[index].close
    if trade.side == "LONG":
        tk_crossed_against = tenkan < kijun
        confirmations = int(close < kijun) + int(tk_crossed_against) + int(close < top)
    else:
        tk_crossed_against = tenkan > kijun
        confirmations = int(close > kijun) + int(tk_crossed_against) + int(close > bottom)
    return (tk_gap_contracting or tk_crossed_against) and confirmations >= settings.smart_exit_confirmations


def _close(open_trade: OpenTrade, price: float, exit_time: int, reason: str, equity: float, settings: Settings, number: int) -> ClosedTrade:
    gross = (price - open_trade.entry) * settings.quantity if open_trade.side == "LONG" else (open_trade.entry - price) * settings.quantity
    fees = ((open_trade.entry + price) / 2.0) * settings.quantity * settings.round_trip_cost_fraction
    risk = abs(open_trade.entry - open_trade.initial_stop) * settings.quantity
    net = gross - fees
    return ClosedTrade(
        number=number,
        signal_time=open_trade.signal_time,
        entry_time=open_trade.entry_time,
        exit_time=exit_time,
        side=open_trade.side,
        trigger=open_trade.trigger,
        entry=round(open_trade.entry, 8),
        stop=round(open_trade.initial_stop, 8),
        target=round(open_trade.target, 8),
        exit=round(price, 8),
        exit_reason=reason,
        quantity=settings.quantity,
        bars_held=open_trade.bars_held,
        planned_rr=open_trade.planned_rr,
        confidence=open_trade.confidence,
        gross_pnl=round(gross, 8),
        fees=round(fees, 8),
        net_pnl=round(net, 8),
        r_multiple=round(net / risk, 8) if risk > EPS else 0.0,
        equity_after=round(equity + net, 8),
    )


def _intrabar_exit(trade: OpenTrade, candle: Candle) -> Optional[tuple[str, float]]:
    if trade.side == "LONG":
        stop_hit, target_hit = candle.low <= trade.stop, candle.high >= trade.target
    else:
        stop_hit, target_hit = candle.high >= trade.stop, candle.low <= trade.target
    if stop_hit:  # Deliberately conservative if both levels touch in one candle.
        return "STOP_LOSS", trade.stop
    if target_hit:
        return "TAKE_PROFIT", trade.target
    return None


def _summary(trades: Sequence[ClosedTrade], start_equity: float, equity_curve: Sequence[dict[str, float | int]]) -> dict[str, float | int | str]:
    gross_profit = sum(item.net_pnl for item in trades if item.net_pnl > 0.0)
    gross_loss = abs(sum(item.net_pnl for item in trades if item.net_pnl < 0.0))
    ending = float(equity_curve[-1]["equity"]) if equity_curve else start_equity
    peak = start_equity
    max_drawdown = 0.0
    for point in equity_curve:
        equity = float(point["equity"])
        peak = max(peak, equity)
        if peak > EPS:
            max_drawdown = max(max_drawdown, (peak - equity) / peak * 100.0)
    wins = sum(1 for item in trades if item.net_pnl > 0.0)
    return {
        "closed_trades": len(trades),
        "wins": wins,
        "win_rate_percent": round(wins / len(trades) * 100.0, 2) if trades else 0.0,
        "net_pnl": round(ending - start_equity, 8),
        "ending_equity": round(ending, 8),
        "profit_factor": round(gross_profit / gross_loss, 4) if gross_loss > EPS else ("∞" if gross_profit > EPS else 0.0),
        "max_drawdown_percent": round(max_drawdown, 4),
    }


def run_backtest(candles: Sequence[Candle], settings: Settings, audit: Optional[CsvAudit] = None, progress: Optional[Callable[[int, int], None]] = None) -> BacktestResult:
    """Replay native 15m signals with next-open fills, SL-first exits and smart exits."""
    config = settings.normalized()
    bars = list(candles)
    if len(bars) < 80:
        raise CsvValidationError("At least 80 candles are required for native 15m warmup")
    indicator = build_indicators(bars)
    current_equity = config.start_equity
    trades: list[ClosedTrade] = []
    signals: list[Signal] = []
    equity_curve: list[dict[str, float | int]] = [{"time": bars[0].time, "equity": current_equity}]
    pending_entry: Optional[Signal] = None
    pending_smart_exit = False
    open_trade: Optional[OpenTrade] = None
    # One entry per direction per native-15m Ichimoku leg. A completed trade
    # does not re-arm that same direction until TK/Kumo structure resets.
    long_trend_entry_armed = True
    short_trend_entry_armed = True
    diagnostics: Counter[str] = Counter()

    for index, candle in enumerate(bars):
        if progress and (index % 250 == 0 or index == len(bars) - 1):
            progress(index + 1, len(bars))
        closed_this_bar = False
        # A smart close signal made at the previous close fills at this open.
        if open_trade is not None and pending_smart_exit:
            closed = _close(open_trade, candle.open, candle.time, "SMART_TREND_EXIT", current_equity, config, len(trades) + 1)
            current_equity += closed.net_pnl
            trades.append(closed)
            equity_curve.append({"time": closed.exit_time, "equity": current_equity})
            open_trade = None
            pending_smart_exit = False
            closed_this_bar = True

        # Entry signals are market-filled at the following candle open. Frozen
        # brackets remain attached even if that open has crossed a level; the
        # fill is never silently cancelled merely because of an overnight gap.
        if pending_entry is not None and open_trade is None:
            inside_frozen_bracket = (
                pending_entry.stop < candle.open < pending_entry.target
                if pending_entry.side == "LONG"
                else pending_entry.target < candle.open < pending_entry.stop
            )
            if not inside_frozen_bracket:
                diagnostics["gap_entry_outside_frozen_bracket"] += 1
            open_trade = OpenTrade(
                signal_time=pending_entry.time,
                entry_time=candle.time,
                side=pending_entry.side,
                trigger=pending_entry.trigger,
                entry=candle.open,
                stop=pending_entry.stop,
                target=pending_entry.target,
                initial_stop=pending_entry.stop,
                planned_rr=config.target_rr,
                confidence=pending_entry.confidence,
            )
            pending_entry = None

        # Bracket orders are live on the fill candle; SL wins if both touch.
        if open_trade is not None:
            open_trade.bars_held += 1
            hit = _intrabar_exit(open_trade, candle)
            if hit:
                reason, price = hit
                closed = _close(open_trade, price, candle.time + M15_MS, reason, current_equity, config, len(trades) + 1)
                current_equity += closed.net_pnl
                trades.append(closed)
                equity_curve.append({"time": closed.exit_time, "equity": current_equity})
                open_trade = None
                closed_this_bar = True

        # All decisions below use the now-closed candle, never future data.
        if open_trade is not None and smart_exit(bars, indicator, index, open_trade, config):
            pending_smart_exit = True
            diagnostics["smart_exit_signals"] += 1

        # Re-arm a direction only when the same native-15m Ichimoku structure
        # has reset. This mirrors the Lipi strategy's same-trend re-entry lock.
        line_values = _cloud_state(bars, indicator, index)
        if line_values is not None:
            tenkan, kijun, current_a, current_b, _future_a, _future_b, _atr = line_values
            top, bottom = max(current_a, current_b), min(current_a, current_b)
            if tenkan < kijun or candle.close <= bottom:
                long_trend_entry_armed = True
            if tenkan > kijun or candle.close >= top:
                short_trend_entry_armed = True

        if open_trade is None and pending_entry is None and not pending_smart_exit and not closed_this_bar:
            signal = evaluate_signal(bars, indicator, index, config)
            if signal is not None:
                direction_armed = long_trend_entry_armed if signal.side == "LONG" else short_trend_entry_armed
                if direction_armed:
                    signals.append(signal)
                    pending_entry = signal
                    if signal.side == "LONG":
                        long_trend_entry_armed = False
                    else:
                        short_trend_entry_armed = False
                else:
                    diagnostics["blocked_same_trend_reentry"] += 1

    result_audit = audit if audit is not None else CsvAudit(accepted_rows=len(bars), first_time=bars[0].time, last_time=bars[-1].time)
    summary = _summary(trades, config.start_equity, equity_curve)
    diagnostics.update({"valid_candles": len(bars), "open_trade_at_end": int(open_trade is not None), "strict_signals": len(signals)})
    return BacktestResult(config, bars, result_audit, trades, signals, equity_curve, summary, dict(diagnostics), open_trade)


def _trade_row(trade: ClosedTrade) -> dict[str, object]:
    row = asdict(trade)
    row["signal_time"] = iso_utc(trade.signal_time)
    row["entry_time"] = iso_utc(trade.entry_time)
    row["exit_time"] = iso_utc(trade.exit_time)
    return row


def write_reports(result: BacktestResult, output_dir: Path) -> dict[str, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    trade_fields = list(asdict(result.trades[0]).keys()) if result.trades else [
        "number", "signal_time", "entry_time", "exit_time", "side", "trigger", "entry", "stop", "target", "exit", "exit_reason", "quantity", "bars_held", "planned_rr", "confidence", "gross_pnl", "fees", "net_pnl", "r_multiple", "equity_after",
    ]
    trades_path = output_dir / "trades.csv"
    with trades_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=trade_fields)
        writer.writeheader()
        writer.writerows(_trade_row(item) for item in result.trades)
    signals_path = output_dir / "signals.csv"
    with signals_path.open("w", encoding="utf-8-sig", newline="") as handle:
        fields = ["time", "side", "trigger", "stop", "target", "confidence", "passed_conditions", "gate_percent"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for item in result.signals:
            row = asdict(item)
            row["time"] = iso_utc(item.time)
            writer.writerow(row)
    equity_path = output_dir / "equity_curve.csv"
    with equity_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["time", "equity"])
        writer.writeheader()
        for point in result.equity_curve:
            writer.writerow({"time": iso_utc(int(point["time"])), "equity": point["equity"]})
    summary_path = output_dir / "summary.json"
    payload = {
        "settings": asdict(result.settings),
        "csv_audit": asdict(result.audit),
        "summary": result.summary,
        "diagnostics": result.diagnostics,
        "open_trade": asdict(result.open_trade) if result.open_trade else None,
        "model": "native_15m_lite_python",
        "warning": "Historical simulator only. No exchange API or live order routing is included.",
    }
    summary_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"trades": trades_path, "signals": signals_path, "equity": equity_path, "summary": summary_path}
