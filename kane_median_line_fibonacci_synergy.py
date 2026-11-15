# -*- coding: utf-8 -*-
"""
==========================================================================
 KANE TRADING ON: MEDIAN LINE AND FIBONACCI SYNERGY  (KMFS)
 تبدیل کل کتاب Jim Kane به یک استراتژی واحد، قابل بک‌تست
==========================================================================

هسته‌ی استراتژی (نقل از خود کتاب):
  «ABCD ها، ترجیحاً در بازگشت‌های .300 / .382 / .447 ، که ادامه‌دهنده‌ی روندی
   هستند که هنوز جا دارد؛ 'context' این فرض را تأیید کند؛ و در ناحیه‌ی PTA
   تأیید از میانه‌خط (ML) بیاید؛ یک خط از ستی که کل حرکت قیمت را پوشش می‌دهد
   + یک یا (ترجیحاً) دو خط ساخته‌شده از خود ABCD.»  → این یعنی Synergy.

نقشه‌ی فصل‌های کتاب به ماژول‌های کد:
  فصل 1  میانه‌خط استاندارد (سه سوینگ آخر، دسته‌ی 1 و 2)،
         division line، warning line ........ median_set(), channel_lines()
  فصل 2  ستِ «Adjusted» (نقطه‌ی 1 «در فضا»، قفل کردن روی II دور/نزدیک/ML)
                                               ........ adjusted_set()
  فصل 3  Modified Schiff + ستِ استاندارد روی خود ABCD
                                               ........ median_set(schiff=True)
  فصل 4  Offset ها (displacement و reflection) ... offset_lines()
  فصل 5  Sliding parallels ....................... sliding_from_slope()
  فصل 6  خطوط روند و کلون‌هایشان ................. trend_lines()
  فصل 7  زمان (برخورد فیبو با خط + تقاطع خطوط) ... time confluence در _build_setup()
  فصل 8  فرآیند Synergy: PTA → گروه‌بندی فیبو → خطوط → امتیاز 1..10
         → تریگر ورود اجباری (بدون fade) → مدیریت معامله

هشدارهای صادقانه:
  * کتاب می‌گوید روش، discretionary است و کِین عمداً کدنویسی/بک‌تست نمی‌کند.
    پس همه‌ی تعریف‌های عددی زیر «برگردان من» از قضاوت چشمی او هستند.
  * تریگر ورود و مدیریت معامله در کتاب‌های دیگر او هست؛ اینجا ساده‌ترین
    نسخه‌ی هم‌خوان با توضیحات این کتاب پیاده شده (EMA کراس + ATR).
  * هیچ ادعایی درباره‌ی سودآوری نمی‌شود. قبل از پول واقعی روی داده‌ی خودتان
    بک‌تست و walk-forward بگیرید.
"""
from __future__ import annotations

import argparse
import bisect
from dataclasses import dataclass, field
from typing import Dict, List, Optional

import numpy as np
import pandas as pd


# ==========================================================================
# 1) پارامترها
# ==========================================================================
@dataclass
class Params:
    # --- اندیکاتورها و سوینگ‌ها
    atr_len: int = 14
    zz_small: float = 1.6      # آستانه‌ی زیگزاگ سوینگ‌های کوچک (ضریب ATR) → نقاط A,B,C
    zz_big: float = 4.5        # آستانه‌ی زیگزاگ سوینگ‌های بزرگ → ست‌های «کل حرکت»
    ema_fast: int = 5          # تریگر ورود (فقط تریگر است، نه سیستم کراس)
    ema_slow: int = 13

    # --- ABCD و فیبوناچی
    trend_min_atr: float = 6.0                 # حداقل اندازه‌ی سوینگ روند X→H
    bc_min: float = 0.382                      # BC/AB : استاندارد .. double top .. expanding
    bc_max: float = 1.272
    proj_ratios: tuple = (1.000, 1.128, 1.272)  # AB=CD و ABCD های جایگزین (1.128 = 'stop run')
    ext_ratios: tuple = (1.272, 1.460)         # external retracement روی BC
    pos_pref: tuple = (0.300, 0.382, 0.447)    # Position numbers ترجیحی کتاب
    pos_other: tuple = (0.500, 0.618, 0.685, 0.786)
    group_tol_atr: float = 0.8                 # پهنای مجاز گروه‌بندی (فیبو grouping)
    zone_pad_atr: float = 0.15

    # --- خطوط
    hit_tol_atr: float = 0.35      # فاصله‌ی مجاز خط تا ناحیه‌ی PTA
    seen_tol_atr: float = 0.35     # قیمت «دیدن» ست: برخورد سوینگ‌ها با خطوط
    slope_min_atr: float = 0.01    # شیب ست: نه خیلی تخت
    slope_max_atr: float = 1.50    # نه خیلی تند (ATR بر بار)
    adj_pairs: int = 3             # چند سوینگ مرجع برای ست Adjusted
    big_sets: int = 3              # چند ست بزرگ آخر

    # --- امتیازدهی (کتاب: فقط 7..9 از 10 ؛ از 10 کامل محتاط است)
    min_rating: float = 7.0
    perfect_cap: float = 9.75
    allow_countertrend: bool = False   # کتاب: عمدتاً با روند context

    # --- ورود / خروج
    max_wait_mult: float = 3.0     # انقضای ستاپ بر حسب طول AB
    invalid_atr: float = 1.0       # عبور از انتهای ناحیه → ستاپ باطل
    max_trigger_bars: int = 12
    cross_lookback: int = 3
    stop_atr: float = 0.35
    max_risk_atr: float = 3.5
    min_rr: float = 1.0
    target_ext: float = 1.272      # «1.272 external retracement» = انتظار متوسط کتاب (روی ساق CD)
    partial_frac: float = 0.5
    trail_atr: float = 2.0
    max_hold: int = 250
    cost_price: float = 0.0        # اسپرد/کمیسیون به واحد قیمت (هر رفت‌وبرگشت)


