import csv
from pathlib import Path
import tempfile
import unittest

import source_inventory


class SourceInventoryTest(unittest.TestCase):
    def test_catalog_has_15_symbols_with_5_crypto_and_10_non_crypto(self) -> None:
        rows = source_inventory.build_inventory()
        self.assertEqual(15, len(rows))
        self.assertEqual(5, sum(1 for row in rows if row.asset_class == "CRYPTO"))
        self.assertEqual(2, sum(1 for row in rows if row.asset_class == "METALS"))
        self.assertEqual(1, sum(1 for row in rows if row.asset_class == "INDEX"))
        self.assertEqual(7, sum(1 for row in rows if row.asset_class == "FOREX"))

    def test_every_symbol_has_unique_dedicated_source(self) -> None:
        by_symbol = {row.symbol: row for row in source_inventory.build_inventory()}
        self.assertEqual(15, len({r.candle_primary for r in by_symbol.values()}))
        self.assertEqual(15, len({r.watch_sources for r in by_symbol.values()}))
        self.assertIn("DUKASCOPY_XAU", by_symbol["XAU/USD"].candle_primary)
        self.assertIn("DUKASCOPY_XAG", by_symbol["XAG/USD"].candle_primary)
        self.assertIn("YAHOO_DOW", by_symbol["DOW"].candle_primary)
        self.assertIn("BINANCE_SPOT", by_symbol["BTC/USDT"].candle_primary)
        self.assertIn("OKX_SPOT", by_symbol["ETH/USDT"].candle_primary)
        self.assertIn("GATE_SPOT", by_symbol["SOL/USDT"].candle_primary)
        self.assertIn("MEXC_SPOT", by_symbol["BNB/USDT"].candle_primary)
        self.assertIn("KRAKEN_SPOT", by_symbol["XRP/USDT"].candle_primary)

    def test_csv_matches_committed_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            generated = Path(tmp) / "inventory.csv"
            source_inventory.write_csv(generated)
            self.assertEqual(
                generated.read_text(encoding="utf-8"),
                source_inventory.DEFAULT_OUT.read_text(encoding="utf-8"),
            )
            with generated.open(encoding="utf-8", newline="") as handle:
                parsed = list(csv.DictReader(handle))
            self.assertEqual(15, len(parsed))


if __name__ == "__main__":
    unittest.main()
