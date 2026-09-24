import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, BarChart3, BarChart2, History, Play, ShieldCheck, Zap } from 'lucide-react';
import ForwardTestPanel from './ForwardTestPanel';
import { apiGet } from '../lib/api';

/**
 * Backtest on real candles pulled from Twelve Data.
 *
 * Every number below comes from replaying the live strategy bar-by-bar over candles the
 * provider actually returned. The panel has no preset "proof of profitability": if the
 * engine loses money on the sample, that is what is shown.
 */
type Trade = {
  side: string;
  entry_time: string;
  exit_time: string;
  entry: number;
  exit: number;
  position_oz: number;
  pnl: number;
  r_multiple: number | null;
  exit_reason: string;
  fees: number;
};

type BacktestResult = {
  error?: string;
  data_source?: string;
  symbol?: string;
  timeframe?: string;
  bars?: number;
  start?: string;
  end?: string;
  initial_balance?: number;
  final_balance?: number;
  total_pnl?: number;
  total_return_pct?: number;
  total_trades?: number;
  wins?: number;
  losses?: number;
  win_rate?: number | null;
  profit_factor?: number | null;
  expectancy?: number | null;
  expectancy_usd?: number | null;
  avg_win?: number | null;
  avg_loss?: number | null;
  sharpe?: number | null;
  performance?: {
    total: number; wins: number; losses: number;
    long_count: number; short_count: number;
    win_rate_pct: number | null; net_pnl_usd: number;
    gross_profit_usd: number; gross_loss_usd: number;
    average_win_usd: number | null; average_loss_usd: number | null;
    profit_factor: number | null; sharpe_per_trade: number | null;
    expectancy_r: number | null; average_duration_seconds: number | null;
    longest_winning_streak: number; longest_losing_streak: number;
    max_drawdown_pct: number | null; fees_usd: number;
  };
  max_drawdown?: number;
  max_drawdown_pct?: number;
  fees_paid?: number;
  skipped_min_lot?: number;
  skipped_margin?: number;
  equity_curve?: { time: string; balance: number }[];
  trades?: Trade[];
  yearly?: { year: number; trades: number; wins: number; win_rate: number | null; pnl: number; avg_r: number | null }[];
  feasibility?: {
    typical_stop_distance?: number;
    risk_usd_at_minimum_lot?: number;
    risk_pct_at_minimum_lot?: number;
    suggested_min_balance?: number;
    verdict?: string;
  };
  rejected_setups?: { reason: string; count: number }[];
  notes?: string[];
};

function EquityChart({ points, initial }: { points: { time: string; balance: number }[]; initial: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!ref.current || points.length < 2) return;
    const canvas = ref.current;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.scale(dpr, dpr);
    const W = rect.width;
    const H = rect.height;
    const pad = { l: 42, r: 10, t: 10, b: 20 };
    const values = points.map((p) => p.balance);
    const min = Math.min(...values, initial);
    const max = Math.max(...values, initial);
    const span = max - min || 1;
    const x = (i: number) => pad.l + (W - pad.l - pad.r) * (i / (points.length - 1));
    const y = (v: number) => pad.t + (H - pad.t - pad.b) * (1 - (v - min) / span);

    ctx.fillStyle = '#0a0c10';
    ctx.fillRect(0, 0, W, H);
    ctx.strokeStyle = '#1a2320';
    ctx.lineWidth = 0.5;
    ctx.fillStyle = '#5a6b65';
    ctx.font = '8px DM Mono';
    for (let i = 0; i <= 4; i++) {
      const value = min + (span * i) / 4;
      const yy = y(value);
      ctx.beginPath();
      ctx.moveTo(pad.l, yy);
      ctx.lineTo(W - pad.r, yy);
      ctx.stroke();
      ctx.fillText(value.toFixed(1), 4, yy + 3);
    }
    const baseline = y(initial);
    ctx.strokeStyle = '#3a4350';
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(pad.l, baseline);
    ctx.lineTo(W - pad.r, baseline);
    ctx.stroke();
    ctx.setLineDash([]);

    const last = values[values.length - 1];
    ctx.strokeStyle = last >= initial ? '#24c69a' : '#ef5d6c';
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    points.forEach((point, index) => {
      const px = x(index);
      const py = y(point.balance);
      if (index === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    });
    ctx.stroke();
  }, [points, initial]);

  return <canvas ref={ref} style={{ width: '100%', height: 170, display: 'block', borderRadius: 6 }} />;
}

