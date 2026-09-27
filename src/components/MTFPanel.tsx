import { useEffect, useState } from 'react';
import { Activity, AlertTriangle, Layers } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Multi-timeframe confluence — real candles only.
 * The old version carried hard-coded performance claims ("PF 1.62", "73% false breaks"):
 * all of them were removed. What is left is the measurement the backend actually computes.
 */
type MTFStatus = {
  mtf_bias?: string;
  alignment?: number;
  buy_count?: number;
  sell_count?: number;
  neutral_count?: number;
  weights?: Record<string, number>;
  // Backend key is `per_tf` (see app/services/mtf_analyzer.py::mtf_confluence), not `timeframes`.
  per_tf?: Record<string, { bias?: string; strength?: number; adx?: number; ema_dist?: number; rsi?: number; score?: number; candles?: number }>;
  advisory?: string;
  // Backend key is `is_veto`, not `veto`.
  is_veto?: boolean;
  veto_reasons?: string[];
  error?: string;
};

export default function MTFPanel({ timeframe }: { timeframe: string }) {
  const [state, setState] = useState<{ loading: boolean; error?: string; data?: MTFStatus }>({ loading: true });

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<MTFStatus>(`/api/v1/mtf/status?timeframe=${timeframe}`);
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

  const mtf = state.data;
  const bias = mtf?.mtf_bias ?? 'NEUTRAL';
  const isBuy = bias === 'BUY';
  const isSell = bias === 'SELL';
  const isVeto = Boolean(mtf?.is_veto);
  const alignment = mtf?.alignment ?? 0;

  return (
    <section className="panel" id="mtf" style={{ gridColumn: '1 / -1', overflow: 'hidden', borderColor: isVeto ? '#ef637140' : isBuy ? '#28c99b30' : isSell ? '#ef637130' : '#2a303a', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><Layers size={12}/> تراز چندتایم‌فریم · بازنمونه‌گیری از کندل‌های واقعی</span>
          <h2>همگرایی تایم‌فریم‌ها (1m → 1h)</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>تایم‌فریم‌های بالاتر از همان کندل‌های واقعی بازنمونه‌گیری می‌شوند؛ داده جداگانه‌ای ساخته نمی‌شود.</small>
        </div>
        <span className="live-tag" style={{ color: state.error ? 'var(--red)' : 'var(--gold)' }}>
          <i style={{ background: state.error ? 'var(--red)' : 'var(--gold)', boxShadow: 'none' }}/>{state.loading ? 'در حال محاسبه' : state.error ? 'بدون داده' : 'زنده'}
        </span>
      </div>

      {state.error && (
        <div style={{ padding: '14px 16px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }}/>
          <span>{state.error}</span>
        </div>
      )}

      {mtf && !mtf.error && (
        <>
          <div style={{ padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>تراز کلی</span>
              <b style={{ display: 'block', color: isVeto ? 'var(--red)' : isBuy ? 'var(--green)' : isSell ? 'var(--red)' : '#c9cdd5', font: '800 16px Manrope', marginTop: 2 }}>
                {isVeto ? 'وتو — واگرایی تایم بالا' : isBuy ? 'صعودی BUY' : isSell ? 'نزولی SELL' : 'خنثی NEUTRAL'} · {(alignment * 100).toFixed(0)}% همگرایی
              </b>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', font: '7px DM Mono', color: '#6b7280' }}>
              <span>همگرایی</span>
              <span>{(mtf.buy_count ?? 0)} صعودی / {(mtf.sell_count ?? 0)} نزولی / {(mtf.neutral_count ?? 0)} خنثی</span>
            </div>
            <div className="range" style={{ height: 5, background: '#1a1f28', borderRadius: 3, overflow: 'hidden' }}>
              <div style={{ width: `${alignment * 100}%`, height: '100%', background: isBuy ? 'var(--green)' : isSell ? 'var(--red)' : '#6b7280' }} />
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {Object.entries(mtf.weights ?? {}).map(([tf, weight]) => (
                <span key={tf} style={{ background: '#11151b', border: '1px solid #1f2630', borderRadius: 3, padding: '2px 6px', font: '7px DM Mono', color: '#8a909c' }}>
                  {tf}: {(weight * 100).toFixed(0)}%
                </span>
              ))}
            </div>
            {mtf.advisory && (
              <p style={{ margin: 0, fontSize: 10, color: '#c9b896', lineHeight: 1.7 }}>{mtf.advisory}</p>
            )}
            {isVeto && mtf.veto_reasons && mtf.veto_reasons.length > 0 && (
              <ul style={{ margin: 0, paddingInlineStart: 16, fontSize: 10, color: '#ef6371', lineHeight: 1.7 }}>
                {mtf.veto_reasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            )}
          </div>

          {mtf.per_tf && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 1, background: '#1a1f28' }}>
              {Object.entries(mtf.per_tf).map(([tf, info]) => (
                <div key={tf} style={{ background: '#0d1117', padding: '9px 11px' }}>
                  <span style={{ display: 'block', color: '#6b7280', font: '7px DM Mono' }}>{tf}</span>
                  <b style={{ display: 'block', marginTop: 3, color: info.bias === 'BUY' ? 'var(--green)' : info.bias === 'SELL' ? 'var(--red)' : '#c9cdd5', font: '700 11px DM Mono' }}>
                    {info.bias ?? '—'}
                  </b>
                  <small style={{ display: 'block', color: '#6b7280', fontSize: 8, marginTop: 2 }}>
                    {info.strength != null ? `قدرت ${info.strength}` : ''}
                    {info.adx != null ? ` · ADX ${info.adx}` : ''}
                    {info.candles != null ? ` · ${info.candles} کندل` : ''}
                  </small>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <div style={{ padding: '8px 14px', background: '#0f1115', borderTop: '1px solid var(--line)', color: '#6b7280', font: '8px DM Mono', display: 'flex', alignItems: 'center', gap: 6 }}>
        <Activity size={11}/> منبع: <code style={{ fontFamily: 'DM Mono' }}>/api/v1/mtf/status</code> روی کندل‌های واقعی · بدون ادعای عملکرد
      </div>
    </section>
  );
}
