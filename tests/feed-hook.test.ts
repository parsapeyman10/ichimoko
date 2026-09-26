import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { useMarketFeed } from '../src/lib/feed';
import type { BackendCandle, ChartTimeframe } from '../src/lib/api';

class FakeWebSocket {
  static sockets: FakeWebSocket[] = [];
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(_url: string) { FakeWebSocket.sockets.push(this); }
  close() { this.onclose?.(); }
  emit(frame: unknown) { this.onmessage?.({ data: JSON.stringify(frame) }); }
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); FakeWebSocket.sockets = []; });
const reply = (payload: unknown) => ({ ok: true, status: 200, text: async () => JSON.stringify(payload) });
const row = (frame: ChartTimeframe, close = 3101, ageMs = 0): BackendCandle => {
  const step = { '1m': 60_000, '5m': 300_000, '15m': 900_000, '1h': 3_600_000 }[frame];
  const start = Math.floor((Date.now() - ageMs) / step) * step;
  return { symbol: 'XAU/USD', timeframe: frame, timestamp: new Date(start).toISOString(),
    open: 3100, high: Math.max(3105, close), low: Math.min(3098, close), close, volume: 0, complete: false };
};
const setup = (get: (path: string) => Promise<ReturnType<typeof reply>>) => {
  vi.stubGlobal('WebSocket', FakeWebSocket);
  vi.stubGlobal('fetch', vi.fn((path: string) => path === '/api/v1/data/status'
    ? Promise.resolve(reply({ api_key_configured: true })) : get(path)));
};

test('late 5m response after timeframe change cannot replace 15m candles or price', async () => {
  let resolveFive!: (response: ReturnType<typeof reply>) => void;
  setup((path) => path.includes('/5m/')
    ? new Promise((resolve) => { resolveFive = resolve; })
    : Promise.resolve(reply([row('15m', 3200)])));
  const { result, rerender } = renderHook(({ tf }: { tf: ChartTimeframe }) => useMarketFeed(tf),
    { initialProps: { tf: '5m' as ChartTimeframe } });
  await waitFor(() => expect(resolveFive).toBeTypeOf('function'));
  rerender({ tf: '15m' });
  await waitFor(() => expect(result.current.snapshot.state).toBe('polling'));
  expect(result.current.snapshot.lastPrice).toBe(3200);
  expect(result.current.snapshot.timeframe).toBe('15m');
  await act(async () => { resolveFive(reply([row('5m', 3101)])); await Promise.resolve(); });
  expect(result.current.snapshot.lastPrice).toBe(3200);
  expect(result.current.snapshot.candles[0].close).toBe(3200);
});

test('successful stale REST history is labelled offline and never a current quote', async () => {
  setup(async () => reply([row('5m', 3101, 86_400_000)]));
  const { result } = renderHook(() => useMarketFeed('5m'));
  await waitFor(() => expect(result.current.snapshot.state).toBe('offline'));
  expect(result.current.snapshot.backendReachable).toBe(true);
  expect(result.current.snapshot.candles).toHaveLength(1);
  expect(result.current.snapshot.detail).toContain('قدیمی');
});

test('WS REST ticks are polling, verified WS ticks are live; wrong symbol is ignored', async () => {
  setup(async () => reply([row('5m')]));
  const { result } = renderHook(() => useMarketFeed('5m'));
  await waitFor(() => expect(result.current.snapshot.state).toBe('polling'));
  const ws = FakeWebSocket.sockets[0];
  const at = new Date().toISOString();
  const base = { type: 'market.update',
    tick: { symbol: 'XAU/USD', bid: 3101, ask: 3101, timestamp: at, provider: 'twelve_data:rest' },
    candles: { '5m': row('5m') } };
  act(() => ws.emit({ ...base, tick: { ...base.tick, symbol: 'BTC/USD' } }));
  expect(result.current.snapshot.state).toBe('polling');
  act(() => ws.emit(base));
  expect(result.current.snapshot.state).toBe('polling');
  act(() => ws.emit({ ...base, tick: { ...base.tick, provider: 'twelve_data:ws', timestamp: new Date(Date.now() + 1).toISOString() } }));
  expect(result.current.snapshot.state).toBe('live');
});

test('an open WS with no further provider ticks expires without waiting for REST', async () => {
  setup(async () => reply([row('5m')]));
  const originalSetInterval = window.setInterval.bind(window);
  const originalClearInterval = window.clearInterval.bind(window);
  let checkFreshness!: () => void;
  vi.spyOn(window, 'setInterval').mockImplementation(((handler: TimerHandler, delay?: number) => {
    if (delay === 5_000) { checkFreshness = handler as () => void; return 987654; }
    return originalSetInterval(handler, delay);
  }) as typeof window.setInterval);
  vi.spyOn(window, 'clearInterval').mockImplementation(((id: number) => {
    if (id !== 987654) originalClearInterval(id);
  }) as typeof window.clearInterval);
  const { result } = renderHook(() => useMarketFeed('5m'));
  await waitFor(() => expect(result.current.snapshot.state).toBe('polling'));
  const now = Date.now();
  act(() => FakeWebSocket.sockets[0].emit({
    type: 'market.update',
    tick: { symbol: 'XAU/USD', bid: 3101, ask: 3101, timestamp: new Date(now).toISOString(), provider: 'twelve_data:ws' },
    candles: { '5m': row('5m') },
  }));
  expect(result.current.snapshot.state).toBe('live');
  vi.spyOn(Date, 'now').mockReturnValue(now + 91_000);
  act(() => checkFreshness());
  expect(result.current.snapshot.state).toBe('offline');
  expect(result.current.snapshot.detail).toContain('قدیمی');
});

test('no key does not poll REST or initiate a WebSocket', async () => {
  vi.stubGlobal('WebSocket', FakeWebSocket);
  const fetchMock = vi.fn(async () => reply({ api_key_configured: false }));
  vi.stubGlobal('fetch', fetchMock);
  const { result } = renderHook(() => useMarketFeed('5m'));
  await waitFor(() => expect(result.current.snapshot.state).toBe('no-key'));
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(FakeWebSocket.sockets).toHaveLength(0);
});
