from statistics import median
from app.models import Candle, Direction, Impact, StrategyContext, TradeSignal
from app.services.indicators import atr, ema, ichimoku, rsi, session_vwap


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def evaluate_scalp(candles: list[Candle], context: StrategyContext) -> TradeSignal:
    """Evaluate a closed-bar 7/22/44 Ichimoku XAU/USD scalp.

    Signals are deterministic and only valid after a candle closes. The function
    does not place orders; execution must repeat spread/event/risk checks atomically.
    """
    if len(candles) < 200:
        return TradeSignal(action=Direction.NO_TRADE, confidence=0, blockers=["At least 200 closed candles required"])

    frame = candles[-1].timeframe
    values = ichimoku(candles)
    ema200 = ema(candles, 200)
    vwap = session_vwap(candles)
    rsi7 = rsi(candles, 7)
    atr14 = atr(candles, 14)
    i, previous = len(candles) - 1, len(candles) - 2
    t, k = values["tenkan"][i], values["kijun"][i]
    prev_t, prev_k = values["tenkan"][previous], values["kijun"][previous]
    span_a, span_b = values["span_a"][i], values["span_b"][i]
    current_atr, current_rsi = atr14[i], rsi7[i]
    assert all(v is not None for v in (t, k, prev_t, prev_k, span_a, span_b, current_atr, current_rsi))
    t, k, prev_t, prev_k = float(t), float(k), float(prev_t), float(prev_k)
    span_a, span_b, current_atr, current_rsi = float(span_a), float(span_b), float(current_atr), float(current_rsi)
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
        return TradeSignal(action=Direction.NO_TRADE, confidence=38, blockers=["No fresh Tenkan/Kijun cross in last two closed bars"])

    direction = Direction.BUY if bull_cross else Direction.SELL
    long = direction is Direction.BUY
    reasons: list[str] = []
    blockers: list[str] = []
    score = 20.0
    reasons.append("Fresh bullish Tenkan/Kijun cross" if long else "Fresh bearish Tenkan/Kijun cross")

    checks = [
        (current.close > cloud_top + 0.08 * current_atr if long else current.close < cloud_bottom - 0.08 * current_atr, 18, "Price accepted beyond Kumo"),
        (span_a > span_b if long else span_a < span_b, 10, "Forward cloud aligned"),
        (current.close > candles[i - 22].high if long else current.close < candles[i - 22].low, 10, "Chikou clearance validated"),
        (current.close > ema200[i] if long else current.close < ema200[i], 15, "EMA 200 macro trend aligned"),
        (current.close > vwap[i] if long else current.close < vwap[i], 12, "Session VWAP aligned"),
        (52 <= current_rsi <= 72 if long else 28 <= current_rsi <= 48, 10, f"RSI 7 confirms without exhaustion ({current_rsi:.1f})"),
    ]
    for passed, weight, reason in checks:
        if passed:
            score += weight
            reasons.append(reason)
        else:
            blockers.append(reason.replace("aligned", "not aligned").replace("validated", "failed"))

    news = context.news
    if news and news.direction in (Direction.BUY, Direction.SELL):
        agrees = news.direction is direction
        score += 5 if agrees else -12
        (reasons if agrees else blockers).append(f"{news.impact.value.lower()}-impact news {'agrees' if agrees else 'conflicts'} ({news.confidence:.0f}%)")
        if not agrees and news.impact is Impact.HIGH and news.confidence >= 75:
            blockers.append("Hard gate: high-impact sentiment conflict")

    if context.higher_timeframe_bias is direction:
        score += 5
        reasons.append("5m trend confirms execution timeframe")
    elif context.higher_timeframe_bias not in (Direction.NEUTRAL, direction):
        score -= 10
        blockers.append("1m/5m directional disagreement")

    recent_atrs = [float(v) for v in atr14[-60:-1] if v is not None]
    if context.event_risk:
        blockers.append("Hard gate: scheduled high-impact release window")
    if context.spread > context.typical_spread * 2:
        blockers.append("Hard gate: spread exceeds 2× rolling median")
    if current_atr > median(recent_atrs) * 2.2:
        blockers.append("Hard gate: volatility shock exceeds 2.2× median ATR")

    candle_range = max(current.high - current.low, 0.01)
    body_quality = abs(current.close - current.open) / candle_range
    volumes = [c.volume for c in candles[-31:-1]]
    if body_quality < 0.35:
        score -= 6
        blockers.append("Weak breakout body / wick rejection risk")
    if current.volume < median(volumes) * 0.60:
        score -= 7
        blockers.append("Low tick-volume participation")

    hard_gate = any(item.startswith("Hard gate") for item in blockers)
    score = round(_clamp(score, 0, 100), 1)
    if hard_gate or score < 72:
        return TradeSignal(action=Direction.NO_TRADE, confidence=score, reasons=reasons, blockers=blockers)

    entry = current.close
    structure = min(c.low for c in candles[-6:]) if long else max(c.high for c in candles[-6:])
    raw_distance = entry - (structure - 0.15 * current_atr) if long else (structure + 0.15 * current_atr) - entry
    stop_distance = _clamp(raw_distance, 0.90 * current_atr, 1.40 * current_atr)
    target_multiple = 2.0 if score >= 85 else 1.8
    stop = entry - stop_distance if long else entry + stop_distance
    target = entry + stop_distance * target_multiple if long else entry - stop_distance * target_multiple

    return TradeSignal(
        action=direction, confidence=score,
        entry=round(entry, 2), stop_loss=round(stop, 2), take_profit=round(target, 2),
        risk_reward=target_multiple,
        expires_after_seconds=frame.seconds * (3 if frame.value == "1m" else 2),
        reasons=reasons, blockers=blockers,
    )


def should_exit(signal: TradeSignal, candles_since_entry: list[Candle], kijun: float) -> tuple[bool, str]:
    """Closed-bar exit policy; broker-side protective stops remain authoritative."""
    if signal.action not in (Direction.BUY, Direction.SELL) or not candles_since_entry:
        return False, "No open directional signal"
    latest = candles_since_entry[-1]
    long = signal.action is Direction.BUY
    if signal.stop_loss is not None and (latest.low <= signal.stop_loss if long else latest.high >= signal.stop_loss):
        return True, "Protective stop reached"
    if signal.take_profit is not None and (latest.high >= signal.take_profit if long else latest.low <= signal.take_profit):
        return True, "Take-profit reached"
    if (latest.close < kijun if long else latest.close > kijun):
        return True, "Closed beyond Kijun invalidation"
    max_bars = 8 if latest.timeframe.value == "1m" else 6
    if len(candles_since_entry) >= max_bars:
        return True, "Time stop reached"
    return False, "Position remains valid"