# ==========================================================================
# 2) ساختارهای داده
# ==========================================================================
@dataclass
class Pv:           # pivot
    i: int          # اندیس بار اکستریم
    p: float        # قیمت
    k: int          # +1 سقف ، -1 کف
    c: int          # اندیس بار تأیید (بدون lookahead)


@dataclass
class Line:
    m: float
    b: float
    name: str
    fam: str        # BIG | ABCD_ML | ABCD_MS | ADJ | TL | OFF
    w: float
    sid: int

    def y(self, x: float) -> float:
        return self.m * x + self.b


@dataclass
class Setup:
    s: int
    t: int
    A: Pv
    B: Pv
    C: Pv
    X: Pv
    H: float
    zone_lo: float
    zone_hi: float
    x0: float
    x1: float
    expire: int
    rating: float
    parts: Dict[str, float]
    group: list
    hits: list
    lines: List[Line] = field(repr=False, default_factory=list)
    touched: bool = False
    touch_i: int = -1
    low: float = 0.0

    def explain(self) -> str:
        d = "LONG" if self.s == 1 else "SHORT"
        out = [f"{d} setup @bar {self.t}  rating={self.rating:.2f}",
               f"  PTA zone (view)=[{self.zone_lo:.5f}, {self.zone_hi:.5f}]"]
        out += [f"  {k:<14}{v:5.2f}" for k, v in self.parts.items()]
        out += ["  group: " + ", ".join(g[1] for g in self.group)]
        out += ["  lines: " + ", ".join(f"{L.fam}:{L.name}@{xs:.0f}" for L, xs in self.hits[:12])]
        return "\n".join(out)

    def lines_orig(self):
        """خطوط به مختصات واقعی قیمت (برای رسم)."""
        return [(self.s * L.m, self.s * L.b, L.fam, L.name) for L in self.lines]


# ==========================================================================
# 3) زیگزاگ علّی (بدون نگاه به آینده)
# ==========================================================================
def zigzag(h: np.ndarray, l: np.ndarray, atr: np.ndarray, mult: float) -> List[Pv]:
    n = len(h)
    piv: List[Pv] = []
    d = 0
    hi, hi_i, lo, lo_i = h[0], 0, l[0], 0
    for i in range(1, n):
        th = mult * atr[i]
        if d == 0:
            if h[i] > hi:
                hi, hi_i = h[i], i
            if l[i] < lo:
                lo, lo_i = l[i], i
            if h[i] - lo >= th and lo_i <= i:
                piv.append(Pv(lo_i, lo, -1, i)); d = 1; hi, hi_i = h[i], i
            elif hi - l[i] >= th:
                piv.append(Pv(hi_i, hi, 1, i)); d = -1; lo, lo_i = l[i], i
        elif d == 1:
            if h[i] > hi:
                hi, hi_i = h[i], i
            elif hi - l[i] >= th:
                piv.append(Pv(hi_i, hi, 1, i)); d = -1; lo, lo_i = l[i], i
        else:
            if l[i] < lo:
                lo, lo_i = l[i], i
            elif h[i] - lo >= th:
                piv.append(Pv(lo_i, lo, -1, i)); d = 1; hi, hi_i = h[i], i
    return piv


def to_view(pivs: List[Pv], s: int) -> List[Pv]:
    """برای حالت SHORT قیمت را قرینه می‌کنیم تا همه‌ی منطق فقط یک‌بار (LONG) نوشته شود."""
    return [Pv(p.i, s * p.p, s * p.k, p.c) for p in pivs]


