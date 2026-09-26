import { useEffect, useState } from 'react';
import { Activity, AlertTriangle, TrendingUp } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Walk-forward on real candles: the older part of the series vs the newer part.
 * There is no "future projection" column anymore — a projection would be invented data.
 */
type Metrics = {
  start: string;
  end: string;
  bars: number;
  initial_balance: number;
  final_balance: number;
  total_pnl: number;
  total_return_pct: number;
  total_trades: number;
  wins: number;
  losses: number;
  win_rate: number | null;
  profit_factor: number | null;
  expectancy: number | null;
  max_drawdown_pct: number;
  fees_paid: number;
  skipped_min_lot: number;
};

type ForwardTrade = {
  entry_time: string;
  side: 'BUY' | 'SELL';
  position_oz: number;
  pnl: number;
  exit_reason: string;
};

type ForwardData = {
  error?: string;
  data_source?: string;
  symbol?: string;
  timeframe?: string;
  bars?: number;
  split_time?: string;
  in_sample?: Metrics;
  out_of_sample?: Metrics;
  out_of_sample_trades?: ForwardTrade[];
  stress_test?: {
    real_trades?: number;
    median_final_balance?: number;
    p05_final_balance?: number;
    p95_final_balance?: number;
    // Backend key is `risk_of_80pct_loss_pct` (see app/services/backtest.py::stress_test_from_trades).
    risk_of_80pct_loss_pct?: number | null;
    note?: string;
    error?: string;
  };
  notes?: string[];
};

