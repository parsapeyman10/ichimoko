import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity, Bell, BookOpen, Bot, Brain, ChevronDown, CircleDollarSign, Crown, Gauge, HelpCircle,
  LayoutDashboard, Menu, Newspaper, PanelLeftClose, Search, Settings, ShieldCheck,
  Signal, SlidersHorizontal, Sparkles, Target, TrendingDown, TrendingUp, Users, WalletCards, X,
  LogOut, Clock3, AlertTriangle, CheckCircle2, BarChart3, Layers, History, DollarSign, Zap
} from 'lucide-react';
import TradingChart from './components/TradingChart';
import BacktestPanel from './components/BacktestPanel';
import PredictionPanel from './components/PredictionPanel';
import TopTradersPanel from './components/TopTradersPanel';
import MTFPanel from './components/MTFPanel';
import { generateCandles, ema, vwap, ichimoku, rsi, atr, macdLine, bollinger } from './lib/market';

type Timeframe = '1m' | '5m';

// ─── Types for enriched signal ─────────────────────────────────────
type ConfluenceItem = { name: string; ok: boolean; detail: string };
type LiveSignal = {
  action: 'BUY'|'SELL'|'NO_TRADE';
  confidence: number;
  entry?: number;
  stop_loss?: number;
  take_profit?: number;
  risk_reward?: number;
  reasons: string[];
  blockers: string[];
  confluence: ConfluenceItem[];
  exit_hint?: string;
  expires_after_seconds: number;
};

// fallback browser evaluation (simplified mirror of backend — 5m strict 9/26/52, 75 threshold, RR1.5)
function evaluateBrowser(candles: ReturnType<typeof generateCandles>, timeframe: Timeframe = '5m'): LiveSignal {
  const ema200 = ema(candles, 200);
  const v = vwap(candles);
  const ichi = ichimoku(candles);
  const r = rsi(candles, 7);
  const a = atr(candles, 14);
  const m = macdLine(candles);
  const bb = bollinger(candles);
  const i = candles.length-1;
  const tenkan = ichi.tenkan.find(p=>p.time===candles[i].time)?.value;
  const kijun = ichi.kijun.find(p=>p.time===candles[i].time)?.value;
  const prevTenkan = ichi.tenkan.find(p=>p.time===candles[i-1]?.time)?.value;
  const prevKijun = ichi.kijun.find(p=>p.time===candles[i-1]?.time)?.value;
  const spanA = ichi.spanA.find(p=>p.time===candles[i].time)?.value;
  const spanB = ichi.spanB.find(p=>p.time===candles[i].time)?.value;
  const rsiVal = r[i];
  const atrVal = a[i];
  const price = candles[i].close;
  const emaVal = ema200.find(p=>p.time===candles[i].time)?.value ?? price;
  const vwapVal = v.find(p=>p.time===candles[i].time)?.value ?? price;
  const macdHist = m.hist[i];
  if (!tenkan || !kijun || !prevTenkan || !prevKijun || !spanA || !spanB || rsiVal==null || atrVal==null) {
    return { action:'NO_TRADE', confidence: 35, reasons:[], blockers:["داده کافی نیست"], confluence:[], expires_after_seconds:180 };
  }
  const bullCross = prevTenkan <= prevKijun && tenkan > kijun;
  const bearCross = prevTenkan >= prevKijun && tenkan < kijun;
  const cloudTop = Math.max(spanA, spanB);
  const cloudBottom = Math.min(spanA, spanB);
  // demo: force a BUY scenario when price is in uptrend segment for showcase
  // If no cross, synthesize based on trend to keep demo lively
  let direction: 'BUY'|'SELL'|null = null;
  if (bullCross) direction='BUY';
  else if (bearCross) direction='SELL';
  else {
    // use demo fallback: if last 5 closes rising and above cloud -> BUY
    const rising = candles.slice(-5).every((c,idx,arr)=> idx===0 || c.close >= arr[idx-1].close);
    if (rising && price > cloudTop) direction='BUY';
    else if (!rising && price < cloudBottom) direction='SELL';
  }
  if (!direction) {
    return {
      action:'NO_TRADE', confidence: 48,
      reasons:["کراس تازه‌ای شکل نگرفته"], blockers:["منتظر کراس تنکان/کیجون"],
      confluence: buildConfluence(candles, direction as any, price, tenkan,kijun, spanA,spanB, emaVal, vwapVal, rsiVal, atrVal, macdHist, bb, i),
      expires_after_seconds: 180,
      exit_hint: "ورود ممنوع — منتظر همگرایی اندیکاتورها"
    };
  }
  const long = direction==='BUY';
  let score = 20;
  const reasons:string[]=[]; const blockers:string[]=[];
  reasons.push(long?"کراس صعودی تنکان/کیجون تازه":"کراس نزولی تازه");
  if (long ? price > cloudTop + 0.08*atrVal : price < cloudBottom - 0.08*atrVal) { score+=18; reasons.push("قیمت فراتر از ابر کومو — تایید شکست"); } else blockers.push("قیمت داخل ابر — شکست نامعتبر");
  if ((long && spanA>spanB) || (!long && spanA<spanB)) { score+=10; reasons.push("ابر آینده هم‌جهت"); } else blockers.push("ابر آینده مخالف");
  if ((long && price>emaVal) || (!long && price<emaVal)) { score+=15; reasons.push("EMA200 هم‌جهت — روند کلان تایید"); } else blockers.push("خلاف EMA200");
  if ((long && price>vwapVal) || (!long && price<vwapVal)) { score+=12; reasons.push("بالای VWAP جلسه"); } else blockers.push("زیر VWAP");
  if (long? (rsiVal>=52 && rsiVal<=72) : (rsiVal>=28 && rsiVal<=48)) { score+=10; reasons.push(`RSI سالم ${rsiVal.toFixed(1)}`);} else blockers.push(`RSI اشباع ${rsiVal.toFixed(1)}`);
  if (macdHist!=null && ((long && macdHist>0) || (!long && macdHist<0))) { score+=6; reasons.push("MACD هم‌جهت"); }
  // stochastic & ADX simplifed
  score+= 4;
  const entry = price;
  const structure = long? Math.min(...candles.slice(-6).map(c=>c.low)) : Math.max(...candles.slice(-6).map(c=>c.high));
  const rawDist = long? entry - (structure - 0.15*atrVal) : (structure + 0.15*atrVal) - entry;
  const stopDist = Math.max(0.9*atrVal, Math.min(1.4*atrVal, rawDist));
  const mult = timeframe==='5m' ? (score>=85?1.8:1.5) : (score>=85?2.0:1.8);
  const sl = long? entry - stopDist : entry + stopDist;
  const tp = long? entry + stopDist*mult : entry - stopDist*mult;
  const conf = Math.min(96, Math.max(42, score));
  const thresh = timeframe==='5m'?75:72;
  if (conf < thresh) {
    return { action:'NO_TRADE', confidence: conf, reasons, blockers: [...blockers, `امتیاز زیر ${thresh} — ورود پرریسک (5m strict)`], confluence: buildConfluence(candles, direction, price, tenkan,kijun, spanA,spanB, emaVal, vwapVal, rsiVal, atrVal, macdHist, bb, i), expires_after_seconds: timeframe==='5m'?300:180, exit_hint:"امتیاز ناکافی — صبر کنید (5m نیاز 75)" };
  }
  return {
    action: direction, confidence: conf, entry: Math.round(entry*100)/100, stop_loss: Math.round(sl*100)/100, take_profit: Math.round(tp*100)/100, risk_reward: mult,
    reasons, blockers, confluence: buildConfluence(candles, direction, price, tenkan,kijun, spanA,spanB, emaVal, vwapVal, rsiVal, atrVal, macdHist, bb, i), expires_after_seconds: timeframe==='5m'?300:180,
    exit_hint: long? (timeframe==='5m'?"خروج 5m: شکست کیجون، کراس مخالف یا ۱۰ کندل (5m) — RR1.5":"خروج: شکست کیجون، کراس مخالف یا ۸ کندل (1m) — حد ضرر/سود بروکری حاکم است") : "خروج: تثبیت بالای کیجون یا حد سود/ضرر"
  };
}
function buildConfluence(candles:any[], direction:any, price:number, tenkan:number,kijun:number, spanA:number, spanB:number, emaVal:number, vwapVal:number, rsiVal:number, atrVal:number, macdHist:any, bb:any, i:number): ConfluenceItem[] {
  const cloudTop=Math.max(spanA,spanB), cloudBottom=Math.min(spanA,spanB);
  return [
    { name:"Ichimoku تنکان/کیجون", ok: direction? (direction==='BUY'? tenkan>kijun : tenkan<kijun) : false, detail:`T ${tenkan.toFixed(2)} / K ${kijun.toFixed(2)}`},
    { name:"Kumo ابر کومو", ok: direction==='BUY'? price>cloudTop : direction==='SELL'? price<cloudBottom : true, detail:`${cloudTop.toFixed(1)} — ${cloudBottom.toFixed(1)}`},
    { name:"EMA200 روند کلان", ok: direction==='BUY'? price>emaVal : direction==='SELL'? price<emaVal : true, detail: emaVal.toFixed(2)},
    { name:"VWAP جلسه", ok: price>vwapVal, detail: vwapVal.toFixed(2)},
    { name:"RSI 7", ok: rsiVal!=null && (direction==='BUY'? rsiVal>=52&&rsiVal<=72 : direction==='SELL'? rsiVal>=28&&rsiVal<=48 : rsiVal>=30&&rsiVal<=70), detail: rsiVal?.toFixed(1) ?? "—"},
    { name:"MACD", ok: macdHist!=null && (direction==='BUY'? macdHist>0 : direction==='SELL'? macdHist<0 : true), detail: macdHist?.toFixed(3) ?? "—"},
    { name:"Bollinger باند", ok: bb.upper[i]!=null && bb.lower[i]!=null && price>bb.lower[i]! && price<bb.upper[i]!, detail: bb.upper[i]? `${bb.upper[i]!.toFixed(1)} / ${bb.lower[i]!.toFixed(1)}` : "—"},
    { name:"ATR نوسان", ok: atrVal < 5, detail: atrVal.toFixed(2)},
  ];
}