# ==========================================================================
# 4) ابزارهای خط (فصل‌های 1 تا 6)
# ==========================================================================
def channel_lines(m, b_up, b_lo, fam, sid) -> List[Line]:
    """ML + دو موازی + division line + warning line (فصل 1)."""
    if b_up < b_lo:
        b_up, b_lo = b_lo, b_up
    h = (b_up - b_lo) / 2.0
    if h < 1e-9:
        return []
    bm = (b_up + b_lo) / 2.0
    spec = [("ML", bm, 1.0), ("UP", b_up, 1.0), ("LO", b_lo, 1.0),
            ("DIV_U", bm + h / 2, .7), ("DIV_L", bm - h / 2, .7),
            ("DIV_UU", b_up + h / 2, .5), ("DIV_LL", b_lo - h / 2, .5),
            ("WARN_U1", b_up + h, .5), ("WARN_L1", b_lo - h, .5),
            ("WARN_U2", b_up + 2 * h, .4), ("WARN_L2", b_lo - 2 * h, .4)]
    return [Line(m, b, n, fam, w, sid) for n, b, w in spec]


def median_set(P1: Pv, P2: Pv, P3: Pv, fam: str, sid: int, schiff: bool = False) -> List[Line]:
    """ست استاندارد (فصل 1) یا Modified Schiff (فصل 3: مبدأ ML = وسط P1-P2 هم در زمان هم قیمت)."""
    x1, y1 = (P1.i, P1.p)
    if schiff:
        x1, y1 = (P1.i + P2.i) / 2.0, (P1.p + P2.p) / 2.0
    xm, ym = (P2.i + P3.i) / 2.0, (P2.p + P3.p) / 2.0
    if xm <= x1:
        return []
    m = (ym - y1) / (xm - x1)
    return channel_lines(m, P2.p - m * P2.i, P3.p - m * P3.i, fam, sid)


def adjusted_set(R2: Pv, R3: Pv, C: Pv, mode: str, sid: int) -> List[Line]:
    """
    ست Adjusted (فصل 2): نقطه‌ی 1 «در فضا» است. R2,R3 = سوینگ مرجع (آخرین thrust یا
    اولین pullback). نقطه‌ی C (سوینگ جدید) را روی یکی از خطوط «قفل» می‌کنیم:
      distal   : C روی II دور (قفل محبوب کتاب؛ II مقابل = هدف اصلاح)
      proximal : C روی II نزدیک
      ml       : C روی خود ML
    شیب ست از قفل شدن به‌دست می‌آید؛ پهنای کانال از سوینگ مرجع (تعادل قیمت-زمان).
    """
    hi_pt, lo_pt = (R2, R3) if R2.p > R3.p else (R3, R2)
    if mode == "distal":
        x0, y0 = hi_pt.i, hi_pt.p
    elif mode == "proximal":
        x0, y0 = lo_pt.i, lo_pt.p
    else:
        x0, y0 = (R2.i + R3.i) / 2.0, (R2.p + R3.p) / 2.0
    dx = C.i - x0
    if dx <= 0:
        return []
    m = (C.p - y0) / dx
    bu, bl = hi_pt.p - m * hi_pt.i, lo_pt.p - m * lo_pt.i
    if bu <= bl:
        return []
    return channel_lines(m, bu, bl, "ADJ", sid)


def trend_lines(piv_v: List[Pv], sid0: int) -> List[Line]:
    """فصل 6: خط روند بین دو سقف/کف آخر + کلون موازی (sliding) روی اکستریم مقابل."""
    out, sid = [], sid0
    for kind in (1, -1):
        ps = [p for p in piv_v if p.k == kind][-3:]
        for a, b in zip(ps[:-1], ps[1:]):
            if b.i <= a.i:
                continue
            m = (b.p - a.p) / (b.i - a.i)
            out.append(Line(m, a.p - m * a.i, f"TL{kind}", "TL", 0.8, sid))
            opp = [q for q in piv_v if q.k == -kind and a.i < q.i < b.i]
            if opp:
                q = min(opp, key=lambda z: z.p) if kind == 1 else max(opp, key=lambda z: z.p)
                out.append(Line(m, q.p - m * q.i, "SLIDE", "TL", 0.6, sid))
            sid += 1
    return out


def sliding_from_slope(m: float, piv_v: List[Pv], lines: List[Line], atr: float, sid: int) -> List[Line]:
    """فصل 5: کلون موازی خطِ یک ست روی سوینگ‌های برجسته‌ای که روی هیچ خطی نیستند."""
    out = []
    for q in piv_v[-6:]:
        d = min((abs(q.p - L.y(q.i)) for L in lines), default=1e9)
        if d > 0.3 * atr:
            out.append(Line(m, q.p - m * q.i, "SLIDE", "TL", 0.6, sid))
    return out


