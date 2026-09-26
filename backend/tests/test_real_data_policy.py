"""
Policy tests: the platform must never serve invented market data.

These tests build their own candle fixtures *as test input* — that is legitimate test data.
What they verify is that no production code path fabricates data when the provider is
unavailable, and that the backtest works only on the candles it is handed.
"""
from __future__ import annotations

import math
import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parent.parent
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.models import Candle, Timeframe  # noqa: E402
from app.services.backtest import cross_candidate_indices, run_backtest, stress_test_from_trades  # noqa: E402
from app.services.history import DataUnavailable, load_history  # noqa: E402
from app.services.news_feed import NewsAggregator  # noqa: E402
from app.services import spot_feed  # noqa: E402
from app.config import Settings  # noqa: E402


def fixture_candles(count: int = 700, timeframe: Timeframe = Timeframe.M5) -> list[Candle]:
    """Deterministic fixture used only as test input."""
    rng = random.Random(1234)
    price = 3300.0
    candles: list[Candle] = []
    start = datetime(2026, 1, 5, 0, 0, tzinfo=timezone.utc)
    for i in range(count):
        drift = math.sin(i / 40) * 0.9 + math.sin(i / 11) * 0.35
        open_price = price
        close = max(1.0, open_price + drift + rng.gauss(0, 0.7))
        high = max(open_price, close) + abs(rng.gauss(0, 0.35))
        low = min(open_price, close) - abs(rng.gauss(0, 0.35))
        candles.append(
            Candle(
                symbol="XAU/USD",
                timeframe=timeframe,
                timestamp=start + timedelta(minutes=timeframe.seconds // 60 * i),
                open=round(open_price, 2),
                high=round(high, 2),
                low=round(low, 2),
                close=round(close, 2),
                volume=float(rng.randint(200, 900)),
                complete=True,
            )
        )
        price = close
    return candles


def test_load_history_requires_a_key(tmp_path, monkeypatch):
    # Isolate the free spot-fallback store so this policy test never sees a real on-disk
    # cache from another test run/process: with no key AND no fallback history yet, the
    # backend must still honestly fail rather than fabricate anything.
    monkeypatch.setattr(spot_feed, "store", spot_feed.SpotHistoryStore(path=tmp_path / "spot.json"))
    settings = Settings(twelve_data_api_key=None)
    import asyncio

    try:
        asyncio.run(load_history(settings, Timeframe.M5, output_size=100))
    except DataUnavailable as exc:
        assert "کلید" in str(exc)
        return
    raise AssertionError("load_history must raise DataUnavailable without an API key or fallback history")


def test_news_aggregator_returns_nothing_without_a_license():
    settings = Settings(fmp_api_key=None)
    aggregator = NewsAggregator(settings)
    import asyncio

    articles = asyncio.run(aggregator.fetch())
    assert articles == []
    status = aggregator.status()
    assert status["configured"] is False
    assert aggregator.status()["provider"] is None


def test_backtest_reports_real_data_and_cost_assumptions():
    candles = fixture_candles()
    result = run_backtest(candles, initial_balance=100.0, risk_percent=0.5, spread=0.30)
    assert result["data_source"] == "twelve_data"
    assert result["bars"] == len(candles)
    assert result["spread"] == 0.30
    assert any("کندل واقعی" in note for note in result["notes"])
    assert "total_trades" in result and result["total_trades"] >= 0
    if result["total_trades"]:
        assert result["wins"] + result["losses"] == result["total_trades"]
        assert result["final_balance"] == round(
            result["initial_balance"] + sum(t["pnl"] for t in result["trades"]), 2
        ) or abs(result["final_balance"] - (result["initial_balance"] + sum(t["pnl"] for t in result["trades"]))) < 0.05
        for trade in result["trades"]:
            assert trade["exit_reason"]
            assert trade["position_oz"] > 0


def test_backtest_refuses_short_history():
    result = run_backtest(fixture_candles(120))
    assert "error" in result


def test_cross_candidates_are_a_subset_of_bars():
    candles = fixture_candles(500)
    candidates = cross_candidate_indices(candles, Timeframe.M5)
    assert all(0 <= index < len(candles) for index in candidates)
    assert len(candidates) < len(candles)


def test_stress_test_needs_real_trades():
    assert "error" in stress_test_from_trades([], initial_balance=100)
    fake = [{"r_multiple": r} for r in (1.5, -1.0, 1.8, -1.0, 2.0, -1.0)]
    report = stress_test_from_trades(fake, initial_balance=100, runs=200)
    assert report["real_trades"] == 6
    assert "median_final_balance" in report
    assert "بازنمونه‌گیری" in report["note"]


def test_api_returns_503_instead_of_made_up_data():
    from fastapi.testclient import TestClient

    from app.main import app

    client = TestClient(app)
    response = client.get("/api/v1/backtest/run?timeframe=5m&bars=300")
    assert response.status_code == 503
    assert "کلید" in response.json()["detail"] or "داده" in response.json()["detail"]

    status = client.get("/api/v1/data/status").json()
    assert status["api_key_configured"] is False
    assert "هیچ کندل" in status["policy"]


def test_no_synthetic_generators_remain():
    market_feed = (BACKEND_ROOT / "app" / "services" / "market_feed.py").read_text()
    backtest = (BACKEND_ROOT / "app" / "services" / "backtest.py").read_text()
    news_feed = (BACKEND_ROOT / "app" / "services" / "news_feed.py").read_text()
    journal = (BACKEND_ROOT / "app" / "services" / "journal.py").read_text()

    assert "synthetic_ticks" not in market_feed
    assert "generate_gold_history" not in backtest
    assert "YEAR_ANCHORS" not in backtest
    assert "_generate_tuned_simulation" not in backtest
    assert "synthetic" not in news_feed.lower() or "no headline is ever invented" in news_feed
    assert "seed_demo" not in journal
    assert not (BACKEND_ROOT / "app" / "data" / "journal.json").exists()


def test_walk_forward_splits_real_series(monkeypatch):
    import asyncio

    from app.services import forward_test as forward_module

    async def fake_loader(settings, timeframe, output_size=1500, **kwargs):
        return fixture_candles(1200, Timeframe.M5)

    monkeypatch.setattr(forward_module, "load_history", fake_loader)
    report = asyncio.run(forward_module.run_forward_test(Settings(twelve_data_api_key="test"), timeframe="5m", output_size=1200))
    assert report["data_source"] == "twelve_data"
    assert report["bars"] == 1200
    assert report["in_sample"]["bars"] == 840
    assert report["in_sample"]["end"] < report["split_time"]
    assert report["out_of_sample"]["bars"] == 360
    assert report["out_of_sample"]["start"] == report["split_time"]
    assert report["out_of_sample"]["warmup_bars"] == 840
    assert report["out_of_sample"]["equity_curve"][0]["time"] == report["split_time"]
    assert report["out_of_sample"]["equity_curve"][0]["balance"] == 100.0
    for trade in report["out_of_sample_trades"]:
        assert trade["entry_time"] >= report["split_time"]
    assert any("دیتای واقعی" in note for note in report["notes"])


def test_feasibility_explains_a_100_dollar_account(monkeypatch):
    candles = fixture_candles(2000)
    result = run_backtest(candles, initial_balance=100.0, risk_percent=0.5, spread=0.30)
    feasibility = result["feasibility"]
    assert feasibility["broker_min_position_oz"] == 1.0
    if result["skipped_min_lot"]:
        assert feasibility["suggested_min_balance"] > 100
        assert "حداقل موجودی" in feasibility["verdict"]
    else:
        assert "قابل اجرا" in feasibility["verdict"]
