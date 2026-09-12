"""
MTF Analyzer — بررسی چند تایم‌فریم برای تصمیم بدون نقص
اصول نخبگان: هرگز خلاف روند تایم بالاتر معامله نکن.
1m = ماشه (trigger) — فقط اجرا
5m = روند کوتاه‌مدت
15m = ساختار روز
1h = رژیم / بایاس کلان
4h = بستر هفتگی (اختیاری)
D1 = مرجع ماهانه (اختیاری)

منطق: امتیاز MTF از -1 (نزولی کامل) تا +1 (صعودی کامل)
- هر تایم +1 صعودی، -1 نزولی، 0 خنثی
- وزن‌دهی: 1m 15%، 5m 20%، 15m 25%، 1h 25%، 4h 15% (MTF بالاتر وزنش بیشتر برای تصمیم)
- وتو: اگر 1h مخالف 1m و ADX 1h >22 → Hard veto (نخبگان هرگز خلاف 1h با قدرت معامله نمی‌کنند)
- همگرایی A+ = 4/5 تایم هم‌جهت و 1h هم‌جهت
"""
from __future__ import annotations
from datetime import datetime, timezone, timedelta
from typing import Any
from statistics import mean

from app.models import Candle, Timeframe, Direction
from app.services.indicators import ema, ichimoku, rsi, adx, atr

