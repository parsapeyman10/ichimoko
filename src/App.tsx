import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity, AlertTriangle, BarChart3, Bell, BookOpen, Brain, ChevronDown, CircleDollarSign,
  Clock3, Flame, Gauge, HelpCircle, History, Layers, LayoutDashboard, LogOut, Menu, Newspaper,
  Rss, Search, Settings, ShieldCheck, Signal, Sparkles,
  TrendingDown, TrendingUp, Users, WalletCards, WifiOff, X, Zap
} from 'lucide-react';
import TradingChart from './components/TradingChart';
import BacktestPanel from './components/BacktestPanel';
import PredictionPanel from './components/PredictionPanel';
import TopTradersPanel from './components/TopTradersPanel';
import MTFPanel from './components/MTFPanel';
import CryptoPumpScanner from './components/CryptoPumpScanner';
import { apiGet, apiPost, barIsCurrent, toBackendCandles, type DataStatus } from './lib/api';
import { useMarketFeed } from './lib/feed';
import type { Candle } from './lib/market';

type Timeframe = '1m' | '5m' | '15m' | '1h';

type ConfluenceItem = { name: string; ok: boolean; detail: string };
type LiveSignal = {
  action: 'BUY' | 'SELL' | 'NO_TRADE';
  confidence: number;
  entry?: number | null;
  stop_loss?: number | null;
  take_profit?: number | null;
  risk_reward?: number | null;
  reasons: string[];
  blockers: string[];
  confluence: ConfluenceItem[];
  exit_hint?: string;
  expires_after_seconds?: number;
};

const emptySignal: LiveSignal = {
  action: 'NO_TRADE',
  confidence: 0,
  reasons: [],
  blockers: ['هنوز دیتای واقعی کافی برای ارزیابی نرسیده است'],
  confluence: [],
};

function Brand() {
  return <div className="brand"><div className="brand-mark"><span>A</span></div><div><b>AURUM</b><small>EDGE</small></div></div>;
}

function Sidebar({ active, setActive, open, close, feedState, provider }: { active: string; setActive: (s: string) => void; open: boolean; close: () => void; feedState: string; provider: string }) {
  const providerLabel = provider === 'twelve_data' ? 'Twelve Data' : provider === 'spot_fallback' ? 'فید رایگان خودکار' : '—';
  const nav = [
    { key: 'terminal', label: 'ترمینال معاملات', sub: 'Trading terminal', icon: LayoutDashboard },
    { key: 'signals', label: 'موتور سیگنال', sub: 'Signal engine', icon: Signal },
    { key: 'predict', label: 'پیش‌بینی رفتار', sub: 'Behaviour rules', icon: Brain },
    { key: 'mtf', label: 'تراز چندتایم‌فریم', sub: 'MTF 1m→1h', icon: Layers },
    { key: 'traders', label: 'اجماع سبک‌ها', sub: 'Rule ensemble', icon: Users },
    { key: 'backtest', label: 'بک‌تست دیتای واقعی', sub: 'Real-candle replay', icon: BarChart3 },
    { key: 'confluence', label: 'روش‌های مکمل', sub: 'Confluence', icon: Layers },
    { key: 'pump-scanner', label: 'اسکن میم‌کوین / پامپ', sub: 'Crypto momentum scan', icon: Flame },
    { key: 'news', label: 'هوش خبری', sub: 'News (auto)', icon: Newspaper },
    { key: 'journal', label: 'رزومه / ژورنال', sub: 'Paper journal', icon: BookOpen },
    { key: 'risk', label: 'مدیریت سرمایه', sub: 'Risk', icon: Gauge },
  ];
  return <>
    <aside className={`sidebar ${open ? 'mobile-open' : ''}`}>
      <div className="sidebar-head"><Brand/><button type="button" className="mobile-close" onClick={close}><X size={19}/></button></div>
      <p className="nav-label">WORKSPACE · فضای کاری</p>
      <nav>{nav.map(({ key, label, sub, icon: Icon }) => <button type="button" key={key} className={active === key ? 'active' : ''} onClick={() => { setActive(key); close(); document.getElementById(key)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }}><Icon size={17}/><span><em style={{ display: 'block', fontStyle: 'normal', fontSize: '11px', fontWeight: 700, lineHeight: 1 }}>{label}</em><i style={{ display: 'block', fontStyle: 'normal', fontSize: '8px', color: '#6b7280', fontWeight: 500 }}>{sub}</i></span></button>)}</nav>
      <p className="nav-label account-label">ACCOUNT · حساب</p>
      <nav>
        <button type="button"><WalletCards size={17}/><span>پورتفوی</span></button>
        <button type="button"><Settings size={17}/><span>تنظیمات</span></button>
      </nav>
      <div className="system-card">
        <div>
          <span className="system-icon">{feedState === 'offline' ? <AlertTriangle size={17}/> : feedState === 'live' ? <ShieldCheck size={17}/> : <Activity size={17}/>}</span>
          <div><b>{feedState === 'live' ? 'فید واقعی متصل' : feedState === 'offline' ? 'فید قطع است' : feedState === 'polling' ? 'کندل REST، نه قیمت زنده' : 'در حال اتصال'}</b><small>بدون دیتای ساختگی</small></div>
        </div>
        <div className="system-row"><span>منبع داده</span><b style={{ color: 'var(--gold)' }}>{providerLabel}</b></div>
        <div className="system-row"><span>وضعیت فید</span><b>{feedState}</b></div>
        <div className="system-row"><span>حالت</span><b style={{ color: 'var(--gold)' }}>REAL DATA ONLY</b></div>
      </div>
      <div className="user-card"><div className="avatar">PP</div><div><b>Peyman P.</b><small>حساب کوچک · Paper</small></div><ChevronDown size={15}/></div>
    </aside>
    {open && <button type="button" aria-label="Close navigation" className="sidebar-scrim" onClick={close}/>}
  </>;
}

