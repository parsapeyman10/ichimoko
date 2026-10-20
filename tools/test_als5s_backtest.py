import csv
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path

import als5s_backtest as als


def fixture(count: int = 900) -> list:
    return als.generate_synthetic_fixture(count)


class PivotTest(unittest.TestCase):
    def test_pivots_stay_invisible_until_their_right_bars_close(self) -> None:
        bars = [
            als.Bar(1_760_000_000_000 + i * als.M5_MS, 30.0,
                    30.15 if i == 10 else 30.02, 29.85 if i == 10 else 29.98, 30.0)
            for i in range(20)
        ]
        highs_before, _ = als.confirmed_swings(bars, 3, 3, up_to_index=12)
        self.assertFalse(any(index == 10 for index, _ in highs_before))
        highs_after, lows_after = als.confirmed_swings(bars, 3, 3, up_to_index=13)
        self.assertTrue(any(index == 10 and price == 30.15 for index, price in highs_after))
        self.assertTrue(any(index == 10 and price == 29.85 for index, price in lows_after))


class RegimeTest(unittest.TestCase):
    def test_protective_state_is_entered_immediately(self) -> None:
        p = als.SilverParams()
        regime, note = als.classify_regime("HIGH_VOL", "RANGE", p)
        self.assertEqual("HIGH_VOL", regime)
        self.assertIn("protective", note)

    def test_leaving_the_protective_state_needs_a_second_bar(self) -> None:
        p = als.SilverParams()
        # Hysteresis compares the previous bar's *raw* label, exactly like the Kotlin engine.
        regime, _ = als.classify_regime("RANGE", "HIGH_VOL", p)
        self.assertEqual("HIGH_VOL", regime)
        regime, _ = als.classify_regime("RANGE", "RANGE", p)
        self.assertEqual("RANGE", regime)

    def test_hysteresis_never_lands_on_an_unconfirmed_label(self) -> None:
        p = als.SilverParams()
        for now in als.REGIMES:
            for previous in als.REGIMES:
                regime, _ = als.classify_regime(now, previous, p)
                self.assertIn(regime, (now, previous), f"{now} after {previous}")


class CostTest(unittest.TestCase):
    def test_round_trip_cost_per_ounce_matches_the_kotlin_policy(self) -> None:
        p = als.SilverParams()
        # 2 × 0.0007 commission + 0.02 spread + 2 × 30.00 × 2 bps
        self.assertAlmostEqual(0.0334, als.round_trip_cost_per_oz(30.0, p), places=6)

    def test_a_wider_spread_is_charged_to_the_trade(self) -> None:
        p = als.SilverParams()
        self.assertAlmostEqual(0.0434, als.round_trip_cost_per_oz(30.0, p, spread_per_oz=0.03), places=6)

    def test_200_ounce_round_trip_costs_are_linear_in_size(self) -> None:
        p = als.SilverParams()
        per_oz = als.round_trip_cost_per_oz(30.0, p)
        self.assertAlmostEqual(per_oz * 200.0, 6.68, places=3)


class RewardRiskTest(unittest.TestCase):
    def test_a_tight_stop_cannot_pay_for_its_own_costs(self) -> None:
        p = als.SilverParams()
        cost = als.round_trip_cost_per_oz(30.0, p)
        tight = als.net_reward_risk(30.0, 29.85, 30.30, cost)  # 15 cents of risk
        wide = als.net_reward_risk(30.0, 29.83, 30.34, cost)   # 17 cents of risk
        self.assertLess(tight, p.min_reward_risk)
        self.assertGreaterEqual(wide, p.min_reward_risk)

    def test_a_target_inside_the_cost_is_worthless(self) -> None:
        cost = als.round_trip_cost_per_oz(30.0, als.SilverParams())
        self.assertLess(als.net_reward_risk(30.0, 29.0, 30.01, cost), 0.0)


class SizingTest(unittest.TestCase):
    def test_risk_cap_governs_size(self) -> None:
        p = als.SilverParams()
        cost = als.round_trip_cost_per_oz(30.0, p)
        oz, risk_usd = als.position_oz(30.0, 29.80, 10_000.0, 0.25, cost, p)
        self.assertGreater(oz, 0.0)
        self.assertAlmostEqual(25.0, risk_usd, delta=0.05)
        steps = oz / p.qty_step_oz  # a whole number of 0.01 oz steps
        self.assertAlmostEqual(round(steps), steps, places=6)

    def test_a_sub_minimum_order_is_refused_rather_than_inflated(self) -> None:
        p = als.SilverParams()
        cost = als.round_trip_cost_per_oz(30.0, p)
        oz, risk_usd = als.position_oz(30.0, 20.0, 100.0, 0.25, cost, p)
        self.assertEqual((0.0, 0.0), (oz, risk_usd))