const stat = (label: string, value: string, tone?: string) => (
  <div key={label} style={{ background: '#11151b', borderRadius: 5, padding: '7px 8px', textAlign: 'center' }}>
    <span style={{ display: 'block', color: '#6b7280', font: '7px DM Mono' }}>{label}</span>
    <b style={{ display: 'block', color: tone ?? '#e6e9ee', font: '700 12px DM Mono', marginTop: 3 }}>{value}</b>
  </div>
);

export default function BacktestPanel() {
  const [timeframe, setTimeframe] = useState<'5m' | '15m' | '1h'>('5m');
  const [bars, setBars] = useState(1500);
  const [balance, setBalance] = useState(100);
  const [risk, setRisk] = useState(0.5);
  const [state, setState] = useState<{ running: boolean; error?: string; data?: BacktestResult }>({ running: false });

  const run = async () => {
    setState({ running: true });
    const query = new URLSearchParams({
      timeframe,
      bars: String(bars),
      initial_balance: String(balance),
      risk_percent: String(risk),
      spread: '0.30',
      commission_per_oz: '0.05',
    });
    const result = await apiGet<BacktestResult>(`/api/v1/backtest/run?${query.toString()}`);
    if (!result.ok) return setState({ running: false, error: result.error });
    if (result.data?.error) return setState({ running: false, error: result.data.error });
    setState({ running: false, data: result.data });
  };

  useEffect(() => {
    void run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const data = state.data;
  const positive = (data?.total_pnl ?? 0) >= 0;

  return (
    <section className="panel" id="backtest" style={{ gridColumn: '1 / -1', overflow: 'hidden', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><History size={12}/> بک‌تست روی کندل واقعی · همین استراتژیِ زنده</span>
          <h2>سنجش عملکرد روی دیتای واقعی (بدون ادعا)</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>
            اسپرد ۰.۳۰$ · کمیسیون ۰.۰۵$ هر اونس · حداقل حجم ۰.۰۱ لات (۱ اونس) — سیگنال‌هایی که با این موجودی قابل اجرا نیستند شمرده می‌شوند، نه اینکه ساخته شوند.
          </small>
        </div>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'flex-end', padding: '10px 14px', borderBottom: '1px solid var(--line)' }}>
        <label style={{ display: 'grid', gap: 4, font: '8px DM Mono', color: '#6b7280' }}>
          تایم‌فریم
          <select value={timeframe} onChange={(event) => {
            const value = event.target.value;
            if (value === '5m' || value === '15m' || value === '1h') setTimeframe(value);
          }} style={{ background: '#11151b', color: '#d5d9e0', border: '1px solid #1f2630', borderRadius: 5, padding: '5px 8px', font: '9px DM Mono' }}>
            <option value="5m">5m</option><option value="15m">15m</option><option value="1h">1h</option>
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4, font: '8px DM Mono', color: '#6b7280' }}>
          تعداد کندل
          <select value={bars} onChange={(event) => setBars(Number(event.target.value))} style={{ background: '#11151b', color: '#d5d9e0', border: '1px solid #1f2630', borderRadius: 5, padding: '5px 8px', font: '9px DM Mono' }}>
            {[500, 1000, 1500, 3000, 5000].map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4, font: '8px DM Mono', color: '#6b7280' }}>
          موجودی ($)
          <input type="number" min={10} step={10} value={balance} onChange={(event) => setBalance(Number(event.target.value))} style={{ width: 92, background: '#11151b', color: '#d5d9e0', border: '1px solid #1f2630', borderRadius: 5, padding: '5px 8px', font: '9px DM Mono' }} />
        </label>
        <label style={{ display: 'grid', gap: 4, font: '8px DM Mono', color: '#6b7280' }}>
          ریسک هر معامله (%)
          <input type="number" min={0.1} max={5} step={0.1} value={risk} onChange={(event) => setRisk(Number(event.target.value))} style={{ width: 80, background: '#11151b', color: '#d5d9e0', border: '1px solid #1f2630', borderRadius: 5, padding: '5px 8px', font: '9px DM Mono' }} />
        </label>
        <button type="button" className="primary-button" onClick={run} disabled={state.running} style={{ height: 30, padding: '0 14px', display: 'flex', alignItems: 'center', gap: 6, font: '700 9px Manrope', opacity: state.running ? 0.6 : 1 }}>
          <Play size={12}/> {state.running ? 'در حال دریافت کندل واقعی…' : 'اجرای بک‌تست'}
        </button>
      </div>

      {state.error && (
        <div style={{ padding: '14px 16px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }}/>
          <span>{state.error} — هیچ نتیجه‌ای ساخته نمی‌شود تا دیتای واقعی برسد.</span>
        </div>
      )}

      {data && (
        <>
          <div style={{ padding: '10px 14px', display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', borderBottom: '1px solid var(--line)' }}>
            <span style={{ font: '8px DM Mono', color: '#6b7280' }}>
              منبع: <b style={{ color: '#7af0c8' }}>{data.data_source}</b> · {data.symbol} {data.timeframe} · {data.bars} کندل
            </span>
            <span style={{ font: '8px DM Mono', color: '#6b7280' }}>
              {data.start && new Date(data.start).toLocaleString('fa-IR')} → {data.end && new Date(data.end).toLocaleString('fa-IR')}
            </span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(110px,1fr))', gap: 8, padding: '12px 14px' }}>
            {stat('موجودی نهایی', `$${(data.final_balance ?? 0).toFixed(2)}`, positive ? 'var(--green)' : 'var(--red)')}
            {stat('بازده', `${(data.total_return_pct ?? 0) > 0 ? '+' : ''}${data.total_return_pct ?? 0}%`, positive ? 'var(--green)' : 'var(--red)')}
            {stat('تعداد معامله', String(data.total_trades ?? 0))}
            {stat('برد', data.win_rate != null ? `${data.win_rate}%` : '—')}
            {stat('PF', data.profit_factor != null ? String(data.profit_factor) : '—', (data.profit_factor ?? 0) > 1 ? 'var(--green)' : 'var(--red)')}
            {stat('انتظار (R)', data.expectancy != null ? `${data.expectancy}R` : '—')}
            {stat('افت سرمایه', `-${data.max_drawdown_pct ?? 0}%`, 'var(--red)')}
            {stat('Sharpe معامله‌ای', data.sharpe != null ? String(data.sharpe) : '—')}
            {stat('کارمزد پرداختی', `$${(data.fees_paid ?? 0).toFixed(2)}`, '#e6a244')}
            {stat('معاملات رد‌شده (حجم کم)', String(data.skipped_min_lot ?? 0), '#e6a244')}
          </div>

          {data.performance && (
            <div style={{ margin: '0 14px 14px', padding: 12, border: '1px solid var(--line)', borderRadius: 8, background: '#0a0e12' }}>
              <div style={{ color: 'var(--gold)', fontSize: 11, marginBottom: 8 }}>گزارش کامل عملکرد · معاملات واقعیِ بک‌تست</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(115px, 1fr))', gap: 7 }}>
                {stat('کل / برد / باخت', `${data.performance.total} / ${data.performance.wins} / ${data.performance.losses}`)}
                {stat('Long / Short', `${data.performance.long_count} / ${data.performance.short_count}`)}
                {stat('نرخ برد', data.performance.win_rate_pct != null ? `${data.performance.win_rate_pct}%` : '—')}
                {stat('سود ناخالص', `${data.performance.gross_profit_usd}$`)}
                {stat('زیان ناخالص', `${data.performance.gross_loss_usd}$`)}
                {stat('سود خالص', `${data.performance.net_pnl_usd}$`, data.performance.net_pnl_usd >= 0 ? 'var(--green)' : 'var(--red)')}
                {stat('میانگین برد', data.performance.average_win_usd != null ? `${data.performance.average_win_usd}$` : '—')}
                {stat('میانگین باخت', data.performance.average_loss_usd != null ? `${data.performance.average_loss_usd}$` : '—')}
                {stat('فاکتور سود', data.performance.profit_factor != null ? String(data.performance.profit_factor) : '—')}
                {stat('Sharpe معامله‌ای', data.performance.sharpe_per_trade != null ? String(data.performance.sharpe_per_trade) : '—')}
                {stat('انتظار به R', data.performance.expectancy_r != null ? String(data.performance.expectancy_r) : '—')}
                {stat('میانگین مدت', data.performance.average_duration_seconds != null ? `${Math.round(data.performance.average_duration_seconds / 60)} دقیقه` : '—')}
                {stat('برد پیاپی', String(data.performance.longest_winning_streak))}
                {stat('باخت پیاپی', String(data.performance.longest_losing_streak))}
                {stat('بیشینه افت', data.performance.max_drawdown_pct != null ? `${data.performance.max_drawdown_pct}%` : '—')}
                {stat('کارمزد', `${data.performance.fees_usd}$`)}
              </div>
              <small style={{ display: 'block', color: '#6b7280', marginTop: 8 }}>
                Sharpe بر پایه بازده هر معامله، با انحراف معیار نمونه و بدون سالانه‌سازی است؛ معادل Sharpe روزانه نیست. نسبت تعریف‌نشده با «—» نشان داده می‌شود.
              </small>
            </div>
          )}

          {data.equity_curve && data.equity_curve.length > 1 && (
            <div style={{ padding: '0 14px 12px' }}>
              <EquityChart points={data.equity_curve} initial={data.initial_balance ?? 100} />
              <div style={{ display: 'flex', justifyContent: 'space-between', font: '7px DM Mono', color: '#5a6b65', marginTop: 4 }}>
                <span>خط چین = موجودی اولیه ${(data.initial_balance ?? 0).toFixed(0)}</span>
                <span>{(data.equity_curve.length)} نقطه برابری</span>
              </div>
            </div>
          )}

          {data.feasibility?.verdict && (
            <div style={{ margin: '0 14px 12px', background: '#0a0e12', border: '1px solid #1f2630', borderRadius: 6, padding: '9px 11px', display: 'flex', gap: 8, alignItems: 'flex-start' }}>
              <ShieldCheck size={14} style={{ color: 'var(--gold)', flexShrink: 0, marginTop: 2 }}/>
              <span style={{ fontSize: 10, color: '#c9b896', lineHeight: 1.8 }}>{data.feasibility.verdict}</span>
            </div>
          )}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 12, padding: '0 14px 14px' }}>
            {!!data.yearly?.length && (
              <div style={{ background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, overflow: 'hidden' }}>
                <div style={{ padding: '6px 9px', background: '#0f1115', font: '8px DM Mono', color: '#c9b896', display: 'flex', alignItems: 'center', gap: 6 }}>
                  <BarChart3 size={11}/> تفکیک سالانه (فقط سال‌های موجود در دیتا)
                </div>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 9 }}>
                  <thead><tr style={{ color: '#6b7280', font: '7px DM Mono' }}><th style={{ padding: '4px 8px', textAlign: 'left' }}>سال</th><th>معامله</th><th>برد</th><th>میانگین R</th><th>سود/زیان</th></tr></thead>
                  <tbody>
                    {data.yearly.map((row) => (
                      <tr key={row.year} style={{ borderTop: '1px solid #1a1f28', textAlign: 'center', font: '8px DM Mono', color: '#c9cdd5' }}>
                        <td style={{ padding: '4px 8px', textAlign: 'left', color: '#8a909c' }}>{row.year}</td>
                        <td>{row.trades}</td>
                        <td>{row.win_rate != null ? `${row.win_rate}%` : '—'}</td>
                        <td>{row.avg_r != null ? `${row.avg_r}R` : '—'}</td>
                        <td style={{ color: row.pnl >= 0 ? 'var(--green)' : 'var(--red)' }}>{row.pnl > 0 ? '+' : ''}{row.pnl}$</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {!!data.rejected_setups?.length && (
              <div style={{ background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, overflow: 'hidden' }}>
                <div style={{ padding: '6px 9px', background: '#0f1115', font: '8px DM Mono', color: '#c9b896', display: 'flex', alignItems: 'center', gap: 6 }}>
                  <Zap size={11}/> چرا سیستم وارد نشد (شفافیت فیلترها)
                </div>
                <div style={{ padding: '8px 10px', display: 'grid', gap: 5 }}>
                  {data.rejected_setups.slice(0, 8).map((item) => (
                    <div key={item.reason} style={{ display: 'flex', justifyContent: 'space-between', font: '8px DM Mono', color: '#8a909c' }}>
                      <span style={{ maxWidth: '80%' }}>{item.reason}</span>
                      <b style={{ color: '#c9cdd5' }}>{item.count}</b>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {!!data.trades?.length && (
            <div style={{ margin: '0 14px 14px', background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, overflow: 'hidden' }}>
              <div style={{ padding: '6px 9px', background: '#0f1115', display: 'flex', justifyContent: 'space-between', font: '8px DM Mono', color: '#c9b896' }}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><BarChart2 size={11}/> معاملات واقعی شبیه‌سازی‌شده</span>
                <span style={{ color: '#5a6b65' }}>{data.trades.length} معامله</span>
              </div>
              <div style={{ maxHeight: 220, overflowY: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 9 }}>
                  <thead><tr style={{ background: '#11151b', color: '#6b7280', font: '7px DM Mono' }}><th style={{ padding: '4px 8px', textAlign: 'left' }}>ورود</th><th>جهت</th><th>حجم</th><th>ورود/خروج</th><th>نتیجه</th><th>R</th><th style={{ textAlign: 'left' }}>دلیل خروج</th></tr></thead>
                  <tbody>
                    {data.trades.slice(-40).reverse().map((trade, index) => (
                      <tr key={index} style={{ borderTop: '1px solid #1a1f28', textAlign: 'center', font: '8px DM Mono', color: '#c9cdd5' }}>
                        <td style={{ padding: '4px 8px', textAlign: 'left', color: '#7a8290' }}>{new Date(trade.entry_time).toLocaleString('fa-IR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</td>
                        <td style={{ color: trade.side === 'BUY' ? 'var(--green)' : 'var(--red)', fontWeight: 700 }}>{trade.side}</td>
                        <td>{trade.position_oz} oz</td>
                        <td>{trade.entry} → {trade.exit}</td>
                        <td style={{ color: trade.pnl >= 0 ? 'var(--green)' : 'var(--red)', fontWeight: 700 }}>{trade.pnl > 0 ? '+' : ''}{trade.pnl.toFixed(2)}$</td>
                        <td>{trade.r_multiple != null ? `${trade.r_multiple}R` : '—'}</td>
                        <td style={{ textAlign: 'left', color: '#8a909c' }}>{trade.exit_reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <ForwardTestPanel initial={balance} risk={risk} timeframe={timeframe} />

          {!!data.notes?.length && (
            <div style={{ padding: '10px 14px', borderTop: '1px solid var(--line)', fontSize: 9, color: '#6b7280', lineHeight: 1.8 }}>
              {data.notes.map((note, index) => <div key={index}>• {note}</div>)}
            </div>
          )}
        </>
      )}
    </section>
  );
}
