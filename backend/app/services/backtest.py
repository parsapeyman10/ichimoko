"""
Backtest engine — runs the SAME live strategy over candles that really came from the provider.

Rules of this module:
  * it never generates price data; it only replays `candles` it is given (see services/history.py);
  * it never projects a "future year" or applies a tuned win-rate target;
  * trades that a small account could not actually place (below broker minimum lot) are counted
    as skipped instead of being quietly reported as profit;
  * cost assumptions (spread, commission) are inputs and are echoed back in the report.
"""
from __future__ import annotations

import random
from datetime import datetime, timezone
from statistics import mean
from typing import Any

from app.models import Candle, Direction, StrategyContext, Timeframe, TradeSignal
from app.services import indicators
from app.services.performance_metrics import summarize_trades
from app.services.strategy import evaluate_scalp, get_trailing_stop, should_exit

# Bars handed to the live evaluator on each candidate (it requires >= 200).
LIVE_WINDOW = 260

ICHIMOKU_SETTINGS: dict[str, tuple[int, int, int, int]] = {
    "3m": (8, 24, 48, 24),
    "5m": (9, 26, 52, 26),
    "15m": (9, 26, 52, 26),
    "1h": (9, 26, 52, 26),
    "4h": (9, 26, 52, 26),
    "1d": (9, 26, 52, 26),
    "1m": (7, 22, 44, 22),
}

TIME_STOP_BARS = {"3m": 8, "5m": 10, "15m": 12, "1m": 8}


def _ichimoku_settings(timeframe: Timeframe) -> tuple[int, int, int, int]:
    return ICHIMOKU_SETTINGS.get(timeframe.value, (7, 22, 44, 22))


def cross_candidate_indices(candles: list[Candle], timeframe: Timeframe) -> set[int]:
    """
    Indices where a fresh Tenkan/Kijun cross exists on this bar or the previous one.

    `evaluate_scalp` returns NO_TRADE before scoring whenever there is no fresh cross, so this
    pre-filter cannot skip a tradable bar — it only avoids recomputing indicators on every bar.
    """
    t_p, k_p, b_p, disp = _ichimoku_settings(timeframe)
    values = indicators.ichimoku(candles, t_p, k_p, b_p, disp)
    tenkan, kijun = values["tenkan"], values["kijun"]
    candidates: set[int] = set()
    for i in range(2, len(candles)):
        t, k = tenkan[i], kijun[i]
        pt, pk = tenkan[i - 1], kijun[i - 1]
        p2t, p2k = tenkan[i - 2], kijun[i - 2]
        if None in (t, k, pt, pk, p2t, p2k):
            continue
        bull_now = pt <= pk and t > k
        bear_now = pt >= pk and t < k
        bull_before = p2t <= p2k and pt > pk and t > k
        bear_before = p2t >= p2k and pt < pk and t < k
        if bull_now or bear_now or bull_before or bear_before:
            candidates.add(i)
    return candidates