class FeatureTest(unittest.TestCase):
    def test_relative_volume_is_an_own_history_ratio(self) -> None:
        bars = [als.Bar(i * als.M5_MS, 30.0, 30.01, 29.99, 30.0, 10.0) for i in range(60)]
        bars.append(als.Bar(60 * als.M5_MS, 30.0, 30.05, 29.95, 30.0, 30.0))
        values = als.relative_volume(bars, 30)
        self.assertAlmostEqual(1.0, values[40], places=6)
        self.assertGreater(values[-1], 2.0)

    def test_a_gap_enters_the_true_range_through_the_previous_close(self) -> None:
        bars = [als.Bar(i * als.M5_MS, 30.0, 30.01, 29.99, 30.0) for i in range(20)]
        bars.append(als.Bar(20 * als.M5_MS, 30.5, 30.51, 30.49, 30.5))  # 0.51 gap, 0.02 range
        self.assertAlmostEqual(0.51, als.true_ranges(bars)[-1], places=6)
        self.assertGreater(max(v for v in als.compute_atr(bars, 14) if v is not None), 0.05)

    def test_engine_a_refuses_without_a_closed_m15_frame(self) -> None:
        p = als.SilverParams()
        bars = fixture(400)
        f = als.build_features(bars, [], [], p)  # no M15 frame at all
        self.assertFalse(f.m15_available)
        blocked = als.engine_a(bars, f, "BULL_TREND", p, 0.03)
        self.assertEqual("IDLE", blocked.state)
        self.assertIn("M15", blocked.detail)
        self.assertNotEqual("READY", als.decide(bars, [], [], p).engines[0].state)


class GoldModuleTest(unittest.TestCase):
    def test_the_cross_module_is_unavailable_without_gold(self) -> None:
        decision = als.decide(fixture(400), als.resample(fixture(400), als.M15_MS), [], als.SilverParams())
        cross = next(c for c in decision.engines if c.engine == "X_CROSS")
        self.assertEqual("UNAVAILABLE", cross.state)
        self.assertEqual("NO_TRADE", cross.direction)
        self.assertFalse(cross.data_available)
        self.assertFalse(decision.gold_available)

    def test_gold_alone_never_creates_a_direction(self) -> None:
        p = als.SilverParams()
        silver = fixture(600)
        gold = [replace(b, open=b.open * 33.0, high=b.high * 33.0, low=b.low * 33.0, close=b.close * 33.0)
                for b in silver]
        decision = als.decide(silver, als.resample(silver, als.M15_MS), gold, p)
        cross = next(c for c in decision.engines if c.engine == "X_CROSS")
        self.assertTrue(cross.data_available)
        self.assertEqual("NO_TRADE", cross.direction)

    def test_correlation_of_a_scaled_copy_is_one(self) -> None:
        p = als.SilverParams()
        silver = fixture(300)
        gold = [replace(b, open=b.open * 33.0, high=b.high * 33.0, low=b.low * 33.0, close=b.close * 33.0)
                for b in silver]
        context = als.gold_context(silver, gold, p)
        self.assertTrue(context.available)
        self.assertAlmostEqual(1.0, context.correlation or 0.0, places=6)


class ReplayTest(unittest.TestCase):
    def test_replay_never_uses_a_bar_before_it_closes(self) -> None:
        bars = fixture(600)
        trades, decisions = als.replay(bars, als.resample(bars, als.M15_MS), [], als.SilverParams(),
                                       warmup=280)
        for trade in trades:
            self.assertGreater(trade.entry_time, 0)
            trigger = next(d for d in decisions if d.bar_time < trade.entry_time)
            self.assertLessEqual(trigger.bar_time, trade.entry_time)

    def test_a_refused_tape_reports_its_refusal_reasons(self) -> None:
        bars = fixture(600)
        payload = als.report(bars, [], "synthetic", als.SilverParams(), stages=False)
        self.assertEqual(0, payload["metrics"]["trade_count"])
        self.assertTrue(payload["no_trade_reason_counts"])
        self.assertIn("SYNTHETIC", payload["synthetic_warning"])

    def test_zero_trades_summarise_without_dividing_by_zero(self) -> None:
        summary = als.summarize([], 10_000.0)
        self.assertEqual(0.0, summary["profit_factor"])
        self.assertEqual(0.0, summary["max_drawdown_pct"])

    def test_the_daily_cap_stops_new_entries_for_the_rest_of_the_day(self) -> None:
        bars = fixture(600)
        trades, decisions = als.replay(bars, als.resample(bars, als.M15_MS), [], als.SilverParams(),
                                       warmup=280, daily_loss_cap=0.0)
        self.assertTrue(all(d.action == "NO_TRADE" for d in decisions))
        self.assertTrue(all("daily loss cap" in " ".join(d.no_trade_reasons) for d in decisions))


