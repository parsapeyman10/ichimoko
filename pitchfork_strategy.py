#!/usr/bin/env python3
"""
Trading with the Pitchfork -- Python implementation
====================================================
Based on Gordon DeRoos' manual "Trading with the Pitchfork" (Andrews median-line method).

Two strategies from the book:
  A) Mini-median line (trend continuation):  reverse divergence -> close crosses
     5-day SMA -> mini pitchfork from closes -> breakthrough of parallel ->
     sliding parallel -> entry on sliding-parallel penetration.
     Stop: a couple of ticks beyond the mini-pitchfork parallel line.
  B) Price failure (trend change): price turns before the median line ->
     penetrates a parallel -> sliding parallel -> Hagopian line -> standard
     divergence. Entry: a couple of ticks beyond the Hagopian line.

IMPORTANT: the book describes a discretionary method. Turning it into rules
required interpretation. Items marked [MY CHOICE] are NOT from the book.
Educational code, not investment advice. Backtest and paper-trade before risking money.

Usage:
    python pitchfork_strategy.py                 # synthetic demo data
    python pitchfork_strategy.py data.csv        # CSV with date,open,high,low,close
    python pitchfork_strategy.py data.csv --weekly --ma 21 --tick 0.05

Requires: numpy, pandas
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass

import numpy as np
import pandas as pd


# --------------------------------------------------------------------------
# Parameters
# --------------------------------------------------------------------------
@dataclass
class Params:
    rsi_len: int = 14               # oscillator (book: RSI or MACD, user adjustable)
    ma_len: int = 5                 # book: 5 (or 21 for fewer whipsaws)
    swing_k: int = 3                # bars each side to confirm a swing   [MY CHOICE]
    trend_len: int = 50             # SMA used as dominant-trend proxy    [MY CHOICE]
    trend_slope_bars: int = 10      # slope lookback for trend proxy      [MY CHOICE]
    div_lookback: int = 15          # how long a divergence stays valid   [MY CHOICE]
    max_wait: int = 20              # bars before a setup expires         [MY CHOICE]
    tick: float = 0.01              # price tick size of your instrument
    stop_ticks: int = 2             # "a couple of ticks" (book)
    use_weekly_filter: bool = False # require weekly trend agreement (book advice)
    risk_pct: float = 0.01          # equity risked per trade             [MY CHOICE]
    commission_pct: float = 0.0005  # per side, fraction of notional      [MY CHOICE]
    initial_equity: float = 100_000.0
    trail_atr: float = 0.0          # >0 enables an ATR ratchet stop      [MY CHOICE]
    atr_len: int = 14


@dataclass
class Signal:
    bar: int
    side: int        # +1 buy, -1 sell
    entry: float
    stop: float
    kind: str        # "mini_median" or "price_failure"


# --------------------------------------------------------------------------
# Building blocks
# --------------------------------------------------------------------------
def rsi(close: pd.Series, n: int) -> pd.Series:
    d = close.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    return (100 - 100 / (1 + up / dn)).fillna(50)


def atr(df: pd.DataFrame, n: int) -> np.ndarray:
    pc = df["close"].shift(1)
    tr = pd.concat([df["high"] - df["low"], (df["high"] - pc).abs(),
                    (df["low"] - pc).abs()], axis=1).max(axis=1)
    return tr.rolling(n).mean().to_numpy()


def find_swings(h: np.ndarray, l: np.ndarray, k: int):
    """Swing highs/lows as lists of (idx, price, confirm_idx). A swing at i is
    only known k bars later, so there is no look-ahead."""
    sh, sl = [], []
    for i in range(k, len(h) - k):
        if np.argmax(h[i - k:i + k + 1]) == k:
            sh.append((i, h[i], i + k))
        if np.argmin(l[i - k:i + k + 1]) == k:
            sl.append((i, l[i], i + k))
    return sh, sl


class Fork:
    """Andrews pitchfork. Median line starts at P1 and passes through the
    midpoint of P2-P3; parallels run through P2 and P3."""

    def __init__(self, p1, p2, p3):
        self.p1, self.p2, self.p3 = p1, p2, p3
        mx, my = (p2[0] + p3[0]) / 2, (p2[1] + p3[1]) / 2
        self.m = (my - p1[1]) / (mx - p1[0])

    def _through(self, p, x):
        return p[1] + self.m * (x - p[0])

    def median(self, x):
        return self._through(self.p1, x)

    def upper(self, x):
        return np.maximum(self._through(self.p2, x), self._through(self.p3, x))

    def lower(self, x):
        return np.minimum(self._through(self.p2, x), self._through(self.p3, x))


def portion_beyond(hi: float, lo: float, line: float) -> float:
    """Share of the bar's range lying above `line` (book: breakthrough = all or
    the largest part of the range beyond the parallel)."""
    rng = hi - lo
    if rng <= 0:
        return 1.0 if hi > line else 0.0
    return max(0.0, hi - max(lo, line)) / rng


def weekly_trend(df: pd.DataFrame) -> np.ndarray:
    """+1/-1/0 weekly trend from a 10-week SMA slope, lagged one completed
    week to avoid look-ahead.                                      [MY CHOICE]"""
    w = df["close"].resample("W").last().dropna()
    ma = w.rolling(10).mean()
    tr = np.sign(ma - ma.shift(4)).shift(1)
    return tr.reindex(df.index, method="ffill").fillna(0).to_numpy()


def flip(o, h, l, c, r):
    """Mirror the market so SELL logic can reuse BUY logic."""
    return -o, -l, -h, -c, 100 - r


# --------------------------------------------------------------------------
# Strategy A: mini-median line (BUY version; SELL uses mirrored prices)
# --------------------------------------------------------------------------
def _mini_fork(c, ma, t):
    """P3 = first close above the MA; P2 = lowest close of the stretch below
    the MA before it; P1 = highest close of the stretch above the MA before that."""
    i = t - 1
    while i >= 0 and c[i] <= ma[i]:
        i -= 1
    if i < 0 or t - 1 - i < 1:
        return None
    p2i = i + 1 + int(np.argmin(c[i + 1:t]))
    j = i
    while j >= 0 and c[j] > ma[j]:
        j -= 1
    if j == i:
        return None
    p1i = j + 1 + int(np.argmax(c[j + 1:i + 1]))
    f = Fork((p1i, c[p1i]), (p2i, c[p2i]), (t, c[t]))
    return f if f.m < 0 else None   # corrective (downward) fork inside an uptrend


def mini_median_buy(o, h, l, c, r, p: Params, htf=None):
    n = len(c)
    ma = pd.Series(c).rolling(p.ma_len).mean().to_numpy()
    tma = pd.Series(c).rolling(p.trend_len).mean().to_numpy()
    _, sl = find_swings(h, l, p.swing_k)

    # bullish REVERSE divergence: price higher low, oscillator lower low
    div_bars = {b[2] for a, b in zip(sl[:-1], sl[1:])
                if b[1] > a[1] and r[b[0]] < r[a[0]]}

    sigs, last_div, setup = [], -10 ** 9, None
    start = max(p.trend_len + p.trend_slope_bars, p.ma_len + 2)
    for t in range(start, n):
        if t in div_bars:
            last_div = t
        if setup is None:
            uptrend = c[t] > tma[t] and tma[t] > tma[t - p.trend_slope_bars]
            if htf is not None and htf[t] <= 0:
                uptrend = False
            crossed = c[t] > ma[t] and c[t - 1] <= ma[t - 1]
            if uptrend and crossed and t - last_div <= p.div_lookback:
                f = _mini_fork(c, ma, t)
                if f:
                    setup = {"fork": f, "created": t, "slide": None}
            continue

        f = setup["fork"]
        if t - setup["created"] > p.max_wait:
            setup = None
            continue
        if setup["slide"] is None:
            # breakthrough of the upper parallel -> sliding parallel from that bar's low
            if portion_beyond(h[t], l[t], f.upper(t)) > 0.5:
                setup["slide"] = (t, l[t])
        else:
            ax, ay = setup["slide"]
            line = ay + f.m * (t - ax)
            if t > ax and h[t] >= line + p.tick:
                entry = max(o[t], line + p.tick)
                stop = f.upper(t) - p.stop_ticks * p.tick   # book: below upper parallel
                if stop < entry:
                    sigs.append((t, entry, stop))
                setup, last_div = None, -10 ** 9
    return sigs


# --------------------------------------------------------------------------
# Strategy B: price failure (BUY version = reversal out of a downtrend)
# --------------------------------------------------------------------------
def _pf_fork(highs, lows, tma, p: Params):
    p3 = highs[-1]
    p2 = next((x for x in reversed(lows) if x[0] < p3[0]), None)
    if p2 is None:
        return None
    p1 = next((x for x in reversed(highs[:-1]) if x[0] < p2[0]), None)
    if p1 is None:
        return None
    i = p3[0]
    if i - p.trend_slope_bars < 0 or not tma[i] < tma[i - p.trend_slope_bars]:
        return None                         # need a prior downtrend
    f = Fork((p1[0], p1[1]), (p2[0], p2[1]), (p3[0], p3[1]))
    return f if f.m < 0 else None           # fork slopes with the trend


def price_failure_buy(o, h, l, c, r, p: Params, htf=None):
    n = len(c)
    tma = pd.Series(c).rolling(p.trend_len).mean().to_numpy()
    sh, sl = find_swings(h, l, p.swing_k)
    sh_at, sl_at = {s[2]: s for s in sh}, {s[2]: s for s in sl}

    highs, lows, sigs, setup = [], [], [], None
    for t in range(p.trend_len + p.trend_slope_bars, n):
        if t in sl_at:
            lows.append(sl_at[t])
        if t in sh_at:
            highs.append(sh_at[t])
            f = _pf_fork(highs, lows, tma, p)
            setup = None
            if f:
                xs = np.arange(f.p3[0] + 1, t + 1)
                # price failure requires the median line NOT to have been reached
                if len(xs) == 0 or not (l[xs] <= f.median(xs)).any():
                    setup = {"fork": f, "created": t, "break": False, "slide": None,
                             "slide_hit": False, "low": l[f.p3[0]:t + 1].min()}
            continue
        if setup is None:
            continue

        f, s = setup["fork"], setup
        s["low"] = min(s["low"], l[t])
        if l[t] <= f.median(t) or t - s["created"] > p.max_wait:
            setup = None                    # median reached (normal reversal) or stale
            continue

        # 2) penetrate the upper parallel, 3) sliding parallel from that bar's low
        if s["slide"] is None:
            if portion_beyond(h[t], l[t], f.upper(t)) > 0.5:
                s["slide"] = (t, l[t])
            continue
        ax, ay = s["slide"]
        if t > ax and h[t] > ay + f.m * (t - ax):
            s["slide_hit"] = True
        if not s["slide_hit"]:
            continue

        # 4) Hagopian line: trendline P1 -> P3 the price was moving along   [MY CHOICE]
        (x1, y1), (x3, y3) = f.p1, f.p3
        hag = y1 + (y3 - y1) / (x3 - x1) * (t - x1)
        # 5) standard bullish divergence formed recently: lower low, higher osc low
        recent = [x for x in lows if x[2] <= t]
        div = (len(recent) >= 2 and recent[-1][2] >= t - p.div_lookback
               and recent[-1][1] < recent[-2][1] and r[recent[-1][0]] > r[recent[-2][0]])

        if div and h[t] >= hag + p.tick:
            entry = max(o[t], hag + p.tick)             # book: ticks beyond Hagopian
            stop = s["low"] - p.stop_ticks * p.tick      # [MY CHOICE] below reversal low
            if stop < entry:
                sigs.append((t, entry, stop))
            setup = None
    return sigs


# --------------------------------------------------------------------------
# Signal generation (both strategies, both directions)
# --------------------------------------------------------------------------
def generate_signals(df: pd.DataFrame, p: Params) -> list[Signal]:
    o, h, l, c = (df[k].to_numpy(float) for k in ("open", "high", "low", "close"))
    r = rsi(df["close"], p.rsi_len).to_numpy()
    htf = None
    if p.use_weekly_filter and isinstance(df.index, pd.DatetimeIndex):
        htf = weekly_trend(df)

    out: list[Signal] = []
    for side in (1, -1):
        oo, hh, ll, cc, rr = (o, h, l, c, r) if side == 1 else flip(o, h, l, c, r)
        side_htf = None if htf is None else htf * side
        for kind, fn in (("mini_median", mini_median_buy), ("price_failure", price_failure_buy)):
            for bar, entry, stop in fn(oo, hh, ll, cc, rr, p, side_htf):
                out.append(Signal(bar, side, side * entry, side * stop, kind))
    out.sort(key=lambda s: s.bar)
    return out


# --------------------------------------------------------------------------
# Simple backtester (one position at a time, stop-and-reverse on opposite signal)
# --------------------------------------------------------------------------
def backtest(df: pd.DataFrame, sigs: list[Signal], p: Params) -> pd.DataFrame:
    o, h, l, c = (df[k].to_numpy(float) for k in ("open", "high", "low", "close"))
    a = atr(df, p.atr_len)
    by_bar = defaultdict(list)
    for s in sigs:
        by_bar[s.bar].append(s)

    equity, pos, trades = p.initial_equity, None, []

    def close(t, price, reason):
        nonlocal equity, pos
        pnl = pos["side"] * (price - pos["entry"]) * pos["qty"]
        pnl -= p.commission_pct * (price + pos["entry"]) * pos["qty"]
        equity += pnl
        trades.append({"entry_date": df.index[pos["bar"]], "exit_date": df.index[t],
                       "side": "long" if pos["side"] == 1 else "short",
                       "kind": pos["kind"], "entry": pos["entry"], "exit": price,
                       "pnl": pnl, "reason": reason, "equity": equity})
        pos = None

    for t in range(len(df)):
        if pos and t > pos["bar"]:                       # protective stop
            if pos["side"] == 1 and l[t] <= pos["stop"]:
                close(t, min(o[t], pos["stop"]), "stop")
            elif pos["side"] == -1 and h[t] >= pos["stop"]:
                close(t, max(o[t], pos["stop"]), "stop")

        for s in by_bar.get(t, []):
            if pos and pos["side"] == s.side:
                continue
            if pos:
                close(t, s.entry, "reverse")
            risk = abs(s.entry - s.stop)
            if risk <= 0:
                continue
            qty = min(equity * p.risk_pct / risk, equity / s.entry)  # no leverage
            pos = {"bar": t, "side": s.side, "entry": s.entry, "stop": s.stop,
                   "qty": qty, "kind": s.kind}

        if pos and p.trail_atr > 0 and not np.isnan(a[t]) and t > pos["bar"]:
            new = c[t] - pos["side"] * p.trail_atr * a[t]
            # book rule: the stop only ever moves in the direction of profit
            pos["stop"] = max(pos["stop"], new) if pos["side"] == 1 else min(pos["stop"], new)

    if pos:
        close(len(df) - 1, c[-1], "end")
    return pd.DataFrame(trades)


def summarize(trades: pd.DataFrame, p: Params) -> str:
    if trades.empty:
        return "No trades."
    w, lo = trades.loc[trades.pnl > 0, "pnl"], trades.loc[trades.pnl <= 0, "pnl"]
    eq = pd.concat([pd.Series([p.initial_equity]), trades["equity"]], ignore_index=True)
    dd = (eq / eq.cummax() - 1).min()
    pf = w.sum() / abs(lo.sum()) if lo.sum() != 0 else float("inf")
    lines = [f"Trades: {len(trades)}   Win rate: {len(w) / len(trades):.1%}",
             f"Total return: {eq.iloc[-1] / p.initial_equity - 1:.1%}   "
             f"Max drawdown (closed trades): {dd:.1%}   Profit factor: {pf:.2f}"]
    for k, g in trades.groupby("kind"):
        lines.append(f"  {k}: {len(g)} trades, net PnL {g.pnl.sum():,.0f}")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Data helpers / CLI
# --------------------------------------------------------------------------
def load_csv(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    df.columns = [x.strip().lower() for x in df.columns]
    date_col = next((x for x in df.columns if x in ("date", "datetime", "time")), None)
    if date_col:
        df[date_col] = pd.to_datetime(df[date_col])
        df = df.set_index(date_col).sort_index()
    return df[["open", "high", "low", "close"]].dropna()


def demo_data(n: int = 2000, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    drift = np.sin(np.linspace(0, 12 * np.pi, n)) * 0.0015       # alternating regimes
    close = 100 * np.exp(np.cumsum(drift + rng.normal(0, 0.012, n)))
    open_ = np.r_[close[0], close[:-1]] * (1 + rng.normal(0, 0.002, n))
    spread = np.abs(rng.normal(0, 0.008, (2, n))) * close
    high = np.maximum(open_, close) + spread[0]
    low = np.minimum(open_, close) - spread[1]
    idx = pd.bdate_range("2015-01-01", periods=n)
    return pd.DataFrame({"open": open_, "high": high, "low": low, "close": close}, index=idx)


def main():
    ap = argparse.ArgumentParser(description="Pitchfork strategy (Andrews/DeRoos)")
    ap.add_argument("csv", nargs="?", help="CSV with date,open,high,low,close (omit for demo)")
    ap.add_argument("--ma", type=int, default=5)
    ap.add_argument("--rsi", type=int, default=14)
    ap.add_argument("--tick", type=float, default=0.01)
    ap.add_argument("--weekly", action="store_true", help="require weekly trend agreement")
    ap.add_argument("--trail-atr", type=float, default=0.0)
    ap.add_argument("--risk", type=float, default=0.01)
    args = ap.parse_args()

    p = Params(ma_len=args.ma, rsi_len=args.rsi, tick=args.tick,
               use_weekly_filter=args.weekly, trail_atr=args.trail_atr, risk_pct=args.risk)
    df = load_csv(args.csv) if args.csv else demo_data()
    if not args.csv:
        print("(no CSV given: using synthetic random data, results are meaningless)\n")

    sigs = generate_signals(df, p)
    print(f"Signals: {len(sigs)}")
    for s in sigs[-5:]:
        print(f"  {df.index[s.bar]:%Y-%m-%d}  {'BUY ' if s.side == 1 else 'SELL'}  "
              f"{s.kind:<13} entry={s.entry:.2f} stop={s.stop:.2f}")
    print()
    print(summarize(backtest(df, sigs, p), p))


if __name__ == "__main__":
    main()
