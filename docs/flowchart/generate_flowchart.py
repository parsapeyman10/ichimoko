#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
مولّد نقشهٔ فلوچارت تعاملی Aurum Edge.

خروجی:
  docs/flowchart/index.html        — نقشهٔ تعاملی چندسطحی (خودکفا، بدون اینترنت)
  docs/FUNCTION_CATALOG_FA.md      — کاتالوگ کامل همهٔ فانکشن‌های مخزن

تعریف فلوچارت‌ها در charts_l0_l1.py و charts_l2_engine.py و charts_l2_data.py است.
اجرا:  python3 docs/flowchart/generate_flowchart.py
"""
import json
import os
import re
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import charts_l0_l1      # noqa: E402
import charts_l2_engine  # noqa: E402
import charts_l2_data    # noqa: E402

ALL_CHARTS = charts_l0_l1.CHARTS + charts_l2_engine.CHARTS + charts_l2_data.CHARTS

# --------------------------------------------------------------------------- #
# استخراج کاتالوگ کامل فانکشن‌ها از سورس
# --------------------------------------------------------------------------- #

KT_FUN = re.compile(r"^(\s*)(?:override\s+|private\s+|internal\s+|protected\s+|public\s+|"
                    r"suspend\s+|inline\s+|operator\s+|tailrec\s+)*fun\s+(?:<[^>]+>\s*)?"
                    r"(?:[\w.<>?]+\.)?(\w+)\s*\(")
KT_DECL = re.compile(r"^(\s*)(?:data\s+|sealed\s+|abstract\s+|open\s+|inner\s+|value\s+|"
                     r"internal\s+|private\s+)*(class|object|interface|enum\s+class)\s+(\w+)")
PY_FUN = re.compile(r"^(\s*)(?:async\s+)?def\s+(\w+)\s*\(")
PY_CLASS = re.compile(r"^class\s+(\w+)")

SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "__pycache__"}


def first_doc_line(lines):
    """اولین خط KDoc یا کامنت توضیحی ابتدای فایل."""
    for ln in lines[:60]:
        s = ln.strip()
        if s.startswith("*") and not s.startswith("*/") and len(s) > 3:
            txt = s.lstrip("* ").strip()
            if txt and not txt.startswith("@"):
                return txt[:130]
        if s.startswith('"""') and len(s) > 4:
            return s.strip('"').strip()[:130]
    return ""


def scan_file(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    decls = []          # (indent, kind, name)
    funs = []           # dict(name, line, owner)
    is_py = path.endswith(".py")
    for i, ln in enumerate(lines, 1):
        if is_py:
            m = PY_CLASS.match(ln)
            if m:
                decls.append((0, "class", m.group(1)))
            m = PY_FUN.match(ln)
            if m:
                indent = len(m.group(1))
                owner = next((d[2] for d in reversed(decls) if d[0] < indent), "")
                funs.append({"name": m.group(2), "line": i, "owner": owner})
        else:
            m = KT_DECL.match(ln)
            if m:
                decls.append((len(m.group(1)), m.group(2), m.group(3)))
            m = KT_FUN.match(ln)
            if m:
                indent = len(m.group(1))
                owner = next((d[2] for d in reversed(decls) if d[0] < indent), "")
                funs.append({"name": m.group(2), "line": i, "owner": owner})
    return {"lines": len(lines), "doc": first_doc_line(lines), "funs": funs,
            "decls": [d[2] for d in decls]}


def build_catalog():
    catalog = OrderedDict()
    roots = [
        ("android/app/src/main/java/com/aurum/edge", ".kt", "اپ اندروید — کد اصلی"),
        ("android/app/src/test/java/com/aurum/edge", ".kt", "اپ اندروید — تست‌ها"),
        ("tick-archive", ".py", "سرویس بایگانی تیک (سرور)"),
        ("tools", ".py", "ابزارهای توسعه و راستی‌آزمایی"),
    ]
    for root, ext, title in roots:
        base = os.path.join(REPO, root)
        if not os.path.isdir(base):
            continue
        files = []
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for fn in sorted(filenames):
                if not fn.endswith(ext):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, base)
                info = scan_file(full)
                info["rel"] = rel
                info["repo_rel"] = os.path.relpath(full, REPO)
                files.append(info)
        files.sort(key=lambda f: f["rel"])
        catalog[root] = {"title": title, "files": files}
    return catalog


