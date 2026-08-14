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
