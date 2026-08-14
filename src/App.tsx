import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity, Bell, BookOpen, Bot, ChevronDown, CircleDollarSign, Gauge, HelpCircle,
  LayoutDashboard, Menu, Newspaper, PanelLeftClose, Search, Settings, ShieldCheck,
  Signal, SlidersHorizontal, Sparkles, Target, TrendingDown, TrendingUp, WalletCards, X,
} from 'lucide-react';
import TradingChart from './components/TradingChart';

type Timeframe = '1m' | '5m';

const news = [
  {
    time: '2m ago', tag: 'FED', impact: 'HIGH', tone: 'bullish', score: 86,
    title: 'Fed officials signal patience on rates as inflation cools further',
    source: 'Reuters', detail: 'Lower real-yield expectations support non-yielding gold demand.',
  },
  {
    time: '11m ago', tag: 'USD', impact: 'MED', tone: 'bearish', score: 64,
    title: 'Dollar index steadies ahead of US retail sales release',
    source: 'FXStreet', detail: 'A firm DXY is limiting immediate upside in precious metals.',
  },
  {
    time: '28m ago', tag: 'GOLD', impact: 'LOW', tone: 'bullish', score: 72,
    title: 'Central bank gold demand remains resilient in latest survey',
    source: 'Financial Modeling Prep', detail: 'Structural physical demand continues to underpin price.',
  },
];

const confluence = [
  { label: 'Price above Kumo cloud', value: '+18', ok: true },
  { label: 'Tenkan crossed Kijun', value: '+20', ok: true },
  { label: 'Above session VWAP', value: '+15', ok: true },
  { label: 'Above EMA 200', value: '+15', ok: true },
  { label: 'RSI 58.4 — healthy', value: '+12', ok: true },
  { label: 'News sentiment bullish', value: '+7', ok: true },
];

function Brand() {
  return <div className="brand"><div className="brand-mark"><span>A</span></div><div><b>AURUM</b><small>EDGE</small></div></div>;
}

function Sidebar({ active, setActive, open, close }: { active: string; setActive: (s: string) => void; open: boolean; close: () => void }) {
  const nav = [
    { key: 'terminal', label: 'Trading terminal', icon: LayoutDashboard },
    { key: 'signals', label: 'Signal engine', icon: Signal, badge: '3' },
    { key: 'news', label: 'News intelligence', icon: Newspaper },
    { key: 'strategy', label: 'Strategy lab', icon: SlidersHorizontal },
    { key: 'journal', label: 'Trade journal', icon: BookOpen },
  ];
  return <>
    <aside className={`sidebar ${open ? 'mobile-open' : ''}`}>
      <div className="sidebar-head"><Brand/><button className="mobile-close" onClick={close}><X size={19}/></button></div>
      <p className="nav-label">WORKSPACE</p>
      <nav>{nav.map(({ key, label, icon: Icon, badge }) => <button key={key} className={active === key ? 'active' : ''} onClick={() => { setActive(key); close(); document.getElementById(key)?.scrollIntoView({ behavior: 'smooth' }); }}><Icon size={17}/><span>{label}</span>{badge && <em>{badge}</em>}</button>)}</nav>
      <p className="nav-label account-label">ACCOUNT</p>
      <nav>
        <button><WalletCards size={17}/><span>Portfolio</span></button>
        <button><Settings size={17}/><span>Settings</span></button>
      </nav>
      <div className="system-card">
        <div><span className="system-icon"><ShieldCheck size={17}/></span><div><b>Systems nominal</b><small>All engines online</small></div></div>
        <div className="system-row"><span>Market data</span><b>124 ms</b></div>
        <div className="system-row"><span>AI sentiment</span><b>Online</b></div>
      </div>
      <div className="user-card"><div className="avatar">PP</div><div><b>Peyman P.</b><small>Pro workspace</small></div><ChevronDown size={15}/></div>
    </aside>
    {open && <button aria-label="Close navigation" className="sidebar-scrim" onClick={close}/>} 
  </>;
}

