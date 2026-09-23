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
    in_sample = run_backtest(
        candles,
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

    # Restrict each half's trades to its own window for honest statistics.
    split_time = candles[split_index].timestamp
    in_sample["trades"] = [t for t in in_sample["trades"] if datetime.fromisoformat(t["entry_time"]) < split_time]
    out_of_sample["trades"] = [t for t in out_of_sample["trades"] if datetime.fromisoformat(t["entry_time"]) >= split_time]

    return {
        "data_source": "twelve_data",
        "symbol": candles[0].symbol,
        "timeframe": tf.value,
        "bars": len(candles),
        "split": split,
        "split_time": split_time.isoformat(),
        "in_sample": _metrics(in_sample),
        "out_of_sample": _metrics(out_of_sample),
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


def _metrics(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "start": result["start"],
        "end": result["end"],
        "bars": result["bars"],
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
        "equity_curve": result["equity_curve"],
    }