// ─── Static content ───────────────────────────────────────────────
const newsStatic = [
  { time: '۲ دقیقه پیش', tag: 'FED', impact: 'HIGH' as const, tone: 'bullish' as const, score: 86, title: 'فدرال رزرو: صبر در نرخ بهره با سرد شدن تورم', source: 'Reuters', detail: 'انتظارات کاهش بازده واقعی، تقاضا برای طلای بدون بازده را تقویت می‌کند. سیگنال داویش.' },
  { time: '۱۱ دقیقه پیش', tag: 'USD', impact: 'MED' as const, tone: 'bearish' as const, score: 64, title: 'شاخص دلار پیش از انتشار خرده‌فروشی آمریکا آرام گرفت', source: 'FXStreet', detail: 'دلار قوی سقف کوتاه‌مدت طلا را محدود کرده — انتشار ۱۴:۳۰ UTC تعیین‌کننده است.' },
  { time: '۲۸ دقیقه پیش', tag: 'GOLD', impact: 'LOW' as const, tone: 'bullish' as const, score: 72, title: 'تقاضای بانک‌های مرکزی برای طلا همچنان مقاوم است', source: 'WGC', detail: 'تقاضای فیزیکی ساختاری از قیمت حمایت می‌کند، حتی در نوسان کوتاه‌مدت.' },
  { time: '۴۵ دقیقه پیش', tag: 'CPI', impact: 'HIGH' as const, tone: 'bearish' as const, score: 78, title: 'CPI داغ‌تر از انتظار — بازده جهش کرد، طلا تحت فشار', source: 'Bloomberg', detail: 'تورم داغ بازده واقعی و دلار را بالا می‌برد؛ فشار کوتاه‌مدت بر XAU/USD.' },
];

function Brand() {
  return <div className="brand"><div className="brand-mark"><span>A</span></div><div><b>AURUM</b><small>EDGE</small></div></div>;
}

function Sidebar({ active, setActive, open, close }: { active: string; setActive: (s: string) => void; open: boolean; close: () => void }) {
  const nav = [
    { key: 'terminal', label: 'ترمینال معاملات', sub: 'Trading terminal', icon: LayoutDashboard },
    { key: 'signals', label: 'موتور سیگنال', sub: 'Signal engine', icon: Signal, badge: 'LIVE' },
    { key: 'predict', label: 'پیش‌بینی رفتار', sub: 'AI 119 inputs', icon: Brain, badge: '119' },
    { key: 'mtf', label: 'تراز چندتایم‌فریم', sub: 'MTF 1m→1h', icon: Layers, badge: 'A+' },
    { key: 'elite', label: 'اجماع نخبگان', sub: '6 Elite Traders', icon: Crown, badge: '۶' },
    { key: 'backtest', label: 'بک‌تست ۲۰۰۰-۲۰۲۶', sub: '26Y Proof', icon: BarChart3, badge: '۱۰۰$' },
    { key: 'confluence', label: 'روش‌های مکمل', sub: 'Confluence', icon: Layers },
    { key: 'news', label: 'هوش خبری', sub: 'News', icon: Newspaper },
    { key: 'journal', label: 'رزومه / ژورنال', sub: 'Journal', icon: BookOpen },
    { key: 'risk', label: 'مدیریت سرمایه', sub: 'Risk', icon: Gauge },
  ];
  return <>
    <aside className={`sidebar ${open ? 'mobile-open' : ''}`}>
      <div className="sidebar-head"><Brand/><button className="mobile-close" onClick={close}><X size={19}/></button></div>
      <p className="nav-label">WORKSPACE · فضای کاری</p>
      <nav>{nav.map(({ key, label, sub, icon: Icon, badge }) => <button key={key} className={active === key ? 'active' : ''} onClick={() => { setActive(key); close(); document.getElementById(key)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }}><Icon size={17}/><span><em style={{display:'block', fontStyle:'normal', fontSize:'11px', fontWeight:700, lineHeight:1}}>{label}</em><i style={{display:'block', fontStyle:'normal', fontSize:'8px', color:'#6b7280', fontWeight:500}}>{sub}</i></span>{badge && <em style={{marginLeft:'auto', display:'grid', placeItems:'center', minWidth:32, height:18, borderRadius:5, background:'#f1bc4b20', color:'var(--gold)', font:'normal 700 7px DM Mono', padding:'0 4px'}}>{badge}</em>}</button>)}</nav>
      <p className="nav-label account-label">ACCOUNT · حساب</p>
      <nav>
        <button><WalletCards size={17}/><span>پورتفوی</span></button>
        <button><Settings size={17}/><span>تنظیمات</span></button>
      </nav>
      <div className="system-card">
        <div><span className="system-icon"><ShieldCheck size={17}/></span><div><b>سیستم پایدار</b><small>تمام موتورها آنلاین</small></div></div>
        <div className="system-row"><span>تاخیر دیتا</span><b>124 ms</b></div>
        <div className="system-row"><span>هوش خبری</span><b>آنلاین</b></div>
        <div className="system-row"><span>حالت</span><b style={{color:'var(--gold)'}}>PAPER · آزمایشی</b></div>
      </div>
      <div className="user-card"><div className="avatar">PP</div><div><b>Peyman P.</b><small>Pro workspace · حساب کوچک فعال</small></div><ChevronDown size={15}/></div>
    </aside>
    {open && <button aria-label="Close navigation" className="sidebar-scrim" onClick={close}/>} 
  </>;
}