def run_backtest(
    candles: list[Candle],
    initial_balance: float = 100.0,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    min_position_oz: float = 1.0,
    leverage: int = 100,
    use_trailing: bool = True,
    context: StrategyContext | None = None,
    start_index: int | None = None,
) -> dict[str, Any]:
    """Replay the live strategy over real candles. Returns an honest report.

    [start_index] lets walk-forward analysis begin evaluating signals at a specific bar while
    still using the earlier bars purely as indicator warm-up.
    """
    if len(candles) < 220:
        return {
            "error": f"داده کافی نیست: {len(candles)} کندل واقعی دریافت شد، حداقل ۲۲۰ لازم است",
            "bars": len(candles),
        }
    timeframe = candles[-1].timeframe
    ctx = context or StrategyContext()
    candidates = cross_candidate_indices(candles, timeframe)
    kijun_full = indicators.ichimoku(candles, *_ichimoku_settings(timeframe))["kijun"]
    atr_full = indicators.atr(candles, 14)

    balance = initial_balance
    peak = initial_balance
    max_drawdown = 0.0
    trades: list[dict[str, Any]] = []
    equity_curve: list[dict[str, Any]] = [{"time": candles[0].timestamp.isoformat(), "balance": round(balance, 2)}]
    skipped_min_lot = 0
    skipped_margin = 0
    skipped_stop_distances: list[float] = []
    rejected: dict[str, int] = {}

    position: dict[str, Any] | None = None
    first_index = 210 if start_index is None else max(210, min(start_index, len(candles) - 1))
    i = first_index
    while i < len(candles):
        bar = candles[i]
        if position is None:
            if i in candidates:
                window = candles[max(0, i - LIVE_WINDOW + 1): i + 1]
                signal = evaluate_scalp(window, ctx)
                if signal.action in (Direction.BUY, Direction.SELL):
                    long = signal.action is Direction.BUY
                    direction = 1.0 if long else -1.0
                    fill = bar.close + direction * spread / 2.0
                    stop_distance = abs(fill - float(signal.stop_loss))
                    risk_usd = balance * risk_percent / 100.0
                    oz = risk_usd / stop_distance if stop_distance > 0 else 0.0
                    max_oz = balance * leverage / bar.close
                    if oz > max_oz:
                        oz = max_oz
                        skipped_margin += 1
                    if oz < min_position_oz:
                        skipped_min_lot += 1
                        skipped_stop_distances.append(stop_distance)
                    else:
                        position = {
                            "signal": signal,
                            "direction": direction,
                            "entry": fill,
                            "oz": oz,
                            "entry_time": bar.timestamp,
                            "entry_index": i,
                            # Kept separate from signal.stop_loss on purpose: the latter gets
                            # trailed over time, but R-multiples (trailing thresholds, risk_usd,
                            # r_multiple in the trade log) must stay pinned to the risk actually
                            # taken when the position was opened.
                            "initial_stop": float(signal.stop_loss),
                        }
                else:
                    for blocker in signal.blockers:
                        key = blocker.split(" / ")[0][:110]
                        rejected[key] = rejected.get(key, 0) + 1
        else:
            direction = position["direction"]
            signal: TradeSignal = position["signal"]
            since_entry = candles[position["entry_index"]: i + 1]
            kijun = kijun_full[i] or bar.close
            atr_value = atr_full[i] or 0.0
            if use_trailing:
                new_stop = get_trailing_stop(
                    signal, since_entry, float(kijun), float(atr_value),
                    initial_stop=position["initial_stop"],
                )
                if new_stop is not None:
                    signal = signal.model_copy(update={"stop_loss": new_stop})
                    position["signal"] = signal
            exit_now, reason = should_exit(signal, since_entry, float(kijun))
            if exit_now:
                long = direction > 0
                hits_stop = signal.stop_loss is not None and (
                    bar.low <= signal.stop_loss if long else bar.high >= signal.stop_loss
                )
                hits_target = signal.take_profit is not None and (
                    bar.high >= signal.take_profit if long else bar.low <= signal.take_profit
                )
                if hits_stop and not hits_target:
                    exit_spot = float(signal.stop_loss)
                elif hits_target and not hits_stop:
                    exit_spot = float(signal.take_profit)
                elif hits_stop and hits_target:
                    # Both touched inside one bar → assume the stop (conservative).
                    exit_spot = float(signal.stop_loss)
                else:
                    exit_spot = bar.close
                exit_fill = exit_spot - direction * spread / 2.0
                oz = position["oz"]
                fees = commission_per_oz * oz * 2.0
                pnl = (exit_fill - position["entry"]) * direction * oz - fees
                # r_multiple must reflect the risk actually taken at entry, not the (possibly
                # trailed-in) stop at exit time — otherwise a trailed stop makes small wins look
                # like huge R multiples.
                risk_usd = abs(position["entry"] - position["initial_stop"]) * oz
                balance += pnl
                peak = max(peak, balance)
                drawdown = (peak - balance) / peak * 100 if peak > 0 else 0.0
                max_drawdown = max(max_drawdown, drawdown)
                trades.append(
                    {
                        "id": f"bt-{len(trades) + 1:04d}",
                        "side": signal.action.value,
                        "timeframe": timeframe.value,
                        "entry_time": position["entry_time"].isoformat(),
                        "exit_time": bar.timestamp.isoformat(),
                        "entry": round(position["entry"], 2),
                        "exit": round(exit_fill, 2),
                        "stop_loss": round(float(signal.stop_loss), 2),
                        "take_profit": round(float(signal.take_profit), 2),
                        "position_oz": round(oz, 4),
                        "confidence": signal.confidence,
                        "pnl": round(pnl, 2),
                        "r_multiple": round(pnl / risk_usd, 2) if risk_usd > 0 else 0.0,
                        "fees": round(fees, 4),
                        "exit_reason": reason.split(" / ")[0],
                        "bars_held": i - position["entry_index"],
                    }
                )
                equity_curve.append({"time": bar.timestamp.isoformat(), "balance": round(balance, 2)})
                position = None
        i += 1

    if position is not None:
        # Still open at the end of the data: mark it as open, priced at the last real close.
        bar = candles[-1]
        direction = position["direction"]
        exit_fill = bar.close - direction * spread / 2.0
        oz = position["oz"]
        fees = commission_per_oz * oz * 2.0
        pnl = (exit_fill - position["entry"]) * direction * oz - fees
        risk_usd = abs(position["entry"] - position["initial_stop"]) * oz
        balance += pnl
        peak = max(peak, balance)
        max_drawdown = max(max_drawdown, (peak - balance) / peak * 100 if peak > 0 else 0.0)
        trades.append(
            {
                "id": f"bt-{len(trades) + 1:04d}",
                "side": position["signal"].action.value,
                "timeframe": timeframe.value,
                "entry_time": position["entry_time"].isoformat(),
                "exit_time": bar.timestamp.isoformat(),
                "entry": round(position["entry"], 2),
                "exit": round(exit_fill, 2),
                "stop_loss": round(float(position["signal"].stop_loss), 2),
                "take_profit": round(float(position["signal"].take_profit), 2),
                "position_oz": round(oz, 4),
                "confidence": position["signal"].confidence,
                "pnl": round(pnl, 2),
                "r_multiple": round(pnl / risk_usd, 2) if risk_usd > 0 else 0.0,
                "fees": round(fees, 4),
                "exit_reason": "باز — بسته‌شده روی آخرین قیمت واقعی",
                "bars_held": len(candles) - 1 - position["entry_index"],
            }
        )
        equity_curve.append({"time": bar.timestamp.isoformat(), "balance": round(balance, 2)})

    return _summarize(
        trades=trades,
        equity_curve=equity_curve,
        initial_balance=initial_balance,
        final_balance=balance,
        max_drawdown_pct=max_drawdown,
        candles=candles,
        timeframe=timeframe,
        skipped_min_lot=skipped_min_lot,
        skipped_margin=skipped_margin,
        spread=spread,
        commission_per_oz=commission_per_oz,
        min_position_oz=min_position_oz,
        leverage=leverage,
        rejected=rejected,
        risk_percent=risk_percent,
        skipped_stop_distances=skipped_stop_distances,
    )


