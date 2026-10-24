import csv
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path

import eur_ares_backtest as eur


def fixture(count: int = 900) -> list:
    return eur.generate_synthetic_fixture(count)


def ts(iso: str) -> int:
    return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)


class PipMathTest(unittest.TestCase):
    def test_round_trip_cost_is_spread_plus_two_commissions_plus_two_slippages(self) -> None:
        p = eur.EurParams()
        # 0.1 pip spread + 2 × 0.35 pip commission + 2 × 0.2 pip slippage.
        self.assertAlmostEqual(1.2, eur.round_trip_cost_pips(1.0850, 0.1, p), places=9)
        self.assertAlmostEqual(1.6, eur.round_trip_cost_pips(1.0850, 0.5, p), places=9)

    def test_a_pip_is_not_a_pipette(self) -> None:
        self.assertAlmostEqual(1.0, eur.pips(0.0001), places=12)
        self.assertAlmostEqual(0.1, eur.pips(0.00001), places=12)
        self.assertEqual(0.0001, eur.PIP)
        self.assertEqual(0.00001, eur.PIPETTE)

    def test_net_reward_risk_is_measured_after_costs(self) -> None:
        self.assertAlmostEqual((20.0 - 1.2) / (10.0 + 1.2),
                               eur.net_reward_risk(1.08500, 1.08400, 1.08700, 1.2), places=9)
        # A 2-pip scalp cannot pay for a 1.2-pip round trip.
        self.assertLess(eur.net_reward_risk(1.08500, 1.08480, 1.08540, 1.2), 1.5)
        # A target inside the cost is worse than no trade.
        self.assertLess(eur.net_reward_risk(1.08500, 1.07000, 1.08501, 1.2), 0.0)


class SizingTest(unittest.TestCase):
    def test_size_follows_the_stop_in_pips(self) -> None:
        p = eur.EurParams()
        lots, units, risk = eur.position_size(1.0850, 10.0, 10_000.0, 0.25, 1.2, p)
        self.assertAlmostEqual(0.22, lots, places=9)
        self.assertAlmostEqual(22_000.0, units, places=6)
        self.assertAlmostEqual(24.64, risk, places=6)

    def test_a_wider_stop_buys_fewer_lots(self) -> None:
        p = eur.EurParams()
        tight, _, _ = eur.position_size(1.0850, 8.0, 10_000.0, 0.25, 1.2, p)
        wide, _, _ = eur.position_size(1.0850, 30.0, 10_000.0, 0.25, 1.2, p)
        self.assertLess(wide, tight)

    def test_below_the_venue_minimum_is_a_refusal_not_a_bigger_bet(self) -> None:
        p = eur.EurParams()
        self.assertEqual((0.0, 0.0, 0.0), eur.position_size(1.0850, 10.0, 50.0, 0.25, 1.2, p))
        self.assertEqual((0.0, 0.0, 0.0), eur.position_size(1.0850, 0.0, 10_000.0, 0.25, 1.2, p))

    def test_leverage_caps_the_notional(self) -> None:
        p = eur.EurParams(max_leverage=30)
        lots, units, _ = eur.position_size(1.0850, 0.5, 10_000.0, 0.25, 1.2, p)
        self.assertLessEqual(lots, 10_000.0 * 30 / (1.0850 * 100_000.0) + 1e-9)


