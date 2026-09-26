import test from 'node:test';
import assert from 'node:assert/strict';
import { barIsCurrent, parseSocketUpdate, toChartCandles, type BackendCandle } from '../src/lib/api.ts';

const now = Date.parse('2026-09-24T15:16:00Z');
const row: BackendCandle = {
  symbol: 'XAU/USD', timeframe: '5m', timestamp: '2026-09-24T15:15:00Z',
  open: 3100, high: 3105, low: 3098, close: 3101, volume: 0, complete: false,
};
const valid = (patch: Partial<BackendCandle> = {}) => ({ ...row, ...patch });

const frame = (patch: Record<string, unknown> = {}, bar = row) => ({
  type: 'market.update',
  tick: { symbol: 'XAU/USD', timestamp: '2026-09-24T15:16:00Z',
    bid: 3101, ask: 3101, provider: 'twelve_data:ws', ...patch },
  candles: { '5m': bar },
});

test('verified candles sort, preserve zero volume and expire by provider bar time', () => {
  const older = valid({ timestamp: '2026-09-24T15:10:00Z', close: 3100 });
  const bars = toChartCandles([row, older], '5m', now);
  assert.equal(bars.length, 2);
  assert.equal(bars[0].time, Date.parse(older.timestamp) / 1000);
  assert.equal(bars[1].volume, 0);
  assert.equal(barIsCurrent(bars[1], '5m', now), true);
  assert.equal(barIsCurrent(bars[1], '5m', now + 10 * 60_000), false);
  assert.equal(barIsCurrent(bars[1], '5m', bars[1].time * 1000 - 1), false);
});

test('one wrong symbol, timeframe, time, price, or duplicate invalidates whole REST batch', () => {
  const corrupt = [
    valid({ symbol: 'BTC/USD' }), valid({ timeframe: '15m' }), valid({ complete: undefined as never }),
    valid({ timestamp: '2026-09-24' }), valid({ timestamp: '2026-09-24T15:20:00Z' }),
    valid({ timestamp: '2026-09-24T15:17:00Z' }), valid({ close: NaN }),
    valid({ high: 3099 }), valid({ low: 3102 }), valid({ volume: -1 }),
  ];
  for (const bad of corrupt) assert.throws(() => toChartCandles([row, bad], '5m', now));
  assert.throws(() => toChartCandles([row, row], '5m', now));
  assert.throws(() => toChartCandles({ results: [row] }, '5m', now));
});

test('only matching timely WS ticks with matching bar and known provenance count as streaming', () => {
  const update = parseSocketUpdate(frame(), '5m', now);
  assert.ok(update);
  assert.equal(update.price, 3101);
  assert.equal(update.streaming, true);
  assert.equal(parseSocketUpdate(frame({ provider: 'twelve_data:rest' }), '5m', now)?.streaming, false);
  for (const bad of [
    frame({ symbol: 'BTC/USD' }), frame({ provider: 'unknown' }), frame({ timestamp: '2026-09-24T15:00:00Z' }),
    frame({ bid: 0 }), frame({ bid: 3102, ask: 3101 }), frame({ bid: 3000, ask: 3000 }),
    frame({}, valid({ symbol: 'BTC/USD' })), frame({}, valid({ timeframe: '15m' })),
    frame({}, valid({ timestamp: '2026-09-24T15:10:00Z' })),
  ]) assert.equal(parseSocketUpdate(bad, '5m', now), null);
});
