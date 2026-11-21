#!/usr/bin/env python3
"""Fast dependency-free regression checks for base_ichimoku_simulator.py."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import base_ichimoku_simulator as sim  # noqa: E402


class BaseIchimokuSimulatorTest(unittest.TestCase):
    def bar(self, index: int, price: float = 2000.0) -> sim.Candle:
        return sim.Candle(index * sim.M15_MS, price, price + 1.0, price - 1.0, price + 0.25, 10.0)

    def test_resample_requires_all_source_bars_in_bucket(self) -> None:
        bars = [self.bar(index) for index in range(8) if index != 2]
        hourly, partial = sim.resample_complete(bars, sim.H1_MS)
        self.assertEqual(partial, 1)
        self.assertEqual(len(hourly), 1)
        self.assertEqual(hourly[0].time, sim.H1_MS)

    def test_closed_window_excludes_still_forming_higher_timeframe_bar(self) -> None:
        bars = [self.bar(index) for index in range(20)]
        hourly, _ = sim.resample_complete(bars, sim.H1_MS)
        starts = [bar.time for bar in hourly]
        # At 00:45, the bucket beginning 00:00 has not yet closed.
        self.assertEqual(sim._closed_window(hourly, starts, 3 * sim.M15_MS, sim.H1_MS), [])
        # At 01:00 it becomes visible; the next H1 bar is still hidden.
        window = sim._closed_window(hourly, starts, 4 * sim.M15_MS, sim.H1_MS)
        self.assertEqual([bar.time for bar in window], [0])

    def test_next_open_rebase_preserves_plan_geometry(self) -> None:
        config = sim.Config(symbol="XAUUSD")
        pending = sim.PendingOrder(
            signal_time=0,
            action="BUY",
            anchor_entry=2000.0,
            anchor_stop=1995.0,
            anchor_target=2010.0,
            atr=5.0,
            strategy_atr=5.0,
            desired_rr=2.0,
            confidence=80.0,
            trigger="PULLBACK_BOUNCE",
        )
        plan = sim.rebase_pending(pending, 2001.0, config)
        self.assertTrue(plan.usable)
        self.assertLess(plan.stop, 2001.0)
        self.assertGreater(plan.target, 2001.0)
        self.assertGreaterEqual(plan.risk_reward, 1.5)
        self.assertLessEqual(plan.risk_reward, 3.0)

    def test_csv_parser_accepts_seconds_and_discards_duplicate_and_bad_ohlc(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "export.csv"
            lines = ["timestamp,open,high,low,close,volume"]
            start_seconds = 1_704_067_200
            for index in range(80):
                timestamp = start_seconds + index * 900
                lines.append(f"{timestamp},2000,2002,1999,2001,10")
            lines.append(f"{start_seconds},2000,2002,1999,2001,10")  # duplicate
            lines.append(f"{start_seconds + 81 * 900},2000,1999,2001,2000,10")  # invalid high/low
            path.write_text("\n".join(lines), encoding="utf-8")
            bars, audit = sim.read_15m_csv(path)
            self.assertEqual(len(bars), 80)
            self.assertEqual(audit.skipped_duplicate, 1)
            self.assertEqual(audit.skipped_invalid, 1)
            self.assertEqual(bars[0].time, start_seconds * 1000)

    def test_simultaneous_stop_and_target_touch_is_stop_first(self) -> None:
        trade = sim.OpenTrade(
            signal_time=0,
            entry_time=sim.M15_MS,
            action="BUY",
            entry=2000.0,
            initial_stop=1995.0,
            stop=1995.0,
            target=2010.0,
            units=1.0,
            confidence=80.0,
            planned_rr=2.0,
            trigger="PULLBACK_BOUNCE",
        )
        candle = sim.Candle(2 * sim.M15_MS, 2000.0, 2012.0, 1993.0, 2003.0, 10.0)
        self.assertEqual(sim.intrabar_exit(trade, candle), ("STOP_LOSS", 1995.0))

    def test_trade_close_uses_modeled_round_trip_cost(self) -> None:
        config = sim.Config(symbol="XAUUSD")
        open_trade = sim.OpenTrade(
            signal_time=0,
            entry_time=sim.M15_MS,
            action="BUY",
            entry=2000.0,
            initial_stop=1995.0,
            stop=1995.0,
            target=2010.0,
            units=1.0,
            confidence=80.0,
            planned_rr=2.0,
            trigger="PULLBACK_BOUNCE",
        )
        trade = sim._close_trade(open_trade, 2010.0, 2 * sim.M15_MS, "TAKE_PROFIT", 1000.0, config, 1)
        self.assertEqual(trade.gross_pnl_usd, 10.0)
        self.assertGreater(trade.fee_usd, 0.0)
        self.assertLess(trade.net_pnl_usd, trade.gross_pnl_usd)


if __name__ == "__main__":
    unittest.main(verbosity=2)
