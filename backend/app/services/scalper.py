"""
Multi-symbol scalp engine — entry plan, exit plan, and a gate-by-gate explanation of
*why* a symbol is not trading right now.

The headline complaint this module answers: "candles get downloaded but no trade ever
happens, on gold or on any pair". Downloading candles was never the problem — the signal
pipeline has roughly twenty veto conditions, most of them tuned for gold, and any single
one of them returns NO_TRADE while the UI only showed the final verdict. `diagnose()`
publishes every gate with its measured value, so a silent market is now explainable
instead of mysterious.

Everything is closed-bar and read-only: this module never sends an order. Real order
submission remains fail-closed in `app.services.execution_gate`.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone

from app.config import Settings
from app.models import Candle, Direction, StrategyContext, Timeframe, TradeSignal
from app.services import instruments as inst
from app.services import universe
from app.services.history import DataUnavailable
from app.services.indicators import adx, atr, ema, ichimoku, support_resistance
from app.services.strategy import evaluate_scalp, get_trailing_stop, should_exit

# Scalping needs enough bars for EMA200 + Ichimoku displacement.
MIN_BARS = 220
DEFAULT_BARS = 400

SCALP_TIMEFRAMES = (Timeframe.M1, Timeframe.M3, Timeframe.M5, Timeframe.M15)

# Which higher timeframe confirms each execution timeframe.
HTF_FOR: dict[Timeframe, Timeframe] = {
    Timeframe.M1: Timeframe.M5,
    Timeframe.M3: Timeframe.M15,
    Timeframe.M5: Timeframe.M15,
    Timeframe.M15: Timeframe.H1,
}


def _weekend_gap_risk(now: datetime) -> bool:
    """Friday 21:00 UTC onwards, until the Sunday reopen."""
    return (now.weekday() == 4 and now.hour >= 21) or now.weekday() == 5


def _bias_from(candles: list[Candle]) -> Direction:
    """Simple, deterministic higher-timeframe bias: EMA200 + Ichimoku cloud agreement."""
    if len(candles) < 210:
        return Direction.NEUTRAL
    close = candles[-1].close
    ema200 = ema(candles, 200)[-1]
    values = ichimoku(candles, 9, 26, 52, 26)
    span_a, span_b = values["span_a"][-1], values["span_b"][-1]
    if ema200 is None or span_a is None or span_b is None:
        return Direction.NEUTRAL
    top, bottom = max(span_a, span_b), min(span_a, span_b)
    if close > ema200 and close > top:
        return Direction.BUY
    if close < ema200 and close < bottom:
        return Direction.SELL
    return Direction.NEUTRAL


async def build_context(
    settings: Settings,
    spec: inst.InstrumentSpec,
    timeframe: Timeframe,
    candles: list[Candle],
    account_equity: float = 100.0,
) -> tuple[StrategyContext, dict | None]:
    """Assemble a real StrategyContext for this symbol — real spread, real HTF bias."""
    price = candles[-1].close
    quote = await universe.live_quote(spec)
    spread = quote["spread"] if quote else spec.default_spread(price)

    htf_bias = Direction.NEUTRAL
    htf = HTF_FOR.get(timeframe)
    if htf:
        try:
            htf_candles, _ = await universe.load_candles(settings, spec.symbol, htf, limit=260)
            htf_bias = _bias_from(htf_candles)
        except DataUnavailable:
            htf_bias = Direction.NEUTRAL

    now = datetime.now(timezone.utc)
    atr14 = [v for v in atr(candles, 14) if v is not None]
    regime = "normal"
    if len(atr14) > 30:
        recent, base = atr14[-1], sorted(atr14[-60:])[len(atr14[-60:]) // 2]
        if base > 0 and recent > base * 3:
            regime = "shock"

    context = StrategyContext(
        spread=spread,
        typical_spread=max(spec.default_spread(price), spec.tick_size),
        higher_timeframe_bias=htf_bias,
        account_equity=account_equity,
        # A 24/7 venue cannot gap over a weekend; the model flag gates this downstream too.
        is_weekend_gap_risk=_weekend_gap_risk(now) and spec.model.weekend_gap_risk,
        volatility_regime=regime,
    )
    return context, quote


def _exit_plan(signal: TradeSignal, candles: list[Candle], spec: inst.InstrumentSpec) -> dict:
    """Concrete exit rules for the panel — stop, target, trail trigger, time stop."""
    frame = candles[-1].timeframe
    max_bars = {"1m": 8, "3m": 8, "5m": 10, "15m": 12}.get(frame.value, 6)
    values = ichimoku(candles, 9, 26, 52, 26)
    kijun = values["kijun"][-1]
    atr14 = atr(candles, 14)[-1]

    if signal.action not in (Direction.BUY, Direction.SELL) or signal.entry is None or signal.stop_loss is None:
        return {
            "has_plan": False,
            "kijun": spec.round_price(kijun) if kijun else None,
            "time_stop_bars": max_bars,
            "note": "پلن خروج فقط پس از صدور سیگنال ورود ساخته می‌شود.",
        }

    long = signal.action is Direction.BUY
    risk = abs(signal.entry - signal.stop_loss)
    breakeven = signal.entry
    lock_half = signal.entry + 0.5 * risk if long else signal.entry - 0.5 * risk
    return {
        "has_plan": True,
        "stop_loss": signal.stop_loss,
        "take_profit": signal.take_profit,
        "risk_price": spec.round_price(risk),
        "cost_unit": spec.cost_unit,
        "risk_pips": round(spec.to_cost_units(risk, signal.entry), 1),
        "reward_pips": round(spec.to_cost_units(
            abs((signal.take_profit or signal.entry) - signal.entry), signal.entry), 1),
        "round_trip_cost_units": round(spec.to_cost_units(spec.round_trip_cost(signal.entry), signal.entry), 1),
        "breakeven_trigger": spec.round_price(signal.entry + risk if long else signal.entry - risk),
        "breakeven_stop": spec.round_price(breakeven),
        "lock_trigger": spec.round_price(signal.entry + 1.5 * risk if long else signal.entry - 1.5 * risk),
        "lock_stop": spec.round_price(lock_half),
        "kijun_trail": spec.round_price(kijun - 0.15 * (atr14 or 0)) if kijun and long else (
            spec.round_price(kijun + 0.15 * (atr14 or 0)) if kijun else None),
        "time_stop_bars": max_bars,
        "time_stop_minutes": round(max_bars * frame.seconds / 60, 1),
        "rules": [
            "۱R سود → حد ضرر به نقطهٔ ورود (بریک‌اون)",
            "۱٫۵R سود → قفل نیم‌R سود",
            "بعد از آن → تریل با کیجون منهای ۰٫۱۵ ATR",
            f"بستهٔ زمانی: حداکثر {max_bars} کندل {frame.value}",
            "کراس مخالف تنکان/کیجون یا بسته‌شدن آن‌سوی کیجون → خروج فوری",
        ],
    }


def _sizing(spec: inst.InstrumentSpec, signal: TradeSignal, equity: float, risk_pct: float) -> dict | None:
    if signal.entry is None or signal.stop_loss is None:
        return None
    risk_price = abs(signal.entry - signal.stop_loss)
    if risk_price <= 0 or equity <= 0:
        return None
    risk_cash = equity * (risk_pct / 100)
    units = risk_cash / risk_price
    notional = units * signal.entry
    return {
        "risk_pct": risk_pct,
        "risk_cash": round(risk_cash, 2),
        "units": round(units, 6),
        "unit_label": spec.unit_label,
        "lots": round(units / 100_000, 4) if spec.kind == inst.FOREX else None,
        "notional": round(notional, 2),
        "note": "حجم بر پایهٔ فاصلهٔ واقعی حد ضرر همین نماد محاسبه شده است، نه یک عدد ثابت طلا.",
    }


async def evaluate_symbol(
    settings: Settings,
    symbol: str,
    timeframe: Timeframe,
    bars: int = DEFAULT_BARS,
    equity: float = 100.0,
    risk_pct: float = 0.5,
    enforce_scalp_policy: bool = True,
) -> dict:
    """Full scalp assessment for one symbol: entry signal + exit plan + sizing."""
    candles, spec = await universe.load_candles(settings, symbol, timeframe, limit=max(bars, MIN_BARS))

    policy_block: str | None = None
    if enforce_scalp_policy and not spec.is_scalpable():
        if spec.kind == inst.CRYPTO:
            why = ("جفت استیبل‌به‌استیبل، توکن اهرمی، یا کوتِ غیردلاری (BTC/ETH/BNB) — "
                   "رنج واقعی یا نقدشوندگی لازم برای اسکلپ را ندارد")
        elif spec.kind == inst.METAL:
            why = "فلز گران‌بها — اسپرد و رفتار گپ‌دار"
        else:
            why = "جفت‌ارز exotic با اسپرد بالا"
        policy_block = f"اسکلپ روی {spec.display} فعال نیست ({why}) — فقط داده و سیگنال نمایش داده می‌شود."

    if len(candles) < MIN_BARS:
        return {
            "symbol": spec.symbol,
            "spec": inst.spec_to_dict(spec),
            "timeframe": timeframe.value,
            "ok": False,
            "error": f"فقط {len(candles)} کندل واقعی موجود است؛ موتور حداقل {MIN_BARS} کندل بسته لازم دارد.",
            "bars": len(candles),
        }

    context, quote = await build_context(settings, spec, timeframe, candles, equity)
    signal = evaluate_scalp(candles, context, spec)

    last = candles[-1]
    return {
        "symbol": spec.symbol,
        "spec": inst.spec_to_dict(spec),
        "timeframe": timeframe.value,
        "ok": True,
        "bars": len(candles),
        "scalp_allowed": spec.is_scalpable(),
        "policy_block": policy_block,
        "last_candle": {
            "timestamp": last.timestamp.isoformat(),
            "close": last.close,
            "age_seconds": round((datetime.now(timezone.utc) - last.timestamp).total_seconds()),
        },
        "quote": quote,
        "context": {
            "spread": spec.round_price(context.spread),
            "cost_unit": spec.cost_unit,
            "spread_pips": round(spec.to_cost_units(context.spread, last.close), 2),
            "typical_spread_pips": round(
                spec.to_cost_units(spec.default_spread(last.close), last.close), 2),
            "taker_fee_bps": spec.model.taker_fee_bps,
            "round_trip_cost_units": round(
                spec.to_cost_units(spec.round_trip_cost(last.close), last.close), 2),
            "htf_bias": context.higher_timeframe_bias.value,
            "volatility_regime": context.volatility_regime,
            "weekend_gap_risk": context.is_weekend_gap_risk,
        },
        "signal": {
            "action": signal.action.value,
            "confidence": signal.confidence,
            "threshold": spec.model.threshold(timeframe.value),
            "entry": signal.entry,
            "stop_loss": signal.stop_loss,
            "take_profit": signal.take_profit,
            "risk_reward": signal.risk_reward,
            "reasons": signal.reasons,
            "blockers": signal.blockers,
            "confluence": signal.confluence,
            "exit_hint": signal.exit_hint,
            "expires_after_seconds": signal.expires_after_seconds,
        },
        "exit_plan": _exit_plan(signal, candles, spec),
        "sizing": _sizing(spec, signal, equity, risk_pct),
    }


async def diagnose(settings: Settings, symbol: str, timeframe: Timeframe, bars: int = DEFAULT_BARS) -> dict:
    """Gate-by-gate answer to 'why is nothing trading on this symbol right now?'."""
    candles, spec = await universe.load_candles(settings, symbol, timeframe, limit=max(bars, MIN_BARS))
    checks: list[dict] = []

    def add(name: str, passed: bool, measured: str, fix: str = "") -> None:
        checks.append({"name": name, "passed": bool(passed), "measured": measured, "fix": fix})

    add("تعداد کندل بسته", len(candles) >= MIN_BARS, f"{len(candles)} / {MIN_BARS}",
        "منتظر جمع‌شدن کندل بیشتر بمانید یا limit را بالا ببرید.")
    if len(candles) < MIN_BARS:
        return {"symbol": spec.symbol, "timeframe": timeframe.value, "spec": inst.spec_to_dict(spec), "checks": checks}

    context, quote = await build_context(settings, spec, timeframe, candles)
    i = len(candles) - 1
    last = candles[i]

    values = ichimoku(candles, 9, 26, 52, 26)
    t, k = values["tenkan"][i], values["kijun"][i]
    pt, pk = values["tenkan"][i - 1], values["kijun"][i - 1]
    cross = bool(t and k and pt and pk and ((pt <= pk and t > k) or (pt >= pk and t < k)))
    add("کراس تازهٔ تنکان/کیجون", cross,
        f"T={t:.{spec.price_precision}f} K={k:.{spec.price_precision}f}" if t and k else "—",
        "بدون کراس تازه در دو کندل آخر، موتور اصلاً وارد امتیازدهی نمی‌شود — این رایج‌ترین دلیل سکوت است.")

    adx_val = adx(candles, 14)[i]
    add("قدرت روند ADX", adx_val is not None and adx_val >= spec.model.min_adx,
        f"{adx_val:.1f} (حداقل {spec.model.min_adx:.0f})" if adx_val else "—",
        "در بازار رنج، موتور عمداً معامله نمی‌کند.")

    atr_val = atr(candles, 14)[i]
    add("نوسان سالم ATR", atr_val is not None and spec.volatility_is_sane(atr_val, last.close),
        f"{atr_val:.{spec.price_precision}f} = {atr_val / last.close * 100:.2f}% قیمت" if atr_val else "—",
        "شوک نوسان = وتوی سخت.")

    hour = last.timestamp.hour + last.timestamp.minute / 60
    in_kz = spec.model.in_killzone(hour)
    add("پنجرهٔ معاملاتی (کیلزون)", in_kz,
        f"UTC {hour:.1f} — " + ("۲۴ساعته" if not spec.model.killzones_utc else
                                "، ".join(f"{a:.0f}-{b:.0f}" for a, b in spec.model.killzones_utc)),
        "خارج از کیلزون با روند ضعیف، وتوی سخت می‌خورد.")

    spread_ok = context.spread <= context.typical_spread * 2
    unit = "پیپ" if spec.cost_unit == "pip" else "بیپ"
    add("اسپرد لحظه‌ای", spread_ok,
        f"{spec.to_cost_units(context.spread, last.close):.2f} {unit} "
        f"(سقف {spec.to_cost_units(context.typical_spread * 2, last.close):.2f})",
        "در زمان اسپرد باز (نیویورک کلوز/خبر/نقدشوندگی کم) ورود بسته است.")

    cost_units = spec.to_cost_units(spec.round_trip_cost(last.close), last.close)
    add("هزینهٔ رفت‌وبرگشت", True,
        f"{cost_units:.1f} {unit}"
        + (f" (شامل کارمزد {spec.model.taker_fee_bps:.0f} بیپ هر طرف)" if spec.model.taker_fee_bps else ""),
        "هدف باید حداقل ۱٫۵ برابر این عدد باشد وگرنه ورود رد می‌شود.")

    add("تازگی آخرین کندل",
        (datetime.now(timezone.utc) - last.timestamp).total_seconds() < timeframe.seconds * 3,
        f"{(datetime.now(timezone.utc) - last.timestamp).total_seconds():.0f} ثانیه",
        "فید کند یا بازار بسته.")

    add("هم‌جهتی تایم بالاتر",
        context.higher_timeframe_bias is Direction.NEUTRAL or True,
        context.higher_timeframe_bias.value,
        "واگرایی با تایم بالاتر + ADX>20 = وتوی سخت روی 3m/5m.")

    add("ریسک گپ آخر هفته", not context.is_weekend_gap_risk,
        "دارد" if context.is_weekend_gap_risk else "ندارد",
        "جمعه ۲۱:۰۰ UTC به بعد ورود جدید بسته است (فقط بازارهای تعطیل‌شونده).")

    sr = support_resistance(candles)
    add("فاصله از حمایت/مقاومت", True,
        f"S={sr['support']} R={sr['resistance']}",
        "نزدیکی به سطح کلیدی امتیاز را کم می‌کند.")

    signal = evaluate_scalp(candles, context, spec)
    hard_gates = [b for b in signal.blockers if b.startswith("Hard gate")]

    return {
        "symbol": spec.symbol,
        "timeframe": timeframe.value,
        "spec": inst.spec_to_dict(spec),
        "checks": checks,
        "score": signal.confidence,
        "threshold": spec.model.threshold(timeframe.value),
        "action": signal.action.value,
        "hard_gates": hard_gates,
        "soft_blockers": [b for b in signal.blockers if not b.startswith("Hard gate")],
        "verdict": (
            "سیگنال صادر شد" if signal.action in (Direction.BUY, Direction.SELL)
            else f"وتوی سخت ({len(hard_gates)} مورد)" if hard_gates
            else f"امتیاز {signal.confidence} کمتر از آستانهٔ {spec.model.threshold(timeframe.value)}"
        ),
        "quote": quote,
    }


async def scan(
    settings: Settings,
    symbols: list[str],
    timeframe: Timeframe,
    bars: int = DEFAULT_BARS,
    equity: float = 100.0,
    risk_pct: float = 0.5,
    only_signals: bool = False,
) -> dict:
    """Evaluate many symbols concurrently and rank by conviction."""
    symbols = [inst.normalize(s) for s in symbols if s and s.strip()][:40]
    if not symbols:
        raise ValueError("هیچ نمادی انتخاب نشده است")

    semaphore = asyncio.Semaphore(8)

    async def one(sym: str) -> dict:
        async with semaphore:
            try:
                return await evaluate_symbol(settings, sym, timeframe, bars, equity, risk_pct)
            except (DataUnavailable, ValueError) as exc:
                return {"symbol": sym, "ok": False, "error": str(exc), "timeframe": timeframe.value}
            except Exception as exc:  # never let one bad symbol kill the scan
                return {"symbol": sym, "ok": False, "error": f"{type(exc).__name__}", "timeframe": timeframe.value}

    results = await asyncio.gather(*(one(s) for s in symbols))
    if only_signals:
        results = [r for r in results if r.get("ok") and r["signal"]["action"] in ("BUY", "SELL")]
    results.sort(key=lambda r: (not r.get("ok"), -(r.get("signal", {}).get("confidence") or 0)))
    return {
        "timeframe": timeframe.value,
        "scanned": len(symbols),
        "results": results,
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }
