#!/usr/bin/env python3
"""Candle-close-only BASE_ICHIMOKU backtest for GoCharting 15-minute CSV exports.

This program is deliberately dependency-free: it uses only the Python standard
library, reconstructs 1H and 4H candles from the supplied 15m series, and never
uses an incomplete higher-timeframe candle in a decision.

The implementation follows the BASE_ICHIMOKU branch reference at
f1c9850ae290bbbc5e953c8fdf453c05754928cd.  It intentionally excludes the
Hidden-Cross, Parsa, BTC-52, Peyman and NeoWave entry paths; this is the pure
four-school (Patel / Tuttle / Hosoda / Peloille) BASE_ICHIMOKU model.

It is a historical simulator, not order-routing software.  It never contacts
an exchange and does not submit real orders.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from bisect import bisect_right
from collections import Counter
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional, Sequence

M15_MS = 15 * 60 * 1000
H1_MS = 60 * 60 * 1000
H4_MS = 4 * 60 * 60 * 1000
WINDOW_BARS = 200  # SNAPSHOT_KEEP_BARS_SHORT in the reference engine
WARMUP_BARS = 60
EPS = 1e-9


class CsvValidationError(ValueError):
    """Raised when a CSV cannot safely be used as a 15-minute base series."""


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
    skipped_zero_volume: int = 0
    gaps: int = 0
    first_time: Optional[int] = None
    last_time: Optional[int] = None


@dataclass
class Config:
    symbol: str = "XAUUSDT"
    starting_equity: float = 1000.0
    risk_percent: float = 0.5
    cost_multiplier: float = 1.0
    max_hold_bars: int = 20
    allow_gaps: bool = False
    # Defaults from BaseStrategyConfig / its BASE_ICHIMOKU effective configuration.
    tenkan_period: int = 9
    kijun_period: int = 26
    senkou_b_period: int = 52
    displacement: int = 26
    max_kijun_extension_atr: float = 1.8
    sl_buffer_atr: float = 0.25
    breakout_buffer_atr: float = 0.15
    min_risk_reward: float = 1.5
    min_efficiency_ratio: float = 0.20
    min_volume_ratio20: float = 0.60
    max_adverse_wick_ratio: float = 0.45
    min_future_kumo_thickness_atr: float = 0.05
    max_sniper_distance_atr: float = 0.35
    require_higher_tf_alignment: bool = True
    require_chikou_open_space: bool = True
    require_future_kumo_slope: bool = True
    allow_maximum_execution: bool = True


@dataclass
class Snapshot:
    """Fields consumed by the four-school BASE_ICHIMOKU conditions."""

    time: int
    open: float
    high: float
    low: float
    close: float
    tenkan: float
    kijun: float
    prev_tenkan: float
    prev_kijun: float
    current_span_a: float
    current_span_b: float
    future_span_a: float
    future_span_b: float
    prev_future_span_a: float
    prev_future_span_b: float
    atr14: float
    chikou_bullish_open: bool
    chikou_bearish_open: bool
    chikou_resistance_peak: float
    chikou_support_trough: float
    current_span_b_flat: bool
    kijun_flat: bool
    tenkan_flat: bool
    flat_equilibrium_levels: list[float]
    body_ratio: float
    clv: float
    volume_ratio20: float
    kijun_slope_atr: float
    tenkan_slope_atr: float
    future_kumo_thickness_atr: float
    current_kumo_thickness_atr: float
    c_clamp_warning: bool
    katana_equilibrium: bool
    bullish_impulse3: bool
    bearish_impulse3: bool
    efficiency_ratio14: float
    upper_wick_ratio: float
    lower_wick_ratio: float
    recent_katana_release: bool
    last_down_fractal_low: float
    last_up_fractal_high: float
    kijun30: float
    ema34: float

    @property
    def kumo_top(self) -> float:
        return max(self.current_span_a, self.current_span_b)

    @property
    def kumo_bottom(self) -> float:
        return min(self.current_span_a, self.current_span_b)

    @property
    def price_above_kumo(self) -> bool:
        return self.close > self.kumo_top

    @property
    def price_below_kumo(self) -> bool:
        return self.close < self.kumo_bottom

    @property
    def future_kumo_bullish(self) -> bool:
        return self.future_span_a > self.future_span_b

    @property
    def future_kumo_bearish(self) -> bool:
        return self.future_span_a < self.future_span_b

    @property
    def future_span_a_rising(self) -> bool:
        return self.future_span_a >= self.prev_future_span_a - EPS

    @property
    def future_span_a_falling(self) -> bool:
        return self.future_span_a <= self.prev_future_span_a + EPS

    @property
    def future_span_b_non_falling(self) -> bool:
        return self.future_span_b >= self.prev_future_span_b - EPS

    @property
    def future_span_b_non_rising(self) -> bool:
        return self.future_span_b <= self.prev_future_span_b + EPS

    @property
    def distance_to_kijun(self) -> float:
        return abs(self.close - self.kijun)

    @property
    def distance_to_kijun_atr(self) -> float:
        return self.distance_to_kijun / max(self.atr14, EPS)

    @property
    def sanyaku_bullish(self) -> bool:
        return (
            self.close > self.kumo_top
            and self.tenkan >= self.kijun - self.atr14 * 0.05
            and self.kijun >= self.kumo_bottom - self.atr14 * 0.10
        )

    @property
    def sanyaku_bearish(self) -> bool:
        return (
            self.close < self.kumo_bottom
            and self.tenkan <= self.kijun + self.atr14 * 0.05
            and self.kijun <= self.kumo_top + self.atr14 * 0.10
        )


@dataclass
class HosodaWave:
    direction: str
    point_a: float
    point_b: float
    point_c: float
    s_point_held: bool
    target_v: float
    target_n: float
    target_e: float
    target_nt: float
    bars_since_start: int
    nearest_cycle: int
    near_cycle_change: bool


@dataclass
class GuardedPlan:
    usable: bool
    entry: float
    stop: float
    target: float
    stop_distance: float
    target_distance: float
    risk_reward: float
    reason: str = ""


@dataclass
class Evaluation:
    action: str  # BUY, SELL, NO_TRADE
    direction: str
    trigger: str
    entry_anchor: float
    stop: float
    target: float
    atr: float
    strategy_atr: float
    desired_rr: float
    plan: GuardedPlan
    conditions: dict[int, bool]
    gate: dict[str, float | bool]
    confidence: float


@dataclass
class PendingOrder:
    signal_time: int
    action: str
    anchor_entry: float
    anchor_stop: float
    anchor_target: float
    atr: float
    strategy_atr: float
    desired_rr: float
    confidence: float
    trigger: str


@dataclass
class OpenTrade:
    signal_time: int
    entry_time: int
    action: str
    entry: float
    initial_stop: float
    stop: float
    target: float
    units: float
    confidence: float
    planned_rr: float
    trigger: str
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
    units: float
    bars_held: int
    planned_rr: float
    confidence: float
    gross_pnl_usd: float
    fee_usd: float
    net_pnl_usd: float
    r_multiple: float
    equity_after_usd: float


# ---------------------------------------------------------------------------
# CSV parsing and timeframe construction
# ---------------------------------------------------------------------------


def _canonical_header(value: str) -> str:
    return "".join(ch for ch in value.strip().lower().replace("\ufeff", "") if ch.isalnum())


def _parse_number(raw: str) -> float:
    value = raw.strip().replace(" ", "").replace(",", "")
    if not value:
        raise ValueError("empty numeric value")
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError("non-finite numeric value")
    return parsed


def _parse_time(raw: str) -> int:
    value = raw.strip().replace("\ufeff", "")
    if not value:
        raise ValueError("empty timestamp")
    try:
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("non-finite timestamp")
        # Seconds are common outside GoCharting; milliseconds are the canonical form.
        return int(number * 1000) if abs(number) < 100_000_000_000 else int(number)
    except ValueError:
        pass
    iso = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(iso)
    except ValueError as exc:
        raise ValueError(f"unsupported timestamp: {raw!r}") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def read_15m_csv(path: Path, *, drop_zero_volume: bool = False) -> tuple[list[Candle], CsvAudit]:
    """Read common GoCharting OHLCV exports and reject unsafe OHLC rows.

    Accepted aliases are timestamp/time/date/datetime and open/high/low/close,
    with an optional volume.  Parsing is intentionally strict about OHLC
    integrity because invalid high/low values produce misleading Ichimoku spans.
    """

    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError:
        text = path.read_text(encoding="utf-8-sig", errors="replace")
    if not text.strip():
        raise CsvValidationError("CSV is empty.")

    try:
        dialect = csv.Sniffer().sniff(text[:8192], delimiters=",;\t")
    except csv.Error:
        dialect = csv.excel
    reader = csv.DictReader(text.splitlines(), dialect=dialect)
    if not reader.fieldnames:
        raise CsvValidationError("CSV has no header row.")

    columns = {_canonical_header(name): name for name in reader.fieldnames if name}

    def select(*names: str) -> Optional[str]:
        return next((columns[name] for name in names if name in columns), None)

    time_col = select("timestamp", "time", "datetime", "date", "opentime", "timeopen")
    open_col = select("open", "o")
    high_col = select("high", "h")
    low_col = select("low", "l")
    close_col = select("close", "c")
    volume_col = select("volume", "vol", "basevolume", "quotevolume")
    missing = [
        name
        for name, col in (("timestamp/time", time_col), ("open", open_col), ("high", high_col), ("low", low_col), ("close", close_col))
        if col is None
    ]
    if missing:
        raise CsvValidationError(
            "CSV must contain OHLC columns and a time column; missing: " + ", ".join(missing)
        )

    audit = CsvAudit()
    by_time: dict[int, Candle] = {}
    assert time_col and open_col and high_col and low_col and close_col
    for row in reader:
        audit.input_rows += 1
        try:
            candle = Candle(
                time=_parse_time(row[time_col]),
                open=_parse_number(row[open_col]),
                high=_parse_number(row[high_col]),
                low=_parse_number(row[low_col]),
                close=_parse_number(row[close_col]),
                volume=_parse_number(row[volume_col]) if volume_col and row.get(volume_col, "").strip() else 0.0,
            )
        except (KeyError, ValueError, TypeError):
            audit.skipped_invalid += 1
            continue
        if (
            candle.open <= 0.0
            or candle.high <= 0.0
            or candle.low <= 0.0
            or candle.close <= 0.0
            or candle.high < candle.low
            or candle.close < candle.low
            or candle.close > candle.high
            or candle.open < candle.low
            or candle.open > candle.high
        ):
            audit.skipped_invalid += 1
            continue
        if drop_zero_volume and volume_col and candle.volume <= 0.0:
            audit.skipped_zero_volume += 1
            continue
        if candle.time in by_time:
            audit.skipped_duplicate += 1
            continue
        by_time[candle.time] = candle

    bars = [by_time[key] for key in sorted(by_time)]
    if len(bars) < 80:
        raise CsvValidationError(f"Need at least 80 valid 15m candles; got {len(bars)}.")
    misaligned = [bar for bar in bars if bar.time % M15_MS != 0]
    if misaligned:
        raise CsvValidationError(
            f"{len(misaligned)} timestamp(s) are not aligned to a 15-minute UTC bucket. "
            "Export the chart in its 15m timeframe."
        )
    audit.accepted_rows = len(bars)
    audit.first_time = bars[0].time
    audit.last_time = bars[-1].time
    audit.gaps = sum(1 for left, right in zip(bars, bars[1:]) if right.time - left.time != M15_MS)
    return bars, audit


def resample_complete(base: Sequence[Candle], target_ms: int) -> tuple[list[Candle], int]:
    """Build only fully populated UTC buckets; incomplete buckets are discarded.

    This is stricter than merely combining whatever source bars happen to be in
    a bucket. A 1H candle requires exactly four consecutive 15m bars and a 4H
    candle requires sixteen.  Therefore gaps cannot silently fabricate a higher
    timeframe candle or leak partial candle information into a decision.
    """

    expected = target_ms // M15_MS
    groups: dict[int, list[Candle]] = {}
    for bar in base:
        bucket = (bar.time // target_ms) * target_ms
        groups.setdefault(bucket, []).append(bar)
    result: list[Candle] = []
    incomplete = 0
    for bucket in sorted(groups):
        group = sorted(groups[bucket], key=lambda bar: bar.time)
        wanted = [bucket + step * M15_MS for step in range(expected)]
        if len(group) != expected or [bar.time for bar in group] != wanted:
            incomplete += 1
            continue
        result.append(
            Candle(
                time=bucket,
                open=group[0].open,
                high=max(bar.high for bar in group),
                low=min(bar.low for bar in group),
                close=group[-1].close,
                volume=sum(bar.volume for bar in group),
            )
        )
    return result, incomplete


# ---------------------------------------------------------------------------
# Indicator snapshot: same periods and cloud displacement as the reference.
# ---------------------------------------------------------------------------


def r2(value: float) -> float:
    return round(value * 100.0) / 100.0


def _midpoint(bars: Sequence[Candle], end: int, period: int) -> float:
    start = max(0, end - period + 1)
    window = bars[start : end + 1]
    return (max(bar.high for bar in window) + min(bar.low for bar in window)) / 2.0


def _ema(values: Sequence[float], period: int) -> list[float]:
    if not values:
        return []
    factor = 2.0 / (period + 1.0)
    output = [values[0]]
    for value in values[1:]:
        output.append(value * factor + output[-1] * (1.0 - factor))
    return output


def _merge_nearby(levels: Sequence[float], tolerance: float) -> list[float]:
    if not levels:
        return []
    ordered = sorted(levels)
    answer: list[float] = []
    total, count = ordered[0], 1
    for value in ordered[1:]:
        average = total / count
        if abs(value - average) <= tolerance:
            total += value
            count += 1
        else:
            answer.append(total / count)
            total, count = value, 1
    answer.append(total / count)
    return answer


def snapshot(candles: Sequence[Candle], config: Config) -> Optional[Snapshot]:
    """Translate computeSnapshot for the pure BASE_ICHIMOKU path.

    The reference keeps the last 200 closed bars in the non-Hidden-Cross path.
    All calculations below operate only on that view, matching its no-look-ahead
    window and its current-cloud source index (displacement - 1).
    """

    if len(candles) < max(config.senkou_b_period + 5, 35):
        return None
    bars = list(candles[-WINDOW_BARS:])
    n = len(bars)
    last = n - 1
    prev = max(0, last - 1)
    disp = max(1, config.displacement - 1)

    tenkan_series = [_midpoint(bars, index, config.tenkan_period) for index in range(n)]
    kijun_series = [_midpoint(bars, index, config.kijun_period) for index in range(n)]
    span_b_series = [_midpoint(bars, index, config.senkou_b_period) for index in range(n)]

    def span_a_creation(index: int) -> float:
        index = max(0, min(last, index))
        return (tenkan_series[index] + kijun_series[index]) / 2.0

    def span_b_creation(index: int) -> float:
        return span_b_series[max(0, min(last, index))]

    def current_a(index: int) -> float:
        return span_a_creation(max(0, index - disp))

    def current_b(index: int) -> float:
        return span_b_creation(max(0, index - disp))

    tenkan, kijun = tenkan_series[last], kijun_series[last]
    prev_tenkan, prev_kijun = tenkan_series[prev], kijun_series[prev]
    cur_a, cur_b = current_a(last), current_b(last)
    fut_a, fut_b = span_a_creation(last), span_b_creation(last)
    prev_fut_a, prev_fut_b = span_a_creation(prev), span_b_creation(prev)

    atr_period = max(1, min(14, n - 1))
    true_ranges: list[float] = []
    for index, bar in enumerate(bars):
        prior_close = bars[max(0, index - 1)].close
        true_ranges.append(max(bar.high - bar.low, abs(bar.high - prior_close), abs(bar.low - prior_close)))
    atr14 = max(sum(true_ranges[last - atr_period + 1 : last + 1]) / atr_period, bars[last].close * 0.0005)
    atr_series: list[float] = []
    for index, bar in enumerate(bars):
        start = max(0, index - atr_period + 1)
        atr_series.append(max(sum(true_ranges[start : index + 1]) / (index - start + 1), bar.close * 0.0005))

    close = bars[last].close
    chikou_index = max(0, last - disp)
    bullish_open, bearish_open = True, True
    for index in range(chikou_index, min(last, chikou_index + 6) + 1):
        bar = bars[index]
        cloud_top, cloud_bottom = max(current_a(index), current_b(index)), min(current_a(index), current_b(index))
        tk_top, tk_bottom = max(tenkan_series[index], kijun_series[index]), min(tenkan_series[index], kijun_series[index])
        if close <= max(bar.high, cloud_top, tk_top):
            bullish_open = False
        if close >= min(bar.low, cloud_bottom, tk_bottom):
            bearish_open = False

    chikou_start = max(1, last - config.kijun_period)
    chikou_values = [bar.close for bar in bars[chikou_start:last]] or [close]
    chikou_peak, chikou_trough = max(chikou_values), min(chikou_values)

    flat_tol = max(close * 0.0003, atr14 * 0.04)
    span_b2 = current_b(max(0, last - 2))
    span_b_flat = abs(cur_b - current_b(prev)) <= flat_tol and abs(cur_b - span_b2) <= flat_tol
    kijun_flat = abs(kijun - prev_kijun) <= flat_tol
    tenkan_flat = abs(tenkan - prev_tenkan) <= flat_tol

    raw_plateaus: list[float] = []
    for index in range(max(3, last - 80), last + 1):
        if abs(kijun_series[index] - kijun_series[index - 1]) <= flat_tol and abs(kijun_series[index] - kijun_series[index - 2]) <= flat_tol:
            raw_plateaus.append(kijun_series[index])
        ssb = current_b(index)
        if abs(ssb - current_b(index - 1)) <= flat_tol and abs(ssb - current_b(index - 2)) <= flat_tol:
            raw_plateaus.append(ssb)
    plateaus = _merge_nearby(raw_plateaus, max(atr14 * 0.35, close * 0.0015))

    cur = bars[last]
    bar_range = max(cur.high - cur.low, 1e-8)
    body_ratio = abs(cur.close - cur.open) / bar_range
    clv = min(1.0, max(0.0, (cur.close - cur.low) / bar_range))
    volume_window = bars[max(0, last - 19) : last + 1]
    average_volume = sum(bar.volume for bar in volume_window) / len(volume_window)
    volume_ratio = cur.volume / average_volume if average_volume > 0.0 and cur.volume > 0.0 else 1.0

    kijun_slope = (kijun - kijun_series[max(0, last - 3)]) / (3.0 * atr14)
    tenkan_slope = (tenkan - prev_tenkan) / atr14
    future_thickness = abs(fut_a - fut_b) / atr14
    current_thickness = abs(cur_a - cur_b) / atr14
    distance_kijun = abs(cur.close - kijun) / atr14
    c_clamp = kijun_flat and span_b_flat and distance_kijun > config.max_kijun_extension_atr * 1.05
    katana = abs(tenkan - kijun) <= flat_tol * 1.2

    before1, before2 = bars[max(0, last - 1)], bars[max(0, last - 2)]
    bull_impulse = sum(1 for bar in (before2, before1, cur) if bar.close > bar.open) >= 2 and cur.close >= max(before1.close, before2.close)
    bear_impulse = sum(1 for bar in (before2, before1, cur) if bar.close < bar.open) >= 2 and cur.close <= min(before1.close, before2.close)

    er_period = max(1, min(14, last))
    net_displacement = abs(cur.close - bars[last - er_period].close)
    path_length = sum(abs(bars[index].close - bars[index - 1].close) for index in range(last - er_period + 1, last + 1))
    efficiency = min(1.0, max(0.0, net_displacement / path_length)) if path_length > 1e-8 else 0.5

    top_body, bottom_body = max(cur.open, cur.close), min(cur.open, cur.close)
    upper_wick = min(1.0, max(0.0, (cur.high - top_body) / bar_range))
    lower_wick = min(1.0, max(0.0, (bottom_body - cur.low) / bar_range))
    compressed_recently = (
        abs(prev_tenkan - prev_kijun) <= atr14 * 0.18
        or abs(tenkan_series[max(0, last - 2)] - kijun_series[max(0, last - 2)]) <= atr14 * 0.18
        or abs(tenkan_series[max(0, last - 3)] - kijun_series[max(0, last - 3)]) <= atr14 * 0.18
    )
    katana_release = compressed_recently and abs(tenkan - kijun) >= abs(prev_tenkan - prev_kijun) and body_ratio >= 0.32

    # Five-bar Williams fractals. The reference starts with the recent 9 bar extreme.
    recent = bars[max(0, last - 8) : last + 1]
    down_fractal = min(bar.low for bar in recent)
    up_fractal = max(bar.high for bar in recent)
    for index in range(last - 2, max(2, last - 28) - 1, -1):
        candidate = bars[index]
        if candidate.low <= bars[index - 1].low and candidate.low <= bars[index - 2].low and candidate.low <= bars[index + 1].low and candidate.low <= bars[index + 2].low:
            down_fractal = candidate.low
            break
    for index in range(last - 2, max(2, last - 28) - 1, -1):
        candidate = bars[index]
        if candidate.high >= bars[index - 1].high and candidate.high >= bars[index - 2].high and candidate.high >= bars[index + 1].high and candidate.high >= bars[index + 2].high:
            up_fractal = candidate.high
            break

    closes = [bar.close for bar in bars]
    ema34 = _ema(closes, 34)[last]
    kijun30 = _midpoint(bars, last, 30)

    return Snapshot(
        time=cur.time,
        open=cur.open,
        high=cur.high,
        low=cur.low,
        close=cur.close,
        tenkan=r2(tenkan),
        kijun=r2(kijun),
        prev_tenkan=r2(prev_tenkan),
        prev_kijun=r2(prev_kijun),
        current_span_a=r2(cur_a),
        current_span_b=r2(cur_b),
        future_span_a=r2(fut_a),
        future_span_b=r2(fut_b),
        prev_future_span_a=r2(prev_fut_a),
        prev_future_span_b=r2(prev_fut_b),
        atr14=r2(atr14),
        chikou_bullish_open=bullish_open,
        chikou_bearish_open=bearish_open,
        chikou_resistance_peak=r2(chikou_peak),
        chikou_support_trough=r2(chikou_trough),
        current_span_b_flat=span_b_flat,
        kijun_flat=kijun_flat,
        tenkan_flat=tenkan_flat,
        flat_equilibrium_levels=[r2(level) for level in plateaus],
        body_ratio=r2(body_ratio),
        clv=r2(clv),
        volume_ratio20=r2(volume_ratio),
        kijun_slope_atr=r2(kijun_slope),
        tenkan_slope_atr=r2(tenkan_slope),
        future_kumo_thickness_atr=r2(future_thickness),
        current_kumo_thickness_atr=r2(current_thickness),
        c_clamp_warning=c_clamp,
        katana_equilibrium=katana,
        bullish_impulse3=bull_impulse,
        bearish_impulse3=bear_impulse,
        efficiency_ratio14=r2(efficiency),
        upper_wick_ratio=r2(upper_wick),
        lower_wick_ratio=r2(lower_wick),
        recent_katana_release=katana_release,
        last_down_fractal_low=r2(down_fractal),
        last_up_fractal_high=r2(up_fractal),
        kijun30=r2(kijun30),
        ema34=r2(ema34),
    )


# ---------------------------------------------------------------------------
# Four-school signal model, plan guard and book confluence gate.
# ---------------------------------------------------------------------------


def hosoda_wave(candles: Sequence[Candle], direction: str, snap: Snapshot) -> Optional[HosodaWave]:
    bars = list(candles[-65:])
    if len(bars) < 12 or direction not in ("BUY", "SELL"):
        return None
    last = len(bars) - 1
    c_start = max(4, last - 6)
    cycles = (9, 17, 26, 33, 42, 65, 76, 129, 172)

    if direction == "BUY":
        c_index = min(range(c_start, last + 1), key=lambda index: bars[index].low)
        b_start = max(2, c_index - 22)
        b_index = max(range(b_start, c_index), key=lambda index: bars[index].high, default=b_start)
        a_start = max(0, b_index - 45)
        a_index = min(range(a_start, b_index + 1), key=lambda index: bars[index].low)
        raw_a, raw_b, raw_c = bars[a_index].low, max(bars[b_index].high, snap.close), min(bars[c_index].low, snap.tenkan)
        height = max(raw_b - raw_a, snap.distance_to_kijun * 2.4, snap.atr14 * 3.0)
        point_a, point_b = raw_b - height, raw_b
        point_c = min(max(raw_c, point_a + snap.atr14 * 0.3), point_b - snap.atr14 * 0.3)
        targets = (
            max(point_b + (point_b - point_c), snap.close + snap.atr14 * 1.5),
            max(point_c + (point_b - point_a), snap.close + snap.atr14 * 2.2),
            max(point_b + (point_b - point_a), snap.close + snap.atr14 * 3.0),
            max(point_c + (point_c - point_a), snap.close + snap.atr14 * 1.2),
        )
        held = point_c >= snap.kumo_bottom
    else:
        c_index = max(range(c_start, last + 1), key=lambda index: bars[index].high)
        b_start = max(2, c_index - 22)
        b_index = min(range(b_start, c_index), key=lambda index: bars[index].low, default=b_start)
        a_start = max(0, b_index - 45)
        a_index = max(range(a_start, b_index + 1), key=lambda index: bars[index].high)
        raw_a, raw_b, raw_c = bars[a_index].high, min(bars[b_index].low, snap.close), max(bars[c_index].high, snap.tenkan)
        height = max(raw_a - raw_b, snap.distance_to_kijun * 2.4, snap.atr14 * 3.0)
        point_a, point_b = raw_b + height, raw_b
        point_c = min(max(raw_c, point_b + snap.atr14 * 0.3), point_a - snap.atr14 * 0.3)
        targets = (
            max(0.00001, min(point_b - (point_c - point_b), snap.close - snap.atr14 * 1.5)),
            max(0.00001, min(point_c - (point_a - point_b), snap.close - snap.atr14 * 2.2)),
            max(0.00001, min(point_b - (point_a - point_b), snap.close - snap.atr14 * 3.0)),
            max(0.00001, min(point_c - (point_a - point_c), snap.close - snap.atr14 * 1.2)),
        )
        held = point_c <= snap.kumo_top

    bars_since = max(1, last - a_index + 1)
    nearest = min(cycles, key=lambda cycle: abs(cycle - bars_since))
    return HosodaWave(
        direction=direction,
        point_a=r2(point_a),
        point_b=r2(point_b),
        point_c=r2(point_c),
        s_point_held=held,
        target_v=r2(targets[0]),
        target_n=r2(targets[1]),
        target_e=r2(targets[2]),
        target_nt=r2(targets[3]),
        bars_since_start=bars_since,
        nearest_cycle=nearest,
        near_cycle_change=abs(nearest - bars_since) <= 1,
    )


def asset_class(symbol: str) -> str:
    clean = symbol.replace("/", "").upper().strip()
    # The Android reference labels USDT pairs CRYPTO before checking XAU.
    if "USDT" in clean or clean.startswith(("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "LINK", "TON", "DOT", "SHIB", "PEPE", "SUI", "NEAR")):
        return "CRYPTO"
    if clean.startswith(("XAU", "XAG", "BRENT", "WTI", "USOIL", "UKOIL", "NATGAS", "COPPER")) or clean in {"GOLD", "SILVER"}:
        return "COMMODITY"
    return "FOREX"


def tick_size(symbol: str) -> float:
    """VenueSpecs tick size for the asset classes used by the BASE path."""
    kind = asset_class(symbol)
    clean = symbol.replace("/", "").upper().strip()
    if kind == "CRYPTO":
        if clean.startswith(("BTC", "ETH", "BNB", "SOL")):
            return 0.01
        return 0.0001
    if kind == "COMMODITY":
        return 0.01
    return 0.001 if clean.endswith("JPY") else 0.00001


def round_to_tick(symbol: str, price: float) -> float:
    tick = max(tick_size(symbol), 1e-8)
    # Kotlin round() uses nearest tick then a deterministic decimal precision.
    snapped = round(price / tick) * tick
    decimals = 2 if tick >= 0.01 else 3 if tick >= 0.001 else 4 if tick >= 0.0001 else 5
    return round(snapped, decimals)


def venue_cost(symbol: str, price: float) -> tuple[float, float, float]:
    """Reference VenueSpecs fees for one price unit (commission side, spread round trip)."""
    kind = asset_class(symbol)
    clean = symbol.upper()
    if kind == "CRYPTO":
        # Binance/KuCoin paper benchmark: 10 bps each side plus 1 bp round-trip spread.
        return price * 10.0 / 10_000.0, price * 10.0 / 10_000.0, price * 1.0 / 10_000.0
    if kind == "COMMODITY" and clean.startswith(("XAU", "GOLD")):
        # Model classification intentionally makes XAUUSDT crypto; this applies to XAU/USD.
        return 0.035, 0.035, 0.12
    if kind == "COMMODITY":
        return 0.0, 0.0, 0.03
    # Forex: IC Markets $3.50 / 100k / side, raw spread 0.1 pip for majors.
    return 0.000035, 0.000035, 0.00001


def round_trip_cost_pct(symbol: str, price: float) -> float:
    entry_fee, exit_fee, spread = venue_cost(symbol, price)
    return max(0.0, (entry_fee + exit_fee + spread) / max(price, EPS))


def _bounds(symbol: str) -> tuple[float, float, float]:
    kind = asset_class(symbol)
    raw = {
        "CRYPTO": (0.0016, 0.0160),
        "FOREX": (0.0003, 0.0028),
        "COMMODITY": (0.0007, 0.0055),
    }[kind]
    min_pct = min(max(raw[0], 0.00018), raw[1] * 0.40)
    max_pct = min(max(raw[1], 0.00018 * 2), 0.065)
    return min_pct, max_pct, max_pct * 1.80


def robust_atr(symbol: str, price: float, atr: float) -> float:
    _, max_stop_pct, _ = _bounds(symbol)
    floor = price * max_stop_pct / 9.0
    ceiling = price * max_stop_pct / 1.6
    fallback = price * max_stop_pct / 2.6
    return min(max(atr if atr > 0.0 and math.isfinite(atr) else fallback, floor), ceiling)


def min_safe_stop_distance(symbol: str, price: float, trigger_atr: float, strategy_atr: float) -> float:
    kind = asset_class(symbol)
    trigger_atr = trigger_atr if trigger_atr > 0.0 else price * 0.002
    strategy_atr = strategy_atr if strategy_atr > 0.0 else trigger_atr
    if kind == "CRYPTO":
        pct_floor = price * (0.0020 if symbol.upper().replace("/", "").startswith(("BTC", "ETH")) else 0.0025)
        # Venue min stop is 2*(spread) / tick; the crypto reference has a 1bp spread.
        venue_floor = price * 0.0002 * 3.0
        return max(venue_floor, pct_floor, trigger_atr * 0.82, strategy_atr * 0.28)
    if kind == "COMMODITY":
        venue_floor = 0.24 * 2.5 if symbol.upper().startswith(("XAU", "GOLD")) else 0.06 * 2.5
        return max(venue_floor, price * 0.0009, trigger_atr * 0.78, strategy_atr * 0.25)
    spread_price = 0.001 if symbol.upper().replace("/", "").endswith("JPY") else 0.00001
    venue_floor = max(2.0 * spread_price, 2.0 * tick_size(symbol)) * 2.5
    return max(venue_floor, price * 0.0004, trigger_atr * 0.75, strategy_atr * 0.24)


def structural_stop(symbol: str, direction: str, entry: float, trig: Snapshot, strat: Snapshot, wave: Optional[HosodaWave], config: Config) -> float:
    """Port of BASE_ICHIMOKU multi-anchor stop selection for all four schools.

    BASE_ICHIMOKU has all four school flags.  The reference priority is therefore
    Patel's union-first order: Kijun26, Kijun30/EMA34, fractal, Kumo edge, wave S.
    """
    atr = robust_atr(symbol, entry, max(trig.atr14, entry * 0.0004))
    strategy_atr = robust_atr(symbol, entry, strat.atr14)
    min_safe = min_safe_stop_distance(symbol, entry, atr, strategy_atr)
    min_pct, max_pct, _ = _bounds(symbol)
    _, _, spread = venue_cost(symbol, entry)
    venue_floor = min(2.0 * max(2.0 * spread, 0.0), entry * max_pct)
    floor = min(entry * max_pct, max(min_safe, entry * min_pct, 0.80 * atr, venue_floor))
    ceiling = max(entry * min_pct, min(entry * max_pct, 2.10 * atr))
    adverse_wick = trig.lower_wick_ratio if direction == "BUY" else trig.upper_wick_ratio
    shield = atr * (min(max(config.sl_buffer_atr, 0.18), 0.42) + 0.16 * min(max(adverse_wick, 0.0), 0.65))
    # MIN_RR_TO_ROUND_TRIP_COST=2.20 and COST_FLOOR_MAX_PCT=4.5% in the engine.
    floor = max(floor, entry * min(max(round_trip_cost_pct(symbol, entry) * 2.20, 0.0), 0.045))

    if direction == "BUY":
        anchors = [
            trig.kijun - shield,
            min(trig.kijun30, trig.ema34) - shield * 0.90,
            min(trig.last_down_fractal_low, trig.low) - shield,
            trig.kumo_bottom - shield * 0.85,
            (wave.point_c if wave else trig.kijun) - shield,
        ]
        valid = [value for value in anchors if math.isfinite(value) and 0.0 < value < entry]
    else:
        anchors = [
            trig.kijun + shield,
            max(trig.kijun30, trig.ema34) + shield * 0.90,
            max(trig.last_up_fractal_high, trig.high) + shield,
            trig.kumo_top + shield * 0.85,
            (wave.point_c if wave else trig.kijun) + shield,
        ]
        valid = [value for value in anchors if math.isfinite(value) and value > entry]

    def distance(value: float) -> float:
        return entry - value if direction == "BUY" else value - entry

    # `chooseStrategyStopAnchor`: first school-priority anchor inside corridor.
    selected = next((value for value in valid if floor <= distance(value) <= ceiling), None)
    if selected is None:
        # A preferred level inside the normal noise floor becomes a floor-distance stop.
        if any(distance(value) < floor for value in valid):
            selected = entry - floor if direction == "BUY" else entry + floor
        elif valid:
            # Same fallback as TradePlanGuard.chooseStructuralAnchor: closest valid
            # structural anchor, then clamped to the volatility corridor.
            nearest = min(valid, key=distance)
            selected_distance = min(max(distance(nearest), floor), ceiling)
            selected = entry - selected_distance if direction == "BUY" else entry + selected_distance
        else:
            selected = entry - floor if direction == "BUY" else entry + floor
    return r2(max(0.00001, selected))


def sanitize_plan(symbol: str, direction: str, entry: float, raw_stop: float, raw_target: float, desired_rr: float, atr: float, strategy_atr: float) -> GuardedPlan:
    """Relevant M15 TradePlanGuard behavior: ATR corridor, RR and cost floor."""
    if entry <= 0.0 or direction not in ("BUY", "SELL"):
        return GuardedPlan(False, entry, 0.0, 0.0, 0.0, 0.0, 0.0, "invalid entry")
    min_pct, max_pct, max_target_pct = _bounds(symbol)
    robust = robust_atr(symbol, entry, atr if atr > 0.0 else strategy_atr)
    structural_floor = min_safe_stop_distance(symbol, entry, robust, robust_atr(symbol, entry, strategy_atr))
    entry_fee, exit_fee, spread = venue_cost(symbol, entry)
    venue_floor = min(2.0 * max(2.0 * spread, 0.0), entry * max_pct)
    floor = min(entry * max_pct, max(structural_floor, entry * min_pct, 0.80 * robust, venue_floor))
    ceiling = max(floor, max(entry * min_pct, min(entry * max_pct, 2.10 * robust)))
    desired_rr = min(3.0, max(1.5, desired_rr))
    target_ceiling = max(floor * 1.5, min(max_target_pct * entry, 3.40 * robust) * 0.995)
    stop_ceiling = max(floor, min(ceiling, target_ceiling / desired_rr) * 0.995)
    raw_stop_distance = abs(entry - raw_stop) if ((direction == "BUY" and raw_stop < entry) or (direction == "SELL" and raw_stop > entry)) else 0.0
    stop_distance = min(max(raw_stop_distance or floor, floor), stop_ceiling)
    raw_target_distance = abs(raw_target - entry) if ((direction == "BUY" and raw_target > entry) or (direction == "SELL" and raw_target < entry)) else 0.0
    structured_target = raw_target_distance if raw_target_distance / max(stop_distance, EPS) >= 1.5 else stop_distance * desired_rr
    upper_target = max(stop_distance * 1.5, min(stop_distance * 3.0, target_ceiling))
    fee_floor = entry * round_trip_cost_pct(symbol, entry) * 2.0
    if upper_target < fee_floor:
        return GuardedPlan(False, entry, 0.0, 0.0, stop_distance, 0.0, 0.0, "target below twice round-trip cost")
    target_distance = min(max(structured_target, max(stop_distance * 1.5, fee_floor)), upper_target)
    stop = round_to_tick(symbol, entry - stop_distance if direction == "BUY" else entry + stop_distance)
    target = round_to_tick(symbol, entry + target_distance if direction == "BUY" else entry - target_distance)
    stop_distance, target_distance = abs(entry - stop), abs(target - entry)
    rr = min(3.0, max(1.5, target_distance / max(stop_distance, EPS)))
    return GuardedPlan(True, round_to_tick(symbol, entry), stop, target, stop_distance, target_distance, r2(rr))


def detect_trigger(direction: str, bars: Sequence[Candle], trig: Snapshot, strat: Snapshot, config: Config) -> str:
    if len(bars) < 5:
        return "NONE"
    cur, prev1, prev2, prev3 = bars[-1], bars[-2], bars[-3], bars[-4]
    buffer = trig.atr14 * (config.breakout_buffer_atr if trig.current_span_b_flat or strat.current_span_b_flat else 0.03)
    if direction == "BUY":
        if trig.recent_katana_release and cur.close > cur.open and cur.close > max(trig.tenkan, trig.kijun) and cur.close > trig.kumo_top and trig.chikou_bullish_open and trig.distance_to_kijun_atr <= 1.15:
            return "EARLY_KATANA_GENESIS"
        touched = any(bar.low <= max(trig.tenkan, trig.kijun) + trig.atr14 * 0.35 and bar.close >= trig.kijun - trig.atr14 * 0.20 for bar in (prev1, prev2, prev3, cur))
        if touched and cur.close > cur.open and cur.close >= trig.tenkan and cur.close > trig.kumo_top:
            return "PULLBACK_BOUNCE"
        crossed = (prev1.close <= trig.kumo_top or prev2.close <= trig.kumo_top or prev1.close <= trig.kijun) and cur.close > max(trig.kumo_top, trig.kijun) + buffer
        if crossed and cur.close >= trig.chikou_resistance_peak + buffer and cur.close > cur.open:
            return "VALIDATED_BREAKOUT"
        if trig.sanyaku_bullish and trig.bullish_impulse3 and cur.close >= max(prev1.high, prev2.high) and trig.distance_to_kijun_atr <= config.max_kijun_extension_atr * 0.85:
            return "SANYAKU_TK_CONTINUATION"
        return "NONE"
    if trig.recent_katana_release and cur.close < cur.open and cur.close < min(trig.tenkan, trig.kijun) and cur.close < trig.kumo_bottom and trig.chikou_bearish_open and trig.distance_to_kijun_atr <= 1.15:
        return "EARLY_KATANA_GENESIS"
    touched = any(bar.high >= min(trig.tenkan, trig.kijun) - trig.atr14 * 0.35 and bar.close <= trig.kijun + trig.atr14 * 0.20 for bar in (prev1, prev2, prev3, cur))
    if touched and cur.close < cur.open and cur.close <= trig.tenkan and cur.close < trig.kumo_bottom:
        return "PULLBACK_BOUNCE"
    crossed = (prev1.close >= trig.kumo_bottom or prev2.close >= trig.kumo_bottom or prev1.close >= trig.kijun) and cur.close < min(trig.kumo_bottom, trig.kijun) - buffer
    if crossed and cur.close <= trig.chikou_support_trough - buffer and cur.close < cur.open:
        return "VALIDATED_BREAKOUT"
    if trig.sanyaku_bearish and trig.bearish_impulse3 and cur.close <= min(prev1.low, prev2.low) and trig.distance_to_kijun_atr <= config.max_kijun_extension_atr * 0.85:
        return "SANYAKU_TK_CONTINUATION"
    return "NONE"


def book_gate(conditions: dict[int, bool], config: Config) -> dict[str, float | bool]:
    weights = {1: 1.0, 3: 1.0, 4: 1.0, 6: 1.0, 8: 1.0, 9: 1.0, 13: 1.0, 15: 1.0, 5: 0.6, 14: 0.6, 11: 1.2, 16: 1.2, 17: 1.2, 10: 0.8, 12: 1.0, 2: 0.6, 7: 0.6}
    hard = {1, 3, 4, 6, 17}
    quality = {5, 8, 9, 13, 14, 15}
    trigger = {2, 7, 10, 11, 12, 16, 17}

    def score(indices: set[int]) -> float:
        maximum = sum(weights[index] for index in indices)
        gained = sum(weights[index] for index in indices if conditions.get(index, False))
        return 100.0 * gained / maximum

    regime_pct, quality_pct, trigger_pct = score(hard), score(quality), score(trigger)
    # The Kotlin gate deliberately totals the three layers, not their set-union.
    # Condition 17 is both a hard economic veto and a trigger/economy condition,
    # so its 1.2 weight appears in both layer totals.
    layer_sets = (hard, quality, trigger)
    total_gained = sum(sum(weights[index] for index in group if conditions.get(index, False)) for group in layer_sets)
    total_available = sum(sum(weights[index] for index in group) for group in layer_sets)
    total = 100.0 * total_gained / total_available
    minimum_layer, minimum_total = (60.0, 70.0) if config.allow_maximum_execution else (70.0, 80.0)
    hard_ok = all(conditions.get(index, False) for index in hard)
    fires = hard_ok and quality_pct >= minimum_layer and trigger_pct >= minimum_layer and total >= minimum_total
    watch = (not fires) and hard_ok and total >= 76.0
    return {"hard_ok": hard_ok, "fires": fires, "watch": watch, "total": r2(total), "regime": r2(regime_pct), "quality": r2(quality_pct), "trigger": r2(trigger_pct)}


def evaluate_base(
    macro_bars: Sequence[Candle],
    strategy_bars: Sequence[Candle],
    trigger_bars: Sequence[Candle],
    config: Config,
) -> Optional[Evaluation]:
    """Evaluate all seventeen BASE_ICHIMOKU conditions on closed candle windows."""
    macro = snapshot(macro_bars, config)
    strat = snapshot(strategy_bars, config)
    trig = snapshot(trigger_bars, config)
    if strat is None or trig is None or len(trigger_bars) < 5:
        return None

    # Exact BASE_ICHIMOKU direction selection, no hidden/other model branch.
    bullish_bias = strat.price_above_kumo or (macro is not None and macro.price_above_kumo and trig.price_above_kumo)
    bearish_bias = strat.price_below_kumo or (macro is not None and macro.price_below_kumo and trig.price_below_kumo)
    if bullish_bias and not bearish_bias:
        direction = "BUY"
    elif bearish_bias and not bullish_bias:
        direction = "SELL"
    else:
        direction = "BUY" if strat.tenkan >= strat.kijun else "SELL"
    long = direction == "BUY"

    # 1 — 3TF Kumo regime.
    strat_kumo = strat.price_above_kumo if long else strat.price_below_kumo
    trig_kumo = trig.price_above_kumo if long else trig.price_below_kumo
    if not config.require_higher_tf_alignment or macro is None:
        macro_kumo = True
    else:
        macro_kumo = not macro.price_below_kumo if long else not macro.price_above_kumo
    c1 = strat_kumo and trig_kumo and macro_kumo

    # 2 — Sanyaku five-line order.
    c2 = (strat.sanyaku_bullish and trig.sanyaku_bullish) if long else (strat.sanyaku_bearish and trig.sanyaku_bearish)

    # 3 — TK alignment and dynamic slope.
    if long:
        c3 = (
            strat.tenkan >= strat.kijun
            and strat.tenkan >= strat.prev_tenkan - strat.atr14 * 0.05
            and strat.kijun >= strat.prev_kijun - strat.atr14 * 0.02
            and trig.tenkan >= trig.prev_tenkan - trig.atr14 * 0.05
        )
    else:
        c3 = (
            strat.tenkan <= strat.kijun
            and strat.tenkan <= strat.prev_tenkan + strat.atr14 * 0.05
            and strat.kijun <= strat.prev_kijun + strat.atr14 * 0.02
            and trig.tenkan <= trig.prev_tenkan + trig.atr14 * 0.05
        )

    # 4 — future Kumo direction. 5 — future thickness.
    if not config.require_future_kumo_slope:
        c4 = True
    elif long:
        c4 = strat.future_kumo_bullish and strat.future_span_a_rising and strat.future_span_b_non_falling and trig.future_kumo_bullish
    else:
        c4 = strat.future_kumo_bearish and strat.future_span_a_falling and strat.future_span_b_non_rising and trig.future_kumo_bearish
    c5 = (
        strat.future_kumo_thickness_atr >= config.min_future_kumo_thickness_atr
        and trig.future_kumo_thickness_atr >= max(0.02, config.min_future_kumo_thickness_atr * 0.80)
    )

    # 6/7 — Chikou free space and structural clearance.
    if not config.require_chikou_open_space:
        c6 = True
    else:
        c6 = (strat.chikou_bullish_open and trig.chikou_bullish_open) if long else (strat.chikou_bearish_open and trig.chikou_bearish_open)
    c7 = trig.close >= trig.chikou_resistance_peak - trig.atr14 * 0.15 if long else trig.close <= trig.chikou_support_trough + trig.atr14 * 0.15

    # 8/9 — Kijun rubber band and flat SSB gravity clearance.
    c8 = strat.distance_to_kijun_atr <= config.max_kijun_extension_atr and not strat.c_clamp_warning and not trig.c_clamp_warning
    gravity_clearance = trig.atr14 * config.breakout_buffer_atr if trig.current_span_b_flat or strat.current_span_b_flat else 0.0
    c9 = trig.close >= trig.kumo_top + gravity_clearance if long else trig.close <= trig.kumo_bottom - gravity_clearance

    trigger = detect_trigger(direction, trigger_bars, trig, strat, config)
    live_price = trig.close
    candle_pocket = trig.low + (trig.high - trig.low) * 0.55 if long else trig.high - (trig.high - trig.low) * 0.55
    entry_anchor = r2(max(trig.tenkan, min(live_price, candle_pocket)) if long else min(trig.tenkan, max(live_price, candle_pocket)))
    wave = hosoda_wave(strategy_bars, direction, strat)
    raw_stop = structural_stop(
        config.symbol,
        direction,
        min(entry_anchor, live_price) if long else max(entry_anchor, live_price),
        trig,
        strat,
        wave,
        config,
    )
    raw_stop_distance = max(abs(entry_anchor - raw_stop), abs(live_price - raw_stop), min_safe_stop_distance(config.symbol, live_price, trig.atr14, strat.atr14))

    # 10 — Hosoda wave S point + time cycle.
    c10 = (wave is None or wave.s_point_held) and not (wave is not None and wave.near_cycle_change and trig.tenkan_flat)

    # 11 — trigger candle price action and volume.
    directional_candle = (trig.close > trig.open and trig.clv >= 0.45 and trig.body_ratio >= 0.22) if long else (trig.close < trig.open and trig.clv <= 0.55 and trig.body_ratio >= 0.22)
    c11 = directional_candle and trig.volume_ratio20 >= config.min_volume_ratio20

    # 13 and 15 are calculated before target selection in the source.
    dual_flat_chop = strat.kijun_flat and strat.current_span_b_flat and trig.kijun_flat and trig.current_span_b_flat and strat.efficiency_ratio14 < config.min_efficiency_ratio
    htf_counter_trap = (trig.close > trig.kijun and strat.close < strat.kijun - strat.atr14 * 0.25) if long else (trig.close < trig.kijun and strat.close > strat.kijun + strat.atr14 * 0.25)
    c13 = (strat.efficiency_ratio14 >= config.min_efficiency_ratio or trig.efficiency_ratio14 >= config.min_efficiency_ratio + 0.02) and not dual_flat_chop and not htf_counter_trap
    wave_bars = wave.bars_since_start if wave else 12
    if not c13 or dual_flat_chop:
        trend_phase = "CHOP_RANGE"
    elif not c8 or (wave_bars > 55 and trig.tenkan_flat and strat.tenkan_flat):
        trend_phase = "LATE_EXHAUSTION"
    elif wave_bars <= 18 or trig.recent_katana_release or trigger == "EARLY_KATANA_GENESIS":
        trend_phase = "GENESIS"
    else:
        trend_phase = "CORE_EXPANSION"
    c15 = trend_phase in ("GENESIS", "CORE_EXPANSION")

    # Base model optimal RR then Hosoda preferred target. The target always stays [1.5R, 3R].
    phase_bonus = 0.25 if trend_phase == "GENESIS" else 0.12 if trend_phase == "CORE_EXPANSION" else 0.0
    er_bonus = min(0.35, max(-0.15, (strat.efficiency_ratio14 + trig.efficiency_ratio14 - 0.50) * 0.45))
    volume_bonus = 0.12 if trig.volume_ratio20 >= 1.20 else 0.0
    # BASE_ICHIMOKU.baseOptimalRr = 2.25 in the reference model.
    desired_rr = r2(min(3.0, max(config.min_risk_reward, 2.25 + phase_bonus + er_bonus + volume_bonus)))
    minimum_rr = min(2.85, max(1.5, config.min_risk_reward))

    # Reference uses plateau walls from macro + strategy (Hidden Cross wall disabled).
    plateaus = sorted(set((macro.flat_equilibrium_levels if macro else []) + strat.flat_equilibrium_levels))
    obstacle = next((level for level in plateaus if level > live_price + trig.atr14 * 0.4), None) if long else next((level for level in reversed(plateaus) if level < live_price - trig.atr14 * 0.4), None)
    target_candidates: list[float] = []
    if wave:
        target_candidates = [wave.target_nt, wave.target_v, wave.target_n, wave.target_e]
    valid_targets = []
    for candidate in target_candidates:
        in_direction = candidate > max(live_price, entry_anchor) if long else candidate < min(live_price, entry_anchor)
        min_distance = min(abs(candidate - live_price), abs(candidate - entry_anchor))
        max_distance = max(abs(candidate - live_price), abs(candidate - entry_anchor))
        if in_direction and min_distance / max(raw_stop_distance, EPS) >= minimum_rr and max_distance / max(raw_stop_distance, EPS) <= 3.0:
            valid_targets.append(candidate)
    raw_target = min(valid_targets, key=lambda target: abs(abs(target - entry_anchor) / max(raw_stop_distance, EPS) - desired_rr)) if valid_targets else (entry_anchor + raw_stop_distance * desired_rr if long else max(0.00001, entry_anchor - raw_stop_distance * desired_rr))
    if obstacle is not None:
        obstacle_distance = min(abs(obstacle - live_price), abs(obstacle - entry_anchor))
        if obstacle_distance / max(raw_stop_distance, EPS) >= minimum_rr:
            buffered = obstacle - trig.atr14 * 0.08 if long else obstacle + trig.atr14 * 0.08
            candidate = min(raw_target, buffered) if long else max(raw_target, buffered)
            if min(abs(candidate - live_price), abs(candidate - entry_anchor)) / max(raw_stop_distance, EPS) >= minimum_rr:
                raw_target = candidate

    # Before the guard, clamp candidate target into reference RR corridor.
    if long:
        raw_target = r2(min(max(raw_target, entry_anchor + raw_stop_distance * minimum_rr), entry_anchor + raw_stop_distance * 3.0))
    else:
        raw_target = r2(max(min(raw_target, entry_anchor - raw_stop_distance * minimum_rr), entry_anchor - raw_stop_distance * 3.0))
    plan = sanitize_plan(config.symbol, direction, entry_anchor, raw_stop, raw_target, desired_rr, trig.atr14, strat.atr14)

    # 12 — target corridor and room before the closest plateau. 14/16/17.
    obstacle_clear = obstacle is None or min(abs(obstacle - live_price), abs(obstacle - entry_anchor)) / max(plan.stop_distance, EPS) >= minimum_rr
    c12 = plan.usable and minimum_rr <= plan.risk_reward <= 3.0 and obstacle_clear
    adverse = trig.upper_wick_ratio if long else trig.lower_wick_ratio
    favorable = trig.lower_wick_ratio if long else trig.upper_wick_ratio
    c14 = adverse <= config.max_adverse_wick_ratio and (trig.body_ratio >= 0.22 or favorable >= 0.25)
    distance_sniper = abs(live_price - entry_anchor) / max(trig.atr14, EPS)
    distance_tenkan = abs(trig.close - trig.tenkan) / max(trig.atr14, EPS)
    c16 = distance_sniper <= config.max_sniper_distance_atr and distance_tenkan <= config.max_sniper_distance_atr + 0.50
    one_r = max(abs(entry_anchor - plan.stop), EPS)
    cost_r = round_trip_cost_pct(config.symbol, entry_anchor) * entry_anchor / one_r
    c17 = cost_r <= 0.45

    conditions = {1: c1, 2: c2, 3: c3, 4: c4, 5: c5, 6: c6, 7: c7, 8: c8, 9: c9, 10: c10, 11: c11, 12: c12, 13: c13, 14: c14, 15: c15, 16: c16, 17: c17}
    gate = book_gate(conditions, config)
    confidence = r2(min(100.0, max(0.0, sum(conditions.values()) / 17.0 * 84.0 + (16.0 if trigger != "NONE" else 0.0))))
    action = direction if trigger != "NONE" and bool(gate["fires"]) and plan.usable else "NO_TRADE"
    return Evaluation(action, direction, trigger, entry_anchor, plan.stop, plan.target, trig.atr14, strat.atr14, desired_rr, plan, conditions, gate, confidence)


# ---------------------------------------------------------------------------
# Candle-close replay. Signals close a candle; orders fill at next candle open.
# ---------------------------------------------------------------------------


def _closed_window(bars: Sequence[Candle], starts: Sequence[int], now: int, interval_ms: int) -> list[Candle]:
    """Return the final WINDOW_BARS fully closed at `now`, never a future bar."""
    end = bisect_right(starts, now - interval_ms)
    begin = max(0, end - WINDOW_BARS)
    return list(bars[begin:end])


def rebase_pending(order: PendingOrder, fill_price: float, config: Config) -> GuardedPlan:
    """Move the signal plan to next-open price, rejecting a stale next-open fill."""
    robust = robust_atr(config.symbol, fill_price, order.atr)
    _, max_stop_pct, _ = _bounds(config.symbol)
    limit = max(1.25 * robust, 3.0 * _bounds(config.symbol)[0] * fill_price)
    if abs(fill_price - order.anchor_entry) > limit:
        return GuardedPlan(False, fill_price, 0.0, 0.0, 0.0, 0.0, 0.0, "next-open fill is stale relative to signal anchor")
    shift = fill_price - order.anchor_entry
    return sanitize_plan(
        config.symbol,
        order.action,
        fill_price,
        order.anchor_stop + shift,
        order.anchor_target + shift,
        order.desired_rr,
        order.atr,
        order.strategy_atr,
    )


def intrabar_exit(open_trade: OpenTrade, bar: Candle) -> Optional[tuple[str, float]]:
    """Return a bracket exit for `bar`; a simultaneous touch always chooses SL."""
    if open_trade.action == "BUY":
        stop_hit, target_hit = bar.low <= open_trade.stop, bar.high >= open_trade.target
    else:
        stop_hit, target_hit = bar.high >= open_trade.stop, bar.low <= open_trade.target
    if stop_hit:
        return "STOP_LOSS", open_trade.stop
    if target_hit:
        return "TAKE_PROFIT", open_trade.target
    return None


def _close_trade(open_trade: OpenTrade, exit_price: float, exit_time: int, reason: str, equity: float, config: Config, number: int) -> ClosedTrade:
    raw_price_move = exit_price - open_trade.entry if open_trade.action == "BUY" else open_trade.entry - exit_price
    gross = raw_price_move * open_trade.units
    entry_fee, exit_fee, spread = venue_cost(config.symbol, open_trade.entry)
    fee = (entry_fee + exit_fee + spread) * open_trade.units * max(config.cost_multiplier, 0.0)
    net = gross - fee
    risk_usd = abs(open_trade.entry - open_trade.initial_stop) * open_trade.units
    return ClosedTrade(
        number=number,
        signal_time=open_trade.signal_time,
        entry_time=open_trade.entry_time,
        exit_time=exit_time,
        side=open_trade.action,
        trigger=open_trade.trigger,
        entry=r2(open_trade.entry),
        stop=r2(open_trade.initial_stop),
        target=r2(open_trade.target),
        exit=r2(exit_price),
        exit_reason=reason,
        units=open_trade.units,
        bars_held=open_trade.bars_held,
        planned_rr=open_trade.planned_rr,
        confidence=open_trade.confidence,
        gross_pnl_usd=r2(gross),
        fee_usd=r2(fee),
        net_pnl_usd=r2(net),
        r_multiple=r2(net / risk_usd) if risk_usd > EPS else 0.0,
        equity_after_usd=r2(equity + net),
    )


def run_backtest(base: Sequence[Candle], config: Config) -> tuple[list[ClosedTrade], dict[str, object], list[dict[str, object]], list[dict[str, object]]]:
    """Run pure BASE_ICHIMOKU with next-open fills and SL-first same-bar policy.

    Ordering is deliberately explicit:
      1. a pending prior-close signal fills at this 15m candle open;
      2. SL/TP are checked against this candle after entry (SL wins ties);
      3. if still open, a candle-close signal is evaluated using only bars closed
         at this time and becomes pending for the following candle open.
    """
    if len(base) < WARMUP_BARS + 2:
        raise CsvValidationError("Not enough candles after validation for BASE_ICHIMOKU warmup.")
    hourly, incomplete_h1 = resample_complete(base, H1_MS)
    macro, incomplete_h4 = resample_complete(base, H4_MS)
    if len(hourly) < 35 or len(macro) < 35:
        raise CsvValidationError("Need at least 35 fully formed 1H and 4H candles; extend the CSV history.")

    h1_starts = [bar.time for bar in hourly]
    h4_starts = [bar.time for bar in macro]
    m15_starts = [bar.time for bar in base]
    equity = config.starting_equity
    open_trade: Optional[OpenTrade] = None
    pending: Optional[PendingOrder] = None
    trades: list[ClosedTrade] = []
    equity_curve: list[dict[str, object]] = [{"time": iso_utc(base[0].time), "equity_usd": r2(equity)}]
    evaluations: list[dict[str, object]] = []
    blocker_counts: Counter[str] = Counter()
    maximum_passed = 0
    best_gate = 0.0

    for index, bar in enumerate(base):
        # First open: this is the only possible entry fill for a previous signal.
        if pending is not None and open_trade is None:
            rebased = rebase_pending(pending, bar.open, config)
            if rebased.usable and ((pending.action == "BUY" and rebased.stop < bar.open < rebased.target) or (pending.action == "SELL" and rebased.target < bar.open < rebased.stop)):
                risk_usd = equity * min(5.0, max(0.05, config.risk_percent)) / 100.0
                units = risk_usd / max(rebased.stop_distance, EPS)
                open_trade = OpenTrade(
                    signal_time=pending.signal_time,
                    entry_time=bar.time,
                    action=pending.action,
                    entry=bar.open,
                    initial_stop=rebased.stop,
                    stop=rebased.stop,
                    target=rebased.target,
                    units=units,
                    confidence=pending.confidence,
                    planned_rr=rebased.risk_reward,
                    trigger=pending.trigger,
                )
            pending = None

        # Open trade is managed before evaluating the close. It is active on its fill bar.
        if open_trade is not None:
            open_trade.bars_held += 1
            exit_hit = intrabar_exit(open_trade, bar)
            if exit_hit is not None:
                # conservative intrabar path: `intrabar_exit` selects STOP_LOSS on a tie.
                reason, exit_price = exit_hit
                closed = _close_trade(open_trade, exit_price, bar.time + M15_MS, reason, equity, config, len(trades) + 1)
                equity += closed.net_pnl_usd
                trades.append(closed)
                equity_curve.append({"time": iso_utc(closed.exit_time), "equity_usd": r2(equity)})
                open_trade = None
            elif open_trade.bars_held >= config.max_hold_bars:
                closed = _close_trade(open_trade, bar.close, bar.time + M15_MS, "TIME_STOP", equity, config, len(trades) + 1)
                equity += closed.net_pnl_usd
                trades.append(closed)
                equity_curve.append({"time": iso_utc(closed.exit_time), "equity_usd": r2(equity)})
                open_trade = None

        # Signal is calculated only when the 15m candle has closed, and cannot
        # touch same-candle OHLC because it is merely a next-open pending order.
        now = bar.time + M15_MS
        if index < WARMUP_BARS or open_trade is not None or pending is not None:
            continue
        macro_window = _closed_window(macro, h4_starts, now, H4_MS)
        strategy_window = _closed_window(hourly, h1_starts, now, H1_MS)
        trigger_window = list(base[max(0, index - WINDOW_BARS + 1) : index + 1])
        if len(macro_window) < 30 or len(strategy_window) < 30 or len(trigger_window) < WARMUP_BARS:
            continue
        evaluation = evaluate_base(macro_window, strategy_window, trigger_window, config)
        if evaluation is None:
            continue
        passed = sum(evaluation.conditions.values())
        maximum_passed = max(maximum_passed, passed)
        best_gate = max(best_gate, float(evaluation.gate["total"]))
        for condition, passed_condition in evaluation.conditions.items():
            if not passed_condition:
                blocker_counts[str(condition)] += 1
        if evaluation.action in ("BUY", "SELL"):
            pending = PendingOrder(
                signal_time=bar.time,
                action=evaluation.action,
                anchor_entry=evaluation.entry_anchor,
                anchor_stop=evaluation.stop,
                anchor_target=evaluation.target,
                atr=evaluation.atr,
                strategy_atr=evaluation.strategy_atr,
                desired_rr=evaluation.desired_rr,
                confidence=evaluation.confidence,
                trigger=evaluation.trigger,
            )
            evaluations.append({
                "signal_time": iso_utc(bar.time),
                "action": evaluation.action,
                "trigger": evaluation.trigger,
                "conditions_passed": passed,
                "book_gate_pct": evaluation.gate["total"],
                "book_quality_pct": evaluation.gate["quality"],
                "book_trigger_pct": evaluation.gate["trigger"],
                "signal_anchor": evaluation.entry_anchor,
                "signal_stop": evaluation.stop,
                "signal_target": evaluation.target,
            })

    # No mark-to-market result is presented as a completed trade. It is still recorded transparently.
    open_summary: Optional[dict[str, object]] = None
    if open_trade is not None:
        open_summary = {
            "signal_time": iso_utc(open_trade.signal_time),
            "entry_time": iso_utc(open_trade.entry_time),
            "side": open_trade.action,
            "entry": r2(open_trade.entry),
            "stop": r2(open_trade.stop),
            "target": r2(open_trade.target),
            "bars_held": open_trade.bars_held,
        }
    diagnostics: dict[str, object] = {
        "input_15m_bars": len(base),
        "complete_1h_bars": len(hourly),
        "complete_4h_bars": len(macro),
        "discarded_incomplete_1h_buckets": incomplete_h1,
        "discarded_incomplete_4h_buckets": incomplete_h4,
        "maximum_conditions_passed": maximum_passed,
        "best_book_gate_pct": r2(best_gate),
        "top_failed_conditions": blocker_counts.most_common(6),
        "open_trade_at_end": open_summary,
        "pending_signal_at_end": iso_utc(pending.signal_time) if pending else None,
    }
    return trades, diagnostics, equity_curve, evaluations


# ---------------------------------------------------------------------------
# Reports and command line
# ---------------------------------------------------------------------------


def iso_utc(timestamp_ms: int) -> str:
    return datetime.fromtimestamp(timestamp_ms / 1000.0, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def build_stats(trades: Sequence[ClosedTrade], starting_equity: float) -> dict[str, object]:
    wins = [trade for trade in trades if trade.net_pnl_usd > 0.0]
    losses = [trade for trade in trades if trade.net_pnl_usd <= 0.0]
    gross_wins = sum(trade.net_pnl_usd for trade in wins)
    gross_losses = abs(sum(trade.net_pnl_usd for trade in losses))
    net = sum(trade.net_pnl_usd for trade in trades)
    r_values = [trade.r_multiple for trade in trades]
    average_r = statistics.mean(r_values) if r_values else 0.0
    standard_deviation = statistics.stdev(r_values) if len(r_values) > 1 else 0.0
    equity, peak, maximum_drawdown = starting_equity, starting_equity, 0.0
    for trade in trades:
        equity += trade.net_pnl_usd
        peak = max(peak, equity)
        maximum_drawdown = max(maximum_drawdown, peak - equity)
    longs = [trade for trade in trades if trade.side == "BUY"]
    shorts = [trade for trade in trades if trade.side == "SELL"]
    reasons = Counter(trade.exit_reason for trade in trades)
    return {
        "closed_trades": len(trades),
        "wins": len(wins),
        "losses": len(losses),
        "win_rate_pct": r2(100.0 * len(wins) / len(trades)) if trades else 0.0,
        "net_pnl_usd": r2(net),
        "ending_equity_usd": r2(equity),
        "profit_factor": r2(gross_wins / gross_losses) if gross_losses > EPS else (99.0 if gross_wins > 0.0 else 0.0),
        "expectancy_r": r2(average_r),
        "stddev_r": r2(standard_deviation),
        "sharpe_per_trade": r2(average_r / standard_deviation) if standard_deviation > EPS else 0.0,
        "max_drawdown_usd": r2(maximum_drawdown),
        "max_drawdown_pct": r2(maximum_drawdown / peak * 100.0) if peak > EPS else 0.0,
        "average_bars_held": r2(statistics.mean([trade.bars_held for trade in trades])) if trades else 0.0,
        "best_trade_usd": r2(max((trade.net_pnl_usd for trade in trades), default=0.0)),
        "worst_trade_usd": r2(min((trade.net_pnl_usd for trade in trades), default=0.0)),
        "long_trades": len(longs),
        "short_trades": len(shorts),
        "long_win_rate_pct": r2(100.0 * sum(trade.net_pnl_usd > 0.0 for trade in longs) / len(longs)) if longs else 0.0,
        "short_win_rate_pct": r2(100.0 * sum(trade.net_pnl_usd > 0.0 for trade in shorts) / len(shorts)) if shorts else 0.0,
        "exit_reasons": dict(sorted(reasons.items())),
    }


def _write_csv(path: Path, rows: Sequence[dict[str, object]], fields: Sequence[str]) -> None:
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_reports(output: Path, config: Config, audit: CsvAudit, trades: Sequence[ClosedTrade], diagnostics: dict[str, object], equity_curve: Sequence[dict[str, object]], signals: Sequence[dict[str, object]]) -> dict[str, object]:
    output.mkdir(parents=True, exist_ok=True)
    stats = build_stats(trades, config.starting_equity)
    trade_rows = []
    for trade in trades:
        row = asdict(trade)
        row["signal_time"] = iso_utc(trade.signal_time)
        row["entry_time"] = iso_utc(trade.entry_time)
        row["exit_time"] = iso_utc(trade.exit_time)
        trade_rows.append(row)
    trade_fields = [field.name for field in ClosedTrade.__dataclass_fields__.values()]
    _write_csv(output / "trades.csv", trade_rows, trade_fields)
    signal_fields = [
        "signal_time", "action", "trigger", "conditions_passed", "book_gate_pct", "book_quality_pct", "book_trigger_pct", "signal_anchor", "signal_stop", "signal_target",
    ]
    _write_csv(output / "signals.csv", list(signals), signal_fields)
    _write_csv(output / "equity_curve.csv", list(equity_curve), ("time", "equity_usd"))
    report = {
        "tool": "BASE_ICHIMOKU four-school CSV simulator",
        "reference_commit": "f1c9850ae290bbbc5e953c8fdf453c05754928cd",
        "mode": {
            "base_model": "BASE_ICHIMOKU only; Patel, Tuttle, Hosoda, Peloille",
            "hidden_cross": False,
            "parsa": False,
            "btc_52": False,
            "peyman": False,
            "neowave": False,
            "input_timeframe": "15m",
            "constructed_timeframes": ["1H", "4H"],
            "decision": "closed candles only; incomplete 1H/4H buckets are never visible",
            "fill": "market at next 15m candle open, rebased with TradePlanGuard corridor",
            "intrabar_ambiguity": "SL-first whenever SL and TP are both touched",
        },
        "config": asdict(config),
        "csv_audit": asdict(audit),
        "diagnostics": diagnostics,
        "stats": stats,
        "limitations": [
            "Historical simulation only. It sends no real exchange or broker order.",
            "No tick path or slippage model: bracket fills use their stop/target prices.",
            "A price gap at next open can reject a stale signal plan rather than pretending an old plan filled.",
            "Parity with a GoCharting export must be measured against that exact CSV; this report does not claim future profitability.",
        ],
    }
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def _print_validation(audit: CsvAudit, bars: Sequence[Candle], h1_incomplete: int, h4_incomplete: int) -> None:
    print("CSV validation passed")
    print(f"  accepted 15m candles: {len(bars):,}")
    print(f"  range: {iso_utc(bars[0].time)} → {iso_utc(bars[-1].time)}")
    print(f"  invalid rows skipped: {audit.skipped_invalid:,}; duplicates skipped: {audit.skipped_duplicate:,}")
    print(f"  15m gaps: {audit.gaps:,}; incomplete 1H buckets: {h1_incomplete:,}; incomplete 4H buckets: {h4_incomplete:,}")


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Candle-close-only four-school BASE_ICHIMOKU simulator for 15m GoCharting CSV.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("csv", type=Path, help="15-minute GoCharting OHLCV CSV export")
    parser.add_argument("--output", type=Path, default=Path("base_ichimoku_report"), help="report directory")
    parser.add_argument("--symbol", default="XAUUSDT", help="symbol used for reference VenueSpecs cost model")
    parser.add_argument("--start-equity", type=float, default=1000.0, help="starting USD equity")
    parser.add_argument("--risk-percent", type=float, default=0.5, help="risk per trade, clamped to 0.05..5.0")
    parser.add_argument("--cost-multiplier", type=float, default=1.0, help="multiply modeled commission/spread costs")
    parser.add_argument("--max-hold-bars", type=int, default=20, help="time-stop in 15m candles")
    parser.add_argument("--drop-zero-volume", action="store_true", help="drop zero-volume candles when a volume column exists")
    parser.add_argument("--allow-gaps", action="store_true", help="continue after 15m gaps (incomplete 1H/4H buckets are discarded)")
    parser.add_argument("--validate-only", action="store_true", help="validate CSV and timeframe reconstruction without a backtest")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        bars, audit = read_15m_csv(args.csv, drop_zero_volume=args.drop_zero_volume)
        h1, incomplete_h1 = resample_complete(bars, H1_MS)
        h4, incomplete_h4 = resample_complete(bars, H4_MS)
        _print_validation(audit, bars, incomplete_h1, incomplete_h4)
        if audit.gaps and not args.allow_gaps:
            raise CsvValidationError("15m gaps detected. Re-export contiguous data or pass --allow-gaps to discard incomplete higher-TF buckets.")
        if args.validate_only:
            return 0
        config = Config(
            symbol=args.symbol,
            starting_equity=args.start_equity,
            risk_percent=args.risk_percent,
            cost_multiplier=args.cost_multiplier,
            max_hold_bars=max(1, args.max_hold_bars),
            allow_gaps=args.allow_gaps,
        )
        trades, diagnostics, equity_curve, signals = run_backtest(bars, config)
        report = write_reports(args.output, config, audit, trades, diagnostics, equity_curve, signals)
        stats = report["stats"]
        print(f"\nBacktest complete: {stats['closed_trades']} closed trade(s), net P&L ${stats['net_pnl_usd']:.2f}, ending equity ${stats['ending_equity_usd']:.2f}")
        print(f"Reports: {args.output / 'report.json'}, {args.output / 'trades.csv'}, {args.output / 'equity_curve.csv'}")
        return 0
    except (OSError, CsvValidationError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
