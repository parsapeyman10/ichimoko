/**
 * Live market feed for the browser terminal. Only backend/provider candles or ticks enter the
 * chart. REST success is not proof of freshness; a WebSocket connection is not a price tick.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { apiGet, barIsCurrent, parseSocketUpdate, toChartCandles, type BackendCandle, type ChartTimeframe, type DataStatus } from './api';
import type { Candle } from './market';

export type FeedState = 'loading' | 'live' | 'polling' | 'offline';

export type FeedSnapshot = {
  timeframe: ChartTimeframe;
  state: FeedState;
  detail: string;
  candles: Candle[];
  lastPrice: number | null;
  lastBarTime: number | null;
  lastUpdate: number | null;
  provider: string;
  backendReachable: boolean;
};

const POLL_MS = 10_000;
const WS_FRESH_MS = 90_000;
const emptySnapshot = (timeframe: ChartTimeframe): FeedSnapshot => ({
  timeframe, state: 'loading', detail: 'در حال دریافت دیتای واقعی…', candles: [],
  lastPrice: null, lastBarTime: null, lastUpdate: null,
  provider: 'unknown', backendReachable: false,
});

export function useMarketFeed(timeframe: ChartTimeframe) {
  const [snapshot, setSnapshot] = useState<FeedSnapshot>(() => emptySnapshot(timeframe));
  const socketRef = useRef<WebSocket | null>(null);
  const candlesRef = useRef<Candle[]>([]);
  const generationRef = useRef(0);
  const requestRef = useRef(0);
  const lastTickAtRef = useRef(0);
  const lastWsReceivedRef = useRef(0);

  const loadHistory = useCallback(async (generation: number) => {
    if (generation !== generationRef.current) return;
    const requestId = ++requestRef.current;
    const result = await apiGet<BackendCandle[]>(`/api/v1/market/${timeframe}/candles?limit=500`);
    if (generation !== generationRef.current || requestId !== requestRef.current) return;
    const now = Date.now();
    let recentWs = now - lastWsReceivedRef.current < WS_FRESH_MS;
    if (!result.ok) {
      setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({
        ...prev, state: recentWs ? 'live' : 'offline',
        detail: result.error, backendReachable: result.status !== 0,
      }));
      return;
    }
    let rows: Candle[];
    try {
      rows = toChartCandles(result.data, timeframe, now);
    } catch (error) {
      if (!recentWs) setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({
        ...prev, state: 'offline', detail: error instanceof Error ? error.message : 'کندل نامعتبر', backendReachable: true,
      }));
      return;
    }
    const current = barIsCurrent(rows[rows.length - 1], timeframe, now);
    const previous = candlesRef.current[candlesRef.current.length - 1];
    if (previous && rows[rows.length - 1]?.time > previous.time) recentWs = false;
    if (recentWs && !current) return; // never roll back a recent provider tick to old REST data
    const latestStreamBar = recentWs ? candlesRef.current[candlesRef.current.length - 1] : undefined;
    if (latestStreamBar && latestStreamBar.time >= (rows[rows.length - 1]?.time ?? 0)) {
      rows = [...rows.filter((bar) => bar.time < latestStreamBar.time), latestStreamBar];
    }
    candlesRef.current = rows;
    setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({
      ...prev,
      state: recentWs ? 'live' : current ? 'polling' : 'offline',
      detail: recentWs ? prev.detail : current
        ? 'REST: کندل تازه است؛ زمان آخرین معاملهٔ درون کندل جداگانه منتشر نشده'
        : 'آخرین کندل منبع قدیمی است؛ دریافت موفق تاریخچه به معنی قیمت زنده نیست',
      candles: rows,
      lastPrice: recentWs ? prev.lastPrice : rows[rows.length - 1]?.close ?? null,
      lastBarTime: rows.length ? rows[rows.length - 1].time * 1000 : null,
      lastUpdate: current && !recentWs ? now : prev.lastUpdate,
      backendReachable: true,
    }));
  }, [timeframe]);

  const connectSocket = useCallback((generation: number) => {
    if (generation !== generationRef.current || socketRef.current) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${protocol}://${window.location.host}/ws/v1/market/xauusd`);
    socketRef.current = socket;
    socket.onmessage = (event) => {
      if (generation !== generationRef.current) return;
      try {
        const message = JSON.parse(event.data);
        if (message.type === 'feed.status') {
          if (message.status !== 'live') {
            lastWsReceivedRef.current = 0;
            setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({
              ...prev, state: barIsCurrent(prev.candles[prev.candles.length - 1], timeframe) ? 'polling' : 'offline',
              detail: typeof message.detail === 'string' ? message.detail : 'فید قطع شده است',
            }));
          }
          return;
        }
        const update = parseSocketUpdate(message, timeframe);
        if (!update || update.at < lastTickAtRef.current) return;
        lastTickAtRef.current = update.at;
        const rows = [...candlesRef.current];
        const last = rows[rows.length - 1];
        if (last && last.time === update.bar.time) rows[rows.length - 1] = update.bar;
        else if (!last || update.bar.time > last.time) rows.push(update.bar);
        else return; // out-of-order bar must not roll the chart backwards
        candlesRef.current = rows.slice(-800);
        lastWsReceivedRef.current = update.streaming ? Date.now() : 0;
        setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({
          ...prev, state: update.streaming ? 'live' : 'polling',
          detail: update.streaming ? '' : 'REST: تیک از کندل منبع با تأخیر دریافت شد',
          candles: candlesRef.current, lastPrice: update.price,
          lastBarTime: update.bar.time * 1000, lastUpdate: update.at, backendReachable: true,
        }));
      } catch {
        // Ignore malformed frames; never substitute fabricated bars.
      }
    };
    socket.onerror = () => socket.close();
    socket.onclose = () => {
      if (generation !== generationRef.current) return;
      socketRef.current = null;
      lastWsReceivedRef.current = 0;
      setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({
        ...prev, state: barIsCurrent(prev.candles[prev.candles.length - 1], timeframe) ? 'polling' : 'offline',
        detail: 'کانال زنده بسته شد؛ نمایش کندل‌های واقعی با برچسب وضعیت REST/آفلاین',
      }));
      void loadHistory(generation);
      window.setTimeout(() => connectSocket(generation), 15_000);
    };
  }, [timeframe, loadHistory]);

  useEffect(() => {
    const generation = ++generationRef.current;
    candlesRef.current = [];
    lastTickAtRef.current = 0;
    lastWsReceivedRef.current = 0;
    setSnapshot(emptySnapshot(timeframe));
    let poll: number | null = null;
    let freshnessTimer: number | null = null;
    void (async () => {
      const status = await apiGet<DataStatus>('/api/v1/data/status');
      if (generation !== generationRef.current) return;
      // Without a Twelve Data key the backend automatically switches to a free, keyless,
      // real spot-quote feed (Swissquote/Gold-API) — it is not a reason to stop. Only a
      // genuine, complete provider outage (backend unreachable) should block the feed.
      if (!status.ok && status.status === 0) {
        setSnapshot({ ...emptySnapshot(timeframe), state: 'offline',
          detail: 'اتصال به سرور برقرار نشد.', backendReachable: false });
        return;
      }
      if (status.ok) {
        setSnapshot((prev) => prev.timeframe !== timeframe ? prev : ({ ...prev, provider: status.data.provider }));
      }
      void loadHistory(generation);
      connectSocket(generation);
      poll = window.setInterval(() => void loadHistory(generation), POLL_MS);
      // An open socket or a pending REST request must not keep the quote "live" indefinitely.
      freshnessTimer = window.setInterval(() => {
        if (generation !== generationRef.current) return;
        const now = Date.now();
        setSnapshot((prev) => {
          if (prev.timeframe !== timeframe) return prev;
          if (prev.state === 'live' && (!prev.lastUpdate || now - prev.lastUpdate > WS_FRESH_MS)) {
            lastWsReceivedRef.current = 0;
            return { ...prev, state: 'offline', detail: 'آخرین تیک ناشر قدیمی است؛ فید زنده قابل تأیید نیست' };
          }
          if (prev.state === 'polling' && !barIsCurrent(prev.candles[prev.candles.length - 1], timeframe, now)) {
            return { ...prev, state: 'offline', detail: 'آخرین کندل REST قدیمی شده است' };
          }
          return prev;
        });
      }, 5_000);
    })();
    return () => {
      generationRef.current++;
      requestRef.current++;
      if (poll !== null) window.clearInterval(poll);
      if (freshnessTimer !== null) window.clearInterval(freshnessTimer);
      if (socketRef.current) {
        socketRef.current.onclose = null;
        socketRef.current.onmessage = null;
        socketRef.current.close();
        socketRef.current = null;
      }
    };
  }, [timeframe, loadHistory, connectSocket]);

  const refresh = useCallback(() => {
    void loadHistory(generationRef.current);
  }, [loadHistory]);

  // Prevent one render of the previous timeframe's candles/signals while effects are switching.
  return { snapshot: snapshot.timeframe === timeframe ? snapshot : emptySnapshot(timeframe), refresh };
}
