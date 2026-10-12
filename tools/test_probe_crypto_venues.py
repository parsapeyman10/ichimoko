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

    def test_freshest_verified_spot_venue_wins_without_using_an_unverified_pair(self):
        candidates = [{"venue": "KRAKEN_SPOT", "ready": True, "trade_age_ms": 48_000},
                      {"venue": "OKX_SPOT", "ready": True, "trade_age_ms": 1_000}]
        self.assertEqual("OKX_SPOT", audit.suggested_venue(candidates))
        candidates[1]["ready"] = False
        self.assertEqual("KRAKEN_SPOT", audit.suggested_venue(candidates))
        candidates[0]["ready"] = False
        self.assertIsNone(audit.suggested_venue(candidates))

    def test_gate_listing_and_candle_column_order_and_execution_time(self):
        with patch.object(audit, "request_json", return_value={
            "id": "RUNE_USDT", "base": "RUNE", "quote": "USDT",
            "trade_status": "tradable", "type": "normal"}):
            self.assertEqual("RUNE_USDT", audit.gate_listing("RUNE/USDT"))
            self.assertIsNone(audit.gate_listing("TON/USDT"))
        interval = 5 * 60_000
        now = 323 * interval + interval // 2
        # Gate: timestamp seconds, quote volume, close, high, low, open, base volume, closed.
        rows = [[str(i * interval // 1000), "123", "2.5", "3", "1", "2", "50", "true"]
                for i in range(1, 323)]
        rows += [[str(323 * interval // 1000), "0", "0", "0", "0", "0", "0", "false"]]
        with patch.object(audit, "request_json", return_value=rows):
            self.assertEqual("ok", audit.candles("GATE_SPOT", "RUNE_USDT", 5, now))
        with patch.object(audit, "request_json", return_value=[{
            "currency_pair": "RUNE_USDT", "create_time_ms": "99000.123000",
            "price": "2.5", "amount": "5"}]):
            self.assertEqual(1000, audit.latest_trade("GATE_SPOT", "RUNE_USDT", 100_000))

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
