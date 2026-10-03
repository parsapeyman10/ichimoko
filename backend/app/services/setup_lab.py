"""
Setup laboratory — learning which Ichimoku opportunities actually pay, from real candles.

THE EQUATION BEHIND "I WANT A 65% WIN RATE"
-------------------------------------------
Win rate is not an independent dial. It is mostly determined by where you put your two
barriers. For a driftless price (a random walk with no edge), starting at 0 with a
take-profit at +a and a stop at -b, the probability of touching the profit barrier first is

        P(win) = b / (a + b) = 1 / (1 + RR)        where RR = a / b

So a "random" strategy already produces:

        RR = 2.00  ->  33% win rate
        RR = 1.00  ->  50% win rate
        RR = 0.54  ->  65% win rate      <-- the number requested
        RR = 0.25  ->  80% win rate

Any coin-flip system can show a 65% win rate simply by taking profits at 0.54R. Its
expectancy is exactly zero:

        E = 0.65 x 0.54 - 0.35 = -0.001 R

Adding execution cost, the honest baseline is

        P_baseline = (b - c) / (a + b)             c = round-trip cost in price units

because the cost shifts the effective profit barrier out and the effective stop barrier in.

Therefore the only number that means anything is the EDGE over that baseline:

        edge = P_observed - P_baseline

A 65% win rate at RR 0.54 is worth nothing. A 65% win rate at RR 1.2 (baseline 45%) is a
20-point edge and is extremely valuable. This module measures that, per Ichimoku setup and
per market context, so the win rate can be raised on purpose rather than by shrinking
targets until the statistic looks nice.

HOW IT AVOIDS FOOLING ITSELF
----------------------------
1. Triple-barrier labelling on real candles. Entry at the next bar's open after a closed
   signal bar. When one bar touches both barriers, the LOSS is recorded — the pessimistic
   assumption, because intrabar order is unknown.
2. Wilson lower confidence bound instead of the raw win rate. 7 wins in 10 trades is a 70%
   point estimate but only a ~39% lower bound; it is not evidence.
3. Walk-forward split. Setups are selected on the earlier portion of history and scored on
   the later portion they never saw.
4. Multiple-testing accounting. Scanning many setup x context combinations guarantees some
   will look good by luck; the number of hypotheses tested is reported and the confidence
   level is tightened accordingly.

Nothing here generates candles. It consumes the same real series as the rest of the app.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from app.models import Candle, Direction
from app.services import instruments as inst
from app.services.indicators import adx, atr, ema, ichimoku

Z_95 = 1.959964


# ─────────────────────────────────────────────────────────────────────────
# Statistics
# ─────────────────────────────────────────────────────────────────────────

def wilson_bounds(wins: int, n: int, z: float = Z_95) -> tuple[float, float]:
    """Wilson score interval for a binomial proportion.

    Far better behaved than the normal approximation at small n, which is exactly the
    regime where trading research deceives itself.
    """
    if n <= 0:
        return 0.0, 1.0
    p = wins / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, centre - margin), min(1.0, centre + margin)


def bonferroni_z(hypotheses: int, base_alpha: float = 0.05) -> float:
    """Tighten the confidence level for the number of combinations scanned.

    Scanning 200 buckets at 95% confidence yields ~10 false positives by construction.
    """
    hypotheses = max(1, hypotheses)
    alpha = base_alpha / hypotheses
    # Inverse normal CDF (Acklam's rational approximation), two-sided.
    return _inv_norm(1 - alpha / 2)


def _inv_norm(p: float) -> float:
    if p <= 0 or p >= 1:
        return 0.0
    a = [-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
         1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00]
    b = [-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
         6.680131188771972e+01, -1.328068155288572e+01]
    c = [-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
         -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00]
    d = [7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
         3.754408661907416e+00]
    plow, phigh = 0.02425, 1 - 0.02425
    if p < plow:
        q = math.sqrt(-2 * math.log(p))
        return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1)
    if p > phigh:
        q = math.sqrt(-2 * math.log(1 - p))
        return -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1)
    q = p - 0.5
    r = q * q
    return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1)


def baseline_win_rate(rr: float) -> float:
    """P(touch TP before SL) for a driftless price — the 'no edge' null hypothesis.

        P(win) = b / (a + b) = 1 / (1 + RR)

    Deliberately cost-free. Cost does not change which barrier price touches first; it
    changes what that touch is worth. Mixing cost into this number (an earlier bug here)
    deflates the null and manufactures edge out of nothing — on a pure random walk it
    reported +16 points of edge that did not exist.
    """
    if rr <= 0:
        return 1.0
    return 1.0 / (1.0 + rr)


def required_win_rate(rr: float, cost_r: float = 0.0) -> float:
    """Win rate needed to break even at this reward ratio, AFTER cost.

    Solve  wr*(rr - c) - (1-wr)*(1 + c) = 0  ->  wr = (1 + c) / (1 + rr)
    This is where cost legitimately belongs: the profitability hurdle, not the null.
    """
    if rr <= 0:
        return 1.0
    return min(1.0, (1.0 + cost_r) / (1.0 + rr))


def rr_for_target_win_rate(target_wr: float) -> float:
    """Invert the baseline: which reward ratio makes a NO-EDGE system show this win rate.

    Used to show that a requested win rate is reachable with geometry alone, and therefore
    proves nothing by itself.
    """
    if target_wr <= 0 or target_wr >= 1:
        return 0.0
    return (1 - target_wr) / target_wr


# ─────────────────────────────────────────────────────────────────────────
# Opportunity detection — every entry Ichimoku offers
# ─────────────────────────────────────────────────────────────────────────

@dataclass
class Opportunity:
    index: int
    setup: str
    direction: Direction
    context: dict[str, str] = field(default_factory=dict)


def _band(value: float | None, edges: list[float], labels: list[str]) -> str:
    if value is None:
        return "unknown"
    for edge, label in zip(edges, labels):
        if value < edge:
            return label
    return labels[-1]


def detect_opportunities(
    candles: list[Candle],
    tenkan: int = 9,
    kijun: int = 26,
    span_b: int = 52,
    displacement: int = 26,
) -> list[Opportunity]:
    """Enumerate every Ichimoku signal in the series, with its market context.

    Deliberately permissive: the point of the lab is to measure which of these pay, not to
    pre-filter them with the beliefs we are trying to test.
    """
    n = len(candles)
    if n < span_b + displacement + 60:
        return []

    values = ichimoku(candles, tenkan, kijun, span_b, displacement)
    t, k = values["tenkan"], values["kijun"]
    sa, sb = values["span_a"], values["span_b"]
    adx14 = adx(candles, 14)
    atr14 = atr(candles, 14)
    ema200 = ema(candles, 200)

    def atr_band(index: int, value: float | None) -> str:
        """Percentile against the TRAILING window only.

        Ranking a bar against the whole series (including its future) is look-ahead and
        quietly leaks outcome information into the context label.
        """
        if value is None:
            return "unknown"
        window = [v for v in atr14[max(0, index - 300):index] if v is not None]
        if len(window) < 30:
            return "unknown"
        rank = sum(1 for x in window if x < value) / len(window)
        return "vol_low" if rank < 0.33 else ("vol_mid" if rank < 0.67 else "vol_high")

    out: list[Opportunity] = []
    start = max(span_b + displacement + 2, 205)

    for i in range(start, n - 1):
        close = candles[i].close
        prev = candles[i - 1]
        ti, ki = t[i], k[i]
        tp, kp = t[i - 1], k[i - 1]
        sai, sbi = sa[i], sb[i]
        if None in (ti, ki, tp, kp, sai, sbi):
            continue

        cloud_top, cloud_bottom = max(sai, sbi), min(sai, sbi)
        cloud_pos = "above_cloud" if close > cloud_top else ("below_cloud" if close < cloud_bottom else "in_cloud")
        thickness = (cloud_top - cloud_bottom) / close if close else 0
        kumo_band = "kumo_thin" if thickness < 0.0015 else ("kumo_mid" if thickness < 0.005 else "kumo_thick")

        a = adx14[i]
        adx_band = _band(a, [18, 25, 35], ["adx_weak", "adx_fair", "adx_strong", "adx_extreme"])

        e200 = ema200[i] if i < len(ema200) else None
        macro = "macro_up" if (e200 and close > e200) else ("macro_down" if e200 else "unknown")

        hour = candles[i].timestamp.hour
        session = ("asia" if 0 <= hour < 7 else "london" if 7 <= hour < 12
                   else "overlap" if 12 <= hour < 16 else "newyork" if 16 <= hour < 21 else "offhours")

        # Chikou (lagging span) free of past price — a core Ichimoku confirmation.
        chikou = "chikou_unknown"
        if i - displacement >= 0:
            past = candles[i - displacement]
            chikou = "chikou_free_up" if close > past.high else (
                "chikou_free_down" if close < past.low else "chikou_tangled")

        context = {
            "cloud_pos": cloud_pos, "adx": adx_band, "macro": macro,
            "session": session, "vol": atr_band(i, atr14[i]), "kumo": kumo_band,
            "chikou": chikou,
        }

        def add(setup: str, direction: Direction) -> None:
            out.append(Opportunity(index=i, setup=setup, direction=direction, context=dict(context)))

        # 1) Tenkan / Kijun cross — the classic trigger.
        if tp <= kp and ti > ki:
            add("tk_cross", Direction.BUY)
        elif tp >= kp and ti < ki:
            add("tk_cross", Direction.SELL)

        # 2) Kumo breakout — price leaving the cloud.
        prev_top = max(sa[i - 1], sb[i - 1]) if None not in (sa[i - 1], sb[i - 1]) else None
        prev_bottom = min(sa[i - 1], sb[i - 1]) if None not in (sa[i - 1], sb[i - 1]) else None
        if prev_top is not None and prev.close <= prev_top < close:
            add("kumo_breakout", Direction.BUY)
        if prev_bottom is not None and prev.close >= prev_bottom > close:
            add("kumo_breakout", Direction.SELL)

        # 3) Kijun bounce — pullback to the baseline that holds.
        if candles[i].low <= ki <= close and prev.close > kp:
            add("kijun_bounce", Direction.BUY)
        if candles[i].high >= ki >= close and prev.close < kp:
            add("kijun_bounce", Direction.SELL)

        # 4) Tenkan bounce — shallower, faster continuation.
        if candles[i].low <= ti <= close and prev.close > tp:
            add("tenkan_bounce", Direction.BUY)
        if candles[i].high >= ti >= close and prev.close < tp:
            add("tenkan_bounce", Direction.SELL)

        # 5) Kumo twist — the forward cloud changing colour.
        if sa[i - 1] is not None and sb[i - 1] is not None:
            if sa[i - 1] <= sb[i - 1] and sai > sbi:
                add("kumo_twist", Direction.BUY)
            elif sa[i - 1] >= sb[i - 1] and sai < sbi:
                add("kumo_twist", Direction.SELL)

        # 6) Full stack alignment — every Ichimoku component agreeing at once.
        if close > cloud_top and ti > ki and chikou == "chikou_free_up" and sai > sbi:
            add("full_stack", Direction.BUY)
        elif close < cloud_bottom and ti < ki and chikou == "chikou_free_down" and sai < sbi:
            add("full_stack", Direction.SELL)

    return out


# ─────────────────────────────────────────────────────────────────────────
# Triple-barrier labelling
# ─────────────────────────────────────────────────────────────────────────

@dataclass
class LabelConfig:
    rr: float = 1.5             # take-profit as a multiple of the stop
    stop_atr: float = 1.0       # stop distance in ATR
    max_bars: int = 24          # time barrier
    cost_r: float = 0.0         # round-trip cost in units of stop distance


def label_outcome(
    candles: list[Candle],
    opp: Opportunity,
    atr_values: list[float | None],
    config: LabelConfig,
) -> dict | None:
    """Resolve one opportunity to win / loss / timeout against real subsequent candles."""
    i = opp.index
    if i + 1 >= len(candles):
        return None
    atr_now = atr_values[i]
    if not atr_now or atr_now <= 0:
        return None

    entry = candles[i + 1].open     # realistic: act on the next bar, not the signal close
    stop_distance = config.stop_atr * atr_now
    long = opp.direction is Direction.BUY
    stop = entry - stop_distance if long else entry + stop_distance
    target = entry + stop_distance * config.rr if long else entry - stop_distance * config.rr

    for offset in range(1, config.max_bars + 1):
        j = i + 1 + offset
        if j >= len(candles):
            break
        bar = candles[j]
        hit_stop = bar.low <= stop if long else bar.high >= stop
        hit_target = bar.high >= target if long else bar.low <= target
        if hit_stop and hit_target:
            # Intrabar order is unknowable from OHLC. Assume the loss — never the win.
            return {"outcome": "loss", "r": -1.0 - config.cost_r, "bars": offset, "ambiguous": True}
        if hit_stop:
            return {"outcome": "loss", "r": -1.0 - config.cost_r, "bars": offset, "ambiguous": False}
        if hit_target:
            return {"outcome": "win", "r": config.rr - config.cost_r, "bars": offset, "ambiguous": False}

    last = candles[min(i + 1 + config.max_bars, len(candles) - 1)]
    move = (last.close - entry) if long else (entry - last.close)
    r = move / stop_distance - config.cost_r
    # A time-stopped trade touched NEITHER barrier, so it is not comparable to the
    # 1/(1+RR) barrier-touch baseline. Folding it into win/loss (an earlier bug here)
    # inflated the win rate at high RR, because a flat exit is "positive" far more often
    # than a distant target is reached. It is kept for expectancy but excluded from the
    # win-rate statistic.
    return {"outcome": "timeout", "r": r, "bars": config.max_bars, "ambiguous": False,
            "timeout": True, "positive": r > 0}


# ─────────────────────────────────────────────────────────────────────────
# Study
# ─────────────────────────────────────────────────────────────────────────

CONTEXT_DIMENSIONS = ("cloud_pos", "adx", "macro", "session", "vol", "kumo", "chikou")


def _summarise(rows: list[dict], rr: float, cost_r: float, z: float) -> dict:
    n = len(rows)
    rs = [r["r"] for r in rows]
    # Win rate is measured over BARRIER-RESOLVED trades only — the population the
    # 1/(1+RR) null actually describes. Timeouts are reported separately.
    resolved = [r for r in rows if r["outcome"] in ("win", "loss")]
    timeouts = [r for r in rows if r["outcome"] == "timeout"]
    m = len(resolved)
    wins = sum(1 for r in resolved if r["outcome"] == "win")
    wr = wins / m if m else 0.0
    lo, hi = wilson_bounds(wins, m, z)
    base = baseline_win_rate(rr)
    hurdle = required_win_rate(rr, cost_r)
    return {
        "samples": n,
        "resolved": m,
        "timeouts": len(timeouts),
        "timeout_pct": round(len(timeouts) / n * 100, 1) if n else 0.0,
        "wins": wins,
        "win_rate": round(wr * 100, 1),
        "win_rate_lower": round(lo * 100, 1),
        "win_rate_upper": round(hi * 100, 1),
        "baseline_win_rate": round(base * 100, 1),
        "breakeven_win_rate": round(hurdle * 100, 1),
        "beats_cost": bool(lo > hurdle),
        "edge_points": round((wr - base) * 100, 1),
        "edge_points_conservative": round((lo - base) * 100, 1),
        "expectancy_r": round(sum(rs) / n, 3) if n else 0.0,
        "avg_bars": round(sum(r["bars"] for r in rows) / n, 1) if n else 0.0,
        "ambiguous_pct": round(sum(1 for r in rows if r.get("ambiguous")) / n * 100, 1) if n else 0.0,
    }


def study(
    candles: list[Candle],
    spec: inst.InstrumentSpec,
    config: LabelConfig | None = None,
    min_samples: int = 30,
    train_fraction: float = 0.7,
) -> dict:
    """Measure every Ichimoku setup x context bucket, in-sample and out-of-sample."""
    config = config or LabelConfig()
    if config.cost_r == 0.0:
        # Express the instrument's real round-trip cost in units of the stop distance.
        mid = candles[-1].close if candles else 0.0
        atr_mid = next((v for v in reversed(atr(candles, 14)) if v), None)
        if mid and atr_mid:
            config.cost_r = spec.round_trip_cost(mid) / (config.stop_atr * atr_mid)

    atr_values = atr(candles, 14)
    opportunities = detect_opportunities(candles)
    if not opportunities:
        return {"error": "کندل کافی برای استخراج فرصت‌ها نیست", "samples": 0}

    split_index = int(len(candles) * train_fraction)

    # Purge overlapping holding periods. A setup like `full_stack` is true on every bar of
    # a trend, so naive sampling produces hundreds of near-identical, highly correlated
    # outcomes. The binomial confidence interval assumes independence, so overlap inflates
    # significance badly — on a random walk it produced an apparent +18-point edge. Only
    # the first opportunity of each setup is kept until its trade has finished.
    labelled: list[tuple[Opportunity, dict]] = []
    busy_until: dict[str, int] = {}
    raw_count = 0
    for opp in opportunities:
        stream = f"{opp.setup}|{opp.direction.value}"
        if opp.index < busy_until.get(stream, -1):
            continue
        result = label_outcome(candles, opp, atr_values, config)
        raw_count += 1
        if result:
            labelled.append((opp, result))
            busy_until[stream] = opp.index + 1 + result["bars"]

    if not labelled:
        return {"error": "هیچ فرصتی قابل برچسب‌گذاری نبود", "samples": 0}

    # Enumerate hypotheses: each setup alone, plus setup x one context dimension.
    buckets: dict[str, list[tuple[Opportunity, dict]]] = {}
    for opp, result in labelled:
        key_all = f"{opp.setup}|{opp.direction.value}"
        buckets.setdefault(key_all, []).append((opp, result))
        for dim in CONTEXT_DIMENSIONS:
            buckets.setdefault(f"{key_all}|{dim}={opp.context.get(dim)}", []).append((opp, result))

    tested = sum(1 for rows in buckets.values() if len(rows) >= min_samples)
    z = bonferroni_z(tested)

    findings = []
    for key, rows in buckets.items():
        if len(rows) < min_samples:
            continue
        train = [r for o, r in rows if o.index < split_index]
        test = [r for o, r in rows if o.index >= split_index]
        entry = {
            "key": key,
            "setup": key.split("|")[0],
            "direction": key.split("|")[1],
            "filter": key.split("|")[2] if key.count("|") > 1 else "(همه شرایط)",
            "all": _summarise([r for _, r in rows], config.rr, config.cost_r, z),
            "train": _summarise(train, config.rr, config.cost_r, z) if len(train) >= 10 else None,
            "test": _summarise(test, config.rr, config.cost_r, z) if len(test) >= 10 else None,
        }
        entry["holds_out_of_sample"] = bool(
            entry["train"] and entry["test"]
            and entry["train"]["edge_points"] > 0 and entry["test"]["edge_points"] > 0
        )
        findings.append(entry)

    findings.sort(key=lambda f: f["all"]["edge_points_conservative"], reverse=True)

    return {
        "symbol": spec.symbol,
        "candles": len(candles),
        "opportunities": len(labelled),
        "opportunities_before_overlap_purge": len(opportunities),
        "overlap_note": (
            f"{len(opportunities)} فرصت خام پیدا شد؛ پس از حذف هم‌پوشانی "
            f"{len(labelled)} نمونهٔ مستقل باقی ماند. بدون این کار، فاصلهٔ اطمینان بی‌معنا "
            "می‌شود چون نمونه‌ها به هم وابسته‌اند."
        ),
        "config": {
            "rr": config.rr, "stop_atr": config.stop_atr, "max_bars": config.max_bars,
            "cost_r": round(config.cost_r, 4),
        },
        "math": {
            "baseline_win_rate_pct": round(baseline_win_rate(config.rr) * 100, 1),
            "required_win_rate_pct": round(required_win_rate(config.rr, config.cost_r) * 100, 1),
            "explanation": (
                f"با RR={config.rr} حتی یک سیستم کاملاً تصادفی "
                f"{baseline_win_rate(config.rr) * 100:.0f}% وین‌ریت نشان می‌دهد. "
                f"با احتساب هزینه، برای سربه‌سر شدن به "
                f"{required_win_rate(config.rr, config.cost_r) * 100:.1f}% نیاز است. "
                "فقط مقدار «edge» (اختلاف با این خط پایه) ارزش دارد."
            ),
        },
        "hypotheses_tested": tested,
        "confidence_z": round(z, 3),
        "multiple_testing_note": (
            f"{tested} ترکیب بررسی شد. با اطمینان ۹۵٪ ساده، حدود {tested * 0.05:.0f} مورد "
            "فقط به‌خاطر شانس «خوب» به نظر می‌رسند؛ به همین دلیل سطح اطمینان سخت‌گیرانه‌تر شده "
            f"(z={z:.2f}) و ستون «حد پایین» ملاک است، نه وین‌ریت خام."
        ),
        "findings": findings[:60],
    }


def win_rate_curve(
    candles: list[Candle],
    spec: inst.InstrumentSpec,
    setup_filter: str | None = None,
    stop_atr: float = 1.0,
    max_bars: int = 24,
) -> dict:
    """Sweep the reward ratio and show the win-rate / expectancy trade-off.

    This is the direct answer to "make the win rate 65%": it shows which RR delivers it,
    and whether expectancy survives the shrinking target.
    """
    atr_values = atr(candles, 14)
    opportunities = detect_opportunities(candles)
    if setup_filter:
        opportunities = [o for o in opportunities if o.setup == setup_filter]
    if not opportunities:
        return {"error": "فرصتی برای این فیلتر پیدا نشد", "rows": []}

    mid = candles[-1].close
    atr_mid = next((v for v in reversed(atr_values) if v), None) or 1.0

    rows = []
    for rr in (0.4, 0.54, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0):
        config = LabelConfig(rr=rr, stop_atr=stop_atr, max_bars=max_bars,
                             cost_r=spec.round_trip_cost(mid) / (stop_atr * atr_mid))
        results = [r for r in (label_outcome(candles, o, atr_values, config) for o in opportunities) if r]
        if len(results) < 20:
            continue
        summary = _summarise(results, rr, config.cost_r, Z_95)
        rows.append({
            "rr": rr,
            "random_baseline_pct": round(baseline_win_rate(rr) * 100, 1),
            **summary,
            "profitable": summary["expectancy_r"] > 0,
        })

    sixty_five = next((r for r in rows if r["win_rate"] >= 65), None)
    return {
        "symbol": spec.symbol,
        "setup": setup_filter or "(همهٔ فرصت‌ها)",
        "rows": rows,
        "rr_for_65pct_random": round(rr_for_target_win_rate(0.65), 2),
        "first_row_above_65pct": sixty_five,
        "verdict": (
            (f"وین‌ریت ۶۵٪ در RR={sixty_five['rr']} به دست می‌آید؛ انتظار ریاضی آنجا "
             f"{sixty_five['expectancy_r']:+.3f}R است "
             f"({'سودده' if sixty_five['expectancy_r'] > 0 else 'زیان‌ده'}). "
             f"خط پایهٔ تصادفی در همان RR برابر {sixty_five['random_baseline_pct']}% است، "
             f"پس لبهٔ واقعی {sixty_five['edge_points']:+.1f} واحد است.")
            if sixty_five else
            "با این مجموعه فرصت‌ها، هیچ RR‌ای وین‌ریت ۶۵٪ تولید نکرد."
        ),
    }


# ─────────────────────────────────────────────────────────────────────────
# Playbook — turning measurements into an entry filter
# ─────────────────────────────────────────────────────────────────────────

def playbook(
    candles: list[Candle],
    spec: inst.InstrumentSpec,
    target_win_rate: float = 0.65,
    min_samples: int = 40,
    config: LabelConfig | None = None,
) -> dict:
    """Select the Ichimoku setups worth trading, and say honestly whether 65% is reachable.

    A setup is admitted only if ALL of these hold:

      1. enough INDEPENDENT samples (overlapping trades already purged),
      2. the Wilson LOWER bound of its win rate clears the cost break-even hurdle —
         the point estimate is not allowed to decide anything,
      3. positive expectancy in R,
      4. the edge survives out-of-sample on the later slice it was not selected on.

    Rules that pass are the ones the engine should actually take. Rules that only pass
    in-sample are reported separately and explicitly labelled as probable curve-fitting.
    """
    config = config or LabelConfig()
    result = study(candles, spec, config, min_samples=min_samples)
    if result.get("error"):
        return result

    hurdle = required_win_rate(config.rr, config.cost_r)
    baseline = baseline_win_rate(config.rr)

    accepted, in_sample_only, rejected = [], [], []
    for finding in result["findings"]:
        stats = finding["all"]
        passes_stat = stats["win_rate_lower"] / 100 > hurdle
        passes_exp = stats["expectancy_r"] > 0
        passes_oos = finding["holds_out_of_sample"]
        row = {
            "key": finding["key"],
            "setup": finding["setup"],
            "direction": finding["direction"],
            "filter": finding["filter"],
            "samples": stats["samples"],
            "win_rate": stats["win_rate"],
            "win_rate_lower": stats["win_rate_lower"],
            "expectancy_r": stats["expectancy_r"],
            "edge_points": stats["edge_points"],
            "oos_edge_points": finding["test"]["edge_points"] if finding["test"] else None,
        }
        if passes_stat and passes_exp and passes_oos:
            accepted.append(row)
        elif passes_exp and stats["win_rate"] / 100 > hurdle:
            in_sample_only.append(row)
        else:
            rejected.append(row)

    reachable = rr_for_target_win_rate(target_win_rate)
    return {
        "symbol": spec.symbol,
        "candles": result["candles"],
        "independent_samples": result["opportunities"],
        "hypotheses_tested": result["hypotheses_tested"],
        "config": result["config"],
        "target_win_rate_pct": round(target_win_rate * 100, 1),
        "baseline_win_rate_pct": round(baseline * 100, 1),
        "breakeven_win_rate_pct": round(hurdle * 100, 1),
        "accepted": accepted[:20],
        "in_sample_only": in_sample_only[:20],
        "rejected_count": len(rejected),
        "target_explanation": (
            f"وین‌ریت {target_win_rate * 100:.0f}٪ با RR={reachable:.2f} حتی بدون هیچ لبه‌ای "
            f"به‌دست می‌آید (انتظار ریاضی صفر). پس عدد وین‌ریت به‌تنهایی هدف درستی نیست؛ "
            f"هدف درست این است که وین‌ریت از خط پایهٔ {baseline * 100:.0f}٪ در همین RR "
            f"و از آستانهٔ سربه‌سری {hurdle * 100:.1f}٪ بالاتر باشد."
        ),
        "verdict": (
            f"{len(accepted)} قانون از {result['hypotheses_tested']} ترکیب بررسی‌شده، هم از نظر "
            f"آماری و هم خارج از نمونه قبول شد."
            if accepted else
            f"هیچ‌کدام از {result['hypotheses_tested']} ترکیب، شرط حد پایین اطمینان و اعتبار "
            "خارج از نمونه را با هم پاس نکرد. روی این دیتا و با این تنظیمات، قانون قابل‌اتکایی پیدا نشد."
        ),
    }
