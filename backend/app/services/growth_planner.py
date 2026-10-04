"""
Account growth planning — what a target actually requires, and what it costs in risk.

This module exists to answer one question honestly: "how do I turn $100 into $1,000?"

It does not forecast the market. It takes an edge you specify (win rate and net
reward:risk, ideally measured from your own backtest/journal rather than hoped for) and
works out the arithmetic consequences:

  * expectancy per trade and the compounding path implied by it,
  * how many trades and how much calendar time the target needs,
  * the risk-per-trade a deadline would force, and whether that is survivable,
  * the probability of hitting the target versus busting, by Monte Carlo over the actual
    trade distribution rather than a smooth average,
  * the Kelly fraction, and how far the proposed risk is from it.

Why this matters: expectancy is linear in risk, but ruin is not. Doubling risk roughly
doubles growth per trade and far more than doubles the chance of never getting there. The
only way to see that is to simulate the path, which is what `monte_carlo` does.

Nothing here is a promise. A positive expectancy is an assumption supplied by the caller;
if the real edge is zero or negative, every number below is a description of losing money
more slowly or faster.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass

# Below this balance the realistic trade universe collapses (see InstrumentSpec.min_qty):
# FX micro lots and 1-oz gold force several percent of risk per trade on a small account.
PRACTICAL_FX_FLOOR_USD = 250.0


@dataclass
class EdgeAssumption:
    """The edge the plan is built on. Measure these; do not wish for them."""

    win_rate: float          # 0..1, fraction of trades that reach target
    net_rr: float            # reward:risk AFTER spread and commission
    risk_pct: float          # risk per trade, % of current balance
    trades_per_day: float = 3.0
    # How many real trades the win rate was measured over. This is not a detail: a 50%
    # win rate seen in 30 trades and one seen in 500 trades are completely different
    # claims. The simulation draws each run's true win rate from the posterior implied by
    # this sample size, so a thinly-evidenced edge correctly produces a wide, scary
    # distribution instead of a smooth curve to the target.
    sample_size: int = 100

    def expectancy_r(self) -> float:
        """Expected return per trade in units of R (risk). Positive = edge."""
        return self.win_rate * self.net_rr - (1.0 - self.win_rate)

    def expectancy_pct(self) -> float:
        """Expected growth per trade as a % of balance."""
        return self.expectancy_r() * self.risk_pct

    def kelly_fraction(self) -> float:
        """Optimal-growth risk fraction. Full Kelly is far too volatile to trade live."""
        if self.net_rr <= 0:
            return 0.0
        edge = (self.win_rate * (1 + self.net_rr) - 1) / self.net_rr
        return max(0.0, edge)


def trades_to_target(start: float, target: float, edge: EdgeAssumption) -> float | None:
    """Trade count needed, compounding at the expected per-trade growth rate.

    Returns None when the edge is non-positive — no number of trades reaches the target.
    """
    if start <= 0 or target <= start:
        return 0.0
    growth = edge.expectancy_pct() / 100.0
    if growth <= 0:
        return None
    return math.log(target / start) / math.log(1 + growth)


def required_risk_pct(start: float, target: float, trades: int, win_rate: float, net_rr: float) -> float | None:
    """The risk-per-trade a deadline forces. Often the most sobering number here."""
    if trades <= 0 or start <= 0 or target <= start:
        return None
    needed_growth = (target / start) ** (1 / trades) - 1
    edge_r = win_rate * net_rr - (1 - win_rate)
    if edge_r <= 0:
        return None
    return needed_growth / edge_r * 100


def monte_carlo(
    start: float,
    target: float,
    edge: EdgeAssumption,
    max_trades: int = 4000,
    ruin_fraction: float = 0.5,
    runs: int = 4000,
    seed: int = 20260101,
    model_edge_uncertainty: bool = True,
) -> dict:
    """Simulate the actual path, not the average.

    Two things averages hide:

    1. Sequence risk. A positive-expectancy strategy can still bust an account if risk per
       trade is high, because losses compound against a shrinking balance and a bad streak
       can end the run before the edge shows up.
    2. Edge uncertainty. Assuming the win rate is exactly known and constant forever is the
       single most misleading assumption in retail trading plans. Here each run draws its
       own true win rate from the Beta posterior implied by `edge.sample_size`, so an edge
       measured over 30 trades produces a far wider outcome distribution than the same
       number measured over 500 — which is the truth.

    `ruin_fraction` is the drawdown that counts as failure (0.5 = account halved).
    """
    rng = random.Random(seed)
    ruin_level = start * ruin_fraction
    reached = busted = 0
    trades_when_reached: list[int] = []
    final_balances: list[float] = []
    max_drawdowns: list[float] = []
    sampled_win_rates: list[float] = []
    negative_edge_runs = 0
    worst_losing_streak = 0
    breakeven_wr = 1 / (1 + edge.net_rr) if edge.net_rr > 0 else 1.0

    # Beta posterior from a uniform prior: wins+1, losses+1.
    alpha = max(1e-6, edge.win_rate * edge.sample_size) + 1.0
    beta_param = max(1e-6, (1 - edge.win_rate) * edge.sample_size) + 1.0

    for _ in range(runs):
        if model_edge_uncertainty:
            true_wr = min(1.0, max(0.0, rng.betavariate(alpha, beta_param)))
        else:
            true_wr = edge.win_rate
        sampled_win_rates.append(true_wr)
        if true_wr < breakeven_wr:
            negative_edge_runs += 1
        balance = start
        peak = start
        streak = 0
        drawdown = 0.0
        outcome = "timeout"
        for trade in range(1, max_trades + 1):
            risk_cash = balance * (edge.risk_pct / 100.0)
            if rng.random() < true_wr:
                balance += risk_cash * edge.net_rr
                streak = 0
            else:
                balance -= risk_cash
                streak += 1
                worst_losing_streak = max(worst_losing_streak, streak)
            peak = max(peak, balance)
            drawdown = max(drawdown, (peak - balance) / peak if peak > 0 else 0.0)
            if balance <= ruin_level:
                busted += 1
                outcome = "ruin"
                break
            if balance >= target:
                reached += 1
                trades_when_reached.append(trade)
                outcome = "target"
                break
        if outcome == "timeout":
            final_balances.append(balance)
        max_drawdowns.append(drawdown)

    def pct(values, q: float) -> float | None:
        if not values:
            return None
        ordered = sorted(values)
        index = min(len(ordered) - 1, max(0, int(q * (len(ordered) - 1))))
        return float(ordered[index])

    return {
        "runs": runs,
        "reached_target_pct": round(reached / runs * 100, 1),
        "ruin_pct": round(busted / runs * 100, 1),
        "timeout_pct": round((runs - reached - busted) / runs * 100, 1),
        "ruin_definition": f"افت موجودی به {ruin_fraction * 100:.0f}% سرمایهٔ اولیه",
        "edge_uncertainty_modelled": model_edge_uncertainty,
        "measured_over_trades": edge.sample_size,
        "prob_edge_is_actually_negative_pct": round(negative_edge_runs / runs * 100, 1),
        "win_rate_p05": round((pct(sampled_win_rates, 0.05) or 0) * 100, 1),
        "win_rate_p95": round((pct(sampled_win_rates, 0.95) or 0) * 100, 1),
        "median_trades_to_target": pct(trades_when_reached, 0.5),
        "p90_trades_to_target": pct(trades_when_reached, 0.9),
        "median_max_drawdown_pct": round((pct(max_drawdowns, 0.5) or 0) * 100, 1),
        "p95_max_drawdown_pct": round((pct(max_drawdowns, 0.95) or 0) * 100, 1),
        "worst_losing_streak": worst_losing_streak,
        "max_trades_simulated": max_trades,
    }


def plan(
    start: float,
    target: float,
    edge: EdgeAssumption,
    deadline_days: int | None = None,
) -> dict:
    """Full, honest plan for getting from `start` to `target`."""
    expectancy_r = edge.expectancy_r()
    expectancy_pct = edge.expectancy_pct()
    needed = trades_to_target(start, target, edge)
    kelly = edge.kelly_fraction()

    warnings: list[str] = []
    verdict_parts: list[str] = []

    if expectancy_r <= 0:
        warnings.append(
            f"با وین‌ریت {edge.win_rate * 100:.0f}% و R:R خالص {edge.net_rr:.2f}، انتظار ریاضی هر معامله "
            f"{expectancy_r:+.3f}R است — یعنی منفی. هیچ حجم و هیچ تعداد معامله‌ای این را سودده نمی‌کند؛ "
            "اول باید خود استراتژی بهتر شود."
        )

    days = None
    if needed and edge.trades_per_day > 0:
        days = needed / edge.trades_per_day

    deadline_risk = None
    if deadline_days and deadline_days > 0:
        deadline_trades = int(deadline_days * edge.trades_per_day)
        deadline_risk = required_risk_pct(start, target, deadline_trades, edge.win_rate, edge.net_rr)
        if deadline_risk is None:
            warnings.append("با این فرض‌ها، رسیدن به هدف در مهلت تعیین‌شده از نظر ریاضی ممکن نیست.")
        elif deadline_risk > 2.0:
            warnings.append(
                f"برای رسیدن به هدف در {deadline_days} روز باید {deadline_risk:.1f}% در هر معامله ریسک کنید. "
                f"این بیش از سقف ۲٪ سیستم است و احتمال نابودی حساب را به‌شدت بالا می‌برد."
            )

    if edge.risk_pct > kelly * 100:
        warnings.append(
            f"ریسک {edge.risk_pct}% از کسر کِلی ({kelly * 100:.1f}%) بیشتر است — در این ناحیه، "
            "افزایش حجم رشد بلندمدت را کم می‌کند نه زیاد."
        )
    elif edge.risk_pct > kelly * 50:
        warnings.append(
            f"ریسک {edge.risk_pct}% بالاتر از نصف کِلی ({kelly * 50:.1f}%) است — نوسان حساب زیاد خواهد بود."
        )

    if start < PRACTICAL_FX_FLOOR_USD:
        warnings.append(
            f"با موجودی {start:.0f} دلار، حداقل حجم قابل معاملهٔ فارکس (۰٫۰۱ لات) و طلا (۱ انس) "
            f"چند درصد حساب را ریسک می‌کند. عملاً زیر حدود {PRACTICAL_FX_FLOOR_USD:.0f} دلار فقط "
            "کریپتوی اسپات (با پله‌های حجم بسیار ریز) قابل معاملهٔ ایمن است."
        )

    simulation = monte_carlo(start, target, edge)

    if expectancy_r > 0:
        verdict_parts.append(
            f"با این فرض‌ها هر معامله به‌طور میانگین {expectancy_pct:+.3f}% به حساب اضافه می‌کند."
        )
        if needed:
            verdict_parts.append(f"رسیدن از {start:.0f}$ به {target:.0f}$ حدود {needed:.0f} معاملهٔ برنده‌وبازنده لازم دارد")
            if days:
                verdict_parts.append(f"که با {edge.trades_per_day:g} معامله در روز می‌شود حدود {days:.0f} روز معاملاتی (~{days / 21:.1f} ماه).")
        verdict_parts.append(
            f"شبیه‌سازی: {simulation['reached_target_pct']}% از مسیرها به هدف رسیدند، "
            f"{simulation['ruin_pct']}% نصف سرمایه را از دست دادند."
        )
        if simulation["prob_edge_is_actually_negative_pct"] >= 5:
            warnings.append(
                f"وین‌ریت {edge.win_rate * 100:.0f}% فقط روی {edge.sample_size} معامله اندازه‌گیری شده است. "
                f"با این حجم نمونه، احتمال اینکه لبهٔ واقعی شما در عمل منفی باشد حدود "
                f"{simulation['prob_edge_is_actually_negative_pct']}% است "
                f"(بازهٔ محتمل وین‌ریت: {simulation['win_rate_p05']}% تا {simulation['win_rate_p95']}%). "
                "قبل از بزرگ‌کردن حجم، نمونهٔ بیشتری جمع کنید."
            )
    else:
        verdict_parts.append("انتظار ریاضی منفی است؛ این هدف با این استراتژی قابل دستیابی نیست.")

    return {
        "start": start,
        "target": target,
        "multiple": round(target / start, 2) if start > 0 else None,
        "edge": {
            "win_rate_pct": round(edge.win_rate * 100, 1),
            "net_rr": edge.net_rr,
            "risk_pct": edge.risk_pct,
            "trades_per_day": edge.trades_per_day,
            "expectancy_r": round(expectancy_r, 4),
            "expectancy_pct_per_trade": round(expectancy_pct, 4),
            "breakeven_win_rate_pct": round(100 / (1 + edge.net_rr), 1) if edge.net_rr > 0 else None,
            "kelly_pct": round(kelly * 100, 2),
            "half_kelly_pct": round(kelly * 50, 2),
            "measured_over_trades": edge.sample_size,
        },
        "path": {
            "trades_needed": round(needed) if needed else None,
            "trading_days_needed": round(days) if days else None,
            "months_needed": round(days / 21, 1) if days else None,
        },
        "deadline": {
            "days": deadline_days,
            "required_risk_pct": round(deadline_risk, 2) if deadline_risk else None,
            "within_system_cap": bool(deadline_risk is not None and deadline_risk <= 2.0),
        } if deadline_days else None,
        "simulation": simulation,
        "warnings": warnings,
        "verdict": " ".join(verdict_parts),
        "disclaimer": (
            "این اعداد نتیجهٔ حساب روی وین‌ریت و R:R‌ای است که شما وارد کرده‌اید، نه پیش‌بینی بازار. "
            "اگر لبهٔ واقعی کمتر از فرض باشد، نتیجه بدتر از این خواهد بود. هیچ تضمینی وجود ندارد."
        ),
    }


def risk_ladder(start: float, target: float, win_rate: float, net_rr: float, trades_per_day: float = 3.0) -> list[dict]:
    """Same edge at different risk levels — shows where growth stops paying for ruin."""
    out = []
    for risk in (0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0):
        edge = EdgeAssumption(win_rate, net_rr, risk, trades_per_day)
        sim = monte_carlo(start, target, edge, runs=1500)
        needed = trades_to_target(start, target, edge)
        out.append({
            "risk_pct": risk,
            "trades_needed": round(needed) if needed else None,
            "days_needed": round(needed / trades_per_day) if needed else None,
            "reached_target_pct": sim["reached_target_pct"],
            "ruin_pct": sim["ruin_pct"],
            "p95_max_drawdown_pct": sim["p95_max_drawdown_pct"],
        })
    return out