def offset_lines(Ls: List[Line], piv_v: List[Pv], since_i: int, until_i: int, atr: float) -> List[Line]:
    """
    فصل 4: اگر قیمت از خطی d واحد جا ماند/رد شد (miss = قیمت − خط)،
      displacement : کل ست به اندازه‌ی +miss جابجا می‌شود
      reflection   : کل ست به اندازه‌ی −miss جابجا می‌شود
    """
    if not Ls:
        return []
    base = [L for L in Ls if L.name in ("ML", "UP", "LO", "DIV_U", "DIV_L")]
    up = next((L for L in Ls if L.name == "UP"), None)
    lo = next((L for L in Ls if L.name == "LO"), None)
    if not base or up is None or lo is None:
        return []
    h = abs(up.b - lo.b) / 2.0
    misses = []
    for q in piv_v:
        if not (since_i < q.i < until_i):
            continue
        L = min(base, key=lambda z: abs(q.p - z.y(q.i)))
        d = q.p - L.y(q.i)
        if 0.12 * atr <= abs(d) <= 0.5 * h:
            misses.append(d)
    out = []
    for d in misses[-2:]:
        for sign, tag in ((1, "DISP"), (-1, "REFL")):
            for L in Ls:
                out.append(Line(L.m, L.b + sign * d, f"{tag}_{L.name}", "OFF", 0.6 * L.w, L.sid))
    return out


def line_hit(L: Line, zlo: float, zhi: float, x0: float, x1: float, tol: float) -> Optional[float]:
    """آیا خط در بازه‌ی زمانی [x0,x1] از ناحیه‌ی PTA می‌گذرد؟ اگر بله، زمانِ برخورد را بده."""
    y0, y1 = L.y(x0), L.y(x1)
    lo, hi = min(y0, y1), max(y0, y1)
    if hi < zlo - tol or lo > zhi + tol:
        return None
    zm = 0.5 * (zlo + zhi)
    xs = 0.5 * (x0 + x1) if abs(L.m) < 1e-12 else (zm - L.b) / L.m
    return float(min(max(xs, x0), x1))


def seen_ratio(Ls: List[Line], piv_v: List[Pv], start_i: int, end_i: int,
               excl: set, atr: float, tol_atr: float) -> float:
    """کتاب: ست باید خودش را «ثابت» کند؛ شمارش برخورد سوینگ‌های قبلی (حتی قبل از لنگر 3) با خطوط."""
    main = [L for L in Ls if L.name in ("ML", "UP", "LO", "DIV_U", "DIV_L")]
    if not main:
        return 0.0
    cnt = 0
    for q in piv_v:
        if q.i <= start_i or q.i > end_i or q.i in excl:
            continue
        if min(abs(q.p - L.y(q.i)) for L in main) <= tol_atr * atr:
            cnt += 1
    return min(1.0, cnt / 3.0)


def best_group(levels: list, tol: float):
    """گروه‌بندی فیبوناچی: خوشه‌ای با حداقل یک عدد pattern و یک عدد position."""
    best = None
    lv = sorted(levels, key=lambda z: z[0])
    for i in range(len(lv)):
        grp = [z for z in lv[i:] if z[0] - lv[i][0] <= tol]
        kinds = {z[2] for z in grp}
        if "pat" in kinds and "pos" in kinds:
            sc = (len(grp), any(z[3] for z in grp), -(grp[-1][0] - grp[0][0]))
            if best is None or sc > best[0]:
                best = (sc, grp)
    return best[1] if best else None


