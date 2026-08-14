export type Candle = {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export type IndicatorPoint = { time: number; value: number };

function mulberry32(seed: number) {
  return () => {
    let t = (seed += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function generateCandles(intervalMinutes = 1, count = 220): Candle[] {
  const random = mulberry32(4217 + intervalMinutes);
  const interval = intervalMinutes * 60;
  const now = Math.floor(Date.now() / 1000 / interval) * interval;
  let price = intervalMinutes === 1 ? 3341.8 : 3307.4;
  const candles: Candle[] = [];

  for (let i = 0; i < count; i += 1) {
    const trend = i < 46 ? -0.035 : i < 104 ? 0.095 : i < 141 ? -0.025 : 0.132;
    const wave = Math.sin(i / 7.1) * 0.115 + Math.sin(i / 17) * 0.07;
    const noise = (random() - 0.49) * (intervalMinutes === 1 ? 0.72 : 1.32);
    const open = price;
    const close = open + trend + wave + noise;
    const wick = 0.2 + random() * (intervalMinutes === 1 ? 0.46 : 0.88);
    const high = Math.max(open, close) + wick * (0.45 + random() * 0.55);
    const low = Math.min(open, close) - wick * (0.45 + random() * 0.55);
    const sessionBoost = i > 145 ? 1.4 : 1;
    const volume = Math.round((240 + random() * 620) * sessionBoost);
    candles.push({
      time: now - (count - i) * interval,
      open: round(open), high: round(high), low: round(low), close: round(close), volume,
    });
    price = close;
  }

  // Normalize the latest candle to the dashboard's realistic gold reference level.
  const shift = 3358.42 - candles[candles.length - 1].close;
  return candles.map((c) => ({
    ...c,
    open: round(c.open + shift), high: round(c.high + shift),
    low: round(c.low + shift), close: round(c.close + shift),
  }));
}

export const round = (n: number) => Math.round(n * 100) / 100;

export function ema(candles: Candle[], period: number): IndicatorPoint[] {
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
    pv += typical * c.volume;
    volume += c.volume;
    return { time: c.time, value: pv / volume };
  });
}

function midpoint(candles: Candle[], index: number, period: number) {
  if (index < period - 1) return null;
  const range = candles.slice(index - period + 1, index + 1);
  return (Math.max(...range.map((c) => c.high)) + Math.min(...range.map((c) => c.low))) / 2;
}

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
    // The visual aligns the cloud to price for readability; execution uses the displaced series server-side.
    if (t !== null && k !== null) spanA.push({ time: c.time, value: (t + k) / 2 });
    if (b !== null) spanB.push({ time: c.time, value: b });
  });
  return { tenkan, kijun, spanA, spanB };
}

export function nextCandle(last: Candle, intervalMinutes: number, tick: number): Candle {
  const pulse = Math.sin(tick / 2.6) * 0.08 + 0.035;
  const close = round(last.close + pulse + (Math.random() - 0.48) * 0.16);
  return {
    time: last.time,
    open: last.open,
    high: round(Math.max(last.high, close + Math.random() * 0.08)),
    low: round(Math.min(last.low, close - Math.random() * 0.08)),
    close,
    volume: last.volume + Math.round(Math.random() * 18 * intervalMinutes),
  };
}
