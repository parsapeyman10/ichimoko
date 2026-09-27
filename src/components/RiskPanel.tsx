import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Calculator, Landmark, ShieldAlert } from 'lucide-react';
import { apiGet, apiPost } from '../lib/api';

/**
 * Position sizing, broker execution cost and a bootstrap stress test.
 *
 * These three backend endpoints (/api/v1/risk/calculate, /api/v1/brokers*,
 * /api/v1/risk/stress-test) already existed but had no UI surface at all —
 * this panel is the first one. Every number here is either a direct
 * arithmetic function of numbers the user typed, or (for the stress test)
 * a resample of the REAL closed trades from the live backtest engine. There
 * is no invented win-rate or simulated price path.
 */
type RiskCalc = {
  balance: number;
  risk_percent: number;
  risk_amount: number;
  entry: number;
  stop_loss: number;
  stop_distance: number;
  position_oz: number;
  position_lots: number;
  notional: number;
  min_lot_oz: number;
  executable_oz: number;
  executable_risk_usd: number;
  executable_risk_pct: number | null;
  warning?: string | null;
};

type BrokerConfig = {
  name: string;
  leverage: number;
  spread_gold: number;
  commission_per_oz: number;
  commission_per_lot: number;
  min_lot: number;
  swap_long_per_night: number;
  swap_short_per_night: number;
  min_deposit: number;
  description: string;
};

type BrokerList = { recommended: BrokerConfig; brokers: BrokerConfig[]; note: string };

type BrokerCost = {
  spread_cost: number;
  commission: number;
  swap: number;
  total_fee_roundtrip: number;
  total_with_swap: number;
  notional: number;
  margin_required: number;
  leverage_used: number;
};

type StressTest = {
  error?: string;
  real_trades?: number;
  method?: string;
  runs?: number;
  median_final_balance?: number;
  p05_final_balance?: number;
  p95_final_balance?: number;
  median_max_drawdown_pct?: number;
  p95_max_drawdown_pct?: number;
  risk_of_80pct_loss_pct?: number;
  note?: string;
  data_source?: string;
  bars_used?: number;
};

const box: React.CSSProperties = { background: '#0a0e12', border: '1px solid #1f2630', borderRadius: 8, padding: '11px 12px' };
const label: React.CSSProperties = { display: 'grid', gap: 4, font: '8px DM Mono', color: '#6b7280' };
const input: React.CSSProperties = { background: '#11151b', color: '#d5d9e0', border: '1px solid #1f2630', borderRadius: 5, padding: '6px 8px', font: '9px DM Mono', width: 110 };

