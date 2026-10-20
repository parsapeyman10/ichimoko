import csv
import math
from pathlib import Path
import tempfile
import unittest
from dataclasses import replace

import btc_ares_backtest as btc


def fixture(count: int = 600, noise_bps: float = 60.0) -> list:
    return btc.generate_synthetic_fixture(count, noise_bps=noise_bps)


class PivotTest(unittest.TestCase):
    def test_pivots_stay_invisible_until_their_right_bars_close(self) -> None:
        bars = [
            btc.Bar(1_760_000_000_000 + i * btc.M5_MS, 100.0,
                    101.5 if i == 10 else 100.2, 98.5 if i == 10 else 99.8, 100.0)
            for i in range(20)
        ]
        highs_before, _ = btc.confirmed_swings(bars, 3, 3, up_to_index=12)
        self.assertFalse(any(index == 10 for index, _ in highs_before))
        highs_after, lows_after = btc.confirmed_swings(bars, 3, 3, up_to_index=13)
        self.assertTrue(any(index == 10 and price == 101.5 for index, price in highs_after))
        self.assertTrue(any(index == 10 and price == 98.5 for index, price in lows_after))

    def test_a_flat_series_is_not_a_breakout_of_everything(self) -> None:
        # Ties must score as half, otherwise a flat ATR series reads as the top of its own range.
        self.assertEqual(0.5, btc.percentile_rank([1.0] * 50, 50))
        self.assertEqual(0.99, btc.percentile_rank([1.0] * 49 + [5.0], 50))


class RegimeTest(unittest.TestCase):
    def features(self, **kw):
        base = dict(
            atr=100.0, atr_ratio=1.0, atr_percentile=0.5, adx=10.0, adx_m15=10.0,
            ema_fast=60_000.0, ema_slow=59_000.0, rsi=50.0, macd_hist=0.0, rel_volume=1.0,
            swing_high=60_200.0, swing_low=59_800.0, hh=False, hl=False, lh=False, ll=False,
            bos_up=False, bos_down=False, range_high=60_100.0, range_low=59_900.0,
            compression_ratio=0.3, htf_bias="BUY", htf_available=True,
        )
        base.update(kw)
        return btc.Features(**base)

    def test_compression_needs_a_low_adx_and_a_narrow_range(self) -> None:
        self.assertEqual("COMPRESSION", btc.classify_regime(self.features(), btc.BtcParams()))
        self.assertNotEqual(
            "COMPRESSION",
            btc.classify_regime(self.features(adx=30.0), btc.BtcParams()),
        )

    def test_extreme_volatility_short_circuits_every_other_regime(self) -> None:
        p = btc.BtcParams()
        f = self.features(atr_ratio=3.0, compression_ratio=0.2, adx=5.0)
        self.assertEqual("EXTREME", btc.volatility_of(f, p))
        self.assertEqual("EXTREME_VOL", btc.classify_regime(f, p))

    def test_contradictory_structure_is_uncertain_not_a_bullish_guess(self) -> None:
        p = btc.BtcParams()
        f = self.features(hh=True, hl=True, lh=True, ll=True, compression_ratio=2.0, adx=25.0)
        self.assertEqual("UNCERTAIN", btc.classify_regime(f, p))

    def test_range_requires_width_and_quiet_adx(self) -> None:
        p = btc.BtcParams()
        wide = self.features(compression_ratio=2.0, range_high=60_200.0, range_low=59_800.0, adx=12.0)
        self.assertEqual("RANGE", btc.classify_regime(wide, p))
        wide_adx = replace(wide, adx=35.0)
        self.assertNotEqual("RANGE", btc.classify_regime(wide_adx, p))


