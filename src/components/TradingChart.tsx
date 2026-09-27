import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ColorType,
  CrosshairMode,
  LineStyle,
  createChart,
  type IChartApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import { ChevronDown, Layers3, RotateCcw } from 'lucide-react';
import { ema, ichimoku, vwap, type Candle } from '../lib/market';

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

type Props = {
  timeframe: '1m' | '5m' | '15m' | '1h';
  onTimeframeChange: (value: '1m' | '5m' | '15m' | '1h') => void;
  candles: Candle[];
  levels: ChartSignalLevels;
  feedLabel: string;
  feedTone: 'live' | 'stale' | 'offline';
  lastBarTime: number | null;
};

export default function TradingChart({
  timeframe,
  onTimeframeChange,
  candles,
  levels,
  feedLabel,
  feedTone,
  lastBarTime,
}: Props) {
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
    return () => {
      observer.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, [candles, timeframe, showStudies, levels]);

  const values = hovered ?? candles[candles.length - 1];
  const positive = values ? values.close >= values.open : true;

  return (
    <section className="chart-card panel">
      <div className="chart-toolbar">
        <div className="symbol-lockup">
          <div className="asset-orb">Au</div>
          <div><strong>XAU / USD</strong><span>داده واقعی Twelve Data</span></div>
        </div>
        <fieldset className="timeframes">
          <legend className="sr-only">Chart timeframe</legend>
          {(['1m', '5m', '15m', '1h'] as const).map((tf) => (
            <button type="button" className={timeframe === tf ? 'active' : ''} onClick={() => onTimeframeChange(tf)} key={tf}>{tf}</button>
          ))}
        </fieldset>
        <div className="chart-actions">
          <button type="button" className={showStudies ? 'study active' : 'study'} onClick={() => setShowStudies(!showStudies)}><Layers3 size={14}/> اندیکاتور <span>4</span></button>
          <button type="button" aria-label="Reset chart" onClick={() => chartRef.current?.timeScale().fitContent()}><RotateCcw size={15}/></button>
        </div>
      </div>

      {candles.length < 2 ? (
        <div style={{ padding: '28px 18px', color: '#8a909c', fontSize: 12, lineHeight: 1.9 }}>
          <b style={{ color: '#c9a86a', display: 'block', marginBottom: 6 }}>هیچ کندل واقعی‌ای برای نمایش نیست</b>
          این ترمینال هیچ کندل ساختگی نمی‌سازد. برای دیدن چارت، باید بک‌اند به Twelve Data وصل باشد
          (<code style={{ fontFamily: 'DM Mono' }}>AURUM_TWELVE_DATA_API_KEY</code> در فایل <code style={{ fontFamily: 'DM Mono' }}>backend/.env</code>) و اینترنت فعال باشد.
          وضعیت فعلی فید: {feedLabel}
        </div>
      ) : (
        <>
          <div className="chart-meta">
            <span>O <b>{values.open.toFixed(2)}</b></span><span>H <b>{values.high.toFixed(2)}</b></span>
            <span>L <b>{values.low.toFixed(2)}</b></span><span>C <b className={positive ? 'green' : 'red'}>{values.close.toFixed(2)}</b></span>
            <span style={{ color: '#6b7280' }}>{lastBarTime ? new Date(lastBarTime).toLocaleString('fa-IR', { hour: '2-digit', minute: '2-digit', month: '2-digit', day: '2-digit' }) : '—'}</span>
            <div className="legend"><i className="dot cyan"/>Tenkan <i className="dot purple"/>Kijun <i className="dot gold"/>VWAP <i className="dot grey"/>EMA200</div>
          </div>
          <div className="chart-body">
            <aside className="drawing-tools">
              <button type="button" className="active"><ChevronDown size={15}/></button>
            </aside>
            <div className="chart-canvas" ref={containerRef}/>
          </div>
          <div className="chart-footer">
            <span><i className="feed-dot" style={{ background: feedTone === 'live' ? 'var(--green)' : feedTone === 'stale' ? '#e6a244' : 'var(--red)' }}/> {feedLabel}</span>
            <span>آخرین کندل واقعی: {lastBarTime ? new Date(lastBarTime).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' }) : '—'}</span>
            <span>منبع: Twelve Data · بدون دیتای ساختگی</span>
          </div>
        </>
      )}
    </section>
  );
}
