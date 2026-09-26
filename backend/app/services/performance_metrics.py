"""Descriptive report from actual settled backtest/journal trade records only.

Sharpe is per-trade (sample standard deviation, zero risk-free rate), NOT annualized
and NOT a daily Sharpe. No annualization without an observed daily equity series.
"""
from __future__ import annotations

import math
from datetime import datetime
from statistics import mean, stdev
from typing import Any


def summarize_trades(trades: list[dict[str, Any]], initial_balance: float) -> dict[str, Any]:
    if not math.isfinite(initial_balance) or initial_balance <= 0:
        raise ValueError("initial_balance must be positive")
    settled = sorted((t for t in trades if t.get("pnl") is not None and math.isfinite(t["pnl"])),
                     key=lambda t: t.get("exit_time", ""))
    winners = [t for t in settled if t["pnl"] > 0]
    losers = [t for t in settled if t["pnl"] <= 0]
    gross_profit = sum(t["pnl"] for t in winners)
    gross_loss = abs(sum(t["pnl"] for t in losers))
    durations: list[float] = []
    returns: list[float] = []
    r_multiples: list[float] = []
    equity = initial_balance
    peak = equity
    max_dd = 0.0
    win_streak = loss_streak = best_win = best_loss = 0
    for trade in settled:
        opened = trade.get("entry_time")
        closed = trade.get("exit_time")
        if opened and closed:
            try:
                seconds = (datetime.fromisoformat(closed) - datetime.fromisoformat(opened)).total_seconds()
                if seconds > 0:
                    durations.append(seconds)
            except ValueError:
                pass
        r = trade.get("r_multiple")
        if isinstance(r, (int, float)) and math.isfinite(r):
            r_multiples.append(r)
        if equity > 0:
            returns.append(trade["pnl"] / equity)
        equity += trade["pnl"]
        peak = max(peak, equity)
        max_dd = max(max_dd, (peak - equity) / peak * 100)
        if trade["pnl"] > 0:
            win_streak, loss_streak = win_streak + 1, 0
        else:
            win_streak, loss_streak = 0, loss_streak + 1
        best_win, best_loss = max(best_win, win_streak), max(best_loss, loss_streak)
    sharpe = mean(returns) / stdev(returns) if len(returns) == len(settled) and len(returns) >= 2 and stdev(returns) > 0 else None
    return {
        "total": len(settled), "wins": len(winners), "losses": len(losers),
        "long_count": sum(t.get("side") == "BUY" for t in settled),
        "short_count": sum(t.get("side") == "SELL" for t in settled),
        "win_rate_pct": round(len(winners) / len(settled) * 100, 2) if settled else None,
        "net_pnl_usd": round(sum(t["pnl"] for t in settled), 2),
        "gross_profit_usd": round(gross_profit, 2), "gross_loss_usd": round(gross_loss, 2),
        "average_win_usd": round(mean(t["pnl"] for t in winners), 2) if winners else None,
        "average_loss_usd": round(mean(t["pnl"] for t in losers), 2) if losers else None,
        "profit_factor": round(gross_profit / gross_loss, 3) if gross_loss else None,
        "sharpe_per_trade": round(sharpe, 3) if sharpe is not None else None,
        "expectancy_r": round(mean(r_multiples), 3) if r_multiples else None,
        "average_duration_seconds": round(mean(durations)) if durations else None,
        "longest_winning_streak": best_win, "longest_losing_streak": best_loss,
        "max_drawdown_pct": round(max_dd, 2) if settled else None,
        "fees_usd": round(sum(t.get("fees", 0.0) for t in settled), 2),
        "method": "per-trade Sharpe; sample stdev; zero risk-free; not annualized",
    }
