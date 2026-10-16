import json
import unittest

from tools import als5_backtest as als5


class Als5BacktestTest(unittest.TestCase):
    def test_confirmed_swings_never_leak_future_bars(self) -> None:
        bars = [
            als5.Bar(
                time_ms=1_760_000_000_000 + i * 300_000,
                open=2400.0,
                high=2450.0 if i == 10 else 2401.0,
                low=2350.0 if i == 10 else 2399.0,
                close=2400.0,
            )
            for i in range(20)
        ]
        # Before 3 right bars close (up_to_index=12), pivot at index 10 must not be visible
        hi_before, lo_before = als5.confirmed_swings(bars, left=3, right=3, up_to_index=12)
        self.assertFalse(any(idx == 10 for idx, _ in hi_before))
        self.assertFalse(any(idx == 10 for idx, _ in lo_before))

        # At index 13 (3 right bars closed), pivot at index 10 is confirmed
        hi_after, lo_after = als5.confirmed_swings(bars, left=3, right=3, up_to_index=13)
        self.assertTrue(any(idx == 10 and price == 2450.0 for idx, price in hi_after))
        self.assertTrue(any(idx == 10 and price == 2350.0 for idx, price in lo_after))

    def test_m5_to_m15_resampling_converts_every_3_m5_bars(self) -> None:
        bars = als5.generate_synthetic_fixture(1500)
        m15 = als5.resample_m5_to_m15(bars)
        self.assertEqual(500, len(m15))
        self.assertEqual(bars[0].open, m15[0].open)
        self.assertEqual(bars[2].close, m15[0].close)
        self.assertEqual(max(b.high for b in bars[:3]), m15[0].high)
        self.assertEqual(min(b.low for b in bars[:3]), m15[0].low)

    def test_position_sizing_enforces_025_pct_risk_and_min_lot(self) -> None:
        params = als5.Als5Params(initial_equity=10_000.0, risk_per_trade_pct=0.25)
        lots, oz = als5.calculate_position_size(2400.0, 2398.0, 10_000.0, params)
        self.assertAlmostEqual(0.10, lots, places=6)
        self.assertAlmostEqual(10.0, oz, places=6)

        too_wide_lots, _ = als5.calculate_position_size(2400.0, 2350.0, 10_000.0, params)
        self.assertEqual(0.0, too_wide_lots)

    def test_stages_a_to_e_harness_executes_reproducibly(self) -> None:
        bars = als5.generate_synthetic_fixture(1500)
        report = als5.run_stages_a_to_e(bars)
        self.assertIn("stage_a_individual", report)
        self.assertIn("stage_b_combined", report)
        self.assertIn("stage_c_ablation", report)
        self.assertIn("stage_d_chronological_split", report)
        stage_e = report["stage_e_robustness"]
        self.assertEqual(1.5, stage_e["cost_stress"]["spread_multiplier"])
        self.assertEqual(2.0, stage_e["cost_stress"]["slippage_multiplier"])
        self.assertIn("london_only", stage_e["session_sensitivity"])
        self.assertIn("new_york_only", stage_e["session_sensitivity"])
        self.assertIn("threshold_7", stage_e["score_threshold_sensitivity"])
        self.assertIn("threshold_9", stage_e["score_threshold_sensitivity"])
        self.assertIn("looser", stage_e["minimum_sweep_atr_sensitivity"])
        self.assertIn("tighter", stage_e["minimum_sweep_atr_sensitivity"])
        cov = report["opportunity_coverage"]
        self.assertGreater(cov["candidates_detected"], 0)
        self.assertGreater(cov["wait_states_logged"], 0)
        self.assertGreater(cov["confirmed_entries"], 0)
        self.assertNotIn("_candidate_keys", cov)
        self.assertNotIn("_wait_keys", cov)
        # The CLI report is JSON serializable; private audit sets must never leak into it.
        json.dumps(report)


if __name__ == "__main__":
    unittest.main()
