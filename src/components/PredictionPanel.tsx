import { useEffect, useState } from 'react';
import { Activity, AlertTriangle, Brain, TrendingDown, TrendingUp } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Behavioural predictor — reads ONLY the backend's real-candle computation.
 * The previous version fell back to client-side `Math.random()` when the API failed:
 * that fallback is gone. If the API is unavailable the panel says so.
 */
type Prediction = {
  expected_direction: string;
  confidence: number;
  expected_value_R?: number;
  is_actionable: boolean;
  explanation?: string;
  horizon?: string;
  top_features?: { name: string; value: number; contribution?: number }[];
  scenario?: { direction?: string; probability?: number }[];
};

export default function PredictionPanel({ timeframe }: { timeframe: string }) {
  const [state, setState] = useState<{ loading: boolean; error?: string; data?: Prediction }>({ loading: true });

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<Prediction>(`/api/v1/predict/next?timeframe=${timeframe}`);
      if (!alive) return;
      if (result.ok) setState({ loading: false, data: result.data });
      else setState({ loading: false, error: result.error });
    };
    void load();
    const timer = window.setInterval(load, 30_000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [timeframe]);

  const prediction = state.data;
  const direction = prediction?.expected_direction ?? 'NEUTRAL';
  const isBuy = direction === 'BUY';
  const isSell = direction === 'SELL';
  const positive = isBuy ? prediction?.confidence ?? 0 : 0;
  const negative = isSell ? prediction?.confidence ?? 0 : 0;
  const neutral = prediction ? Math.max(0, 100 - positive - negative) : 100;

  return (
    <section className="panel" id="predict" style={{ gridColumn: '1 / -1', overflow: 'hidden', borderColor: isBuy ? '#28c99b40' : isSell ? '#ef637140' : '#2a303a', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><Brain size={12}/> رفتارشناسی سفارشات · از کندل‌های واقعی</span>
          <h2>پیش‌بینی رفتار ۱ تا ۳ کندل بعد</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>
            همه ورودی‌ها از کندل‌های واقعیِ بسته محاسبه می‌شوند (بدون نشت آینده). این یک مدل قاعده‌محور است، نه وعده سود.
          </small>
        </div>
        <span className="live-tag" style={{ color: state.loading ? '#8a909c' : state.error ? 'var(--red)' : '#7af0c8' }}>
          <i style={{ background: state.loading ? '#8a909c' : state.error ? 'var(--red)' : 'var(--green)', boxShadow: 'none' }}/>{state.loading ? 'در حال محاسبه' : state.error ? 'بدون داده' : 'زنده'}
        </span>
      </div>

      {state.error && (
        <div style={{ padding: '14px 16px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }}/>
          <span>{state.error} — تا وقتی دیتای واقعی نرسد، هیچ پیش‌بینی‌ای ساخته نمی‌شود.</span>
        </div>
      )}

      {prediction && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 1, background: '#1a1f28' }}>
            {[
              { k: 'صعود', v: positive, c: 'var(--green)' },
              { k: 'خنثی', v: neutral, c: '#8a909c' },
              { k: 'نزول', v: negative, c: 'var(--red)' },
            ].map((x) => (
              <div key={x.k} style={{ background: '#0d1117', padding: '10px 12px', position: 'relative' }}>
                <span style={{ display: 'block', color: '#6b7280', font: '7px DM Mono' }}>{x.k}</span>
                <b style={{ display: 'block', color: x.c, font: '800 18px DM Mono', marginTop: 4 }}>{x.v.toFixed(1)}%</b>
                <div style={{ position: 'absolute', bottom: 0, left: 0, height: 3, width: `${x.v}%`, background: x.c, opacity: 0.9 }} />
              </div>
            ))}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12, padding: 14 }}>
            <div style={{ background: '#0a0e12', border: '1px solid #1f2630', borderRadius: 8, padding: '11px 12px' }}>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>جهت مورد انتظار</span>
              <b style={{ display: 'block', color: isBuy ? 'var(--green)' : isSell ? 'var(--red)' : '#c9cdd5', font: '800 16px Manrope', marginTop: 2 }}>
                {isBuy && <TrendingUp size={16} style={{ verticalAlign: '-3px', marginLeft: 4 }}/>}
                {isSell && <TrendingDown size={16} style={{ verticalAlign: '-3px', marginLeft: 4 }}/>}
                {isBuy ? 'صعود BUY' : isSell ? 'نزول SELL' : 'خنثی NEUTRAL'} · {prediction.confidence.toFixed(1)}%
              </b>
              <small style={{ display: 'block', color: '#6b7280', fontSize: 9, marginTop: 4 }}>
                EV: {prediction.expected_value_R != null ? `${prediction.expected_value_R.toFixed(3)}R` : '—'} · قابل معامله: {prediction.is_actionable ? 'بله' : 'خیر'}
              </small>
              <small style={{ display: 'block', color: '#6b7280', fontSize: 9, marginTop: 2 }}>{prediction.horizon ?? ''}</small>
            </div>

            <div style={{ background: '#0a0e12', border: '1px solid #1f2630', borderRadius: 8, padding: '11px 12px' }}>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>مهم‌ترین ورودی‌ها</span>
              <div style={{ display: 'grid', gap: 4, marginTop: 6 }}>
                {(prediction.top_features ?? []).slice(0, 6).map((feature, index) => (
                  <div key={`${feature.name}-${index}`} style={{ display: 'flex', justifyContent: 'space-between', font: '8px DM Mono', color: '#c9cdd5' }}>
                    <span>{feature.name}</span>
                    <b style={{ color: feature.value >= 0 ? 'var(--green)' : 'var(--red)' }}>{feature.value.toFixed(2)}</b>
                  </div>
                ))}
                {!(prediction.top_features ?? []).length && <small style={{ color: '#6b7280', fontSize: 9 }}>فهرست ورودی‌ها از بک‌اند ارسال نشد.</small>}
              </div>
            </div>

            <div style={{ background: '#0a0e12', border: '1px solid #1f2630', borderRadius: 8, padding: '11px 12px' }}>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>توضیح مدل</span>
              <p style={{ margin: '6px 0 0', fontSize: 10, color: '#9aa3b2', lineHeight: 1.7 }}>
                {prediction.explanation ?? 'توضیحی ارسال نشده است.'}
              </p>
            </div>
          </div>
        </>
      )}

      <div style={{ padding: '8px 14px', background: '#0f1115', borderTop: '1px solid var(--line)', display: 'flex', gap: 8, alignItems: 'center', color: '#7a8290', fontSize: 9 }}>
        <Activity size={12}/>
        هر ۳۰ ثانیه از <code style={{ fontFamily: 'DM Mono' }}>/api/v1/predict/next</code> خوانده می‌شود · بدون fallback ساختگی
      </div>
    </section>
  );
}
