/**
 * Chart types and indicator math for the browser terminal.
 *
 * This module is intentionally free of any data generation: candles arrive from the backend
 * (real provider data) and these helpers only compute studies on top of them.
 */

export type Candle = {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export type IndicatorPoint = { time: number; value: number };

export const round = (n: number) => Math.round(n * 100) / 100;

export function ema(candles: Candle[], period: number): IndicatorPoint[] {
  if (!candles.length) return [];
  const k = 2 / (period + 1);
  let value = candles[0].close;
  return candles.map((c) => {
    value = c.close * k + value * (1 - k);
    return { time: c.time, value };
  });
}

export function vwap(candles: Candle[]): IndicatorPoint[] {
  let pv = 0;
  let volume = 0;
  return candles.map((c) => {
    const typical = (c.high + c.low + c.close) / 3;
    const barVolume = c.volume > 0 ? c.volume : 1;
    pv += typical * barVolume;
    volume += barVolume;
    return { time: c.time, value: pv / volume };
  });
}

function midpoint(candles: Candle[], index: number, period: number) {
  if (index < period - 1) return null;
  const range = candles.slice(index - period + 1, index + 1);
  return (Math.max(...range.map((c) => c.high)) + Math.min(...range.map((c) => c.low))) / 2;
}

/**
 * Ichimoku for chart display.
 *
 * `spanA/spanB` are drawn on the bar where they are computed (readable on the chart).
 * The execution engine uses the displacement-corrected cloud, which is why the drawn cloud
 * and the signal engine can deliberately disagree.
 */
export function ichimoku(candles: Candle[], settings = { tenkan: 7, kijun: 22, spanB: 44 }) {
  const tenkan: IndicatorPoint[] = [];
  const kijun: IndicatorPoint[] = [];
  const spanA: IndicatorPoint[] = [];
  const spanB: IndicatorPoint[] = [];

  candles.forEach((c, index) => {
    const t = midpoint(candles, index, settings.tenkan);
    const k = midpoint(candles, index, settings.kijun);
    const b = midpoint(candles, index, settings.spanB);
    if (t !== null) tenkan.push({ time: c.time, value: t });
    if (k !== null) kijun.push({ time: c.time, value: k });
    if (t !== null && k !== null) spanA.push({ time: c.time, value: (t + k) / 2 });
    if (b !== null) spanB.push({ time: c.time, value: b });
  });
  return { tenkan, kijun, spanA, spanB };
}

export function rsi(candles: Candle[], period = 7): (number | null)[] {
  const out: (number | null)[] = Array(candles.length).fill(null);
  if (candles.length <= period) return out;
  let gains = 0;
  let losses = 0;
  for (let i = 1; i <= period; i++) {
    const d = candles[i].close - candles[i - 1].close;
    if (d > 0) gains += d;
    else losses += -d;
  }
  let avgG = gains / period;
  let avgL = losses / period;
  out[period] = avgL === 0 ? 100 : 100 - 100 / (1 + avgG / avgL);
  for (let i = period + 1; i < candles.length; i++) {
    const d = candles[i].close - candles[i - 1].close;
    avgG = (avgG * (period - 1) + Math.max(d, 0)) / period;
    avgL = (avgL * (period - 1) + Math.max(-d, 0)) / period;
    out[i] = avgL === 0 ? 100 : 100 - 100 / (1 + avgG / avgL);
  }
  return out;
}


