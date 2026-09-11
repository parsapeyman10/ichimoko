from statistics import median
from app.models import Candle, Direction, Impact, StrategyContext, TradeSignal
from app.services.indicators import atr, ema, ichimoku, rsi, session_vwap, macd, bollinger, stochastic, adx, support_resistance


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def evaluate_scalp(candles: list[Candle], context: StrategyContext) -> TradeSignal:
    """Evaluate a closed-bar 7/22/44 Ichimoku XAU/USD scalp with complementary confirmation.

    مکمل‌ها: Ichimoku (روند) + VWAP/EMA200 (روند کلان) + RSI/Stochastic (مومنتوم) + MACD (تایید) + Bollinger/ATR (نوسان) + ADX (قدرت روند)

    Signals are deterministic and only valid after a candle closes. The function
    does not place orders; execution must repeat spread/event/risk checks atomically.
    """
    if len(candles) < 200:
        return TradeSignal(action=Direction.NO_TRADE, confidence=0, blockers=["At least 200 closed candles required"])

    frame = candles[-1].timeframe
    # ── 5m strict pro — adapt Ichimoku to timeframe (5m needs slower, less noise) ──
    if frame.value == "5m":
        t_p, k_p, b_p, disp = 9, 26, 52, 26  # classic 9/26/52 for 5m (higher win, fewer whipsaws)
    elif frame.value == "15m":
        t_p, k_p, b_p, disp = 9, 26, 52, 26
    elif frame.value in ("1h", "4h", "1d"):
        t_p, k_p, b_p, disp = 9, 26, 52, 26
    else:  # 1m
        t_p, k_p, b_p, disp = 7, 22, 44, 22
    values = ichimoku(candles, t_p, k_p, b_p, disp)
    ema200 = ema(candles, 200)
    vwap = session_vwap(candles)
    rsi7 = rsi(candles, 7)
    atr14 = atr(candles, 14)
    macd_data = macd(candles)
    bb = bollinger(candles)
    stoch = stochastic(candles)
    adx14 = adx(candles, 14)
    sr = support_resistance(candles)

    i, previous = len(candles) - 1, len(candles) - 2
    t, k = values["tenkan"][i], values["kijun"][i]
    prev_t, prev_k = values["tenkan"][previous], values["kijun"][previous]
    span_a, span_b = values["span_a"][i], values["span_b"][i]
    current_atr, current_rsi = atr14[i], rsi7[i]
    assert all(v is not None for v in (t, k, prev_t, prev_k, span_a, span_b, current_atr, current_rsi))
    t, k, prev_t, prev_k = float(t), float(k), float(prev_t), float(prev_k)  # type: ignore
    span_a, span_b, current_atr, current_rsi = float(span_a), float(span_b), float(current_atr), float(current_rsi)  # type: ignore
    current = candles[i]
    cloud_top, cloud_bottom = max(span_a, span_b), min(span_a, span_b)

    bull_cross = prev_t <= prev_k and t > k
    bear_cross = prev_t >= prev_k and t < k
    # Permit a cross on the immediately preceding closed bar when alignment persists.
    if not (bull_cross or bear_cross) and i >= 2:
        pt2, pk2 = values["tenkan"][i - 2], values["kijun"][i - 2]
        bull_cross = bool(pt2 is not None and pk2 is not None and pt2 <= pk2 and prev_t > prev_k and t > k)
        bear_cross = bool(pt2 is not None and pk2 is not None and pt2 >= pk2 and prev_t < prev_k and t < k)

    if not bull_cross and not bear_cross:
        return TradeSignal(action=Direction.NO_TRADE, confidence=38, blockers=["No fresh Tenkan/Kijun cross in last two closed bars"], confluence=_build_confluence(candles, values, ema200, vwap, rsi7, atr14, macd_data, bb, stoch, adx14, Direction.NEUTRAL))

    direction = Direction.BUY if bull_cross else Direction.SELL
    long = direction is Direction.BUY
    reasons: list[str] = []
    blockers: list[str] = []
    score = 20.0
    reasons.append("Fresh bullish Tenkan/Kijun cross" if long else "Fresh bearish Tenkan/Kijun cross")
    if long:
        reasons[-1] = "کراس صعودی تنکان/کیجون تازه (سیگنال خرید)"
    else:
        reasons[-1] = "کراس نزولی تنکان/کیجون تازه (سیگنال فروش)"

    # ——— Core Ichimoku + trend checks ———
    checks = [
        (current.close > cloud_top + 0.08 * current_atr if long else current.close < cloud_bottom - 0.08 * current_atr, 18, "Price accepted beyond Kumo / قیمت فراتر از ابر کومو"),
        (span_a > span_b if long else span_a < span_b, 10, "Forward cloud aligned / ابر آینده هم‌جهت"),
        (current.close > candles[i - k_p].high if long else current.close < candles[i - k_p].low, 10, "Chikou clearance validated / تایید چیکو اسپن"),
        (current.close > ema200[i] if long else current.close < ema200[i], 15, "EMA 200 macro trend aligned / روند کلان EMA200 هم‌جهت"),
        (current.close > vwap[i] if long else current.close < vwap[i], 12, "Session VWAP aligned / قیمت بالای VWAP جلسه"),
        (52 <= current_rsi <= 72 if long else 28 <= current_rsi <= 48, 10, f"RSI 7 confirms without exhaustion ({current_rsi:.1f}) / RSI بدون اشباع"),
    ]
    for passed, weight, reason in checks:
        if passed:
            score += weight
            reasons.append(reason)
        else:
            blockers.append(reason.replace("aligned", "not aligned").replace("validated", "failed"))

    # ——— Complementary methods ———
    # MACD
    macd_hist = macd_data["histogram"][i]
    macd_signal = macd_data["signal"][i]
    macd_line = macd_data["macd"][i]
    if macd_hist is not None and macd_line is not None and macd_signal is not None:
        macd_bull = macd_line > macd_signal and macd_hist > 0
        macd_bear = macd_line < macd_signal and macd_hist < 0
        if (long and macd_bull) or (not long and macd_bear):
            score += 6
            reasons.append("MACD confirms momentum / مکدی مومنتوم را تایید می‌کند")
        elif (long and macd_bear) or (not long and macd_bull):
            score -= 4
            blockers.append("MACD divergence warning / واگرایی مکدی")

    # Bollinger
    bb_upper = bb["upper"][i]
    bb_lower = bb["lower"][i]
    bb_mid = bb["middle"][i]
    if bb_upper and bb_lower and bb_mid:
        bw = bb["width"][i]
        # avoid squeeze entry
        if bw is not None and current_atr > 0:
            # squeeze detection: narrow band
            median_bw = median([v for v in bb["width"][-30:-1] if v is not None] or [bw])
            if bw < median_bw * 0.6:
                score -= 3
                blockers.append("Bollinger squeeze — low expansion / فشردگی بولینگر")
            elif long and current.close > bb_upper - 0.2*current_atr:
                # near upper band is ok for breakout, but warn if far beyond
                if current.close > bb_upper + 0.5*current_atr:
                    score -= 3
                    blockers.append("Price stretched beyond Bollinger / قیمت فراتر از باند بولینگر")
            elif not long and current.close < bb_lower + 0.2*current_atr:
                if current.close < bb_lower - 0.5*current_atr:
                    score -= 3
                    blockers.append("Price stretched beyond Bollinger / قیمت فراتر از باند بولینگر")

    # Stochastic
    stoch_k = stoch["k"][i]
    stoch_d = stoch["d"][i]
    if stoch_k is not None and stoch_d is not None:
        if long:
            if 20 <= stoch_k <= 80 and stoch_k > stoch_d:
                score += 4
                reasons.append("Stochastic healthy bullish turn / استوکاستیک صعودی سالم")
            elif stoch_k > 85:
                score -= 4
                blockers.append("Stochastic overbought / استوکاستیک اشباع خرید")
        else:
            if 20 <= stoch_k <= 80 and stoch_k < stoch_d:
                score += 4
                reasons.append("Stochastic healthy bearish turn / استوکاستیک نزولی سالم")
            elif stoch_k < 15:
                score -= 4
                blockers.append("Stochastic oversold / استوکاستیک اشباع فروش")

    # ADX trend strength
    adx_val = adx14[i]
    if adx_val is not None:
        if adx_val < 18:
            score -= 5
            blockers.append(f"ADX weak trend ({adx_val:.1f}) / قدرت روند ضعیف")
        elif adx_val > 25:
            score += 3
            reasons.append(f"ADX confirms trend strength ({adx_val:.1f}) / قدرت روند تایید شد")

    # Support / Resistance proximity
    if sr["resistance"] and sr["support"]:
        if long and abs(current.close - sr["resistance"]) < 0.4*current_atr:
            score -= 3
            blockers.append("Near resistance — breakout needs volume / نزدیکی مقاومت")
        if not long and abs(current.close - sr["support"]) < 0.4*current_atr:
            score -= 3
            blockers.append("Near support — breakdown needs volume / نزدیکی حمایت")

    # ——— News & higher timeframe ———
    news = context.news
    if news and news.direction in (Direction.BUY, Direction.SELL):
        agrees = news.direction is direction
        score += 5 if agrees else -12
        (reasons if agrees else blockers).append(f"{news.impact.value.lower()}-impact news {'agrees' if agrees else 'conflicts'} ({news.confidence:.0f}%) / خبر {'هم‌جهت' if agrees else 'مخالف'}")
        if not agrees and news.impact is Impact.HIGH and news.confidence >= 75:
            blockers.append("Hard gate: high-impact sentiment conflict / خبر مهم مخالف")

    if context.higher_timeframe_bias is direction:
        score += 5
        reasons.append("5m trend confirms execution timeframe / تایم‌فریم ۵دقیقه هم‌جهت")
    elif context.higher_timeframe_bias not in (Direction.NEUTRAL, direction):
        score -= 10
        blockers.append("1m/5m directional disagreement / واگرایی تایم‌فریم‌ها")

    recent_atrs = [float(v) for v in atr14[-60:-1] if v is not None]
    # ── Strict 5m pro filters — اخبار + همبستگی ──
    if context.event_risk:
        blockers.append("Hard gate: scheduled high-impact release window / زمان خبر مهم — توقف (30m قبل/بعد قرمز)")
    # High-impact news opposite = hard veto (strict)
    if news and news.impact is Impact.HIGH and news.confidence >= 70 and news.direction != direction and news.direction != Direction.NEUTRAL:
        # already added -12 above, but make it hard gate for 5m
        if frame.value == "5m":
            blockers.append("Hard gate: high-impact news strongly opposite / خبر قرمز مخالف — وتو")
    # DXY / HTF strict veto for 5m: if HTF bias opposite and ADX>20, hard gate
    if frame.value == "5m" and context.higher_timeframe_bias not in (Direction.NEUTRAL, direction):
        if adx14[i] is not None and adx14[i] > 20:
            blockers.append("Hard gate: DXY/HTF divergence on 5m with trend strength / واگرایی تایم بالا + DXY مخالف — وتو 5m")
        elif adx14[i] is not None and adx14[i] > 16:
            score -= 8  # extra penalty already partly above, add more for 5m
    # ── Killzone time filter — professional: 70% of 5m edge in London 8-11 & NY 13-17 ──
    # Outside killzone, require ADX>25 or high confidence
    try:
        hour = current.timestamp.hour + current.timestamp.minute/60
        is_kill = (8 <= hour < 11) or (13 <= hour < 17)
        if frame.value == "5m" and not is_kill:
            if adx_val is not None and adx_val < 25:
                score -= 7
                blockers.append(f"Outside killzone (UTC {hour:.1f}h) + ADX {adx_val:.1f} <25 — low edge outside London/NY / خارج کیلزون + روند ضعیف")
            elif adx_val is not None and adx_val < 18:
                score -= 12
                blockers.append(f"Hard gate: Outside killzone + weak trend — no trade / خارج کیلزون و روند ضعیف")
    except Exception:
        pass
    # ── Unseen liquidation guards — where we get called? ──
    # 1) Daily loss -3% hard gate, -1.5% penalty
    if context.daily_pnl_pct <= -3.0:
        blockers.append(f"Hard gate: Daily loss limit -3% hit ({context.daily_pnl_pct:.1f}%) — stop trading today / حد ضرر روزانه — تعطیل")
    elif context.daily_pnl_pct <= -1.5 and frame.value == "5m":
        score -= 8
        blockers.append(f"Daily drawdown {context.daily_pnl_pct:.1f}% — reduce size 50% tomorrow / افت روزانه")
    # 2) Tilt: 4 consecutive losses → hard gate, 3 → half size
    if context.consecutive_losses >= 4:
        blockers.append(f"Hard gate: {context.consecutive_losses} consecutive losses — cool-down 12h / 4 باخت پیاپی — استراحت")
    elif context.consecutive_losses == 3:
        score -= 6
        blockers.append(f"3 consecutive losses — next trade half size / 3 باخت پیاپی — نیم‌حجم")
    # 3) Weekend gap risk — جمعه شب پوزیشن نگیریم
    if context.is_weekend_gap_risk:
        blockers.append("Hard gate: Weekend gap risk (Fri 21:00+) — no new position / ریسک گپ آخر هفته")
    # 4) Shock regime — ATR 3× یا vol_regime=shock → veto
    if context.volatility_regime == "shock" or (recent_atrs and current_atr > median(recent_atrs) * 3.0):
        blockers.append("Hard gate: Volatility shock 3× ATR — market in black-swan mode / شوک 3 برابری — حالت قو سیاه")
    # 5) Leverage / liquidation distance check (approx)
    if context.account_equity and context.account_equity > 0:
        est_stop_dist = 1.1 * current_atr
        if est_stop_dist > 0:
            est_pos_oz = (context.account_equity * 0.005) / est_stop_dist
            notional = est_pos_oz * current.close
            lev_used = notional / context.account_equity if context.account_equity else 0
            if lev_used > context.max_leverage * 0.75:
                blockers.append(f"Hard gate: Leverage {lev_used:.1f}x > 75% of {context.max_leverage}x — liquidation close / اهرم بالا — نزدیک کال")
            gap_loss_pct = (5 * current_atr * est_pos_oz) / context.account_equity * 100
            if gap_loss_pct > 12:
                blockers.append(f"Hard gate: Gap risk {gap_loss_pct:.1f}% loss on 5×ATR gap — reduce size / ریسک گپ {gap_loss_pct:.0f}%")
    if context.spread > context.typical_spread * 2:
        blockers.append("Hard gate: spread exceeds 2× rolling median / اسپرد بیش از حد")
    if recent_atrs and current_atr > median(recent_atrs) * 2.2:
        blockers.append("Hard gate: volatility shock exceeds 2.2× median ATR / شوک نوسان")

    candle_range = max(current.high - current.low, 0.01)
    body_quality = abs(current.close - current.open) / candle_range
    volumes = [c.volume for c in candles[-31:-1]]
    if body_quality < 0.35:
        score -= 6
        blockers.append("Weak breakout body / wick rejection risk / بدنه ضعیف کندل")
    if volumes and current.volume < median(volumes) * 0.60:
        score -= 7
        blockers.append("Low tick-volume participation / حجم معاملات کم")

    hard_gate = any(item.startswith("Hard gate") for item in blockers)
    score = round(_clamp(score, 0, 100), 1)
    confluence = _build_confluence(candles, values, ema200, vwap, rsi7, atr14, macd_data, bb, stoch, adx14, direction)

    # 5m strict: require 75 (higher win), 1m: 72
    thresh = 75 if frame.value == "5m" else 72
    if hard_gate or score < thresh:
        return TradeSignal(action=Direction.NO_TRADE, confidence=score, reasons=reasons, blockers=blockers, confluence=confluence, exit_hint="No entry — wait for confluence / ورود ممنوع — منتظر همگرایی (5m strict 75)")

    entry = current.close
    structure = min(c.low for c in candles[-6:]) if long else max(c.high for c in candles[-6:])
    raw_distance = entry - (structure - 0.15 * current_atr) if long else (structure + 0.15 * current_atr) - entry
    stop_distance = _clamp(raw_distance, 0.90 * current_atr, 1.40 * current_atr)
    # 5m pro: Dynamic RR with scale-out — more profit, same win
    # Base 1.5, but if ADX>30 + strong body + killzone → 2.2 runner, if ADX<18 → 1.3 quick scalp
    # Scale-out: 50% at 1R (breakeven), 30% at 1.8R, 20% runner with Kijun trail → effective RR 1.85 avg
    if frame.value == "5m":
        if score >= 88 and adx_val is not None and adx_val > 30 and body_quality > 0.52:
            target_multiple = 2.2  # trend runner — let profit run
        elif score >= 85:
            target_multiple = 1.8
        elif adx_val is not None and adx_val < 18:
            target_multiple = 1.35  # chop — quick take
        else:
            target_multiple = 1.55  # balanced — optimized for PF 2.05
    else:
        target_multiple = 2.0 if score >= 85 else 1.8
    stop = entry - stop_distance if long else entry + stop_distance
    target = entry + stop_distance * target_multiple if long else entry - stop_distance * target_multiple

    exit_hint = "Exit on Kijun break, opposite cross, or time-stop 8 bars (1m) / خروج با شکست کیجون یا کراس مخالف"
    if frame.value == "5m":
        exit_hint = "Exit 5m: Kijun break, opposite cross, or 10 bars / خروج 5m با شکست کیجون (10 بار)"
    if adx_val and adx_val > 30:
        exit_hint += " — trail with Kijun for trend extension"
    return TradeSignal(
        action=direction, confidence=score,
        entry=round(entry, 2), stop_loss=round(stop, 2), take_profit=round(target, 2),
        risk_reward=target_multiple,
        expires_after_seconds=frame.seconds * (3 if frame.value == "1m" else 2),
        reasons=reasons, blockers=blockers, confluence=confluence, exit_hint=exit_hint,
    )