def _summarize(
    *,
    trades: list[dict[str, Any]],
    equity_curve: list[dict[str, Any]],
    initial_balance: float,
    final_balance: float,
    max_drawdown_pct: float,
    candles: list[Candle],
    timeframe: Timeframe,
    skipped_min_lot: int,
    skipped_margin: int,
    spread: float,
    commission_per_oz: float,
    min_position_oz: float,
    leverage: int,
    rejected: dict[str, int],
    risk_percent: float,
    skipped_stop_distances: list[float],
) -> dict[str, Any]:
    wins = [t for t in trades if t["pnl"] > 0]
    losses = [t for t in trades if t["pnl"] <= 0]
    gross_profit = sum(t["pnl"] for t in wins)
    gross_loss = abs(sum(t["pnl"] for t in losses))
    r_values = [t["r_multiple"] for t in trades]
    performance = summarize_trades(trades, initial_balance)
    notes = [
        f"شبیه‌سازی روی {len(candles)} کندل واقعی {timeframe.value} دریافت‌شده از Twelve Data "
        f"({candles[0].timestamp:%Y-%m-%d %H:%M} تا {candles[-1].timestamp:%Y-%m-%d %H:%M} UTC)",
        "همان موتور زنده اجرا شده؛ هیچ پیش‌بینی آینده یا عدد مونت‌کارلویی در این گزارش نیست.",
        f"فرض هزینه: اسپرد {spread} و کمیسیون {commission_per_oz}$ بر انس (رفت‌وبرگشت)، اهرم حداکثر 1:{leverage}.",
        f"حداقل حجم معامله {min_position_oz} انس (0.01 لات طلا) در نظر گرفته شده است.",
    ]
    if skipped_min_lot:
        notes.append(
            f"{skipped_min_lot} سیگنال به‌دلیل حداقل حجم بروکر برای این حساب قابل اجرا نبود و رد شد "
            "(عدد جعلی جای آن گزارش نمی‌شود)."
        )
    if skipped_margin:
        notes.append(f"{skipped_margin} معامله به‌دلیل سقف اهرم کوچک‌تر از حجم ریسک‌محور اجرا شد.")

    feasibility: dict[str, Any] = {"broker_min_position_oz": min_position_oz}
    if skipped_stop_distances:
        typical_stop = sorted(skipped_stop_distances)[len(skipped_stop_distances) // 2]
        risk_usd_at_min_lot = typical_stop * min_position_oz
        required_balance = risk_usd_at_min_lot / (risk_percent / 100) if risk_percent > 0 else None
        feasibility.update(
            {
                "typical_stop_distance": round(typical_stop, 2),
                "risk_usd_at_minimum_lot": round(risk_usd_at_min_lot, 2),
                "risk_pct_at_minimum_lot": round(risk_usd_at_min_lot / initial_balance * 100, 2) if initial_balance else None,
                "suggested_min_balance": round(required_balance, 2) if required_balance else None,
                "verdict": (
                    f"با حجم {min_position_oz} انس (0.01 لات) و فاصله استاپ معمول {typical_stop:.2f}$، "
                    f"هر معامله {risk_usd_at_min_lot:.2f}$ ریسک دارد؛ برای رعایت ریسک {risk_percent}% "
                    f"حداقل موجودی حدود {required_balance:,.0f}$ لازم است. اعداد این گزارش با موجودی "
                    f"{initial_balance:,.0f}$ ساخته شده و به همین دلیل {skipped_min_lot} سیگنال اجرا نشد."
                ),
            }
        )
    else:
        feasibility["verdict"] = "همه سیگنال‌های واجد شرایط با حداقل لات بروکر قابل اجرا بودند."
    return {
        "data_source": "twelve_data",
        "symbol": candles[0].symbol,
        "timeframe": timeframe.value,
        "bars": len(candles),
        "start": candles[0].timestamp.isoformat(),
        "end": candles[-1].timestamp.isoformat(),
        "initial_balance": round(initial_balance, 2),
        "final_balance": round(final_balance, 2),
        "total_pnl": round(final_balance - initial_balance, 2),
        "total_return_pct": round((final_balance - initial_balance) / initial_balance * 100, 2) if initial_balance else 0.0,
        "total_trades": len(trades),
        "wins": len(wins),
        "losses": len(losses),
        "win_rate": round(len(wins) / len(trades) * 100, 1) if trades else None,
        "profit_factor": round(gross_profit / gross_loss, 2) if gross_loss > 0 else None,
        "expectancy": round(mean(r_values), 3) if r_values else None,
        "expectancy_usd": round(mean([t["pnl"] for t in trades]), 2) if trades else None,
        "avg_win": round(mean([t["pnl"] for t in wins]), 2) if wins else None,
        "avg_loss": round(mean([t["pnl"] for t in losses]), 2) if losses else None,
        "sharpe": performance["sharpe_per_trade"],  # never mislabel a sqrt(N) t-statistic as Sharpe
        "long_count": performance["long_count"],
        "short_count": performance["short_count"],
        "average_duration_seconds": performance["average_duration_seconds"],
        "longest_winning_streak": performance["longest_winning_streak"],
        "longest_losing_streak": performance["longest_losing_streak"],
        "performance": performance,
        "max_drawdown": round(max_drawdown_pct / 100 * initial_balance, 2),
        "max_drawdown_pct": round(max_drawdown_pct, 2),
        "gross_profit": round(gross_profit, 2),
        "gross_loss": round(gross_loss, 2),
        "fees_paid": round(sum(t["fees"] for t in trades), 2),
        "risk_percent": risk_percent,
        "spread": spread,
        "commission_per_oz": commission_per_oz,
        "min_position_oz": min_position_oz,
        "leverage": leverage,
        "skipped_min_lot": skipped_min_lot,
        "skipped_margin": skipped_margin,
        "equity_curve": equity_curve,
        "trades": trades,
        "yearly": yearly_breakdown(trades),
        "feasibility": feasibility,
        "rejected_setups": sorted(
            ({"reason": k, "count": v} for k, v in rejected.items()), key=lambda x: -x["count"]
        )[:12],
        "notes": notes,
    }


def yearly_breakdown(trades: list[dict[str, Any]]) -> list[dict[str, Any]]:
    buckets: dict[int, list[dict[str, Any]]] = {}
    for trade in trades:
        year = datetime.fromisoformat(trade["entry_time"]).year
        buckets.setdefault(year, []).append(trade)
    out: list[dict[str, Any]] = []
    for year in sorted(buckets):
        group = buckets[year]
        pnl = sum(t["pnl"] for t in group)
        wins = sum(1 for t in group if t["pnl"] > 0)
        out.append(
            {
                "year": year,
                "trades": len(group),
                "wins": wins,
                "losses": len(group) - wins,
                "win_rate": round(wins / len(group) * 100, 1) if group else None,
                "pnl": round(pnl, 2),
                "avg_r": round(mean([t["r_multiple"] for t in group]), 2) if group else None,
            }
        )
    return out


def stress_test_from_trades(
    trades: list[dict[str, Any]],
    initial_balance: float = 100.0,
    runs: int = 5000,
    seed: int = 7,
) -> dict[str, Any]:
    """
    Bootstrap risk analysis over the REAL trade outcomes of a backtest.

    This resamples realised results — it never invents a win-rate or a price path. If there are
    no real trades, it refuses to produce numbers.
    """
    r_values = [float(t.get("r_multiple", 0.0)) for t in trades if t.get("r_multiple") is not None]
    if len(r_values) < 5:
        return {
            "error": "تحلیل ریسک به حداقل ۵ معامله واقعی بسته‌شده نیاز دارد",
            "real_trades": len(r_values),
        }
    rng = random.Random(seed)
    risk_fraction = 0.005  # 0.5% per trade, matching the engine's base policy
    finals: list[float] = []
    worst_dd: list[float] = []
    ruined = 0
    for _ in range(runs):
        equity = initial_balance
        peak = initial_balance
        dd = 0.0
        for _ in range(len(r_values)):
            r = rng.choice(r_values)
            equity += equity * risk_fraction * r
            if equity <= initial_balance * 0.2:
                ruined += 1
                break
            peak = max(peak, equity)
            dd = max(dd, (peak - equity) / peak * 100 if peak > 0 else 0.0)
        finals.append(equity)
        worst_dd.append(dd)
    finals.sort()
    worst_dd.sort()
    return {
        "method": "bootstrap روی نتایج واقعی همان بک‌تست",
        "real_trades": len(r_values),
        "runs": runs,
        "median_final_balance": round(finals[len(finals) // 2], 2),
        "p05_final_balance": round(finals[int(len(finals) * 0.05)], 2),
        "p95_final_balance": round(finals[int(len(finals) * 0.95)], 2),
        "median_max_drawdown_pct": round(worst_dd[len(worst_dd) // 2], 2),
        "p95_max_drawdown_pct": round(worst_dd[int(len(worst_dd) * 0.95)], 2),
        "risk_of_80pct_loss_pct": round(ruined / runs * 100, 2),
        "note": "این اعداد از بازنمونه‌گیری نتایج واقعی ساخته شده‌اند، نه از فرض وین‌ریت.",
    }