class SessionTest(unittest.TestCase):
    def test_the_same_utc_hour_is_a_different_session_in_winter_and_summer(self) -> None:
        winter = eur.session_at(ts("2026-01-15T12:30:00Z"))
        summer = eur.session_at(ts("2026-07-15T12:30:00Z"))
        self.assertEqual("LONDON", winter["session"])
        self.assertEqual("OVERLAP", summer["session"])
        self.assertEqual("12:30", winter["london"])
        self.assertEqual("13:30", summer["london"])
        self.assertEqual("07:30", winter["newYork"])
        self.assertEqual("08:30", summer["newYork"])

    def test_tokyo_and_new_york_are_classified_without_dst_guesses(self) -> None:
        # 02:00 UTC = 11:00 Tokyo (Asia), 21:00 previous day in New York.
        asia = eur.session_at(ts("2026-07-15T02:00:00Z"))
        self.assertEqual("ASIA", asia["session"])
        # 15:00 UTC = 16:00 London, 11:00 New York → overlap.
        self.assertEqual("OVERLAP", eur.session_at(ts("2026-07-15T15:00:00Z"))["session"])
        # 22:00 UTC = 23:00 London, 18:00 New York → off-session.
        self.assertEqual("OFF", eur.session_at(ts("2026-07-15T22:00:00Z"))["session"])

    def test_the_rollover_window_is_its_own_state(self) -> None:
        inside = eur.session_at(ts("2026-07-15T21:05:00Z"))  # New York 17:05
        after = eur.session_at(ts("2026-07-15T21:20:00Z"))  # New York 17:20
        self.assertEqual("ROLLOVER", inside["session"])
        self.assertTrue(inside["rollover"])
        self.assertFalse(after["rollover"])
        self.assertFalse(eur.is_tradable_session(str(inside["session"])))
        self.assertTrue(eur.is_tradable_session("OVERLAP"))


class StructureTest(unittest.TestCase):
    def test_pivots_stay_invisible_until_their_right_bars_close(self) -> None:
        bars = [eur.Bar(1_760_000_000_000 + i * eur.M5_MS, 1.0850, 1.0852, 1.0848, 1.0850)
                for i in range(30)]
        bars[20] = eur.Bar(bars[20].time_ms, 1.0850, 1.0920, 1.0848, 1.0850)
        highs, _ = eur.confirmed_swings(bars, 3, 3, up_to_index=22)
        self.assertFalse(any(index == 20 for index, _ in highs))
        highs, _ = eur.confirmed_swings(bars, 3, 3, up_to_index=23)
        self.assertTrue(any(index == 20 and price == 1.0920 for index, price in highs))

    def test_round_number_grid_is_fifty_pips(self) -> None:
        # A 50-pip grid: ... 1.0800, 1.0850, 1.0900 ...
        above, below = eur.round_numbers(1.08523, 50.0)
        self.assertAlmostEqual(1.0850, below, places=5)
        self.assertAlmostEqual(1.0900, above, places=5)

    def test_the_previous_day_range_ignores_the_current_day(self) -> None:
        start = ts("2026-07-13T00:00:00Z")
        bars = [eur.Bar(start + i * eur.M5_MS, 1.0850, 1.0850, 1.0850, 1.0850) for i in range(288)]
        # A violent current-day bar must not leak into the previous day's range.
        bars.append(eur.Bar(start + 288 * eur.M5_MS, 1.0850, 1.2000, 0.9000, 1.0850))
        high, low = eur.previous_day_range(bars)
        self.assertAlmostEqual(1.0850, high, places=5)
        self.assertAlmostEqual(1.0850, low, places=5)

    def test_regime_hysteresis_protects_immediately(self) -> None:
        p = eur.EurParams()
        self.assertEqual("EVENT_UNSTABLE",
                         eur.classify_regime("EVENT_UNSTABLE", "RANGE", p)[0])
        self.assertEqual("EVENT_UNSTABLE",
                         eur.classify_regime("RANGE", "EVENT_UNSTABLE", p)[0])
        self.assertEqual("RANGE", eur.classify_regime("RANGE", None, p)[0])
        self.assertEqual("BULL_TREND",
                         eur.classify_regime("BULL_TREND", "RANGE", replace(p, regime_confirm_bars=1))[0])


