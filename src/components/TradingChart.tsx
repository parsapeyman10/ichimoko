import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ColorType,
  CrosshairMode,
  LineStyle,
  createChart,
  type IChartApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import { ChevronDown, ExternalLink, Layers3, RotateCcw } from 'lucide-react';
import { ema, ichimoku, vwap, type Candle } from '../lib/market';
import { MARKET_SYMBOL } from '../lib/api';

const colors = {
  gold: '#f4bd48',
  cyan: '#57c7d4',
  purple: '#9d83e9',
  up: '#24c69a',
  down: '#ef5d6c',
};

export type ChartSignalLevels = {
  entry?: number | null;
  stop_loss?: number | null;
  take_profit?: number | null;
  action?: string;
} | null;

/**
 * App symbol → exact TradingView ticker. The same mapping exists on the Android side
 * (`TradingViewSymbols.kt`) so a chart, a price and a signal can never point at different
 * venues. Unknown symbols return null instead of silently falling back to gold.
 */
const TV_SYMBOLS: Record<string, string> = {
  'XAU/USD': 'OANDA:XAUUSD',
  'XAG/USD': 'OANDA:XAGUSD',
  'USOIL': 'TVC:USOIL',
  'UKOIL': 'TVC:UKOIL',
  'COPPER': 'CAPITALCOM:COPPER',
  'EUR/USD': 'OANDA:EURUSD',
  'GBP/USD': 'OANDA:GBPUSD',
  'USD/JPY': 'OANDA:USDJPY',
  'AUD/USD': 'OANDA:AUDUSD',
  'USD/CHF': 'OANDA:USDCHF',
  'USD/CAD': 'OANDA:USDCAD',
  'NZD/USD': 'OANDA:NZDUSD',
  'EUR/GBP': 'OANDA:EURGBP',
  'EUR/JPY': 'OANDA:EURJPY',
  'GBP/JPY': 'OANDA:GBPJPY',
  'AUD/JPY': 'OANDA:AUDJPY',
  'CAD/JPY': 'OANDA:CADJPY',
  'CHF/JPY': 'OANDA:CHFJPY',
  'EUR/CHF': 'OANDA:EURCHF',
  'GBP/CHF': 'OANDA:GBPCHF',
  'BTC/USDT': 'BINANCE:BTCUSDT',
  'ETH/USDT': 'BINANCE:ETHUSDT',
  'SOL/USDT': 'BINANCE:SOLUSDT',
  'BNB/USDT': 'BINANCE:BNBUSDT',
  'XRP/USDT': 'BINANCE:XRPUSDT',
  'DOGE/USDT': 'BINANCE:DOGEUSDT',
  'ADA/USDT': 'BINANCE:ADAUSDT',
  'AAPL': 'NASDAQ:AAPL',
  'TSLA': 'NASDAQ:TSLA',
  'NVDA': 'NASDAQ:NVDA',
  'MSFT': 'NASDAQ:MSFT',
  'AMZN': 'NASDAQ:AMZN',
  'GOOGL': 'NASDAQ:GOOGL',
  'META': 'NASDAQ:META',
};

export function tradingViewSymbol(symbol: string): string | null {
  const clean = symbol.trim().toUpperCase();
  return TV_SYMBOLS[clean] ?? (clean.includes('USDT') ? `BINANCE:${clean.replace('/', '')}` : null);
}

function tradingViewInterval(timeframe: '1m' | '5m' | '15m' | '1h'): string {
  return timeframe === '1h' ? '60' : timeframe.replace('m', '');
}

/** Official TradingView Advanced Chart embed — the same widget the phone app renders. */
export function tradingViewWidgetUrl(tvSymbol: string, timeframe: '1m' | '5m' | '15m' | '1h', widgetId: string): string {
  const params = new URLSearchParams({
    frameElementId: widgetId,
    symbol: tvSymbol,
    interval: tradingViewInterval(timeframe),
    hidesidetoolbar: '0',
    symboledit: '1',
    saveimage: '0',
    toolbarbg: '0b0e13',
    theme: 'dark',
    style: '1',
    timezone: 'Etc/UTC',
    withdateranges: '1',
    hideideas: '1',
    locale: 'en',
    studies: '["STD;Ichimoku Cloud"]',
  });
  return `https://s.tradingview.com/widgetembed/?${params.toString()}`;
}

