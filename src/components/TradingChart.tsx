import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ColorType,
  CrosshairMode,
  LineStyle,
  createChart,
  type IChartApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import { BarChart3, ChevronDown, Crosshair, Expand, Layers3, Pencil, RotateCcw } from 'lucide-react';
import { ema, generateCandles, ichimoku, nextCandle, vwap, type Candle } from '../lib/market';

const colors = {
  gold: '#f4bd48',
  cyan: '#57c7d4',
  purple: '#9d83e9',
  up: '#24c69a',
  down: '#ef5d6c',
};

type Props = {
  timeframe: '1m' | '5m';
  onTimeframeChange: (value: '1m' | '5m') => void;
  onPriceChange: (value: number, delta: number) => void;
};

export default function TradingChart({ timeframe, onTimeframeChange, onPriceChange }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [showStudies, setShowStudies] = useState(true);
  const [activeTool, setActiveTool] = useState('crosshair');
  const [hovered, setHovered] = useState<Candle | null>(null);
  const data = useMemo(() => generateCandles(timeframe === '1m' ? 1 : 5), [timeframe]);

  useEffect(() => {
    if (!containerRef.current) return;
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
      timeScale: { borderColor: '#222731', timeVisible: true, secondsVisible: false, rightOffset: 8, barSpacing: timeframe === '1m' ? 7 : 8, minBarSpacing: 3 },
      handleScroll: true,
      handleScale: true,
    });
    chartRef.current = chart;

    const candles = chart.addCandlestickSeries({
      upColor: colors.up, downColor: colors.down, borderVisible: false,
      wickUpColor: colors.up, wickDownColor: colors.down,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
      lastValueVisible: true,
    });
    candles.setData(data.map((c) => ({ ...c, time: c.time as UTCTimestamp })));

    const volume = chart.addHistogramSeries({
      priceFormat: { type: 'volume' }, priceScaleId: 'volume', lastValueVisible: false, priceLineVisible: false,
    });
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.83, bottom: 0 } });
    volume.setData(data.map((c) => ({ time: c.time as UTCTimestamp, value: c.volume, color: c.close >= c.open ? '#24c69a32' : '#ef5d6c32' })));

    if (showStudies) {
      const ichi = ichimoku(data);
      const cloudA = chart.addAreaSeries({
        lineColor: '#2ab68a7d', topColor: '#1a745d32', bottomColor: '#1a745d05', lineWidth: 1,
        priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
      });
      cloudA.setData(ichi.spanA.map((p) => ({ ...p, time: p.time as UTCTimestamp })));
      const cloudB = chart.addAreaSeries({
        lineColor: '#db626d6b', topColor: '#8b3d4525', bottomColor: '#8b3d4503', lineWidth: 1,
        priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
      });
      cloudB.setData(ichi.spanB.map((p) => ({ ...p, time: p.time as UTCTimestamp })));

      const studies = [
        { points: ichi.tenkan, color: colors.cyan, width: 1 as const },
        { points: ichi.kijun, color: colors.purple, width: 1 as const },
        { points: vwap(data), color: colors.gold, width: 2 as const },
        { points: ema(data, 200), color: '#87909f', width: 1 as const },
      ];
      studies.forEach(({ points, color, width }) => {
        const series = chart.addLineSeries({ color, lineWidth: width, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
        series.setData(points.map((p) => ({ ...p, time: p.time as UTCTimestamp })));
      });
    }

    const buyIndex = data.length - 31;
    candles.setMarkers([
      { time: data[buyIndex].time as UTCTimestamp, position: 'belowBar', color: colors.gold, shape: 'arrowUp', text: 'BUY 91%' },
    ]);

    chart.timeScale().setVisibleLogicalRange({ from: data.length - 91, to: data.length + 5 });
    chart.subscribeCrosshairMove((param) => {
      if (!param.time) return setHovered(null);
      const value = param.seriesData.get(candles) as { open: number; high: number; low: number; close: number } | undefined;
      if (value) setHovered({ ...value, time: Number(param.time), volume: 0 });
    });

    let tick = 0;
    const timer = window.setInterval(() => {
      tick += 1;
      const latest = nextCandle(data[data.length - 1], timeframe === '1m' ? 1 : 5, tick);
      data[data.length - 1] = latest;
      candles.update({ ...latest, time: latest.time as UTCTimestamp });
      volume.update({ time: latest.time as UTCTimestamp, value: latest.volume, color: latest.close >= latest.open ? '#24c69a32' : '#ef5d6c32' });
      onPriceChange(latest.close, latest.close - latest.open);
    }, 1800);

    const observer = new ResizeObserver(() => chart.applyOptions({ width: el.clientWidth, height: el.clientHeight }));
    observer.observe(el);
    return () => {
      window.clearInterval(timer);
      observer.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, [data, timeframe, showStudies, onPriceChange]);

  const values = hovered ?? data[data.length - 1];
  const positive = values.close >= values.open;

  return (
    <section className="chart-card panel">
      <div className="chart-toolbar">
        <div className="symbol-lockup">
          <div className="asset-orb">Au</div>
          <div><strong>XAU / USD</strong><span>Gold Spot · Demo feed</span></div>
        </div>
        <div className="timeframes" aria-label="Chart timeframe">
          {(['1m', '5m'] as const).map((tf) => <button className={timeframe === tf ? 'active' : ''} onClick={() => onTimeframeChange(tf)} key={tf}>{tf}</button>)}
          <button className="muted-time">15m</button><button className="muted-time">1H</button>
        </div>
        <div className="chart-actions">
          <button className={showStudies ? 'study active' : 'study'} onClick={() => setShowStudies(!showStudies)}><Layers3 size={14}/> Indicators <span>4</span></button>
          <button aria-label="Reset chart" onClick={() => chartRef.current?.timeScale().fitContent()}><RotateCcw size={15}/></button>
          <button aria-label="Expand chart"><Expand size={15}/></button>
        </div>
      </div>
      <div className="chart-meta">
        <span>O <b>{values.open.toFixed(2)}</b></span><span>H <b>{values.high.toFixed(2)}</b></span>
        <span>L <b>{values.low.toFixed(2)}</b></span><span>C <b className={positive ? 'green' : 'red'}>{values.close.toFixed(2)}</b></span>
        <span className="chart-change green">+0.14%</span>
        <div className="legend"><i className="dot cyan"/>Tenkan 7 <i className="dot purple"/>Kijun 22 <i className="dot gold"/>VWAP <i className="dot grey"/>EMA 200</div>
      </div>
      <div className="chart-body">
        <aside className="drawing-tools">
          <button className={activeTool === 'crosshair' ? 'active' : ''} onClick={() => setActiveTool('crosshair')}><Crosshair size={16}/></button>
          <button className={activeTool === 'draw' ? 'active' : ''} onClick={() => setActiveTool('draw')}><Pencil size={16}/></button>
          <button><BarChart3 size={16}/></button>
          <button><ChevronDown size={15}/></button>
        </aside>
        <div className="chart-canvas" ref={containerRef}/>
        <div className="market-session"><span className="pulse-dot"/> LONDON / NY OVERLAP</div>
      </div>
      <div className="chart-footer"><span><i className="feed-dot"/> LIVE · 124ms</span><span>UTC 14:32:08</span><span>Powered by institutional feed</span></div>
    </section>
  );
}