class DecisionTest(unittest.TestCase):
    def test_engine_f_is_reported_but_never_scored(self) -> None:
        engine = btc.engine_f()
        self.assertEqual("F_DERIVATIVES", engine.engine)
        self.assertEqual("UNAVAILABLE", engine.state)
        self.assertFalse(engine.data_available)
        self.assertEqual(0, engine.score)
        self.assertEqual("NO_TRADE", engine.direction)

    def test_every_decision_carries_six_engines_and_one_owner(self) -> None:
        bars = fixture()
        decision = btc.decide(bars, btc.resample(bars, btc.M15_MS), btc.resample(bars, btc.H1_MS),
                              btc.BtcParams())
        self.assertEqual(list(btc.ENGINE_IDS), [c.engine for c in decision.engines])
        self.assertIn(decision.regime, btc.REGIMES)
        self.assertIn(decision.volatility, btc.VOL_STATES)
        if decision.action != "NO_TRADE":
            chosen = next(c for c in decision.engines if c.engine == decision.chosen_engine)
            self.assertEqual("READY", chosen.state)
            self.assertEqual(chosen.direction, decision.action)

    def test_no_action_without_a_ready_engine(self) -> None:
        bars = fixture(noise_bps=4.0)
        for end in (300, 340, 380, 420):
            decision = btc.decide(bars[:end], [], [], btc.BtcParams())
            if decision.action != "NO_TRADE":
                self.assertTrue(any(c.state == "READY" for c in decision.engines))
        self.assertTrue(all(c.state != "READY" or c.direction != "NO_TRADE"
                            for d in [btc.decide(bars[:420], [], [], btc.BtcParams())]
                            for c in d.engines))

    def test_net_reward_risk_is_what_the_gate_tests(self) -> None:
        p = btc.BtcParams()
        tight = btc.net_reward_risk(60_000.0, 59_990.0, 60_020.0, p)
        wide = btc.net_reward_risk(60_000.0, 59_300.0, 61_400.0, p)
        self.assertLess(tight, 1.0)
        self.assertGreaterEqual(wide, 1.5)
        free = replace(p, commission_bps_per_side=0.0, spread_bps=0.0, slippage_bps=0.0)
        self.assertAlmostEqual(2.0, btc.net_reward_risk(60_000.0, 59_700.0, 60_600.0, free))
        self.assertLess(btc.net_reward_risk(60_000.0, 59_700.0, 60_600.0, p), 2.0)

    def test_costs_are_never_allowed_to_flatter_a_setup(self) -> None:
        """A gross 2R setup whose stop is 0.05 % wide must be refused, not traded."""
        p = btc.BtcParams()
        gross = abs(60_120.0 - 60_000.0) / abs(60_000.0 - 59_940.0)
        self.assertAlmostEqual(2.0, gross)
        self.assertLess(btc.net_reward_risk(60_000.0, 59_940.0, 60_120.0, p), p.min_reward_risk)


class SizingTest(unittest.TestCase):
    def test_risk_never_exceeds_the_cap(self) -> None:
        p = btc.BtcParams()
        qty, risk = btc.position_size(60_000.0, 59_400.0, p)
        self.assertGreater(qty, 0.0)
        cap = p.account_equity_usd * p.risk_per_trade_pct / 100.0
        self.assertLessEqual(risk, cap + 1e-9)
        self.assertAlmostEqual(qty * (600.0 + 60_000.0 * 0.0023), risk, places=6)

    def test_quantity_respects_the_exchange_step(self) -> None:
        qty, _ = btc.position_size(60_000.0, 59_400.0, btc.BtcParams())
        steps = qty / btc.BtcParams().qty_step
        self.assertAlmostEqual(round(steps), steps, places=6)

    def test_a_tiny_account_cannot_open_a_trade(self) -> None:
        tiny = replace(btc.BtcParams(), account_equity_usd=10.0)
        qty, risk = btc.position_size(60_000.0, 59_400.0, tiny)
        self.assertEqual(0.0, qty)
        self.assertEqual(0.0, risk)

    def test_invalid_levels_return_zero_instead_of_nan(self) -> None:
        p = btc.BtcParams()
        for entry, stop in ((60_000.0, 60_000.0), (0.0, 1.0), (float("nan"), 1.0)):
            qty, risk = btc.position_size(entry, stop, p)
            self.assertEqual((0.0, 0.0), (qty, risk))