# فانکشن‌هایی که فلوچارت عمیق (سطح ۲) دارند: نام فانکشن ← شناسهٔ نمودار
FUN_CHART = {
    "evaluateLive": "fn-signalengine", "evaluate": "fn-signalengine",
    "score": "fn-v1score-side", "frameBias": "fn-v1score-side",
    "directionalAlignment": "fn-v1score-side", "chooseSide": "fn-v1score-side",
    "compute": "fn-ichimoku", "donchianMid": "fn-ichimoku", "ema": "fn-ichimoku",
    "rsi": "fn-ichimoku", "macdHistogram": "fn-ichimoku", "adx": "fn-ichimoku",
    "atr": "fn-ichimoku",
    "read": "fn-alignment", "trendAligned": "fn-alignment", "rangeAligned": "fn-alignment",
    "assess": "fn-entrysetup",
    "classifyRegime": "fn-als5", "evaluateEngineA": "fn-als5",
    "evaluateEngineB": "fn-als5", "evaluateEngineC": "fn-als5",
    "calculatePositionSize": "fn-als5", "sessionAllowed": "fn-als5",
    "convertFromM5": "fn-mtf", "analyze": "fn-mtf", "resample": "fn-mtf",
    "blocker": "fn-autorules", "opportunityBlocker": "fn-autorules",
    "preview": "fn-ticket",
    "onMarketUpdate": "fn-autotrader", "auditAiEntry": "fn-autotrader",
    "open": "fn-journal-open",
    "settle": "fn-settle", "settleTick": "fn-settle", "advance": "fn-settle",
    "settleBinanceBatch": "fn-settle", "applyCryptoClosedBars": "fn-settle",
    "refresh": "fn-market-refresh",
    "publishCandles": "fn-publish", "onTick": "fn-publish", "startWatchdog": "fn-publish",
    "sweepAll": "fn-scanner-sweep", "sweepOnce": "fn-scanner-sweep",
    "rankOpportunities": "fn-scanner-sweep",
    "refreshServerMode": "fn-news-refresh", "refreshClientMode": "fn-news-refresh",
    "apply": "fn-newsconfluence", "alignment": "fn-newsconfluence",
    "planTradeWithAi": "fn-ai-plan", "fromResponse": "fn-ai-plan",
    "ensureConnection": "fn-ai-plan", "reviewOpenPositions": "fn-ai-plan",
    "watch": "fn-tickmonitor", "poll": "fn-tickmonitor",
    "watchBinance": "fn-tickmonitor", "pollArchive": "fn-tickmonitor",
    "checkForUpdate": "fn-updater", "downloadApk": "fn-updater",
    "chooseUpdate": "fn-updater", "checkManifest": "fn-updater",
    "checkLatestRelease": "fn-updater",
    "startApp": "fn-viewmodel", "ensureLiveTickerLoop": "fn-viewmodel",
    "ensureVisibleOnlineLoop": "fn-viewmodel", "submitPaper": "fn-viewmodel",
    "ensureChannels": "fn-notifier", "notifyRecordedAutoEntry": "fn-notifier",
    "notifyVerifiedOpportunity": "fn-notifier", "notifyClosedTrade": "fn-notifier",
    "on_publication": "fn-archive", "publish": "fn-archive", "page": "fn-archive",
    "validate_trade": "fn-archive",
    "review": "auto-entry",
    "onStartCommand": "service",
}


def validate_charts(charts):
    ids = set()
    for ch in charts:
        assert ch["id"] not in ids, f"شناسهٔ تکراری نمودار: {ch['id']}"
        ids.add(ch["id"])
    chart_ids = ids
    for ch in charts:
        node_ids = set()
        for n in ch["nodes"]:
            assert n["id"] not in node_ids, f"گره تکراری در {ch['id']}: {n['id']}"
            node_ids.add(n["id"])
            if n.get("link"):
                assert n["link"] in chart_ids, f"لینک ناموجود {n['link']} در {ch['id']}"
        for e in ch["edges"]:
            assert e["f"] in node_ids, f"مبدأ یال ناموجود {e['f']} در {ch['id']}"
            assert e["t"] in node_ids, f"مقصد یال ناموجود {e['t']} در {ch['id']}"
    return True


# --------------------------------------------------------------------------- #
# قالب HTML + رندرکنندهٔ SVG (جاوااسکریپت توکار — بدون هیچ وابستگی خارجی)
# --------------------------------------------------------------------------- #