export default function RiskPanel() {
  const [balance, setBalance] = useState(100);
  const [riskPercent, setRiskPercent] = useState(0.5);
  const [entry, setEntry] = useState(2650);
  const [stopLoss, setStopLoss] = useState(2635);
  const [direction, setDirection] = useState<'BUY' | 'SELL'>('BUY');
  const [holdsDays, setHoldsDays] = useState(0);

  const [calc, setCalc] = useState<{ loading: boolean; error?: string; data?: RiskCalc }>({ loading: true });
  const [brokers, setBrokers] = useState<{ loading: boolean; error?: string; data?: BrokerList }>({ loading: true });
  const [brokerName, setBrokerName] = useState<string>('');
  const [cost, setCost] = useState<{ loading: boolean; error?: string; data?: BrokerCost }>({ loading: true });
  const [stress, setStress] = useState<{ running: boolean; error?: string; data?: StressTest }>({ running: false });

  useEffect(() => {
    let alive = true;
    const load = async () => {
      setCalc((prev) => ({ ...prev, loading: true }));
      const query = new URLSearchParams({ entry: String(entry), stop_loss: String(stopLoss) });
      const result = await apiPost<RiskCalc>(`/api/v1/risk/calculate?${query.toString()}`, {
        balance, risk_percent: riskPercent, contract_size: 1,
      });
      if (!alive) return;
      if (result.ok) setCalc({ loading: false, data: result.data });
      else setCalc({ loading: false, error: result.error });
    };
    void load();
    return () => { alive = false; };
  }, [balance, riskPercent, entry, stopLoss]);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<BrokerList>('/api/v1/brokers');
      if (!alive) return;
      if (result.ok) {
        setBrokers({ loading: false, data: result.data });
        setBrokerName((current) => current || result.data.recommended.name);
      } else setBrokers({ loading: false, error: result.error });
    };
    void load();
    return () => { alive = false; };
  }, []);

  const positionOz = calc.data?.executable_oz ?? 1;

  useEffect(() => {
    if (!brokerName) return;
    let alive = true;
    const load = async () => {
      setCost((prev) => ({ ...prev, loading: true }));
      const query = new URLSearchParams({
        position_oz: String(positionOz), entry: String(entry),
        holds_days: String(holdsDays), direction,
      });
      const result = await apiGet<BrokerCost>(`/api/v1/brokers/${encodeURIComponent(brokerName)}/cost?${query.toString()}`);
      if (!alive) return;
      if (result.ok) setCost({ loading: false, data: result.data });
      else setCost({ loading: false, error: result.error });
    };
    void load();
    return () => { alive = false; };
  }, [brokerName, positionOz, entry, holdsDays, direction]);

  const runStressTest = async () => {
    setStress({ running: true });
    const result = await apiGet<StressTest>('/api/v1/risk/stress-test?timeframe=5m&bars=1500&runs=5000&initial_balance=' + balance);
    if (!result.ok) return setStress({ running: false, error: result.error });
    if (result.data?.error) return setStress({ running: false, error: result.data.error });
    setStress({ running: false, data: result.data });
  };

  const data = calc.data;
  const brokerOptions = useMemo(() => brokers.data?.brokers ?? [], [brokers.data]);

  return (
    <section className="panel" id="risk-lab" style={{ gridColumn: '1 / -1', overflow: 'hidden', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><Calculator size={12}/> مدیریت ریسک · حجم پوزیشن، هزینه بروکر و آزمون استرس</span>
          <h2>ماشین‌حساب ریسک و مقایسه بروکر</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>
            حجم و ریسک از عدد‌های واردشده محاسبه می‌شود؛ آزمون استرس از بازنمونه‌گیری معاملات واقعیِ بک‌تست ساخته می‌شود، نه از فرض وین‌ریت.
          </small>
        </div>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'flex-end', padding: '10px 14px', borderBottom: '1px solid var(--line)' }}>
        <label style={label}>موجودی (USD)
          <input style={input} type="number" min={10} max={100000} value={balance} onChange={(e) => setBalance(Number(e.target.value) || 0)} />
        </label>
        <label style={label}>ریسک هر معامله (٪)
          <input style={input} type="number" min={0.1} max={5} step={0.1} value={riskPercent} onChange={(e) => setRiskPercent(Number(e.target.value) || 0)} />
        </label>
        <label style={label}>قیمت ورود
          <input style={input} type="number" value={entry} onChange={(e) => setEntry(Number(e.target.value) || 0)} />
        </label>
        <label style={label}>حد ضرر
          <input style={input} type="number" value={stopLoss} onChange={(e) => setStopLoss(Number(e.target.value) || 0)} />
        </label>
        <label style={label}>جهت
          <select style={input} value={direction} onChange={(e) => setDirection(e.target.value === 'SELL' ? 'SELL' : 'BUY')}>
            <option value="BUY">BUY</option><option value="SELL">SELL</option>
          </select>
        </label>
        <label style={label}>روزهای نگهداری (سواپ)
          <input style={input} type="number" min={0} max={60} value={holdsDays} onChange={(e) => setHoldsDays(Number(e.target.value) || 0)} />
        </label>
      </div>

      {calc.error && (
        <div style={{ padding: '12px 14px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8 }}>
          <AlertTriangle size={14}/> {calc.error}
        </div>
      )}

      {data && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12, padding: 14 }}>
          <div style={box}>
            <span style={{ color: '#6b7280', font: '7px DM Mono' }}>اندازه پوزیشن</span>
            <b style={{ display: 'block', color: '#e6e9ee', font: '800 16px Manrope', marginTop: 4 }}>
              {data.executable_oz.toFixed(3)} انس · {data.position_lots.toFixed(3)} لات
            </b>
            <small style={{ display: 'block', color: '#6b7280', fontSize: 9, marginTop: 4 }}>
              ارزش فرضی {data.notional.toFixed(2)}$ · حداقل لات بروکر {data.min_lot_oz} انس
            </small>
            {data.warning && <small style={{ display: 'block', color: '#e6a244', fontSize: 9, marginTop: 4 }}>{data.warning}</small>}
          </div>
          <div style={box}>
            <span style={{ color: '#6b7280', font: '7px DM Mono' }}>ریسک واقعی قابل اجرا</span>
            <b style={{ display: 'block', color: 'var(--red)', font: '800 16px Manrope', marginTop: 4 }}>
              {data.executable_risk_usd.toFixed(2)}$ ({data.executable_risk_pct != null ? `${data.executable_risk_pct.toFixed(2)}٪` : '—'})
            </b>
            <small style={{ display: 'block', color: '#6b7280', fontSize: 9, marginTop: 4 }}>
              ریسک هدف {data.risk_amount.toFixed(2)}$ · فاصله حد ضرر {data.stop_distance.toFixed(2)}$
            </small>
          </div>
          <div style={box}>
            <span style={{ color: '#6b7280', font: '7px DM Mono' }}><Landmark size={11} style={{ verticalAlign: '-2px' }}/> بروکر</span>
            <select style={{ ...input, width: '100%', marginTop: 6 }} value={brokerName} onChange={(e) => setBrokerName(e.target.value)}>
              {brokerOptions.map((b) => <option key={b.name} value={b.name}>{b.name}</option>)}
            </select>
            {brokers.error && <small style={{ display: 'block', color: '#e6a244', fontSize: 9, marginTop: 4 }}>{brokers.error}</small>}
            {cost.error && <small style={{ display: 'block', color: '#e6a244', fontSize: 9, marginTop: 4 }}>{cost.error}</small>}
            {cost.data && (
              <small style={{ display: 'block', color: '#9aa3b2', fontSize: 9, marginTop: 4, lineHeight: 1.6 }}>
                هزینهٔ رفت‌وبرگشت {cost.data.total_fee_roundtrip.toFixed(2)}$ (اسپرد {cost.data.spread_cost.toFixed(2)}$ + کمیسیون {cost.data.commission.toFixed(2)}$)
                {holdsDays > 0 && ` + سواپ ${cost.data.swap.toFixed(2)}$`} · مارجین لازم {cost.data.margin_required.toFixed(2)}$
              </small>
            )}
          </div>
        </div>
      )}

      <div style={{ padding: '10px 14px', borderTop: '1px solid var(--line)' }}>
        <button onClick={() => void runStressTest()} disabled={stress.running}
          style={{ display: 'flex', alignItems: 'center', gap: 6, background: '#161b22', border: '1px solid #2a303a', color: '#d5d9e0', borderRadius: 6, padding: '7px 12px', font: '9px DM Mono', cursor: 'pointer' }}>
          <ShieldAlert size={13}/> {stress.running ? 'در حال اجرای بازنمونه‌گیری…' : 'اجرای آزمون استرس روی معاملات واقعی بک‌تست'}
        </button>
        {stress.error && (
          <div style={{ marginTop: 8, color: '#e6a244', fontSize: 10, display: 'flex', gap: 6, alignItems: 'flex-start' }}>
            <AlertTriangle size={13} style={{ flexShrink: 0, marginTop: 1 }}/> {stress.error}
          </div>
        )}
        {stress.data && !stress.data.error && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px,1fr))', gap: 8, marginTop: 10 }}>
            {[
              { k: 'معاملات واقعی', v: String(stress.data.real_trades ?? '—') },
              { k: 'موجودی میانه پس از اجرا', v: `${stress.data.median_final_balance?.toFixed(2)}$` },
              { k: 'بازه P05–P95', v: `${stress.data.p05_final_balance?.toFixed(0)}–${stress.data.p95_final_balance?.toFixed(0)}$` },
              { k: 'افت میانه/P95', v: `${stress.data.median_max_drawdown_pct?.toFixed(1)}٪ / ${stress.data.p95_max_drawdown_pct?.toFixed(1)}٪` },
              { k: 'ریسک افت ۸۰٪', v: `${stress.data.risk_of_80pct_loss_pct?.toFixed(2)}٪` },
            ].map((s) => (
              <div key={s.k} style={{ background: '#11151b', borderRadius: 5, padding: '7px 8px', textAlign: 'center' }}>
                <span style={{ display: 'block', color: '#6b7280', font: '7px DM Mono' }}>{s.k}</span>
                <b style={{ display: 'block', color: '#e6e9ee', font: '700 11px DM Mono', marginTop: 3 }}>{s.v}</b>
              </div>
            ))}
          </div>
        )}
        {stress.data?.note && <small style={{ display: 'block', color: '#6b7280', fontSize: 9, marginTop: 6 }}>{stress.data.note}</small>}
      </div>
    </section>
  );
}
