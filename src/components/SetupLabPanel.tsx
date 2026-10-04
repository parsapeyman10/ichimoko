import { useCallback, useState } from 'react';
import { AlertTriangle, BookOpenCheck, FlaskConical, Sigma } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Setup laboratory.
 *
 * Measures every Ichimoku opportunity on real candles and reports, per setup and context,
 * the win rate AGAINST the no-edge baseline 1/(1+RR). Raw win rate is shown but never
 * used to decide anything — the Wilson lower bound and the out-of-sample slice do that.
 */

type Row = {
  key: string; setup: string; direction: string; filter: string;
  samples: number; win_rate: number; win_rate_lower: number;
  expectancy_r: number; edge_points: number; oos_edge_points: number | null;
};

type Playbook = {
  symbol: string;
  candles: number;
  independent_samples: number;
  hypotheses_tested: number;
  config: { rr: number; stop_atr: number; max_bars: number; cost_r: number };
  target_win_rate_pct: number;
  baseline_win_rate_pct: number;
  breakeven_win_rate_pct: number;
  accepted: Row[];
  in_sample_only: Row[];
  rejected_count: number;
  target_explanation: string;
  verdict: string;
};

type CurveRow = {
  rr: number; random_baseline_pct: number; win_rate: number; win_rate_lower: number;
  edge_points: number; expectancy_r: number; samples: number; resolved: number;
  timeout_pct: number; profitable: boolean;
};

type Curve = { symbol: string; setup: string; rows: CurveRow[]; rr_for_65pct_random: number; verdict: string };

const TIMEFRAMES = ['1m', '3m', '5m', '15m', '1h'] as const;

