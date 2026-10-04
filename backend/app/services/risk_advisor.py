"""
Automatic position sizing and risk selection.

The user does not know what risk percentage to use, what lot size to enter, or what any of
it means — and should not have to. This module decides it, from the only input a person
reliably knows: how much money is in the account.

It answers, for one concrete signal on one concrete symbol:

  * what fraction of the account to risk,              → `decide_risk_pct`
  * how many units/lots/coins that actually means,     → `size_position`
  * whether the venue will even accept that size,      → venue min_qty / step / min_notional
  * what it costs and what has to go right to profit,  → fees, break-even win rate
  * and when the honest answer is "do not take this trade".

Two things it deliberately does NOT do:

1. It never rounds a position UP to reach the venue minimum. On a small account the
   smallest tradable order frequently risks far more than is sane — e.g. 0.01 lots of
   EUR/USD on a $100 account with a 12-pip stop risks $12, i.e. 12% of the account. The
   honest output there is "not tradable", not a quietly oversized position.
2. It never promises an outcome. Every number is an arithmetic consequence of the signal's
   own stop distance and the venue's published fees — not a forecast.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.models import Direction, TradeSignal
from app.services import instruments as inst

# Risk appetite presets. "balanced" is the default for someone with no preference.
PROFILES: dict[str, float] = {
    "conservative": 0.25,
    "balanced": 0.50,
    "aggressive": 1.00,
}

# Nothing this module returns may ever exceed these, whatever the inputs say.
ABSOLUTE_MAX_RISK_PCT = 2.0
DAILY_LOSS_LIMIT_PCT = 3.0

# Per-instrument-tier multipliers on the base risk. Wider spreads and thinner books mean
# the same nominal risk carries more slippage, so the size comes down.
TIER_FACTOR: dict[str, float] = {
    "fx_major": 1.00,
    "fx_minor": 0.80,
    "fx_exotic": 0.60,
    "metal": 0.80,
    "crypto_major": 0.70,
    "crypto_alt": 0.50,
}


def instrument_tier(spec: inst.InstrumentSpec) -> str:
    if spec.kind == inst.CRYPTO:
        return "crypto_major" if spec.model.max_leverage >= 10 else "crypto_alt"
    if spec.kind == inst.METAL:
        return "metal"
    if spec.symbol in inst.MAJORS:
        return "fx_major"
    if spec.symbol in inst.MINORS:
        return "fx_minor"
    return "fx_exotic"


@dataclass
class AccountState:
    """What the engine needs to know about the account. Only `balance` is really required."""

    balance: float
    profile: str = "balanced"
    daily_pnl_pct: float = 0.0
    consecutive_losses: int = 0
    open_positions: int = 0


def decide_risk_pct(
    spec: inst.InstrumentSpec,
    signal: TradeSignal,
    account: AccountState,
    volatility_regime: str = "normal",
) -> tuple[float, list[dict]]:
    """Choose the risk-per-trade percentage, and show every step of the reasoning."""
    steps: list[dict] = []
    base = PROFILES.get(account.profile, PROFILES["balanced"])
    risk = base
    steps.append({
        "factor": "پروفایل ریسک",
        "effect": f"پایه {base}%",
        "why": f"پروفایل «{account.profile}» — درصد پایهٔ ریسک هر معامله",
    })

    tier = instrument_tier(spec)
    factor = TIER_FACTOR[tier]
    risk *= factor
    steps.append({
        "factor": "ردهٔ نماد",
        "effect": f"×{factor:g} → {risk:.3g}%",
        "why": f"{spec.display} در ردهٔ {tier} — اسپرد و نقدشوندگی این رده",
    })

    # Conviction: a signal well above its own threshold earns a little more size.
    threshold = spec.model.threshold(
        signal.reasons and "5m" or "5m"  # threshold is per-timeframe; caller passes it in signal flow
    )
    margin = signal.confidence - threshold
    if margin >= 13:
        conviction = 1.30
    elif margin >= 8:
        conviction = 1.15
    elif margin >= 3:
        conviction = 1.00
    else:
        conviction = 0.85
    risk *= conviction
    steps.append({
        "factor": "قدرت سیگنال",
        "effect": f"×{conviction:g} → {risk:.3g}%",
        "why": f"امتیاز {signal.confidence:.0f} در برابر آستانهٔ {threshold:.0f} (اختلاف {margin:+.0f})",
    })

    if volatility_regime == "shock":
        risk *= 0.5
        steps.append({
            "factor": "شوک نوسان",
            "effect": f"×0.5 → {risk:.3g}%",
            "why": "ATR بیش از ۳ برابر میانه — حد ضرر راحت‌تر زده می‌شود",
        })

    if account.consecutive_losses >= 3:
        risk *= 0.5
        steps.append({
            "factor": "باخت پیاپی",
            "effect": f"×0.5 → {risk:.3g}%",
            "why": f"{account.consecutive_losses} باخت پشت سر هم — کاهش حجم تا بازگشت ثبات",
        })

    if account.daily_pnl_pct <= -1.5:
        risk *= 0.5
        steps.append({
            "factor": "افت روزانه",
            "effect": f"×0.5 → {risk:.3g}%",
            "why": f"امروز {account.daily_pnl_pct:.1f}% در ضرر — نصف‌کردن حجم",
        })

    if account.open_positions >= 2:
        risk *= 0.6
        steps.append({
            "factor": "پوزیشن‌های باز",
            "effect": f"×0.6 → {risk:.3g}%",
            "why": f"{account.open_positions} پوزیشن باز — ریسک کل حساب تجمیع می‌شود",
        })

    # Remaining daily budget is a hard ceiling, not a suggestion.
    remaining = DAILY_LOSS_LIMIT_PCT + min(account.daily_pnl_pct, 0.0)
    if remaining <= 0:
        steps.append({
            "factor": "سقف ضرر روزانه",
            "effect": "→ 0%",
            "why": f"حد ضرر روزانهٔ {DAILY_LOSS_LIMIT_PCT}% مصرف شده — امروز معامله نکنید",
        })
        return 0.0, steps
    if risk > remaining:
        risk = remaining
        steps.append({
            "factor": "سقف ضرر روزانه",
            "effect": f"→ {risk:.3g}%",
            "why": f"فقط {remaining:.2f}% از بودجهٔ ضرر امروز باقی مانده است",
        })

    if risk > ABSOLUTE_MAX_RISK_PCT:
        risk = ABSOLUTE_MAX_RISK_PCT
        steps.append({
            "factor": "سقف مطلق",
            "effect": f"→ {risk}%",
            "why": f"ریسک هر معامله هرگز از {ABSOLUTE_MAX_RISK_PCT}% بیشتر نمی‌شود",
        })

    return round(risk, 3), steps


def size_position(
    spec: inst.InstrumentSpec,
    signal: TradeSignal,
    account: AccountState,
    risk_pct: float,
) -> dict:
    """Turn a risk percentage into an executable quantity — or explain why it is not."""
    if signal.action not in (Direction.BUY, Direction.SELL) or not signal.entry or not signal.stop_loss:
        return {"tradable": False, "reason": "سیگنال ورودی فعالی وجود ندارد."}

    entry, stop = signal.entry, signal.stop_loss
    stop_distance = abs(entry - stop)
    if stop_distance <= 0:
        return {"tradable": False, "reason": "فاصلهٔ حد ضرر صفر است."}
    if risk_pct <= 0 or account.balance <= 0:
        return {"tradable": False, "reason": "بودجهٔ ریسک برای این معامله صفر است."}

    risk_cash = account.balance * (risk_pct / 100)
    ideal_units = risk_cash / stop_distance
    units = spec.quantize_qty(ideal_units)

    warnings: list[str] = []
    blockers: list[str] = []

    # ── venue minimum check — the part that actually bites small accounts ──
    min_units = spec.min_qty if spec.min_qty > 0 else 0.0
    if spec.min_notional > 0 and entry > 0:
        min_units = max(min_units, spec.min_notional / entry)
    min_units = spec.quantize_qty(min_units * 1.0000001) if min_units > 0 else 0.0

    if units <= 0 or not spec.meets_min_notional(units, entry):
        if min_units <= 0:
            return {"tradable": False, "reason": "حداقل حجم قابل معاملهٔ این نماد مشخص نیست."}
        forced_risk = min_units * stop_distance
        forced_pct = forced_risk / account.balance * 100
        return {
            "tradable": False,
            "reason": (
                f"حجم مناسب برای این ریسک {ideal_units:.6g} {spec.unit_label} است، ولی حداقل حجم "
                f"قابل معامله {min_units:.6g} است. کوچک‌ترین معاملهٔ ممکن "
                f"{forced_risk:,.2f}$ ریسک دارد یعنی {forced_pct:.1f}% حساب — خیلی بیشتر از "
                f"{risk_pct}% هدف. این معامله با این موجودی قابل انجام نیست."
            ),
            "ideal_units": round(ideal_units, 8),
            "min_units": round(min_units, 8),
            "forced_risk_cash": round(forced_risk, 2),
            "forced_risk_pct": round(forced_pct, 2),
            "min_balance_needed": round(forced_risk / (risk_pct / 100), 2),
        }

    # Realised risk after rounding DOWN to the venue step.
    actual_risk_cash = units * stop_distance
    actual_risk_pct = actual_risk_cash / account.balance * 100
    notional = units * entry
    leverage = notional / account.balance if account.balance else 0.0
    margin = notional / spec.model.max_leverage if spec.model.max_leverage else notional

    cost = spec.round_trip_cost(entry) * units
    reward_price = abs((signal.take_profit or entry) - entry)
    gross_reward = reward_price * units
    net_reward = gross_reward - cost
    rr_net = net_reward / actual_risk_cash if actual_risk_cash > 0 else 0.0
    breakeven_wr = 100 / (1 + rr_net) if rr_net > 0 else 100.0

    if leverage > spec.model.max_leverage:
        blockers.append(
            f"اهرم لازم {leverage:.1f}x از سقف مدل این نماد ({spec.model.max_leverage}x) بیشتر است."
        )
    if cost >= gross_reward:
        blockers.append(
            f"هزینهٔ اجرا ({cost:,.2f}$) از کل سود هدف ({gross_reward:,.2f}$) بیشتر است."
        )
    if actual_risk_pct > ABSOLUTE_MAX_RISK_PCT:
        blockers.append(f"ریسک واقعی {actual_risk_pct:.2f}% از سقف {ABSOLUTE_MAX_RISK_PCT}% عبور کرد.")

    if actual_risk_pct < risk_pct * 0.6:
        warnings.append(
            f"به‌خاطر پله‌های حجم صرافی، ریسک واقعی ({actual_risk_pct:.2f}%) از هدف "
            f"({risk_pct}%) کمتر شد — عمداً رو به پایین گرد شده است."
        )
    if rr_net < 1.0:
        warnings.append(
            f"بعد از کسر هزینه، نسبت سود به ضرر {rr_net:.2f} است؛ برای سربه‌سر شدن به "
            f"وین‌ریت بالای {breakeven_wr:.0f}% نیاز دارید."
        )

    lots = units / 100_000 if spec.kind == inst.FOREX else (units / 100 if spec.kind == inst.METAL else None)

    return {
        "tradable": not blockers,
        "blockers": blockers,
        "warnings": warnings,
        "risk_pct_target": risk_pct,
        "risk_cash_target": round(risk_cash, 2),
        "units": round(units, 8),
        "unit_label": spec.unit_label,
        "lots": round(lots, 4) if lots is not None else None,
        "ideal_units": round(ideal_units, 8),
        "min_units": round(min_units, 8),
        "qty_step": spec.qty_step,
        "stop_distance": spec.round_price(stop_distance),
        "stop_distance_units": round(spec.to_cost_units(stop_distance, entry), 1),
        "cost_unit": spec.cost_unit,
        "risk_cash": round(actual_risk_cash, 2),
        "risk_pct": round(actual_risk_pct, 3),
        "notional": round(notional, 2),
        "leverage": round(leverage, 2),
        "max_leverage": spec.model.max_leverage,
        "margin_required": round(margin, 2),
        "execution_cost": round(cost, 4),
        "gross_reward": round(gross_reward, 2),
        "net_reward": round(net_reward, 2),
        "net_rr": round(rr_net, 2),
        "breakeven_win_rate": round(breakeven_wr, 1),
    }


def advise(
    spec: inst.InstrumentSpec,
    signal: TradeSignal,
    account: AccountState,
    volatility_regime: str = "normal",
    timeframe: str = "5m",
    manual_risk_pct: float | None = None,
) -> dict:
    """Full automatic risk decision for one signal, with its reasoning."""
    threshold = spec.model.threshold(timeframe)
    if manual_risk_pct is not None:
        risk_pct = min(max(manual_risk_pct, 0.0), ABSOLUTE_MAX_RISK_PCT)
        steps = [{
            "factor": "ورودی دستی",
            "effect": f"{risk_pct}%",
            "why": "درصد ریسک را خودتان مشخص کرده‌اید؛ محاسبهٔ خودکار دور زده شد.",
        }]
    else:
        risk_pct, steps = decide_risk_pct(spec, signal, account, volatility_regime)
        # `decide_risk_pct` uses the 5m threshold as a default; correct it for the real one.
        margin = signal.confidence - threshold
        steps = [
            s if s["factor"] != "قدرت سیگنال" else {
                **s,
                "why": f"امتیاز {signal.confidence:.0f} در برابر آستانهٔ {threshold:.0f} ({margin:+.0f})",
            }
            for s in steps
        ]

    sizing = size_position(spec, signal, account, risk_pct)
    return {
        "auto": manual_risk_pct is None,
        "balance": account.balance,
        "profile": account.profile,
        "risk_pct": risk_pct,
        "reasoning": steps,
        "sizing": sizing,
        "guardrails": {
            "max_risk_per_trade_pct": ABSOLUTE_MAX_RISK_PCT,
            "daily_loss_limit_pct": DAILY_LOSS_LIMIT_PCT,
            "remaining_daily_budget_pct": round(
                max(0.0, DAILY_LOSS_LIMIT_PCT + min(account.daily_pnl_pct, 0.0)), 2
            ),
            "max_concurrent_positions": 3,
            "note": (
                "این اعداد نتیجهٔ حسابِ فاصلهٔ حد ضررِ همین سیگنال و کارمزد اعلام‌شدهٔ همین "
                "بازار است، نه پیش‌بینی سود. هیچ سفارشی ارسال نمی‌شود."
            ),
        },
    }