HTML = r"""<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>نقشهٔ فلوچارت Aurum Edge — طرح جدید اپ</title>
<style>
:root{
  --bg:#0b1020; --panel:#111a30; --panel2:#0e1526; --line:#233252; --txt:#dbe4f5;
  --mut:#8fa3c8; --acc:#38bdf8; --acc2:#f59e0b; --ok:#34d399; --bad:#fb7185;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--txt);
  font-family:Tahoma,"Segoe UI",system-ui,sans-serif;font-size:15px;line-height:1.9}
a{color:var(--acc);text-decoration:none}
header.hero{padding:34px 22px 18px;background:linear-gradient(160deg,#0e1a38,#0b1020 70%);
  border-bottom:1px solid var(--line)}
header.hero h1{margin:0 0 6px;font-size:27px}
header.hero p{margin:4px 0;color:var(--mut);max-width:980px}
.banner{max-width:980px;margin-top:14px;background:#2a1f07;border:1px solid #92600a;color:#fcd34d;
  border-radius:12px;padding:10px 16px;font-size:13.5px}
.stats{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}
.stat{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:8px 16px}
.stat b{font-size:19px;color:#fff}
.stat span{display:block;font-size:12px;color:var(--mut)}
.layout{display:flex;align-items:flex-start;gap:18px;padding:18px;max-width:1560px;margin:0 auto}
nav.toc{position:sticky;top:14px;width:300px;flex:0 0 300px;background:var(--panel);
  border:1px solid var(--line);border-radius:14px;padding:14px;max-height:calc(100vh - 28px);overflow:auto}
nav.toc h3{margin:12px 0 6px;font-size:13px;color:var(--acc2)}
nav.toc a{display:block;padding:4px 8px;border-radius:8px;color:var(--txt);font-size:13.5px}
nav.toc a:hover{background:#1a2747}
nav.toc a .fid{direction:ltr;unicode-bidi:embed;color:var(--mut);font-size:11px;font-family:Consolas,monospace}
#q{width:100%;background:#0a1224;border:1px solid var(--line);color:var(--txt);
  border-radius:10px;padding:9px 12px;font-family:inherit;font-size:14px}
main{flex:1;min-width:0}
.legend{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 16px}
.legend div{font-size:12.5px;border:1px solid var(--line);border-radius:999px;padding:3px 12px;background:var(--panel2)}
.legend i{display:inline-block;width:11px;height:11px;border-radius:3px;margin-left:6px;vertical-align:-1px}
section.chart{background:var(--panel);border:1px solid var(--line);border-radius:16px;
  padding:16px 18px 10px;margin:0 0 22px;scroll-margin-top:14px}
section.chart.flash{outline:2px solid var(--acc2);outline-offset:2px}
section.chart h2{margin:2px 0 2px;font-size:19px}
section.chart .lvl{display:inline-block;font-size:11.5px;border-radius:999px;padding:1px 10px;
  background:#1d2b4d;color:var(--acc);border:1px solid var(--line);margin-left:8px;vertical-align:2px}
section.chart .src{direction:ltr;text-align:left;font-family:Consolas,monospace;font-size:11.5px;
  color:var(--mut);word-break:break-all}
section.chart .desc{color:var(--mut);font-size:13.5px;margin:6px 0 10px}
.toolbar{display:flex;gap:6px;margin:8px 0}
.toolbar button{background:#18264a;color:var(--txt);border:1px solid var(--line);border-radius:8px;
  padding:4px 12px;cursor:pointer;font-family:inherit;font-size:13px}
.toolbar button:hover{background:#20325f}
.canvas{overflow:hidden;border:1px solid var(--line);border-radius:12px;background:
  radial-gradient(circle at 1px 1px,#1b2a4d 1px,transparent 0);background-size:22px 22px;background-color:#0a1122}
.canvas svg{display:block;width:100%;height:auto;cursor:grab;touch-action:none}
.canvas:fullscreen{background-color:#0a1122}
.canvas:fullscreen svg{width:100%!important;height:100%!important;margin:0!important}
.canvas svg:active{cursor:grabbing}
.node text{pointer-events:none}
.node.clickable{cursor:pointer}
.node.clickable:hover .box{stroke:var(--acc2);stroke-width:2.4}
details.cat{background:var(--panel);border:1px solid var(--line);border-radius:14px;margin:0 0 14px;padding:6px 16px}
details.cat>summary{cursor:pointer;font-weight:bold;padding:8px 0}
details.file{border-top:1px dashed #1d2b4d;padding:6px 0}
details.file>summary{cursor:pointer;font-size:13.5px}
details.file .doc{color:var(--mut);font-size:12.5px}
table{border-collapse:collapse;width:100%;font-size:12.5px;margin:8px 0 12px}
th,td{border-bottom:1px solid #1d2b4d;padding:3px 8px;text-align:right}
td.fn{direction:ltr;text-align:left;font-family:Consolas,monospace;font-size:12px}
td.ln{color:var(--mut);width:56px}
.chip{font-size:11px;border:1px solid var(--line);border-radius:999px;padding:0 8px;background:#16244a}
.hidden{display:none!important}
footer{color:var(--mut);font-size:12.5px;padding:8px 22px 34px;max-width:1560px;margin:0 auto}
@media(max-width:980px){.layout{flex-direction:column}nav.toc{position:static;width:100%;flex:none;max-height:300px}}
@media print{nav.toc,.toolbar,#q{display:none}body{background:#fff;color:#111}section.chart{break-inside:avoid;background:#fff}}
</style>
</head>
<body>
<header class="hero">
  <h1>🗺️ نقشهٔ طرح جدید اپ Aurum Edge</h1>
  <p>این نقشه، معماری هدفِ اپ بعد از ریفکتورِ درخواستی است: خبر و تقویم اقتصادی کامل حذف شده‌اند؛ هر تایم‌فریم کندل واقعی و مستقل خودش را از منبع رایگان می‌گیرد
  (دیگر نه سیاست ۱۵۰۰ کندل هست نه ساخت تایم‌فریم از روی M5)؛ دروازهٔ تأیید بازار با «گارد فید» سبک جایگزین شده؛ پایش پس‌زمینه تطبیقی است
  (متناسب با تایم‌فریم و وضعیت پوزیشن)؛ و هر سورسی که داده ندهد صراحتاً «مرده» اعلام و جایگزینش فعال می‌شود.
  روی هر گرهٔ بنفشِ «زیرنمودار» کلیک کن تا به فلوچارت همان فانکشن بپری؛ با ＋／－ یا چرخ موس زوم کن و با درگ جابه‌جا شو.</p>
  <div class="banner">⚠️ <b>وضعیت:</b> این صفحه «طرح هدف» است و هنوز روی کد اعمال نشده — برای سازماندهی و تأیید منطق جدید است.
  کاتالوگ انتهای صفحه هنوز از کد فعلی استخراج می‌شود و بعد از هر فاز ریفکتور با نقشه تطبیق داده خواهد شد.</div>
  <div class="stats">__STATS__</div>
</header>
<div class="layout">
  <nav class="toc">
    <input id="q" type="search" placeholder="جستجو در نمودارها و فانکشن‌ها…">
    <div id="tocLinks">__TOC__</div>
  </nav>
  <main>
    <div class="legend">
      <div><i style="background:#14532d"></i>شروع</div>
      <div><i style="background:#7f1d2d"></i>پایان / خروج</div>
      <div><i style="background:#1e3a5f"></i>پردازش</div>
      <div><i style="background:#92600a"></i>تصمیم (شرط)</div>
      <div><i style="background:#4c2a6e"></i>ورودی / خروجی</div>
      <div><i style="background:#0f4c4c"></i>ذخیره‌سازی / داده</div>
      <div><i style="background:#312e81"></i>زیرنمودار — کلیک = فلوچارت آن فانکشن ⧉</div>
      <div><i style="background:#374151"></i>سامانهٔ بیرونی</div>
    </div>
    <div id="charts"></div>
    <h2 style="margin-top:34px">📚 کاتالوگ کامل همهٔ فانکشن‌ها</h2>
    <p style="color:var(--mut);font-size:13.5px">استخراج خودکار از سورس. روی هر فایل باز کن تا همهٔ فانکشن‌هایش را ببینی؛
    کنار فانکشن‌هایی که فلوچارت عمیق دارند برچسب «⧉ فلوچارت» است.</p>
    <div id="catalog">__CATALOG__</div>
  </main>
</div>
<footer>ساخته‌شده با <span dir="ltr">docs/flowchart/generate_flowchart.py</span> از سورس مخزن — هیچ سفارش واقعی در این اپ ارسال نمی‌شود؛ همهٔ معاملات کاغذی‌اند.</footer>

<script>
"use strict";
const CHARTS = __CHARTS_JSON__;

/* ---------- اندازه‌گیری متن ---------- */
const _cv = document.createElement("canvas").getContext("2d");
function textW(s, font){ _cv.font = font; return _cv.measureText(s).width; }
const F_LBL = '13px Tahoma,"Segoe UI",sans-serif';
const F_SUB = '10px Consolas,monospace';

/* ---------- چیدمان لایه‌ای ---------- */
function layout(chart){
  const nodes = new Map();
  chart.nodes.forEach((n,i)=>nodes.set(n.id,{...n, idx:i, kids:[], pars:[]}));
  const edges = chart.edges.map(e=>({...e, back: e.k==="back"}));
  edges.forEach(e=>{ nodes.get(e.f).kids.push(e); nodes.get(e.t).pars.push(e); });

  // تشخیص یال‌های برگشتی با DFS از گره‌های شروع
  const color = new Map(); // 0 سفید 1 خاکستری 2 سیاه
  function dfs(id){
    color.set(id,1);
    for(const e of nodes.get(id).kids){
      if(e.back) continue;
      const c = color.get(e.t)||0;
      if(c===1){ e.back = true; }
      else if(c===0){ dfs(e.t); }
    }
    color.set(id,2);
  }
  const starts = chart.nodes.filter(n=>n.t==="start").map(n=>n.id);
  (starts.length?starts:[chart.nodes[0].id]).forEach(id=>{ if(!color.get(id)) dfs(id); });
  chart.nodes.forEach(n=>{ if(!color.get(n.id)) dfs(n.id); });

  // لایه = بلندترین مسیر از مبدأ روی یال‌های روبه‌جلو
  const layer = new Map(chart.nodes.map(n=>[n.id,0]));
  let changed = true, guard = 0;
  while(changed && guard++ < 500){
    changed = false;
    for(const e of edges){
      if(e.back) continue;
      const want = layer.get(e.f)+1;
      if(layer.get(e.t) < want){ layer.set(e.t, want); changed = true; }
    }
  }
  const byLayer = new Map();
  nodes.forEach(n=>{ n.layer = layer.get(n.id);
    if(!byLayer.has(n.layer)) byLayer.set(n.layer,[]);
    byLayer.get(n.layer).push(n); });
  const layers = [...byLayer.keys()].sort((a,b)=>a-b).map(k=>byLayer.get(k));

  // ترتیب درون لایه با میانگین وزنی همسایه‌ها (کاهش تقاطع)
  function posIn(arr,id){ return arr.indexOf(nodes.get(id)); }
  for(let it=0; it<6; it++){
    const dirDown = it%2===0;
    const seq = dirDown ? layers : [...layers].reverse();
    for(const arr of seq){
      const key = n=>{
        const rel = dirDown ? n.pars.filter(e=>!e.back).map(e=>e.f)
                            : n.kids.filter(e=>!e.back).map(e=>e.t);
        if(!rel.length) return n._ord ?? arr.indexOf(n);
        const vals = rel.map(id=>{ const m = nodes.get(id);
          return layers[m.layer] ? layers[m.layer].indexOf(m) : 0; });
        return vals.reduce((a,b)=>a+b,0)/vals.length;
      };
      arr.forEach((n,i)=>n._ord=i);
      arr.sort((a,b)=>(key(a)-key(b)) || (a._ord-b._ord));
    }
  }

  // اندازهٔ گره‌ها
  nodes.forEach(n=>{
    const lines = (n.label||"").split("\n");
    n.lines = lines;
    let w = Math.max(...lines.map(s=>textW(s,F_LBL)), 40);
    if(n.sub) w = Math.max(w, textW(n.sub,F_SUB));
    let h = lines.length*19 + (n.sub?16:0) + 14;
    if(n.t==="dec"){ w = w*1.45 + 34; h = Math.max(h*1.5, 64); }
    else if(n.t==="start"||n.t==="end"){ w += 30; h = Math.max(h,40); }
    else if(n.t==="io"){ w += 26; }
    else { w += 22; }
    n.w = Math.ceil(w); n.h = Math.ceil(h);
  });

  // مختصات
  const HGAP=26, VGAP=54;
  const layerW = layers.map(arr=>arr.reduce((s,n)=>s+n.w,0)+HGAP*(arr.length-1));
  const maxW = Math.max(...layerW, 300);
  let y = 26;
  layers.forEach((arr,li)=>{
    const lh = Math.max(...arr.map(n=>n.h));
    let x = (maxW - layerW[li])/2 + 20;
    arr.forEach(n=>{ n.x = x + n.w/2; n.y = y + lh/2; x += n.w + HGAP; });
    y += lh + VGAP;
  });
  // هم‌ترازی فرزند زیر والد (دو گذر ساده بدون هم‌پوشانی)
  for(let pass=0; pass<2; pass++){
    for(const arr of layers){
      for(const n of arr){
        const ps = n.pars.filter(e=>!e.back).map(e=>nodes.get(e.f));
        if(ps.length===1 && ps[0].kids.filter(e=>!e.back).length===1){
          const want = ps[0].x, i = arr.indexOf(n);
          const leftLim = i>0 ? arr[i-1].x + arr[i-1].w/2 + n.w/2 + HGAP : n.w/2+6;
          const rightLim = i<arr.length-1 ? arr[i+1].x - arr[i+1].w/2 - n.w/2 - HGAP : 1e9;
          n.x = Math.max(leftLim, Math.min(rightLim, want));
        }
      }
    }
  }
  let minX=1e18,maxX=-1e18;
  nodes.forEach(n=>{ minX=Math.min(minX,n.x-n.w/2); maxX=Math.max(maxX,n.x+n.w/2); });
  const backEdges = edges.filter(e=>e.back);
  const W = Math.ceil(maxX-minX) + 40 + backEdges.length*16;
  const H = Math.ceil(y - VGAP + 26);
  const shift = 20 - minX;
  nodes.forEach(n=>n.x += shift);
  return {nodes, edges, W, H, backEdges};
}

/* ---------- رسم SVG ---------- */
const COLORS = {
  start:["#14532d","#34d399"], end:["#7f1d2d","#fb7185"], proc:["#1e3a5f","#4b7dd1"],
  dec:["#92600a","#f59e0b"], io:["#4c2a6e","#a78bfa"], data:["#0f4c4c","#2dd4bf"],
  store:["#0f4c4c","#2dd4bf"], sub:["#312e81","#818cf8"], ext:["#374151","#9ca3af"]
};
function esc(s){ return (s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;"); }

function nodeShape(n){
  const [fill,stroke] = COLORS[n.t]||COLORS.proc;
  const w=n.w,h=n.h;
  let shape="";
  if(n.t==="dec"){
    shape = `<polygon class="box" points="0,${-h/2} ${w/2},0 0,${h/2} ${-w/2},0" fill="${fill}" stroke="${stroke}" stroke-width="1.6"/>`;
  } else if(n.t==="io"){
    const s=12;
    shape = `<polygon class="box" points="${-w/2+s},${-h/2} ${w/2},${-h/2} ${w/2-s},${h/2} ${-w/2},${h/2}" fill="${fill}" stroke="${stroke}" stroke-width="1.6"/>`;
  } else if(n.t==="start"||n.t==="end"){
    shape = `<rect class="box" x="${-w/2}" y="${-h/2}" width="${w}" height="${h}" rx="${h/2}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`;
  } else if(n.t==="sub"){
    shape = `<rect class="box" x="${-w/2}" y="${-h/2}" width="${w}" height="${h}" rx="8" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`
          + `<rect x="${-w/2+5}" y="${-h/2+5}" width="${w-10}" height="${h-10}" rx="5" fill="none" stroke="${stroke}" stroke-width="1" opacity=".65"/>`;
  } else if(n.t==="ext"){
    shape = `<rect class="box" x="${-w/2}" y="${-h/2}" width="${w}" height="${h}" rx="8" fill="${fill}" stroke="${stroke}" stroke-width="1.4" stroke-dasharray="6 4"/>`;
  } else if(n.t==="store"||n.t==="data"){
    shape = `<rect class="box" x="${-w/2}" y="${-h/2}" width="${w}" height="${h}" rx="8" fill="${fill}" stroke="${stroke}" stroke-width="1.6"/>`
          + `<path d="M ${-w/2} ${-h/2+8} Q 0 ${-h/2-4} ${w/2} ${-h/2+8}" fill="none" stroke="${stroke}" stroke-width="1.2"/>`;
  } else {
    shape = `<rect class="box" x="${-w/2}" y="${-h/2}" width="${w}" height="${h}" rx="8" fill="${fill}" stroke="${stroke}" stroke-width="1.6"/>`;
  }
  const totalLines = n.lines.length + (n.sub?1:0);
  let ty = -(totalLines-1)*9.5 + 4;
  let texts = "";
  n.lines.forEach(ln=>{ texts += `<text x="0" y="${ty}" text-anchor="middle" fill="#eef3ff" font-family='Tahoma,"Segoe UI",sans-serif' font-size="13">${esc(ln)}</text>`; ty += 19; });
  if(n.sub) texts += `<text x="0" y="${ty-3}" text-anchor="middle" fill="#9fb4dd" font-family="Consolas,monospace" font-size="10" direction="ltr">${esc(n.sub)}</text>`;
  if(n.t==="sub") texts += `<text x="${w/2-13}" y="${-h/2+15}" text-anchor="middle" fill="#c7d2fe" font-size="12">⧉</text>`;
  const click = n.link ? ` class="node clickable" data-link="${n.link}"` : ` class="node"`;
  return `<g${click} transform="translate(${n.x},${n.y})">${shape}${texts}</g>`;
}

function render(chart){
  const L = layout(chart);
  const N = id=>L.nodes.get(id);
  let out = `<svg viewBox="0 0 ${L.W} ${L.H}" data-w="${L.W}" data-h="${L.H}" role="img">`;
  out += `<defs><marker id="ar-${chart.id}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#7d94c4"/></marker></defs>`;
  out += `<g class="viewport">`;
  // یال‌ها
  let back_i = 0;
  for(const e of L.edges){
    const a=N(e.f), b=N(e.t);
    let d="", lx=0, ly=0;
    if(e.back){
      back_i++;
      const chX = L.W - 12 - back_i*14;
      d = `M ${a.x+a.w/2} ${a.y} L ${chX} ${a.y} L ${chX} ${b.y} L ${b.x+b.w/2+3} ${b.y}`;
      lx = chX-4; ly = (a.y+b.y)/2;
    } else if(b.y > a.y){
      const y1=a.y+a.h/2, y2=b.y-b.h/2, my=(y1+y2)/2;
      d = `M ${a.x} ${y1} C ${a.x} ${my}, ${b.x} ${my}, ${b.x} ${y2-2}`;
      lx=(a.x+b.x)/2+6; ly=my;
    } else {
      const left = b.x < a.x;
      const x1 = left? a.x-a.w/2 : a.x+a.w/2, x2 = left? b.x+b.w/2 : b.x-b.w/2;
      d = `M ${x1} ${a.y} C ${x1+(left?-40:40)} ${a.y}, ${x2+(left?40:-40)} ${b.y}, ${x2+(left?3:-3)} ${b.y}`;
      lx=(x1+x2)/2; ly=(a.y+b.y)/2-6;
    }
    out += `<path d="${d}" fill="none" stroke="#7d94c4" stroke-width="1.6" marker-end="url(#ar-${chart.id})" ${e.back?'stroke-dasharray="5 4"':''}/>`;
    if(e.label){
      out += `<text x="${lx}" y="${ly}" text-anchor="middle" font-family='Tahoma,"Segoe UI",sans-serif' font-size="11.5" fill="#ffd58a" stroke="#0a1122" stroke-width="3.5" paint-order="stroke">${esc(e.label)}</text>`;
    }
  }
  chart.nodes.forEach(n=>{ out += nodeShape(N(n.id)); });
  out += `</g></svg>`;
  return out;
}

/* ---------- ساخت صفحه ---------- */
const LVL = {0:"سطح ۰ · نقشهٔ کلان",1:"سطح ۱ · جریان اصلی",2:"سطح ۲ · فلوچارت فانکشن"};
const wrap = document.getElementById("charts");
CHARTS.forEach(ch=>{
  const sec = document.createElement("section");
  sec.className="chart"; sec.id="chart-"+ch.id;
  sec.dataset.search = (ch.title+" "+ch.id+" "+(ch.file||"")+" "+(ch.desc||"")).toLowerCase();
  sec.innerHTML =
    `<h2><span class="lvl">${LVL[ch.level]||""}</span>${esc(ch.title)}</h2>`+
    (ch.file?`<div class="src">${esc(ch.file)}</div>`:"")+
    (ch.desc?`<div class="desc">${esc(ch.desc)}</div>`:"")+
    `<div class="toolbar"><button data-act="in">＋ بزرگ‌نمایی</button><button data-act="out">－ کوچک‌نمایی</button>`+
    `<button data-act="fit">⟲ اندازهٔ صفحه</button><button data-act="fs">⛶ تمام‌صفحه</button></div>`+
    `<div class="canvas">${render(ch)}</div>`;
  wrap.appendChild(sec);
  attachPanZoom(sec);
});

function attachPanZoom(sec){
  const svg = sec.querySelector("svg"), g = sec.querySelector(".viewport");
  // نمودارهای خیلی بلند در ابتدا به اندازهٔ ارتفاع صفحه کوچک می‌شوند تا یک‌جا دیده شوند؛
  // کاربر با زوم و درگ جزئیات را می‌کاود.
  const W0 = +svg.dataset.w, H0 = +svg.dataset.h;
  const cw = sec.querySelector(".canvas").clientWidth || W0;
  const maxH = Math.round(window.innerHeight * 0.82);
  if (H0 / W0 * cw > maxH) {
    svg.style.width = Math.max(320, Math.round(maxH * W0 / H0)) + "px";
    svg.style.margin = "0 auto";
  }
  const st = {k:1,x:0,y:0};
  const apply = ()=>g.setAttribute("transform",`translate(${st.x},${st.y}) scale(${st.k})`);
  sec.querySelectorAll(".toolbar button").forEach(btn=>{
    btn.addEventListener("click",()=>{
      const act = btn.dataset.act;
      if(act==="in") st.k = Math.min(4, st.k*1.25);
      else if(act==="out") st.k = Math.max(.2, st.k/1.25);
      else if(act==="fit"){ st.k=1; st.x=0; st.y=0; }
      else if(act==="fs"){ const c=sec.querySelector(".canvas"); if(c.requestFullscreen) c.requestFullscreen(); return; }
      apply();
    });
  });
  let drag=null;
  svg.addEventListener("pointerdown",e=>{ drag={x:e.clientX,y:e.clientY,ox:st.x,oy:st.y,moved:false}; svg.setPointerCapture(e.pointerId); });
  svg.addEventListener("pointermove",e=>{
    if(!drag) return;
    const r = svg.getBoundingClientRect(), W=+svg.dataset.w;
    const scale = W/r.width;
    const dx=(e.clientX-drag.x)*scale, dy=(e.clientY-drag.y)*scale;
    if(Math.abs(e.clientX-drag.x)+Math.abs(e.clientY-drag.y)>4) drag.moved=true;
    st.x=drag.ox+dx; st.y=drag.oy+dy; apply();
  });
  svg.addEventListener("pointerup",e=>{
    const wasDrag = drag && drag.moved; drag=null;
    if(wasDrag) return;
    const nd = e.target.closest(".node.clickable");
    if(nd) jumpTo(nd.dataset.link);
  });
  svg.addEventListener("wheel",e=>{
    e.preventDefault();
    st.k = Math.max(.2, Math.min(4, st.k*(e.deltaY<0?1.12:0.89)));
    apply();
  },{passive:false});
}

function jumpTo(id){
  const el = document.getElementById("chart-"+id);
  if(!el) return;
  document.getElementById("q").value=""; filter("");
  el.scrollIntoView({behavior:"smooth", block:"start"});
  el.classList.add("flash");
  setTimeout(()=>el.classList.remove("flash"), 1800);
}
document.querySelectorAll("[data-jump]").forEach(a=>{
  a.addEventListener("click",ev=>{ ev.preventDefault(); jumpTo(a.dataset.jump); });
});

/* ---------- جستجو ---------- */
function filter(q){
  q = (q||"").trim().toLowerCase();
  document.querySelectorAll("section.chart").forEach(sec=>{
    sec.classList.toggle("hidden", !!q && !sec.dataset.search.includes(q));
  });
  document.querySelectorAll("nav.toc a").forEach(a=>{
    a.classList.toggle("hidden", !!q && !a.textContent.toLowerCase().includes(q));
  });
  document.querySelectorAll("details.file").forEach(d=>{
    const hit = !q || d.dataset.search.includes(q);
    d.classList.toggle("hidden", !hit);
    if(q && hit) { d.open = true; const p=d.closest("details.cat"); if(p) p.open=true; }
  });
}
document.getElementById("q").addEventListener("input",e=>filter(e.target.value));
</script>
</body>
</html>
"""


