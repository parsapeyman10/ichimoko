import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Bell, BellOff, Flame, Radar } from 'lucide-react';
import { apiGet } from '../lib/api';

/**
 * Real, evidence-based meme/small-cap "early momentum" screen — NOT a pump prediction or an
 * order engine. Every candidate and every reason on it comes straight from the backend's
 * `CryptoScanner`, which cross-checks CoinGecko against Binance Spot (real order book, real
 * closed 1h candles) before a coin is shown here. `entry_reference_price` and
 * `stop_reference_price` are real, live market levels (best ask / recent real candle low) — not
 * advice, not a guarantee, and this panel never invents a coin or a level on its own.
 */
type Candidate = {
  id: string;
  name: string;
  symbol: string;
  price_usd: number;
  market_cap_usd: number;
  volume_24h_usd: number;
  change_1h_pct: number;
  change_24h_pct: number;
  change_7d_pct: number;
  change_3h_pct: number;
  volume_ratio_3h: number;
  taker_buy_ratio_3h: number;
  spread_pct: number;
  entry_reference_price?: number;
  stop_reference_price?: number;
  reasons?: string[];
  link: string;
};

type Snapshot = {
  status: { state: string; provider: string; error: string | null; last_success_at: string | null; cached: boolean };
  checked_at: string;
  scanned: number;
  preselected: number;
  filters: Record<string, string>;
  candidates: Candidate[];
  note: string;
};

const REFRESH_MS = 45_000; // the backend itself only re-scans every 120s; polling faster just re-reads its cache
const ALARM_KEY = 'aurum.pumpScanner.alarm';
const ALARM_OPTIONS = [
  { id: 'off', label: 'بدون صدا (فقط نمایش)' },
  { id: 'chime', label: 'زنگ ساده' },
  { id: 'double', label: 'دو بوق' },
  { id: 'siren', label: 'آژیر' },
] as const;
type AlarmId = typeof ALARM_OPTIONS[number]['id'];

/** Synthesized in-browser — no external audio file needed, and each option is audibly distinct. */
function playAlarm(kind: AlarmId) {
  if (kind === 'off') return;
  try {
    const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const beep = (freq: number, start: number, duration: number) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = freq;
      osc.type = 'sine';
      gain.gain.setValueAtTime(0.0001, ctx.currentTime + start);
      gain.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + start + duration);
      osc.connect(gain).connect(ctx.destination);
      osc.start(ctx.currentTime + start);
      osc.stop(ctx.currentTime + start + duration + 0.05);
    };
    if (kind === 'chime') beep(880, 0, 0.35);
    else if (kind === 'double') { beep(740, 0, 0.15); beep(740, 0.22, 0.15); }
    else if (kind === 'siren') { beep(600, 0, 0.2); beep(950, 0.22, 0.2); beep(600, 0.44, 0.2); beep(950, 0.66, 0.2); }
    window.setTimeout(() => void ctx.close(), 1500);
  } catch {
    // Audio is a courtesy, never a requirement — a failure here must not break the panel.
  }
}

const stat = (label: string, value: string, tone?: string) => (
  <div key={label} style={{ background: '#11151b', borderRadius: 5, padding: '5px 7px', textAlign: 'center', minWidth: 62 }}>
    <span style={{ display: 'block', color: '#6b7280', font: '7px DM Mono' }}>{label}</span>
    <b style={{ display: 'block', color: tone ?? '#e6e9ee', font: '700 10.5px DM Mono', marginTop: 2 }}>{value}</b>
  </div>
);