class EngineTest(unittest.TestCase):
    def test_news_and_macro_are_unavailable_never_directions(self) -> None:
        news = eur.engine_e(1.2)
        self.assertEqual("UNAVAILABLE", news.state)
        self.assertEqual("NO_TRADE", news.direction)
        self.assertFalse(news.data_available)
        macro = eur.engine_x()
        self.assertEqual("UNAVAILABLE", macro.state)
        self.assertFalse(macro.data_available)

    def test_every_engine_prices_the_round_trip(self) -> None:
        bars = fixture(700)
        h1 = eur.to_h1(bars)
        decision = eur.decide(bars, h1, eur.EurParams())
        self.assertTrue(decision.cost_pips > 0.0)
        for candidate in decision.engines:
            if candidate.engine not in ("X_MACRO",):
                self.assertGreater(candidate.cost_pips, 0.0, candidate.engine)

    def test_a_decision_never_acts_without_a_ready_engine(self) -> None:
        bars = fixture(700)
        decision = eur.decide(bars, eur.to_h1(bars), eur.EurParams())
        if decision.action != "NO_TRADE":
            chosen = next(c for c in decision.engines if c.engine == decision.chosen_engine)
            self.assertEqual("READY", chosen.state)
            self.assertEqual(decision.action, chosen.direction)
        else:
            self.assertTrue(decision.no_trade_reasons)

    def test_the_score_is_a_sum_of_its_components(self) -> None:
        bars = fixture(600)
        decision = eur.decide(bars, eur.to_h1(bars), eur.EurParams())
        for candidate in decision.engines:
            parts = (candidate.regime_score + candidate.structure_score + candidate.entry_score
                     + candidate.confirmation_score + candidate.volatility_score
                     + candidate.session_score + candidate.rr_score + candidate.cost_score)
            self.assertEqual(parts, candidate.score)
            self.assertTrue(0 <= candidate.score <= 100)

    def test_a_ready_engine_still_needs_net_rr_and_a_cost_cap(self) -> None:
        bars = fixture(900)
        decision = eur.decide(bars, eur.to_h1(bars), eur.EurParams())
        ready = [c for c in decision.engines if c.state == "READY"]
        for candidate in ready:
            if candidate.net_rr is not None:
                self.assertGreaterEqual(candidate.net_rr, eur.EurParams().min_reward_risk - 1e-9)
            self.assertLessEqual(candidate.cost_to_risk,
                                 eur.EurParams().max_cost_to_risk_fraction + 1e-9)