# ==========================================================================
# 5) موتور استراتژی
# ==========================================================================
class KaneSynergy:
    def __init__(self, df: pd.DataFrame, params: Params = Params()):
        self.p = params
        self.df = df.reset_index(drop=True)
        d = self.df
        self.o, self.h, self.l, self.c = (d[k].to_numpy(float) for k in ("open", "high", "low", "close"))
        n = len(d)
        pc = np.r_[self.c[0], self.c[:-1]]
        tr = np.maximum.reduce([self.h - self.l, np.abs(self.h - pc), np.abs(self.l - pc)])
        self.atr = pd.Series(tr).ewm(alpha=1.0 / params.atr_len, adjust=False).mean().to_numpy()
        cs = pd.Series(self.c)
        ema = lambda k: cs.ewm(span=k, adjust=False).mean().to_numpy()
        self.ema50, self.ema100, self.ema200 = ema(50), ema(100), ema(200)
        ef, es = ema(params.ema_fast), ema(params.ema_slow)
        self.small = zigzag(self.h, self.l, self.atr, params.zz_small)
        self.big = zigzag(self.h, self.l, self.atr, params.zz_big)
        self.big_c = [q.c for q in self.big]
        self.Hv = {1: self.h, -1: -self.l}
        self.Lv = {1: self.l, -1: -self.h}
        self.Ov = {1: self.o, -1: -self.o}
        self.Cv = {1: self.c, -1: -self.c}
        self.Ef = {1: ef, -1: -ef}
        self.Es = {1: es, -1: -es}
        self.setups: List[Setup] = []   # همه‌ی ستاپ‌های ساخته‌شده (برای بررسی/رسم)

    # ---------------- context (چند تایم‌فریم/روند بزرگتر) ----------------
    def context_dir(self, t: int) -> int:
        k = bisect.bisect_right(self.big_c, t)
        bp = self.big[:k]
        hs = [q for q in bp if q.k == 1][-2:]
        ls = [q for q in bp if q.k == -1][-2:]
        st = 0
        if len(hs) == 2 and len(ls) == 2:
            if hs[1].p > hs[0].p and ls[1].p > ls[0].p:
                st = 1
            elif hs[1].p < hs[0].p and ls[1].p < ls[0].p:
                st = -1
        sc = st + np.sign(self.c[t] - self.ema100[t]) + np.sign(self.ema50[t] - self.ema200[t])
        return 1 if sc >= 2 else (-1 if sc <= -2 else 0)

    # ---------------- ساخت ستاپ در لحظه‌ی تأیید نقطه‌ی C ----------------
    def _build_setup(self, t: int, ck: int, s: int) -> Optional[Setup]:
        p = self.p
        sv = to_view(self.small[max(0, ck - 14): ck + 1], s)
        if len(sv) < 4:
            return None
        C, B, A = sv[-1], sv[-2], sv[-3]
        if C.k != 1 or B.k != -1 or A.k != 1:
            return None
        atr = self.atr[t]
        kb = bisect.bisect_right(self.big_c, t)
        bigv = to_view(self.big[:kb], s)
        X = next((q for q in reversed(bigv) if q.k == -1 and q.i < A.i), None)
        if X is None or X.p >= B.p:
            return None
        H = float(self.Hv[s][X.i: C.i + 1].max())
        AB, BC = A.p - B.p, C.p - B.p
        if AB <= 0 or not (p.bc_min <= BC / AB <= p.bc_max):
            return None
        if (H - X.p) < p.trend_min_atr * atr:
            return None
        ctx = self.context_dir(t)
        if ctx == -s and not p.allow_countertrend:
            return None

        # ---- (الف) PTA: گروه‌بندی فیبوناچی (pattern numbers + position numbers)
        lev = []
        for k in p.proj_ratios:
            lev.append((C.p - k * AB, f"AB=CD {k}", "pat", False))
        for r in p.ext_ratios:
            lev.append((C.p - r * BC, f"BCext {r}", "pat", False))
        for r in p.pos_pref:
            lev.append((H - r * (H - X.p), f"pos {r}", "pos", True))
        for r in p.pos_other:
            lev.append((H - r * (H - X.p), f"pos {r}", "pos", False))
        lev = [z for z in lev if X.p < z[0] < C.p]
        grp = best_group(lev, p.group_tol_atr * atr)
        if not grp:
            return None
        zlo = min(z[0] for z in grp) - p.zone_pad_atr * atr
        zhi = max(z[0] for z in grp) + p.zone_pad_atr * atr
        width = zhi - zlo - 2 * p.zone_pad_atr * atr

        abb = max(1, B.i - A.i)
        x0 = max(t + 1.0, C.i + 0.5 * abb)
        x1 = max(x0 + 3.0, C.i + 2.0 * abb + 5.0)
        tol = p.hit_tol_atr * atr

        # ---- (ب) خطوط از همه‌ی خانواده‌ها
        lines: List[Line] = []
        sid = 0
        set_meta: Dict[int, dict] = {}

        def add(Ls, fam, anchors, start_i, third_i):
            nonlocal sid
            if not Ls:
                return
            m = Ls[0].m
            if not (p.slope_min_atr * atr <= abs(m) <= p.slope_max_atr * atr):
                # ست خیلی تخت/تند: کتاب ترجیح نمی‌دهد (ولی کاملاً حذف نمی‌کند → وزن کم)
                for L in Ls:
                    L.w *= 0.5
            set_meta[sid] = dict(fam=fam, m=m, start=start_i, third=third_i,
                                 excl={q.i for q in anchors}, lines=Ls)
            lines.extend(Ls)
            sid += 1

        # ست‌های بزرگ (کل حرکت قیمت) از سوینگ‌های بزرگ
        for j in range(max(0, len(bigv) - 2 - p.big_sets), len(bigv) - 2):
            P1, P2, P3 = bigv[j:j + 3]
            Ls = median_set(P1, P2, P3, "BIG", sid)
            if Ls and Ls[0].m < 0:        # ست خلاف روند (برای LONG) وزن کمتر
                for L in Ls:
                    L.w *= 0.6
            add(Ls, "BIG", [P1, P2, P3], P1.i, P3.i)

        # دو ست روی خود الگو: استاندارد و Modified Schiff (فصل 3)
        add(median_set(A, B, C, "ABCD_ML", sid), "ABCD_ML", [A, B, C], A.i, C.i)
        add(median_set(A, B, C, "ABCD_MS", sid, schiff=True), "ABCD_MS", [A, B, C], A.i, C.i)

        # ست‌های Adjusted (فصل 2) با سه حالت قفل
        before = sv[:-1]
        for j in range(p.adj_pairs):
            if len(before) < 2 + j:
                break
            R2, R3 = before[-2 - j], before[-1 - j]
            for mode in ("distal", "proximal", "ml"):
                add(adjusted_set(R2, R3, C, mode, sid), "ADJ", [R2, R3, C], R2.i, R3.i)

        # خطوط روند + sliding parallels (فصل 5 و 6)
        tl = trend_lines(sv, 1000)
        lines.extend(tl)
        for sid_k, meta in list(set_meta.items())[:6]:
            lines.extend(sliding_from_slope(meta["m"], sv, meta["lines"], atr, 2000 + sid_k))

        # Offset ها (فصل 4) روی ست‌های بزرگ و Adjusted
        for sid_k, meta in set_meta.items():
            if meta["fam"] in ("BIG", "ADJ"):
                lines.extend(offset_lines(meta["lines"], sv, meta["third"], C.i, atr))

        # ---- (ج) برخورد خطوط با PTA + «دیده‌شدن» ست + زمان
        seen = {k: seen_ratio(m["lines"], sv, m["start"], C.i, m["excl"], atr, p.seen_tol_atr)
                for k, m in set_meta.items()}
        hits, fam_best, xs_by_fam = [], {}, {}
        for L in lines:
            xs = line_hit(L, zlo, zhi, x0, x1, tol)
            if xs is None:
                continue
            w = L.w
            if L.sid in seen:
                w *= 0.5 + 0.5 * seen[L.sid]
            hits.append((L, xs))
            fam_best[L.fam] = max(fam_best.get(L.fam, 0.0), w)
            fam_best[(L.fam, L.sid)] = max(fam_best.get((L.fam, L.sid), 0.0), w)
            xs_by_fam.setdefault(L.fam, []).append(xs)
        if not hits:
            return None

        # فاکتور زمان (فصل 7): چند خانواده/خط در یک پنجره‌ی زمانی هم‌زمان می‌رسند
        tw = max(3.0, 0.15 * abb)
        allx = sorted((xs, L.fam) for L, xs in hits)
        best_n, best_f = 0, 0
        for xi, _ in allx:
            inw = [f for xx, f in allx if abs(xx - xi) <= tw]
            if len(inw) > best_n:
                best_n, best_f = len(inw), len(set(inw))
        time_score = 1.0 if best_f >= 2 and best_n >= 3 else (0.5 if best_f >= 2 else 0.0)

        # ---- (د) امتیاز 1..10 (مجموع وزن‌ها = 10)
        parts: Dict[str, float] = {}
        ratio = BC / AB
        pref = any(z[3] for z in grp)
        parts["pattern"] = (1.0 if pref else 0.5) \
            + (0.5 if 0.5 <= ratio <= 0.9 else 0.3) \
            + (0.5 if 0.3 <= (C.i - B.i) / abb <= 1.5 else 0.0)
        parts["grouping"] = min(0.9, 0.3 * (len(grp) - 1)) \
            + (0.6 if width <= 0.5 * atr else 0.3 if width <= 1.0 * atr else 0.0)
        parts["big_set"] = 1.5 * fam_best.get("BIG", 0.0)
        parts["abcd_sets"] = min(2.0, 1.0 * fam_best.get("ABCD_ML", 0.0) + 1.0 * fam_best.get("ABCD_MS", 0.0))
        parts["adjusted"] = 1.0 * fam_best.get("ADJ", 0.0)
        parts["tl_off_slide"] = 0.5 * max(fam_best.get("TL", 0.0), fam_best.get("OFF", 0.0))
        parts["time"] = 1.0 * time_score
        parts["context"] = 0.5 if ctx == s else 0.0
        rating = float(min(10.0, sum(parts.values())))

        return Setup(s=s, t=t, A=A, B=B, C=C, X=X, H=H, zone_lo=zlo, zone_hi=zhi,
                     x0=x0, x1=x1, expire=int(t + p.max_wait_mult * abb + 10),
                     rating=rating, parts=parts, group=grp, hits=hits, lines=lines)

    # ---------------- منطق ورود: بدون fade، فقط با تریگر ----------------
    def _check(self, st: Setup, t: int) -> str:
        p, s = self.p, st.s
        atr = self.atr[t]
        Lv = self.Lv[s]
        if t > st.expire or Lv[t] < st.zone_lo - p.invalid_atr * atr:
            return "drop"
        if not st.touched:
            if Lv[t] <= st.zone_hi + p.hit_tol_atr * atr:
                st.touched, st.touch_i, st.low = True, t, Lv[t]
            return "wait"
        st.low = min(st.low, Lv[t])
        if t - st.touch_i > p.max_trigger_bars:
            return "drop"
        ef, es, Cv = self.Ef[s], self.Es[s], self.Cv[s]
        crossed = any(ef[k] > es[k] and ef[k - 1] <= es[k - 1]
                      for k in range(max(1, t - p.cross_lookback + 1), t + 1))
        if crossed and ef[t] > es[t] and Cv[t] > ef[t]:
            return "trigger"
        return "wait"

    def _scale(self, r: float) -> float:
        if r >= self.p.perfect_cap:      # «از 10 کامل محتاطم»
            return 1.0
        return float(np.interp(r, [7.0, 8.0, 9.0], [0.5, 1.0, 1.25]))

    # ---------------- حلقه‌ی اصلی ----------------
    def run(self) -> pd.DataFrame:
        p = self.p
        n = len(self.c)
        warm = max(220, p.atr_len * 3)
        active: List[Setup] = []
        pending = None
        trade = None
        trades = []
        piv_by_confirm: Dict[int, List[int]] = {}
        for k, q in enumerate(self.small):
            piv_by_confirm.setdefault(q.c, []).append(k)

        for t in range(warm, n):
            # 1) اجرای سفارش pending در open همین بار
            if pending is not None and trade is None:
                st, stop_v, t1_v, ds = pending
                s = st.s
                entry = self.Ov[s][t]
                risk = entry - stop_v
                if 0 < risk <= p.max_risk_atr * self.atr[t] and (t1_v - entry) / risk >= p.min_rr:
                    trade = dict(s=s, rating=st.rating, scale=self._scale(st.rating),
                                 entry_i=t, entry=entry, stop=stop_v, stop0=stop_v, t1=t1_v,
                                 risk=risk, frac=1.0, realized=0.0, t1_done=False,
                                 hh=entry, atr=self.atr[t])
                    active = [a for a in active if a is not st]
            pending = None

            # 2) مدیریت معامله‌ی باز
            if trade is not None:
                done = self._manage(trade, t)
                if done:
                    trades.append(done)
                    trade = None

            # 3) ستاپ‌های جدید در لحظه‌ی تأیید C
            for ck in piv_by_confirm.get(t, []):
                for s in (1, -1):
                    st = self._build_setup(t, ck, s)
                    if st is not None and st.rating >= p.min_rating:
                        self.setups.append(st)
                        active.append(st)
            active = active[-8:]

            # 4) بررسی تریگر ستاپ‌های فعال
            if trade is None and pending is None:
                for st in sorted(active, key=lambda z: -z.rating):
                    act = self._check(st, t)
                    if act == "trigger":
                        s = st.s
                        stop_v = min(st.low, st.zone_lo) - p.stop_atr * self.atr[t]
                        D = st.low
                        t1_v = D + p.target_ext * (st.C.p - D)
                        pending = (st, stop_v, t1_v, D)
                        break
                active = [a for a in active if self._check_alive(a, t)]
        return pd.DataFrame(trades)

    def _check_alive(self, st: Setup, t: int) -> bool:
        if t > st.expire:
            return False
        if st.touched and t - st.touch_i > self.p.max_trigger_bars:
            return False
        if self.Lv[st.s][t] < st.zone_lo - self.p.invalid_atr * self.atr[t]:
            return False
        return True

    def _manage(self, tr: dict, t: int):
        p, s = self.p, tr["s"]
        Hv, Lv, Ov, Cv = self.Hv[s][t], self.Lv[s][t], self.Ov[s][t], self.Cv[s][t]
        px, reason = None, None
        if Lv <= tr["stop"]:
            px = min(Ov, tr["stop"])
            reason = "stop" if not tr["t1_done"] else "trail/BE"
        elif not tr["t1_done"] and Hv >= tr["t1"]:
            tr["realized"] += p.partial_frac * (tr["t1"] - tr["entry"])
            tr["frac"] -= p.partial_frac
            tr["t1_done"] = True
            tr["stop"] = max(tr["stop"], tr["entry"])
        if px is None and tr["t1_done"]:
            tr["hh"] = max(tr["hh"], Hv)
            tr["stop"] = max(tr["stop"], tr["hh"] - p.trail_atr * tr["atr"])
        if px is None and t - tr["entry_i"] >= p.max_hold:
            px, reason = Cv, "time"
        if px is None:
            return None
        pnl = tr["realized"] + tr["frac"] * (px - tr["entry"])
        R = pnl / tr["risk"] - p.cost_price / tr["risk"]
        return dict(side="LONG" if s == 1 else "SHORT", entry_i=tr["entry_i"], exit_i=t,
                    rating=round(tr["rating"], 2), scale=tr["scale"], R=R,
                    R_weighted=R * tr["scale"], reason=reason, bars=t - tr["entry_i"])


