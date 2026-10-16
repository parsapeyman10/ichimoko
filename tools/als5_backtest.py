#!/usr/bin/env python3
"""ALS-5 — Adaptive Liquidity & Structure Gold (XAU/USD) Backtesting & Validation Engine.

Implements:
- Non-repainting confirmed pivot extraction (leftBars, rightBars)
- M5 -> M15 resampling (3x M5 = 1 M15) without look-ahead bias
- 4-State Adaptive Market-Regime Classifier (BULLISH_TREND, BEARISH_TREND, RANGE, UNCERTAIN_HIGH_VOL)
- Engine A (Trend Continuation), Engine B (Liquidity Sweep & Reversal), Engine C (Range Mean-Reversion)
- Pre-confirmation Opportunity Monitor (WAIT -> BUY / SELL / EXPIRED / INVALIDATED)
- 0–10 Quality Scoring across 5 categories + hard requirement overrides
- Stage A (Individual Engine Baselines), Stage B (Combined System), Stage C (Ablation Tests),
  Stage D (Chronological Dev / Val / Out-of-Sample Split), Stage E (Parameter, Session & Cost Robustness)
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
import json
import math
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class Bar:
    time_ms: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 100.0


@dataclass(frozen=True)
class Als5Params:
    pivot_left: int = 3
    pivot_right: int = 3
    atr_period: int = 14
    adx_period: int = 14
    ema_period: int = 50
    trend_adx_min: float = 18.0
    range_adx_max: float = 20.0
    high_vol_atr_ratio: float = 2.2
    low_vol_atr_ratio: float = 0.55
    min_impulse_atr: float = 1.1
    min_pullback_fib: float = 0.28
    max_pullback_fib: float = 0.85
    min_swing_progress_atr: float = 0.05
    max_chase_atr: float = 1.25
    pullback_zone_atr_tolerance: float = 0.60
    min_rejection_wick_ratio: float = 0.40
    micro_lookback_bars: int = 3
    min_sweep_atr: float = 0.12
    max_sweep_atr: float = 1.35
    mss_window_bars: int = 6
    min_disp_ratio: float = 0.40
    min_range_width_atr: float = 1.8
    max_range_width_atr: float = 6.0
    range_zone_frac: float = 0.22
    range_mid_low_frac: float = 0.25
    range_mid_high_frac: float = 0.75
    range_breakout_atr_buffer: float = 0.40
    range_entry_wick_atr: float = 0.15
    range_target_atr_buffer: float = 0.15
    max_spread_atr_ratio: float = 0.25
    score_threshold_10: int = 8
    risk_per_trade_pct: float = 0.25
    min_rr: float = 1.5
    target_rr: float = 1.8
    max_daily_loss_pct: float = 1.0
    max_consec_losses: int = 3
    stop_buffer_atr: float = 0.25
    min_stop_atr: float = 0.35
    max_stop_atr: float = 3.5
    initial_equity: float = 10_000.0
    contract_size_oz: float = 100.0
    min_lot: float = 0.01
    lot_step: float = 0.01
    spread_usd_per_oz: float = 0.16
    slippage_usd_per_oz: float = 0.05
    commission_per_lot_side_usd: float = 3.50
    allow_asian: bool = False
    asian_start_hour_utc: int = 0
    asian_end_hour_utc: int = 7
    allow_london: bool = True
    london_start_hour_local: int = 7
    london_end_hour_local: int = 13
    allow_ny: bool = True
    new_york_start_hour_local: int = 8
    new_york_end_hour_local: int = 16
    news_timestamps_ms: Tuple[int, ...] = ()
    news_blackout_minutes: int = 30
    enable_engine_a: bool = True
    enable_engine_b: bool = True
    enable_engine_c: bool = True
    use_regime_filter: bool = True
    use_volatility_filter: bool = True
    exit_model: str = "STRUCTURAL_LEVEL"


@dataclass
class TradeRecord:
    entry_bar: int
    exit_bar: int
    entry_time_ms: int
    exit_time_ms: int
    side: str  # BUY | SELL
    engine: str
    regime: str
    session: str
    entry_price: float
    stop_loss: float
    take_profit: float
    exit_price: float
    lots: float
    ounces: float
    gross_pnl_usd: float
    cost_usd: float
    net_pnl_usd: float
    r_multiple: float
    score_10: int


@dataclass
class OpportunityAudit:
    candidates_detected: int = 0
    wait_states_logged: int = 0
    candidates_expired: int = 0
    candidates_invalidated: int = 0
    confirmed_entries: int = 0
    rejected_by_score: int = 0
    rejected_by_regime: int = 0
    rejected_by_session: int = 0
    rejected_by_event: int = 0
    rejected_by_daily_lock: int = 0
    rejected_by_rr: int = 0
    rejected_by_spread: int = 0
    rejected_by_min_size: int = 0
    rejected_by_duplicate: int = 0
    counterfactual_waits_that_hit_target: int = 0
    counterfactual_rejections_that_hit_target: int = 0
    _candidate_keys: set[str] = field(default_factory=set, repr=False)
    _wait_keys: set[str] = field(default_factory=set, repr=False)

    def record_candidate(self, key: str, wait: bool = False) -> None:
        if key not in self._candidate_keys:
            self._candidate_keys.add(key)
            self.candidates_detected += 1
        if wait and key not in self._wait_keys:
            self._wait_keys.add(key)
            self.wait_states_logged += 1

    def summary(self) -> Dict[str, int]:
        return {
            key: value for key, value in vars(self).items()
            if not key.startswith("_")
        }


def resample_m5_to_m15(m5_bars: Sequence[Bar]) -> List[Bar]:
    m15: List[Bar] = []
    n = len(m5_bars) // 3
    for i in range(n):
        chunk = m5_bars[i * 3 : i * 3 + 3]
        m15.append(
            Bar(
                time_ms=chunk[0].time_ms,
                open=chunk[0].open,
                high=max(b.high for b in chunk),
                low=min(b.low for b in chunk),
                close=chunk[-1].close,
                volume=sum(b.volume for b in chunk),
            )
        )
    return m15


def compute_atr(bars: Sequence[Bar], period: int = 14) -> List[float]:
    if not bars:
        return []
    trs: List[float] = []
    for i, b in enumerate(bars):
        if i == 0:
            trs.append(b.high - b.low)
        else:
            prev_c = bars[i - 1].close
            trs.append(max(b.high - b.low, abs(b.high - prev_c), abs(b.low - prev_c)))
    out: List[float] = []
    running = 0.0
    for i, tr in enumerate(trs):
        if i < period:
            running += tr
            out.append(running / (i + 1))
        else:
            running = (out[-1] * (period - 1) + tr) / period
            out.append(running)
    return out


def compute_ema(values: Sequence[float], period: int = 50) -> List[float]:
    if not values:
        return []
    k = 2.0 / (period + 1.0)
    out = [values[0]]
    for v in values[1:]:
        out.append(v * k + out[-1] * (1.0 - k))
    return out


def compute_adx(bars: Sequence[Bar], period: int = 14) -> List[float]:
    if len(bars) < 2:
        return [15.0 for _ in bars]
    plus_dm = [0.0]
    minus_dm = [0.0]
    tr = [bars[0].high - bars[0].low]
    for i in range(1, len(bars)):
        up = bars[i].high - bars[i - 1].high
        down = bars[i - 1].low - bars[i].low
        plus_dm.append(up if (up > down and up > 0) else 0.0)
        minus_dm.append(down if (down > up and down > 0) else 0.0)
        prev_c = bars[i - 1].close
        tr.append(max(bars[i].high - bars[i].low, abs(bars[i].high - prev_c), abs(bars[i].low - prev_c)))
    atr = compute_ema(tr, period)
    p_di = compute_ema(plus_dm, period)
    m_di = compute_ema(minus_dm, period)
    dx: List[float] = []
    for a, p, m in zip(atr, p_di, m_di):
        if a <= 1e-9:
            dx.append(0.0)
        else:
            p_pct = 100.0 * p / a
            m_pct = 100.0 * m / a
            denom = p_pct + m_pct
            dx.append(100.0 * abs(p_pct - m_pct) / denom if denom > 1e-9 else 0.0)
    return compute_ema(dx, period)


def confirmed_swings(
    bars: Sequence[Bar], left: int, right: int, up_to_index: int
) -> Tuple[List[Tuple[int, float]], List[Tuple[int, float]]]:
    """Returns (swing_highs, swing_lows) confirmed on or before up_to_index.
    A pivot at p is only confirmed once p + right <= up_to_index (zero look-ahead).
    """
    highs: List[Tuple[int, float]] = []
    lows: List[Tuple[int, float]] = []
    max_p = min(up_to_index, len(bars) - 1) - right
    for p in range(left, max_p + 1):
        hi = bars[p].high
        lo = bars[p].low
        is_hi = True
        is_lo = True
        for k in range(p - left, p + right + 1):
            if k == p:
                continue
            if bars[k].high > hi or (k < p and bars[k].high >= hi):
                is_hi = False
            if bars[k].low < lo or (k < p and bars[k].low <= lo):
                is_lo = False
        if is_hi:
            highs.append((p, hi))
        if is_lo:
            lows.append((p, lo))
    return highs, lows


def session_of(time_ms: int, params: Als5Params = Als5Params()) -> str:
    """Return a DST-aware local trading session label; NY takes priority in overlap."""
    instant = datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc)
    london_hour = instant.astimezone(ZoneInfo("Europe/London")).hour
    new_york_hour = instant.astimezone(ZoneInfo("America/New_York")).hour
    utc_hour = instant.hour
    if params.allow_ny and params.new_york_start_hour_local <= new_york_hour < params.new_york_end_hour_local:
        return "NEW_YORK"
    if params.allow_london and params.london_start_hour_local <= london_hour < params.london_end_hour_local:
        return "LONDON"
    if params.allow_asian and params.asian_start_hour_utc <= utc_hour < params.asian_end_hour_utc:
        return "ASIAN"
    return "OFF_SESSION"


def session_allowed(time_ms: int, params: Als5Params) -> bool:
    instant = datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc)
    london_hour = instant.astimezone(ZoneInfo("Europe/London")).hour
    new_york_hour = instant.astimezone(ZoneInfo("America/New_York")).hour
    return (
        (params.allow_london and params.london_start_hour_local <= london_hour < params.london_end_hour_local)
        or (params.allow_ny and params.new_york_start_hour_local <= new_york_hour < params.new_york_end_hour_local)
        or (params.allow_asian and params.asian_start_hour_utc <= instant.hour < params.asian_end_hour_utc)
    )


def gold_market_open(time_ms: int) -> bool:
    """Conservative CME/OTC gold schedule in DST-aware New York time, including daily break."""
    local = datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc).astimezone(ZoneInfo("America/New_York"))
    weekday = local.weekday()  # Monday=0 .. Sunday=6
    hour = local.hour
    if weekday == 5:  # Saturday
        return False
    if weekday == 4 and hour >= 17:  # Friday from 17:00 NY
        return False
    if weekday == 6 and hour < 18:  # Sunday until 18:00 NY
        return False
    if weekday in {0, 1, 2, 3} and hour >= 17:  # Daily maintenance break 17:00–18:00 NY
        return False
    return True


def in_news_blackout(time_ms: int, params: Als5Params) -> bool:
    window_ms = max(0, params.news_blackout_minutes) * 60_000
    return any(abs(time_ms - event_ms) <= window_ms for event_ms in params.news_timestamps_ms)


def calculate_position_size(entry: float, stop: float, equity: float, params: Als5Params) -> Tuple[float, float]:
    stop_dist = abs(entry - stop)
    rt_cost_per_oz = (
        params.spread_usd_per_oz
        + params.slippage_usd_per_oz
        + (2.0 * params.commission_per_lot_side_usd / params.contract_size_oz)
    )
    eff_loss = stop_dist + rt_cost_per_oz
    if eff_loss <= 1e-9:
        return 0.0, 0.0
    risk_usd = equity * (params.risk_per_trade_pct / 100.0)
    raw_oz = risk_usd / eff_loss
    raw_lots = raw_oz / params.contract_size_oz
    if raw_lots < params.min_lot:
        return 0.0, 0.0
    stepped_lots = math.floor(raw_lots / params.lot_step + 1e-9) * params.lot_step
    if stepped_lots < params.min_lot:
        return 0.0, 0.0
    return stepped_lots, stepped_lots * params.contract_size_oz


def net_reward_risk(entry: float, stop: float, target: float, params: Als5Params) -> float:
    cost = params.spread_usd_per_oz + params.slippage_usd_per_oz + (
        2.0 * params.commission_per_lot_side_usd / params.contract_size_oz
    )
    risk = abs(entry - stop) + cost
    reward = max(0.0, abs(target - entry) - cost)
    return reward / risk if risk > 1e-9 else 0.0


def select_target(side: str, entry: float, stop: float, structural: Optional[float], params: Als5Params,
                  *, require_structural: bool = False) -> float:
    distance = abs(entry - stop)
    fixed = entry + params.target_rr * distance if side == "BUY" else entry - params.target_rr * distance
    if params.exit_model in {"FIXED_RR", "TRAIL_AFTER_1R"} or structural is None:
        return fixed
    feasible = (structural >= entry + params.min_rr * distance if side == "BUY"
                else structural <= entry - params.min_rr * distance)
    if feasible or require_structural:
        return structural
    return fixed


def counterfactual_outcome(
    bars: Sequence[Bar], start_index: int, side: str, stop: float, target: float, horizon: int
) -> str:
    """Audit-only future-path label for a previously recorded candidate; never feeds entries."""
    for future in bars[start_index + 1 : start_index + 1 + horizon]:
        if side == "BUY":
            if future.low <= stop:
                return "STOP_FIRST"
            if future.high >= target:
                return "TARGET_FIRST"
        else:
            if future.high >= stop:
                return "STOP_FIRST"
            if future.low <= target:
                return "TARGET_FIRST"
    return "UNRESOLVED"


def run_backtest(
    m5_bars: Sequence[Bar],
    params: Als5Params = Als5Params(),
    trade_start_index: int = 0,
    trade_end_index: Optional[int] = None,
) -> Tuple[List[TradeRecord], OpportunityAudit]:
    """Run one chronological segment while using earlier bars only as warm-up context.

    `trade_end_index` is exclusive. No entry is allowed before `trade_start_index`; this lets
    validation/OOS runs retain past indicator context without reusing past trade PnL.
    """
    audit = OpportunityAudit()
    end_index = len(m5_bars) if trade_end_index is None else min(len(m5_bars), trade_end_index)
    start_index = max(0, trade_start_index)
    if len(m5_bars) < 60 or end_index <= start_index:
        return [], audit

    atr5_series = compute_atr(m5_bars, params.atr_period)
    adx5_series = compute_adx(m5_bars, params.adx_period)
    ema5_series = compute_ema([b.close for b in m5_bars], params.ema_period)

    m15_bars = resample_m5_to_m15(m5_bars)
    atr15_series = compute_atr(m15_bars, params.atr_period)
    adx15_series = compute_adx(m15_bars, params.adx_period)
    ema15_series = compute_ema([b.close for b in m15_bars], params.ema_period)

    equity = params.initial_equity
    day_start_equity = equity
    current_day = -1
    consec_losses = 0
    last_traded_key = ""
    trades: List[TradeRecord] = []
    open_trade: Optional[Dict[str, object]] = None

    rt_cost_per_oz = (
        params.spread_usd_per_oz
        + params.slippage_usd_per_oz
        + (2.0 * params.commission_per_lot_side_usd / params.contract_size_oz)
    )

    for idx in range(48, end_index):
        bar = m5_bars[idx]
        bar_day = bar.time_ms // 86_400_000
        if bar_day != current_day:
            current_day = bar_day
            day_start_equity = equity

        # 1. Settle open position if stop or target hit on current bar
        if open_trade is not None:
            side = str(open_trade["side"])
            stop = float(open_trade["stop"])
            tp = float(open_trade["tp"])
            entry = float(open_trade["entry"])
            ounces = float(open_trade["ounces"])
            lots = float(open_trade["lots"])
            init_risk_usd = float(open_trade["init_risk_usd"])

            exit_price: Optional[float] = None
            if side == "BUY":
                if bar.low <= stop:
                    # Adverse gap-through is filled at the worse open, not at an impossible stop price.
                    exit_price = min(stop, bar.open)
                elif bar.high >= tp:
                    exit_price = tp
                elif params.exit_model == "TRAIL_AFTER_1R" and bar.high >= entry + (entry - stop):
                    open_trade["stop"] = entry
            else:
                if bar.high >= stop:
                    exit_price = max(stop, bar.open)
                elif bar.low <= tp:
                    exit_price = tp
                elif params.exit_model == "TRAIL_AFTER_1R" and bar.low <= entry - (stop - entry):
                    open_trade["stop"] = entry

            # Close any remaining position at the end of a test segment; never omit an open loss.
            if exit_price is None and idx == end_index - 1:
                exit_price = bar.close

            if exit_price is not None:
                gross_per_oz = (exit_price - entry) if side == "BUY" else (entry - exit_price)
                gross_usd = gross_per_oz * ounces
                cost_usd = rt_cost_per_oz * ounces
                net_usd = gross_usd - cost_usd
                r_mult = net_usd / init_risk_usd if init_risk_usd > 0 else 0.0
                equity += net_usd
                consec_losses = consec_losses + 1 if net_usd < 0 else 0
                trades.append(
                    TradeRecord(
                        entry_bar=int(open_trade["entry_bar"]),
                        exit_bar=idx,
                        entry_time_ms=int(open_trade["entry_time_ms"]),
                        exit_time_ms=bar.time_ms,
                        side=side,
                        engine=str(open_trade["engine"]),
                        regime=str(open_trade["regime"]),
                        session=str(open_trade["session"]),
                        entry_price=entry,
                        stop_loss=stop,
                        take_profit=tp,
                        exit_price=exit_price,
                        lots=lots,
                        ounces=ounces,
                        gross_pnl_usd=gross_usd,
                        cost_usd=cost_usd,
                        net_pnl_usd=net_usd,
                        r_multiple=r_mult,
                        score_10=int(open_trade["score_10"]),
                    )
                )
                open_trade = None
            continue

        if idx < start_index:
            continue

        # 2. Classify regime from confirmed M15 bars up to idx // 3 - 1 (no look-ahead)
        m15_idx = (idx // 3) - 1
        if m15_idx < 10:
            continue
        atr5 = max(atr5_series[idx], 1e-6)
        recent_atrs = sorted(atr5_series[max(0, idx - 60) : idx + 1])
        med_atr5 = recent_atrs[len(recent_atrs) // 2] if recent_atrs else atr5
        atr_ratio = atr5 / med_atr5 if med_atr5 > 0 else 1.0

        m15_close = m15_bars[m15_idx].close
        m15_ema = ema15_series[m15_idx]
        m15_adx = adx15_series[m15_idx]
        hi15, lo15 = confirmed_swings(m15_bars, params.pivot_left, params.pivot_right, m15_idx)

        hh15 = len(hi15) >= 2 and hi15[-1][1] > hi15[-2][1]
        hl15 = len(lo15) >= 2 and lo15[-1][1] > lo15[-2][1]
        lh15 = len(hi15) >= 2 and hi15[-1][1] < hi15[-2][1]
        ll15 = len(lo15) >= 2 and lo15[-1][1] < lo15[-2][1]
        bos_up15 = len(hi15) >= 1 and m15_close > hi15[-1][1]
        bos_dn15 = len(lo15) >= 1 and m15_close < lo15[-1][1]

        # Range edges use confirmed M5 pivots where available; the fallback rolling extrema
        # are from already-closed bars only and never include the forming/future bar.
        hi5, lo5 = confirmed_swings(m5_bars, params.pivot_left, params.pivot_right, idx)
        recent_hi = [price for p, price in hi5 if idx - p <= 48][-3:]
        recent_lo = [price for p, price in lo5 if idx - p <= 48][-3:]
        r_high = max(recent_hi) if len(recent_hi) >= 2 else max(b.high for b in m5_bars[idx - 36 : idx])
        r_low = min(recent_lo) if len(recent_lo) >= 2 else min(b.low for b in m5_bars[idx - 36 : idx])
        r_width_atr = (r_high - r_low) / atr5

        if params.use_volatility_filter and (
            atr_ratio > params.high_vol_atr_ratio or atr_ratio < params.low_vol_atr_ratio
        ):
            regime = "UNCERTAIN_HIGH_VOL"
        elif ((hh15 and hl15) or bos_up15) and m15_close >= m15_ema and m15_adx >= params.trend_adx_min:
            regime = "BULLISH_TREND"
        elif ((lh15 and ll15) or bos_dn15) and m15_close <= m15_ema and m15_adx >= params.trend_adx_min:
            regime = "BEARISH_TREND"
        elif params.min_range_width_atr <= r_width_atr <= params.max_range_width_atr and m15_adx <= params.range_adx_max + 4.0:
            regime = "RANGE"
        elif (hh15 and hl15 or bos_up15) and m15_close >= m15_ema:
            regime = "BULLISH_TREND"
        elif (lh15 and ll15 or bos_dn15) and m15_close <= m15_ema:
            regime = "BEARISH_TREND"
        else:
            regime = "UNCERTAIN_HIGH_VOL"

        if len(hi5) < 2 or len(lo5) < 2:
            continue

        sess = session_of(bar.time_ms, params)
        sess_ok = session_allowed(bar.time_ms, params)
        market_open = gold_market_open(bar.time_ms)
        news_blocked = in_news_blackout(bar.time_ms, params)
        daily_loss_pct = max(0.0, (day_start_equity - equity) / day_start_equity * 100.0)
        daily_locked = daily_loss_pct >= params.max_daily_loss_pct or consec_losses >= params.max_consec_losses
        spread_atr_ratio = params.spread_usd_per_oz / atr5
        execution_ok = sess_ok and market_open and not daily_locked and not news_blocked
        exec_score = 2 if (execution_ok and spread_atr_ratio <= 0.12) else (
            1 if execution_ok and spread_atr_ratio <= params.max_spread_atr_ratio else 0
        )

        prev_bar = m5_bars[idx - 1]
        bar_range = max(bar.high - bar.low, 1e-6)
        disp_ratio = abs(bar.close - bar.open) / bar_range

        candidate: Optional[Tuple[str, str, str, float, float, float, int, str]] = None
        # Tuple: (engine, state, side, entry, stop, tp, score_10, setup_key)

        # --- Engine A: Trend Continuation ---
        if params.enable_engine_a:
            if (not params.use_regime_filter or regime == "BULLISH_TREND") and bar.close > ema5_series[idx]:
                previous_high = hi5[-2][1]
                latest_high = hi5[-1][1]
                prot_low = lo5[-1][1]
                prior_low = lo5[-2][1]
                bos_confirmed = latest_high >= previous_high + params.min_swing_progress_atr * atr5
                hl_preserved = prot_low >= prior_low + params.min_swing_progress_atr * atr5 and bar.low > prot_low
                imp_top = max(latest_high, max(b.high for b in m5_bars[idx - 10 : idx]))
                imp_leg = imp_top - prot_low
                pull_low = min(b.low for b in m5_bars[idx - 5 : idx + 1])
                retrace = (imp_top - pull_low) / imp_leg if imp_leg > 0 else 0.0
                near_broken = abs(pull_low - previous_high) <= params.pullback_zone_atr_tolerance * atr5
                in_zone = params.min_pullback_fib <= retrace <= params.max_pullback_fib or near_broken
                if bos_confirmed and hl_preserved and imp_leg >= params.min_impulse_atr * atr5 and in_zone:
                    micro_level = max(b.high for b in m5_bars[max(0, idx - 4) : idx])
                    lower_wick = min(bar.open, bar.close) - bar.low
                    rejection = bar.close > bar.open and (disp_ratio >= params.min_disp_ratio or lower_wick / bar_range >= params.min_rejection_wick_ratio)
                    confirmed = rejection and bar.close > micro_level and bar.close - micro_level <= params.max_chase_atr * atr5
                    stop = min(pull_low, prot_low) - params.stop_buffer_atr * atr5
                    stop_dist = bar.close - stop
                    if params.min_stop_atr * atr5 <= stop_dist <= params.max_stop_atr * atr5:
                        structural = latest_high if latest_high >= bar.close + params.min_rr * stop_dist else None
                        tp = select_target("BUY", bar.close, stop, structural, params)
                        gross_rr = (tp - bar.close) / stop_dist
                        net_rr = net_reward_risk(bar.close, stop, tp, params)
                        rr_points = 2 if net_rr >= 2.0 else 1 if net_rr >= params.min_rr else 0
                        score = 2 + (2 if near_broken else 1) + (2 if confirmed else 1) + rr_points + exec_score
                        state = "BUY" if confirmed and gross_rr >= params.min_rr and net_rr >= params.min_rr else "WAIT"
                        candidate = ("ENGINE_A_TREND", state, "BUY", bar.close, stop, tp, score, f"A_BUY_{lo5[-1][0]}")

            elif (not params.use_regime_filter or regime == "BEARISH_TREND") and bar.close < ema5_series[idx]:
                previous_low = lo5[-2][1]
                latest_low = lo5[-1][1]
                prot_high = hi5[-1][1]
                prior_high = hi5[-2][1]
                bos_confirmed = latest_low <= previous_low - params.min_swing_progress_atr * atr5
                lh_preserved = prot_high <= prior_high - params.min_swing_progress_atr * atr5 and bar.high < prot_high
                imp_bot = min(latest_low, min(b.low for b in m5_bars[idx - 10 : idx]))
                imp_leg = prot_high - imp_bot
                pull_high = max(b.high for b in m5_bars[idx - 5 : idx + 1])
                retrace = (pull_high - imp_bot) / imp_leg if imp_leg > 0 else 0.0
                near_broken = abs(pull_high - previous_low) <= params.pullback_zone_atr_tolerance * atr5
                in_zone = params.min_pullback_fib <= retrace <= params.max_pullback_fib or near_broken
                if bos_confirmed and lh_preserved and imp_leg >= params.min_impulse_atr * atr5 and in_zone:
                    micro_level = min(b.low for b in m5_bars[max(0, idx - 4) : idx])
                    upper_wick = bar.high - max(bar.open, bar.close)
                    rejection = bar.close < bar.open and (disp_ratio >= params.min_disp_ratio or upper_wick / bar_range >= params.min_rejection_wick_ratio)
                    confirmed = rejection and bar.close < micro_level and micro_level - bar.close <= params.max_chase_atr * atr5
                    stop = max(pull_high, prot_high) + params.stop_buffer_atr * atr5
                    stop_dist = stop - bar.close
                    if params.min_stop_atr * atr5 <= stop_dist <= params.max_stop_atr * atr5:
                        structural = latest_low if latest_low <= bar.close - params.min_rr * stop_dist else None
                        tp = select_target("SELL", bar.close, stop, structural, params)
                        gross_rr = (bar.close - tp) / stop_dist
                        net_rr = net_reward_risk(bar.close, stop, tp, params)
                        rr_points = 2 if net_rr >= 2.0 else 1 if net_rr >= params.min_rr else 0
                        score = 2 + (2 if near_broken else 1) + (2 if confirmed else 1) + rr_points + exec_score
                        state = "SELL" if confirmed and gross_rr >= params.min_rr and net_rr >= params.min_rr else "WAIT"
                        candidate = ("ENGINE_A_TREND", state, "SELL", bar.close, stop, tp, score, f"A_SELL_{hi5[-1][0]}")

        # --- Engine B: Liquidity Sweep & Reversal ---
        if candidate is None and params.enable_engine_b:
            prior_hi5, prior_lo5 = confirmed_swings(
                m5_bars, params.pivot_left, params.pivot_right,
                max(0, idx - params.mss_window_bars - 1),
            )
            swept_low = prior_lo5[-1][1] if prior_lo5 else None
            latest_sweep: Optional[Tuple[int, float]] = None
            if swept_low is not None and regime != "BEARISH_TREND":
                for s_idx in range(max(10, idx - params.mss_window_bars), idx + 1):
                    depth = (swept_low - m5_bars[s_idx].low) / atr5
                    if params.min_sweep_atr <= depth <= params.max_sweep_atr and m5_bars[s_idx].close > swept_low:
                        latest_sweep = (s_idx, m5_bars[s_idx].low)
            if latest_sweep is not None:
                s_idx, sweep_low = latest_sweep
                micro_before = m5_bars[max(0, s_idx - params.micro_lookback_bars) : s_idx]
                if micro_before:
                    micro_high = max(b.high for b in micro_before)
                    valid_sweep = bar.low >= sweep_low and bar.close > swept_low and idx > s_idx
                    confirmed = valid_sweep and bar.close > micro_high and bar.close > bar.open and disp_ratio >= params.min_disp_ratio
                    stop = sweep_low - params.stop_buffer_atr * atr5
                    stop_dist = bar.close - stop
                    if params.min_stop_atr * atr5 <= stop_dist <= params.max_stop_atr * atr5:
                        opposing = prior_hi5[-1][1] if prior_hi5 else None
                        tp = select_target("BUY", bar.close, stop, opposing, params)
                        gross_rr = (tp - bar.close) / stop_dist
                        net_rr = net_reward_risk(bar.close, stop, tp, params)
                        rr_points = 2 if net_rr >= 2.0 else 1 if net_rr >= params.min_rr else 0
                        score = 2 + 2 + (2 if confirmed else 1) + rr_points + exec_score
                        state = "BUY" if confirmed and gross_rr >= params.min_rr and net_rr >= params.min_rr else "WAIT"
                        candidate = ("ENGINE_B_SWEEP", state, "BUY", bar.close, stop, tp, score, f"B_BUY_{s_idx}")

            if candidate is None:
                swept_high = prior_hi5[-1][1] if prior_hi5 else None
                latest_sweep = None
                if swept_high is not None and regime != "BULLISH_TREND":
                    for s_idx in range(max(10, idx - params.mss_window_bars), idx + 1):
                        depth = (m5_bars[s_idx].high - swept_high) / atr5
                        if params.min_sweep_atr <= depth <= params.max_sweep_atr and m5_bars[s_idx].close < swept_high:
                            latest_sweep = (s_idx, m5_bars[s_idx].high)
                if latest_sweep is not None:
                    s_idx, sweep_high = latest_sweep
                    micro_before = m5_bars[max(0, s_idx - params.micro_lookback_bars) : s_idx]
                    if micro_before:
                        micro_low = min(b.low for b in micro_before)
                        valid_sweep = bar.high <= sweep_high and bar.close < swept_high and idx > s_idx
                        confirmed = valid_sweep and bar.close < micro_low and bar.close < bar.open and disp_ratio >= params.min_disp_ratio
                        stop = sweep_high + params.stop_buffer_atr * atr5
                        stop_dist = stop - bar.close
                        if params.min_stop_atr * atr5 <= stop_dist <= params.max_stop_atr * atr5:
                            opposing = prior_lo5[-1][1] if prior_lo5 else None
                            tp = select_target("SELL", bar.close, stop, opposing, params)
                            gross_rr = (bar.close - tp) / stop_dist
                            net_rr = net_reward_risk(bar.close, stop, tp, params)
                            rr_points = 2 if net_rr >= 2.0 else 1 if net_rr >= params.min_rr else 0
                            score = 2 + 2 + (2 if confirmed else 1) + rr_points + exec_score
                            state = "SELL" if confirmed and gross_rr >= params.min_rr and net_rr >= params.min_rr else "WAIT"
                            candidate = ("ENGINE_B_SWEEP", state, "SELL", bar.close, stop, tp, score, f"B_SELL_{s_idx}")

        # --- Engine C: Range & Mean Reversion ---
        if candidate is None and params.enable_engine_c and (not params.use_regime_filter or regime == "RANGE"):
            if params.min_range_width_atr <= r_width_atr <= params.max_range_width_atr and r_high > r_low:
                loc = (bar.close - r_low) / (r_high - r_low)
                in_middle = params.range_mid_low_frac <= loc <= params.range_mid_high_frac
                near_low = not in_middle and (loc <= params.range_zone_frac or
                    (bar.low <= r_low + params.range_entry_wick_atr * atr5 and bar.close > r_low))
                near_high = not in_middle and (loc >= 1.0 - params.range_zone_frac or
                    (bar.high >= r_high - params.range_entry_wick_atr * atr5 and bar.close < r_high))
                if near_low:
                    confirmed = bar.close > bar.open and bar.close > prev_bar.high
                    stop = min(r_low, bar.low) - params.stop_buffer_atr * atr5
                    stop_dist = bar.close - stop
                    if params.min_stop_atr * atr5 <= stop_dist <= params.max_stop_atr * atr5:
                        structural_target = r_high - params.range_target_atr_buffer * atr5
                        tp = select_target("BUY", bar.close, stop, structural_target, params, require_structural=True)
                        gross_rr = (tp - bar.close) / stop_dist
                        net_rr = net_reward_risk(bar.close, stop, tp, params)
                        rr_points = 2 if net_rr >= 2.0 else 1 if net_rr >= params.min_rr else 0
                        score = 2 + 2 + (2 if confirmed else 0) + rr_points + exec_score
                        state = "BUY" if confirmed and gross_rr >= params.min_rr and net_rr >= params.min_rr else "WAIT"
                        candidate = ("ENGINE_C_RANGE", state, "BUY", bar.close, stop, tp, score, f"C_BUY_{int(round(r_low * 10))}")
                elif near_high:
                    confirmed = bar.close < bar.open and bar.close < prev_bar.low
                    stop = max(r_high, bar.high) + params.stop_buffer_atr * atr5
                    stop_dist = stop - bar.close
                    if params.min_stop_atr * atr5 <= stop_dist <= params.max_stop_atr * atr5:
                        structural_target = r_low + params.range_target_atr_buffer * atr5
                        tp = select_target("SELL", bar.close, stop, structural_target, params, require_structural=True)
                        gross_rr = (bar.close - tp) / stop_dist
                        net_rr = net_reward_risk(bar.close, stop, tp, params)
                        rr_points = 2 if net_rr >= 2.0 else 1 if net_rr >= params.min_rr else 0
                        score = 2 + 2 + (2 if confirmed else 0) + rr_points + exec_score
                        state = "SELL" if confirmed and gross_rr >= params.min_rr and net_rr >= params.min_rr else "WAIT"
                        candidate = ("ENGINE_C_RANGE", state, "SELL", bar.close, stop, tp, score, f"C_SELL_{int(round(r_high * 10))}")

        if candidate is None:
            continue

        eng, st, side, entry, stop, tp, score, key = candidate
        new_wait = key not in audit._wait_keys
        audit.record_candidate(key, wait=st == "WAIT")
        gross_risk = abs(entry - stop)
        gross_reward = (tp - entry) if side == "BUY" else (entry - tp)
        gross_rr = gross_reward / gross_risk if gross_risk > 0 else 0.0
        net_rr = net_reward_risk(entry, stop, tp, params)

        if st == "WAIT":
            if gross_rr < params.min_rr or net_rr < params.min_rr:
                audit.rejected_by_rr += 1
            if new_wait and counterfactual_outcome(m5_bars, idx, side, stop, tp, params.mss_window_bars) == "TARGET_FIRST":
                audit.counterfactual_waits_that_hit_target += 1
            continue

        rejection: Optional[str] = None
        if params.use_regime_filter and regime == "UNCERTAIN_HIGH_VOL":
            audit.rejected_by_regime += 1
            rejection = "regime"
        elif not market_open or not sess_ok:
            audit.rejected_by_session += 1
            rejection = "session"
        elif news_blocked:
            audit.rejected_by_event += 1
            rejection = "event"
        elif daily_locked:
            audit.rejected_by_daily_lock += 1
            rejection = "daily"
        elif spread_atr_ratio > params.max_spread_atr_ratio:
            audit.rejected_by_spread += 1
            rejection = "spread"
        elif gross_rr < params.min_rr or net_rr < params.min_rr:
            audit.rejected_by_rr += 1
            rejection = "rr"
        elif score < params.score_threshold_10:
            audit.rejected_by_score += 1
            rejection = "score"
        elif key == last_traded_key:
            audit.rejected_by_duplicate += 1
            rejection = "duplicate"

        if rejection is not None:
            if counterfactual_outcome(m5_bars, idx, side, stop, tp, params.mss_window_bars) == "TARGET_FIRST":
                audit.counterfactual_rejections_that_hit_target += 1
            continue

        lots, ounces = calculate_position_size(entry, stop, equity, params)
        if lots <= 0.0:
            audit.rejected_by_min_size += 1
            continue

        audit.confirmed_entries += 1
        last_traded_key = key
        init_risk_usd = (gross_risk + rt_cost_per_oz) * ounces
        open_trade = {
            "entry_bar": idx,
            "entry_time_ms": bar.time_ms,
            "side": side,
            "engine": eng,
            "regime": regime,
            "session": sess,
            "entry": entry,
            "stop": stop,
            "tp": tp,
            "lots": lots,
            "ounces": ounces,
            "init_risk_usd": init_risk_usd,
            "score_10": score,
        }

    return trades, audit


def summarize_metrics(
    trades: Sequence[TradeRecord], initial_equity: float = 10_000.0
) -> Dict[str, object]:
    total = len(trades)
    if total == 0:
        return {
            "trade_count": 0,
            "win_rate_pct": 0.0,
            "net_profit_usd": 0.0,
            "return_pct": 0.0,
            "profit_factor": 0.0,
            "expectancy_usd": 0.0,
            "expectancy_r": 0.0,
            "avg_win_usd": 0.0,
            "avg_loss_usd": 0.0,
            "max_drawdown_pct": 0.0,
            "max_consec_losses": 0,
            "costs_pct_of_gross_profit": 0.0,
            "gross_profit_usd": 0.0,
            "gross_loss_usd": 0.0,
            "transaction_costs_usd": 0.0,
            "by_engine": {},
            "by_regime": {},
            "by_side": {},
            "by_session": {},
        }
    wins = [t for t in trades if t.net_pnl_usd > 0]
    losses = [t for t in trades if t.net_pnl_usd <= 0]
    gross_wins = sum(t.net_pnl_usd for t in wins)
    gross_losses = abs(sum(t.net_pnl_usd for t in losses))
    total_gross_profit = sum(max(0.0, t.gross_pnl_usd) for t in trades)
    total_costs = sum(t.cost_usd for t in trades)
    net_profit = sum(t.net_pnl_usd for t in trades)

    eq = initial_equity
    peak = initial_equity
    max_dd_pct = 0.0
    cur_consec_l = 0
    max_consec_l = 0
    for t in trades:
        eq += t.net_pnl_usd
        peak = max(peak, eq)
        dd = (peak - eq) / peak * 100.0 if peak > 0 else 0.0
        max_dd_pct = max(max_dd_pct, dd)
        if t.net_pnl_usd <= 0:
            cur_consec_l += 1
            max_consec_l = max(max_consec_l, cur_consec_l)
        else:
            cur_consec_l = 0

    def group_stats(key_fn) -> Dict[str, Dict[str, float]]:
        groups: Dict[str, List[TradeRecord]] = {}
        for t in trades:
            groups.setdefault(key_fn(t), []).append(t)
        return {
            k: {
                "count": len(v),
                "net_pnl_usd": round(sum(x.net_pnl_usd for x in v), 2),
                "expectancy_r": round(sum(x.r_multiple for x in v) / len(v), 3),
            }
            for k, v in groups.items()
        }

    return {
        "trade_count": total,
        "win_rate_pct": round(100.0 * len(wins) / total, 2),
        "net_profit_usd": round(net_profit, 2),
        "return_pct": round(100.0 * net_profit / initial_equity, 2),
        "profit_factor": round(gross_wins / gross_losses, 3) if gross_losses > 0 else None,
        "expectancy_usd": round(net_profit / total, 2),
        "expectancy_r": round(sum(t.r_multiple for t in trades) / total, 3),
        "avg_win_usd": round(gross_wins / len(wins), 2) if wins else 0.0,
        "avg_loss_usd": round(-gross_losses / len(losses), 2) if losses else 0.0,
        "max_drawdown_pct": round(max_dd_pct, 2),
        "max_consec_losses": max_consec_l,
        "costs_pct_of_gross_profit": round(100.0 * total_costs / total_gross_profit, 2) if total_gross_profit > 0 else 0.0,
        "gross_profit_usd": round(sum(max(0.0, t.gross_pnl_usd) for t in trades), 2),
        "gross_loss_usd": round(sum(min(0.0, t.gross_pnl_usd) for t in trades), 2),
        "transaction_costs_usd": round(total_costs, 2),
        "by_engine": group_stats(lambda t: t.engine),
        "by_regime": group_stats(lambda t: t.regime),
        "by_side": group_stats(lambda t: t.side),
        "by_session": group_stats(lambda t: t.session),
    }


def generate_synthetic_fixture(count: int = 1500) -> List[Bar]:
    """Deterministic multi-regime structural test fixture (BULLISH, RANGE, SWEEP, BEARISH)
    used strictly to verify engine state-machine transitions and stage A–E test harness logic.
    Never presented as live historical XAU/USD market performance.
    """
    bars: List[Bar] = []
    start_ms = 1_760_000_000_000  # UTC aligned
    step_ms = 300_000
    price = 2400.0
    for i in range(count):
        phase = (i // 250) % 3
        cycle = i % 24
        if phase == 0:
            # Bullish staircase with pullbacks every 24 bars
            if cycle < 15:
                delta = 0.55
            elif cycle < 20:
                delta = -0.65
            else:
                delta = 0.95
        elif phase == 1:
            # Bounded range with occasional liquidity sweep at cycle == 18
            wave = math.sin(i * 0.35) * 1.8
            delta = wave - (bars[-1].close - 2430.0) * 0.25 if bars else 0.0
        else:
            # Bearish staircase with pullbacks
            if cycle < 15:
                delta = -0.55
            elif cycle < 20:
                delta = 0.65
            else:
                delta = -0.95

        open_p = price
        close_p = max(1800.0, open_p + delta)
        high_p = max(open_p, close_p) + (0.9 if cycle == 19 else 0.45)
        low_p = min(open_p, close_p) - (0.9 if cycle == 19 else 0.45)
        bars.append(
            Bar(
                time_ms=start_ms + i * step_ms,
                open=round(open_p, 2),
                high=round(high_p, 2),
                low=round(low_p, 2),
                close=round(close_p, 2),
                volume=150.0,
            )
        )
        price = close_p
    return bars


def run_stages_a_to_e(bars: Sequence[Bar], params: Optional[Als5Params] = None) -> Dict[str, object]:
    base_params = params or Als5Params()
    # Stage A — Individual engines
    trades_a, _ = run_backtest(bars, replace(base_params, enable_engine_a=True, enable_engine_b=False, enable_engine_c=False))
    trades_b, _ = run_backtest(bars, replace(base_params, enable_engine_a=False, enable_engine_b=True, enable_engine_c=False))
    trades_c, _ = run_backtest(bars, replace(base_params, enable_engine_a=False, enable_engine_b=False, enable_engine_c=True))

    # Stage B — Combined 3-engine system
    trades_all, audit_all = run_backtest(bars, base_params)

    # Stage C — Ablation study (remove one component at a time)
    no_regime, _ = run_backtest(bars, replace(base_params, use_regime_filter=False))
    no_score, _ = run_backtest(bars, replace(base_params, score_threshold_10=5))
    no_vol, _ = run_backtest(bars, replace(base_params, use_volatility_filter=False))

    # Stage D — chronological 50/25/25 split. Each segment sees earlier bars for indicator warm-up,
    # but opens trades only inside its own indices and starts with independent reference equity.
    n = len(bars)
    dev_end = n // 2
    val_end = (3 * n) // 4
    trades_dev, _ = run_backtest(bars, base_params, trade_start_index=0, trade_end_index=dev_end)
    trades_val, _ = run_backtest(bars, base_params, trade_start_index=dev_end, trade_end_index=val_end)
    trades_oos, _ = run_backtest(bars, base_params, trade_start_index=val_end, trade_end_index=n)

    # Stage E — Cost & slippage stress test (1.5x and 2.0x transaction costs)
    high_cost_params = replace(
        base_params,
        spread_usd_per_oz=base_params.spread_usd_per_oz * 1.5,
        slippage_usd_per_oz=base_params.slippage_usd_per_oz * 2.0,
    )
    trades_high_cost, _ = run_backtest(bars, high_cost_params)
    trades_london_only, _ = run_backtest(bars, replace(base_params, allow_asian=False, allow_london=True, allow_ny=False))
    trades_new_york_only, _ = run_backtest(bars, replace(base_params, allow_asian=False, allow_london=False, allow_ny=True))
    trades_score_7, _ = run_backtest(bars, replace(base_params, score_threshold_10=7))
    trades_score_9, _ = run_backtest(bars, replace(base_params, score_threshold_10=9))
    trades_sweep_loose, _ = run_backtest(bars, replace(base_params, min_sweep_atr=max(0.05, base_params.min_sweep_atr - 0.02)))
    trades_sweep_tight, _ = run_backtest(bars, replace(base_params, min_sweep_atr=base_params.min_sweep_atr + 0.02))

    def metrics(trades: Sequence[TradeRecord]) -> Dict[str, object]:
        return summarize_metrics(trades, base_params.initial_equity)

    loose_sweep_min = max(0.05, base_params.min_sweep_atr - 0.02)
    tight_sweep_min = base_params.min_sweep_atr + 0.02

    return {
        "stage_a_individual": {
            "engine_a_only": metrics(trades_a),
            "engine_b_only": metrics(trades_b),
            "engine_c_only": metrics(trades_c),
        },
        "stage_b_combined": metrics(trades_all),
        "opportunity_coverage": audit_all.summary(),
        "stage_c_ablation": {
            "without_regime_classifier": metrics(no_regime),
            "without_score_threshold": metrics(no_score),
            "without_volatility_lock": metrics(no_vol),
        },
        "stage_d_chronological_split": {
            "development_50pct": metrics(trades_dev),
            "validation_25pct": metrics(trades_val),
            "out_of_sample_25pct": metrics(trades_oos),
        },
        "stage_e_robustness": {
            "cost_stress": {
                "spread_multiplier": 1.5,
                "slippage_multiplier": 2.0,
                "commission_multiplier": 1.0,
                "metrics": metrics(trades_high_cost),
            },
            "session_sensitivity": {
                "london_only": metrics(trades_london_only),
                "new_york_only": metrics(trades_new_york_only),
            },
            "score_threshold_sensitivity": {
                "threshold_7": metrics(trades_score_7),
                "threshold_9": metrics(trades_score_9),
            },
            "minimum_sweep_atr_sensitivity": {
                "looser": {"value": loose_sweep_min, "metrics": metrics(trades_sweep_loose)},
                "tighter": {"value": tight_sweep_min, "metrics": metrics(trades_sweep_tight)},
            },
        },
    }


def load_csv_bars(path: Path) -> List[Bar]:
    rows: List[Bar] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for r in reader:
            rows.append(
                Bar(
                    time_ms=int(r["time_ms"]),
                    open=float(r["open"]),
                    high=float(r["high"]),
                    low=float(r["low"]),
                    close=float(r["close"]),
                    volume=float(r.get("volume", 100.0)),
                )
            )
    return rows


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, help="Path to real XAU/USD M5 OHLCV CSV file.")
    parser.add_argument("--verify-harness", action="store_true", help="Run Stage A–E verification on synthetic structural fixture.")
    args = parser.parse_args(argv)

    if args.csv:
        bars = load_csv_bars(args.csv)
        dataset_label = f"REAL_CSV:{args.csv}"
    elif args.verify_harness:
        bars = generate_synthetic_fixture(1500)
        dataset_label = "SYNTHETIC_STRUCTURAL_VERIFICATION_FIXTURE_ONLY (NOT REAL XAU/USD MARKET HISTORY)"
    else:
        parser.error("Provide --csv <path/to/xauusd_m5.csv> or --verify-harness")
        return 2

    report = {
        "dataset": dataset_label,
        "bars_count": len(bars),
        "results": run_stages_a_to_e(bars),
    }
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
