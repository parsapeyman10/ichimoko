import { useEffect, useState } from 'react';
import { AlertTriangle, ListTree, SlidersHorizontal } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Transparency panel: shows the exact engineered feature values the signal engine and
 * predictor are computing right now, straight from the backend's real-candle pipeline.
 * This is the concrete, testable alternative to "act like an excellent trader" via
 * scraping/Selenium: every number here is a named, reviewable rule input, not a black box.
 */
type FeatureList = { count: number; features: string[] };
type FeatureExplain = { features: Record<string, number>; top: [string, number][]; detail?: string };

export default function FeatureInspectorPanel({ timeframe }: { timeframe: string }) {
  const [list, setList] = useState<{ loading: boolean; error?: string; data?: FeatureList }>({ loading: true });
  const [explain, setExplain] = useState<{ loading: boolean; error?: string; data?: FeatureExplain }>({ loading: true });
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    let alive = true;
    apiGet<FeatureList>('/api/v1/features/list').then((result) => {
      if (!alive) return;
      if (result.ok) setList({ loading: false, data: result.data });
      else setList({ loading: false, error: result.error });
    });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<FeatureExplain>(`/api/v1/features/explain?timeframe=${timeframe}`);
      if (!alive) return;
      if (result.ok) setExplain({ loading: false, data: result.data });
      else setExplain({ loading: false, error: result.error });
    };
    void load();
    const timer = window.setInterval(load, 30_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [timeframe]);

  const top = explain.data?.top ?? [];
  const allEntries = Object.entries(explain.data?.features ?? {}).sort((a, b) => a[0].localeCompare(b[0]));

  return (
    <section className="panel" id="feature-inspector" style={{ gridColumn: '1 / -1', overflow: 'hidden', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><SlidersHorizontal size={12}/> شفافیت موتور قانون‌محور · بدون جعبه سیاه</span>
          <h2>ویژگی‌های مهندسی‌شدهٔ زنده {list.data ? `(${list.data.count} ویژگی)` : ''}</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>
            همهٔ این مقادیر مستقیماً از کندل‌های واقعیِ بسته محاسبه می‌شوند؛ همان ورودی‌هایی که پیش‌بینی و اجماع سبک‌ها را می‌سازند — جایگزین قانون‌محور برای وب‌اسکرپینگ/Selenium.
          </small>
        </div>
      </div>

      {(list.error || explain.error) && (
        <div style={{ padding: '12px 14px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8 }}>
          <AlertTriangle size={14} style={{ flexShrink: 0, marginTop: 1 }}/>
          <span>{explain.error ?? list.error} — تا رسیدن دیتای واقعی، ویژگی‌ای محاسبه/نمایش داده نمی‌شود.</span>
        </div>
      )}

      {top.length > 0 && (
        <>
          <div style={{ padding: '10px 14px 0', font: '7px DM Mono', color: '#6b7280' }}>۱۵ ویژگیِ با بیشترین وزن مطلق</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px,1fr))', gap: 8, padding: 12 }}>
            {top.map(([name, value]) => (
              <div key={name} style={{ background: '#0a0e12', border: '1px solid #1f2630', borderRadius: 6, padding: '8px 9px' }}>
                <span style={{ display: 'block', color: '#8a909c', font: '7px DM Mono', wordBreak: 'break-word' }}>{name}</span>
                <b style={{ display: 'block', color: value >= 0 ? 'var(--green)' : 'var(--red)', font: '700 12px DM Mono', marginTop: 4 }}>{value.toFixed(4)}</b>
              </div>
            ))}
          </div>

          <div style={{ padding: '0 14px 12px' }}>
            <button onClick={() => setShowAll((v) => !v)}
              style={{ display: 'flex', alignItems: 'center', gap: 6, background: '#161b22', border: '1px solid #2a303a', color: '#d5d9e0', borderRadius: 6, padding: '6px 10px', font: '8px DM Mono', cursor: 'pointer' }}>
              <ListTree size={12}/> {showAll ? 'بستن فهرست کامل' : `نمایش هر ${allEntries.length} ویژگی`}
            </button>
            {showAll && (
              <div style={{ marginTop: 8, maxHeight: 260, overflowY: 'auto', background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6 }}>
                {allEntries.map(([name, value]) => (
                  <div key={name} style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 9px', borderBottom: '1px solid #12161c', font: '8px DM Mono' }}>
                    <span style={{ color: '#9aa3b2' }}>{name}</span>
                    <b style={{ color: value >= 0 ? 'var(--green)' : 'var(--red)' }}>{value.toFixed(4)}</b>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}

      <div style={{ padding: '8px 14px', background: '#0f1115', borderTop: '1px solid var(--line)', color: '#7a8290', fontSize: 9 }}>
        هر ۳۰ ثانیه از <code style={{ fontFamily: 'DM Mono' }}>/api/v1/features/explain</code> خوانده می‌شود · بدون fallback ساختگی
      </div>
    </section>
  );
}