def build_stats(catalog):
    total_files = total_lines = total_funs = 0
    for grp in catalog.values():
        for f in grp["files"]:
            total_files += 1
            total_lines += f["lines"]
            total_funs += len(f["funs"])
    return total_files, total_lines, total_funs


def fa_num(n):
    return f"{n:,}".translate(str.maketrans("0123456789,", "۰۱۲۳۴۵۶۷۸۹٬"))


def build_html(catalog):
    validate_charts(ALL_CHARTS)
    tf, tl, tfun = build_stats(catalog)
    deep = sum(1 for c in ALL_CHARTS if c["level"] == 2)
    stats = "".join(
        f'<div class="stat"><b>{fa_num(v)}</b><span>{k}</span></div>'
        for k, v in [
            ("نمودار فلوچارت", len(ALL_CHARTS)),
            ("فلوچارت عمیق فانکشن", deep),
            ("فایل سورس اسکن‌شده", tf),
            ("خط کد", tl),
            ("فانکشن در کاتالوگ", tfun),
            ("نماد فعال (قرارداد V1)", 15),
        ]
    )
    toc_levels = [(0, "سطح ۰ — نقشهٔ کلان اپ"), (1, "سطح ۱ — جریان‌های اصلی زمان اجرا"),
                  (2, "سطح ۲ — فلوچارت عمیق فانکشن‌ها")]
    toc = ""
    for lvl, title in toc_levels:
        toc += f"<h3>{title}</h3>"
        for ch in ALL_CHARTS:
            if ch["level"] == lvl:
                toc += (f'<a href="#chart-{ch["id"]}" data-jump="{ch["id"]}">'
                        f'{ch["title"]} <span class="fid">{ch["id"]}</span></a>')
    toc += '<h3>کاتالوگ</h3><a href="#catalog">📚 همهٔ فانکشن‌ها بر اساس فایل</a>'

    cat_html = ""
    for root, grp in catalog.items():
        nf = len(grp["files"])
        nfn = sum(len(f["funs"]) for f in grp["files"])
        cat_html += (f'<details class="cat"><summary>{grp["title"]} — '
                     f'<span dir="ltr">{root}</span> '
                     f'({fa_num(nf)} فایل · {fa_num(nfn)} فانکشن)</summary>')
        for f in grp["files"]:
            search_blob = (f["rel"] + " " + " ".join(x["name"] for x in f["funs"])).lower()
            cat_html += (f'<details class="file" data-search="{search_blob}">'
                         f'<summary><span dir="ltr">{f["rel"]}</span> '
                         f'<span class="chip">{fa_num(f["lines"])} خط</span> '
                         f'<span class="chip">{fa_num(len(f["funs"]))} فانکشن</span></summary>')
            if f["doc"]:
                cat_html += f'<div class="doc">{f["doc"]}</div>'
            if f["funs"]:
                cat_html += ('<table><tr><th>فانکشن</th><th>کلاس/آبجکت</th>'
                             '<th>خط</th><th>فلوچارت</th></tr>')
                for fn in f["funs"]:
                    link = FUN_CHART.get(fn["name"], "")
                    chip = (f'<a href="#chart-{link}" data-jump="{link}">⧉ فلوچارت</a>'
                            if link else "—")
                    cat_html += (f'<tr><td class="fn">{fn["name"]}()</td>'
                                 f'<td class="fn">{fn["owner"] or "—"}</td>'
                                 f'<td class="ln">{fn["line"]}</td><td>{chip}</td></tr>')
                cat_html += "</table>"
            cat_html += "</details>"
        cat_html += "</details>"

    charts_json = json.dumps(ALL_CHARTS, ensure_ascii=False)
    html = (HTML.replace("__STATS__", stats)
                .replace("__TOC__", toc)
                .replace("__CATALOG__", cat_html)
                .replace("__CHARTS_JSON__", charts_json))
    return html


