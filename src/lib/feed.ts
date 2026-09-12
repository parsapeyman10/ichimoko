/**
 * Live market feed for the browser terminal.
 *
 * Sources, in order:
 *   1. `GET /api/v1/market/{timeframe}/candles` — real candles from the backend (Twelve Data);
 *   2. `WS  /ws/v1/market/xauusd` — real ticks pushed by the backend.
 *
 * If both fail, the hook reports `offline` and hands back the last real candles it received
 * (clearly labelled with their timestamps). It never fabricates a candle or a price.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { apiGet, toChartCandles, type BackendCandle, type DataStatus } from './api';
import type { Candle } from './market';

export type FeedState = 'loading' | 'live' | 'polling' | 'offline' | 'no-key';

export type FeedSnapshot = {
  state: FeedState;
  detail: string;
  candles: Candle[];
  lastPrice: number | null;
  lastBarTime: number | null;
  lastUpdate: number | null;
  provider: string;
  backendReachable: boolean;
};

const POLL_MS = 20_000;

export function useMarketFeed(timeframe: '1m' | '5m' | '15m' | '1h') {
  const [snapshot, setSnapshot] = useState<FeedSnapshot>({
    state: 'loading',
    detail: 'در حال دریافت دیتای واقعی…',
    candles: [],
    lastPrice: null,
    lastBarTime: null,
    lastUpdate: null,
    provider: 'twelve_data',
    backendReachable: false,
  });
  const socketRef = useRef<WebSocket | null>(null);
  const candlesRef = useRef<Candle[]>([]);
  const timeframeRef = useRef(timeframe);
  timeframeRef.current = timeframe;

  const loadHistory = useCallback(async () => {
    const result = await apiGet<BackendCandle[]>(`/api/v1/market/${timeframe}/candles?limit=500`);
    if (!result.ok) {
      setSnapshot((prev) => ({
        ...prev,
        state: result.status === 0 ? 'offline' : 'offline',
        detail: result.error,
        backendReachable: false,
        // keep whatever real candles we already had
        candles: candlesRef.current,
      }));
      return;
    }
    const rows = toChartCandles(result.data);
    candlesRef.current = rows;
    setSnapshot((prev) => ({
      ...prev,
      state: 'polling',
      detail: '',
      candles: rows,
      lastPrice: rows.length ? rows[rows.length - 1].close : null,
      lastBarTime: rows.length ? rows[rows.length - 1].time * 1000 : null,
      lastUpdate: Date.now(),
      backendReachable: true,
    }));
  }, [timeframe]);

  const connectSocket = useCallback(() => {
    if (socketRef.current) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${protocol}://${window.location.host}/ws/v1/market/xauusd`);
    socketRef.current = socket;

    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.type === 'feed.status') {
          setSnapshot((prev) => ({
            ...prev,
            state: message.status === 'live' ? prev.state : 'offline',
            detail: message.detail || prev.detail,
          }));
          return;
        }
        if (message.type === 'market.update') {
          const price = message?.tick?.mid ?? (message?.tick?.bid + message?.tick?.ask) / 2;
          if (typeof price !== 'number' || !Number.isFinite(price)) return;
          const key = timeframeRef.current;
          const bar = message?.candles?.[key];
          const rows = [...candlesRef.current];
          if (bar) {
            const time = Math.floor(new Date(bar.timestamp).getTime() / 1000);
            const incoming: Candle = {
              time,
              open: bar.open,
              high: bar.high,
              low: bar.low,
              close: bar.close,
              volume: bar.volume,
            };
            const last = rows[rows.length - 1];
            if (last && last.time === time) rows[rows.length - 1] = incoming;
            else if (!last || time > last.time) rows.push(incoming);
            candlesRef.current = rows.slice(-800);
          }
          setSnapshot((prev) => ({
            ...prev,
            state: 'live',
            candles: candlesRef.current,
            lastPrice: price,
            lastBarTime: bar ? new Date(bar.timestamp).getTime() : prev.lastBarTime,
            lastUpdate: Date.now(),
            backendReachable: true,
          }));
        }
      } catch {
        /* ignore malformed frames — never substitute data */
      }
    };
    socket.onerror = () => {
      socket.close();
    };
    socket.onclose = () => {
      socketRef.current = null;
      setSnapshot((prev) => ({
        ...prev,
        state: prev.candles.length ? 'offline' : prev.state,
        detail: prev.candles.length
          ? 'کانال زنده بسته شد — همان کندل‌های واقعیِ دریافت‌شده نمایش داده می‌شود'
          : 'کانال زنده بسته شد و هنوز کندلی دریافت نشده است',
      }));
      // fall back to REST polling (still real data)
      window.setTimeout(() => {
        void loadHistory();
      }, 3_000);
    };
  }, [loadHistory]);

  useEffect(() => {
    candlesRef.current = [];
    let cancelled = false;
    (async () => {
      const status = await apiGet<DataStatus>('/api/v1/data/status');
      if (!cancelled && status.ok && !status.data.api_key_configured) {
        setSnapshot((prev) => ({
          ...prev,
          state: 'no-key',
          detail:
            'کلید Twelve Data در بک‌اند تنظیم نشده است (AURUM_TWELVE_DATA_API_KEY). بدون آن هیچ داده‌ای — نه واقعی و نه ساختگی — نمایش داده نمی‌شود.',
          backendReachable: true,
        }));
        return;
      }
      void loadHistory();
      connectSocket();
    })();

    const poll = window.setInterval(() => {
      void loadHistory();
    }, POLL_MS);

    return () => {
      cancelled = true;
      window.clearInterval(poll);
      socketRef.current?.close();
      socketRef.current = null;
    };
  }, [timeframe, loadHistory, connectSocket]);

  const refresh = useCallback(() => {
    void loadHistory();
  }, [loadHistory]);

  return { snapshot, refresh };
}