class FillTest(unittest.TestCase):
    def _flat(self, count: int, price: float = 1.0850) -> list:
        return [eur.Bar(i * eur.M5_MS, price, price + 0.0002, price - 0.0002, price)
                for i in range(count)]

    def test_fill_is_the_next_open_plus_slippage(self) -> None:
        p = replace(eur.EurParams(), max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = eur.Bar(eur.M5_MS, 1.08510, 1.08512, 1.08508, 1.08510)
        fill = eur.simulate_trade(bars, 0, "BUY", 1.08500, 1.08300, 1.08700, 1.0, p)
        self.assertAlmostEqual(1.08510 + p.slippage_pips * eur.PIP, fill["entry"], places=9)
        self.assertEqual(bars[1].time_ms, fill["entry_time"])

    def test_the_stop_wins_a_tie_against_the_target(self) -> None:
        p = replace(eur.EurParams(), max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = eur.Bar(eur.M5_MS, 1.0850, 1.0950, 1.0750, 1.0850)  # hits both levels
        fill = eur.simulate_trade(bars, 0, "BUY", 1.08500, 1.08300, 1.08700, 1.0, p)
        self.assertEqual("STOP", fill["outcome"])
        self.assertLess(fill["net_pnl_usd"], 0.0)

    def test_a_gap_past_the_invalidation_is_refused_not_widened(self) -> None:
        p = replace(eur.EurParams(), max_hold_bars=5)
        bars = self._flat(6)
        bars[1] = eur.Bar(eur.M5_MS, 1.0800, 1.0802, 1.0798, 1.0800)  # opens below the stop
        fill = eur.simulate_trade(bars, 0, "BUY", 1.08500, 1.08300, 1.08700, 1.0, p)
        self.assertEqual(0.0, fill["lots"])

    def test_costs_are_charged_in_currency_on_both_sides(self) -> None:
        p = replace(eur.EurParams(), max_hold_bars=4)
        bars = self._flat(6)
        bars[1] = eur.Bar(eur.M5_MS, 1.0850, 1.0872, 1.0849, 1.0871)
        fill = eur.simulate_trade(bars, 0, "BUY", 1.08500, 1.08400, 1.08700, 1.0, p)
        self.assertEqual("TARGET", fill["outcome"])
        expected = 100_000.0 * (2 * p.commission_per_unit + 0.1 * eur.PIP)
        self.assertAlmostEqual(expected, fill["cost_usd"], places=6)
        self.assertAlmostEqual(fill["gross_pnl_usd"] - fill["cost_usd"], fill["net_pnl_usd"], places=6)


class ReplayTest(unittest.TestCase):
    def test_a_refused_tape_reports_its_reasons(self) -> None:
        payload = eur.report(fixture(600), eur.to_h1(fixture(600)), "synthetic",
                             eur.EurParams(), stages=False)
        self.assertIn("SYNTHETIC", payload["synthetic_warning"])
        self.assertTrue(payload["no_trade_reason_counts"])
        self.assertEqual("not estimated", payload["cost_model"]["swap"].split(":")[0])

    def test_zero_trades_summarise_without_dividing_by_zero(self) -> None:
        summary = eur.summarize([], 10_000.0)
        self.assertIsNone(summary["profit_factor"])
        self.assertEqual(0.0, summary["max_drawdown_pct"])

    def test_the_daily_cap_blocks_new_entries(self) -> None:
        bars = fixture(600)
        trades, decisions = eur.replay(bars, eur.to_h1(bars), eur.EurParams(), warmup=280,
                                       daily_loss_cap=0.0)
        self.assertEqual([], trades)
        self.assertTrue(all("daily loss cap" in " ".join(d.no_trade_reasons) for d in decisions))

    def test_the_rollover_window_blocks_entries_in_the_replay(self) -> None:
        start = ts("2026-07-15T00:00:00Z")
        bars = eur.generate_synthetic_fixture(600, start=start)
        _, decisions = eur.replay(bars, eur.to_h1(bars), eur.EurParams(), warmup=280)
        rollover = [d for d in decisions if d.rollover]
        self.assertTrue(rollover, "the fixture must contain at least one rollover window")
        for decision in rollover:
            self.assertEqual("NO_TRADE", decision.action)
            self.assertTrue(any("rollover" in r for r in decision.no_trade_reasons))

    def test_exit_models_are_independent_and_deterministic(self) -> None:
        bars = fixture(700)
        h1 = eur.to_h1(bars)
        first, _ = eur.replay(bars, h1, eur.EurParams())
        second, _ = eur.replay(bars, h1, eur.EurParams())
        self.assertEqual(len(first), len(second))
        for model in ("FIXED_RR", "TRAIL_AFTER_1R", "PARTIAL_HALF"):
            trades, _ = eur.replay(bars, h1, replace(eur.EurParams(), exit_model=model))
            self.assertEqual(len(trades), len(first), model)


class FixtureAndCsvTest(unittest.TestCase):
    def test_the_fixture_is_deterministic(self) -> None:
        self.assertEqual(fixture(200), fixture(200))

    def test_iso_and_epoch_timestamps_are_both_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            iso_path = Path(tmp) / "eur_iso.csv"
            with iso_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "open", "high", "low", "close", "volume"])
                writer.writerow(["2026-07-15T10:00:00Z", 1.0850, 1.0852, 1.0848, 1.0851, 120])
                writer.writerow(["2026-07-15T10:05:00Z", 1.0851, 1.0854, 1.0850, 1.0853, 140])
            bars = eur.load_csv_bars(iso_path)
            self.assertEqual(2, len(bars))
            self.assertAlmostEqual(1.0851, bars[0].close, places=6)
            self.assertEqual(ts("2026-07-15T10:00:00Z"), bars[0].time_ms)

            epoch_path = Path(tmp) / "eur_epoch.csv"
            with epoch_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "open", "high", "low", "close"])
                writer.writerow([1_760_000_000, 1.0850, 1.0852, 1.0848, 1.0851])
            bars = eur.load_csv_bars(epoch_path)
            self.assertEqual(1_760_000_000_000, bars[0].time_ms)
            self.assertEqual(1000.0, bars[0].volume)

    def test_a_csv_without_columns_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "broken.csv"
            path.write_text("when,px\n1,1.08\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                eur.load_csv_bars(path)


if __name__ == "__main__":
    unittest.main()
