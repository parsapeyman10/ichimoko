import { useCallback, useEffect, useState } from 'react';
import {
  Activity, AlertTriangle, Bot, CircleDollarSign, Pause, Play,
  RotateCcw, ShieldCheck, Target, TrendingUp,
} from 'lucide-react';
import { apiGet, apiPost } from '../lib/api';

/**
 * Autonomous trader console.
 *
 * The loop runs server-side and trades on PAPER against real closed candles, charged the
 * venue's real spread and commission. It never reaches a broker — live submission stays
 * fail-closed in the backend's execution gate. This panel is deliberately explicit about
 * that, and about the fact that a small closed-trade count cannot establish an edge.
 */

type Position = {
  id: string;
  symbol: string;
  display: string;
  side: 'BUY' | 'SELL';
  qty: number;
  unit_label: string;
  entry: number;
  stop_loss: number;
  take_profit: number;
  confidence: number;
  risk_pct: number;
  bars_held: number;
  unrealised: number;
};

type ClosedTrade = {
  symbol: string;
  side: string;
  entry: number;
  exit: number;
  net_pnl: number;
  fees: number;
  r_multiple: number | null;
  exit_reason: string;
  closed_at: string;
};

type LogLine = { at: string; kind: string; message: string };

type Performance = {
  starting_balance: number;
  balance: number;
  target_balance: number | null;
  return_pct: number;
  progress_to_target_pct: number | null;
  trades: number;
  wins: number;
  losses: number;
  win_rate_pct: number | null;
  profit_factor: number | null;
  avg_r: number | null;
  max_drawdown_pct: number;
  consecutive_losses: number;
  open_positions: number;
  cycles: number;
  confidence_note: string;
};

type State = {
  running: boolean;
  enabled: boolean;
  interval_seconds: number;
  balance: number;
  starting_balance: number;
  target_balance: number | null;
  timeframe: string;
  profile: string;
  watchlist: string[];
  positions: Position[];
  closed: ClosedTrade[];
  log: LogLine[];
  performance: Performance;
  execution_mode: string;
  execution_note: string;
};

type GrowthPlan = {
  start: number;
  target: number;
  multiple: number;
  edge: {
    win_rate_pct: number;
    net_rr: number;
    risk_pct: number;
    expectancy_r: number;
    expectancy_pct_per_trade: number;
    breakeven_win_rate_pct: number | null;
    kelly_pct: number;
    half_kelly_pct: number;
    measured_over_trades: number;
  };
  path: { trades_needed: number | null; trading_days_needed: number | null; months_needed: number | null };
  simulation: {
    reached_target_pct: number;
    ruin_pct: number;
    median_trades_to_target: number | null;
    p95_max_drawdown_pct: number;
    worst_losing_streak: number;
    prob_edge_is_actually_negative_pct: number;
    win_rate_p05: number;
    win_rate_p95: number;
  };
  warnings: string[];
  verdict: string;
  disclaimer: string;
};

const KIND_ICON: Record<string, string> = {
  entry: '▲', exit: '■', trail: '↗', gate: '⛔', warn: '!', day: '◴',
};