def build_catalog_md(catalog):
    tf, tl, tfun = build_stats(catalog)
    out = [
        "# کاتالوگ کامل فانکشن‌های مخزن (استخراج خودکار از سورس)",
        "",
        f"> این فایل با `docs/flowchart/generate_flowchart.py` ساخته می‌شود؛ دستی ویرایشش نکنید.",
        f"> مجموع: {tf} فایل · {tl} خط · {tfun} فانکشن. نقشهٔ تصویری تعاملی: `docs/flowchart/index.html`",
        "",
    ]
    for root, grp in catalog.items():
        nfn = sum(len(f["funs"]) for f in grp["files"])
        out.append(f"## {grp['title']} — `{root}` ({len(grp['files'])} فایل · {nfn} فانکشن)")
        out.append("")
        for f in grp["files"]:
            out.append(f"### `{f['rel']}` — {f['lines']} خط · {len(f['funs'])} فانکشن")
            if f["doc"]:
                out.append(f"> {f['doc']}")
            if f["funs"]:
                out.append("")
                out.append("| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |")
                out.append("|---|---|---|---|")
                for fn in f["funs"]:
                    link = FUN_CHART.get(fn["name"], "")
                    mark = f"`#{link}` در نقشهٔ HTML" if link else "—"
                    out.append(f"| `{fn['name']}()` | {fn['owner'] or '—'} | {fn['line']} | {mark} |")
            out.append("")
    return "\n".join(out) + "\n"


def main():
    catalog = build_catalog()
    html = build_html(catalog)
    out_html = os.path.join(HERE, "index.html")
    with open(out_html, "w", encoding="utf-8") as fh:
        fh.write(html)
    out_md = os.path.join(REPO, "docs", "FUNCTION_CATALOG_FA.md")
    with open(out_md, "w", encoding="utf-8") as fh:
        fh.write(build_catalog_md(catalog))
    tf, tl, tfun = build_stats(catalog)
    print(f"OK  charts={len(ALL_CHARTS)} files={tf} lines={tl} funs={tfun}")
    print(f" -> {out_html} ({os.path.getsize(out_html)//1024} KB)")
    print(f" -> {out_md} ({os.path.getsize(out_md)//1024} KB)")


if __name__ == "__main__":
    main()