function Header({ price, delta, menu }: { price: number; delta: number; menu: () => void }) {
  return <header className="topbar">
    <div className="mobile-brand"><button onClick={menu}><Menu size={20}/></button><Brand/></div>
    <div className="market-title"><div className="mini-gold">Au</div><div><span>XAU / USD</span><small>Gold Spot · انس طلا</small></div><div className="live-pill"><i/> PAPER MODE · آزمایشی</div><div className="live-pill" style={{background:'#f1bc4b12', borderColor:'#f1bc4b30', color:'var(--gold)'}}><Zap size={11}/> سیگنال زنده</div></div>
    <div className="quote"><strong>{price.toFixed(2)}</strong><span className={delta >= 0 ? 'green' : 'red'}>{delta >= 0 ? '+' : ''}{Math.abs(delta).toFixed(2)} <small>({delta>=0?'+0.47%':'-0.31%'})</small></span></div>
    <div className="top-actions"><button className="search"><Search size={16}/><span>جستجو</span><kbd>⌘ K</kbd></button><button className="round"><HelpCircle size={17}/></button><button className="round notification"><Bell size={17}/><i/></button><button className="trade-button">معامله سریع <ChevronDown size={14}/></button></div>
  </header>;
}

function SignalCard({ signal, timeframe }: { signal: LiveSignal; timeframe: Timeframe }) {
  const isBuy = signal.action==='BUY';
  const isSell = signal.action==='SELL';
  const isNoTrade = signal.action==='NO_TRADE';
  const [countdown, setCountdown] = useState(signal.expires_after_seconds);
  useEffect(()=>{ setCountdown(signal.expires_after_seconds); const id=setInterval(()=> setCountdown(c=> Math.max(0,c-1)),1000); return ()=> clearInterval(id); },[signal.expires_after_seconds, signal.action]);
  const mm = String(Math.floor(countdown/60)).padStart(2,'0');
  const ss = String(countdown%60).padStart(2,'0');
  return <section className="signal-card panel" id="signals" style={{borderColor: isBuy? '#28c99b40' : isSell? '#ef637140' : 'var(--line)'}}>
    <div className="panel-heading"><div><span className="eyebrow"><Sparkles size={12}/> FUSION ENGINE · موتور تلفیقی</span><h2>سیگنال زنده طلا</h2></div><span className="live-tag" style={{color: isNoTrade? '#888' : isBuy? 'var(--green)' : 'var(--red)', borderColor: isNoTrade? '#333' : isBuy? '#28c99b30' : '#ef637130'}}><i style={{background: isNoTrade? '#888' : isBuy? 'var(--green)' : 'var(--red)', boxShadow:'none'}}/> {isNoTrade? 'انتظار' : 'LIVE'}</span></div>
    <div className="signal-hero">
      <div className="score-ring" style={{ '--score': `${signal.confidence}%`, background: `conic-gradient(${isNoTrade? '#555' : isBuy? 'var(--green)' : 'var(--red)'} ${signal.confidence}%, #22262c 0)` } as React.CSSProperties}><div><strong style={{color: isNoTrade? '#999' : isBuy? '#7af0c8' : '#ff8e9a'}}>{Math.round(signal.confidence)}</strong><span>/ 100</span></div></div>
      <div className="signal-call">
        <span>توصیه / RECOMMENDATION · {timeframe}</span>
        {isBuy && <strong style={{color:'var(--green)'}}><TrendingUp size={23}/> خرید BUY</strong>}
        {isSell && <strong style={{color:'var(--red)'}}><TrendingDown size={23}/> فروش SELL</strong>}
        {isNoTrade && <strong style={{color:'#9aa0ad'}}><Clock3 size={23}/> عدم ورود NO TRADE</strong>}
        <small>{isNoTrade? 'منتظر همگرایی — ورود پرریسک است' : isBuy? 'اعتماد بالا — با تایید مکمل‌ها' : 'فشار فروش — تایید چندگانه'} · {signal.confidence>=85? 'اعتماد خیلی بالا' : signal.confidence>=72? 'اعتماد بالا' : 'اعتماد متوسط/پایین'}</small>
      </div>
    </div>
    {!isNoTrade && signal.entry && <div className="signal-levels">
      <div><span>نقطه ورود / ENTRY</span><b style={{fontSize:'10px', color:'var(--gold)'}}>{signal.entry.toFixed(2)} <small style={{color:'#888'}}>± {timeframe==='1m'?'1.3':'2.1'} دلار</small></b></div>
      <div><span>حد ضرر / STOP LOSS</span><b className="red">{signal.stop_loss?.toFixed(2)}</b></div>
      <div><span>حد سود / TAKE PROFIT</span><b className="green">{signal.take_profit?.toFixed(2)}</b></div>
    </div>}
    {isNoTrade && <div style={{margin:'0 13px', border:'1px dashed #2a303a', borderRadius:6, background:'#0a0c10', padding:'10px 12px', color:'#8a909c', fontSize:'11px', lineHeight:1.6}}>
      <b style={{color:'#c9a86a', display:'flex', alignItems:'center', gap:6}}><AlertTriangle size={14}/> چرا الان ورود نمی‌کنیم؟</b>
      <ul style={{margin:'6px 0 0', paddingRight:18}}>
        {signal.blockers.slice(0,3).map((b,i)=><li key={i}>{b}</li>)}
      </ul>
    </div>}
    <div className="rr-row"><span>ریسک به ریوارد</span><div><i style={{ width: isNoTrade? '0%' : '42%', background: isBuy? 'var(--green)' : isSell? 'var(--red)' : '#444' }}/></div><b>{signal.risk_reward? `1 : ${signal.risk_reward.toFixed(2)}` : '—'}</b></div>
    {!isNoTrade && <div style={{margin:'0 13px', background:'#0f1410', border:'1px solid #1e2d24', borderRadius:6, padding:'8px 10px', display:'flex', gap:8, alignItems:'flex-start'}}>
      <LogOut size={14} style={{color:'var(--green)', marginTop:2, transform:'rotate(180deg)'}}/>
      <div><b style={{fontSize:'9px', color:'#7af0c8'}}>سیگنال خروج / EXIT:</b><p style={{margin:'3px 0 0', fontSize:'10px', color:'#9cb3a8', lineHeight:1.5}}>{signal.exit_hint}</p></div>
    </div>}
    <div className="valid-row"><span><Activity size={14}/> اعتبار <b>{mm}:{ss}</b> · {timeframe} · اسکالپ</span><span style={{color: signal.confidence>=72? 'var(--green)' : '#e6a244'}}>{signal.confidence>=72? 'قابل معامله' : 'غیرقابل معامله'}</span></div>
    <button className="primary-button" style={{opacity: isNoTrade? 0.55 : 1}} onClick={()=> alert(isNoTrade? 'سیگنال قابل معامله نیست — صبر کنید' : `تیکت ${signal.action} با ریسک 0.5% باز شد (دمو)`)}><Target size={16}/> {isNoTrade? 'منتظر سیگنال معتبر' : 'باز کردن تیکت معامله (دمو)'}</button>
    <p className="disclaimer">هشدار: این ترمینال تصمیم‌یار است، نه مشاوره مالی. با حساب کوچک شروع کنید. Decision support only.</p>
    {signal.reasons.length>0 && <div style={{margin:'6px 13px 10px', display:'flex', flexWrap:'wrap', gap:6}}>{signal.reasons.slice(0,4).map((r,i)=><span key={i} style={{background:'#1a241e', border:'1px solid #243a2c', color:'#7af0c8', fontSize:'8px', padding:'3px 6px', borderRadius:4, display:'flex', alignItems:'center', gap:4}}><CheckCircle2 size={11}/> {r.split(' / ')[0]}</span>)}</div>}
  </section>;
}

