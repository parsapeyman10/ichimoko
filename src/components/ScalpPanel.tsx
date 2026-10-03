import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle, ArrowDownRight, ArrowUpRight, Ban, Crosshair, Gauge,
  LogIn, LogOut, RefreshCw, Search, Stethoscope, Target, Timer,
} from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Multi-symbol scalping desk.
 *
 * Scalp entries are deliberately restricted to currency pairs (`scalp_enabled` on the
 * backend spec). Metals and crypto are still browsable and still produce signals, but the
 * panel marks them view-only instead of pretending a 2.8-pip gold spread or a 24/7 alt
 * is a scalp. The backend is the single source of that rule — this component only renders
 * what `spec.scalp_enabled` says.
 *
 * Nothing here invents a price. Every failure path shows the backend's explicit error.
 */

type Instrument = {
  symbol: string;
  display: string;
  kind: 'forex' | 'metal' | 'crypto';
  base: string;
  quote: string;
  venue: string;
  price_precision: number;
  tick_size: number;
  pip_size: number;
  unit_label: string;
  scalp_enabled: boolean;
  model: {
    thresholds: Record<string, number>;
    session_24h: boolean;
    weekend_gap_risk: boolean;
    min_adx: number;
    rr_base: number;
    rr_runner: number;
    typical_spread_pips: number;
    max_leverage: number;
  };
};

type UniverseResponse = {
  total: number;
  returned: number;
  crypto_error: string | null;
  forex_history_available: boolean;
  forex_history_note: string;
  instruments: Instrument[];
};

type Signal = {
  action: 'BUY' | 'SELL' | 'NO_TRADE';
  confidence: number;
  threshold: number;
  entry: number | null;
  stop_loss: number | null;
  take_profit: number | null;
  risk_reward: number | null;
  reasons: string[];
  blockers: string[];
  confluence: { name: string; ok: boolean; detail: string }[];
  exit_hint: string | null;
};

type ExitPlan = {
  has_plan: boolean;
  risk_pips?: number;
  reward_pips?: number;
  breakeven_trigger?: number;
  breakeven_stop?: number;
  lock_trigger?: number;
  lock_stop?: number;
  kijun_trail?: number | null;
  time_stop_bars?: number;
  time_stop_minutes?: number;
  rules?: string[];
  note?: string;
};

type Sizing = {
  risk_pct: number;
  risk_cash: number;
  units: number;
  unit_label: string;
  lots: number | null;
  notional: number;
  note: string;
};

type ScalpResult = {
  symbol: string;
  timeframe: string;
  ok: boolean;
  error?: string;
  bars?: number;
  scalp_allowed?: boolean;
  policy_block?: string | null;
  spec?: Instrument;
  last_candle?: { timestamp: string; close: number; age_seconds: number };
  quote?: { bid: number; ask: number; spread_pips: number; source: string } | null;
  context?: {
    spread_pips: number;
    typical_spread_pips: number;
    htf_bias: string;
    volatility_regime: string;
    weekend_gap_risk: boolean;
  };
  signal?: Signal;
  exit_plan?: ExitPlan;
  sizing?: Sizing | null;
};

type ScanResponse = { timeframe: string; scanned: number; results: ScalpResult[] };

type Diagnosis = {
  symbol: string;
  timeframe: string;
  checks: { name: string; passed: boolean; measured: string; fix: string }[];
  score: number;
  threshold: number;
  action: string;
  hard_gates: string[];
  soft_blockers: string[];
  verdict: string;
};

const TIMEFRAMES = ['1m', '3m', '5m', '15m'] as const;
const DEFAULT_WATCHLIST = ['EUR/USD', 'GBP/USD', 'USD/JPY', 'AUD/USD', 'USD/CAD', 'EUR/JPY'];

const KIND_LABEL: Record<string, string> = { forex: 'جفت‌ارز', metal: 'فلز', crypto: 'کریپتو' };

function fmt(value: number | null | undefined, precision = 5): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return value.toFixed(precision);
}