function Header({ price, previous, menu, feedState, lastBarTime }: { price: number | null; previous: number | null; menu: () => void; feedState: string; lastBarTime: number | null }) {
  const delta = price != null && previous != null ? price - previous : null;
  return <header className="topbar">
    <div className="mobile-brand"><button type="button" onClick={menu}><Menu size={20}/></button><Brand/></div>
    <div className="market-title">
      <div className="mini-gold">Au</div>
      <div><span>XAU / USD</span><small>Gold Spot · انس طلا</small></div>
      <div className="live-pill" style={feedState === 'live' ? undefined : { background: '#f1bc4b12', borderColor: '#f1bc4b30', color: 'var(--gold)' }}>
        <i style={feedState === 'live' ? undefined : { background: 'var(--gold)', boxShadow: 'none' }}/>
        {feedState === 'live' ? 'LIVE · فید واقعی' : feedState === 'offline' ? 'OFFLINE · قطع' : feedState === 'polling' ? 'REST · کندل دوره‌ای' : 'CONNECTING'}
      </div>
      {lastBarTime && <div className="live-pill" style={{ background: '#f1bc4b12', borderColor: '#f1bc4b30', color: 'var(--gold)' }}><Zap size={11}/> آخرین کندل {new Date(lastBarTime).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' })}</div>}
    </div>
    <div className="quote">
      <strong>{price != null ? price.toFixed(2) : '—'}</strong>
      <span className={delta == null ? '' : delta >= 0 ? 'green' : 'red'}>
        {delta != null ? `${delta >= 0 ? '+' : ''}${delta.toFixed(2)}` : 'بدون داده'}
      </span>
    </div>
    <div className="top-actions">
      <button type="button" className="search"><Search size={16}/><span>جستجو</span><kbd>⌘ K</kbd></button>
      <button type="button" className="round"><HelpCircle size={17}/></button>
      <button type="button" className="round notification"><Bell size={17}/></button>
    </div>
  </header>;
}

function SignalCard({ signal, timeframe, updatedAt }: { signal: LiveSignal; timeframe: Timeframe; updatedAt: number | null }) {
  const isBuy = signal.action === 'BUY';
  const isSell = signal.action === 'SELL';
  const isNoTrade = signal.action === 'NO_TRADE';
  return <section className="signal-card panel" id="signals" style={{ borderColor: isBuy ? '#28c99b40' : isSell ? '#ef637140' : 'var(--line)' }}>
    <div className="panel-heading">
      <div><span className="eyebrow"><Sparkles size={12}/> REAL-CANDLE ENGINE · موتور روی کندل واقعی</span><h2>سیگنال زنده طلا</h2></div>
      <span className="live-tag" style={{ color: isNoTrade ? '#888' : isBuy ? 'var(--green)' : 'var(--red)', borderColor: isNoTrade ? '#333' : isBuy ? '#28c99b30' : '#ef637130' }}>
        <i style={{ background: isNoTrade ? '#888' : isBuy ? 'var(--green)' : 'var(--red)', boxShadow: 'none' }}/> {isNoTrade ? 'انتظار' : 'سیگنال'}
      </span>
    </div>
    <div className="signal-hero">
      <div className="score-ring" style={{ '--score': `${signal.confidence}%`, background: `conic-gradient(${isNoTrade ? '#555' : isBuy ? 'var(--green)' : 'var(--red)'} ${signal.confidence}%, #22262c 0)` } as React.CSSProperties}>
        <div><strong style={{ color: isNoTrade ? '#999' : isBuy ? '#7af0c8' : '#ff8e9a' }}>{Math.round(signal.confidence)}</strong><span>/ 100</span></div>
      </div>
      <div className="signal-call">
        <span>توصیه / RECOMMENDATION · {timeframe}</span>
        {isBuy && <strong style={{ color: 'var(--green)' }}><TrendingUp size={23}/> خرید BUY</strong>}
        {isSell && <strong style={{ color: 'var(--red)' }}><TrendingDown size={23}/> فروش SELL</strong>}
        {isNoTrade && <strong style={{ color: '#9aa0ad' }}><Clock3 size={23}/> عدم ورود NO TRADE</strong>}
        <small>{updatedAt ? `آخرین ارزیابی ${new Date(updatedAt).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}` : 'در انتظار دیتای واقعی'}</small>
      </div>
    </div>

    {!isNoTrade && signal.entry != null && <div className="signal-levels">
      <div><span>نقطه ورود / ENTRY</span><b style={{ fontSize: '10px', color: 'var(--gold)' }}>{signal.entry.toFixed(2)}</b></div>
      <div><span>حد ضرر / STOP LOSS</span><b className="red">{signal.stop_loss?.toFixed(2) ?? '—'}</b></div>
      <div><span>حد سود / TAKE PROFIT</span><b className="green">{signal.take_profit?.toFixed(2) ?? '—'}</b></div>
    </div>}

    {isNoTrade && <div style={{ margin: '0 13px', border: '1px dashed #2a303a', borderRadius: 6, background: '#0a0c10', padding: '10px 12px', color: '#8a909c', fontSize: '11px', lineHeight: 1.6 }}>
      <b style={{ color: '#c9a86a', display: 'flex', alignItems: 'center', gap: 6 }}><AlertTriangle size={14}/> چرا الان ورود نمی‌کنیم؟</b>
      <ul style={{ margin: '6px 0 0', paddingRight: 18 }}>
        {signal.blockers.slice(0, 4).map((blocker, index) => <li key={index}>{blocker}</li>)}
      </ul>
    </div>}

    <div className="rr-row"><span>ریسک به ریوارد</span><div><i style={{ width: isNoTrade ? '0%' : '42%', background: isBuy ? 'var(--green)' : isSell ? 'var(--red)' : '#444' }}/></div><b>{signal.risk_reward ? `1 : ${signal.risk_reward.toFixed(2)}` : '—'}</b></div>

    {!isNoTrade && signal.exit_hint && <div style={{ margin: '0 13px', background: '#0f1410', border: '1px solid #1e2d24', borderRadius: 6, padding: '8px 10px', display: 'flex', gap: 8, alignItems: 'flex-start' }}>
      <LogOut size={14} style={{ color: 'var(--green)', marginTop: 2, transform: 'rotate(180deg)' }}/>
      <div><b style={{ fontSize: '9px', color: '#7af0c8' }}>سیگنال خروج / EXIT:</b><p style={{ margin: '3px 0 0', fontSize: '10px', color: '#9cb3a8', lineHeight: 1.5 }}>{signal.exit_hint}</p></div>
    </div>}

    {!!signal.reasons.length && <div style={{ padding: '8px 13px', display: 'flex', flexWrap: 'wrap', gap: 4 }}>
      {signal.reasons.map((reason, index) => <span key={index} style={{ background: '#11151b', border: '1px solid #1f2630', borderRadius: 4, padding: '3px 6px', font: '7px DM Mono', color: '#8a909c' }}><CheckIcon/> {reason.split(' / ')[0]}</span>)}
    </div>}
  </section>;
}

function CheckIcon() {
  return <Zap size={9} style={{ verticalAlign: '-1px', color: 'var(--gold)' }}/>;
}

function ConfluenceCard({ signal }: { signal: LiveSignal }) {
  return <section className="confluence-card panel" id="confluence">
    <div className="panel-heading">
      <div><span className="eyebrow">ICHIMOKU 7·22·44 + فیلترهای مکمل</span><h2>همگرایی روش‌های مکمل</h2><small style={{ color: '#6b7280', fontSize: '9px' }}>هر سیگنال با فیلترهای مستقل امتیاز می‌گیرد</small></div>
      <span className="bull-badge" style={{ background: signal.action === 'BUY' ? '#28c99b14' : signal.action === 'SELL' ? '#ef637114' : '#2a303a30', color: signal.action === 'BUY' ? 'var(--green)' : signal.action === 'SELL' ? 'var(--red)' : '#888', borderColor: signal.action === 'BUY' ? '#28c99b30' : signal.action === 'SELL' ? '#ef637130' : '#333' }}>
        {signal.action === 'BUY' ? 'BULLISH · صعودی' : signal.action === 'SELL' ? 'BEARISH · نزولی' : 'NEUTRAL · خنثی'}
      </span>
    </div>
    <div className="confluence-list" style={{ maxHeight: 320 }}>
      {signal.confluence.length === 0
        ? <div style={{ padding: 12, color: '#666', fontSize: 11 }}>تا رسیدن کندل واقعی، همگرایی محاسبه نمی‌شود.</div>
        : signal.confluence.map((row) => <div key={row.name} style={{ opacity: row.ok ? 1 : 0.55 }}>
            <span className="check" style={{ background: row.ok ? '#28c99b18' : '#3a2330', color: row.ok ? 'var(--green)' : '#9b6b7a' }}>{row.ok ? '✓' : '✕'}</span>
            <span style={{ flex: 1 }}>{row.name}<small style={{ display: 'block', color: '#555d6b', font: '6px DM Mono' }}>{row.detail}</small></span>
            <b style={{ color: row.ok ? 'var(--green)' : '#9b6b7a', fontSize: '7px' }}>{row.ok ? '+امتیاز' : '—'}</b>
          </div>)}
    </div>
    <div style={{ padding: '8px 12px', background: '#0f1115', borderTop: '1px solid var(--line)', display: 'flex', gap: 8, alignItems: 'center', color: '#7a8290', fontSize: '9px', lineHeight: 1.5 }}>
      <Layers size={14} style={{ color: 'var(--gold)' }}/>
      <span><b style={{ color: '#c9a86a' }}>چرا مکمل؟</b> ایچیموکو روند، RSI/MACD مومنتوم، Vortex/ADX قدرت حرکت و VWAP میانگین وزنی را می‌گیرد؛ همه روی همان کندل‌های واقعی.</span>
    </div>
  </section>;
}

type NewsAnalysis = { direction: string; confidence: number; impact: string; rationale: string };
type NewsArticle = {
  id: string;
  headline: string;
  summary: string;
  source: string;
  url?: string | null;
  published_at?: string | null;
  analysis: NewsAnalysis;
  language: string;
};
type NewsSourceStatus = { name: string; feed: string; language: string; state: string; count?: number; error?: string | null };
type NewsWebStatus = {
  provider: string | null;
  configured: boolean;
  state: 'online' | 'partial' | 'unavailable';
  last_success_at?: string | null;
  error?: string | null;
  cached: boolean;
  sources: NewsSourceStatus[];
};
type NewsGuard = { state: 'CLEAR' | 'BLOCKED' | 'UNKNOWN'; reason: string; until?: string | null };
type AiConfluence = {
  status: 'AVAILABLE' | 'UNKNOWN';
  symbol: string;
  direction: string;
  confidence: number;
  impact?: string;
  model?: string | null;
  reason: string;
  evidence_ids: string[];
  checked_at: string;
};
type WebNewsResponse = {
  status: NewsWebStatus;
  articles: NewsArticle[];
  guard: NewsGuard;
  ai_confluence: AiConfluence;
  checked_at: string;
  notice?: string;
};

function NewsCard() {
  const [data, setData] = useState<WebNewsResponse | null>(null);
  const [error, setError] = useState<string>('');

  useEffect(() => {
    let alive = true;
    // /api/v1/news/web scrapes publishers' own public RSS feeds automatically (no manual
    // URL/API key needed) and, only if a server-side model key is configured, adds a real
    // model-backed AI confluence verdict. It never falls back to keyword-guessing "AI".
    const load = async () => {
      const result = await apiGet<WebNewsResponse>('/api/v1/news/web');
      if (!alive) return;
      if (result.ok) {
        setData(result.data);
        setError('');
      } else {
        setError(result.error);
      }
    };
    void load();
    const timer = window.setInterval(load, 60_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const ai = data?.ai_confluence;
  const guard = data?.guard;
  const articles = data?.articles ?? [];
  const aiTone = ai?.status === 'AVAILABLE' ? (ai.direction === 'BUY' ? 'bullish' : ai.direction === 'SELL' ? 'bearish' : 'neutral') : 'neutral';
  const guardColor = guard?.state === 'CLEAR' ? 'var(--green)' : guard?.state === 'BLOCKED' ? 'var(--red)' : '#e6a244';
  const onlineSources = data?.status.sources.filter((s) => s.state === 'online').length ?? 0;
  const totalSources = data?.status.sources.length ?? 0;

  return <section className="news-panel panel" id="news">
    <div className="panel-heading wide">
      <div>
        <span className="eyebrow"><Rss size={12}/> RSS خودکار ناشران + تقویم Forex Factory · بدون تنظیم دستی</span>
        <h2>نبض بازار (خودکار، بدون خبر ساختگی)</h2>
        <small style={{ color: '#6b7280', fontSize: '8px' }}>
          فقط از فید عمومی ناشران و تقویم هفتگی؛ اگر پوشش کامل نباشد وضعیت «نامشخص» اعلام می‌شود، نه خبر ساختگی.
        </small>
      </div>
      {ai && (
        <div className="sentiment-summary">
          <span>{ai.status === 'AVAILABLE' ? 'تحلیل هوش مصنوعی' : 'هوش مصنوعی'}</span>
          <b style={{ color: aiTone === 'bullish' ? 'var(--green)' : aiTone === 'bearish' ? 'var(--red)' : '#e6a244' }}>
            <TrendingUp size={15}/>
            {ai.status === 'AVAILABLE' ? `${ai.direction} · ${ai.confidence.toFixed(0)}%` : 'غیرفعال'}
          </b>
        </div>
      )}
    </div>

    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', padding: '0 16px 10px' }}>
      {guard && (
        <span className="live-pill" style={{ background: 'transparent', borderColor: guardColor + '40', color: guardColor }}>
          <AlertTriangle size={11}/> {guard.state === 'CLEAR' ? 'بدون خبر پراثر تازه' : guard.state === 'BLOCKED' ? 'توقف ورود — خبر پراثر' : 'وضعیت نامشخص'}
        </span>
      )}
      {data?.status && (
        <span className="live-pill" style={{ background: 'transparent' }}>منابع آنلاین {onlineSources}/{totalSources}</span>
      )}
    </div>

    {(error || (guard && guard.state !== 'CLEAR')) && (
      <div style={{ padding: '0 16px 14px', color: '#e6a244', fontSize: 11, lineHeight: 1.8 }}>
        <AlertTriangle size={14} style={{ verticalAlign: '-2px', marginLeft: 6 }}/>
        {error || guard?.reason}
      </div>
    )}
    {ai && ai.status !== 'AVAILABLE' && (
      <div style={{ padding: '0 16px 14px', color: '#6b7280', fontSize: 10, lineHeight: 1.8 }}>
        هوش مصنوعی: {ai.reason}
      </div>
    )}

    <div className="news-list">
      {articles.slice(0, 8).map((item) => {
        const dir = item.analysis?.direction;
        const tone = dir === 'BUY' ? 'bullish' : dir === 'SELL' ? 'bearish' : 'neutral';
        return (
          <details key={item.id} className="news-item">
            <summary>
              <span className="news-time" style={{ fontFamily: 'DM Mono' }}>{item.published_at ? new Date(item.published_at).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' }) : '—'}</span>
              <span className={`tone-icon ${tone}`} style={{ width: 26, height: 26 }}><Newspaper size={14}/></span>
              <span className="news-copy">
                <span className="news-meta"><span className="news-tag">{item.language === 'fa' ? 'خبر' : 'NEWS'}</span><span className="source">{item.source}</span></span>
                <span className="news-headline">{item.headline}</span>
              </span>
            </summary>
            {item.summary && <p className="news-excerpt">{item.summary}</p>}
            {item.url && (
              <a href={item.url} target="_blank" rel="noreferrer" style={{ display: 'block', padding: '0 16px 12px', fontSize: 9, color: 'var(--gold)' }}>
                مشاهده در منبع ↗
              </a>
            )}
          </details>
        );
      })}
      {!articles.length && !error && (
        <div style={{ padding: 14, color: '#6b7280', fontSize: 11, lineHeight: 1.8 }}>
          هنوز خبری از فیدهای عمومی دریافت نشده؛ اتصال سرور به ناشران در حال بررسی است.
        </div>
      )}
    </div>

    <div style={{ padding: '10px 14px', borderTop: '1px solid var(--line)', color: '#7a8290', fontSize: 9, lineHeight: 1.7 }}>
      برچسب هر خبر یک تحلیل قاعده‌محور سریع است، نه هوش مصنوعی؛ تحلیل واقعی هوش مصنوعی فقط در نشان بالای پنل (وقتی مدل و مجوز روی سرور فعال باشد) نمایش داده می‌شود. هیچ خبر یا تقویم اقتصادی‌ای از خودمان اضافه نمی‌کنیم.
    </div>
  </section>;
}

function RiskCard({ signal, price }: { signal: LiveSignal; price: number | null }) {
  const [risk, setRisk] = useState(0.5);
  const [balance, setBalance] = useState(100);
  const entry = signal.entry ?? price ?? null;
  const stop = signal.stop_loss ?? null;
  const stopDist = entry != null && stop != null ? Math.abs(entry - stop) : null;
  const riskAmount = balance * risk / 100;
  const exactOz = stopDist ? riskAmount / stopDist : 0;
  const minLotOz = 1.0;
  const executableOz = Math.max(exactOz, minLotOz);
  const actualRisk = stopDist ? executableOz * stopDist : 0;
  const riskColor = risk <= 0.5 ? 'var(--green)' : risk <= 1 ? '#e6a244' : 'var(--red)';
  const belowMin = stopDist != null && exactOz < minLotOz;

  return <section className="risk-panel panel" id="risk">
    <div className="panel-heading">
      <div><span className="eyebrow"><Gauge size={12}/> POSITION SIZING · حساب کوچک</span><h2>مدیریت سرمایه</h2><small style={{ color: '#6b7280', fontSize: '8px' }}>محاسبه با قیمت واقعی سیگنال</small></div>
      <CircleDollarSign size={19} className="heading-icon"/>
    </div>
    <label><span>موجودی حساب (دلار)</span><div className="input-wrap"><i>$</i><input value={balance} onChange={(event) => setBalance(Number(event.target.value) || 0)} type="number"/><em>USD</em></div></label>
    <div style={{ display: 'flex', gap: 6, margin: '0 13px', flexWrap: 'wrap' }}>
      {[100, 250, 500, 1000, 5000].map((value) => <button type="button" key={value} onClick={() => setBalance(value)} style={{ flex: 1, minWidth: 44, height: 24, border: balance === value ? '1px solid var(--gold)' : '1px solid var(--line)', background: balance === value ? '#f1bc4b18' : '#0a0c10', color: balance === value ? 'var(--gold)' : '#888', borderRadius: 4, font: '700 9px DM Mono', cursor: 'pointer' }}>${value}</button>)}
    </div>
    <label htmlFor="risk-per-trade"><span>ریسک هر معامله</span><b className="gold-text" style={{ color: riskColor }}>{risk.toFixed(2)}%</b></label>
    <input id="risk-per-trade" className="range" type="range" min="0.1" max="2" step="0.1" value={risk} onChange={(event) => setRisk(Number(event.target.value))} style={{ background: `linear-gradient(90deg, ${riskColor} ${risk / 2 * 100}%, #262b33 ${risk / 2 * 100}%)` }}/>
    <div className="risk-marks"><span>0.1%</span><span style={{ color: riskColor }}>{risk <= 0.5 ? 'محافظه‌کار' : risk <= 1 ? 'متعادل' : 'تهاجمی'}</span><span>2.0%</span></div>

    {stopDist == null ? (
      <div style={{ margin: '0 13px', padding: '10px 12px', border: '1px dashed #2a303a', borderRadius: 6, color: '#8a909c', fontSize: 11 }}>
        برای محاسبه حجم، اول باید یک سیگنال واقعی با حد ضرر مشخص برسد.
      </div>
    ) : (
      <>
        <div className="position-result">
          <div><span>ریسک هدف</span><b style={{ color: riskColor }}>${riskAmount.toFixed(2)}</b><small style={{ color: '#6b7280', font: '7px DM Mono' }}>حجم دقیق ریسک‌محور: {exactOz.toFixed(3)} oz</small></div>
          <div><span>حجم قابل اجرا</span><b>{executableOz.toFixed(2)} oz</b><small style={{ color: '#6b7280', font: '7px DM Mono' }}>ریسک واقعی ${actualRisk.toFixed(2)} ({balance ? (actualRisk / balance * 100).toFixed(2) : '0'}%)</small></div>
        </div>
        <div style={{ margin: '8px 13px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, font: '7px DM Mono' }}>
          <div style={{ background: '#0a0e12', border: '1px solid #1a2520', borderRadius: 4, padding: '6px 7px', color: '#7a8a9a' }}>استاپ: <b style={{ color: '#fff' }}>{stopDist.toFixed(2)}$</b> ({entry != null && entry > 0 ? `${(stopDist / entry * 100).toFixed(3)}%` : '—'})</div>
          <div style={{ background: '#0a0e12', border: '1px solid #1a2520', borderRadius: 4, padding: '6px 7px', color: '#7a8a9a' }}>R:R: <b style={{ color: 'var(--green)' }}>1:{signal.risk_reward?.toFixed(2) ?? '—'}</b></div>
        </div>
        <div className="risk-note" style={{ borderColor: belowMin ? '#f1bc4b30' : '#28c99b18', background: belowMin ? '#f1bc4b0a' : '#28c99b07' }}>
          {belowMin ? <AlertTriangle size={15} style={{ color: 'var(--gold)' }}/> : <ShieldCheck size={15}/>}
          <span style={{ lineHeight: 1.6 }}>
            {belowMin
              ? <>ریسک دقیق {risk}% معادل {exactOz.toFixed(3)} oz است، ولی حداقل حجم بروکر (۰.۰۱ لات = ۱ انس) بزرگ‌تر است؛ با این اجرا ریسک واقعی <b style={{ color: 'var(--gold)' }}>${actualRisk.toFixed(2)} ({(actualRisk / balance * 100).toFixed(1)}%)</b> می‌شود. یا موجودی بیشتری لازم است یا باید این ستاپ را رد کنی.</>
              : <>حجم ریسک‌محور با حداقل لات بروکر سازگار است. ریسک واقعی این معامله <b>${actualRisk.toFixed(2)}</b> است.</>}
          </span>
        </div>
      </>
    )}

    <div style={{ margin: '0 13px 10px', background: '#0f1115', border: '1px solid var(--line)', borderRadius: 5, padding: '8px 9px' }}>
      <b style={{ fontSize: '9px', display: 'flex', alignItems: 'center', gap: 6 }}><ShieldCheck size={12} style={{ color: 'var(--green)' }}/> حساب کوچک، واقعیت‌ها</b>
      <p style={{ margin: '4px 0 0', fontSize: '9px', color: '#8a909c', lineHeight: 1.7 }}>
        با موجودی کوچک و حداقل لات ۱ انس، ریسک هر معامله معمولاً بیشتر از درصد هدف است. این عدد را ببین، نه عدد آرزو.
        هیچ سودی هم تضمین نمی‌شود؛ خروجی فقط بازتاب بک‌تست روی دیتای واقعی است.
      </p>
    </div>
  </section>;
}

type JournalEntry = {
  id: string;
  action: string;
  entry: number;
  stop_loss: number;
  take_profit: number;
  confidence: number;
  opened_at: string;
  closed_at?: string | null;
  exit_price?: number | null;
  exit_reason?: string | null;
  pnl?: number | null;
};

type JournalStats = { closed: number; open: number; win_rate: number | null };

function JournalCard() {
  const [entries, setEntries] = useState<JournalEntry[]>([]);
  const [stats, setStats] = useState<JournalStats | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<{ entries: JournalEntry[]; stats: JournalStats }>('/api/v1/journal?limit=50');
      if (!alive) return;
      if (result.ok) {
        setEntries(result.data.entries ?? []);
        setStats(result.data.stats ?? null);
        setError('');
      } else setError(result.error);
    };
    void load();
    const timer = window.setInterval(load, 30_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const closed = entries.filter((entry) => entry.exit_price != null);
  const open = entries.filter((entry) => entry.exit_price == null);

  return <section className="panel" id="journal" style={{ gridColumn: '1 / -1' }}>
    <div className="panel-heading wide">
      <div><span className="eyebrow"><BookOpen size={12}/> PAPER JOURNAL · فقط سیگنال‌های واقعی</span><h2>رزومه معاملات (کاغذی)</h2>
        <small style={{ color: '#6b7280', fontSize: 9 }}>هر ردیف یک سیگنال واقعیِ سیستم است که با دیتای واقعی بعدش تسویه می‌شود. ژورنال خالی یعنی هنوز سیگنالی صادر نشده.</small>
      </div>
      {stats && <span style={{ font: '8px DM Mono', color: '#8a909c' }}>{stats.closed ?? 0} بسته‌شده · {stats.open ?? 0} باز · وین‌ریت {stats.win_rate != null ? `${stats.win_rate}%` : '—'}</span>}
    </div>

    {error && <div style={{ padding: '12px 14px', color: '#e6a244', fontSize: 11 }}>{error}</div>}

    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px,1fr))', gap: 12, padding: 12 }}>
      {[{ title: 'پوزیشن‌های باز', rows: open }, { title: 'بسته‌شده‌ها', rows: closed.slice(0, 8) }].map((group) => (
        <div key={group.title} style={{ background: '#0a0e12', border: '1px solid #1a2320', borderRadius: 6, overflow: 'hidden' }}>
          <div style={{ padding: '6px 9px', background: '#0f1115', font: '8px DM Mono', color: '#c9b896' }}>{group.title}</div>
          {group.rows.length === 0
            ? <div style={{ padding: '10px 12px', color: '#6b7280', fontSize: 10 }}>خالی است.</div>
            : group.rows.map((entry) => (
              <div key={entry.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, padding: '7px 10px', borderTop: '1px solid #1a1f28', font: '8px DM Mono', color: '#c9cdd5' }}>
                <span style={{ color: entry.action === 'BUY' ? 'var(--green)' : 'var(--red)' }}>{entry.action}</span>
                <span>{entry.entry}</span>
                <span style={{ color: '#7a8290' }}>{new Date(entry.opened_at).toLocaleString('fa-IR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</span>
                <span style={{ color: entry.pnl == null ? '#7a8290' : entry.pnl >= 0 ? 'var(--green)' : 'var(--red)' }}>
                  {entry.pnl != null ? `${entry.pnl > 0 ? '+' : ''}${entry.pnl.toFixed(2)}$` : entry.exit_reason ?? 'باز'}
                </span>
              </div>
            ))}
        </div>
      ))}
    </div>
  </section>;
}

function Disclaimer() {
  return <div className="disclaimer" style={{ margin: '16px 0', padding: '12px 14px', background: '#0a0c10', border: '1px solid var(--line)', borderRadius: 8 }}>
    <b style={{ fontSize: 11, color: '#c9a86a', display: 'flex', alignItems: 'center', gap: 6 }}><History size={13}/> دیتای واقعی، بدون نسخه دمو</b>
    <p style={{ margin: '6px 0 0', fontSize: 10, color: '#8a909c', lineHeight: 1.9 }}>
      این ترمینال هیچ کندل، قیمت، خبر یا معامله‌ای نمی‌سازد. هر عدد از یک منبع واقعی می‌آید: در صورت وجود کلید Twelve Data، مستقیم از آن؛
      در غیر این صورت به‌صورت خودکار از فید رایگان و بدون کلید قیمت لحظه‌ای طلا (Swissquote/Gold-API) — و در صورت تنظیم، منبع خبری مجاز.
      وقتی اینترنت قطع باشد یا هیچ منبعی پاسخ ندهد، وضعیت «آفلاین» نشان داده می‌شود و آخرین داده واقعیِ دریافت‌شده برچسب‌دار نمایش داده می‌شود —
      نه یک نسخه دموی ساختگی. تنها «تمرینی» که وجود دارد، یاد گرفتن از همین دیتای واقعی است: بک‌تست، walk-forward و ژورنال کاغذی.
    </p>
  </div>;
}

export default function App() {
  const [timeframe, setTimeframe] = useState<Timeframe>('5m');
  const [active, setActive] = useState('terminal');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [signal, setSignal] = useState<LiveSignal>(emptySignal);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [status, setStatus] = useState<DataStatus | null>(null);

  const { snapshot, refresh } = useMarketFeed(timeframe);
  const candles: Candle[] = snapshot.candles;
  const activeProvider = status?.provider ?? snapshot.provider;
  const providerName = activeProvider === 'twelve_data' ? 'Twelve Data' : activeProvider === 'spot_fallback' ? 'فید رایگان خودکار طلا (Swissquote/Gold-API)' : '—';

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const result = await apiGet<DataStatus>('/api/v1/data/status');
      if (alive && result.ok) setStatus(result.data);
    };
    void load();
    const timer = window.setInterval(load, 60_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  // Evaluate the real candles server-side. No client-side fallback signal exists.
  useEffect(() => {
    if (snapshot.state !== 'live' && snapshot.state !== 'polling' ||
        !barIsCurrent(candles[candles.length - 1], timeframe)) {
      setSignal({ ...emptySignal, blockers: [snapshot.detail || 'دادهٔ زندهٔ تأییدشده در دسترس نیست؛ کندل‌های قدیمی فقط برای مشاهده‌اند'] });
      setUpdatedAt(null);
      return;
    }
    if (candles.length < 60) {
      setSignal({ ...emptySignal, blockers: ['کندل واقعی کافی برای ارزیابی دریافت نشده است'] });
      setUpdatedAt(null);
      return;
    }
    let alive = true;
    let latestRequest = 0;
    const evaluate = async () => {
      const requestId = ++latestRequest;
      if (!barIsCurrent(candles[candles.length - 1], timeframe)) {
        setSignal({ ...emptySignal, blockers: ['کندل منبع قدیمی شده است؛ ارزیابی تازه انجام نشد'] });
        setUpdatedAt(null);
        return;
      }
      const payload = toBackendCandles(candles.slice(-320), timeframe);
      const result = await apiPost<LiveSignal>('/api/v1/strategy/evaluate', {
        candles: payload,
        context: { spread: 0.30, typical_spread: 0.30, event_risk: false, higher_timeframe_bias: 'NEUTRAL' },
      });
      if (!alive || latestRequest !== requestId || !barIsCurrent(candles[candles.length - 1], timeframe)) return;
      if (result.ok) {
        setSignal({
          action: result.data.action,
          confidence: result.data.confidence ?? 0,
          entry: result.data.entry,
          stop_loss: result.data.stop_loss,
          take_profit: result.data.take_profit,
          risk_reward: result.data.risk_reward,
          reasons: result.data.reasons ?? [],
          blockers: result.data.blockers ?? [],
          confluence: result.data.confluence ?? [],
          exit_hint: result.data.exit_hint,
          expires_after_seconds: result.data.expires_after_seconds,
        });
        setUpdatedAt(Date.now());
      } else {
        setSignal({ ...emptySignal, blockers: [result.error] });
        setUpdatedAt(null);
      }
    };
    void evaluate();
    const timer = window.setInterval(evaluate, 30_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [candles, timeframe, snapshot.detail]);

  const feedLabel = useMemo(() => {
    if (snapshot.state === 'live') return 'فید زنده واقعی';
    if (snapshot.state === 'polling') return 'به‌روزرسانی دوره‌ای (REST)';
    if (snapshot.state === 'loading') return 'در حال دریافت…';
    return 'آفلاین — آخرین داده واقعی کش‌شده';
  }, [snapshot.state]);

  const feedTone: 'live' | 'offline' | 'stale' = snapshot.state === 'live' ? 'live' : snapshot.state === 'offline' ? 'offline' : 'stale';
  const previousClose = candles.length > 1 ? candles[candles.length - 2].close : null;
  const select = useCallback((key: string) => { setActive(key); document.getElementById(key)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, []);

  return <div className="app-shell">
    <Sidebar active={active} setActive={setActive} open={sidebarOpen} close={() => setSidebarOpen(false)} feedState={snapshot.state} provider={status?.provider ?? snapshot.provider}/>
    <div className="main-shell">
      <Header price={snapshot.lastPrice} previous={previousClose} menu={() => setSidebarOpen(true)} feedState={snapshot.state} lastBarTime={snapshot.lastBarTime}/>
      <main>
        <div className="page-intro">
          <div>
            <span className="eyebrow">
              <span style={{ width: 6, height: 6, background: feedTone === 'live' ? 'var(--green)' : feedTone === 'offline' ? 'var(--red)' : 'var(--gold)', borderRadius: '50%', boxShadow: 'none' }}/>
              PRECIOUS METALS DESK · میز طلا
            </span>
            <h1>ترمینال هوشمند طلا روی دیتای واقعی</h1>
            <small style={{ color: '#6b7280', fontSize: '10px' }}>{providerName} · بک‌تست و walk-forward روی همان کندل‌های واقعی · بدون هیچ دیتای ساختگی</small>
          </div>
          <div className="intro-stats">
            <span><i className="feed-dot" style={{ background: feedTone === 'live' ? 'var(--green)' : feedTone === 'offline' ? 'var(--red)' : 'var(--gold)' }}/> {feedLabel}</span>
            <span>{providerName}</span>
            <span style={{ background: '#f1bc4b18', color: 'var(--gold)', border: '1px solid #f1bc4b30', padding: '2px 6px', borderRadius: 4, font: '700 8px DM Mono' }}>REAL DATA ONLY</span>
            <button type="button" onClick={refresh} className="text-button" style={{ font: '700 8px DM Mono' }}>به‌روزرسانی</button>
          </div>
        </div>

        {snapshot.state === 'offline' && (
          <div className="event-banner" style={{ borderColor: '#ef637130', background: '#ef63710a' }}>
            <span className="event-icon" style={{ background: '#ef637118' }}><WifiOff size={16}/></span>
            <div>
              <b>اتصال به منبع داده قطع است</b>
              <p style={{ margin: '3px 0 0', fontSize: 10, color: '#c9b896', lineHeight: 1.7 }}>{snapshot.detail}</p>
            </div>
            <div className="event-date">{candles.length ? `آخرین کندل: ${new Date((snapshot.lastBarTime ?? 0)).toLocaleString('fa-IR')}` : 'بدون داده'}</div>
          </div>
        )}

        <div className="dashboard-grid" id="terminal">
          <TradingChart
            timeframe={timeframe}
            onTimeframeChange={setTimeframe}
            candles={candles}
            levels={signal.action === 'NO_TRADE' ? null : { entry: signal.entry, stop_loss: signal.stop_loss, take_profit: signal.take_profit, action: signal.action }}
            feedLabel={feedLabel}
            feedTone={feedTone}
            lastBarTime={snapshot.lastBarTime}
          />
          <SignalCard signal={signal} timeframe={timeframe} updatedAt={updatedAt}/>
          <NewsCard/>
          <div className="right-stack"><ConfluenceCard signal={signal}/><RiskCard signal={signal} price={snapshot.lastPrice}/></div>
        </div>

        <div style={{ display: 'grid', gap: 13, marginTop: 13 }}><PredictionPanel timeframe={timeframe}/></div>
        <div style={{ display: 'grid', gap: 13, marginTop: 13 }}><MTFPanel timeframe={timeframe}/></div>
        <div style={{ display: 'grid', gap: 13, marginTop: 13 }}><TopTradersPanel timeframe={timeframe}/></div>
        <div style={{ display: 'grid', gap: 13, marginTop: 13 }}><BacktestPanel/></div>
        <div style={{ display: 'grid', gap: 13, marginTop: 13 }}><CryptoPumpScanner/></div>
        <div style={{ display: 'grid', gap: 13, marginTop: 13 }}><JournalCard/></div>

        <Disclaimer/>

        <div style={{ marginTop: 16, padding: '12px 14px', background: '#0a0c10', border: '1px solid var(--line)', borderRadius: 8, display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <div style={{ width: 36, height: 36, borderRadius: 8, background: 'linear-gradient(135deg, #f1bc4b, #9b671f)', display: 'grid', placeItems: 'center', color: '#111', font: '700 14px Georgia' }}>$</div>
            <div>
              <b style={{ fontSize: 12 }}>قبل از هر چیز: اعداد واقعی را ببین</b>
              <small style={{ display: 'block', color: '#6b7280', fontSize: 10, marginTop: 2 }}>
                ۱) فید قیمت به‌صورت خودکار متصل است ({providerName}) · ۲) بک‌تست و walk-forward را روی دیتای واقعی اجرا کن · ۳) فقط اگر خارج از نمونه هم مثبت ماند، به Paper Trade فکر کن.
              </small>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="text-button" onClick={() => select('backtest')} style={{ height: 34, padding: '0 14px', borderRadius: 6, border: '1px solid var(--gold)', background: 'linear-gradient(180deg, #f5ca6d, #d9a33c)', color: '#1b160c', font: '800 10px Manrope' }}><BarChart3 size={14}/> رفتن به بک‌تست واقعی</button>
            <button type="button" className="text-button" onClick={() => select('journal')} style={{ height: 34, padding: '0 12px', borderRadius: 6, border: '1px solid var(--line)', background: '#11151b', color: '#8a909c', font: '700 10px Manrope' }}>ژورنال کاغذی</button>
          </div>
        </div>

        <footer style={{ marginTop: 14, display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8, color: '#5a6b65', font: '9px DM Mono' }}>
          <span>AURUM EDGE · Real-data build</span>
          <span>{providerName} · بدون fallback ساختگی</span>
        </footer>
      </main>
    </div>
  </div>;
}
