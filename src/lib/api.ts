import type { Candle } from './market';

/**
 * Thin API client.
 *
 * Rule of this app: when the backend has no real data it answers with an explicit error.
 * The UI shows that error. There is no client-side fallback that invents candles, prices,
 * news or trades — see `src/lib/feed.ts` for the live pipeline.
 */

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: string; status: number };

function describe(status: number, payload: unknown): string {
  const detail = (payload as { detail?: unknown })?.detail;
  if (typeof detail === 'string' && detail.trim()) return detail;
  if (Array.isArray(detail) && detail.length) {
    const first = detail[0] as { msg?: string };
    if (first?.msg) return first.msg;
  }
  if (status === 503) return 'دیتای واقعی در دسترس نیست (سرویس‌دهنده داده پاسخ نداد)';
  if (status === 404) return 'مسیر API پیدا نشد';
  return `خطای API (HTTP ${status})`;
}

async function request<T>(path: string, init?: RequestInit): Promise<ApiResult<T>> {
  try {
    const response = await fetch(path, init);
    const text = await response.text();
    let payload: unknown = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
      payload = null;
    }
    if (!response.ok) {
      return { ok: false, error: describe(response.status, payload), status: response.status };
    }
    return { ok: true, data: payload as T };
  } catch {
    return {
      ok: false,
      status: 0,
      error: 'ارتباط با بک‌اند برقرار نشد — مطمئن شو سرویس روی پورت 8000 در حال اجراست',
    };
  }
}

export const apiGet = <T>(path: string) => request<T>(path, { method: 'GET' });

export const apiPost = <T>(path: string, body: unknown) =>
  request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

export type BackendCandle = {
  symbol: string;
  timeframe: string;
  timestamp: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  complete: boolean;
};

export type DataStatus = {
  provider: string;
  api_key_configured: boolean;
  symbol: string;
  feed: {
    state: string;
    detail: string | null;
    last_tick_at: string | null;
    provider: string;
  };
  timeframes: Record<string, { live_candles: number; last_bar: string | null }>;
  policy: string;
};

export type ChartTimeframe = '1m' | '5m' | '15m' | '1h';
const barSeconds: Record<ChartTimeframe, number> = { '1m': 60, '5m': 300, '15m': 900, '1h': 3600 };

/** Do not relabel another instrument/interval, malformed OHLC or a future bar as live gold. */
export function toChartCandles(payload: unknown, timeframe: ChartTimeframe, now = Date.now()): Candle[] {
  if (!Array.isArray(payload) || payload.length > 2000) throw new Error('ساختار یا حجم کندل‌های ناشر نامعتبر است');
  const seen = new Set<number>();
  return payload.map((value) => {
    const row = value as BackendCandle;
    const time = typeof row?.timestamp === 'string' &&
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(row.timestamp)
        ? Date.parse(row.timestamp) : NaN;
    const numbers = [row?.open, row?.high, row?.low, row?.close, row?.volume];
    if (row?.symbol !== 'XAU/USD' || row?.timeframe !== timeframe || typeof row.complete !== 'boolean' ||
        !Number.isFinite(time) || time <= 0 || time > now + 60_000 ||
        time % (barSeconds[timeframe] * 1000) !== 0 || seen.has(time) ||
        numbers.some((item) => typeof item !== 'number' || !Number.isFinite(item)) ||
        row.open <= 0 || row.high <= 0 || row.low <= 0 || row.close <= 0 || row.volume < 0 ||
        row.low > Math.min(row.open, row.close) || row.high < Math.max(row.open, row.close)) {
      throw new Error('نماد، بازه، زمان یا OHLC پاسخ کندل معتبر نیست');
    }
    seen.add(time);
    return { time: time / 1000, open: row.open, high: row.high, low: row.low, close: row.close, volume: row.volume };
  }).sort((a, b) => a.time - b.time);
}

export function barIsCurrent(last: Candle | undefined, timeframe: ChartTimeframe, now = Date.now()): boolean {
  return Boolean(last && now - last.time * 1000 >= 0 && now - last.time * 1000 <= barSeconds[timeframe] * 1000 + 90_000);
}

/** Backend WS frames are untrusted display input, even though they originate on our API host. */
export function parseSocketUpdate(payload: unknown, timeframe: ChartTimeframe, now = Date.now()): { price: number; at: number; bar: Candle; streaming: boolean } | null {
  const message = payload as { type?: string; tick?: { symbol?: string; timestamp?: string; provider?: string; bid?: number; ask?: number }; candles?: Record<string, BackendCandle> } | null;
  if (message?.type !== 'market.update' || message.tick?.symbol !== 'XAU/USD' ||
      !['twelve_data:ws', 'twelve_data:rest', 'spot_fallback:swissquote', 'spot_fallback:gold-api'].includes(message.tick?.provider ?? '') ||
      typeof message.tick?.timestamp !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(message.tick.timestamp)) return null;
  const at = Date.parse(message.tick.timestamp);
  const { bid, ask } = message.tick;
  if (!Number.isFinite(at) || now - at < -30_000 || now - at > 90_000 ||
      typeof bid !== 'number' || typeof ask !== 'number' || !Number.isFinite(bid) || !Number.isFinite(ask) ||
      bid <= 0 || ask <= 0 || bid > ask) return null;
  const price = (bid + ask) / 2;
  if (!Number.isFinite(price) || price <= 0 || !message.candles?.[timeframe]) return null;
  try {
    const [bar] = toChartCandles([message.candles[timeframe]], timeframe, now);
    const start = bar.time * 1000;
    if (at < start || at >= start + barSeconds[timeframe] * 1000 ||
        Math.abs(bar.close - price) > Math.max(0.01, price * 0.000001)) return null;
    return { price, at, bar, streaming: message.tick.provider === 'twelve_data:ws' };
  } catch {
    return null;
  }
}

export const toBackendCandles = (rows: { time: number; open: number; high: number; low: number; close: number; volume: number }[], timeframe: string) =>
  rows.map((row) => ({
    symbol: 'XAU/USD',
    timeframe,
    timestamp: new Date(row.time * 1000).toISOString(),
    open: row.open,
    high: row.high,
    low: row.low,
    close: row.close,
    volume: row.volume,
    complete: true,
  }));