export default function ForwardTestPanel({
  initial,
  risk,
  timeframe,
}: {
  initial: number;
  risk: number;
  timeframe?: string;
}) {
  const [state, setState] = useState<{ loading: boolean; data?: ForwardData; error?: string }>({ loading: true });

  useEffect(() => {
    let alive = true;
    setState({ loading: true });
    const load = async () => {
      const query = `initial_balance=${initial}&risk_percent=${risk}${timeframe ? `&timeframe=${timeframe}` : ''}`;
      const result = await apiGet<ForwardData>(`/api/v1/backtest/forward?${query}`);
      if (!alive) return;
      if (!result.ok) return setState({ loading: false, error: result.error });
      if (result.data?.error) return setState({ loading: false, error: result.data.error });
      setState({ loading: false, data: result.data });
    };
    void load();
    return () => {
      alive = false;
    };
  }, [initial, risk, timeframe]);

  if (state.loading) {
    return (
      <div style={{ padding: 12, textAlign: 'center', color: '#6b7280', font: '9px DM Mono' }}>
        در حال اجرای walk-forward روی کندل‌های واقعی…
      </div>
    );
  }

  if (state.error || !state.data) {
    return (
      <div style={{ padding: '12px 14px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8, alignItems: 'flex-start', borderBottom: '1px solid var(--line)' }}>
        <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }} />
        <span>{state.error ?? 'پاسخی از بک‌اند دریافت نشد.'}</span>
      </div>
    );
  }

  const data = state.data;
  const inSample = data.in_sample;
  const outOfSample = data.out_of_sample;
  if (!inSample || !outOfSample) return null;

  const robustness = (() => {
    const inPf = inSample.profit_factor ?? 0;
    const outPf = outOfSample.profit_factor ?? 0;
    if (!outOfSample.total_trades) return { tone: 'var(--red)', text: 'خارج از نمونه هیچ معامله‌ای ثبت نشد — برای قضاوت، بازهٔ بلندتری لازم است.' };
    if (outPf >= inPf * 0.7 && outPf > 1) return { tone: 'var(--green)', text: 'خارج از نمونه هم مثبت مانده؛ شواهدِ (نه تضمینِ) پایداری.' };
    if (outPf > 1) return { tone: 'var(--gold)', text: 'خارج از نمونه سودده ولی ضعیف‌تر از داخل نمونه است.' };
    return { tone: 'var(--red)', text: 'خارج از نمونه زیان‌ده است — این استراتژی با این تنظیمات قابل اتکا نیست.' };
  })();

  const columns = [
    { title: `داخل نمونه (آموزش)`, metrics: inSample, accent: '#6b7280' },
    { title: `خارج از نمونه (دیده‌نشده)`, metrics: outOfSample, accent: 'var(--gold)' },
  ];

  return (
    <div style={{ borderBottom: '1px solid var(--line)', background: 'linear-gradient(180deg, #0a141b, #0a0c10)' }}>
      <div style={{ padding: '10px 12px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap', borderBottom: '1px solid #1a2630' }}>
        <b style={{ fontSize: 11, display: 'flex', alignItems: 'center', gap: 6, color: '#7af0c8' }}>
          <Activity size={14} style={{ color: 'var(--gold)' }} /> Walk-Forward روی دیتای واقعی — {data.bars} کندل {data.timeframe}
        </b>
        <span style={{ font: '8px DM Mono', color: robustness.tone, background: '#11151b', border: '1px solid #1f2630', padding: '3px 7px', borderRadius: 10 }}>
          {data.split_time ? `تقسیم در ${new Date(data.split_time).toLocaleString('fa-IR')}` : ''}
        </span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px,1fr))', gap: 10, padding: 12 }}>
        {columns.map((col) => (
          <div
            key={col.title}
            style={{
              background: col.accent === 'var(--gold)' ? '#f1bc4b0a' : '#0a0e12',
              border: `1px solid ${col.accent === 'var(--gold)' ? '#f1bc4b30' : '#1a2320'}`,
              borderRadius: 8,
              padding: 10,
            }}
          >
            <b style={{ fontSize: 8, color: col.accent }}>{col.title}</b>
            <small style={{ display: 'block', color: '#5a6b65', font: '7px DM Mono', marginTop: 3 }}>
              {new Date(col.metrics.start).toLocaleDateString('fa-IR')} → {new Date(col.metrics.end).toLocaleDateString('fa-IR')} · {col.metrics.bars} کندل
            </small>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, marginTop: 8, font: '7px DM Mono' }}>
              <div style={{ background: '#11151b', borderRadius: 4, padding: 6, textAlign: 'center' }}>
                <span style={{ color: '#6b7280' }}>موجودی</span>
                <b style={{ display: 'block', color: '#fff', marginTop: 2 }}>${col.metrics.final_balance.toFixed(2)}</b>
                <small style={{ color: col.metrics.total_pnl >= 0 ? 'var(--green)' : 'var(--red)' }}>
                  {col.metrics.total_return_pct > 0 ? '+' : ''}{col.metrics.total_return_pct}%
                </small>
              </div>
              <div style={{ background: '#11151b', borderRadius: 4, padding: 6, textAlign: 'center' }}>
                <span style={{ color: '#6b7280' }}>برد / PF</span>
                <b style={{ display: 'block', color: (col.metrics.profit_factor ?? 0) > 1 ? 'var(--green)' : 'var(--red)', marginTop: 2 }}>
                  {col.metrics.win_rate != null ? `${col.metrics.win_rate}%` : '—'} / {col.metrics.profit_factor ?? '—'}
                </b>
                <small style={{ color: '#5a6b65' }}>{col.metrics.total_trades} معامله</small>
              </div>
              <div style={{ background: '#11151b', borderRadius: 4, padding: 6, textAlign: 'center' }}>
                <span style={{ color: '#6b7280' }}>افت سرمایه</span>
                <b style={{ display: 'block', color: 'var(--red)', marginTop: 2 }}>-{col.metrics.max_drawdown_pct}%</b>
                <small style={{ color: '#5a6b65' }}>انتظار {col.metrics.expectancy != null ? `${col.metrics.expectancy}R` : '—'}</small>
              </div>
              <div style={{ background: '#11151b', borderRadius: 4, padding: 6, textAlign: 'center' }}>
                <span style={{ color: '#6b7280' }}>هزینه‌ها</span>
                <b style={{ display: 'block', color: '#e6a244', marginTop: 2 }}>${col.metrics.fees_paid.toFixed(2)}</b>
                <small style={{ color: '#5a6b65' }}>رد‌شده: {col.metrics.skipped_min_lot}</small>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div style={{ margin: '0 12px 12px', background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, padding: '8px 10px' }}>
        <b style={{ fontSize: 9, color: robustness.tone }}>{robustness.text}</b>
      </div>

      {data.stress_test && !data.stress_test.error && (
        <div style={{ margin: '0 12px 12px', background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, padding: '8px 10px' }}>
          <b style={{ fontSize: 8, color: '#c9b896' }}>تست استرس روی معاملات واقعی خارج از نمونه ({data.stress_test.real_trades} معامله)</b>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px,1fr))', gap: 6, marginTop: 6, font: '8px DM Mono', color: '#8a909c' }}>
            <span>میانه: <b style={{ color: '#fff' }}>${data.stress_test.median_final_balance?.toFixed(2)}</b></span>
            <span>۵٪ بدترین: <b style={{ color: 'var(--red)' }}>${data.stress_test.p05_final_balance?.toFixed(2)}</b></span>
            <span>۹۵٪ بهترین: <b style={{ color: 'var(--green)' }}>${data.stress_test.p95_final_balance?.toFixed(2)}</b></span>
            <span>ریسک افت ۸۰٪ سرمایه: <b style={{ color: '#e6a244' }}>{data.stress_test.risk_of_80pct_loss_pct != null ? `${data.stress_test.risk_of_80pct_loss_pct}%` : '—'}</b></span>
          </div>
          {data.stress_test.note && <div style={{ marginTop: 6, fontSize: 8, color: '#6b7280', lineHeight: 1.6 }}>{data.stress_test.note}</div>}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: 8, padding: '0 12px 12px' }}>
        <div style={{ background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, overflow: 'hidden' }}>
          <div style={{ padding: '6px 8px', background: '#0f1115', display: 'flex', justifyContent: 'space-between' }}>
            <b style={{ fontSize: 8, color: '#c9b896' }}>معاملات خارج از نمونه (واقعی)</b>
            <span style={{ font: '7px DM Mono', color: '#5a6b65' }}>{(data.out_of_sample_trades ?? []).length} معامله</span>
          </div>
          <div style={{ maxHeight: 180, overflowY: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 9 }}>
              <thead>
                <tr style={{ background: '#11151b', color: '#6b7280', font: '7px DM Mono', textAlign: 'left' }}>
                  <th style={{ padding: '4px 6px' }}>ورود</th><th>جهت</th><th>حجم</th><th>نتیجه</th><th>دلیل خروج</th>
                </tr>
              </thead>
              <tbody>
                {(data.out_of_sample_trades ?? []).slice(-8).map((trade, index) => (
                  <tr key={index} style={{ borderTop: '1px solid #1a1f28', color: '#c9cdd5' }}>
                    <td style={{ padding: '4px 6px', font: '7px DM Mono', color: '#7a8290' }}>{new Date(trade.entry_time).toLocaleDateString('fa-IR')}</td>
                    <td><span style={{ font: '700 7px DM Mono', color: trade.side === 'BUY' ? 'var(--green)' : 'var(--red)' }}>{trade.side}</span></td>
                    <td style={{ font: '7px DM Mono', color: '#7a8290' }}>{trade.position_oz} oz</td>
                    <td style={{ font: '700 8px DM Mono', color: (trade.pnl || 0) >= 0 ? 'var(--green)' : 'var(--red)' }}>
                      {trade.pnl != null ? `${trade.pnl > 0 ? '+' : ''}${trade.pnl.toFixed(2)}$` : '—'}
                    </td>
                    <td style={{ fontSize: 8, color: '#8a909c' }}>{trade.exit_reason}</td>
                  </tr>
                ))}
                {!(data.out_of_sample_trades ?? []).length && (
                  <tr><td colSpan={5} style={{ padding: '10px 8px', color: '#6b7280', fontSize: 9 }}>در این بازه هیچ معامله‌ای ثبت نشد.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div style={{ background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, padding: '8px 10px' }}>
          <b style={{ fontSize: 8, color: '#7af0c8', display: 'flex', alignItems: 'center', gap: 6 }}><TrendingUp size={11}/> نکات خواندن این جدول</b>
          <ul style={{ margin: '6px 0 0', paddingRight: 14, font: '8px DM Mono', color: '#8a909c', lineHeight: 1.7 }}>
            {(data.notes ?? []).map((note, index) => <li key={index}>{note}</li>)}
            <li>هیچ ستون «پیش‌بینی آینده» وجود ندارد؛ عدد آینده فقط با گذشت زمان و دیتای واقعی ساخته می‌شود.</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
