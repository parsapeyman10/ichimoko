from app.services.performance_metrics import summarize_trades


def test_full_trade_report_and_undefined_ratios():
    trades = [
        {"side": "BUY", "pnl": 20.0, "r_multiple": 2.0, "entry_time": "2026-01-01T00:00:00+00:00", "exit_time": "2026-01-01T00:01:00+00:00", "fees": 1.0},
        {"side": "SELL", "pnl": 10.0, "r_multiple": 1.0, "entry_time": "2026-01-01T00:01:00+00:00", "exit_time": "2026-01-01T00:03:00+00:00", "fees": 1.0},
        {"side": "SELL", "pnl": -15.0, "r_multiple": -1.0, "entry_time": "2026-01-01T00:03:00+00:00", "exit_time": "2026-01-01T00:04:00+00:00", "fees": 1.0},
        {"side": "BUY", "pnl": -5.0, "r_multiple": -0.5, "entry_time": "2026-01-01T00:04:00+00:00", "exit_time": "2026-01-01T00:05:00+00:00", "fees": 1.0},
    ]
    report = summarize_trades(trades, 100.0)
    assert report["total"] == 4 and report["wins"] == 2 and report["losses"] == 2
    assert report["long_count"] == 2 and report["short_count"] == 2
    assert report["average_win_usd"] == 15.0 and report["average_loss_usd"] == -10.0
    assert report["average_duration_seconds"] == 75
    assert report["longest_winning_streak"] == 2 and report["longest_losing_streak"] == 2
    assert report["max_drawdown_pct"] == round(20 / 130 * 100, 2)
    assert report["profit_factor"] == 1.5 and report["net_pnl_usd"] == 10
    assert report["fees_usd"] == 4 and report["sharpe_per_trade"] is not None
    assert "not annualized" in report["method"]

    empty = summarize_trades([], 100)
    assert empty["sharpe_per_trade"] is None and empty["profit_factor"] is None
    assert empty["max_drawdown_pct"] is None and empty["win_rate_pct"] is None
    all_wins = summarize_trades(trades[:2], 100)
    assert all_wins["profit_factor"] is None and all_wins["average_loss_usd"] is None


def test_report_is_attached_to_real_candle_replay():
    from test_real_data_policy import fixture_candles
    from app.services.backtest import run_backtest

    result = run_backtest(fixture_candles(700), initial_balance=10000, min_position_oz=0.01)
    assert result["data_source"] == "twelve_data"
    assert result["performance"]["total"] == result["total_trades"]
    assert result["performance"]["long_count"] + result["performance"]["short_count"] == result["total_trades"]
    assert result["sharpe"] == result["performance"]["sharpe_per_trade"]
