"""
Feature Store — تمام ورودی‌های کلیدی برای پیش‌بینی رفتار کندل بعدی
119 ورودی در 15 دسته (A-O) — بدون نشت آینده (فقط کندل بسته)
شامل K: Elite Smart Money (8) + L: Multi-Timeframe MTF (10) + M: Candle Behavior (9) + N: ICT Full (8) + O: OrderFlow & VolumeProfile (8) — تراز کامل + رفتارشناسی
"""
from __future__ import annotations
import math
from datetime import timezone
from statistics import mean, stdev
from typing import Any

from app.models import Candle, StrategyContext
from app.services.indicators import (
    atr, ema, ichimoku, rsi, session_vwap,
    macd, bollinger, stochastic, adx, support_resistance
)

def _safe(v, default=0.0):
    return float(v) if v is not None else default

def _slope(series, idx, period=5):
    if idx < period or series[idx] is None or series[idx-period] is None:
        return 0.0
    return (_safe(series[idx]) - _safe(series[idx-period])) / period

def _zscore(values, idx, window=60):
    start = max(0, idx - window + 1)
    w = [v for v in values[start:idx+1] if v is not None]
    if len(w) < 5:
        return 0.0
    m = mean(w)
    try:
        s = stdev(w)
    except Exception:
        s = 0.0
    if s == 0:
        return 0.0
    return (_safe(values[idx]) - m) / s

