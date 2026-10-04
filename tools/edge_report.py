#!/usr/bin/env python3
"""
Edge report — run the setup laboratory on REAL candles and print a paste-friendly summary.

Why this script exists
----------------------
The analysis has to happen where the data is. Your machine can reach Binance and Twelve
Data; a restricted environment (CI, a sandbox, a locked-down server) often cannot. Rather
than describing what to run, this does the whole measurement in one command and prints a
compact block you can paste straight back into a conversation for interpretation.

It also writes the full JSON to `edge_report.json` for anything you want to inspect later.

Usage
-----
    python tools/edge_report.py
    python tools/edge_report.py --symbols BTCUSDT,ETHUSDT,EUR/USD --timeframes 5m,15m
    python tools/edge_report.py --bars 3000 --rr 1.5

Reading the output
------------------
`WR` is the raw win rate and is NOT the number that matters. `base` is what a coin flip
would show at that reward ratio (1/(1+RR)), and `EDGE` is the difference. Only EDGE is
evidence of skill. `edgeLB` is the conservative (Wilson lower bound) version, which is
what the playbook actually decides on, and `OOS` is the edge on the later slice of history
that was never used to pick the rule.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.config import get_settings                      # noqa: E402
from app.models import Timeframe                         # noqa: E402
from app.services import setup_lab as lab                # noqa: E402
from app.services import universe                        # noqa: E402
from app.services.history import DataUnavailable         # noqa: E402

DEFAULT_SYMBOLS = "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT"


async def one(settings, symbol: str, timeframe: Timeframe, bars: int, rr: float,
              stop_atr: float, max_bars: int, min_samples: int) -> dict:
    candles, spec = await universe.load_candles(settings, symbol, timeframe, limit=bars)
    config = lab.LabelConfig(rr=rr, stop_atr=stop_atr, max_bars=max_bars)
    book = lab.playbook(candles, spec, min_samples=min_samples, config=config)
    curve = lab.win_rate_curve(candles, spec, stop_atr=stop_atr, max_bars=max_bars)
    return {"symbol": spec.symbol, "timeframe": timeframe.value, "playbook": book, "curve": curve}


async def main() -> int:
    parser = argparse.ArgumentParser(description="Measure the real Ichimoku edge")
    parser.add_argument("--symbols", default=DEFAULT_SYMBOLS)
    parser.add_argument("--timeframes", default="5m,15m")
    parser.add_argument("--bars", type=int, default=3000)
    parser.add_argument("--rr", type=float, default=1.5)
    parser.add_argument("--stop-atr", type=float, default=1.0)
    parser.add_argument("--max-bars", type=int, default=24)
    parser.add_argument("--min-samples", type=int, default=30)
    parser.add_argument("--out", default="edge_report.json")
    args = parser.parse_args()

    settings = get_settings()
    symbols = [s.strip() for s in args.symbols.split(",") if s.strip()]
    frames = [Timeframe(t.strip()) for t in args.timeframes.split(",") if t.strip()]

    print(f"\nمحاسبهٔ لبهٔ واقعی — {len(symbols)} نماد × {len(frames)} تایم‌فریم، "
          f"{args.bars} کندل، RR={args.rr}\n")

    results: list[dict] = []
    for symbol in symbols:
        for frame in frames:
            label = f"{symbol} {frame.value}"
            try:
                result = await one(settings, symbol, frame, args.bars, args.rr,
                                   args.stop_atr, args.max_bars, args.min_samples)
            except (DataUnavailable, ValueError) as exc:
                print(f"  ✗ {label:<18} {exc}")
                results.append({"symbol": symbol, "timeframe": frame.value, "error": str(exc)})
                continue
            except Exception as exc:  # one bad symbol must not kill the report
                print(f"  ✗ {label:<18} {type(exc).__name__}: {exc}")
                results.append({"symbol": symbol, "timeframe": frame.value, "error": repr(exc)})
                continue
            book = result["playbook"]
            print(f"  ✓ {label:<18} نمونهٔ مستقل {book.get('independent_samples', 0):<5} "
                  f"قانون قبول‌شده: {len(book.get('accepted', []))}")
            results.append(result)

    Path(args.out).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    # ── paste-friendly summary ──────────────────────────────────────────
    print("\n" + "=" * 78)
    print("ACCEPTED RULES  (statistically sound AND valid out-of-sample)")
    print("=" * 78)
    print(f"{'symbol':<11}{'tf':<5}{'setup':<15}{'dir':<5}{'filter':<24}{'n':>5}{'WR':>6}{'edgeLB':>8}{'OOS':>7}")
    any_rule = False
    for item in results:
        for rule in item.get("playbook", {}).get("accepted", []):
            any_rule = True
            print(f"{item['symbol']:<11}{item['timeframe']:<5}{rule['setup']:<15}"
                  f"{rule['direction']:<5}{rule['filter'][:23]:<24}{rule['samples']:>5}"
                  f"{rule['win_rate']:>6}{rule['win_rate_lower']:>8}"
                  f"{(rule['oos_edge_points'] if rule['oos_edge_points'] is not None else 0):>7}")
    if not any_rule:
        print("  (هیچ قانونی هر دو فیلتر را پاس نکرد — روی این دیتا لبهٔ قابل‌اتکا پیدا نشد)")

    print("\n" + "=" * 78)
    print("WIN RATE vs REWARD RATIO   (base = coin flip at that RR; only EDGE is skill)")
    print("=" * 78)
    for item in results:
        rows = item.get("curve", {}).get("rows") or []
        if not rows:
            continue
        print(f"\n{item['symbol']} {item['timeframe']}")
        print(f"  {'RR':>5}{'base%':>8}{'WR%':>7}{'EDGE':>7}{'E[R]':>8}{'resolved':>10}")
        for row in rows:
            print(f"  {row['rr']:>5}{row['random_baseline_pct']:>8}{row['win_rate']:>7}"
                  f"{row['edge_points']:>+7}{row['expectancy_r']:>8}{row['resolved']:>10}")

    print(f"\nفایل کامل: {args.out}")
    print("این خروجی را کپی کنید و برای تحلیل بفرستید.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
