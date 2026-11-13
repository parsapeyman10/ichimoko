#!/usr/bin/env python3
"""Dependency-free regression tests for the native-15m Windows backtester."""

from __future__ import annotations

import csv
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import native15_engine as engine


BASE_TIME = 1_704_067_200_000  # 2024-01-01T00:00:00Z, on a 15m boundary


def candles(count: int = 90, *, flat: bool = False) -> list[engine.Candle]:
    result: list[engine.Candle] = []
    for index in range(count):
        close = 100.0 if flat else 100.0 + index * 0.1
        opening = close if flat else close - 0.04
        result.append(
            engine.Candle(
                time=BASE_TIME + index * engine.M15_MS,
                open=opening,
                high=close + 0.15,
                low=opening - 0.15,
                close=close,
                volume=100.0 + index,
            )
        )
    return result


def signal_at(index: int, bars: list[engine.Candle], *, side: str = "LONG", stop: float = 99.0, target: float = 102.0) -> engine.Signal:
    return engine.Signal(
        time=bars[index].time + engine.M15_MS,
        side=side,
        trigger="TEST",
        stop=stop,
        target=target,
        confidence=90.0,
        passed_conditions=17,
        gate_percent=90.0,
    )


class Native15EngineTests(unittest.TestCase):
    def test_csv_accepts_seconds_and_audits_duplicate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.csv"
            with source.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["timestamp", "open", "high", "low", "close", "volume"])
                for candle in candles(80):
                    writer.writerow([candle.time // 1000, candle.open, candle.high, candle.low, candle.close, candle.volume])
                duplicate = candles(80)[10]
                writer.writerow([duplicate.time // 1000, duplicate.open, duplicate.high, duplicate.low, duplicate.close, duplicate.volume])
            parsed, audit = engine.read_15m_csv(source)
        self.assertEqual(len(parsed), 80)
        self.assertEqual(audit.accepted_rows, 80)
        self.assertEqual(audit.skipped_duplicate, 1)
        self.assertEqual(audit.gaps, 0)

    def test_csv_rejects_15m_gap_unless_explicitly_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "gapped.csv"
            with source.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["time", "o", "h", "l", "c"])
                for index, candle in enumerate(candles(81)):
                    if index != 40:
                        writer.writerow([candle.time, candle.open, candle.high, candle.low, candle.close])
            with self.assertRaises(engine.CsvValidationError):
                engine.read_15m_csv(source)
            parsed, audit = engine.read_15m_csv(source, allow_gaps=True)
        self.assertEqual(len(parsed), 80)
        self.assertEqual(audit.gaps, 1)

    def test_indicator_warmup_and_native_atr_floor(self) -> None:
        bars = [engine.Candle(BASE_TIME + index * engine.M15_MS, 100.0, 100.0, 100.0, 100.0, 100.0) for index in range(80)]
        indicators = engine.build_indicators(bars)
        self.assertIsNone(engine._cloud_state(bars, indicators, 78))
        self.assertIsNotNone(engine._cloud_state(bars, indicators, 79))
        self.assertEqual(indicators.atr[79], 0.05)  # max(ATR, close * 0.0005)

    def test_next_open_fill_and_stop_first_when_both_levels_touch(self) -> None:
        bars = candles(84)
        bars[80] = engine.Candle(bars[80].time, 100.0, 103.0, 97.0, 101.0, 150.0)

        def fake_signal(_bars: list[engine.Candle], _indicators: engine.Indicators, index: int, _settings: engine.Settings) -> engine.Signal | None:
            return signal_at(index, bars) if index == 79 else None

        with patch.object(engine, "evaluate_signal", side_effect=fake_signal):
            result = engine.run_backtest(bars, engine.Settings(round_trip_cost_fraction=0.0, smart_exit_enabled=False))
        self.assertEqual(len(result.trades), 1)
        trade = result.trades[0]
        self.assertEqual(trade.entry_time, bars[80].time)
        self.assertEqual(trade.entry, 100.0)
        self.assertEqual(trade.exit_reason, "STOP_LOSS")
        self.assertEqual(trade.exit, 99.0)
        self.assertEqual(trade.exit_time, bars[80].time + engine.M15_MS)

    def test_smart_exit_is_submitted_on_close_and_fills_next_open(self) -> None:
        bars = candles(84)
        bars[80] = engine.Candle(bars[80].time, 100.0, 101.0, 99.0, 100.0, 150.0)
        bars[81] = engine.Candle(bars[81].time, 101.0, 101.2, 100.5, 100.8, 151.0)

        def fake_signal(_bars: list[engine.Candle], _indicators: engine.Indicators, index: int, _settings: engine.Settings) -> engine.Signal | None:
            return signal_at(index, bars, stop=95.0, target=110.0) if index == 79 else None

        def fake_smart_exit(_bars: list[engine.Candle], _indicators: engine.Indicators, index: int, _trade: engine.OpenTrade, _settings: engine.Settings) -> bool:
            return index == 80

        with patch.object(engine, "evaluate_signal", side_effect=fake_signal), patch.object(engine, "smart_exit", side_effect=fake_smart_exit):
            result = engine.run_backtest(bars, engine.Settings(round_trip_cost_fraction=0.0, smart_exit_enabled=True))
        self.assertEqual(len(result.trades), 1)
        trade = result.trades[0]
        self.assertEqual(trade.exit_reason, "SMART_TREND_EXIT")
        self.assertEqual(trade.exit_time, bars[81].time)
        self.assertEqual(trade.exit, bars[81].open)

    def test_next_open_gap_is_filled_not_silently_cancelled(self) -> None:
        bars = candles(84)
        bars[80] = engine.Candle(bars[80].time, 98.0, 101.0, 97.5, 100.0, 150.0)

        def fake_signal(_bars: list[engine.Candle], _indicators: engine.Indicators, index: int, _settings: engine.Settings) -> engine.Signal | None:
            return signal_at(index, bars) if index == 79 else None

        with patch.object(engine, "evaluate_signal", side_effect=fake_signal):
            result = engine.run_backtest(bars, engine.Settings(round_trip_cost_fraction=0.0, smart_exit_enabled=False))
        self.assertEqual(len(result.trades), 1)
        self.assertEqual(result.trades[0].entry, 98.0)
        self.assertEqual(result.diagnostics["gap_entry_outside_frozen_bracket"], 1)

    def test_no_fixed_20_bar_exit_and_report_files(self) -> None:
        bars = candles(112)

        def fake_signal(_bars: list[engine.Candle], _indicators: engine.Indicators, index: int, _settings: engine.Settings) -> engine.Signal | None:
            return signal_at(index, bars, stop=90.0, target=120.0) if index == 79 else None

        with patch.object(engine, "evaluate_signal", side_effect=fake_signal):
            result = engine.run_backtest(bars, engine.Settings(round_trip_cost_fraction=0.0, smart_exit_enabled=False))
        self.assertIsNotNone(result.open_trade)
        assert result.open_trade is not None
        self.assertGreater(result.open_trade.bars_held, 20)
        self.assertEqual(result.trades, [])

        with tempfile.TemporaryDirectory() as directory:
            paths = engine.write_reports(result, Path(directory) / "reports")
            self.assertEqual(set(paths), {"trades", "signals", "equity", "summary"})
            self.assertTrue(all(path.is_file() for path in paths.values()))
            summary = json.loads(paths["summary"].read_text(encoding="utf-8"))
        self.assertEqual(summary["model"], "native_15m_lite_python")
        self.assertIn("No exchange API", summary["warning"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
