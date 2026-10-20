import unittest

from tools import probe_twelve_coverage as probe


class ProbeTwelveCoverageTest(unittest.TestCase):
    def test_catalog_reads_all_fifteen_symbols(self) -> None:
        symbols = probe.read_catalog_symbols()
        self.assertEqual(15, len(symbols))
        self.assertEqual(15, len(set(symbols)))
        self.assertIn("XAU/USD", symbols)
        self.assertIn("DOW", symbols)
        self.assertIn("BTC/USDT", symbols)

    def test_key_env_names_are_stable(self) -> None:
        self.assertEqual("TWELVE_DATA_KEY_EUR_USD", probe.key_env_name("EUR/USD"))
        self.assertEqual("TWELVE_DATA_KEY_XAU_USD", probe.key_env_name("XAU/USD"))
        self.assertEqual("TWELVE_DATA_KEY_DOW", probe.key_env_name("DOW"))

    def test_classifier_never_invents_success(self) -> None:
        meta = {"symbol": "EUR/USD", "currency": "USD"}
        self.assertEqual("ok", probe.classify(
            {"meta": meta, "values": [{"datetime": "2026-10-20 00:00:00"}]}, "EUR/USD")[0])
        self.assertEqual("no-data", probe.classify({"meta": meta, "values": []}, "EUR/USD")[0])
        self.assertEqual("id-mismatch", probe.classify(
            {"meta": {"symbol": "DJI", "currency": "USD"}, "values": [{}]}, "DOW")[0])
        # A USD-quoted answer for a USDT symbol is a different instrument, not a success.
        self.assertEqual("id-mismatch", probe.classify(
            {"meta": {"symbol": "BTC/USD", "currency": "USD"}, "values": [{}]},
            "BTC/USDT", "USDT")[0])
        self.assertEqual("currency-mismatch", probe.classify(
            {"meta": {"symbol": "BTC/USDT", "currency": "USD"}, "values": [{}]},
            "BTC/USDT", "USDT")[0])
        self.assertEqual("quota", probe.classify(
            {"code": 429, "message": "API limit reached"}, "EUR/USD")[0])
        self.assertEqual("error-401", probe.classify(
            {"code": 401, "message": "apikey is invalid"}, "EUR/USD")[0])
        self.assertEqual("bad-response", probe.classify("<html>", "EUR/USD")[0])

    def test_expected_status_is_honest_about_paid_markets(self) -> None:
        self.assertEqual("free-same-id", probe.expected_status("EUR/USD"))
        self.assertEqual("paid-commodity", probe.expected_status("XAU/USD"))
        self.assertEqual("index-id-mismatch", probe.expected_status("DOW"))
        self.assertEqual("crypto-usd-not-usdt", probe.expected_status("BTC/USDT"))


if __name__ == "__main__":
    unittest.main()