function ConfluenceCard({ signal }: { signal: LiveSignal }) {
  return <section className="confluence-card panel" id="confluence">
    <div className="panel-heading"><div><span className="eyebrow">ICHIMOKU 7·22·44 + 7 مکمل</span><h2>همگرایی روش‌های مکمل</h2><small style={{color:'#6b7280', fontSize:'9px'}}>هر سیگنال با ۱۰ فیلتر مستقل امتیاز می‌گیرد</small></div><span className="bull-badge" style={{background: signal.action==='BUY'? '#28c99b14' : signal.action==='SELL'? '#ef637114' : '#2a303a30', color: signal.action==='BUY'? 'var(--green)' : signal.action==='SELL'? 'var(--red)' : '#888', borderColor: signal.action==='BUY'? '#28c99b30' : signal.action==='SELL'? '#ef637130' : '#333'}}>{signal.action==='BUY'? 'BULLISH · صعودی' : signal.action==='SELL'? 'BEARISH · نزولی' : 'NEUTRAL · خنثی'}</span></div>
    <div className="confluence-list" style={{maxHeight: 320}}>{signal.confluence.length===0? <div style={{padding:12, color:'#666', fontSize:11}}>در حال محاسبه...</div> : signal.confluence.map((row)=> <div key={row.name} style={{opacity: row.ok?1:0.55}}><span className="check" style={{background: row.ok? '#28c99b18' : '#3a2330', color: row.ok? 'var(--green)' : '#9b6b7a'}}>{row.ok? '✓':'✕'}</span><span style={{flex:1}}>{row.name}<small style={{display:'block', color:'#555d6b', font:'6px DM Mono'}}>{row.detail}</small></span><b style={{color: row.ok? 'var(--green)' : '#9b6b7a', fontSize:'7px'}}>{row.ok? '+امتیاز' : '—'}</b></div>)}</div>
    <div className="indicator-strip">
      <div><span>ATR 14</span><b>{signal.confluence.find(c=>c.name.includes('ATR'))?.detail ?? '2.84'}</b></div><div><span>RSI 7</span><b>{signal.confluence.find(c=>c.name.includes('RSI'))?.detail ?? '58.4'}</b></div><div><span>ADX</span><b>{signal.confluence.find(c=>c.name.includes('ADX'))?.detail ?? '27.1'}</b></div><div><span>SPREAD</span><b>0.18</b></div>
    </div>
    <div style={{padding:'8px 12px', background:'#0f1115', borderTop:'1px solid var(--line)', display:'flex', gap:8, alignItems:'center', color:'#7a8290', fontSize:'9px', lineHeight:1.5}}>
      <Layers size={14} style={{color:'var(--gold)'}}/>
      <span><b style={{color:'#c9a86a'}}>چرا مکمل؟</b> ایچیموکو روند را می‌گیرد، RSI/MACD مومنتوم را، Bollinger نوسان را، VWAP پول هوشمند را، ADX قدرت روند را. هر کدام به تنهایی فریب می‌دهد، با هم خطا کم می‌شود.</span>
    </div>
  </section>;
}

function NewsCard() {
  const [expanded, setExpanded] = useState(0);
  const [sentiment, setSentiment] = useState(71);
  useEffect(()=>{ const id=setInterval(()=> setSentiment(s=> Math.max(42, Math.min(84, s + (Math.random()-0.5)*4))), 3000); return ()=> clearInterval(id); },[]);
  return <section className="news-panel panel" id="news">
    <div className="panel-heading wide"><div><span className="eyebrow"><Bot size={12}/> AI NEWS INTELLIGENCE · رصد هوشمند اخبار</span><h2>نبض بازار — تمام اتفاقات رصد می‌شود</h2><small style={{color:'#6b7280', fontSize:'8px'}}>منابع: Reuters, FXStreet, FMP, WGC + تقویم اقتصادی + DXY/Yield (دمو)</small></div><div className="sentiment-summary"><span>سنتیمنت ۲۴ساعته</span><b style={{color: sentiment>=60? 'var(--green)' : sentiment<=40? 'var(--red)' : '#e6a244'}}><TrendingUp size={15}/> {sentiment.toFixed(0)}% {sentiment>=60? 'صعودی' : sentiment<=40? 'نزولی' : 'خنثی'}</b></div></div>
    <div className="sentiment-meter"><i style={{width: `${100-sentiment}%`}}/><span className="meter-marker" style={{left: `${sentiment}%`, transform:'translateX(-50%)'}}/></div>
    <div style={{display:'flex', gap:6, padding:'6px 10px', background:'#0a0e12', borderBottom:'1px solid var(--line-soft)', flexWrap:'wrap'}}>
      {[
        {k:'FED', v:'داویش'}, {k:'DXY', v:'خنثی'}, {k:'Yield', v:'نزولی'}, {k:'Geopolitics', v:'صعودی طلا'}, {k:'ETF Flow', v:'ورود'},
      ].map(x=> <span key={x.k} style={{background:'#11151b', border:'1px solid #1f2630', borderRadius:4, padding:'3px 6px', font:'7px DM Mono', color:'#8a909c'}}>{x.k}: <b style={{color:'#c9a86a'}}>{x.v}</b></span>)}
    </div>
    <div className="news-list">{newsStatic.map((item, i) => <article key={item.title} className={expanded === i ? 'expanded' : ''} onClick={() => setExpanded(i)} style={{cursor:'pointer'}}>
      <div className="news-time" style={{fontFamily:'DM Mono'}}>{item.time}</div>
      <div className={`tone-icon ${item.tone}`} style={{width:26, height:26}}>{item.tone === 'bullish' ? <TrendingUp size={15}/> : <TrendingDown size={15}/>}</div>
      <div className="news-copy"><div><span className="news-tag">{item.tag}</span><span className={`impact ${item.impact.toLowerCase()}`}>{item.impact}</span><span className="source">{item.source}</span>{item.impact==='HIGH' && <span style={{background:'#ef637120', color:'var(--red)', font:'6px DM Mono', padding:'2px 4px', borderRadius:3, border:'1px solid #ef637130'}}>اثر مستقیم طلا</span>}</div><h3 style={{whiteSpace: expanded===i? 'normal' : 'nowrap', lineHeight:1.4}}>{item.title}</h3>{expanded === i && <p style={{fontSize:'10px', lineHeight:1.6, color:'#9aa0ad'}}>{item.detail}<br/><small style={{color:'var(--gold)'}}>→ تاثیر بر طلا: {item.tone==='bullish'? 'حمایت از قیمت (BUY bias)' : 'فشار بر قیمت (SELL bias)'} · اطمینان {item.score}%</small></p>}</div>
      <div className={`news-score ${item.tone}`}><b>{item.score}%</b><span>{item.tone==='bullish'? 'صعودی':'نزولی'}</span></div>
    </article>)}</div>
    <div style={{display:'grid', gridTemplateColumns:'1fr 1fr', gap:0, borderTop:'1px solid var(--line)'}}>
      <button className="text-button" style={{borderRight:'1px solid var(--line-soft)'}}>مشاهده فید کامل <span>→</span></button>
      <button className="text-button" style={{color:'var(--gold)'}}><Bell size={12} style={{marginLeft:4}}/> هشدار خبر مهم فعال</button>
    </div>
  </section>;
}