export default function SetupLabPanel() {
  const [symbol, setSymbol] = useState('BTCUSDT');
  const [timeframe, setTimeframe] = useState<(typeof TIMEFRAMES)[number]>('5m');
  const [rr, setRr] = useState(1.5);
  const [bars, setBars] = useState(3000);
  const [book, setBook] = useState<Playbook | null>(null);
  const [curve, setCurve] = useState<Curve | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = useCallback(async () => {
    setBusy(true); setError(null);
    const base = `symbol=${encodeURIComponent(symbol)}&timeframe=${timeframe}&bars=${bars}`;
    const [pb, cv] = await Promise.all([
      apiGet<Playbook>(`/api/v1/lab/playbook?${base}&rr=${rr}`),
      apiGet<Curve>(`/api/v1/lab/win-rate-curve?${base}`),
    ]);
    if (pb.ok) setBook(pb.data); else { setBook(null); setError(pb.error); }
    if (cv.ok) setCurve(cv.data); else setCurve(null);
    setBusy(false);
  }, [symbol, timeframe, rr, bars]);

  const table = (rows: Row[], tone: 'ok' | 'warn') => (
    <table className="scalp-table">
      <thead>
        <tr><th>ستاپ</th><th>جهت</th><th>شرط</th><th>نمونه</th><th>وین‌ریت</th><th>حد پایین</th><th>لبه</th><th>لبهٔ خارج نمونه</th><th>E[R]</th></tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.key}>
            <td><b>{r.setup}</b></td>
            <td>{r.direction === 'BUY' ? 'خرید' : 'فروش'}</td>
            <td className="ap-reason">{r.filter}</td>
            <td className="num">{r.samples}</td>
            <td className="num">{r.win_rate}%</td>
            <td className={`num ${tone === 'ok' ? 'green' : ''}`}>{r.win_rate_lower}%</td>
            <td className={`num ${r.edge_points > 0 ? 'green' : 'red'}`}>{r.edge_points > 0 ? '+' : ''}{r.edge_points}</td>
            <td className="num">{r.oos_edge_points ?? '—'}</td>
            <td className={`num ${r.expectancy_r > 0 ? 'green' : 'red'}`}>{r.expectancy_r}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );

  return (
    <section className="panel" id="setup-lab">
      <header className="scalp-head">
        <div>
          <h3><FlaskConical size={15} /> آزمایشگاه ستاپ‌ها</h3>
          <p>
            همهٔ فرصت‌هایی که ایچیموکو می‌دهد روی کندل واقعی اندازه‌گیری می‌شوند: کراس تنکان/کیجون،
            شکست کومو، برگشت از کیجون و تنکان، تاب کومو، و هم‌راستایی کامل.
            معیار، وین‌ریت خام نیست — <b>اختلاف با خط پایهٔ تصادفی همان RR</b> است.
          </p>
        </div>
        <button type="button" className="scalp-run" disabled={busy} onClick={run}>
          <Sigma size={13} /> {busy ? 'در حال محاسبه…' : 'اندازه‌گیری'}
        </button>
      </header>

      <div className="scalp-controls">
        <div className="scalp-field">
          <label htmlFor="lab-symbol">نماد</label>
          <input id="lab-symbol" value={symbol} onChange={(e) => setSymbol(e.target.value.toUpperCase())} />
        </div>
        <div className="scalp-field">
          <label htmlFor="lab-tf">تایم‌فریم</label>
          <div className="scalp-tfs" id="lab-tf">
            {TIMEFRAMES.map((tf) => (
              <button key={tf} type="button" className={tf === timeframe ? 'on' : ''} onClick={() => setTimeframe(tf)}>{tf}</button>
            ))}
          </div>
        </div>
        <div className="scalp-field">
          <label htmlFor="lab-rr">RR هدف</label>
          <input id="lab-rr" type="number" min={0.2} max={5} step={0.1} value={rr}
            onChange={(e) => setRr(Math.max(0.2, Number(e.target.value) || 1.5))} />
        </div>
        <div className="scalp-field">
          <label htmlFor="lab-bars">تعداد کندل</label>
          <input id="lab-bars" type="number" min={600} max={5000} step={500} value={bars}
            onChange={(e) => setBars(Math.max(600, Number(e.target.value) || 3000))} />
        </div>
      </div>

      <div className="scalp-results">
        {error && <p className="scalp-error"><AlertTriangle size={12} /> {error}</p>}
        {!book && !error && <p className="scalp-empty">نماد را انتخاب کنید و «اندازه‌گیری» را بزنید.</p>}

        {book && (
          <>
            <p className="scalp-verdict">{book.verdict}</p>
            <div className="scalp-numbers">
              <div><span>کندل بررسی‌شده</span><b>{book.candles}</b></div>
              <div><span>نمونهٔ مستقل</span><b>{book.independent_samples}</b></div>
              <div><span>ترکیب آزمایش‌شده</span><b>{book.hypotheses_tested}</b></div>
              <div><span>خط پایهٔ تصادفی</span><b>{book.baseline_win_rate_pct}%</b></div>
              <div><span>آستانهٔ سربه‌سر</span><b>{book.breakeven_win_rate_pct}%</b></div>
              <div><span>قوانین قبول‌شده</span><b className={book.accepted.length ? 'green' : 'red'}>{book.accepted.length}</b></div>
            </div>
            <p className="scalp-warn"><AlertTriangle size={12} /> {book.target_explanation}</p>

            <h5 className="ap-h5"><BookOpenCheck size={12} /> قوانین قبول‌شده (آماری + خارج از نمونه)</h5>
            {book.accepted.length ? table(book.accepted, 'ok')
              : <p className="scalp-empty">هیچ قانونی هر دو فیلتر را پاس نکرد.</p>}

            {book.in_sample_only.length > 0 && (
              <>
                <h5 className="ap-h5">فقط داخل نمونه — احتمالاً برازش بیش از حد</h5>
                <p className="scalp-warn">
                  <AlertTriangle size={12} />
                  این {book.in_sample_only.length} مورد اگر فقط به وین‌ریت خام نگاه می‌کردیم «خوب» به نظر
                  می‌رسیدند، ولی یا حد پایین اطمینان را رد نکردند یا خارج از نمونه دوام نیاوردند.
                </p>
                {table(book.in_sample_only, 'warn')}
              </>
            )}
          </>
        )}

        {curve && curve.rows.length > 0 && (
          <>
            <h5 className="ap-h5">وین‌ریت در برابر RR — چرا ۶۵٪ به‌تنهایی معنا ندارد</h5>
            <table className="scalp-table">
              <thead>
                <tr><th>RR</th><th>خط پایهٔ تصادفی</th><th>وین‌ریت اندازه‌گیری‌شده</th><th>حد پایین</th><th>لبه</th><th>تایم‌اوت</th><th>E[R]</th><th>سودده؟</th></tr>
              </thead>
              <tbody>
                {curve.rows.map((r) => (
                  <tr key={r.rr}>
                    <td className="num"><b>{r.rr}</b></td>
                    <td className="num">{r.random_baseline_pct}%</td>
                    <td className="num">{r.win_rate}%</td>
                    <td className="num">{r.win_rate_lower}%</td>
                    <td className={`num ${r.edge_points > 0 ? 'green' : 'red'}`}>{r.edge_points > 0 ? '+' : ''}{r.edge_points}</td>
                    <td className="num">{r.timeout_pct}%</td>
                    <td className={`num ${r.expectancy_r > 0 ? 'green' : 'red'}`}>{r.expectancy_r}</td>
                    <td>{r.profitable ? <span className="scalp-badge buy">بله</span> : <span className="scalp-badge flat">خیر</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="scalp-note">{curve.verdict}</p>
            <p className="scalp-note">
              یک سیستم کاملاً تصادفی با RR={curve.rr_for_65pct_random} دقیقاً ۶۵٪ وین‌ریت نشان می‌دهد و
              انتظار ریاضی‌اش صفر است. پس ستون «لبه» تنها ستونی است که ارزش دارد.
            </p>
          </>
        )}
      </div>
    </section>
  );
}