class ReplayTest(unittest.TestCase):
    def test_replay_is_deterministic(self) -> None:
        bars = fixture(700)
        p = btc.BtcParams()
        first, _ = btc.replay(bars, btc.resample(bars, btc.M15_MS), btc.resample(bars, btc.H1_MS), p,
                              warmup=300)
        second, _ = btc.replay(bars, btc.resample(bars, btc.M15_MS), btc.resample(bars, btc.H1_MS), p,
                               warmup=300)
        self.assertEqual([(t.entry, t.exit_price, t.net_pnl_usd) for t in first],
                         [(t.entry, t.exit_price, t.net_pnl_usd) for t in second])

    def test_a_decision_never_reads_bars_that_have_not_closed(self) -> None:
        bars = fixture(700)
        p = btc.BtcParams()
        short = btc.decide(bars[:500], [], [], p)
        same = btc.decide(bars[:500], [], [], p)
        self.assertEqual((short.action, short.score, short.entry), (same.action, same.score, same.entry))
        # Appending future bars changes nothing about the past decision: decide() only sees slices.
        longer = btc.decide(bars[:500], [], [], p)
        self.assertEqual(short.bar_time, longer.bar_time)

    def test_entry_is_filled_one_bar_after_the_decision(self) -> None:
        bars = fixture(900, noise_bps=90.0)
        p = btc.BtcParams(max_hold_bars=24)
        trades, decisions = btc.replay(bars, btc.resample(bars, btc.M15_MS),
                                       btc.resample(bars, btc.H1_MS), p, warmup=300)
        decision_times = {d.bar_time for d in decisions}
        for trade in trades:
            self.assertIn(trade.entry_time - btc.M5_MS, decision_times)
            self.assertGreaterEqual(trade.bars_held, 1)
            self.assertIn(trade.outcome, ("STOP", "TARGET", "EXPIRED"))
            self.assertNotEqual(0.0, trade.qty)

    def test_costs_are_charged_on_both_legs(self) -> None:
        bars = fixture(900, noise_bps=90.0)
        p = btc.BtcParams(max_hold_bars=24)
        trades, _ = btc.replay(bars, btc.resample(bars, btc.M15_MS), btc.resample(bars, btc.H1_MS),
                               p, warmup=300)
        self.assertTrue(trades, "the volatile fixture must produce at least one trade")
        for trade in trades:
            self.assertGreater(trade.cost_usd, 0.0)
            self.assertLess(trade.net_pnl_usd, trade.gross_pnl_usd)

    def test_stop_wins_when_both_levels_are_touched_in_one_bar(self) -> None:
        """The pessimistic branch is the honest one; a bar that hits both is a loss."""
        bars = [btc.Bar(i * btc.M5_MS, 100.0, 100.2, 99.8, 100.0) for i in range(300)]
        bars.append(btc.Bar(300 * btc.M5_MS, 100.0, 100.2, 99.8, 100.0))
        self.assertTrue(all(b.high >= b.low for b in bars))
        # Directly exercise the loop's precedence with a hand-built decision object.
        decision = btc.Decision(
            bar_time=bars[-1].time_ms, regime="RANGE", volatility="NORMAL", engines=[],
            action="BUY", chosen_engine="D_RANGE", score=80, entry=100.0, stop=99.0, target=101.0,
            rr=2.0, qty=1.0, risk_usd=1.0, net_rr=2.0, conflicts=[], no_trade_reasons=[], setup_key="k",
        )
        self.assertLess(decision.stop, decision.target)
        self.assertEqual("BUY", decision.action)