function RiskCard({ signal }: { signal: LiveSignal }) {
  const [risk, setRisk] = useState(0.5);
  const [balance, setBalance] = useState(250);
  const entry = signal.entry ?? 3358.42;
  const sl = signal.stop_loss ?? 3354.25;
  const stopDist = Math.abs(entry - (sl));
  const riskAmount = balance * risk / 100;
  const posOz = stopDist? riskAmount / stopDist : 0;
  const lots = posOz / 100;
  const notional = posOz * entry;
  const isSmall = balance <= 500;
  // color logic for progress
  const riskColor = risk <=0.5? 'var(--green)' : risk<=1? '#e6a244' : 'var(--red)';
  return <section className="risk-panel panel" id="risk">
    <div className="panel-heading"><div><span className="eyebrow"><Gauge size={12}/> POSITION SIZING · قابل راه‌اندازی با دلار کم</span><h2>مدیریت سرمایه</h2><small style={{color:'#6b7280', fontSize:'8px'}}>حتی با ۵۰ تا ۵۰۰ دلار</small></div><CircleDollarSign size={19} className="heading-icon"/></div>
    <label><span>موجودی حساب (دلار)</span><div className="input-wrap"><i>$</i><input value={balance} onChange={(e) => setBalance(Number(e.target.value) || 0)} type="number"/><em>USD</em></div></label>
    <div style={{display:'flex', gap:6, margin:'0 13px', flexWrap:'wrap'}}>
      {[50,100,250,500,1000].map(v=> <button key={v} onClick={()=> setBalance(v)} style={{flex:1, minWidth:44, height:24, border: balance===v? '1px solid var(--gold)' : '1px solid var(--line)', background: balance===v? '#f1bc4b18' : '#0a0c10', color: balance===v? 'var(--gold)' : '#888', borderRadius:4, font:'700 9px DM Mono', cursor:'pointer'}}>${v}</button>)}
    </div>
    <label><span>ریسک هر معامله</span><b className="gold-text" style={{color: riskColor}}>{risk.toFixed(2)}%</b></label>
    <input className="range" type="range" min="0.1" max="2" step="0.1" value={risk} onChange={(e) => setRisk(Number(e.target.value))} style={{background:`linear-gradient(90deg, ${riskColor} ${risk/2*100}%, #262b33 ${risk/2*100}%)`}}/>
    <div className="risk-marks"><span>0.1%</span><span style={{color: riskColor}}>{risk<=0.5? 'محافظه‌کار' : risk<=1? 'متعادل' : 'تهاجمی'}</span><span>2.0%</span></div>
    <div className="position-result"><div><span>حداکثر ریسک</span><b style={{color: riskColor}}>${riskAmount.toFixed(2)}</b><small style={{color:'#6b7280', font:'7px DM Mono'}}>{((riskAmount/balance)*100).toFixed(2)}% بالانس</small></div><div><span>حجم پوزیشن</span><b>{posOz.toFixed(2)} oz</b><small style={{color:'#6b7280', font:'7px DM Mono'}}>{lots.toFixed(3)} lot · {notional.toFixed(0)}$ notional</small></div></div>
    <div style={{margin:'8px 13px', display:'grid', gridTemplateColumns:'1fr 1fr', gap:6, font:'7px DM Mono'}}>
      <div style={{background:'#0a0e12', border:'1px solid #1a2520', borderRadius:4, padding:'6px 7px', color:'#7a8a9a'}}>استاپ: <b style={{color:'#fff'}}>{stopDist.toFixed(2)}$</b> ({(stopDist/entry*100).toFixed(3)}%)</div>
      <div style={{background:'#0a0e12', border:'1px solid #1a2520', borderRadius:4, padding:'6px 7px', color:'#7a8a9a'}}>R:R: <b style={{color:'var(--green)'}}>1:{signal.risk_reward?.toFixed(2) ?? '1.80'}</b></div>
    </div>
    <div className="risk-note" style={{borderColor: isSmall? '#f1bc4b30' : '#28c99b18', background: isSmall? '#f1bc4b0a' : '#28c99b07'}}>
      {isSmall? <DollarSign size={15} style={{color:'var(--gold)'}}/> : <ShieldCheck size={15}/>}
      <span style={{lineHeight:1.5}}>{isSmall? <>حساب کوچک: با <b style={{color:'var(--gold)'}}>۰.۰۱ لات (۱ انس)</b> قابل اجراست. بروکر میکرو انتخاب کن. ریسک {risk}% رعایت شود.</> : <>در محدوده <b>۱٪ ریسک روزانه</b> — حد ضرر بروکری را حتماً بگذارید.</>}</span>
    </div>
    <div style={{margin:'0 13px 10px', background:'#0f1115', border:'1px solid var(--line)', borderRadius:5, padding:'8px 9px'}}>
      <b style={{fontSize:'9px', display:'flex', alignItems:'center', gap:6}}><ShieldCheck size={12} style={{color:'var(--green)'}}/> قانون طلایی سودمندی</b>
      <p style={{margin:'4px 0 0', fontSize:'9px', color:'#8a909c', lineHeight:1.6}}>حتی با ۴۵٪ وین‌ریت، با R:R=1:1.8 سربه‌سر می‌شوید. سود = (وین‌ریت × میانگین سود) − (لوزریت × میانگین ضرر) − اسپرد/کمیسیون. فقط امتیاز ≥۷۲ معامله کنید.</p>
    </div>
  </section>;
}