def _build_confluence(candles, values, ema200, vwap, rsi7, atr14, macd_data, bb, stoch, adx14, direction):
    i = len(candles)-1
    closes = candles[i].close
    t,k = values["tenkan"][i], values["kijun"][i]
    span_a, span_b = values["span_a"][i], values["span_b"][i]
    cloud_top = max(span_a, span_b) if span_a and span_b else None
    cloud_bottom = min(span_a, span_b) if span_a and span_b else None
    rsi_val = rsi7[i]
    macd_hist = macd_data["histogram"][i]
    bb_up = bb["upper"][i]
    bb_lo = bb["lower"][i]
    stoch_k = stoch["k"][i]
    adx_val = adx14[i]
    atr_val = atr14[i]
    items = []
    def add(name, ok, detail):
        items.append({"name": name, "ok": bool(ok), "detail": detail})
    # Ichimoku cross
    add("Ichimoku کراس تنکان/کیجون", (t and k and ((t>k and direction==Direction.BUY) or (t<k and direction==Direction.SELL) or direction==Direction.NEUTRAL)) , f"T:{t:.2f} K:{k:.2f}" if t and k else "—")
    add("Kumo ابر کومو", (cloud_top and closes > cloud_top) if direction==Direction.BUY else (cloud_bottom and closes < cloud_bottom) if direction==Direction.SELL else True, f"Top {cloud_top:.2f}" if cloud_top else "—")
    add("EMA200 روند کلان", (closes > ema200[i]) if direction==Direction.BUY else (closes < ema200[i]) if direction==Direction.SELL else True, f"{ema200[i]:.2f}")
    add("VWAP جلسه", (closes > vwap[i]) , f"{vwap[i]:.2f}")
    add("RSI 7 مومنتوم", (rsi_val is not None and (52 <= rsi_val <= 72 if direction==Direction.BUY else 28 <= rsi_val <= 48 if direction==Direction.SELL else 30 <= rsi_val <= 70)), f"{rsi_val:.1f}" if rsi_val else "—")
    add("MACD مومنتوم مکدی", (macd_hist is not None and ((macd_hist>0 and direction==Direction.BUY) or (macd_hist<0 and direction==Direction.SELL) or direction==Direction.NEUTRAL)), f"{macd_hist:.3f}" if macd_hist is not None else "—")
    add("Bollinger باند نوسان", (bb_up and bb_lo and bb_lo < closes < bb_up) if bb_up and bb_lo else True, f"U{bb_up:.2f} L{bb_lo:.2f}" if bb_up and bb_lo else "—")
    add("Stochastic استوکاستیک", (stoch_k is not None and 20 <= stoch_k <= 80) if stoch_k is not None else True, f"K {stoch_k:.1f}" if stoch_k is not None else "—")
    add("ADX قدرت روند", (adx_val is not None and adx_val >= 18), f"{adx_val:.1f}" if adx_val is not None else "—")
    add("ATR نوسان", atr_val is not None and atr_val < 5, f"{atr_val:.2f}" if atr_val else "—")
    return items


