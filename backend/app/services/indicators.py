from app.models import Candle


def _rolling_midpoint(candles: list[Candle], period: int) -> list[float | None]:
    result: list[float | None] = [None] * len(candles)
    for i in range(period - 1, len(candles)):
        window = candles[i - period + 1 : i + 1]
        result[i] = (max(c.high for c in window) + min(c.low for c in window)) / 2
    return result


def ema(candles: list[Candle], period: int = 200) -> list[float]:
    alpha = 2 / (period + 1)
    output = [candles[0].close]
    for candle in candles[1:]:
        output.append(candle.close * alpha + output[-1] * (1 - alpha))
    return output

def ema_values(values: list[float], period: int) -> list[float | None]:
    """EMA on arbitrary float series."""
    if len(values) < period:
        return [None] * len(values)
    alpha = 2 / (period + 1)
    out: list[float | None] = [None] * len(values)
    # SMA seed
    sma = sum(values[:period]) / period
    out[period - 1] = sma
    prev = sma
    for i in range(period, len(values)):
        prev = values[i] * alpha + prev * (1 - alpha)
        out[i] = prev
    return out


def rsi(candles: list[Candle], period: int = 7) -> list[float | None]:
    output: list[float | None] = [None] * len(candles)
    if len(candles) <= period:
        return output
    gains, losses = [], []
    for i in range(1, period + 1):
        change = candles[i].close - candles[i - 1].close
        gains.append(max(change, 0)); losses.append(max(-change, 0))
    avg_gain, avg_loss = sum(gains) / period, sum(losses) / period
    output[period] = 100 if avg_loss == 0 else 100 - (100 / (1 + avg_gain / avg_loss))
    for i in range(period + 1, len(candles)):
        change = candles[i].close - candles[i - 1].close
        avg_gain = (avg_gain * (period - 1) + max(change, 0)) / period
        avg_loss = (avg_loss * (period - 1) + max(-change, 0)) / period
        output[i] = 100 if avg_loss == 0 else 100 - (100 / (1 + avg_gain / avg_loss))
    return output


def atr(candles: list[Candle], period: int = 14) -> list[float | None]:
    output: list[float | None] = [None] * len(candles)
    true_ranges = [candles[0].high - candles[0].low]
    for i in range(1, len(candles)):
        c, previous = candles[i], candles[i - 1]
        true_ranges.append(max(c.high - c.low, abs(c.high - previous.close), abs(c.low - previous.close)))
    if len(candles) < period:
        return output
    value = sum(true_ranges[:period]) / period
    output[period - 1] = value
    for i in range(period, len(candles)):
        value = (value * (period - 1) + true_ranges[i]) / period
        output[i] = value
    return output


def session_vwap(candles: list[Candle]) -> list[float]:
    output, cumulative_pv, cumulative_volume = [], 0.0, 0.0
    active_day = None
    for candle in candles:
        day = candle.timestamp.date()
        if day != active_day:
            active_day, cumulative_pv, cumulative_volume = day, 0.0, 0.0
        volume = max(candle.volume, 1)
        typical = (candle.high + candle.low + candle.close) / 3
        cumulative_pv += typical * volume
        cumulative_volume += volume
        output.append(cumulative_pv / cumulative_volume)
    return output


def ichimoku(candles: list[Candle], tenkan_period=7, kijun_period=22, span_b_period=44, displacement=22):
    tenkan = _rolling_midpoint(candles, tenkan_period)
    kijun = _rolling_midpoint(candles, kijun_period)
    raw_a: list[float | None] = [None] * len(candles)
    raw_b = _rolling_midpoint(candles, span_b_period)
    for i, (t, k) in enumerate(zip(tenkan, kijun)):
        if t is not None and k is not None:
            raw_a[i] = (t + k) / 2
    # Value projected in the past is the cloud visible at the current bar.
    cloud_a = [raw_a[i - displacement] if i >= displacement else None for i in range(len(candles))]
    cloud_b = [raw_b[i - displacement] if i >= displacement else None for i in range(len(candles))]
    return {"tenkan": tenkan, "kijun": kijun, "span_a": cloud_a, "span_b": cloud_b}


# ─── Complementary methods ──────────────────────────────────────────────