def resample_candles(candles: list[Candle], target: Timeframe) -> list[Candle]:
    """Resample M1 candles into higher timeframe by OHLC aggregation."""
    if not candles:
        return []
    # if already target or smaller, return as is
    # we assume input is M1 sorted ascending by timestamp
    sec = target.seconds
    # bucket by floor timestamp to target seconds since epoch
    buckets: dict[int, list[Candle]] = {}
    for c in candles:
        ts = int(c.timestamp.timestamp())
        bucket = (ts // sec) * sec
        buckets.setdefault(bucket, []).append(c)
    out: list[Candle] = []
    for bucket_ts in sorted(buckets.keys()):
        group = buckets[bucket_ts]
        if not group:
            continue
        # Buckets are built strictly from the M1 candles that actually exist in the feed.
        # A partially covered bucket is still real data (open/high/low/close of the bars we have);
        # completeness is reported by the caller instead of inventing the missing minutes.
        o = group[0].open
        h = max(g.high for g in group)
        l = min(g.low for g in group)
        c = group[-1].close
        v = sum(g.volume for g in group)
        # use bucket start as timestamp
        ts_dt = datetime.fromtimestamp(bucket_ts, tz=timezone.utc)
        out.append(Candle(symbol=group[0].symbol, timeframe=target, timestamp=ts_dt, open=o, high=h, low=l, close=c, volume=v, complete=True))
    return out

def _bias_for_tf(candles: list[Candle]) -> dict[str, Any]:
    """Single TF bias: BUY/SELL/NEUTRAL + strength 0-100 — adaptive to short HTF history"""
    if len(candles) < 5:
        return {"bias": Direction.NEUTRAL, "strength": 0, "adx": 0, "ema_dist": 0, "rsi": 50, "cloud": 0, "score": 0}
    if len(candles) >= 250:
        ema_period = 200
    elif len(candles) >= 80:
        ema_period = 50
    elif len(candles) >= 20:
        ema_period = 20
    else:
        ema_period = 5
    try:
        e_fast = ema(candles, ema_period)
        if len(candles) >= 60:
            ich = ichimoku(candles, 7,22,44,22)
        else:
            ich = {"tenkan": [None]*len(candles), "kijun": [None]*len(candles), "span_a": [None]*len(candles), "span_b": [None]*len(candles)}
        r7 = rsi(candles, 7)
        a14 = adx(candles, 14)
        atv = atr(candles, 14)
    except Exception:
        return {"bias": Direction.NEUTRAL, "strength": 0, "adx": 0, "ema_dist": 0, "rsi": 50, "cloud": 0, "score": 0}
    i = len(candles)-1
    if e_fast[i] is None:
        return {"bias": Direction.NEUTRAL, "strength": 0, "adx": 0, "ema_dist": 0, "rsi": 50, "cloud": 0, "score": 0}
    adx_raw = a14[i]
    adx_v = float(adx_raw) if adx_raw is not None else 15.0
    rsi_raw = r7[i]
    rsi_v = float(rsi_raw) if rsi_raw is not None else 50.0
    price = candles[i].close
    ema_v = float(e_fast[i])  # type: ignore
    atr_v = float(atv[i] or 1)  # type: ignore
    tenkan = ich["tenkan"][i]
    kijun = ich["kijun"][i]
    has_ich = tenkan is not None and kijun is not None
    span_a = ich["span_a"][i]
    span_b = ich["span_b"][i]
    cloud_top = max(float(span_a or price), float(span_b or price))
    cloud_bot = min(float(span_a or price), float(span_b or price))
    # scoring
    score = 0
    ema_dist = (price - ema_v) / max(atr_v, 0.01)
    if ema_dist > 0.6: score += 2
    elif ema_dist < -0.6: score -= 2
    if has_ich:
        tk_spread = (float(tenkan) - float(kijun)) / max(atr_v, 0.01)
        if tk_spread > 0.25: score += 1
        elif tk_spread < -0.25: score -= 1
        if price > cloud_top + 0.08*atr_v: score += 2
        elif price < cloud_bot - 0.08*atr_v: score -= 2
        elif price > cloud_top: score += 1
        elif price < cloud_bot: score -= 1
    else:
        # short TF without ichimoku: use EMA only, no TK/cloud
        pass
    # RSI
    if rsi_v > 58: score += 0.5
    elif rsi_v < 42: score -= 0.5
    # ADX confirmation
    adx_weight = 1 if adx_v >= 20 else 0.4
    score *= adx_weight if adx_v < 18 else 1
    # Determine bias
    if score >= 1.8:
        bias = Direction.BUY
        strength = min(95, 55 + score*9 + (adx_v-18)*1.2)
    elif score <= -1.8:
        bias = Direction.SELL
        strength = min(95, 55 + (-score)*9 + (adx_v-18)*1.2)
    else:
        bias = Direction.NEUTRAL
        strength = 45 + abs(score)*4
    return {"bias": bias, "strength": int(strength), "adx": int(adx_v), "ema_dist": round(ema_dist,2), "rsi": round(rsi_v,1), "cloud": 1 if bias==Direction.BUY else -1 if bias==Direction.SELL else 0, "score": round(score,2)}

# weights for MTF overall
MTF_WEIGHTS = {
    Timeframe.M1: 0.15,
    Timeframe.M5: 0.20,
    Timeframe.M15: 0.25,
    Timeframe.H1: 0.25,
    Timeframe.H4: 0.10,
    Timeframe.D1: 0.05,
}

def mtf_confluence(candles_by_tf: dict[Timeframe, list[Candle]]) -> dict[str, Any]:
    """Compute MTF confluence from dict TF->candles. Candles must be O H L C with enough history."""
    # Analyze each TF
    per_tf: dict[str, Any] = {}
    weighted_score = 0.0
    total_w = 0.0
    buy_count = 0
    sell_count = 0
    neutral_count = 0
    max_adx = 0
    for tf, candles in candles_by_tf.items():
        if not candles or len(candles) < 5:
            continue
        info = _bias_for_tf(candles)
        per_tf[tf.value] = {
            "bias": info["bias"].value,
            "strength": info["strength"],
            "adx": info["adx"],
            "ema_dist": info["ema_dist"],
            "rsi": info["rsi"],
            "score": info["score"],
            "candles": len(candles),
        }
        w = MTF_WEIGHTS.get(tf, 0.1)
        dir_val = 1 if info["bias"]==Direction.BUY else -1 if info["bias"]==Direction.SELL else 0
        # weight by strength
        strength_factor = info["strength"]/70
        weighted_score += dir_val * w * (0.6 + 0.4*strength_factor)
        total_w += w
        if dir_val>0: buy_count+=1
        elif dir_val<0: sell_count+=1
        else: neutral_count+=1
        max_adx = max(max_adx, info["adx"])

    if total_w==0:
        return {"mtf_bias": Direction.NEUTRAL.value, "mtf_score": 0, "mtf_strength": 0, "alignment": 0, "per_tf": {}, "veto": False, "veto_reasons": [], "quality": "C"}

    norm = weighted_score / max(total_w*1.0, 0.01)  # -1 .. +1
    # overall bias
    if norm > 0.28:
        mtf_bias = Direction.BUY
    elif norm < -0.28:
        mtf_bias = Direction.SELL
    else:
        mtf_bias = Direction.NEUTRAL
    # alignment: how many TF agree with overall bias
    if mtf_bias == Direction.BUY:
        aligned = buy_count
    elif mtf_bias == Direction.SELL:
        aligned = sell_count
    else:
        aligned = neutral_count
    total_tfs = len(per_tf)
    alignment = aligned / max(total_tfs,1)

    # Quality
    if alignment >= 0.8 and abs(norm) > 0.55 and mtf_bias!=Direction.NEUTRAL:
        quality = "A+ — تراز کامل MTF"
    elif alignment >= 0.6 and abs(norm) > 0.35:
        quality = "B+ — همگرا"
    elif alignment >= 0.5:
        quality = "B — نیمه‌تراز"
    else:
        quality = "C — واگرا / خنثی"

    # Veto logic — elite never trades against strong HTF
    veto_reasons = []
    # HTF veto: 1h opposite 1m with strong ADX
    h1 = per_tf.get("1h")
    m1 = per_tf.get("1m")
    if h1 and m1:
        if h1["bias"]=="BUY" and m1["bias"]=="SELL" and h1["adx"]>=22:
            veto_reasons.append(f"وتو MTF: 1h صعودی قوی (ADX {h1['adx']}) خلاف 1m نزولی — نخبگان خلاف 1h نمی‌روند")
        if h1["bias"]=="SELL" and m1["bias"]=="BUY" and h1["adx"]>=22:
            veto_reasons.append(f"وتو MTF: 1h نزولی قوی (ADX {h1['adx']}) خلاف 1m صعودی")
    # 15m veto
    m15 = per_tf.get("15m")
    if m15 and m1:
        if m15["bias"]=="BUY" and m1["bias"]=="SELL" and m15["adx"]>=24:
            veto_reasons.append(f"وتو MTF: 15m صعودی قوی خلاف 1m")
        if m15["bias"]=="SELL" and m1["bias"]=="BUY" and m15["adx"]>=24:
            veto_reasons.append(f"وتو MTF: 15m نزولی قوی خلاف 1m")
    # Complete disagreement: only when both BUY and SELL present (true divergence), not just neutrals
    if total_tfs>=3 and alignment < 0.4 and buy_count>0 and sell_count>0:
        veto_reasons.append("واگرایی کامل MTF — 1m/5m/15m/1h ناهم‌جهت، تصمیم بدون نقص = صبر")

    is_veto = len(veto_reasons)>0
    mtf_strength = int(min(95, abs(norm)*85 + alignment*15))

    # Build human summary
    if is_veto:
        advisory = veto_reasons[0] + " — بهترین تصمیم بدون نقص، عدم ورود است."
    elif mtf_bias!=Direction.NEUTRAL and alignment>=0.6:
        advisory = f"تراز MTF {mtf_bias.value} با {(alignment*100):.0f}% همگرایی ({quality}) — ماشه 1m با تایید 5m/15m/1h. فقط همین‌ها A+ هستند."
    else:
        advisory = f"MTF {mtf_bias.value} ولی همگرایی {(alignment*100):.0f}% ({quality}) — بدون تراز کامل، ریسک تله."

    return {
        "mtf_bias": mtf_bias.value,
        "mtf_score": round(norm,3),
        "mtf_strength": mtf_strength,
        "alignment": round(alignment,3),
        "per_tf": per_tf,
        "buy_count": buy_count,
        "sell_count": sell_count,
        "neutral_count": neutral_count,
        "is_veto": is_veto,
        "veto_reasons": veto_reasons,
        "quality": quality,
        "advisory": advisory,
        "weights": {k.value: v for k,v in MTF_WEIGHTS.items()},
    }

def mtf_from_m1(candles_m1: list[Candle]) -> dict[str, Any]:
    """Convenience: build MTF from 200+ M1 candles by resampling."""
    if len(candles_m1) < 60:
        return mtf_confluence({})
    # resample
    by_tf: dict[Timeframe, list[Candle]] = {}
    by_tf[Timeframe.M1] = candles_m1[-220:]
    # 5m — need at least 10
    c5 = resample_candles(candles_m1, Timeframe.M5)
    if len(c5)>=10:
        by_tf[Timeframe.M5] = c5[-80:]
    # 15m — need at least 8 (220m1 => 14x15m)
    c15 = resample_candles(candles_m1, Timeframe.M15)
    if len(c15)>=8:
        by_tf[Timeframe.M15] = c15[-80:]
    # 1h — need at least 3 (220m1 => 3-4 x1h)
    c1h = resample_candles(candles_m1, Timeframe.H1)
    if len(c1h)>=3:
        by_tf[Timeframe.H1] = c1h[-80:]
    # 4h (if enough)
    if len(candles_m1) >= 200:
        c4h = resample_candles(candles_m1, Timeframe.H4)
        if len(c4h)>=3:
            by_tf[Timeframe.H4] = c4h[-60:]
    return mtf_confluence(by_tf)

def mtf_features_from_m1(candles_m1: list[Candle]) -> dict[str, float]:
    """8 MTF features for predictor (L category)."""
    mtf = mtf_from_m1(candles_m1)
    per = mtf.get("per_tf", {})
    def bias_val(tf_str):
        b = per.get(tf_str, {}).get("bias", "NEUTRAL")
        return 1.0 if b=="BUY" else -1.0 if b=="SELL" else 0.0
    def strength_val(tf_str):
        return per.get(tf_str, {}).get("strength", 50) / 70.0
    def adx_val(tf_str):
        return per.get(tf_str, {}).get("adx", 15) / 25.0
    feats: dict[str, float] = {}
    feats["mtf_bias_5m"] = bias_val("5m")
    feats["mtf_bias_15m"] = bias_val("15m")
    feats["mtf_bias_1h"] = bias_val("1h")
    feats["mtf_score"] = mtf.get("mtf_score", 0)
    feats["mtf_alignment"] = mtf.get("alignment", 0)
    feats["mtf_strength"] = mtf.get("mtf_strength", 0) / 70.0
    # weighted Ema alignment count normalized
    feats["mtf_ema_alignment"] = (bias_val("5m")+bias_val("15m")+bias_val("1h"))/3
    feats["mtf_adx_1h"] = adx_val("1h")
    feats["mtf_veto"] = 1.0 if mtf.get("is_veto") else 0.0
    # extra: divergence flag
    feats["mtf_divergence"] = 1.0 if mtf.get("alignment",1) < 0.4 else 0.0
    return feats