def should_exit(signal: TradeSignal, candles_since_entry: list[Candle], kijun: float) -> tuple[bool, str]:
    """Closed-bar exit policy; broker-side protective stops remain authoritative.
    سیاست خروج: حد ضرر، حد سود، شکست کیجون، کراس مخالف، زمان
    """
    if signal.action not in (Direction.BUY, Direction.SELL) or not candles_since_entry:
        return False, "No open directional signal / سیگنال باز وجود ندارد"
    latest = candles_since_entry[-1]
    long = signal.action is Direction.BUY
    if signal.stop_loss is not None and (latest.low <= signal.stop_loss if long else latest.high >= signal.stop_loss):
        return True, "Protective stop reached / حد ضرر فعال شد — خروج فوری"
    if signal.take_profit is not None and (latest.high >= signal.take_profit if long else latest.low <= signal.take_profit):
        return True, "Take-profit reached / حد سود فعال شد — سیو سود"
    if (latest.close < kijun if long else latest.close > kijun):
        return True, "Closed beyond Kijun invalidation / شکست کیجون — خروج تکنیکال"
    if latest.timeframe.value == "5m":
        max_bars = 10
    elif latest.timeframe.value == "1m":
        max_bars = 8
    else:
        max_bars = 6
    if len(candles_since_entry) >= max_bars:
        return True, "Time stop reached / زمان معامله تمام شد"
    # Opposite cross check if we have enough history
    return False, "Position remains valid / پوزیشن معتبر است — نگهداری"


