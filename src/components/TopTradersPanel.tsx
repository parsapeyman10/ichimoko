import { useEffect, useState } from 'react';
import { AlertTriangle, Scale, Users } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Analysis-style ensemble — each archetype is a deterministic rule set computed on real candles
 * by the backend. The previous version fell back to `Math.random()` votes and printed
 * "PF 1.35 → 1.62" style claims; both are gone.
 */
type Vote = {
  id: string;
  name: string;
  style: string;
  direction: string;
  confidence: number;
  weight: number;
  reason: string;
  is_veto: boolean;
};

type Ensemble = {
  consensus: string;
  consensus_confidence?: number;
  agreement?: number;
  quality?: string;
  advisory?: string;
  veto_reasons?: string[];
  votes: Vote[];
  error?: string;
};

export default function TopTradersPanel({ timeframe }: { timeframe: string }) {
  const [state, setState] = useState<{ loading: boolean; error?: string; data?: Ensemble }>({ loading: true });

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<Ensemble>(`/api/v1/traders/ensemble?timeframe=${timeframe}`);
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

  const data = state.data;
  const consensus = data?.consensus ?? 'NEUTRAL';

  return (
    <section className="panel" id="traders" style={{ gridColumn: '1 / -1', overflow: 'hidden', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><Users size={12}/> شش سبک تحلیلی · رای‌گیری وزن‌دار روی کندل واقعی</span>
          <h2>اجماع سبک‌ها (نه وعده سود)</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>
            ICT/SMC · روند · بازگشت به میانگین · ماکرو · اسکالپ ریزساختار · عرضه/تقاضا — همه قاعده‌محور و قابل بازتولید؛ هیچ عدد عملکردی ادعا نمی‌شود.
          </small>
        </div>
        <span className="live-tag" style={{ color: state.error ? 'var(--red)' : 'var(--purple)' }}>
          <i style={{ background: state.error ? 'var(--red)' : '#9d83e9', boxShadow: 'none' }}/>{state.loading ? 'در حال محاسبه' : state.error ? 'بدون داده' : 'زنده'}
        </span>
      </div>

      {state.error && (
        <div style={{ padding: '14px 16px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }}/>
          <span>{state.error} — بدون دیتای واقعی، رای‌گیری انجام نمی‌شود.</span>
        </div>
      )}

      {data && !data.error && (
        <>
          <div style={{ padding: '12px 14px', display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'center' }}>
            <div>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>اجماع</span>
              <b style={{ display: 'block', color: consensus === 'BUY' ? 'var(--green)' : consensus === 'SELL' ? 'var(--red)' : '#c9cdd5', font: '800 15px Manrope', marginTop: 2 }}>
                {consensus === 'BUY' ? 'صعودی BUY' : consensus === 'SELL' ? 'نزولی SELL' : 'خنثی NEUTRAL'}
              </b>
            </div>
            <div>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>هم‌جهتی وزنی</span>
              <b style={{ display: 'block', color: 'var(--gold)', font: '800 15px DM Mono', marginTop: 2 }}>
                {data.agreement != null ? `${(data.agreement * 100).toFixed(0)}%` : '—'}
              </b>
            </div>
            <div>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>کیفیت هم‌جهتی</span>
              <b style={{ display: 'block', color: '#c9cdd5', font: '800 15px DM Mono', marginTop: 2 }}>{data.quality ?? '—'}</b>
            </div>
            <div>
              <span style={{ color: '#6b7280', font: '7px DM Mono' }}>وتو</span>
              <b style={{ display: 'block', color: (data.veto_reasons ?? []).length ? 'var(--red)' : 'var(--green)', font: '800 15px DM Mono', marginTop: 2 }}>
                {(data.veto_reasons ?? []).length ? 'دارد' : 'ندارد'}
              </b>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 1, background: '#1a1f28' }}>
            {data.votes.map((vote) => (
              <div key={vote.id} style={{ background: '#0d1117', padding: '10px 11px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <b style={{ fontSize: 11, color: '#d5d9e0' }}>{vote.name}</b>
                  <span style={{ font: '7px DM Mono', color: '#6b7280' }}>وزن {(vote.weight * 100).toFixed(0)}%</span>
                </div>
                <small style={{ display: 'block', color: '#6b7280', fontSize: 8, marginTop: 3 }}>{vote.style}</small>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6, font: '8px DM Mono' }}>
                  <span style={{ color: vote.direction === 'BUY' ? 'var(--green)' : vote.direction === 'SELL' ? 'var(--red)' : '#8a909c' }}>
                    {vote.direction}
                  </span>
                  <span style={{ color: '#8a909c' }}>{vote.confidence}%</span>
                </div>
                <p style={{ margin: '6px 0 0', fontSize: 9, color: '#9aa3b2', lineHeight: 1.6 }}>{vote.reason}</p>
                {vote.is_veto && (
                  <span style={{ display: 'inline-block', marginTop: 6, background: '#ef63711f', border: '1px solid #ef637140', color: '#ef8b95', borderRadius: 3, padding: '1px 5px', font: '7px DM Mono' }}>
                    VETO
                  </span>
                )}
              </div>
            ))}
          </div>

          {data.advisory && (
            <div style={{ padding: '10px 14px', borderTop: '1px solid var(--line)', color: '#c9b896', fontSize: 10, lineHeight: 1.7 }}>
              <Scale size={12} style={{ verticalAlign: '-2px', marginLeft: 4 }}/> {data.advisory}
            </div>
          )}
        </>
      )}

      <div style={{ padding: '8px 14px', background: '#0f1115', borderTop: '1px solid var(--line)', color: '#6b7280', font: '8px DM Mono' }}>
        منبع: <code style={{ fontFamily: 'DM Mono' }}>/api/v1/traders/ensemble</code> · رای‌ها روی کندل‌های واقعی محاسبه می‌شوند
      </div>
    </section>
  );
}