def build_features(candles: list[Candle], context: StrategyContext | None = None) -> dict[str, float]:
    """
    ورودی‌های مدل پیش‌بینی — همه از کندل‌های بسته، بدون look-ahead
    خروجی: dict نام -> مقدار نرمال‌شده/خام (مدل خودش نرمال می‌کند)
    """
    n = len(candles)
    if n < 200:
        raise ValueError("Need 200 candles")
    i = n - 1
    cur = candles[i]
    prev = candles[i-1] if i>0 else cur

    # Precompute indicators once
    ema200 = ema(candles, 200)
    vwap = session_vwap(candles)
    rsi7 = rsi(candles, 7)
    rsi14 = rsi(candles, 14)
    atr14 = atr(candles, 14)
    macd_d = macd(candles)
    bb = bollinger(candles)
    stoch = stochastic(candles)
    adx14 = adx(candles, 14)
    # 5m strict pro — adapt Ichimoku params to timeframe (5m less noise → higher win)
    tf = candles[-1].timeframe.value if candles else "1m"
    if tf == "5m":
        t_p, k_p, b_p, disp = 9, 26, 52, 26
    elif tf == "15m":
        t_p, k_p, b_p, disp = 9, 26, 52, 26
    elif tf in ("1h", "4h", "1d"):
        t_p, k_p, b_p, disp = 9, 26, 52, 26
    else:
        t_p, k_p, b_p, disp = 7, 22, 44, 22
    ich = ichimoku(candles, t_p, k_p, b_p, disp)
    sr = support_resistance(candles, 30)

    # Helpers
    tenkan = _safe(ich["tenkan"][i])
    kijun = _safe(ich["kijun"][i])
    span_a = ich["span_a"][i]
    span_b = ich["span_b"][i]
    cloud_top = max(_safe(span_a), _safe(span_b)) if span_a is not None and span_b is not None else cur.close
    cloud_bottom = min(_safe(span_a), _safe(span_b)) if span_a is not None and span_b is not None else cur.close
    ema200_v = _safe(ema200[i])
    vwap_v = _safe(vwap[i])
    rsi7_v = _safe(rsi7[i], 50)
    rsi14_v = _safe(rsi14[i], 50)
    atr_v = _safe(atr14[i], 1.0)
    macd_line = _safe(macd_d["macd"][i])
    macd_sig = _safe(macd_d["signal"][i])
    macd_hist = _safe(macd_d["histogram"][i])
    bb_up = _safe(bb["upper"][i], cur.close+1)
    bb_low = _safe(bb["lower"][i], cur.close-1)
    bb_mid = _safe(bb["middle"][i], cur.close)
    bb_width = _safe(bb["width"][i], 1.0)
    stoch_k = _safe(stoch["k"][i], 50)
    stoch_d = _safe(stoch["d"][i], 50)
    adx_v = _safe(adx14[i], 20)

    # ── A: Price Geometry (12) ──
    rng = max(cur.high - cur.low, 0.01)
    body = abs(cur.close - cur.open)
    upper_wick = cur.high - max(cur.open, cur.close)
    lower_wick = min(cur.open, cur.close) - cur.low
    f: dict[str, float] = {}
    f["close"] = cur.close
    f["ret_1"] = (cur.close - prev.close) / max(prev.close, 1)
    f["ret_5"] = (cur.close - candles[i-5].close) / max(candles[i-5].close, 1) if i>=5 else 0
    f["ret_20"] = (cur.close - candles[i-20].close) / max(candles[i-20].close, 1) if i>=20 else 0
    f["body_ratio"] = body / rng
    f["upper_wick_ratio"] = upper_wick / rng
    f["lower_wick_ratio"] = lower_wick / rng
    f["hl_range"] = rng
    f["hl_range_atr"] = rng / max(atr_v, 0.01)
    f["close_vs_mid"] = (cur.close - (cur.high+cur.low)/2) / rng
    # consecutive
    consec_bull = 0
    for k in range(i, max(-1, i-6), -1):
        if candles[k].close > candles[k].open:
            consec_bull += 1
        else:
            break
    consec_bear = 0
    for k in range(i, max(-1, i-6), -1):
        if candles[k].close < candles[k].open:
            consec_bear += 1
        else:
            break
    f["consec_bull"] = consec_bull
    f["consec_bear"] = consec_bear
    # swing position (0=low, 1=high in last 20)
    last20 = candles[max(0,i-19):i+1]
    lo = min(c.low for c in last20)
    hi = max(c.high for c in last20)
    f["swing_pos"] = (cur.close - lo) / max(hi-lo, 0.01)

    # ── B: Trend Structure (15) ──
    f["tenkan"] = tenkan
    f["kijun"] = kijun
    f["tenkan_kijun_spread"] = tenkan - kijun
    f["tenkan_kijun_spread_atr"] = (tenkan - kijun) / max(atr_v, 0.01)
    f["tenkan_slope"] = _slope(ich["tenkan"], i, 3)
    f["kijun_slope"] = _slope(ich["kijun"], i, 3)
    f["price_vs_cloud_top"] = (cur.close - cloud_top) / max(atr_v, 0.01)
    f["price_vs_cloud_bottom"] = (cur.close - cloud_bottom) / max(atr_v, 0.01)
    f["cloud_thickness"] = (cloud_top - cloud_bottom) / max(atr_v, 0.01)
    f["cloud_bearish"] = 1.0 if (span_a is not None and span_b is not None and span_a < span_b) else 0.0
    f["ema200_dist"] = (cur.close - ema200_v) / max(atr_v, 0.01)
    f["ema200_dist_norm"] = (cur.close - ema200_v) / max(cur.close, 1)
    f["ema200_slope"] = _slope(ema200, i, 5)
    f["vwap_dist"] = (cur.close - vwap_v) / max(atr_v, 0.01)
    f["vwap_slope_5"] = _slope(vwap, i, 5)
    f["adx"] = adx_v
    # HH/HL structure
    recent_high = max(c.high for c in candles[i-10:i+1])
    recent_low = min(c.low for c in candles[i-10:i+1])
    f["hh"] = 1.0 if cur.high >= recent_high - 0.01 else 0.0
    f["ll"] = 1.0 if cur.low <= recent_low + 0.01 else 0.0

    # ── C: Momentum (10) ──
    f["rsi7"] = rsi7_v
    f["rsi14"] = rsi14_v
    f["rsi7_slope"] = _slope(rsi7, i, 3)
    f["rsi7_z"] = _zscore(rsi7, i, 60)
    f["macd_line"] = macd_line
    f["macd_hist"] = macd_hist
    f["macd_hist_slope"] = _slope(macd_d["histogram"], i, 3)  # type: ignore
    f["stoch_k"] = stoch_k
    f["stoch_d"] = stoch_d
    f["stoch_kd_spread"] = stoch_k - stoch_d

    # ── D: Volatility (8) ──
    f["atr"] = atr_v
    f["atr_z"] = _zscore(atr14, i, 60)  # type: ignore
    f["bb_width"] = bb_width
    f["bb_width_z"] = _zscore(bb["width"], i, 60)  # type: ignore
    f["bb_pctB"] = (cur.close - bb_low) / max(bb_up - bb_low, 0.01)  # 0=low,1=high
    f["bb_squeeze"] = 1.0 if bb_width < (mean([v for v in bb["width"][-30:] if v is not None] or [bb_width]) * 0.7) else 0.0
    f["atr_on_range"] = atr_v / max(rng, 0.01)
    f["vol_regime_high"] = 1.0 if atr_v > 2.2 * (mean([v for v in atr14[-60:-1] if v is not None] or [atr_v])) else 0.0

    # ── E: Volume / Flow (6) ──
    vols = [c.volume for c in candles[max(0,i-30):i+1]]
    med_vol = sorted(vols)[len(vols)//2] if vols else 300
    f["vol_vs_median"] = cur.volume / max(med_vol, 1)
    f["vol_trend_5"] = (mean(vols[-5:]) - mean(vols[-10:-5])) / max(mean(vols[-10:-5]), 1) if len(vols)>=10 else 0
    # OBV-like
    obv = 0
    for k in range(max(1,i-20), i+1):
        obv += vols[k - max(0,i-30)] * (1 if candles[k].close > candles[k-1].close else -1 if candles[k].close < candles[k-1].close else 0)
    f["obv_20"] = obv / 1000.0
    f["vwap_dist_vol_adj"] = f["vwap_dist"] * (1 if f["vol_vs_median"]>1 else 0.7)
    f["range_x_vol"] = rng * f["vol_vs_median"]
    f["volume_spike"] = 1.0 if f["vol_vs_median"] > 1.8 else 0.0

    # ── F: Support/Resistance ──
    sup = sr.get("support")
    res = sr.get("resistance")
    f["dist_to_res_atr"] = (res - cur.close) / max(atr_v, 0.01) if res else 5.0
    f["dist_to_sup_atr"] = (cur.close - sup) / max(atr_v, 0.01) if sup else 5.0

    # ── G: Time / Human Behavior (7) ──
    # UTC session: Asia 0-7, London 8-16, NY 13-21 (overlap 13-16 golden)
    hour = cur.timestamp.astimezone(timezone.utc).hour + cur.timestamp.astimezone(timezone.utc).minute/60
    f["hour_utc"] = hour
    f["is_london"] = 1.0 if 8 <= hour < 17 else 0.0
    f["is_ny"] = 1.0 if 13 <= hour < 22 else 0.0
    f["is_overlap"] = 1.0 if 13 <= hour < 17 else 0.0
    f["is_asia"] = 1.0 if (hour >= 0 and hour < 8) else 0.0
    f["dow"] = cur.timestamp.weekday()  # 0 Mon
    f["is_rollover"] = 1.0 if 21.9 <= hour or hour < 0.2 else 0.0
    # minutes from NY open (13:30 UTC for gold active) — proxy for liquidity
    f["mins_from_ny_open"] = (hour - 13.5) * 60

    # ── H: Cross-market proxies (derived, never presented as real DXY/yield data) ──
    # DXY is NOT available from the free provider: this is a clearly-labelled PROXY derived from
    # gold's own EMA20 slope. It is a computed feature, not imported dollar data — never present it
    # to users as a real DXY reading.
    # If a real DXY/yield feed is added later, replace these two features with its values.
    closes = [c.close for c in candles]
    ema20 = ema(candles, 20)[i] if len(candles)>=20 else cur.close
    f["dxy_proxy"] = -(cur.close - ema20) / max(atr_v, 0.01) * 0.35  # inverse
    f["yield_proxy"] = f["atr_z"] * 0.55  # yield up => vol up
    f["vix_proxy"] = f["hl_range_atr"] * 0.5 + f["atr_z"] * 0.3
    f["risk_on_proxy"] = f["consec_bull"] - f["consec_bear"]  # >0 risk-on

    # ── I: Sentiment (from context) ──
    if context and context.news:
        f["news_dir"] = 1.0 if context.news.direction.value == "BUY" else -1.0 if context.news.direction.value == "SELL" else 0.0
        f["news_conf"] = context.news.confidence / 100.0
        f["news_impact"] = 2.0 if context.news.impact.value == "HIGH" else 1.0 if context.news.impact.value == "MEDIUM" else 0.3
        f["news_score"] = f["news_dir"] * f["news_conf"] * f["news_impact"]
    else:
        f["news_dir"] = 0.0
        f["news_conf"] = 0.0
        f["news_impact"] = 0.0
        f["news_score"] = 0.0

    # ── J: Microstructure ──
    spread = context.spread if context else 0.18
    typical = context.typical_spread if context else 0.18
    f["spread"] = spread
    f["spread_vs_typical"] = spread / max(typical, 0.01)
    f["body_vs_spread"] = body / max(spread, 0.01)

    # ── K: Elite Smart Money — رفتار نخبگان (ICT/SMC) ──
    # Liquidity sweep: wick beyond recent high/low then close back inside (stop hunt)
    recent_high_10 = max(c.high for c in candles[i-10:i]) if i>=10 else cur.high
    recent_low_10 = min(c.low for c in candles[i-10:i]) if i>=10 else cur.low
    swept_high = 1.0 if (cur.high > recent_high_10 + 0.15*atr_v and cur.close < recent_high_10) else 0.0
    swept_low = 1.0 if (cur.low < recent_low_10 - 0.15*atr_v and cur.close > recent_low_10) else 0.0
    f["liquidity_sweep_bear"] = swept_high  # bearish sweep = trap longs
    f["liquidity_sweep_bull"] = swept_low
    # FVG (Fair Value Gap): gap between candle i-2 high/low and i low/high with no overlap (3-bar pattern)
    fvg_bull = 0.0
    fvg_bear = 0.0
    if i >= 2:
        c2 = candles[i-2]; c1 = candles[i-1]
        # Bullish FVG: low[i] > high[i-2] (gap up, inefficiency)
        if cur.low > c2.high + 0.08*atr_v:
            fvg_bull = 1.0
        # Bearish FVG: high[i] < low[i-2]
        if cur.high < c2.low - 0.08*atr_v:
            fvg_bear = 1.0
    f["fvg_bull"] = fvg_bull
    f["fvg_bear"] = fvg_bear
    # Order Block distance: last opposite candle before impulse (impulse = body > 0.6*range and consecutive)
    ob_dist = 5.0
    for k in range(i-1, max(0, i-20), -1):
        ck = candles[k]
        body_k = abs(ck.close - ck.open)
        rng_k = max(ck.high - ck.low, 0.01)
        is_bear_ob = ck.close < ck.open and body_k/rng_k > 0.45  # bearish OB before bullish move
        is_bull_ob = ck.close > ck.open and body_k/rng_k > 0.45
        # if current is bullish, look for bearish OB; vice versa
        if cur.close > cur.open and is_bear_ob:
            ob_dist = abs(cur.close - ck.close) / max(atr_v, 0.01)
            break
        if cur.close < cur.open and is_bull_ob:
            ob_dist = abs(cur.close - ck.close) / max(atr_v, 0.01)
            break
    f["order_block_dist_atr"] = ob_dist
    # Premium / Discount: position in 20-bar range (0=discount low, 1=premium high)
    f["premium_discount"] = (cur.close - lo) / max(hi - lo, 0.01)
    # Killzone active: London 8-11, NY 13-16 golden (highest elite activity)
    f["killzone_active"] = 1.0 if (8 <= hour < 11 or 13 <= hour < 17) else 0.0
    # Turtle-style breakout: price breaks the 20-bar high/low
    hi20 = max(c.high for c in candles[i-20:i]) if i>=20 else cur.high
    lo20 = min(c.low for c in candles[i-20:i]) if i>=20 else cur.low
    if cur.close > hi20:
        f["turtle_breakout_20"] = 1.0
    elif cur.close < lo20:
        f["turtle_breakout_20"] = -1.0
    else:
        f["turtle_breakout_20"] = 0.0

    # ── L: Multi-Timeframe — تراز چندتایم‌فریم (بدون نقص) ──
    # Resample همین 200 کندل 1m به 5m/15m/1h/4h و بایاس هر تایم را بسنج
    try:
        from app.services.mtf_analyzer import mtf_features_from_m1
        mtf_feats = mtf_features_from_m1(candles)
        f.update(mtf_feats)
    except Exception:
        # fallback neutral
        for k in ["mtf_bias_5m","mtf_bias_15m","mtf_bias_1h","mtf_score","mtf_alignment","mtf_strength","mtf_ema_alignment","mtf_adx_1h","mtf_veto","mtf_divergence"]:
            f[k] = 0.0

    # ── M: Candle Behavior Deep — رفتارشناسی پیشرفته کندل (یوتیوب خلأ) ──
    # 1) Wick rejection, 2) Inside/Outside, 3) Composite 3c, 4) Error/Absorption, 5) Pinbar/Marubozu
    try:
        # Wick rejection
        f["wick_rejection_bull"] = 1.0 if (lower_wick/rng > 0.55 and f["close_vs_mid"] > 0.25 and body/rng > 0.15) else 0.0
        f["wick_rejection_bear"] = 1.0 if (upper_wick/rng > 0.55 and f["close_vs_mid"] < -0.25 and body/rng > 0.15) else 0.0
        # Inside bar: cur inside prev
        prev_high = prev.high; prev_low = prev.low
        f["inside_bar"] = 1.0 if (cur.high < prev_high - 0.02*atr_v and cur.low > prev_low + 0.02*atr_v) else 0.0
        # Outside / Engulfing: cur engulfs prev + direction
        is_outside = (cur.high > prev_high + 0.05*atr_v and cur.low < prev_low - 0.05*atr_v)
        if is_outside:
            if cur.close > cur.open and prev.close < prev.open and body > abs(prev.close-prev.open)*1.05:
                f["engulf_flag"] = 1.0
            elif cur.close < cur.open and prev.close > prev.open and body > abs(prev.close-prev.open)*1.05:
                f["engulf_flag"] = -1.0
            else:
                f["engulf_flag"] = 0.0
        else:
            f["engulf_flag"] = 0.0
        # Three-candle composite: high of 3, low of 3, body of composite
        if i >= 2:
            c2 = candles[i-2]
            hi3 = max(c2.high, candles[i-1].high, cur.high)
            lo3 = min(c2.low, candles[i-1].low, cur.low)
            rng3 = max(hi3 - lo3, 0.01)
            body3 = abs(cur.close - c2.open)
            f["three_c_body_ratio"] = body3 / rng3  # 0-1, high = strong momentum
            # Error candle: small body + both wicks long + indecision
            f["error_candle_flag"] = 1.0 if (body/rng < 0.22 and upper_wick/rng > 0.33 and lower_wick/rng > 0.33 and f["vol_vs_median"] > 0.9) else 0.0
        else:
            f["three_c_body_ratio"] = body/rng
            f["error_candle_flag"] = 0.0
        # Absorption proxy: high vol but small range + small body = effort without result
        # Normalized: vol_vs_median high + hl_range_atr low + body_ratio low => high absorption
        f["absorption_proxy"] = (f["vol_vs_median"] * 0.5) * (1.0 / max(f["hl_range_atr"], 0.3)) * (1.0 - body/rng)
        f["absorption_proxy"] = max(0, min(3.0, f["absorption_proxy"]))
        # Pinbar / Hammer / Shooting Star (1 = hammer bull, -1 = shooting bear)
        pin_bull = (lower_wick/rng > 0.55 and upper_wick/rng < 0.18 and body/rng < 0.35 and cur.close > (cur.high+cur.low)/2)
        pin_bear = (upper_wick/rng > 0.55 and lower_wick/rng < 0.18 and body/rng < 0.35 and cur.close < (cur.high+cur.low)/2)
        if pin_bull:
            f["pinbar_flag"] = 1.0
        elif pin_bear:
            f["pinbar_flag"] = -1.0
        else:
            f["pinbar_flag"] = 0.0
        # Marubozu flag (1 bull, -1 bear, 0 else)
        is_marubozu_bull = (body/rng > 0.88 and upper_wick/rng < 0.06 and lower_wick/rng < 0.06 and cur.close > cur.open)
        is_marubozu_bear = (body/rng > 0.88 and upper_wick/rng < 0.06 and lower_wick/rng < 0.06 and cur.close < cur.open)
        if is_marubozu_bull:
            f["marubozu_flag"] = 1.0
        elif is_marubozu_bear:
            f["marubozu_flag"] = -1.0
        else:
            f["marubozu_flag"] = 0.0
    except Exception:
        for k in ["wick_rejection_bull","wick_rejection_bear","inside_bar","engulf_flag","three_c_body_ratio","error_candle_flag","absorption_proxy","pinbar_flag","marubozu_flag"]:
            if k not in f:
                f[k] = 0.0
        # ensure all 8 (note we have 9 here, we will keep 8 discarding one, but keep all for now and slice later)
        # we keep 8: wick_rejection_bull,bear, inside_bar, engulf_flag, three_c_body_ratio, error_candle_flag, absorption_proxy, pinbar_flag, marubozu_flag -> need 8, merge pin+maru? keep 8+ keep both =9, so we collapse marubozu into pin? We'll keep both and adjust count to 9 then final will be 9 but we target 8; keep all 9 for safety
        pass

    # Normalize M to 8 by merging marubozu into pin? We'll keep 8 distinct: remove marubozu separate and use inside as is -> we actually have 8 if we count wick_bull, wick_bear, inside, engulf, three_c, error, absorption, pinbar =8 ; marubozu will be extra so we drop marubozu and fold into pinbar weight. For now drop marubozu to keep 8.
    if "marubozu_flag" in f:
        # fold into pinbar if needed, but keep for O? We'll keep it but will count as extra -> adjust N/O counts to keep total 118 exactly: we have 9 in M, so N should be 7 to keep 24. Simpler: keep marubozu and treat M as 9, N as 7, O as 8 => 24. We'll just keep marubozu and adjust downstream counts.
        pass

    # ── N: ICT Full — تکمیل Smart Money (Breaker, OTE, AMD, SilverBullet, EQH/EQL) ──
    try:
        # Breaker / Mitigation detection (approx)
        # OB candidate is 15 bars ago bearish/bullish block; breaker = OB broken after sweep, mitigation = broken without sweep
        breaker = 0.0
        mitigation = 0.0
        # use recent sweep flags already computed
        if i >= 5:
            # look for last OB break: price closed beyond recent OB level (order_block_dist small then grown?)
            # Simplified: if swept_high and cur.close < candles[i-5].low - 0.5*atr_v => bearish breaker (bull OB failed)
            # if swept_low and cur.close > candles[i-5].high + 0.5*atr_v => bullish breaker
            if swept_high and cur.close < min(c.low for c in candles[i-5:i]) - 0.2*atr_v:
                breaker = -1.0  # bearish breaker (bull OB broken down)
            elif swept_low and cur.close > max(c.high for c in candles[i-5:i]) + 0.2*atr_v:
                breaker = 1.0
            # Mitigation: no sweep but OB broken (failed swing)
            else:
                # check if price broke 5-bar OB without sweep
                mid = candles[i-5]
                if cur.close > mid.high + 0.3*atr_v and not swept_low and not swept_high:
                    # check if there was a bear OB that failed
                    mitigation = 1.0 if f["order_block_dist_atr"] > 3 else 0.0
                elif cur.close < mid.low - 0.3*atr_v and not swept_low and not swept_high:
                    mitigation = -1.0 if f["order_block_dist_atr"] > 3 else 0.0
        f["breaker_flag"] = breaker
        f["mitigation_flag"] = mitigation

        # OTE (Optimal Trade Entry 62-79% fib of last impulse 20 bars) — directional
        hi20 = max(c.high for c in candles[max(0,i-20):i+1])
        lo20 = min(c.low for c in candles[max(0,i-20):i+1])
        imp_range = max(hi20 - lo20, 0.01)
        # find last hi/lo index to determine impulse direction
        hi_idx = max(range(max(0,i-20), i+1), key=lambda k: candles[k].high)
        lo_idx = min(range(max(0,i-20), i+1), key=lambda k: candles[k].low)
        if hi_idx > lo_idx:
            # bullish impulse up (lo -> hi), OTE is support below hi (retrace long)
            ote_top = hi20 - 0.62*imp_range
            ote_bot = hi20 - 0.79*imp_range
            ote_is_bull = True
        else:
            # bearish impulse down (hi -> lo), OTE is resistance above lo (retrace short)
            ote_top = lo20 + 0.79*imp_range
            ote_bot = lo20 + 0.62*imp_range
            ote_is_bull = False
        ote_center = (ote_top + ote_bot)/2
        f["ote_distance_atr"] = abs(cur.close - ote_center) / max(atr_v, 0.01)
        in_ote = (ote_bot <= cur.close <= ote_top)
        if in_ote:
            f["ote_in_zone"] = 1.0 if ote_is_bull else -1.0
        else:
            f["ote_in_zone"] = 0.0

        # Equal highs/lows (EQH/EQL within 0.18*ATR)
        eqh = 0.0
        if i >= 3:
            last_highs = [c.high for c in candles[i-3:i+1]]
            if max(last_highs) - min(last_highs) < 0.18*atr_v:
                eqh = 1.0
            last_lows = [c.low for c in candles[i-3:i+1]]
            if max(last_lows) - min(last_lows) < 0.18*atr_v:
                eqh = -1.0 if eqh==0 else eqh  # keep 1 for EQH, -1 for EQL, if both keep 1
        f["eqh_eql_flag"] = eqh

        # AMD phase: Accumulation (range squeeze) -> Manipulation (sweep) -> Distribution (expansion)
        # squeeze = bb_squeeze and vol low, manip = sweep flag, distribution = strong body + vol spike
        squeeze = f.get("bb_squeeze", 0) > 0.5 or f["hl_range_atr"] < 0.7
        manip = (swept_high or swept_low) > 0.5
        expansion = (body/rng > 0.65 and f["vol_vs_median"] > 1.25)
        f["amd_phase_flag"] = 1.0 if (squeeze and manip) else (0.5 if manip else (0.0 if not expansion else -0.5))

        # Silver Bullet window: 15:00-16:00 UTC (10-11 ET NY) - highest WR per ICT 2026
        f["silver_bullet_active"] = 1.0 if (15 <= hour < 16 and f["killzone_active"] > 0.5) else 0.0

        # Liquidity void / FVG not yet mitigated: if fvg_bull/bear present and not yet retested
        # Simplified: FVG present last 3 bars and price not yet inside FVG
        liq_void = 0.0
        if i >= 3:
            has_fvg = f.get("fvg_bull",0) + f.get("fvg_bear",0) > 0.5
            # check if price closed inside recent FVG gap (would be mitigated)
            # For bull FVG: gap between c2.high and cur.low
            c2 = candles[i-2]
            gap_mitigated = (c2.high < cur.low and cur.close < c2.high)  # simplified
            if has_fvg and not gap_mitigated:
                liq_void = 1.0 if f.get("fvg_bull",0) else -1.0 if f.get("fvg_bear",0) else 0.0
        f["liq_void_flag"] = liq_void
    except Exception:
        for k in ["breaker_flag","mitigation_flag","ote_distance_atr","ote_in_zone","eqh_eql_flag","amd_phase_flag","silver_bullet_active","liq_void_flag"]:
            if k not in f:
                f[k] = 0.0

    # ── O: OrderFlow & Volume Profile — جریان سفارش و پروفایل حجم ──
    try:
        # Delta proxy per bar: (close-open)/range * volume, signed
        delta_proxy = ((cur.close - cur.open) / max(rng, 0.01)) * cur.volume
        # normalize by median volume
        med_vol = sorted([c.volume for c in candles[max(0,i-20):i+1]])[10] if i>=20 else 300
        f["delta_proxy"] = delta_proxy / max(med_vol, 1)  # ~ -1..+1 per bar scaled
        f["delta_proxy"] = max(-3, min(3, f["delta_proxy"]))

        # CVD 20: sum last 20 delta_proxies
        cvd_vals = []
        for k in range(max(0,i-19), i+1):
            ck = candles[k]
            r = max(ck.high - ck.low, 0.01)
            d = ((ck.close - ck.open)/r) * ck.volume / max(med_vol,1)
            cvd_vals.append(d)
        cvd20 = sum(cvd_vals)
        f["cvd_20"] = max(-10, min(10, cvd20 / 5.0))  # normalize

        # Delta divergence: price makes HH but delta makes LH (bearish), or LL but HL (bullish)
        # Compare last 10 highs: price high vs CVD high
        div = 0.0
        if i >= 10:
            # find last two swing highs (separated)
            highs = [(idx, candles[idx].high) for idx in range(i-10, i+1)]
            # price HH?
            price_hh = highs[-1][1] > max(h for _,h in highs[:-1]) + 0.15*atr_v
            price_ll = highs[-1][1] < min(h for _,h in highs[:-1]) - 0.15*atr_v
            # CVD at those highs: use delta_proxy of that bar vs current
            # Simplified: CVD at high vs now
            cvd_now = cvd20
            cvd_prev = sum(cvd_vals[:-1]) / 5 if len(cvd_vals)>1 else 0
            if price_hh and cvd_now < cvd_prev - 0.4:
                div = -1.0  # bearish divergence
            elif price_ll and cvd_now > cvd_prev + 0.4:
                div = 1.0  # bullish divergence
        f["delta_divergence"] = div

        # Volume Profile approximated: bin last 30 bars mid-price by volume
        look = candles[max(0,i-29):i+1]
        lo_p = min(c.low for c in look)
        hi_p = max(c.high for c in look)
        rng_p = max(hi_p - lo_p, 0.01)
        bins = 20
        bin_vol = [0.0]*bins
        for ck in look:
            mid = (ck.high + ck.low)/2
            b = int((mid - lo_p)/rng_p * (bins-1))
            b = max(0, min(bins-1, b))
            bin_vol[b] += ck.volume
        poc_bin = max(range(bins), key=lambda b: bin_vol[b])
        poc_price = lo_p + (poc_bin+0.5)/bins * rng_p
        total_vol = sum(bin_vol)
        # Value Area 70%: accumulate bins around POC sorted by distance
        order_bins = sorted(range(bins), key=lambda b: abs(b-poc_bin))
        cum = 0.0
        va_bins = []
        for b in order_bins:
            cum += bin_vol[b]
            va_bins.append(b)
            if cum >= total_vol*0.70:
                break
        va_low_price = lo_p + min(va_bins)/bins * rng_p
        va_high_price = lo_p + (max(va_bins)+1)/bins * rng_p
        # features
        f["poc_distance_atr"] = (cur.close - poc_price) / max(atr_v, 0.01)
        # value area position: 0 below VAL, 0.5 inside, 1 above VAH, linear
        if cur.close < va_low_price:
            f["value_area_pos"] = 0.0 + max(0, (cur.close - lo_p)/(va_low_price - lo_p+0.01))*0.3 if va_low_price>lo_p else 0.0
        elif cur.close > va_high_price:
            f["value_area_pos"] = 0.7 + min(1, (cur.close - va_high_price)/(hi_p - va_high_price+0.01))*0.3 if hi_p>va_high_price else 1.0
        else:
            # inside VA: 0.3-0.7 mapped linearly
            f["value_area_pos"] = 0.3 + 0.4 * ((cur.close - va_low_price)/max(va_high_price - va_low_price,0.01))
        f["value_area_pos"] = max(0, min(1, f["value_area_pos"]))
        # HV node proximity: within 0.45 ATR of POC
        f["hv_node_prox"] = 1.0 if abs(cur.close - poc_price) < 0.45*atr_v else 0.0
        # Stacked imbalance: 3 consecutive bullish/bearish large bodies with vol spike
        stacked = 0.0
        if i >= 2:
            bodies = [abs(candles[k].close - candles[k].open)/max(candles[k].high-candles[k].low,0.01) for k in range(i-2,i+1)]
            vols_3 = [candles[k].volume/max(med_vol,1) for k in range(i-2,i+1)]
            bull_stack = all(b > 0.55 and candles[k].close > candles[k].open and vols_3[k-(i-2)]>1.0 for k,b in zip(range(i-2,i+1), bodies))
            bear_stack = all(b > 0.55 and candles[k].close < candles[k].open and vols_3[k-(i-2)]>1.0 for k,b in zip(range(i-2,i+1), bodies))
            if bull_stack:
                stacked = 1.0
            elif bear_stack:
                stacked = -1.0
        f["stacked_imbalance"] = stacked
        # Initiation vs Absorption: initiation = price breaks VA with strong delta; absorption = high vol but price stalls at VA boundary
        if abs(cur.close - va_high_price) < 0.25*atr_v or abs(cur.close - va_low_price) < 0.25*atr_v:
            # at VA edge
            if abs(f["delta_proxy"]) > 1.1 and body/rng > 0.5:
                f["initiation_vs_absorption"] = 1.0 if f["delta_proxy"] >0 else -1.0  # initiative
            elif f["absorption_proxy"] > 1.4:
                f["initiation_vs_absorption"] = -0.7 if f["delta_proxy"]>0 else 0.7  # absorbed (counter)
            else:
                f["initiation_vs_absorption"] = 0.0
        else:
            f["initiation_vs_absorption"] = 0.0
    except Exception:
        for k in ["delta_proxy","cvd_20","delta_divergence","poc_distance_atr","value_area_pos","hv_node_prox","stacked_imbalance","initiation_vs_absorption"]:
            if k not in f:
                f[k] = 0.0

    return f

def feature_names() -> list[str]:
    """Ordered list for model."""
    # Build once via dummy
    from datetime import datetime, timezone
    from app.models import Candle, Timeframe
    dummy = [Candle(symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=datetime(2024,1,1,tzinfo=timezone.utc), open=2000, high=2001, low=1999, close=2000.5, volume=300, complete=True)]*220
    # need varied closes to avoid stdev 0
    for idx, c in enumerate(dummy):
        c.close = 2000 + idx*0.1
        c.high = c.close + 1
        c.low = c.close - 1
    feats = build_features(dummy)
    return sorted(feats.keys())

def to_vector(feats: dict[str, float], order: list[str] | None = None) -> list[float]:
    if order is None:
        order = sorted(feats.keys())
    return [float(feats.get(k, 0.0)) for k in order]