def macd(candles: list[Candle], fast=12, slow=26, signal=9):
    closes = [c.close for c in candles]
    ema_fast = ema_values(closes, fast)
    ema_slow = ema_values(closes, slow)
    macd_line: list[float | None] = [None] * len(candles)
    for i in range(len(candles)):
        if ema_fast[i] is not None and ema_slow[i] is not None:
            macd_line[i] = float(ema_fast[i] - ema_slow[i])  # type: ignore
    # signal line = EMA of macd_line
    valid = [(i, v) for i, v in enumerate(macd_line) if v is not None]
    vals = [v for _, v in valid]
    sig = ema_values(vals, signal) if len(vals) >= signal else [None]*len(vals)
    # map back
    signal_line: list[float | None] = [None] * len(candles)
    hist: list[float | None] = [None] * len(candles)
    for idx, (orig_i, _) in enumerate(valid):
        if sig[idx] is not None:
            signal_line[orig_i] = sig[idx]
            hist[orig_i] = float(macd_line[orig_i] - sig[idx])  # type: ignore
    return {"macd": macd_line, "signal": signal_line, "histogram": hist}


def bollinger(candles: list[Candle], period=20, mult=2.0):
    closes = [c.close for c in candles]
    sma: list[float | None] = [None]*len(candles)
    upper: list[float | None] = [None]*len(candles)
    lower: list[float | None] = [None]*len(candles)
    for i in range(period-1, len(candles)):
        window = closes[i-period+1:i+1]
        mean = sum(window)/period
        var = sum((x-mean)**2 for x in window)/period
        std = var ** 0.5
        sma[i]=mean
        upper[i]=mean+mult*std
        lower[i]=mean-mult*std
    return {"middle": sma, "upper": upper, "lower": lower, "width": [ (u-l) if u and l else None for u,l in zip(upper, lower)] }


def stochastic(candles: list[Candle], k_period=14, d_period=3):
    k: list[float | None] = [None]*len(candles)
    for i in range(k_period-1, len(candles)):
        window = candles[i-k_period+1:i+1]
        hh = max(c.high for c in window)
        ll = min(c.low for c in window)
        denom = hh - ll
        if denom == 0:
            k[i]=50
        else:
            k[i]= 100*(candles[i].close - ll)/denom
    d: list[float | None] = [None]*len(candles)
    # %D = SMA of %K
    for i in range(len(candles)):
        if i >= k_period-1 + d_period-1:
            vals = [v for v in k[i-d_period+1:i+1] if v is not None]
            if len(vals)==d_period:
                d[i]= sum(vals)/d_period
    return {"k": k, "d": d}


def adx(candles: list[Candle], period=14):
    """Simplified ADX for trend strength."""
    n = len(candles)
    out: list[float | None] = [None]*n
    if n < period*2:
        return out
    tr = [0.0]*n
    plus_dm = [0.0]*n
    minus_dm = [0.0]*n
    for i in range(1, n):
        h, l, pc = candles[i].high, candles[i].low, candles[i-1].close
        ph, pl = candles[i-1].high, candles[i-1].low
        tr[i]= max(h-l, abs(h-pc), abs(l-pc))
        up = h - ph
        dn = pl - l
        if up > dn and up > 0:
            plus_dm[i]= up
        if dn > up and dn > 0:
            minus_dm[i]= dn
    # Wilder smoothing
    atr_s = sum(tr[1:period+1])/period
    pdm_s = sum(plus_dm[1:period+1])/period
    mdm_s = sum(minus_dm[1:period+1])/period
    dx: list[float | None] = [None]*n
    for i in range(period, n):
        if i > period:
            atr_s = (atr_s*(period-1)+ tr[i])/period
            pdm_s = (pdm_s*(period-1)+ plus_dm[i])/period
            mdm_s = (mdm_s*(period-1)+ minus_dm[i])/period
        pdi = 100*pdm_s/atr_s if atr_s else 0
        mdi = 100*mdm_s/atr_s if atr_s else 0
        denom = pdi+mdi
        dx[i]= 100*abs(pdi-mdi)/denom if denom else 0
    # ADX = Wilder MA of DX
    adx_val = sum(v for v in dx[period:period*2] if v is not None)/period
    out[period*2-1]= adx_val
    for i in range(period*2, n):
        if dx[i] is not None and out[i-1] is not None:
            out[i]= (out[i-1]*(period-1)+ dx[i])/period # type: ignore
    return out


def support_resistance(candles: list[Candle], lookback=30):
    """Simple recent swing levels."""
    if len(candles) < lookback:
        return {"support": None, "resistance": None}
    recent = candles[-lookback:]
    return {"support": min(c.low for c in recent), "resistance": max(c.high for c in recent)}
