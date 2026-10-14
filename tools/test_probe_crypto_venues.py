import unittest

from tools import probe_crypto_venues as probe


class ProbeCryptoVenuesTest(unittest.TestCase):
    def test_catalog_has_5_unique_usdt_pairs(self) -> None:
        symbols = probe.load_catalog_symbols()
        self.assertEqual(5, len(symbols))
        self.assertEqual(5, len(set(symbols)))
        self.assertEqual(["BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT"], symbols)

    def test_exchange_parsers_accept_only_live_spot_usdt_pairs(self) -> None:
        self.assertEqual(
            {"BTC/USDT"},
            probe.binance_spot_usdt_pairs({
                "symbols": [
                    {"baseAsset": "BTC", "quoteAsset": "USDT", "status": "TRADING", "isSpotTradingAllowed": True},
                    {"baseAsset": "ETH", "quoteAsset": "USDT", "status": "BREAK", "isSpotTradingAllowed": True},
                    {"baseAsset": "SOL", "quoteAsset": "BTC", "status": "TRADING", "isSpotTradingAllowed": True},
                ]
            }),
        )
        self.assertEqual(
            {"ETH/USDT"},
            probe.okx_spot_usdt_pairs({
                "code": "0",
                "data": [
                    {"instType": "SPOT", "baseCcy": "ETH", "quoteCcy": "USDT", "state": "live"},
                    {"instType": "SWAP", "baseCcy": "BTC", "quoteCcy": "USDT", "state": "live"},
                ],
            }),
        )
        self.assertEqual(
            {"SOL/USDT"},
            probe.bybit_spot_usdt_pairs({
                "retCode": 0,
                "result": {"list": [{"baseCoin": "SOL", "quoteCoin": "USDT", "status": "Trading"}]},
            }),
        )
        self.assertEqual(
            {"XRP/USDT"},
            probe.kucoin_spot_usdt_pairs({
                "code": "200000",
                "data": [{"baseCurrency": "XRP", "quoteCurrency": "USDT", "enableTrading": True}],
            }),
        )
        self.assertEqual(
            {"ADA/USDT"},
            probe.gate_spot_usdt_pairs([
                {"base": "ADA", "quote": "USDT", "trade_status": "tradable"},
                {"base": "DOGE", "quote": "USDT", "trade_status": "untradable"},
            ]),
        )
        self.assertEqual(
            {"BTC/USDT", "DOGE/USDT"},
            probe.kraken_spot_usdt_pairs({
                "error": [],
                "result": {
                    "XBTUSDT": {"wsname": "XBT/USDT", "status": "online"},
                    "XDGUSDT": {"wsname": "XDG/USDT", "status": "online"},
                    "ETHUSD": {"wsname": "ETH/USD", "status": "online"},
                },
            }),
        )
        self.assertEqual(
            {"BTC/USDT"},
            probe.nobitex_usdt_pairs({
                "status": "ok",
                "stats": {
                    "btc-usdt": {"isClosed": False},
                    "eth-usdt": {"isClosed": True},
                    "btc-rls": {"isClosed": False},
                },
            }),
        )

    def test_coverage_summary_Separates_binance_from_fallbacks(self) -> None:
        summary = probe.coverage_summary(
            ["BTC/USDT", "ETH/USDT", "SOL/USDT"],
            {
                "binance_vision": {"BTC/USDT", "ETH/USDT", "SOL/USDT"},
                "okx_spot": {"BTC/USDT"},
                "gate_spot": {"ETH/USDT"},
            },
        )
        self.assertEqual(3, summary["covered_by_any_venue"])
        self.assertEqual(2, summary["covered_by_non_binance_venue"])
        self.assertEqual(["SOL/USDT"], summary["missing_from_all_non_binance"])

    def test_closed_bar_validator_checks_1500_contiguous_fresh_bars(self) -> None:
        step = 300_000
        end = 1_800_000_000_000 // step * step
        bars = [
            (end - (1500 - i) * step, 100.0, 101.0, 99.0, 100.5, 12.0)
            for i in range(1500)
        ]
        ok, detail = probe.validate_closed_bars(bars, step, end + 1_000)
        self.assertTrue(ok, detail)
        bad_gap = list(bars)
        bad_gap[-1] = (bad_gap[-1][0] + step, 100.0, 101.0, 99.0, 100.5, 12.0)
        self.assertFalse(probe.validate_closed_bars(bad_gap, step, end + step + 1_000)[0])
        self.assertFalse(probe.validate_closed_bars(bars[:-1], step, end + 1_000)[0])


if __name__ == "__main__":
    unittest.main()