function ActionBadge({ action }: { action: string }) {
  if (action === 'BUY') return <span className="scalp-badge buy"><ArrowUpRight size={12} /> خرید</span>;
  if (action === 'SELL') return <span className="scalp-badge sell"><ArrowDownRight size={12} /> فروش</span>;
  return <span className="scalp-badge flat"><Ban size={12} /> بدون ورود</span>;
}

export default function ScalpPanel() {
  const [universe, setUniverse] = useState<UniverseResponse | null>(null);
  const [universeError, setUniverseError] = useState<string | null>(null);
  const [kind, setKind] = useState<'all' | 'forex' | 'crypto' | 'metal'>('forex');
  const [query, setQuery] = useState('');
  const [watchlist, setWatchlist] = useState<string[]>(DEFAULT_WATCHLIST);
  const [timeframe, setTimeframe] = useState<(typeof TIMEFRAMES)[number]>('5m');
  const [equity, setEquity] = useState(100);
  const [riskPct, setRiskPct] = useState(0.5);
  const [onlySignals, setOnlySignals] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [scan, setScan] = useState<ScanResponse | null>(null);
  const [scanError, setScanError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [diagnosis, setDiagnosis] = useState<Diagnosis | null>(null);
  const [diagLoading, setDiagLoading] = useState(false);

  // ── universe ──────────────────────────────────────────────────────────
  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<UniverseResponse>(
        `/api/v1/instruments?kind=${kind}&q=${encodeURIComponent(query.trim())}&limit=300`,
      );
      if (!alive) return;
      if (result.ok) {
        setUniverse(result.data);
        setUniverseError(null);
      } else {
        setUniverse(null);
        setUniverseError(result.error);
      }
    };
    const timer = window.setTimeout(load, query ? 300 : 0);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [kind, query]);

  const toggle = useCallback((symbol: string) => {
    setWatchlist((current) =>
      current.includes(symbol)
        ? current.filter((s) => s !== symbol)
        : current.length >= 40 ? current : [...current, symbol],
    );
  }, []);

  // ── scan ──────────────────────────────────────────────────────────────
  const runScan = useCallback(async () => {
    if (!watchlist.length) {
      setScanError('حداقل یک نماد را انتخاب کنید');
      return;
    }
    setScanning(true);
    setScanError(null);
    const params = new URLSearchParams({
      symbols: watchlist.join(','),
      timeframe,
      equity: String(equity),
      risk_pct: String(riskPct),
      only_signals: String(onlySignals),
    });
    const result = await apiGet<ScanResponse>(`/api/v1/scalp/scan?${params}`);
    setScanning(false);
    if (result.ok) {
      setScan(result.data);
      setScanError(null);
    } else {
      setScan(null);
      setScanError(result.error);
    }
  }, [watchlist, timeframe, equity, riskPct, onlySignals]);

  const loadDiagnosis = useCallback(async (symbol: string) => {
    setDiagLoading(true);
    setDiagnosis(null);
    const result = await apiGet<Diagnosis>(
      `/api/v1/scalp/diagnose?symbol=${encodeURIComponent(symbol)}&timeframe=${timeframe}`,
    );
    setDiagLoading(false);
    if (result.ok) setDiagnosis(result.data);
    else setDiagnosis({
      symbol, timeframe, checks: [], score: 0, threshold: 0,
      action: 'NO_TRADE', hard_gates: [result.error], soft_blockers: [], verdict: result.error,
    });
  }, [timeframe]);

  const detail = useMemo(
    () => scan?.results.find((r) => r.symbol === selected) ?? null,
    [scan, selected],
  );

  const scalpableCount = watchlist.filter((s) => s.includes('/')).length;

  return (
    <section className="panel scalp-panel" id="scalp">
      <header className="scalp-head">
        <div>
          <h3><Crosshair size={15} /> میز اسکلپ چندنمادی</h3>
          <p>
            ورود و خروج اسکلپی فقط روی جفت‌ارزها فعال است. هر نماد با مدل مخصوص خودش
            امتیازدهی می‌شود — آستانه، کیلزون، حداقل ADX، نسبت ریوارد و دقت قیمت برای هر جفت متفاوت است.
          </p>
        </div>
        <button type="button" className="scalp-run" onClick={runScan} disabled={scanning}>
          <RefreshCw size={13} className={scanning ? 'spin' : ''} />
          {scanning ? 'در حال اسکن…' : `اسکن ${watchlist.length} نماد`}
        </button>
      </header>

      {/* ── controls ─────────────────────────────────────────────── */}
      <div className="scalp-controls">
        <div className="scalp-field">
          <label htmlFor="scalp-tf">تایم‌فریم</label>
          <div className="scalp-tfs" id="scalp-tf">
            {TIMEFRAMES.map((tf) => (
              <button key={tf} type="button" className={tf === timeframe ? 'on' : ''} onClick={() => setTimeframe(tf)}>
                {tf}
              </button>
            ))}
          </div>
        </div>
        <div className="scalp-field">
          <label htmlFor="scalp-equity">موجودی حساب ($)</label>
          <input
            id="scalp-equity" type="number" min={1} step={10} value={equity}
            onChange={(e) => setEquity(Math.max(1, Number(e.target.value) || 1))}
          />
        </div>
        <div className="scalp-field">
          <label htmlFor="scalp-risk">ریسک هر معامله (٪)</label>
          <input
            id="scalp-risk" type="number" min={0.1} max={5} step={0.1} value={riskPct}
            onChange={(e) => setRiskPct(Math.min(5, Math.max(0.1, Number(e.target.value) || 0.5)))}
          />
        </div>
        <label className="scalp-check">
          <input type="checkbox" checked={onlySignals} onChange={(e) => setOnlySignals(e.target.checked)} />
          فقط نمادهای دارای سیگنال
        </label>
      </div>

      <div className="scalp-body">
        {/* ── universe picker ────────────────────────────────────── */}
        <aside className="scalp-universe">
          <div className="scalp-kinds">
            {(['forex', 'crypto', 'metal', 'all'] as const).map((k) => (
              <button key={k} type="button" className={k === kind ? 'on' : ''} onClick={() => setKind(k)}>
                {k === 'all' ? 'همه' : KIND_LABEL[k]}
              </button>
            ))}
          </div>
          <div className="scalp-search">
            <Search size={12} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="جستجوی نماد (EURUSD، BTC، XAU…)"
              aria-label="جستجوی نماد"
            />
          </div>

          {universeError && <p className="scalp-error"><AlertTriangle size={12} /> {universeError}</p>}
          {universe?.crypto_error && kind !== 'forex' && kind !== 'metal' && (
            <p className="scalp-warn"><AlertTriangle size={12} /> {universe.crypto_error}</p>
          )}
          {universe && !universe.forex_history_available && (
            <p className="scalp-warn"><AlertTriangle size={12} /> {universe.forex_history_note}</p>
          )}

          {universe && (
            <p className="scalp-count">
              {universe.returned} از {universe.total} نماد · انتخاب‌شده {watchlist.length}
              {scalpableCount !== watchlist.length && ` (${scalpableCount} قابل اسکلپ)`}
            </p>
          )}

          <ul className="scalp-list">
            {(universe?.instruments ?? []).map((item) => (
              <li key={item.symbol}>
                <button
                  type="button"
                  className={watchlist.includes(item.symbol) ? 'picked' : ''}
                  onClick={() => toggle(item.symbol)}
                >
                  <b>{item.display}</b>
                  <span className={`kind ${item.kind}`}>{KIND_LABEL[item.kind]}</span>
                  {item.scalp_enabled
                    ? <i className="ok">اسکلپ</i>
                    : <i className="no">فقط نمایش</i>}
                </button>
              </li>
            ))}
          </ul>
        </aside>

        {/* ── results ─────────────────────────────────────────────── */}
        <div className="scalp-results">
          {scanError && <p className="scalp-error"><AlertTriangle size={12} /> {scanError}</p>}
          {!scan && !scanError && <p className="scalp-empty">نمادها را انتخاب کنید و «اسکن» را بزنید.</p>}

          {scan && (
            <table className="scalp-table">
              <thead>
                <tr>
                  <th>نماد</th><th>وضعیت</th><th>امتیاز</th><th>ورود</th>
                  <th>حد ضرر</th><th>حد سود</th><th>R:R</th><th>اسپرد</th><th />
                </tr>
              </thead>
              <tbody>
                {scan.results.map((row) => {
                  const precision = row.spec?.price_precision ?? 5;
                  return (
                    <tr key={row.symbol} className={row.symbol === selected ? 'on' : ''}>
                      <td><b>{row.spec?.display ?? row.symbol}</b></td>
                      <td>
                        {row.ok
                          ? <ActionBadge action={row.signal?.action ?? 'NO_TRADE'} />
                          : <span className="scalp-badge err">خطا</span>}
                      </td>
                      <td className="num">
                        {row.ok
                          ? <span className={(row.signal?.confidence ?? 0) >= (row.signal?.threshold ?? 100) ? 'green' : ''}>
                              {row.signal?.confidence?.toFixed(0)} / {row.signal?.threshold}
                            </span>
                          : '—'}
                      </td>
                      <td className="num">{fmt(row.signal?.entry, precision)}</td>
                      <td className="num red">{fmt(row.signal?.stop_loss, precision)}</td>
                      <td className="num green">{fmt(row.signal?.take_profit, precision)}</td>
                      <td className="num">{row.signal?.risk_reward?.toFixed(2) ?? '—'}</td>
                      <td className="num">{row.context ? `${row.context.spread_pips} p` : '—'}</td>
                      <td>
                        <button
                          type="button"
                          className="scalp-link"
                          onClick={() => { setSelected(row.symbol); void loadDiagnosis(row.symbol); }}
                        >
                          جزئیات
                        </button>
                      </td>
                    </tr>
                  );
                })}
                {!scan.results.length && (
                  <tr><td colSpan={9} className="scalp-empty">هیچ نمادی با فیلتر فعلی سیگنال نداد.</td></tr>
                )}
              </tbody>
            </table>
          )}

          {/* ── detail ───────────────────────────────────────────── */}
          {detail && (
            <div className="scalp-detail">
              <h4>{detail.spec?.display ?? detail.symbol} · {detail.timeframe}</h4>

              {!detail.ok && <p className="scalp-error"><AlertTriangle size={12} /> {detail.error}</p>}
              {detail.policy_block && <p className="scalp-warn"><Ban size={12} /> {detail.policy_block}</p>}

              {detail.ok && detail.spec && (
                <div className="scalp-model">
                  <span>آستانهٔ {detail.timeframe}: <b>{detail.spec.model.thresholds[detail.timeframe]}</b></span>
                  <span>حداقل ADX: <b>{detail.spec.model.min_adx}</b></span>
                  <span>اسپرد معمول: <b>{detail.spec.model.typical_spread_pips} پیپ</b></span>
                  <span>سشن: <b>{detail.spec.model.session_24h ? '۲۴ساعته' : 'کیلزون لندن/نیویورک'}</b></span>
                  <span>دقت قیمت: <b>{detail.spec.price_precision} رقم</b></span>
                  <span>سقف اهرم مدل: <b>{detail.spec.model.max_leverage}x</b></span>
                </div>
              )}

              {detail.ok && (
                <div className="scalp-cols">
                  {/* entry */}
                  <div className="scalp-card">
                    <h5><LogIn size={13} /> پلن ورود</h5>
                    <dl>
                      <div><dt>وضعیت</dt><dd><ActionBadge action={detail.signal?.action ?? 'NO_TRADE'} /></dd></div>
                      <div><dt>قیمت ورود</dt><dd>{fmt(detail.signal?.entry, detail.spec?.price_precision ?? 5)}</dd></div>
                      <div><dt>تایید تایم بالاتر</dt><dd>{detail.context?.htf_bias ?? '—'}</dd></div>
                      <div><dt>رژیم نوسان</dt><dd>{detail.context?.volatility_regime ?? '—'}</dd></div>
                      <div><dt>کندل‌های واقعی</dt><dd>{detail.bars}</dd></div>
                    </dl>
                    {detail.sizing && (
                      <p className="scalp-size">
                        <Gauge size={11} /> حجم: <b>{detail.sizing.units}</b> {detail.sizing.unit_label}
                        {detail.sizing.lots !== null && <> · <b>{detail.sizing.lots}</b> لات</>}
                        {' '}· ریسک ${detail.sizing.risk_cash}
                      </p>
                    )}
                  </div>

                  {/* exit */}
                  <div className="scalp-card">
                    <h5><LogOut size={13} /> پلن خروج</h5>
                    {detail.exit_plan?.has_plan ? (
                      <>
                        <dl>
                          <div><dt><Target size={10} /> حد سود</dt><dd className="green">{fmt(detail.signal?.take_profit, detail.spec?.price_precision ?? 5)} ({detail.exit_plan.reward_pips} پیپ)</dd></div>
                          <div><dt>حد ضرر</dt><dd className="red">{fmt(detail.signal?.stop_loss, detail.spec?.price_precision ?? 5)} ({detail.exit_plan.risk_pips} پیپ)</dd></div>
                          <div><dt>بریک‌اون در</dt><dd>{fmt(detail.exit_plan.breakeven_trigger, detail.spec?.price_precision ?? 5)}</dd></div>
                          <div><dt>قفل نیم‌R در</dt><dd>{fmt(detail.exit_plan.lock_trigger, detail.spec?.price_precision ?? 5)}</dd></div>
                          <div><dt>تریل کیجون</dt><dd>{fmt(detail.exit_plan.kijun_trail, detail.spec?.price_precision ?? 5)}</dd></div>
                          <div><dt><Timer size={10} /> بستهٔ زمانی</dt><dd>{detail.exit_plan.time_stop_bars} کندل (~{detail.exit_plan.time_stop_minutes} دقیقه)</dd></div>
                        </dl>
                        <ul className="scalp-rules">
                          {detail.exit_plan.rules?.map((rule) => <li key={rule}>{rule}</li>)}
                        </ul>
                      </>
                    ) : (
                      <p className="scalp-empty">{detail.exit_plan?.note}</p>
                    )}
                  </div>
                </div>
              )}

              {/* confluence */}
              {!!detail.signal?.confluence?.length && (
                <div className="scalp-conf">
                  {detail.signal.confluence.map((item) => (
                    <span key={item.name} className={item.ok ? 'ok' : 'no'}>
                      {item.name} <i>{item.detail}</i>
                    </span>
                  ))}
                </div>
              )}

              {/* diagnosis */}
              <div className="scalp-card diag">
                <h5><Stethoscope size={13} /> چرا معامله نمی‌شود؟</h5>
                {diagLoading && <p className="scalp-empty">در حال بررسی دروازه‌ها…</p>}
                {diagnosis && (
                  <>
                    <p className="scalp-verdict">
                      نتیجه: <b>{diagnosis.verdict}</b> · امتیاز {diagnosis.score} از آستانهٔ {diagnosis.threshold}
                    </p>
                    <ul className="scalp-checks">
                      {diagnosis.checks.map((check) => (
                        <li key={check.name} className={check.passed ? 'ok' : 'no'}>
                          <b>{check.passed ? '✓' : '✕'} {check.name}</b>
                          <span>{check.measured}</span>
                          {!check.passed && check.fix && <em>{check.fix}</em>}
                        </li>
                      ))}
                    </ul>
                    {!!diagnosis.hard_gates.length && (
                      <div className="scalp-gates">
                        <b>وتوهای سخت:</b>
                        <ul>{diagnosis.hard_gates.map((g) => <li key={g}>{g}</li>)}</ul>
                      </div>
                    )}
                  </>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