# ==========================================================================
# 6) آمار، داده‌ی آزمایشی، رسم، CLI
# ==========================================================================
def stats(tr: pd.DataFrame) -> dict:
    if tr.empty:
        return {"trades": 0}
    w, lo = tr[tr.R > 0].R.sum(), -tr[tr.R <= 0].R.sum()
    eq = tr.R_weighted.cumsum()
    return {"trades": len(tr), "win_rate": float((tr.R > 0).mean()),
            "avg_R": float(tr.R.mean()), "profit_factor": float(w / lo) if lo > 0 else float("inf"),
            "total_R_weighted": float(tr.R_weighted.sum()),
            "max_DD_R": float((eq.cummax() - eq).max())}


def make_synthetic(n: int = 6000, seed: int = 7) -> pd.DataFrame:
    """فقط برای تست اجرای کد (نه ارزیابی سودآوری): قیمت رِژیم‌دار با روند و اصلاح."""
    rng = np.random.default_rng(seed)
    r = np.zeros(n)
    i = 0
    while i < n:
        L = int(rng.integers(60, 260)); mu = rng.choice([-1, 1]) * rng.uniform(0.0002, 0.0007)
        r[i:i + L] = mu + rng.normal(0, 0.0025, min(L, n - i)); i += L
    c = 100 * np.exp(np.cumsum(r)); o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.0012, n)))
    l = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.0012, n)))
    return pd.DataFrame(dict(open=o, high=h, low=l, close=c))


