#!/usr/bin/env python3
"""Static contract for the standalone Drive-book Pine strategy.

This is intentionally independent from the older BASE strategy checks. TradingView's
compiler is not present in this workspace, so this guards the new source's explicit
book-synthesis contract rather than claiming an executable backtest.
"""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "strategies" / "tradingview" / "ICHIMOKU_BOOK_SYNTHESIS_STRATEGY.pine"


def without_line_comments(source: str) -> str:
    return "\n".join(line.split("//", 1)[0] for line in source.splitlines())


class BookSynthesisStaticContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SOURCE.read_text(encoding="utf-8")
        cls.code = without_line_comments(cls.source)

    def test_is_a_standalone_pine_strategy(self) -> None:
        self.assertIn("//@version=5", self.source)
        self.assertIn('strategy("Ichimoku Book Synthesis | Squeeze & Reclaim"', self.code)
        self.assertNotIn("BASE Main", self.code)
        self.assertNotIn("BASE_ICHIMOKU", self.code)
        self.assertNotIn("request.security", self.code)
        self.assertNotIn("input.timeframe(", self.code)

    def test_requires_the_book_synthesis_context(self) -> None:
        for token in (
            "bool cloudBull",
            "bool cloudBear",
            "bool tkBull",
            "bool tkBear",
            "float rsi14 = ta.rsi(close, 14)",
            "float ema89 = ta.ema(close, 89)",
            "float sma200 = ta.sma(close, 200)",
            "bool chikouBullClear",
            "bool chikouBearClear",
            "bool contextLong",
            "bool contextShort",
        ):
            self.assertIn(token, self.code)

    def test_has_only_the_two_documented_entry_families(self) -> None:
        for token in (
            "bool squeezeActive",
            "bool squeezeRecentlyReleased",
            "bool squeezeBreakoutLong",
            "bool squeezeBreakoutShort",
            "bool pullbackReclaimLong",
            "bool pullbackReclaimShort",
            "bool longSetup = contextLong and (squeezeBreakoutLong or pullbackReclaimLong)",
            "bool shortSetup = contextShort and (squeezeBreakoutShort or pullbackReclaimShort)",
        ):
            self.assertIn(token, self.code)

    def test_uses_frozen_kijun_bracket_and_two_to_three_r_target(self) -> None:
        self.assertIn("float longStopCandidate = kijun - atr * kijunStopAtrBuffer", self.code)
        self.assertIn("float shortStopCandidate = kijun + atr * kijunStopAtrBuffer", self.code)
        self.assertIn('input.float(2.50, "Fixed target R multiple", minval=2.00, maxval=3.00', self.code)
        self.assertIn("var float activeStop = na", self.code)
        self.assertIn("var float activeTarget = na", self.code)
        self.assertIn('strategy.exit("Book Long bracket", from_entry="Book Long", stop=activeStop, limit=activeTarget)', self.code)
        self.assertIn('strategy.exit("Book Short bracket", from_entry="Book Short", stop=activeStop, limit=activeTarget)', self.code)
        self.assertIn("pyramiding=0", self.code)
        self.assertNotRegex(self.code, r"bars_held|time.stop|strategy\.close_all")


if __name__ == "__main__":
    unittest.main(verbosity=2)
