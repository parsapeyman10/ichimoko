"""
Walk-forward analysis on real candles.

In-sample  : the older part of the real series (parameter judgement zone).
Out-of-sample: the newer part the engine has never "seen" during tuning.

The previous version projected a synthetic "next 12 months" and used tuned Monte-Carlo
win-rate targets; both are gone. If the engine loses money out-of-sample, the report says so.
"""
from __future__ import annotations

from typing import Any
from datetime import datetime, timezone

from app.config import Settings
from app.models import Timeframe
from app.services.backtest import stress_test_from_trades, run_backtest
from app.services.history import load_history


async def run_forward_test(
    settings: Settings,
    timeframe: str = "5m",
    output_size: int = 1500,
    split: float = 0.7,
    initial_balance: float = 100.0,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    use_trailing: bool = True,
) -> dict[str, Any]:
    tf = Timeframe(timeframe) if timeframe in {t.value for t in Timeframe} else Timeframe.M5
    candles = await load_history(settings, tf, output_size=output_size)
    if len(candles) < 400:
        return {
            "error": f"داده کافی نیست: {len(candles)} کندل واقعی دریافت شد؛ برای walk-forward حداقل ۴۰۰ کندل لازم است",
            "bar": len(candles),
        }

    split_index = int(len(candles) * min(max(split, 0.3), 0.85))
    if split_index < 220:
        return {"error": "دورهٔ آموزشی به حداقل ۲۲۰ کندل واقعی نیاز دارد؛ تقسیم بازه را تغییر دهید", "bars": len(candles)}
    split_time = candles[split_index].timestamp
    # Never run the in-sample replay through the unseen tail and then filter just its
    # trades: that leaks future equity, drawdown, fees, end time and warm-up statistics.
    in_sample = run_backtest(
        candles[:split_index],
        initial_balance=initial_balance,
        risk_percent=risk_percent,
        spread=spread,
        commission_per_oz=commission_per_oz,
        use_trailing=use_trailing,
        start_index=210,
    )
    out_of_sample = run_backtest(
        candles,
        initial_balance=initial_balance,
        risk_percent=risk_percent,
        spread=spread,
        commission_per_oz=commission_per_oz,
        use_trailing=use_trailing,
        start_index=split_index,
    )
    if "error" in in_sample or "error" in out_of_sample:
        return {"error": (in_sample.get("error") or out_of_sample.get("error")), "bar": len(candles)}

    # OOS processes the older bars only as indicator warm-up (start_index=split_index).
    # Expose the actual test window, not those warm-up bars, in its summary and curve.
    out_of_sample["trades"] = [t for t in out_of_sample["trades"] if datetime.fromisoformat(t["entry_time"]) >= split_time]

    return {
        "data_source": "twelve_data" if settings.has_market_key else "spot_fallback",
        "symbol": candles[0].symbol,
        "timeframe": tf.value,
        "bars": len(candles),
        "split": split_index / len(candles),
        "split_time": split_time.isoformat(),
        "in_sample": _metrics(in_sample, bars=split_index, warmup_bars=210,
                              start=candles[0].timestamp, end=candles[split_index - 1].timestamp),
        "out_of_sample": _metrics(out_of_sample, bars=len(candles) - split_index,
                                  warmup_bars=split_index, start=split_time, end=candles[-1].timestamp),
        "out_of_sample_trades": out_of_sample["trades"],
        "stress_test": stress_test_from_trades(out_of_sample["trades"], initial_balance),
        "notes": [
            "بازه‌ها روی دیتای واقعی همان نماد تقسیم شده‌اند؛ هیچ سال آینده‌ای ساخته نشده است.",
            f"بازه In-Sample: {candles[0].timestamp:%Y-%m-%d %H:%M} تا {split_time:%Y-%m-%d %H:%M} UTC",
            f"بازه Out-of-Sample: {split_time:%Y-%m-%d %H:%M} تا {candles[-1].timestamp:%Y-%m-%d %H:%M} UTC",
            "تست استرس فقط روی نتایج واقعی همان بازه بازنمونه‌گیری می‌شود.",
        ],
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }


def _metrics(result: dict[str, Any], *, bars: int, warmup_bars: int,
             start: datetime, end: datetime) -> dict[str, Any]:
    curve = [point for point in result["equity_curve"] if point["time"] >= start.isoformat()]
    if not curve or curve[0]["time"] != start.isoformat():
        curve.insert(0, {"time": start.isoformat(), "balance": result["initial_balance"]})
    return {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "bars": bars,
        "warmup_bars": warmup_bars,
        "initial_balance": result["initial_balance"],
        "final_balance": result["final_balance"],
        "total_pnl": result["total_pnl"],
        "total_return_pct": result["total_return_pct"],
        "total_trades": result["total_trades"],
        "wins": result["wins"],
        "losses": result["losses"],
        "win_rate": result["win_rate"],
        "profit_factor": result["profit_factor"],
        "expectancy": result["expectancy"],
        "max_drawdown_pct": result["max_drawdown_pct"],
        "fees_paid": result["fees_paid"],
        "skipped_min_lot": result["skipped_min_lot"],
        "equity_curve": curve,
    }