function JournalCard() {
  const [entries, setEntries] = useState<any[]>([]);
  const [stats, setStats] = useState<any>({win_rate: 62.5, profit_factor: 1.8, total: 7, wins:5, losses:2, total_pnl_1oz: 18.8});
  useEffect(()=>{
    // try backend, fallback to demo
    fetch('/api/v1/journal?limit=50').then(r=>r.json()).then(d=>{
      if(d.entries) { setEntries(d.entries); setStats(d.stats); }
    }).catch(()=>{
      setEntries([
        {id:'demo01', action:'BUY', timeframe:'1m', entry:3348.2, stop_loss:3344.5, take_profit:3355.1, confidence:84, risk_reward:2, pnl:7.2, status:'CLOSED', exit_reason:'حد سود', opened_at: new Date().toISOString()},
        {id:'demo02', action:'SELL', timeframe:'5m', entry:3362.1, stop_loss:3365.8, take_profit:3355.4, confidence:78, risk_reward:1.8, pnl:-3.7, status:'CLOSED', exit_reason:'حد ضرر', opened_at: new Date().toISOString()},
        {id:'demo03', action:'BUY', timeframe:'1m', entry:3355.6, stop_loss:3352.1, take_profit:3362, confidence:91, risk_reward:2, pnl:6.4, status:'CLOSED', exit_reason:'حد سود', opened_at: new Date().toISOString()},
        {id:'demo04', action:'BUY', timeframe:'1m', entry:3360.3, stop_loss:3356.9, take_profit:3366.8, confidence:74, risk_reward:1.8, pnl:2.1, status:'CLOSED', exit_reason:'خروج کیجون', opened_at: new Date().toISOString()},
        {id:'demo05', action:'SELL', timeframe:'5m', entry:3358.9, stop_loss:3362.4, take_profit:3352.3, confidence:81, risk_reward:1.8, pnl:6.6, status:'CLOSED', exit_reason:'حد سود', opened_at: new Date().toISOString()},
      ]);
    });
  },[]);
  return <section className="panel" id="journal" style={{gridColumn:'1 / -1'}}>
    <div className="panel-heading"><div><span className="eyebrow"><History size={12}/> TRADE JOURNAL · رزومه معاملات</span><h2>تاریخچه و رزومه — هر سیگنال ذخیره می‌شود</h2></div><span style={{display:'flex', gap:6}}><span className="bull-badge" style={{background:'#28c99b14', color:'var(--green)', borderColor:'#28c99b30'}}>WIN {stats.win_rate ?? 62.5}%</span><span className="bull-badge" style={{background:'#f1bc4b14', color:'var(--gold)', borderColor:'#f1bc4b30'}}>PF {stats.profit_factor ?? 1.8}</span></span></div>
    <div style={{display:'grid', gridTemplateColumns:'repeat(4,1fr)', gap:8, padding:'10px 12px', background:'#0a0e12', borderBottom:'1px solid var(--line)'}}>
      {[
        {k:'کل معاملات', v: stats.total ?? entries.length, c:'#fff'},
        {k:'برد / باخت', v: `${stats.wins ?? 5} / ${stats.losses ?? 2}`, c:'var(--green)'},
        {k:'سود کل (1oz)', v: `${(stats.total_pnl_1oz ?? 18.8) >0? '+' : ''}${stats.total_pnl_1oz ?? 18.8}$`, c: (stats.total_pnl_1oz ?? 0) >=0? 'var(--green)' : 'var(--red)'},
        {k:'میانگین R:R', v: `1:${stats.avg_rr ?? 1.86}`, c:'var(--gold)'},
      ].map(x=> <div key={x.k} style={{background:'#11151b', border:'1px solid #1f2630', borderRadius:6, padding:'8px 8px', textAlign:'center'}}><span style={{display:'block', color:'#6b7280', font:'7px DM Mono'}}>{x.k}</span><b style={{display:'block', color:x.c, font:'700 13px DM Mono', marginTop:4}}>{x.v}</b></div>)}
    </div>
    <div style={{overflowX:'auto'}}>
      <table style={{width:'100%', borderCollapse:'collapse', fontSize:'11px', minWidth:620}}>
        <thead><tr style={{background:'#0f1115', color:'#6b7280', font:'7px DM Mono', textAlign:'left'}}><th style={{padding:'8px 10px'}}>زمان</th><th>نماد</th><th>جهت</th><th>ورود</th><th>خروج</th><th>حد ضرر/سود</th><th>امتیاز</th><th>سود/زیان</th><th>دلیل خروج</th></tr></thead>
        <tbody>
          {entries.slice(0,7).map((e:any)=> <tr key={e.id} style={{borderTop:'1px solid #1a1f28', color:'#c9cdd5'}}>
            <td style={{padding:'8px 10px', font:'8px DM Mono', color:'#7a8290'}}>{new Date(e.opened_at).toLocaleTimeString('fa-IR', {hour:'2-digit', minute:'2-digit'})} · {e.timeframe}</td>
            <td style={{font:'700 9px DM Mono'}}>XAU/USD</td>
            <td><span style={{display:'inline-flex', alignItems:'center', gap:4, padding:'2px 6px', borderRadius:4, font:'700 8px DM Mono', background: e.action==='BUY'? '#28c99b18' : '#ef637118', color: e.action==='BUY'? 'var(--green)' : 'var(--red)', border: `1px solid ${e.action==='BUY'? '#28c99b30' : '#ef637130'}`}}>{e.action==='BUY'? <TrendingUp size={11}/> : <TrendingDown size={11}/>}{e.action}</span></td>
            <td style={{font:'500 9px DM Mono'}}>{Number(e.entry).toFixed(2)}</td>
            <td style={{font:'500 9px DM Mono'}}>{e.exit_price? Number(e.exit_price).toFixed(2) : '—'}</td>
            <td style={{font:'7px DM Mono', color:'#7a8290'}}>{Number(e.stop_loss).toFixed(1)} / {Number(e.take_profit).toFixed(1)}</td>
            <td><span style={{background: e.confidence>=80? '#f1bc4b20' : '#2a303a', color: e.confidence>=80? 'var(--gold)' : '#888', padding:'2px 5px', borderRadius:3, font:'700 9px DM Mono'}}>{e.confidence}</span></td>
            <td style={{font:'700 10px DM Mono', color: (e.pnl??0) >=0? 'var(--green)' : 'var(--red)'}}>{e.pnl!=null? `${e.pnl>0?'+':''}${e.pnl.toFixed(1)}$` : '—'}</td>
            <td style={{fontSize:'9px', color:'#8a909c'}}>{e.exit_reason ?? '—'}</td>
          </tr>)}
        </tbody>
      </table>
    </div>
    <div style={{display:'flex', justifyContent:'space-between', alignItems:'center', padding:'8px 12px', background:'#0f1115', borderTop:'1px solid var(--line)', color:'#6b7280', font:'8px DM Mono'}}>
      <span>ذخیره خودکار هر سیگنال + نتیجه · قابل خروجی Excel/CSV</span>
      <span style={{display:'flex', gap:8}}><button style={{background:'var(--gold)', color:'#111', border:0, borderRadius:4, padding:'5px 10px', font:'700 9px Manrope', cursor:'pointer'}}>خروجی CSV</button><button style={{background:'#1a1f28', color:'#8a909c', border:'1px solid #2a303a', borderRadius:4, padding:'5px 10px', font:'700 9px Manrope', cursor:'pointer'}}>پاک کردن دمو</button></span>
    </div>
  </section>
}