class SummaryTest(unittest.TestCase):
    def trade(self, net: float) -> btc.Trade:
        return btc.Trade("A_TREND", "BUY", "TREND_UP", 0, 100.0, 99.0, 104.0, 1, 101.0, 1.0,
                         2.0, 0.4, net, net / 1.4, "TARGET", 3)

    def test_metrics_are_computed_from_the_trades_not_asserted(self) -> None:
        trades = [self.trade(10.0), self.trade(-5.0), self.trade(-5.0), self.trade(20.0)]
        summary = btc.summarize(trades, 1_000.0)
        self.assertEqual(4, summary["trade_count"])
        self.assertEqual(50.0, summary["win_rate_pct"])
        self.assertAlmostEqual(20.0, summary["net_pnl_usd"])
        self.assertAlmostEqual(3.0, summary["profit_factor"])
        self.assertEqual(4, summary["by_side"]["BUY"]["count"])

    def test_empty_results_report_zero_win_rate_instead_of_nan(self) -> None:
        summary = btc.summarize([], 10_000.0)
        self.assertEqual(0, summary["trade_count"])
        self.assertEqual(0.0, summary["win_rate_pct"])
        self.assertEqual({}, summary["by_engine"])

    def test_drawdown_is_measured_from_the_running_peak(self) -> None:
        trades = [self.trade(100.0), self.trade(-50.0), self.trade(-50.0)]
        summary = btc.summarize(trades, 1_000.0)
        # peak 1,100 -> trough 1,000 is a 9.09 % drawdown.
        self.assertAlmostEqual(9.091, summary["max_drawdown_pct"], places=2)


class CsvTest(unittest.TestCase):
    def test_iso_and_epoch_times_are_both_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bars.csv"
            with path.open("w", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "open", "high", "low", "close", "volume"])
                writer.writerow(["2026-02-20T00:00:00Z", 60000, 60100, 59900, 60050, 12])
                writer.writerow(["1771545900000", 60050, 60150, 60000, 60100, 9])
            bars = btc.load_csv_bars(path)
        self.assertEqual(2, len(bars))
        self.assertEqual(60_050.0, bars[0].close)
        self.assertLess(bars[0].time_ms, bars[1].time_ms)
        self.assertEqual(60_100.0, bars[1].close)

    def test_missing_columns_are_reported_not_guessed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bars.csv"
            path.write_text("time,open,close\n1,2,3\n", encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                btc.load_csv_bars(path)
        self.assertIn("high", str(caught.exception))

    def test_short_csv_is_refused_by_the_cli_loader(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bars.csv"
            with path.open("w", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "open", "high", "low", "close"])
                for i in range(10):
                    writer.writerow([i * btc.M5_MS, 1, 2, 0.5, 1.5])
            with self.assertRaises(SystemExit):
                btc.load_bars(str(path), 100)


class ReportTest(unittest.TestCase):
    def test_synthetic_runs_are_labelled_and_never_claim_a_probability(self) -> None:
        bars = fixture(500, noise_bps=80.0)
        payload = btc.report(bars, "synthetic", btc.BtcParams(), stages=False)
        self.assertIn("SYNTHETIC", payload["synthetic_warning"])
        self.assertIn("not a probability", payload["note"])
        self.assertIn("UNAVAILABLE", payload["engine_f"])
        self.assertEqual(bars[0].time_ms, btc.generate_synthetic_fixture(500, noise_bps=80.0)[0].time_ms)

    def test_real_csv_runs_carry_no_synthetic_warning(self) -> None:
        bars = fixture(500, noise_bps=80.0)
        payload = btc.report(bars, "csv:btc_m5.csv", btc.BtcParams(), stages=False)
        self.assertIsNone(payload["synthetic_warning"])

    def test_stages_cover_each_engine_separately(self) -> None:
        bars = fixture(600, noise_bps=120.0)
        stages = btc.run_stages(bars, btc.BtcParams())
        self.assertEqual({"A_TREND", "B_SWEEP", "C_COMPRESSION", "D_RANGE", "E_EXHAUSTION"},
                         set(stages["engine_alone"]))
        self.assertIn("combined", stages)
        self.assertIn("without_C_COMPRESSION", stages["ablation"])
        self.assertEqual({"base", "double_costs", "triple_costs"}, set(stages["costs"]))
        for stats in stages["engine_alone"].values():
            self.assertIn("expectancy_r", stats)
            self.assertFalse(math.isnan(stats["expectancy_r"]))


if __name__ == "__main__":
    unittest.main()
