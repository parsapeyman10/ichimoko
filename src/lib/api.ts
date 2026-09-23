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
  } catch (error) {
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

export const toChartCandles = (rows: BackendCandle[]) =>
  rows.map((row) => ({
    time: Math.floor(new Date(row.timestamp).getTime() / 1000),
    open: row.open,
    high: row.high,
    low: row.low,
    close: row.close,
    volume: row.volume,
  }));

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