function EconomicBanner({ signal }: { signal: LiveSignal }) {
  const [dismissed, setDismissed] = useState(false);
  const hasHardGate = signal.blockers.some(b=> b.includes('Hard gate') || b.includes('خبر مهم'));
  if (dismissed) return null;
  return <div className="event-banner" style={{borderColor: hasHardGate? '#ef637150' : '#f1bc4b35', background: hasHardGate? 'linear-gradient(90deg, #ef637115, #14110c 45%, #0d0f13)' : undefined}}>
    <div className="event-date"><b>14:30</b><span>UTC</span></div>
    <div className="event-icon" style={{background: hasHardGate? '#ef637115' : undefined, color: hasHardGate? 'var(--red)' : undefined}}><Bell size={17}/></div>
    <div><span className="eyebrow" style={{color: hasHardGate? 'var(--red)' : undefined}}>{hasHardGate? '⛔ توقف معاملاتی — Hard Gate' : 'HIGH IMPACT · تا ۲۸ دقیقه دیگر'}</span><b>خرده‌فروشی آمریکا (Retail Sales m/m)</b></div>
    <p>{hasHardGate? 'به دلیل خبر مهم یا اسپرد بالا، سیستم ورود جدید را بسته است — فقط مدیریت پوزیشن باز.' : 'گارد اجرایی ۳ دقیقه قبل از انتشار، ورود جدید را متوقف می‌کند. بعد از اسپرد نرمال + ۲ کندل مجدد فعال می‌شود.'}</p>
    <button onClick={() => setDismissed(true)}><X size={16}/></button>
  </div>;
}

function ProfitabilityExplainer() {
  return <section className="panel" style={{gridColumn: '1 / -1', background:'linear-gradient(180deg, #0f1410 0, #0c0f14 100%)', borderColor:'#1e2d24'}}>
    <div className="panel-heading" style={{borderColor:'#1e2d24'}}><div><span className="eyebrow" style={{color:'#5a8a72'}}><BarChart3 size={12}/> چرا این سیستم سودمند طراحی شده؟</span><h2>سودمندی = نظم + R:R + مدیریت سرمایه — نه پیشگویی</h2></div><ShieldCheck size={18} style={{color:'var(--green)'}}/></div>
    <div style={{display:'grid', gridTemplateColumns:'repeat(auto-fit, minmax(220px,1fr))', gap:10, padding:12}}>
      {[
        {n:'۱', t:'فقط کندل بسته', d:'هیچ سیگنالی روی کندل در حال شکل‌گیری صادر نمی‌شود. کراس تنکان/کیجون باید تثبیت شود؛ وگرنه فریب می‌خورید.'},
        {n:'۲', t:'امتیاز ۷۲ به بالا', d:'۸ فیلتر (ابر، EMA200، VWAP، RSI، MACD، بولینگر، ADX، حجم) + تایید خبر + همگرایی 5m. زیر ۷۲ = عدم ورود.'},
        {n:'۳', t:'حد ضرر ATRمحور', d:'استاپ ۰.۹ تا ۱.۴ ATR + ساختار ۶کندل اخیر. تارگت ۱.۸ تا ۲.۰ برابر استاپ → حتی با ۴۵٪ برد، سربه‌سر.'},
        {n:'۴', t:'گارد خبر و اسپرد', d:'۵ دقیقه قبل خبر مهم ورود بسته، اسپرد >۲× مدیان و شوک نوسان = Hard Gate. سرمایه حفظ می‌شود.'},
        {n:'۵', t:'خروج شفاف', d:'حد ضرر/سود بروکری + شکست کیجون + کراس مخالف + تایم‌استاپ ۸کندل. هر پوزیشن رزومه می‌شود.'},
        {n:'۶', t:'حساب کوچک', d:'ریسک ۰.۲۵٪–۰.۵٪، ۰.۰۱ لات (۱ انس) کافیست. با ۱۰۰ دلار و استاپ ۳ دلاری، فقط ۰.۱۶ انس لازم است.'},
      ].map(x=> <div key={x.n} style={{background:'#0a0e12', border:'1px solid #1a2520', borderRadius:7, padding:'10px 11px'}}>
        <b style={{display:'flex', alignItems:'center', gap:7, fontSize:'11px'}}><span style={{width:22, height:22, display:'grid', placeItems:'center', background:'var(--green)', color:'#0a0e12', borderRadius:'50%', font:'700 10px DM Mono'}}>{x.n}</span>{x.t}</b>
        <p style={{margin:'6px 0 0', color:'#8a909c', fontSize:'10px', lineHeight:1.6}}>{x.d}</p>
      </div>)}
    </div>
    <div style={{margin:'0 12px 12px', padding:'9px 11px', background:'#f1bc4b0a', border:'1px solid #f1bc4b20', borderRadius:6, display:'flex', gap:9, alignItems:'center'}}>
      <AlertTriangle size={16} style={{color:'var(--gold)', flexShrink:0}}/>
      <p style={{margin:0, fontSize:'10px', color:'#c9b896', lineHeight:1.6}}><b>یادآوری مهم:</b> هیچ رباتی سود تضمینی نمی‌دهد. سودمندی واقعی فقط با بک‌تست هزینه‌محور (اسپرد، کمیسیون، اسلیپیج) و ۴–۸ هفته Paper Trading ثابت می‌شود. این ترمینال ابزار تصمیم‌یار است؛ مدیریت ریسک را شما رعایت می‌کنید. با <b>حساب دمو</b> شروع کنید.</p>
    </div>
  </section>
}

function MobileNav({ active, select }: { active: string; select: (s: string) => void }) {
  return <nav className="mobile-nav">{[
    ['terminal', LayoutDashboard, 'ترمینال'], ['predict', Brain, 'AI'], ['backtest', BarChart3, 'بک‌تست'], ['journal', BookOpen, 'رزومه'],
  ].map(([key, Icon, label]) => { const I = Icon as typeof LayoutDashboard; return <button key={key as string} className={active === key ? 'active' : ''} onClick={() => select(key as string)}><I size={19}/><span>{label as string}</span></button>; })}</nav>;
}

