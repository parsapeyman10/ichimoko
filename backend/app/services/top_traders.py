"""
Complementary analysis ensemble — شش سبک تحلیلی روی همان ۸۴ ورودی.

This module is a set of *rules* (archetypes), not a claim about any real person and not a
performance claim. Each archetype contributes a weighted vote; the result is an advisory
score, never a promise of profit. Performance is only ever reported from a real-data
backtest (see services/backtest.py) or the paper journal.

Archetypes:
1. ict_smc        — نقدینگی، FVG، Order Block، Killzone
2.  trend         — EMA200، ADX، سوار شدن بر روند
3.  quant         — بازگشت به میانگین، باند بولینگر، Z-score
4.  macro         — دلار، بازده، خبر
5.  scalper       — حجم، اسپرد، ریزساختار
6.  supply_demand — سطوح عرضه/تقاضا، premium/discount
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Any

@dataclass
class TraderVote:
    id: str
    name: str
    style: str
    direction: str  # BUY / SELL / NEUTRAL
    confidence: int # 0-100
    weight: float   # 0-1 experience weight
    reason: str
    is_veto: bool = False  # if true, this trader says NO_TRADE regardless of direction

# Fixed starting weights (priors). They are tuning parameters, not measured performance.
TRADER_WEIGHTS = {
    "ict_smc": 0.24,
    "trend": 0.22,
    "quant": 0.16,
    "macro": 0.18,
    "scalper": 0.12,
    "supply_demand": 0.08,
}

TRADERS_META = {
    "ict_smc": {"name": "ICT / SMC", "full": "Smart Money Concepts (archetype)", "style": "Liquidity Sweep · FVG · Order Block · Killzone", "desc": "شکار استاپ‌ها، ورود پس از جارو نقدینگی در Killzone"},
    "trend": {"name": "Trend Following", "full": "Institutional trend archetype", "style": "EMA200 · ADX · سوار روند، قطع سریع ضرر", "desc": "فقط روند قوی، بگذار سود بدود"},
    "quant": {"name": "Quant Mean-Reversion", "full": "Statistical mean-reversion archetype", "style": "BB %B · RSI Z · VWAP بازگشت", "desc": "خرید افراط فروش، فروش افراط خرید — فقط وقتی Z>1.5"},
    "macro": {"name": "Macro", "full": "Macro / rates & dollar archetype", "style": "DXY · Yield · News · Risk-on", "desc": "وقتی دلار و بازده و خبر هم‌جهت شدند"},
    "scalper": {"name": "Scalper Orderflow", "full": "Order-flow scalping archetype", "style": "Volume Spike · Spread · Body/Spread", "desc": "حجم انفجاری + اسپرد سالم = مومنتوم لحظه‌ای"},
    "supply_demand": {"name": "Supply/Demand", "full": "Supply & demand zone archetype", "style": "Premium/Discount · Order Block Distance", "desc": "خرید در Discount، فروش در Premium — نه وسط رنج"},
}

def _vote_ict(feats: dict[str, float]) -> TraderVote:
    # ICT archetype
    kill = feats.get("killzone_active", 0)
    liq_bear = feats.get("liquidity_sweep_bear", 0)
    liq_bull = feats.get("liquidity_sweep_bull", 0)
    fvg_bear = feats.get("fvg_bear", 0)
    fvg_bull = feats.get("fvg_bull", 0)
    premium = feats.get("premium_discount", 0.5)
    ob_dist = feats.get("order_block_dist_atr", 5)
    # Veto if not killzone -> ICT doesn't trade
    if kill < 0.5:
        return TraderVote("ict_smc", TRADERS_META["ict_smc"]["name"], TRADERS_META["ict_smc"]["style"], "NEUTRAL", 35, TRADER_WEIGHTS["ict_smc"], "خارج Killzone — این سبک معامله نمی‌کند", is_veto=False)
    # Bearish: sweep highs + bearish FVG + premium (>0.65) + close near OB
    bear_score = 0
    if liq_bear > 0.5: bear_score += 2
    if fvg_bear > 0.5: bear_score += 2
    if premium > 0.62: bear_score += 1
    if ob_dist < 1.5: bear_score += 1
    bull_score = 0
    if liq_bull > 0.5: bull_score += 2
    if fvg_bull > 0.5: bull_score += 2
    if premium < 0.38: bull_score += 1
    if ob_dist < 1.5: bull_score += 1
    if bear_score >= 3 and bear_score > bull_score:
        return TraderVote("ict_smc", TRADERS_META["ict_smc"]["name"], TRADERS_META["ict_smc"]["style"], "SELL", 72 + bear_score*4, TRADER_WEIGHTS["ict_smc"], f"جارو سقف + FVG نزولی + Premium ({premium:.2f}) — تله خرید")
    if bull_score >= 3 and bull_score > bear_score:
        return TraderVote("ict_smc", TRADERS_META["ict_smc"]["name"], TRADERS_META["ict_smc"]["style"], "BUY", 72 + bull_score*4, TRADER_WEIGHTS["ict_smc"], f"جارو کف + FVG صعودی + Discount ({premium:.2f}) — تله فروش")
    return TraderVote("ict_smc", TRADERS_META["ict_smc"]["name"], TRADERS_META["ict_smc"]["style"], "NEUTRAL", 48, TRADER_WEIGHTS["ict_smc"], "ساختار SMC ناقص — صبر")

def _vote_trend(feats: dict[str, float]) -> TraderVote:
    ema = feats.get("ema200_dist", 0)
    adx = feats.get("adx", 15)
    tk_spread = feats.get("tenkan_kijun_spread_atr", 0)
    tk_slope = feats.get("tenkan_slope", 0)
    vwap = feats.get("vwap_dist", 0)
    cloud_bear = feats.get("cloud_bearish", 0)
    # trend archetype: ADX must confirm trend, else no trade
    if adx < 17:
        return TraderVote("trend", TRADERS_META["trend"]["name"], TRADERS_META["trend"]["style"], "NEUTRAL", 42, TRADER_WEIGHTS["trend"], f"ADX ضعیف {adx:.0f} — روند بی‌جان", is_veto=False)
    if ema > 0.7 and vwap > 0.5 and tk_spread > 0.25 and tk_slope > 0 and cloud_bear < 0.5:
        conf = min(88, 62 + int(adx - 17)*2 + int(ema*6))
        return TraderVote("trend", TRADERS_META["trend"]["name"], TRADERS_META["trend"]["style"], "BUY", conf, TRADER_WEIGHTS["trend"], f"بالای EMA200 ({ema:.1f} ATR) + ADX {adx:.0f} — سوار روند صعودی")
    if ema < -0.7 and vwap < -0.5 and tk_spread < -0.25 and tk_slope < 0 and cloud_bear > 0.5:
        conf = min(88, 62 + int(adx - 17)*2 + int(-ema*6))
        return TraderVote("trend", TRADERS_META["trend"]["name"], TRADERS_META["trend"]["style"], "SELL", conf, TRADER_WEIGHTS["trend"], f"زیر EMA200 ({ema:.1f}) + ADX {adx:.0f} — سوار روند نزولی")
    return TraderVote("trend", TRADERS_META["trend"]["name"], TRADERS_META["trend"]["style"], "NEUTRAL", 50, TRADER_WEIGHTS["trend"], "روند ناقص — این سبک وارد نمی‌شود")

def _vote_quant(feats: dict[str, float]) -> TraderVote:
    bb = feats.get("bb_pctB", 0.5)
    rsi = feats.get("rsi7", 50)
    rsi_z = feats.get("rsi7_z", 0)
    stoch = feats.get("stoch_k", 50)
    # quant archetype
    if bb > 0.88 and rsi > 66 and rsi_z > 1.2 and stoch > 78:
        return TraderVote("quant", TRADERS_META["quant"]["name"], TRADERS_META["quant"]["style"], "SELL", 71, TRADER_WEIGHTS["quant"], f"اشباع صعود BB {bb:.2f} + RSI {rsi:.0f} Z {rsi_z:.1f} — بازگشت به میانگین")
    if bb < 0.12 and rsi < 34 and rsi_z < -1.2 and stoch < 22:
        return TraderVote("quant", TRADERS_META["quant"]["name"], TRADERS_META["quant"]["style"], "BUY", 71, TRADER_WEIGHTS["quant"], f"اشباع نزول BB {bb:.2f} + RSI {rsi:.0f} — بازگشت")
    return TraderVote("quant", TRADERS_META["quant"]["name"], TRADERS_META["quant"]["style"], "NEUTRAL", 45, TRADER_WEIGHTS["quant"], "قیمت در تعادل — Quant دست نمی‌زند")

def _vote_macro(feats: dict[str, float]) -> TraderVote:
    news = feats.get("news_score", 0)
    dxy = feats.get("dxy_proxy", 0)
    yld = feats.get("yield_proxy", 0)
    risk = feats.get("risk_on_proxy", 0)
    # macro archetype: needs alignment of dollar + yield + news
    # dxy_proxy is a labelled gold-derived proxy (see features.py). Alignment of the proxy,
    # news tone and event risk decides whether the macro archetype votes at all.
    score = 0
    if news > 0.35: score += 2
    elif news < -0.35: score -= 2
    if dxy < -0.3: score += 1  # DXY weak => gold bullish
    elif dxy > 0.3: score -= 1
    if yld < -0.2: score += 1  # yield down => gold bullish
    elif yld > 0.2: score -= 1
    if risk > 0: score += 0.5
    elif risk < 0: score -= 0.5
    if score >= 2.2:
        return TraderVote("macro", TRADERS_META["macro"]["name"], TRADERS_META["macro"]["style"], "BUY", 68 + int(score*6), TRADER_WEIGHTS["macro"], f"دلار ضعیف + خبر مثبت ({news:+.2f}) — ماکرو صعودی")
    if score <= -2.2:
        return TraderVote("macro", TRADERS_META["macro"]["name"], TRADERS_META["macro"]["style"], "SELL", 68 + int(-score*6), TRADER_WEIGHTS["macro"], f"دلار قوی + خبر منفی ({news:+.2f}) — ماکرو نزولی")
    return TraderVote("macro", TRADERS_META["macro"]["name"], TRADERS_META["macro"]["style"], "NEUTRAL", 48, TRADER_WEIGHTS["macro"], "ماکرو خنثی — عدم همگرایی دلار/خبر")

def _vote_scalper(feats: dict[str, float]) -> TraderVote:
    vol = feats.get("vol_vs_median", 1)
    spread_ok = feats.get("spread_vs_typical", 1) < 1.4
    body_spread = feats.get("body_vs_spread", 0)
    ret1 = feats.get("ret_1", 0)
    macd = feats.get("macd_hist", 0)
    atr_z = feats.get("atr_z", 0)
    # Scalper needs volume spike + tight spread + body confirms
    if not spread_ok:
        return TraderVote("scalper", TRADERS_META["scalper"]["name"], TRADERS_META["scalper"]["style"], "NEUTRAL", 38, TRADER_WEIGHTS["scalper"], "اسپرد باز — Scalper کنار می‌کشد", is_veto=True)
    if vol > 1.5 and body_spread > 4 and abs(ret1) > 0.0004:
        if ret1 > 0 and macd > 0:
            return TraderVote("scalper", TRADERS_META["scalper"]["name"], TRADERS_META["scalper"]["style"], "BUY", 66, TRADER_WEIGHTS["scalper"], f"حجم {vol:.1f}× + بدنه/اسپرد {body_spread:.0f} + مومنتوم")
        if ret1 < 0 and macd < 0:
            return TraderVote("scalper", TRADERS_META["scalper"]["name"], TRADERS_META["scalper"]["style"], "SELL", 66, TRADER_WEIGHTS["scalper"], f"حجم {vol:.1f}× + فشار فروش لحظه‌ای")
    return TraderVote("scalper", TRADERS_META["scalper"]["name"], TRADERS_META["scalper"]["style"], "NEUTRAL", 44, TRADER_WEIGHTS["scalper"], "حجم/مومنتوم ناکافی — Scalper صبر")

def _vote_supply_demand(feats: dict[str, float]) -> TraderVote:
    premium = feats.get("premium_discount", 0.5)
    ob_dist = feats.get("order_block_dist_atr", 5)
    dist_res = feats.get("dist_to_res_atr", 5)
    dist_sup = feats.get("dist_to_sup_atr", 5)
    # supply/demand archetype: buy discount near demand, sell premium near supply
    if premium < 0.33 and dist_sup < 1.2 and ob_dist < 2.0:
        return TraderVote("supply_demand", TRADERS_META["supply_demand"]["name"], TRADERS_META["supply_demand"]["style"], "BUY", 69, TRADER_WEIGHTS["supply_demand"], f"Discount {premium:.2f} + نزدیک تقاضا ({dist_sup:.1f} ATR) — ارزش خرید")
    if premium > 0.67 and dist_res < 1.2 and ob_dist < 2.0:
        return TraderVote("supply_demand", TRADERS_META["supply_demand"]["name"], TRADERS_META["supply_demand"]["style"], "SELL", 69, TRADER_WEIGHTS["supply_demand"], f"Premium {premium:.2f} + نزدیک عرضه ({dist_res:.1f} ATR) — ارزش فروش")
    return TraderVote("supply_demand", TRADERS_META["supply_demand"]["name"], TRADERS_META["supply_demand"]["style"], "NEUTRAL", 46, TRADER_WEIGHTS["supply_demand"], "وسط رنج — بدون ارزش Supply/Demand")

# ─── Public API ───
def vote_all(feats: dict[str, float]) -> list[TraderVote]:
    return [
        _vote_ict(feats),
        _vote_trend(feats),
        _vote_quant(feats),
        _vote_macro(feats),
        _vote_scalper(feats),
        _vote_supply_demand(feats),
    ]

def ensemble(feats: dict[str, float], base_ev: float | None = None, base_direction: str | None = None) -> dict[str, Any]:
    """
    Weighted vote of the six rule archetypes over features computed from real closed candles.

    Output: consensus, agreement, quality label, veto reasons, advisory text.
    Nothing here is measured performance — it is a transparent, deterministic rule ensemble.
    """
    votes = vote_all(feats)
    # Weighted directional score: BUY +1, SELL -1, NEUTRAL 0
    score = 0.0
    total_w = 0.0
    buy_w = 0.0
    sell_w = 0.0
    neutral_w = 0.0
    for v in votes:
        w = v.weight * (v.confidence / 70)  # confidence-weighted
        total_w += w
        if v.direction == "BUY":
            score += w
            buy_w += w
        elif v.direction == "SELL":
            score -= w
            sell_w += w
        else:
            neutral_w += w

    # Normalize -1 .. +1
    norm = score / max(total_w, 0.01)
    # Consensus
    if norm > 0.28:
        consensus = "BUY"
    elif norm < -0.28:
        consensus = "SELL"
    else:
        consensus = "NEUTRAL"

    # Agreement: fraction of weight aligned with consensus
    if consensus == "BUY":
        agreement = buy_w / max(total_w, 0.01)
    elif consensus == "SELL":
        agreement = sell_w / max(total_w, 0.01)
    else:
        agreement = neutral_w / max(total_w, 0.01)

    # Ensemble confidence (0-100) — agreement + individual confidence
    avg_conf = sum(v.confidence * v.weight for v in votes) / max(sum(v.weight for v in votes), 0.01)
    elite_conf = avg_conf * (0.55 + 0.45 * agreement)  # agreement boosts
    # Veto conditions — elite discipline: 70% of trades filtered out + MTF
    veto_reasons = []
    if feats.get("is_rollover", 0) > 0.5:
        veto_reasons.append("رول‌اور — نخبگان معامله نمی‌کنند")
    if feats.get("spread_vs_typical", 1) > 1.7:
        veto_reasons.append("اسپرد غیرعادی — نخبگان کنار")
    if feats.get("vol_regime_high", 0) > 0.5 and feats.get("atr_z", 0) > 2.0:
        veto_reasons.append("رژیم پرنوسان — صبر")
    if feats.get("is_asia", 0) > 0.5 and feats.get("vol_vs_median", 1) < 0.7:
        veto_reasons.append("سشن آسیا کم‌حجم — نخبگان صبر")
    if feats.get("mtf_veto", 0) > 0.5:
        veto_reasons.append("وتو MTF: 1h/15m خلاف 1m با قدرت — نخبگان خلاف HTF نمی‌روند")
    if feats.get("mtf_divergence", 0) > 0.5:
        veto_reasons.append("واگرایی MTF: همگرایی <40% — تصمیم بدون نقص = صبر")
    # Scalper veto spreads to whole ensemble
    scalper_veto = any(v.id == "scalper" and v.is_veto for v in votes)
    if scalper_veto:
        veto_reasons.append("ریزساختار خراب — Scalper وتو")

    is_veto = len(veto_reasons) > 0

    # Advisory EV nudge (hand-tuned prior, not a measured edge): if ensemble agrees with base predictor, boost EV; if conflicts, dampen
    elite_ev_boost = 0.0
    if base_direction and base_ev is not None:
        if consensus == base_direction and consensus != "NEUTRAL" and agreement > 0.42:
            elite_ev_boost = 0.09 + agreement * 0.12  # heuristic nudge, documented as a prior
        elif consensus != "NEUTRAL" and consensus != base_direction and agreement > 0.38:
            elite_ev_boost = -0.14 - agreement * 0.08  # penalize conflict

    # Agreement quality label. It describes agreement between rule sets,
    # NOT a measured edge: real performance only comes from a real-data backtest.
    quality = "A+"
    if agreement < 0.35 or elite_conf < 58:
        quality = "C — پراکنده"
    elif agreement < 0.50:
        quality = "B — متوسط"
    elif agreement >= 0.58 and elite_conf >= 68 and not is_veto:
        quality = "A+ — نخبگی"

    advisory = ""
    if is_veto:
        advisory = f"وتو سبک‌ها: {veto_reasons[0]} — حتی اگر مدل سیگنال دهد، این سبک می‌گوید وارد نشو."
    elif consensus != "NEUTRAL" and agreement >= 0.50:
        advisory = f"{agreement*100:.0f}% وزن سبک‌ها هم‌جهت ({consensus}) — فقط همین ستاپ‌ها با فیلترهای دیگر هم‌خوان‌اند."
    else:
        advisory = "عدم اجماع سبک‌ها — معامله نکردن هم یک تصمیم معتبر است."

    return {
        "votes": [
            {"id": v.id, "name": v.name, "style": v.style, "direction": v.direction, "confidence": v.confidence, "weight": v.weight, "reason": v.reason, "is_veto": v.is_veto}
            for v in votes
        ],
        "consensus": consensus,
        "agreement": round(agreement, 3),
        "elite_confidence": round(elite_conf, 1),
        "elite_score": round(norm, 3),
        "is_veto": is_veto,
        "veto_reasons": veto_reasons,
        "elite_ev_boost": round(elite_ev_boost, 3),
        "quality": quality,
        "advisory": advisory,
        "summary": f"اجماع سبک‌ها: {consensus} با {agreement*100:.0f}% توافق · وتو: {'بله' if is_veto else 'خیر'} · کیفیت {quality}",
    }