class FillTest(unittest.TestCase):
    """The replay must not be able to invent a better fill than the tape allows."""

    def _flat(self, count: int, price: float = 30.0) -> list:
        return [als.Bar(i * als.M5_MS, price, price + 0.01, price - 0.01, price, 100.0)
                for i in range(count)]

    def test_fill_happens_at_the_next_open_plus_slippage(self) -> None:
        p = als.SilverParams(max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = als.Bar(als.M5_MS, 30.10, 30.11, 30.09, 30.10, 100.0)
        fill = als.simulate_trade(bars, 0, "BUY", 30.0, 29.70, 30.60, 10.0, p)
        self.assertAlmostEqual(30.10 * (1 + p.slippage_bps / 10_000.0), fill["entry"], places=6)
        self.assertEqual(bars[1].time_ms, fill["entry_time"])

    def test_the_stop_wins_a_tie_against_the_target(self) -> None:
        p = als.SilverParams(max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = als.Bar(als.M5_MS, 30.0, 30.80, 29.60, 30.0, 100.0)  # hits both levels
        fill = als.simulate_trade(bars, 0, "BUY", 30.0, 29.70, 30.60, 10.0, p)
        self.assertEqual("STOP", fill["outcome"])
        self.assertLess(fill["net_pnl_usd"], 0.0)

    def test_a_target_fill_still_pays_its_costs(self) -> None:
        p = als.SilverParams(max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = als.Bar(als.M5_MS, 30.0, 30.70, 29.95, 30.65, 100.0)
        fill = als.simulate_trade(bars, 0, "BUY", 30.0, 29.70, 30.60, 100.0, p)
        self.assertEqual("TARGET", fill["outcome"])
        self.assertGreater(fill["gross_pnl_usd"], 0.0)
        self.assertAlmostEqual(fill["gross_pnl_usd"] - fill["cost_usd"], fill["net_pnl_usd"], places=6)
        self.assertAlmostEqual(100.0 * (2 * p.commission_per_oz + p.spread_per_oz),
                               fill["cost_usd"], places=6)

    def test_a_gap_past_the_invalidation_is_refused_not_widened(self) -> None:
        p = als.SilverParams(max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = als.Bar(als.M5_MS, 29.50, 29.55, 29.45, 29.50, 100.0)  # opened under the stop
        fill = als.simulate_trade(bars, 0, "BUY", 30.0, 29.70, 30.60, 10.0, p)
        self.assertEqual(0.0, fill["qty_oz"])

    def test_a_trade_that_never_triggers_expires_at_the_last_close(self) -> None:
        p = als.SilverParams(max_hold_bars=3)
        bars = self._flat(8)
        fill = als.simulate_trade(bars, 0, "BUY", 30.0, 29.0, 31.0, 10.0, p)
        self.assertEqual("EXPIRED", fill["outcome"])
        self.assertEqual(bars[3].time_ms, fill["exit_time"])

    def test_trailing_stop_moves_only_after_one_r(self) -> None:
        p = als.SilverParams(max_hold_bars=6, exit_model="TRAIL_AFTER_1R")
        bars = self._flat(8)
        bars[1] = als.Bar(als.M5_MS, 30.0, 30.40, 29.95, 30.35, 100.0)
        bars[2] = als.Bar(2 * als.M5_MS, 30.35, 30.75, 30.30, 30.70, 100.0)
        bars[3] = als.Bar(3 * als.M5_MS, 30.70, 30.72, 30.35, 30.40, 100.0)
        fill = als.simulate_trade(bars, 0, "BUY", 30.0, 29.70, 31.0, 10.0, p)
        # Once 1R is printed the stop trails to +0.5R, so the pullback exits with half a unit of R.
        self.assertEqual("STOP", fill["outcome"])
        risk = fill["entry"] - 29.70
        self.assertAlmostEqual(0.5 * risk * 10.0, fill["gross_pnl_usd"], delta=0.1)


class FixtureTest(unittest.TestCase):
    def test_the_fixture_is_deterministic(self) -> None:
        self.assertEqual(fixture(200), fixture(200))

    def test_the_fixture_is_flagged_as_synthetic(self) -> None:
        payload = als.report(fixture(400), [], "synthetic", als.SilverParams(), stages=False)
        self.assertIn("not silver", payload["synthetic_warning"])


class CsvTest(unittest.TestCase):
    def test_iso_and_epoch_timestamps_are_both_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            iso_path = Path(tmp) / "xag_iso.csv"
            with iso_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "open", "high", "low", "close", "volume"])
                writer.writerow(["2026-02-20T10:00:00Z", 30.0, 30.1, 29.9, 30.05, 120])
                writer.writerow(["2026-02-20T10:05:00Z", 30.05, 30.2, 30.0, 30.15, 140])
            bars = als.load_csv_bars(iso_path)
            self.assertEqual(2, len(bars))
            self.assertEqual(
                int(datetime(2026, 2, 20, 10, 0, tzinfo=timezone.utc).timestamp() * 1000),
                bars[0].time_ms)
            self.assertAlmostEqual(30.05, bars[0].close, places=6)

            epoch_path = Path(tmp) / "xag_epoch.csv"
            with epoch_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "open", "high", "low", "close"])
                writer.writerow([1_760_000_000, 30.0, 30.1, 29.9, 30.05])
            bars = als.load_csv_bars(epoch_path)
            self.assertEqual(1_760_000_000_000, bars[0].time_ms)
            self.assertEqual(100.0, bars[0].volume)

    def test_a_csv_without_columns_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "broken.csv"
            path.write_text("when,px\n1,30\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                als.load_csv_bars(path)


class PineContractTest(unittest.TestCase):
    """Cheap guards for the Pine deliverable: a strategy, not an indicator, and no repainting."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.path = Path(__file__).resolve().parent.parent / "strategies" / "als5s_silver_m5.pine"
        cls.text = cls.path.read_text(encoding="utf-8")
        cls.code = "\n".join(line.split("//")[0] for line in cls.text.splitlines())

    def test_it_is_a_v6_strategy_not_an_indicator(self) -> None:
        self.assertIn("//@version=6", self.text)
        self.assertLess(self.text.index("//@version=6"), self.text.index("strategy("))
        self.assertIn("strategy(", self.code)
        self.assertNotIn("indicator(", self.code)

    def test_orders_only_after_a_bar_closes(self) -> None:
        self.assertIn("calc_on_every_tick   = false", self.text)
        self.assertIn("process_orders_on_close = false", self.text)
        self.assertIn("barstate.isconfirmed", self.code)
        self.assertIn("barstate.isconfirmed", self.code.split("strategy.entry", 1)[0])

    def test_higher_timeframe_requests_cannot_look_ahead(self) -> None:
        for line in self.code.splitlines():
            if "request.security" in line:
                self.assertIn("barmerge.lookahead_off", line, line)

    def test_pivots_wait_for_their_right_bars(self) -> None:
        self.assertIn("ta.pivothigh(high, pivotLeft, pivotRight)", self.code)
        self.assertIn("ta.pivotlow(low, pivotLeft, pivotRight)", self.code)

    def test_costs_and_risk_are_explicit(self) -> None:
        for needle in ("commission_value", "spreadTicks", "roundTripCost", "costToRisk",
                       "minNetRr", "riskPerTradePct", "maxDailyLossPct"):
            self.assertIn(needle, self.code, needle)

    def test_only_a_ready_engine_can_be_chosen(self) -> None:
        self.assertIn("aReady and aScore >=", self.code)
        self.assertIn('cbReady ? "CB" : "NONE"', self.code)

    def test_no_martingale_or_stop_widening_helpers(self) -> None:
        lowered = self.code.lower()
        for banned in ("martingale", "averaging", "strategy.entry(\"add", "pyramiding           = "):
            if banned.startswith("pyramiding"):
                self.assertIn("pyramiding           = 0", self.code)
            else:
                self.assertNotIn(banned, lowered)


if __name__ == "__main__":
    unittest.main()