def plot_setup(df: pd.DataFrame, st: Setup, before: int = 150, after: int = 80):
    """رسم ستاپ: کندل‌ها، ناحیه‌ی PTA و خطوطی که به آن می‌خورند (برای تأیید بصری مثل کِین)."""
    import matplotlib.pyplot as plt
    a, b = max(0, st.A.i - 20), min(len(df), st.t + after)
    fig, ax = plt.subplots(figsize=(12, 6))
    xs = np.arange(a, b)
    ax.vlines(xs, df.low[a:b], df.high[a:b], lw=.6, color="gray")
    ax.plot(xs, df.close[a:b], lw=.8, color="k")
    ax.axhspan(st.s * st.zone_lo, st.s * st.zone_hi, alpha=.25, color="gold", label="PTA")
    seen = set()
    for L, _ in st.hits:
        if (L.fam, L.name, round(L.b, 6)) in seen:
            continue
        seen.add((L.fam, L.name, round(L.b, 6)))
        ax.plot(xs, st.s * (L.m * xs + L.b), lw=.9, label=f"{L.fam}:{L.name}")
    lo_, hi_ = df.low[a:b].min(), df.high[a:b].max()
    ax.set_ylim(lo_ - (hi_ - lo_) * .05, hi_ + (hi_ - lo_) * .05)
    ax.set_title(f"{'LONG' if st.s == 1 else 'SHORT'}  rating={st.rating:.1f}")
    ax.legend(fontsize=6, ncol=3)
    plt.show()


def main():
    ap = argparse.ArgumentParser(description="Kane Median Line & Fibonacci Synergy backtest")
    ap.add_argument("csv", nargs="?", help="CSV با ستون‌های open,high,low,close (ستون اضافه مجاز)")
    ap.add_argument("--min-rating", type=float, default=7.0)
    ap.add_argument("--synthetic", action="store_true", help="اجرا روی داده‌ی آزمایشی")
    ap.add_argument("--explain", type=int, default=0, help="چاپ جزئیات N ستاپ اول")
    a = ap.parse_args()
    df = make_synthetic() if (a.synthetic or not a.csv) else pd.read_csv(a.csv)
    df.columns = [c.lower() for c in df.columns]
    eng = KaneSynergy(df, Params(min_rating=a.min_rating))
    tr = eng.run()
    print(f"ستاپ‌های پذیرفته‌شده: {len(eng.setups)}")
    print(stats(tr))
    for st in eng.setups[:a.explain]:
        print(st.explain())
    return eng, tr


if __name__ == "__main__":
    main()