type Props = {
  timeframe: '1m' | '5m' | '15m' | '1h';
  onTimeframeChange: (value: '1m' | '5m' | '15m' | '1h') => void;
  candles: Candle[];
  levels: ChartSignalLevels;
  feedLabel: string;
  feedTone: 'live' | 'stale' | 'offline';
  lastBarTime: number | null;
  symbol?: string;
};

type Mode = 'tradingview' | 'native';

export default function TradingChart({
  timeframe,
  onTimeframeChange,
  candles,
  levels,
  feedLabel,
  feedTone,
  lastBarTime,
  symbol = MARKET_SYMBOL,
}: Props) {
  // TradingView is the chart (restored default); the app's own renderer stays one tap away
  // for the bar-by-bar studies view and for offline / blocked-widget situations.
  const [mode, setMode] = useState<Mode>('tradingview');
  const [widgetLoaded, setWidgetLoaded] = useState(false);
  const [widgetSlow, setWidgetSlow] = useState(false);

  const tvSymbol = useMemo(() => tradingViewSymbol(symbol), [symbol]);
  const widgetId = `aurum_tv_${timeframe}`;
  const widgetUrl = useMemo(
    () => (tvSymbol ? tradingViewWidgetUrl(tvSymbol, timeframe, widgetId) : null),
    [tvSymbol, timeframe, widgetId],
  );

  useEffect(() => {
    if (mode !== 'tradingview') return;
    setWidgetLoaded(false);
    setWidgetSlow(false);
    const timer = window.setTimeout(() => setWidgetSlow(true), 9000);
    return () => window.clearTimeout(timer);
  }, [mode, widgetUrl]);

  const lastCandle = candles.length ? candles[candles.length - 1] : null;
  const referencePrice = lastCandle?.close ?? levels?.entry ?? null;

  const levelRows = (() => {
    if (!levels) return [];
    const rows: { label: string; value: number; tone: string }[] = [];
    if (typeof levels.entry === 'number' && Number.isFinite(levels.entry)) rows.push({ label: 'ورود', value: levels.entry, tone: colors.gold });
    if (typeof levels.stop_loss === 'number' && Number.isFinite(levels.stop_loss)) rows.push({ label: 'حد ضرر', value: levels.stop_loss, tone: colors.down });
    if (typeof levels.take_profit === 'number' && Number.isFinite(levels.take_profit)) rows.push({ label: 'حد سود', value: levels.take_profit, tone: colors.up });
    return rows;
  })();

  const riskReward = (() => {
    const entry = levels?.entry;
    const stop = levels?.stop_loss;
    const target = levels?.take_profit;
    if (typeof entry !== 'number' || typeof stop !== 'number' || typeof target !== 'number') return null;
    const risk = Math.abs(entry - stop);
    const reward = Math.abs(target - entry);
    if (!Number.isFinite(risk) || !Number.isFinite(reward) || risk <= 0) return null;
    return reward / risk;
  })();

  const pct = (value: number) => (referencePrice && referencePrice > 0
    ? `${value > referencePrice ? '+' : ''}${(((value - referencePrice) / referencePrice) * 100).toFixed(2)}٪`
    : '—');

  return (
    <section className="chart-card panel">
      <div className="chart-toolbar">
        <div className="symbol-lockup">
          <div className="asset-orb">{symbol.startsWith('XAU') ? 'Au' : symbol.slice(0, 2)}</div>
          <div>
            <strong>{symbol.replace('/', ' / ')}</strong>
            <span>{mode === 'tradingview' ? `TradingView · ${tvSymbol ?? 'نماد نگاشت نشده'}` : 'رندر بومی اپ روی کندل‌های بک‌اند'}</span>
          </div>
        </div>
        <fieldset className="timeframes">
          <legend className="sr-only">Chart timeframe</legend>
          {(['1m', '5m', '15m', '1h'] as const).map((tf) => (
            <button type="button" className={timeframe === tf ? 'active' : ''} onClick={() => onTimeframeChange(tf)} key={tf}>{tf}</button>
          ))}
        </fieldset>
        <div className="chart-actions">
          <button
            type="button"
            className={mode === 'tradingview' ? 'study active' : 'study'}
            title="چارت TradingView (ویدجت رسمی)"
            onClick={() => setMode('tradingview')}
          >
            TradingView
          </button>
          <button
            type="button"
            className={mode === 'native' ? 'study active' : 'study'}
            title="چارت بومی اپ روی کندل‌های واقعی بک‌اند"
            onClick={() => setMode('native')}
          >
            بومی <span>{candles.length}</span>
          </button>
          {mode === 'native' && (
            <button type="button" aria-label="Reset chart" onClick={() => window.dispatchEvent(new Event('aurum:reset-native-chart'))}>
              <RotateCcw size={15}/>
            </button>
          )}
          {tvSymbol && (
            <a
              className="chart-link"
              href={`https://www.tradingview.com/chart/?symbol=${encodeURIComponent(tvSymbol)}`}
              target="_blank"
              rel="noreferrer noopener"
            >
              <ExternalLink size={13}/> TradingView
            </a>
          )}
        </div>
      </div>

      {mode === 'tradingview' ? (
        <>
          <div className="chart-meta">
            <span>وضعیت: <b>ویدجت رسمی TradingView</b></span>
            <span>نماد: <b>{tvSymbol ?? '—'}</b></span>
            <span>تایم‌فریم: <b>{timeframe}</b></span>
            <div className="legend">
              <i className="dot cyan"/>Tenkan <i className="dot purple"/>Kijun <i className="dot gold"/>Ichimoku Study
            </div>
          </div>
          <div className="chart-body tv-body">
            {tvSymbol ? (
              <>
                {!widgetLoaded && (
                  <div className="tv-placeholder">
                    <b>{widgetSlow ? 'ویدجت TradingView بارگذاری نشد یا شبکه آن را بسته است' : 'در حال بارگذاری ویدجت رسمی TradingView…'}</b>
                    <span>
                      {widgetSlow
                        ? 'چارت بومی اپ با همان کندل‌های واقعی بک‌اند همیشه در دسترس است.'
                        : 'این ویدجت قیمت واقعی را مستقیم از TradingView می‌گیرد و به بک‌اند نیازی ندارد.'}
                    </span>
                    {widgetSlow && (
                      <button type="button" onClick={() => setMode('native')}>نمایش چارت بومی</button>
                    )}
                  </div>
                )}
                <iframe
                  key={widgetUrl}
                  className="tv-frame"
                  title={`TradingView chart ${tvSymbol} ${timeframe}`}
                  src={widgetUrl ?? undefined}
                  onLoad={() => setWidgetLoaded(true)}
                  allowFullScreen
                  referrerPolicy="origin"
                />
                {levelRows.length > 0 && (
                  <div className="tv-hud">
                    <div className="tv-hud-head">
                      <span>طرح سیگنال (کاغذی)</span>
                      <b style={{ color: levels?.action === 'SELL' ? colors.down : colors.up }}>
                        {levels?.action === 'SELL' ? 'SHORT' : 'LONG'}
                      </b>
                    </div>
                    {levelRows.map((row) => (
                      <div className="tv-hud-row" key={row.label}>
                        <span>{row.label}</span>
                        <b style={{ color: row.tone }}>{row.value.toFixed(2)}</b>
                        <i>{pct(row.value)}</i>
                      </div>
                    ))}
                    {riskReward != null && (
                      <div className="tv-hud-row rr">
                        <span>R:R</span>
                        <b style={{ color: colors.cyan }}>1 : {riskReward.toFixed(2)}</b>
                      </div>
                    )}
                    <small>خطوط فقط طرح سیگنال‌اند؛ معاملهٔ ثبت‌شده نیستند.</small>
                  </div>
                )}
              </>
            ) : (
              <div className="tv-placeholder static">
                <b>نماد {symbol} روی TradingView نگاشت نشده است</b>
                <span>چارت بومی اپ را ببین؛ هیچ نماد جایگزینی به‌صورت پنهانی نشان داده نمی‌شود.</span>
                <button type="button" onClick={() => setMode('native')}>نمایش چارت بومی</button>
              </div>
            )}
          </div>
        </>
      ) : (
        <NativeChart
          timeframe={timeframe}
          candles={candles}
          levels={levels}
          lastBarTime={lastBarTime}
          symbol={symbol}
        />
      )}

      <div className="chart-footer">
        <span><i className="feed-dot" style={{ background: feedTone === 'live' ? 'var(--green)' : feedTone === 'stale' ? '#e6a244' : 'var(--red)' }}/> {feedLabel}</span>
        <span>{mode === 'tradingview' ? 'قیمت و کندل: ویدجت TradingView' : `آخرین کندل واقعی: ${lastBarTime ? new Date(lastBarTime).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' }) : '—'}`}</span>
        <span>منبع: {mode === 'tradingview' ? (tvSymbol ?? 'TradingView') : 'بک‌اند روی کندل واقعی · بدون دیتای ساختگی'}</span>
      </div>
    </section>
  );
}