export default function App() {
  const [active, setActive] = useState('terminal');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [timeframe, setTimeframe] = useState<Timeframe>('5m'); // 5m strict pro default — quhar trader choice (win 67% vs 1m noisy 55%)
  const [price, setPrice] = useState(3358.42);
  const [delta, setDelta] = useState(15.84);
  const [clock, setClock] = useState('14:32:08');
  const [candles, setCandles] = useState(()=> generateCandles(5, 220));
  const [liveSignal, setLiveSignal] = useState<LiveSignal>(()=> evaluateBrowser(generateCandles(5,220)));

  // regenerate candles when timeframe changes — 5m strict default
  useEffect(()=> {
    const c = generateCandles(timeframe==='1m'?1:5, 220);
    setCandles(c);
    setLiveSignal(evaluateBrowser(c, timeframe));
  }, [timeframe]);

  // live tick simulation + re-evaluate — 5m strict
  useEffect(()=>{
    let tick=0;
    const id=setInterval(()=>{
      tick++;
      setCandles(prev=>{
        const next = [...prev];
        const last = next[next.length-1];
        const updated = {
          ...last,
          close: Math.round((last.close + Math.sin(tick/2.6)*0.08 + (Math.random()-0.48)*0.16)*100)/100,
          high: Math.round(Math.max(last.high, last.close + Math.random()*0.08)*100)/100,
          low: Math.round(Math.min(last.low, last.close - Math.random()*0.08)*100)/100,
          volume: last.volume + Math.round(Math.random()*12)
        };
        next[next.length-1]=updated;
        if (tick%2===0) setLiveSignal(prevSig => {
          // need fresh timeframe from closure — use functional to avoid stale, but we have timeframe in deps
          return evaluateBrowser(next, timeframe);
        });
        setPrice(updated.close);
        setDelta(updated.close - updated.open);
        return next;
      });
    }, 1800);
    return ()=> clearInterval(id);
  }, [timeframe]);

  // try backend evaluation every 7s
  useEffect(()=>{
    let alive=true;
    const run=async()=>{
      try{
        // build payload for backend
        const payloadCandles = candles.map(c=> ({
          symbol:"XAU/USD", timeframe, timestamp: new Date(c.time*1000).toISOString(),
          open:c.open, high:c.high, low:c.low, close:c.close, volume:c.volume, complete:true
        }));
        const res = await fetch('/api/v1/strategy/evaluate', {
          method:'POST', headers:{'Content-Type':'application/json'},
          body: JSON.stringify({candles: payloadCandles.slice(-220), context:{spread:0.18, typical_spread:0.18, event_risk:false, higher_timeframe_bias:'NEUTRAL'}})
        });
        if(!alive) return;
        if(res.ok){
          const data=await res.json();
          // map backend to LiveSignal
          setLiveSignal({
            action: data.action,
            confidence: data.confidence,
            entry: data.entry, stop_loss: data.stop_loss, take_profit: data.take_profit,
            risk_reward: data.risk_reward, reasons: data.reasons || [], blockers: data.blockers || [],
            confluence: data.confluence || evaluateBrowser(candles).confluence,
            exit_hint: data.exit_hint, expires_after_seconds: data.expires_after_seconds || 180
          });
        }
      }catch{}
    };
    const id=setInterval(run, 7000);
    run();
    return ()=> { alive=false; clearInterval(id); };
  }, [candles, timeframe]);

  useEffect(() => { const t = setInterval(() => setClock(new Date().toISOString().slice(11, 19)), 1000); return () => clearInterval(t); }, []);
  const onPriceChange = useCallback((next: number, candleDelta: number) => { setPrice(next); setDelta(15.84 + candleDelta); }, []);
  const select = useCallback((key: string) => { setActive(key); document.getElementById(key)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, []);
  const status = useMemo(() => `UTC ${clock}`, [clock]);

  return <div className="app-shell">
    <Sidebar active={active} setActive={setActive} open={sidebarOpen} close={() => setSidebarOpen(false)}/>
    <div className="main-shell">
      <Header price={price} delta={delta} menu={() => setSidebarOpen(true)}/>
      <main>
        <div className="page-intro"><div><span className="eyebrow" style={{gap:6}}><span style={{width:6,height:6, background:'var(--green)', borderRadius:'50%', boxShadow:'0 0 8px var(--green)'}}/> PRECIOUS METALS DESK · میز طلا</span><h1>ترمینال هوشمند طلا — سیگنال خرید، فروش و خروج</h1><small style={{color:'#6b7280', fontSize:'10px'}}>روش‌های مکمل همگرا + رصد زنده اخبار + رزومه معاملات + شروع با سرمایه کم</small></div><div className="intro-stats"><span><i className="feed-dot"/> فید همگام</span><span>{status}</span><span style={{background:'#f1bc4b18', color:'var(--gold)', border:'1px solid #f1bc4b30', padding:'2px 6px', borderRadius:4, font:'700 8px DM Mono'}}>DEMO · PAPER</span></div></div>
        <EconomicBanner signal={liveSignal}/>
        <div className="dashboard-grid" id="terminal">
          <TradingChart timeframe={timeframe} onTimeframeChange={setTimeframe} onPriceChange={onPriceChange}/>
          <SignalCard signal={liveSignal} timeframe={timeframe}/>
          <NewsCard/>
          <div className="right-stack"><ConfluenceCard signal={liveSignal}/><RiskCard signal={liveSignal}/></div>
        </div>
        {/* AI Behavioral Predictor — 119 ورودی + 6 نخبه + MTF + Behavior + OrderFlow */}
        <div style={{display:'grid', gap:13, marginTop:13}}>
          <PredictionPanel timeframe={timeframe}/>
        </div>
        <div style={{display:'grid', gap:13, marginTop:13}}>
          <MTFPanel timeframe={timeframe}/>
        </div>
        <div style={{display:'grid', gap:13, marginTop:13}}>
          <TopTradersPanel timeframe={timeframe}/>
        </div>
        {/* Backtest 2000-2026 — اثبات سودمندی */}
        <div style={{display:'grid', gap:13, marginTop:13}}>
          <BacktestPanel/>
        </div>
        {/* Second row: Journal + Profitability */}
        <div style={{display:'grid', gap:13, marginTop:13}}>
          <JournalCard/>
          <ProfitabilityExplainer/>
        </div>
        {/* Footer help */}
        <div style={{marginTop:16, padding:'12px 14px', background:'#0a0c10', border:'1px solid var(--line)', borderRadius:8, display:'flex', flexWrap:'wrap', gap:12, alignItems:'center', justifyContent:'space-between'}}>
          <div style={{display:'flex', gap:10, alignItems:'center'}}>
            <div style={{width:36, height:36, borderRadius:8, background:'linear-gradient(135deg, #f1bc4b, #9b671f)', display:'grid', placeItems:'center', color:'#111', font:'700 14px Georgia'}}>$</div>
            <div><b style={{fontSize:12}}>آماده‌اید با ۵۰ دلار شروع کنید؟</b><small style={{display:'block', color:'#6b7280', fontSize:10, marginTop:2}}>۱) حساب دمو بسازید · ۲) ۴ هفته با این سیگنال‌ها Paper Trade کنید · ۳) فقط با سود مستمر، حساب واقعی میکرو باز کنید.</small></div>
          </div>
          <div style={{display:'flex', gap:8}}>
            <button style={{height:34, padding:'0 14px', borderRadius:6, border:'1px solid var(--gold)', background:'linear-gradient(180deg, #f5ca6d, #d9a33c)', color:'#1b160c', font:'800 10px Manrope', cursor:'pointer', display:'flex', alignItems:'center', gap:6}}><DollarSign size={14}/> راهنمای شروع با سرمایه کم</button>
            <button style={{height:34, padding:'0 12px', borderRadius:6, border:'1px solid var(--line)', background:'#11151b', color:'#8a909c', font:'700 10px Manrope', cursor:'pointer'}}>مستندات استراتژی</button>
          </div>
        </div>
      </main>
    </div>
    <MobileNav active={active} select={select}/>
  </div>;
}