function Header({ price, delta, menu }: { price: number; delta: number; menu: () => void }) {
  return <header className="topbar">
    <div className="mobile-brand"><button onClick={menu}><Menu size={20}/></button><Brand/></div>
    <div className="market-title"><div className="mini-gold">Au</div><div><span>XAU / USD</span><small>Gold Spot</small></div><div className="live-pill"><i/> PAPER MODE</div></div>
    <div className="quote"><strong>{price.toFixed(2)}</strong><span className={delta >= 0 ? 'green' : 'red'}>{delta >= 0 ? '+' : ''}{Math.abs(delta).toFixed(2)} <small>(+0.47%)</small></span></div>
    <div className="top-actions"><button className="search"><Search size={16}/><span>Search markets</span><kbd>⌘ K</kbd></button><button className="round"><HelpCircle size={17}/></button><button className="round notification"><Bell size={17}/><i/></button><button className="trade-button">Quick trade <ChevronDown size={14}/></button></div>
  </header>;
}

function SignalCard() {
  return <section className="signal-card panel" id="signals">
    <div className="panel-heading"><div><span className="eyebrow"><Sparkles size={12}/> FUSION ENGINE</span><h2>Live signal</h2></div><span className="live-tag"><i/> LIVE</span></div>
    <div className="signal-hero">
      <div className="score-ring" style={{ '--score': '87%' } as React.CSSProperties}><div><strong>87</strong><span>/ 100</span></div></div>
      <div className="signal-call"><span>RECOMMENDATION</span><strong><TrendingUp size={23}/> BUY</strong><small>High conviction</small></div>
    </div>
    <div className="signal-levels">
      <div><span>ENTRY ZONE</span><b>3,357.80 – 3,359.10</b></div>
      <div><span>STOP LOSS</span><b className="red">3,354.25</b></div>
      <div><span>TAKE PROFIT</span><b className="green">3,365.80</b></div>
    </div>
    <div className="rr-row"><span>Risk / reward</span><div><i style={{ width: '31%' }}/></div><b>1 : 2.16</b></div>
    <div className="valid-row"><span><Activity size={14}/> Valid for <b>02:41</b></span><span>1m · Scalping</span></div>
    <button className="primary-button"><Target size={16}/> Open trade ticket</button>
    <p className="disclaimer">Decision support only. Not financial advice.</p>
  </section>;
}

function ConfluenceCard() {
  return <section className="confluence-card panel" id="strategy">
    <div className="panel-heading"><div><span className="eyebrow">ICHIMOKU 7 · 22 · 44</span><h2>Signal confluence</h2></div><span className="bull-badge">BULLISH</span></div>
    <div className="confluence-list">{confluence.map((row) => <div key={row.label}><span className="check">✓</span><span>{row.label}</span><b>{row.value}</b></div>)}</div>
    <div className="indicator-strip">
      <div><span>ATR 14</span><b>2.84</b></div><div><span>RSI 7</span><b>58.4</b></div><div><span>ADX</span><b>27.1</b></div><div><span>SPREAD</span><b>0.18</b></div>
    </div>
  </section>;
}

function NewsCard() {
  const [expanded, setExpanded] = useState(0);
  return <section className="news-panel panel" id="news">
    <div className="panel-heading wide"><div><span className="eyebrow"><Bot size={12}/> AI NEWS INTELLIGENCE</span><h2>Market pulse</h2></div><div className="sentiment-summary"><span>24H SENTIMENT</span><b><TrendingUp size={15}/> 71% BULLISH</b></div></div>
    <div className="sentiment-meter"><i/><span className="meter-marker"/></div>
    <div className="news-list">{news.map((item, i) => <article key={item.title} className={expanded === i ? 'expanded' : ''} onClick={() => setExpanded(i)}>
      <div className="news-time">{item.time}</div>
      <div className={`tone-icon ${item.tone}`}>{item.tone === 'bullish' ? <TrendingUp size={15}/> : <TrendingDown size={15}/>}</div>
      <div className="news-copy"><div><span className="news-tag">{item.tag}</span><span className={`impact ${item.impact.toLowerCase()}`}>{item.impact}</span><span className="source">{item.source}</span></div><h3>{item.title}</h3>{expanded === i && <p>{item.detail}</p>}</div>
      <div className={`news-score ${item.tone}`}><b>{item.score}%</b><span>{item.tone}</span></div>
    </article>)}</div>
    <button className="text-button">View intelligence feed <span>→</span></button>
  </section>;
}