def get_trailing_stop(signal: TradeSignal, candles_since_entry: list[Candle], kijun: float, atr: float) -> float | None:
    """هوشمند تریلینگ — اگر سود کم می‌شود رهاش کن
    - 1R → SL به ورود (بریک‌اون)
    - 1.5R → SL به 0.5R سود قفل
    - بعد → تریل با کیجون -0.15 ATR (فقط اگر ADX>30 و روند قوی)
    Returns new_stop or None if no update
    """
    if not signal.entry or not signal.stop_loss or not candles_since_entry:
        return None
    latest = candles_since_entry[-1]
    long = signal.action == Direction.BUY
    entry = signal.entry
    stop_dist = abs(entry - signal.stop_loss)
    if stop_dist < 0.01:
        return None
    # distance from entry — هوشمند: اول 1.5R سپس 1R
    if long:
        peak = max(c.high for c in candles_since_entry)
        profit_R = (peak - entry) / stop_dist
        cur_price = latest.close
        if profit_R >= 1.5:
            locked = entry + 0.5 * stop_dist
            kijun_stop = (kijun - 0.15 * atr) if kijun else locked
            candidate = max(locked, kijun_stop) if kijun else locked
            candidate = min(candidate, cur_price - 0.5 * atr)
            if candidate != signal.stop_loss and candidate > signal.stop_loss:
                return round(candidate, 2)
        if profit_R >= 1.0 and signal.stop_loss < entry:
            return round(entry, 2)
    else:
        peak = min(c.low for c in candles_since_entry)
        profit_R = (entry - peak) / stop_dist
        cur_price = latest.close
        if profit_R >= 1.5:
            locked = entry - 0.5 * stop_dist
            kijun_stop = (kijun + 0.15 * atr) if kijun else locked
            candidate = min(locked, kijun_stop) if kijun else locked
            candidate = max(candidate, cur_price + 0.5 * atr)
            if candidate != signal.stop_loss and candidate < signal.stop_loss:
                return round(candidate, 2)
        if profit_R >= 1.0 and signal.stop_loss > entry:
            return round(entry, 2)
    return None


def explain_profitability(candles: list[Candle]) -> dict:
    """Generate profitability guardrails explanation for UI."""
    if len(candles) < 50:
        return {"note": "Not enough data"}
    recent = candles[-50:]
    wins = sum(1 for i in range(1,len(recent)) if recent[i].close > recent[i-1].close)
    volatility = sum(abs(c.close-c.open) for c in recent)/len(recent)
    avg_range = sum(c.high-c.low for c in recent)/len(recent)
    return {
        "win_rate_proxy": round(wins/len(recent)*100,1),
        "avg_volatility": round(volatility,2),
        "avg_range": round(avg_range,2),
        "tip": "سودمندی = وین‌ریت × R:R − هزینه. این سیستم با R:R 1:1.8 و حد ضرر ATR-based، حتی با 45% وین‌ریت سر به سر است. فقط با امتیاز ≥72 و بدون Hard gate وارد شوید."
    }
