#!/usr/bin/env python3
"""Static contract checks for the native-15m TradingView source.

TradingView's compiler is not available in this workspace.  These checks are
not a substitute for a Pine Editor compile; they guard the scope that is easy
to regress locally: one native timeframe, no MTF access, Main-Core gates, and
one frozen SL/TP lifecycle.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "strategies" / "tradingview" / "BASE_ICHIMOKU_Main_Core_Native_15m.pine"


def without_line_comments(source: str) -> str:
    return "\n".join(line.split("//", 1)[0] for line in source.splitlines())


class MainCoreNative15StaticContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SOURCE.read_text(encoding="utf-8")
        cls.code = without_line_comments(cls.source)

    def test_is_a_native_15m_strategy(self) -> None:
        self.assertIn('strategy("BASE Main-Core native 15m | Trend Origin"', self.code)
        self.assertIn("timeframe.isminutes and timeframe.multiplier == 15", self.code)
        self.assertIn("process_orders_on_close=false", self.code)

    def test_has_no_multitimeframe_or_resampling_access(self) -> None:
        forbidden = ("request.security", "request.security_lower_tf", "request.economic", "ticker.modify", "timeframe.change")
        for token in forbidden:
            self.assertNotIn(token, self.code, token)
        self.assertNotIn('input.timeframe(', self.code)
        self.assertNotIn('timeframe.period', self.code)

    def test_contains_all_native_main_core_condition_slots(self) -> None:
        for number in range(1, 18):
            self.assertRegex(self.code, rf"\bc{number}\s*=")
        for token in (
            "chikouBullishOpen15",
            "recentKatanaRelease15",
            "cClampWarning15",
            "nativeSPointLong15",
            "distanceToSniperAtr15",
            "executionCost15",
        ):
            self.assertIn(token, self.code)

    def test_xau_reference_cost_does_not_create_a_permanent_execution_veto(self) -> None:
        self.assertIn("commission_value=0.00086, slippage=6", self.code)
        self.assertIn('input.float(0.0000466, "Round-trip cost fraction (XAU reference)"', self.code)
        self.assertIn("float stopFloor15 = math.max(close * minStopPct, atr15 * 0.80)", self.code)
        self.assertIn("float costToRiskRatio15", self.code)
        self.assertNotIn("input.float(0.0021, \"Round-trip cost fraction\"", self.code)

    def test_uses_main_trigger_families_not_the_old_twenty_bar_breakout(self) -> None:
        for trigger in ("triggerGenesis", "triggerPullback", "triggerBreakout", "triggerContinuation"):
            self.assertIn(trigger, self.code)
        self.assertNotIn("priorHigh15", self.code)
        self.assertNotIn("priorLow15", self.code)

    def test_requires_progressive_impulse_kijun_stop_and_two_to_three_risk_reward(self) -> None:
        self.assertIn("bool progressiveImpulse15", self.code)
        self.assertIn("var int bullChikouReleaseBar15", self.code)
        self.assertIn("bool chikouReleaseArmed15", self.code)
        self.assertIn("bool futureKumoTrendOrigin15", self.code)
        self.assertIn("bool kijunHistoricalLevelValid15", self.code)
        self.assertIn("bool kijunStopGeometry15", self.code)
        self.assertIn("bool originBaseOrSweepPassed15", self.code)
        self.assertIn("bool rsiMidlinePassed15", self.code)
        self.assertIn("and originBaseOrSweepPassed15 and rsiMidlinePassed15 and chikouReleaseArmed15 and futureKumoTrendOrigin15 and progressiveImpulse15 and kijunStopGeometry15", self.code)
        self.assertIn("string phaseAudit15", self.code)
        self.assertIn("string triggerAudit15", self.code)
        self.assertIn("string originAudit15", self.code)
        self.assertIn("string momentumAudit15", self.code)
        self.assertIn("string gateState15", self.code)
        self.assertIn('"WAIT: BASE/SWEEP"', self.code)
        self.assertIn('"WAIT: RSI MIDLINE"', self.code)
        self.assertIn('"WAIT: CHIKOU BLOCKED"', self.code)
        self.assertIn('"MATURE LEG: RESET NEEDED"', self.code)
        self.assertIn('"WAIT: TRIGGER"', self.code)
        self.assertIn("var table dashboard = table.new(position.top_right, 2, 17", self.code)
        self.assertIn('input.float(2.25, "Target R:R (set before backtest quantity)", minval=2.00, maxval=3.00', self.code)
        self.assertIn("bool c12 = targetRr >= 2.00 and targetRr <= 3.00", self.code)
        for rejected_anchor in ("anchorRibbon15", "anchorFractal15", "anchorKumo15", "anchorWave15"):
            self.assertNotIn(rejected_anchor, self.code)

    def test_preserves_single_lifecycle_and_frozen_bracket(self) -> None:
        for token in (
            'strategy.entry("BASE Main Long"',
            'strategy.entry("BASE Main Short"',
            'strategy.exit("BASE Main Long exit"',
            'strategy.exit("BASE Main Short exit"',
            "pendingStopPrice := stopPrice15",
            "pendingTargetPrice := targetPrice15",
            "longTrendEntryArmed := false",
            "shortTrendEntryArmed := false",
        ):
            self.assertIn(token, self.code)
        self.assertNotRegex(self.code, r"bars_held|bar_index\s*-\s*lockedSignalBar\s*>?=\s*20")

    def test_only_sl_tp_lines_and_no_entry_markers(self) -> None:
        self.assertEqual(self.code.count("line.new("), 4)
        self.assertNotIn("plotshape(", self.code)
        self.assertNotIn("strategy.close_all", self.code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