export default function AutopilotPanel() {
  const [state, setState] = useState<State | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [plan, setPlan] = useState<GrowthPlan | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [startBalance, setStartBalance] = useState(100);
  const [targetBalance, setTargetBalance] = useState(1000);

  const load = useCallback(async () => {
    const result = await apiGet<State>('/api/v1/autopilot/state');
    if (result.ok) { setState(result.data); setError(null); }
    else { setError(result.error); }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(load, 15_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const act = useCallback(async (path: string) => {
    setBusy(true);
    await apiPost(path, {});
    await load();
    setBusy(false);
  }, [load]);

  /** Plan from the autopilot's OWN measured results when it has enough of them. */
  const loadPlan = useCallback(async () => {
    const perf = state?.performance;
    // Use the autopilot's own measured results once there are enough of them; otherwise
    // fall back to a placeholder edge and a small sample size, so the planner's own
    // uncertainty modelling makes the weakness of the evidence obvious.
    const measured = perf && perf.trades >= 20 && perf.win_rate_pct != null ? perf : null;
    const winRate = measured ? (measured.win_rate_pct ?? 50) / 100 : 0.5;
    const sample = measured ? measured.trades : 30;
    const netRr = measured?.avg_r ? Math.max(0.3, Math.abs(measured.avg_r) + 1) : 1.45;
    const params = new URLSearchParams({
      start: String(startBalance),
      target: String(targetBalance),
      win_rate: String(winRate.toFixed(4)),
      net_rr: String(netRr.toFixed(2)),
      risk_pct: '0.5',
      trades_per_day: '3',
      sample_size: String(sample),
    });
    const result = await apiGet<GrowthPlan>(`/api/v1/plan/growth?${params}`);
    if (result.ok) { setPlan(result.data); setPlanError(null); }
    else { setPlan(null); setPlanError(result.error); }
  }, [state, startBalance, targetBalance]);

  const perf = state?.performance;
  const progress = Math.max(0, Math.min(100, perf?.progress_to_target_pct ?? 0));

  return (
    <section className="panel autopilot" id="autopilot">
      <header className="scalp-head">
        <div>
          <h3><Bot size={15} /> تریدر خودکار</h3>
          <p>
            موتور خودش بازار را رصد می‌کند، دروازه‌های خبر و ریسک را چک می‌کند، حجم را حساب
            می‌کند و معامله باز و بسته می‌کند. <b>معاملات کاغذی هستند</b> ولی روی کندل‌های واقعی
            و با کسر اسپرد و کارمزد واقعی اجرا می‌شوند.
          </p>
        </div>
        <div className="ap-actions">
          {state?.running
            ? <button type="button" className="ap-stop" disabled={busy} onClick={() => act('/api/v1/autopilot/stop')}><Pause size={13} /> توقف</button>
            : <button type="button" className="scalp-run" disabled={busy} onClick={() => act('/api/v1/autopilot/start')}><Play size={13} /> شروع</button>}
          <button type="button" className="ap-ghost" disabled={busy} onClick={() => act('/api/v1/autopilot/cycle')}><Activity size={12} /> یک سیکل</button>
          <button type="button" className="ap-ghost" disabled={busy} onClick={() => act(`/api/v1/autopilot/reset?balance=${startBalance}&target=${targetBalance}`)}><RotateCcw size={12} /> ریست</button>
        </div>
      </header>

      {error && <p className="scalp-error"><AlertTriangle size={12} /> {error}</p>}

      {state && (
        <>
          <p className="ap-mode">
            <ShieldCheck size={12} /> حالت اجرا: <b>کاغذی (paper)</b> — {state.execution_note}
          </p>

          {/* ── progress to target ── */}
          <div className="ap-progress">
            <div className="ap-progress-head">
              <span><CircleDollarSign size={12} /> موجودی <b>${perf?.balance.toLocaleString()}</b></span>
              <span><Target size={12} /> هدف <b>${state.target_balance?.toLocaleString()}</b></span>
              <span className={(perf?.return_pct ?? 0) >= 0 ? 'green' : 'red'}>
                <TrendingUp size={12} /> {perf?.return_pct}%
              </span>
            </div>
            <div className="ap-bar"><i style={{ width: `${progress}%` }} /></div>
            <small>{progress.toFixed(1)}% از مسیر رسیدن به هدف</small>
          </div>

          {/* ── measured performance ── */}
          {perf && (
            <>
              <div className="scalp-numbers">
                <div><span>معاملات بسته</span><b>{perf.trades}</b></div>
                <div><span>برد / باخت</span><b>{perf.wins} / {perf.losses}</b></div>
                <div><span>وین‌ریت</span><b>{perf.win_rate_pct ?? '—'}%</b></div>
                <div><span>فاکتور سود</span><b>{perf.profit_factor ?? '—'}</b></div>
                <div><span>میانگین R</span><b>{perf.avg_r ?? '—'}</b></div>
                <div><span>حداکثر افت</span><b className="red">{perf.max_drawdown_pct}%</b></div>
                <div><span>باخت پیاپی</span><b>{perf.consecutive_losses}</b></div>
                <div><span>سیکل‌های اجرا</span><b>{perf.cycles}</b></div>
              </div>
              <p className="scalp-note"><AlertTriangle size={11} /> {perf.confidence_note}</p>
            </>
          )}

          {/* ── open positions ── */}
          <h5 className="ap-h5">پوزیشن‌های باز ({state.positions.length})</h5>
          {state.positions.length === 0
            ? <p className="scalp-empty">پوزیشن بازی وجود ندارد.</p>
            : (
              <table className="scalp-table">
                <thead><tr><th>نماد</th><th>جهت</th><th>حجم</th><th>ورود</th><th>حد ضرر</th><th>حد سود</th><th>ریسک</th><th>کندل</th><th>شناور</th></tr></thead>
                <tbody>
                  {state.positions.map((p) => (
                    <tr key={p.id}>
                      <td><b>{p.display}</b></td>
                      <td><span className={`scalp-badge ${p.side === 'BUY' ? 'buy' : 'sell'}`}>{p.side === 'BUY' ? 'خرید' : 'فروش'}</span></td>
                      <td className="num">{p.qty}</td>
                      <td className="num">{p.entry}</td>
                      <td className="num red">{p.stop_loss}</td>
                      <td className="num green">{p.take_profit}</td>
                      <td className="num">{p.risk_pct}%</td>
                      <td className="num">{p.bars_held}</td>
                      <td className={`num ${p.unrealised >= 0 ? 'green' : 'red'}`}>{p.unrealised >= 0 ? '+' : ''}{p.unrealised}$</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

          {/* ── recent closed ── */}
          {state.closed.length > 0 && (
            <>
              <h5 className="ap-h5">آخرین معاملات بسته</h5>
              <table className="scalp-table">
                <thead><tr><th>نماد</th><th>جهت</th><th>ورود</th><th>خروج</th><th>کارمزد</th><th>سود/ضرر</th><th>R</th><th>دلیل خروج</th></tr></thead>
                <tbody>
                  {[...state.closed].reverse().slice(0, 12).map((t) => (
                    <tr key={`${t.closed_at}-${t.symbol}`}>
                      <td><b>{t.symbol}</b></td>
                      <td>{t.side === 'BUY' ? 'خرید' : 'فروش'}</td>
                      <td className="num">{t.entry}</td>
                      <td className="num">{t.exit}</td>
                      <td className="num red">-{t.fees}$</td>
                      <td className={`num ${t.net_pnl >= 0 ? 'green' : 'red'}`}>{t.net_pnl >= 0 ? '+' : ''}{t.net_pnl}$</td>
                      <td className="num">{t.r_multiple ?? '—'}</td>
                      <td className="ap-reason">{t.exit_reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}

          {/* ── audit log ── */}
          <h5 className="ap-h5">گزارش تصمیم‌ها</h5>
          <ul className="ap-log">
            {[...state.log].reverse().slice(0, 25).map((line) => (
              <li key={`${line.at}-${line.message}`} className={line.kind}>
                <i>{KIND_ICON[line.kind] ?? '·'}</i>
                <time>{new Date(line.at).toLocaleTimeString('fa-IR')}</time>
                <span>{line.message}</span>
              </li>
            ))}
            {state.log.length === 0 && <li className="empty">هنوز تصمیمی ثبت نشده — «یک سیکل» را بزنید.</li>}
          </ul>

          {/* ── growth plan ── */}
          <div className="scalp-card ap-plan">
            <h5><Target size={13} /> مسیر رسیدن به هدف — حساب واقعی، نه آرزو</h5>
            <div className="scalp-controls" style={{ padding: 0, border: 0, background: 'none' }}>
              <div className="scalp-field">
                <label htmlFor="ap-start">سرمایهٔ شروع ($)</label>
                <input id="ap-start" type="number" min={10} step={10} value={startBalance}
                  onChange={(e) => setStartBalance(Math.max(10, Number(e.target.value) || 100))} />
              </div>
              <div className="scalp-field">
                <label htmlFor="ap-target">هدف ($)</label>
                <input id="ap-target" type="number" min={20} step={100} value={targetBalance}
                  onChange={(e) => setTargetBalance(Math.max(20, Number(e.target.value) || 1000))} />
              </div>
              <button type="button" className="ap-ghost" onClick={loadPlan}>محاسبه</button>
            </div>

            {planError && <p className="scalp-error"><AlertTriangle size={12} /> {planError}</p>}

            {plan && (
              <>
                <p className="scalp-verdict">{plan.verdict}</p>
                <div className="scalp-numbers">
                  <div><span>انتظار هر معامله</span><b>{plan.edge.expectancy_pct_per_trade}%</b></div>
                  <div><span>وین‌ریت سربه‌سر</span><b>{plan.edge.breakeven_win_rate_pct}%</b></div>
                  <div><span>معاملات لازم</span><b>{plan.path.trades_needed ?? '—'}</b></div>
                  <div><span>زمان لازم</span><b>{plan.path.months_needed ?? '—'} ماه</b></div>
                  <div><span>رسیدن به هدف</span><b className="green">{plan.simulation.reached_target_pct}%</b></div>
                  <div><span>نصف‌شدن سرمایه</span><b className="red">{plan.simulation.ruin_pct}%</b></div>
                  <div><span>بدترین افت (p95)</span><b className="red">{plan.simulation.p95_max_drawdown_pct}%</b></div>
                  <div><span>بدترین باخت پیاپی</span><b>{plan.simulation.worst_losing_streak}</b></div>
                  <div><span>کِلی / نصف کِلی</span><b>{plan.edge.kelly_pct}% / {plan.edge.half_kelly_pct}%</b></div>
                  <div><span>احتمال منفی‌بودن لبه</span><b className="red">{plan.simulation.prob_edge_is_actually_negative_pct}%</b></div>
                </div>
                {plan.warnings.map((w) => (
                  <p key={w} className="scalp-warn"><AlertTriangle size={12} /> {w}</p>
                ))}
                <p className="scalp-note">{plan.disclaimer}</p>
              </>
            )}
          </div>
        </>
      )}
    </section>
  );
}
