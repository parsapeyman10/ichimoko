"""
Year-to-date trade list — built from real candles only.

The old implementation fabricated ~200 trades with invented win/loss narratives.
This module replays the live engine over the real bars the provider returns for the
requested year and reports exactly what happened, including how much of the period the
current API plan could actually deliver.
"""
from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Any

from app.config import Settings
from app.models import Timeframe
from app.services.backtest import run_backtest
from app.services.history import DataUnavailable, load_history

MAX_BARS = 5000


async def get_ytd_report(
    settings: Settings,
    timeframe: str = "5m",
    year: int | None = None,
    initial_balance: float = 100.0,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
) -> dict[str, Any]:
    target_year = year or datetime.now(timezone.utc).year
    tf = Timeframe(timeframe) if timeframe in {t.value for t in Timeframe} else Timeframe.M5
    start = date(target_year, 1, 1).isoformat()
    end = min(date(target_year, 12, 31), datetime.now(timezone.utc).date()).isoformat()

    candles = await load_history(
        settings,
        tf,
        output_size=MAX_BARS,
        start_date=start,
        end_date=end,
        use_cache=True,
    )

    result = run_backtest(
        candles,
        initial_balance=initial_balance,
        risk_percent=risk_percent,
        spread=spread,
        commission_per_oz=commission_per_oz,
    )
    if "error" in result:
        return {"error": result["error"], "year": target_year, "timeframe": tf.value}

    coverage_start = candles[0].timestamp
    coverage_end = candles[-1].timestamp
    notes = list(result.get("notes", []))
    notes.append(
        "پوشش داده: پلن فعلی API حداکثر "
        f"{MAX_BARS} کندل برای هر درخواست برمی‌گرداند؛ بازه واقعی دریافت‌شده از "
        f"{coverage_start:%Y-%m-%d %H:%M} تا {coverage_end:%Y-%m-%d %H:%M} UTC است. "
        "هر عددی خارج از این بازه گزارش نمی‌شود."
    )
    return {
        "year": target_year,
        "timeframe": tf.value,
        "data_source": "twelve_data",
        "coverage": {
            "requested_start": start,
            "requested_end": end,
            "actual_start": coverage_start.isoformat(),
            "actual_end": coverage_end.isoformat(),
            "bars": len(candles),
        },
        "summary": {
            "trades": result["total_trades"],
            "wins": result["wins"],
            "losses": result["losses"],
            "win_rate": result["win_rate"],
            "profit_factor": result["profit_factor"],
            "net_pnl": result["total_pnl"],
            "final_balance": result["final_balance"],
            "max_drawdown_pct": result["max_drawdown_pct"],
            "fees_paid": result["fees_paid"],
        },
        "trades": result["trades"],
        "yearly": result["yearly"],
        "rejected_setups": result["rejected_setups"],
        "skipped_min_lot": result["skipped_min_lot"],
        "equity_curve": result["equity_curve"],
        "notes": notes,
    }


__all__ = ["get_ytd_report", "DataUnavailable"]
