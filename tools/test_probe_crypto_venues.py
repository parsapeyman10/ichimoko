import unittest
from unittest.mock import patch
import probe_crypto_venues as audit


class MarketProbeTest(unittest.TestCase):
    def test_catalog_has_unique_usdt_pairs(self):
        self.assertEqual(45, len(audit.symbols()))
        self.assertEqual(len(audit.symbols()), len(set(audit.symbols())))

    def test_kraken_rest_alias_and_exact_quote(self):
        raw = {"XBTUSDT": {"wsname": "XBT/USDT", "status": "online"},
               "DOGEUSD": {"wsname": "XDG/USD", "status": "online"},
               "XDGUSDT": {"wsname": "XDG/USDT", "status": "online"}}
        self.assertEqual("XBTUSDT", audit.kraken_listing(raw, "BTC/USDT"))
        self.assertEqual("XDGUSDT", audit.kraken_listing(raw, "DOGE/USDT"))
        self.assertIsNone(audit.kraken_listing(raw, "ETH/USDT"))
        raw["XDGUSDT"]["status"] = "cancel_only"
        self.assertIsNone(audit.kraken_listing(raw, "DOGE/USDT"))

    def test_okx_spot_not_swap_or_usd(self):
        data = [{"instId": "BTC-USDT", "instType": "SWAP", "state": "live",
                 "baseCcy": "BTC", "quoteCcy": "USDT"},
                {"instId": "BTC-USD", "instType": "SPOT", "state": "live",
                 "baseCcy": "BTC", "quoteCcy": "USD"}]
        self.assertIsNone(audit.okx_listing(data, "BTC/USDT"))
        data.append({"instId": "BTC-USDT", "instType": "SPOT", "state": "live",
                     "baseCcy": "BTC", "quoteCcy": "USDT"})
        self.assertEqual("BTC-USDT", audit.okx_listing(data, "BTC/USDT"))

    def test_three_closed_frames_cannot_be_padded_or_stale(self):
        interval = 5 * 60_000
        now = 323 * interval + interval // 2
        rows = [(i * interval, 2.0, 3.0, 1.0, 2.0, 4.0) for i in range(1, 323)]
        self.assertEqual("ok", audit.checked_bars(rows, 5, now))
        self.assertEqual("319/320 closed", audit.checked_bars(rows[-319:], 5, now))
        self.assertEqual("missing bar", audit.checked_bars(rows[-321:-10] + rows[-9:], 5, now))
        self.assertEqual("stale last bar", audit.checked_bars(rows, 5, now + 4 * interval))
        self.assertEqual("invalid OHLC", audit.checked_bars(rows[:-1] +
            [(rows[-1][0], 2.0, 1.0, 1.0, 2.0, 4.0)], 5, now))

    def test_no_source_is_ready_without_all_frames_and_fresh_real_execution(self):
        with patch.object(audit, "candles", side_effect=["ok", "ok", "missing bar"]), \
             patch.object(audit, "latest_trade", return_value=1000):
            self.assertFalse(audit.probe("KRAKEN_SPOT", "XBTUSDT", 2_000_000)["ready"])
        with patch.object(audit, "candles", return_value="ok"), \
             patch.object(audit, "latest_trade", return_value=91_000):
            self.assertFalse(audit.probe("OKX_SPOT", "BTC-USDT", 2_000_000)["ready"])


if __name__ == "__main__":
    unittest.main()
