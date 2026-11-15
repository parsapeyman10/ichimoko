#!/usr/bin/env python3
"""Deterministic checks for the pure-Python Windows core."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from aurum_core import (
    Candle,
    JournalStore,
    calculate_position,
    export_candles,
    export_journal,
    ichimoku,
    monthly_calendar,
    read_csv,
    run_ichimoku_baseline,
    sanitize_trade_plan,
)


class AurumPythonCoreTests(unittest.TestCase):
    def test_cross_platform_build_wrappers_pass_the_application_source(self) -> None:
        root = Path(__file__).resolve().parent
        batch = (root / "build_windows.bat").read_text(encoding="utf-8")
        builder = (root / "build_desktop.py").read_text(encoding="utf-8")
        shell = (root / "build_desktop.sh").read_text(encoding="utf-8")
        self.assertIn("build_desktop.py", batch)
        self.assertIn("--recreate-venv", batch)
        self.assertIn("import sys, tkinter", batch)
        self.assertIn('"--paths", "."', builder)
        self.assertIn("APP_SOURCE.name", builder)
        self.assertIn("platform.system", builder)
        self.assertIn("prepare_tkinter_environment", builder)
        self.assertIn("assert_tkinter(Path(sys.executable)", builder)
        self.assertIn("shutil.rmtree(VENV_DIR)", builder)
        self.assertIn("build_desktop.py", shell)
        self.assertIn('"$@"', shell)
        self.assertNotIn('--paths "%SCRIPT_DIR%"', batch)

    def bars(self, count: int = 120) -> list[Candle]:
        output: list[Candle] = []
        close = 100.0
        for index in range(count):
            open_ = close
            close += 0.34 if index % 9 < 5 else -0.21
            output.append(Candle((index + 1) * 300_000, open_, max(open_, close) + 0.25, min(open_, close) - 0.25, close, 10.0))
        return output

    def test_csv_audit_handles_seconds_duplicates_and_bad_ohlc(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "eurusd-m5-export.csv"
            path.write_text("timestamp,open,high,low,close,volume\n300,1,1.2,0.9,1.1,3\n300,1,1.2,0.9,1.1,3\n600,1.1,1.0,0.9,1.0,3\n900,1.0,1.2,0.95,1.1,0\n", encoding="utf-8")
            candles, audit = read_csv(path)
        self.assertEqual(len(candles), 1)
        self.assertEqual(audit.duplicate_rows, 1)
        self.assertEqual(audit.skipped_rows, 1)
        self.assertEqual(audit.flat_rows_dropped, 1)
        self.assertEqual(audit.suggested_symbol, "EUR/USD")

    def test_ichimoku_warmup_and_backtest_are_deterministic(self) -> None:
        bars = self.bars(240)
        points = ichimoku(bars)
        self.assertFalse(points[70].valid)
        self.assertTrue(points[71].valid)
        first = run_ichimoku_baseline(bars)
        second = run_ichimoku_baseline(bars)
        self.assertEqual(len(first.trades), len(second.trades))
        self.assertAlmostEqual(first.stats.final_equity, second.stats.final_equity, places=9)
        self.assertGreater(first.stats.final_equity, 0)

    def test_risk_calculator_and_guarded_plan(self) -> None:
        risk = calculate_position("XAU/USD", 10_000, 0.5, 2_000, 1_990, 2_020)
        self.assertIsNotNone(risk)
        assert risk is not None
        self.assertAlmostEqual(risk.risk_budget_usd, 50)
        self.assertAlmostEqual(risk.position_units, 5)
        self.assertTrue(risk.plan_valid)
        plan = sanitize_trade_plan("BTC/USDT", "BUY", 100_000, 99_000, 102_000, 2.0, 500, 15)
        self.assertTrue(plan.usable)
        self.assertLess(plan.stop_loss, plan.entry)
        self.assertGreater(plan.take_profit, plan.entry)
        self.assertGreaterEqual(plan.risk_reward, 1.5)
        self.assertLessEqual(plan.risk_reward, 3.0)

    def test_journal_calendar_and_exports(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)
            journal = JournalStore(folder / "journal.sqlite")
            journal.add_trade("BTC/USD", "BUY", 100, 99, 102, 102, 5, 1_704_067_200_000)
            rows = journal.recent()
            report = monthly_calendar(journal, 2024, 1)
            export_journal(rows, folder / "journal.csv")
            export_journal(rows, folder / "journal.json")
            export_journal(rows, folder / "journal.pdf")
            export_candles(self.bars(2), folder / "candles.json")
            journal.close()
            self.assertEqual(len(rows), 1)
            self.assertEqual(report["wins"], 1)
            for filename in ("journal.csv", "journal.json", "journal.pdf", "candles.json"):
                self.assertTrue((folder / filename).is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