function RiskCard() {
  const [risk, setRisk] = useState(0.5);
  const [balance, setBalance] = useState(25000);
  const riskAmount = balance * risk / 100;
  const units = Math.floor(riskAmount / 4.17);
  return <section className="risk-panel panel">
    <div className="panel-heading"><div><span className="eyebrow"><Gauge size={12}/> POSITION SIZING</span><h2>Risk calculator</h2></div><CircleDollarSign size={19} className="heading-icon"/></div>
    <label><span>Account balance</span><div className="input-wrap"><i>$</i><input value={balance} onChange={(e) => setBalance(Number(e.target.value) || 0)}/><em>USD</em></div></label>
    <label><span>Risk per trade</span><b className="gold-text">{risk.toFixed(2)}%</b></label>
    <input className="range" type="range" min="0.1" max="2" step="0.1" value={risk} onChange={(e) => setRisk(Number(e.target.value))}/>
    <div className="risk-marks"><span>0.1%</span><span>Conservative</span><span>2.0%</span></div>
    <div className="position-result"><div><span>MAXIMUM RISK</span><b>${riskAmount.toFixed(2)}</b></div><div><span>POSITION SIZE</span><b>{units} oz</b></div></div>
    <div className="risk-note"><ShieldCheck size={15}/><span>Within your <b>1.0% daily risk limit</b></span></div>
  </section>;
}

function EconomicBanner() {
  const [dismissed, setDismissed] = useState(false);
  if (dismissed) return null;
  return <div className="event-banner"><div className="event-date"><b>14:30</b><span>UTC</span></div><div className="event-icon"><Bell size={17}/></div><div><span className="eyebrow">HIGH IMPACT · IN 28 MIN</span><b>US Retail Sales m/m</b></div><p>Execution guard will pause new entries 3 minutes before release.</p><button onClick={() => setDismissed(true)}><X size={16}/></button></div>;
}

function MobileNav({ active, select }: { active: string; select: (s: string) => void }) {
  return <nav className="mobile-nav">{[
    ['terminal', LayoutDashboard, 'Terminal'], ['signals', Signal, 'Signals'], ['news', Newspaper, 'News'], ['strategy', SlidersHorizontal, 'Strategy'],
  ].map(([key, Icon, label]) => { const I = Icon as typeof LayoutDashboard; return <button key={key as string} className={active === key ? 'active' : ''} onClick={() => select(key as string)}><I size={19}/><span>{label as string}</span></button>; })}</nav>;
}

export default function App() {
  const [active, setActive] = useState('terminal');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [timeframe, setTimeframe] = useState<Timeframe>('1m');
  const [price, setPrice] = useState(3358.42);
  const [delta, setDelta] = useState(15.84);
  const [clock, setClock] = useState('14:32:08');

  useEffect(() => { const t = setInterval(() => setClock(new Date().toISOString().slice(11, 19)), 1000); return () => clearInterval(t); }, []);
  const onPriceChange = useCallback((next: number, candleDelta: number) => { setPrice(next); setDelta(15.84 + candleDelta); }, []);
  const select = useCallback((key: string) => { setActive(key); document.getElementById(key)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, []);
  const status = useMemo(() => `UTC ${clock}`, [clock]);

  return <div className="app-shell">
    <Sidebar active={active} setActive={setActive} open={sidebarOpen} close={() => setSidebarOpen(false)}/>
    <div className="main-shell">
      <Header price={price} delta={delta} menu={() => setSidebarOpen(true)}/>
      <main>
        <div className="page-intro"><div><span className="eyebrow">PRECIOUS METALS DESK</span><h1>Trading terminal</h1></div><div className="intro-stats"><span><i className="feed-dot"/> Feeds synchronized</span><span>{status}</span></div></div>
        <EconomicBanner/>
        <div className="dashboard-grid" id="terminal">
          <TradingChart timeframe={timeframe} onTimeframeChange={setTimeframe} onPriceChange={onPriceChange}/>
          <SignalCard/>
          <NewsCard/>
          <div className="right-stack"><ConfluenceCard/><RiskCard/></div>
        </div>
      </main>
    </div>
    <MobileNav active={active} select={select}/>
  </div>;
}