export default function CryptoPumpScanner() {
  const [state, setState] = useState<{ loading: boolean; error?: string; data?: Snapshot }>({ loading: true });
  const [alarm, setAlarm] = useState<AlarmId>(() => {
    const saved = typeof localStorage !== 'undefined' ? localStorage.getItem(ALARM_KEY) : null;
    return (ALARM_OPTIONS.find((option) => option.id === saved)?.id ?? 'chime');
  });
  const seenIds = useRef<Set<string> | null>(null); // null = first load; don't alarm on the initial batch

  useEffect(() => { localStorage.setItem(ALARM_KEY, alarm); }, [alarm]);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<Snapshot>('/api/v1/crypto/candidates');
      if (!alive) return;
      if (!result.ok) { setState({ loading: false, error: result.error }); return; }
      const data = result.data;
      const freshIds = new Set(data.candidates.map((c) => c.id));
      if (seenIds.current) {
        const isNew = [...freshIds].some((id) => !seenIds.current!.has(id));
        if (isNew) playAlarm(alarm);
      }
      seenIds.current = freshIds;
      setState({ loading: false, data });
    };
    void load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => { alive = false; window.clearInterval(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [alarm]);

  const data = state.data;

  return (
    <section className="panel" id="pump-scanner" style={{ gridColumn: '1 / -1', overflow: 'hidden', background: 'linear-gradient(180deg, #0f1418 0, #0c0f14 100%)' }}>
      <div className="panel-heading wide">
        <div>
          <span className="eyebrow"><Radar size={12}/> غربالگری شواهد-محور کوین‌های کوچک/میم‌کوین · CoinGecko + Binance Spot</span>
          <h2>کوین‌هایی که در حال شکل‌گیری مومنتوم اولیه‌اند</h2>
          <small style={{ color: '#6b7280', fontSize: 9 }}>
            پیش‌بینی پامپ یا تضمین سود نیست — فقط کوین‌هایی که هم‌زمان از دو منبع مستقل (CoinGecko و بازار Spot بایننس) شرایط سخت‌گیرانهٔ حجم/مومنتوم/نقدشوندگی را رد کرده‌اند، با دلیل واقعی هرکدام.
          </small>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, font: '8px DM Mono', color: '#6b7280' }}>
            {alarm === 'off' ? <BellOff size={13}/> : <Bell size={13}/>} آلارم کاندید جدید
            <select value={alarm} onChange={(event) => setAlarm(event.target.value as AlarmId)}
                    style={{ background: '#11151b', color: '#d5d9e0', border: '1px solid #1f2630', borderRadius: 5, padding: '4px 6px', font: '9px DM Mono' }}>
              {ALARM_OPTIONS.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
            </select>
          </label>
          <button type="button" className="text-button" onClick={() => playAlarm(alarm)} style={{ font: '700 8px DM Mono' }}>تست صدا</button>
        </div>
      </div>

      {data && (
        <div style={{ padding: '8px 14px', display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', borderBottom: '1px solid var(--line)', font: '8px DM Mono', color: '#6b7280' }}>
          <span>وضعیت: <b style={{ color: data.status.state === 'online' ? 'var(--green)' : 'var(--red)' }}>{data.status.state === 'online' ? 'آنلاین' : 'در دسترس نیست'}</b></span>
          <span>منبع: {data.status.provider}</span>
          <span>بررسی‌شده: {data.scanned} · پیش‌فیلتر: {data.preselected} · نامزد نهایی: {data.candidates.length}</span>
          {data.status.last_success_at && <span>آخرین اسکن موفق: {new Date(data.status.last_success_at).toLocaleTimeString('fa-IR')}</span>}
        </div>
      )}

      {state.error && (
        <div style={{ padding: '14px 16px', color: '#e6a244', fontSize: 11, display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }}/>
          <span>{state.error}</span>
        </div>
      )}

      {data?.status.error && (
        <div style={{ padding: '10px 16px', color: '#e6a244', fontSize: 10.5, display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <AlertTriangle size={14} style={{ flexShrink: 0, marginTop: 2 }}/>
          <span>{data.status.error}</span>
        </div>
      )}

      {data && data.candidates.length === 0 && !data.status.error && (
        <div style={{ padding: '18px 16px', color: '#6b7280', fontSize: 10.5, textAlign: 'center' }}>
          الان هیچ کوینی هر دو شرط CoinGecko و بایننس را هم‌زمان پاس نکرده — نتیجهٔ خالی معتبر است، نه خطا.
        </div>
      )}

      {data && data.candidates.length > 0 && (
        <div style={{ display: 'grid', gap: 10, padding: '12px 14px' }}>
          {data.candidates.map((candidate) => (
            <div key={candidate.id} style={{ background: '#11151b', border: '1px solid #1f2630', borderRadius: 8, padding: '10px 12px', display: 'grid', gap: 8 }}>
              <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8, alignItems: 'center' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <Flame size={14} style={{ color: 'var(--gold)' }}/>
                  <a href={candidate.link} target="_blank" rel="noreferrer" style={{ color: '#e6e9ee', fontWeight: 700, fontSize: 12, textDecoration: 'none' }}>
                    {candidate.name} · {candidate.symbol}
                  </a>
                </div>
                <span style={{ font: '700 12px DM Mono', color: '#e6e9ee' }}>${candidate.price_usd}</span>
              </div>

              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {stat('۱ساعته', `${candidate.change_1h_pct > 0 ? '+' : ''}${candidate.change_1h_pct}%`, candidate.change_1h_pct >= 0 ? 'var(--green)' : 'var(--red)')}
                {stat('۳ساعته', `${candidate.change_3h_pct > 0 ? '+' : ''}${candidate.change_3h_pct}%`, candidate.change_3h_pct >= 0 ? 'var(--green)' : 'var(--red)')}
                {stat('۲۴ساعته', `${candidate.change_24h_pct > 0 ? '+' : ''}${candidate.change_24h_pct}%`, candidate.change_24h_pct >= 0 ? 'var(--green)' : 'var(--red)')}
                {stat('نسبت حجم۳س', `${candidate.volume_ratio_3h}×`)}
                {stat('سهم خرید', `${(candidate.taker_buy_ratio_3h * 100).toFixed(0)}%`)}
                {stat('اسپرد', `${candidate.spread_pct}%`)}
              </div>

              {(candidate.entry_reference_price || candidate.stop_reference_price) && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, font: '8.5px DM Mono', color: '#9aa3af', background: '#0c0f14', borderRadius: 6, padding: '6px 8px' }}>
                  {candidate.entry_reference_price && <span>مرجع ورود (Ask لحظه‌ای): <b style={{ color: '#7af0c8' }}>${candidate.entry_reference_price}</b></span>}
                  {candidate.stop_reference_price && <span>مرجع استاپ (کف ۳ کندل بستهٔ اخیر): <b style={{ color: '#f0847a' }}>${candidate.stop_reference_price}</b></span>}
                </div>
              )}

              {candidate.reasons && candidate.reasons.length > 0 && (
                <ul style={{ margin: 0, paddingRight: 16, display: 'grid', gap: 3 }}>
                  {candidate.reasons.map((reason) => (
                    <li key={reason} style={{ font: '9px Manrope', color: '#c9b896' }}>{reason}</li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </div>
      )}

      {data?.note && <div style={{ padding: '8px 14px', borderTop: '1px solid var(--line)', fontSize: 9, color: '#5a6b65' }}>{data.note}</div>}
    </section>
  );
}