function NativeChart({
  timeframe,
  candles,
  levels,
  lastBarTime,
  symbol,
}: {
  timeframe: '1m' | '5m' | '15m' | '1h';
  candles: Candle[];
  levels: ChartSignalLevels;
  lastBarTime: number | null;
  symbol: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [showStudies, setShowStudies] = useState(true);
  const [hovered, setHovered] = useState<Candle | null>(null);

  const studies = useMemo(() => {
    if (candles.length < 2) return null;
    return {
      ichi: ichimoku(candles, timeframe === '1m' ? { tenkan: 7, kijun: 22, spanB: 44 } : { tenkan: 9, kijun: 26, spanB: 52 }),
      vwap: vwap(candles),
      ema200: ema(candles, 200),
    };
  }, [candles, timeframe]);

  useEffect(() => {
    if (!containerRef.current || candles.length < 2) return;
    const el = containerRef.current;
    const chart = createChart(el, {
      width: el.clientWidth,
      height: el.clientHeight,
      layout: { background: { type: ColorType.Solid, color: '#0b0e13' }, textColor: '#6f7786', fontFamily: 'DM Mono, monospace', fontSize: 10 },
      grid: { vertLines: { color: '#171b23' }, horzLines: { color: '#171b23' } },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: { color: '#7c8493', width: 1, style: LineStyle.Dashed, labelBackgroundColor: '#252b35' },
        horzLine: { color: '#7c8493', width: 1, style: LineStyle.Dashed, labelBackgroundColor: '#252b35' },
      },
      rightPriceScale: { borderColor: '#222731', scaleMargins: { top: 0.08, bottom: 0.2 } },
      timeScale: { borderColor: '#222731', timeVisible: true, secondsVisible: false, rightOffset: 8, barSpacing: 7, minBarSpacing: 3 },
      handleScroll: true,
      handleScale: true,
    });
    chartRef.current = chart;

    const candleSeries = chart.addCandlestickSeries({
      upColor: colors.up, downColor: colors.down, borderVisible: false,
      wickUpColor: colors.up, wickDownColor: colors.down,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      lastValueVisible: true,
    });
    candleSeries.setData(candles.map((c) => ({ ...c, time: c.time as UTCTimestamp })));

    const volume = chart.addHistogramSeries({
      priceFormat: { type: 'volume' }, priceScaleId: 'volume', lastValueVisible: false, priceLineVisible: false,
    });
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.83, bottom: 0 } });
    volume.setData(candles.map((c) => ({ time: c.time as UTCTimestamp, value: c.volume, color: c.close >= c.open ? '#24c69a32' : '#ef5d6c32' })));

    if (showStudies && studies) {
      const cloudA = chart.addAreaSeries({
        lineColor: '#2ab68a7d', topColor: '#1a745d32', bottomColor: '#1a745d05', lineWidth: 1,
        priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
      });
      cloudA.setData(studies.ichi.spanA.map((p) => ({ ...p, time: p.time as UTCTimestamp })));
      const cloudB = chart.addAreaSeries({
        lineColor: '#db626d6b', topColor: '#8b3d4525', bottomColor: '#8b3d4503', lineWidth: 1,
        priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
      });
      cloudB.setData(studies.ichi.spanB.map((p) => ({ ...p, time: p.time as UTCTimestamp })));

      const lineSeries = [
        { points: studies.ichi.tenkan, color: colors.cyan, width: 1 as const },
        { points: studies.ichi.kijun, color: colors.purple, width: 1 as const },
        { points: studies.vwap, color: colors.gold, width: 2 as const },
        { points: studies.ema200, color: '#87909f', width: 1 as const },
      ];
      lineSeries.forEach(({ points, color, width }) => {
        const series = chart.addLineSeries({ color, lineWidth: width, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
        series.setData(points.map((p) => ({ ...p, time: p.time as UTCTimestamp })));
      });
    }

    if (levels && (levels.entry || levels.stop_loss || levels.take_profit)) {
      const lines = [
        { price: levels.entry, color: colors.gold, title: 'ورود' },
        { price: levels.stop_loss, color: colors.down, title: 'SL' },
        { price: levels.take_profit, color: colors.up, title: 'TP' },
      ].filter((line) => typeof line.price === 'number' && Number.isFinite(line.price));
      lines.forEach((line) => {
        candleSeries.createPriceLine({
          price: line.price as number,
          color: line.color,
          lineWidth: 1,
          lineStyle: LineStyle.Dashed,
          axisLabelVisible: true,
          title: line.title,
        });
      });
    }

    chart.timeScale().setVisibleLogicalRange({ from: Math.max(0, candles.length - 120), to: candles.length + 5 });
    chart.subscribeCrosshairMove((param) => {
      if (!param.time) return setHovered(null);
      const value = param.seriesData.get(candleSeries) as { open: number; high: number; low: number; close: number } | undefined;
      if (value) setHovered({ ...value, time: Number(param.time), volume: 0 });
    });

    const observer = new ResizeObserver(() => chart.applyOptions({ width: el.clientWidth, height: el.clientHeight }));
    observer.observe(el);
    const reset = () => chart.timeScale().fitContent();
    window.addEventListener('aurum:reset-native-chart', reset);
    return () => {
      observer.disconnect();
      window.removeEventListener('aurum:reset-native-chart', reset);
      chart.remove();
      chartRef.current = null;
    };
  }, [candles, timeframe, showStudies, levels]);

  const values = hovered ?? candles[candles.length - 1];
  const positive = values ? values.close >= values.open : true;

  return (
    <>
      <div className="chart-meta">
        <span>O <b>{values ? values.open.toFixed(2) : '—'}</b></span>
        <span>H <b>{values ? values.high.toFixed(2) : '—'}</b></span>
        <span>L <b>{values ? values.low.toFixed(2) : '—'}</b></span>
        <span>C <b className={positive ? 'green' : 'red'}>{values ? values.close.toFixed(2) : '—'}</b></span>
        <span style={{ color: '#6b7280' }}>{lastBarTime ? new Date(lastBarTime).toLocaleString('fa-IR', { hour: '2-digit', minute: '2-digit', month: '2-digit', day: '2-digit' }) : '—'}</span>
        <div className="legend"><i className="dot cyan"/>Tenkan <i className="dot purple"/>Kijun <i className="dot gold"/>VWAP <i className="dot grey"/>EMA200</div>
      </div>
      <div className="chart-body">
        <aside className="drawing-tools">
          <button type="button" className="active"><ChevronDown size={15}/></button>
          <button type="button" className={showStudies ? 'active' : ''} title="اندیکاتورها" onClick={() => setShowStudies((v) => !v)}><Layers3 size={15}/></button>
        </aside>
        {candles.length < 2 ? (
          <div className="native-empty">
            <b>هیچ کندل واقعی‌ای برای نمایش نیست</b>
            <p>
              این ترمینال هیچ کندل ساختگی نمی‌سازد. برای دیدن چارت بومی، باید بک‌اند به دادهٔ واقعی وصل باشد
              (<code>AURUM_TWELVE_DATA_API_KEY</code> در <code>backend/.env</code>) و اینترنت فعال باشد.
              چارت TradingView بالا مستقل از بک‌اند کار می‌کند.
            </p>
            <span>وضعیت فعلی فید: {symbol}</span>
          </div>
        ) : (
          <div className="chart-canvas" ref={containerRef}/>
        )}
      </div>
    </>
  );
}
