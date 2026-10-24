import json
import tempfile
import unittest
from pathlib import Path
from aiohttp.test_utils import TestClient, TestServer

from archive import Archive, make_app, validate_trade


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dbpath = Path(self.tmp.name) / "trades.db"
        self.a = Archive(self.dbpath, ["BTCUSDT"])
        self.now = 1_800_000_000_000

    def tearDown(self):
        self.a.db.close()
        self.tmp.cleanup()

    def trade(self, at=None):
        return {"time": self.now if at is None else at, "price": "62000.5",
                "volume": "0.03", "type": "buy"}

    def test_first_start_gap_survives_reopen(self):
        self.assertEqual(self.a.page("BTCUSDT", 0, 5)["gaps"][0]["reason"], "process_start")
        self.a.subscribed("BTCUSDT", False, False, True)
        seq = self.a.publish("BTCUSDT", self.trade(), 42, self.now)
        self.assertEqual(self.a.page("BTCUSDT", 0, 5)["trades"][0]["seq"], seq)
        self.a.db.close()
        self.a = Archive(self.dbpath, ["BTCUSDT"])
        self.assertEqual(self.a.page("BTCUSDT", seq, 5)["gaps"][-1]["reason"], "process_start")
        self.assertEqual(self.a.last_seq("BTCUSDT"), seq)

    def test_offset_dedup_jump_and_recovery_gap(self):
        self.a.subscribed("BTCUSDT", False, False, True)
        first = self.a.publish("BTCUSDT", self.trade(), 3, self.now)
        self.assertIsNone(self.a.publish("BTCUSDT", self.trade(), 3, self.now))
        second = self.a.publish("BTCUSDT", self.trade(self.now + 1), 5, self.now)
        self.a.subscribed("BTCUSDT", True, False, True)
        self.assertEqual(self.a.last_seq("BTCUSDT"), second)
        reasons = [g["reason"] for g in self.a.page("BTCUSDT", first, 10)["gaps"]]
        self.assertIn("publisher_offset_jump", reasons)
        self.assertIn("upstream_recovery_failed", reasons)
        self.assertEqual(self.a.publish("BTCUSDT", self.trade(self.now + 2), 6, self.now), second + 1)

    def test_stale_does_not_become_fresh_and_no_foreign_symbol(self):
        self.a.subscribed("BTCUSDT", False, False, False)
        self.assertIsNone(self.a.publish("BTCUSDT", self.trade(self.now - 60_000), 1, self.now))
        with self.assertRaises(ValueError):
            validate_trade({**self.trade(), "price": "NaN"}, self.now)
        self.assertEqual(self.a.last_seq("BTCUSDT"), 0)
        self.assertIn("invalid_or_stale_publication",
                      [g["reason"] for g in self.a.page("BTCUSDT", 0, 5)["gaps"]])

    def test_page_order_is_local_sequence_not_millisecond(self):
        self.a.subscribed("BTCUSDT", False, False, True)
        self.a.publish("BTCUSDT", self.trade(self.now), 40, self.now)
        self.a.publish("BTCUSDT", self.trade(self.now - 1), 41, self.now)
        page = self.a.page("BTCUSDT", 0, 1)
        self.assertEqual(page["trades"][0]["time"], self.now)
        self.assertEqual(self.a.page("BTCUSDT", page["next"], 1)["trades"][0]["time"], self.now - 1)


class HttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.a = Archive(Path(self.tmp.name) / "db", ["ETHUSDT"])
        self.client = TestClient(TestServer(make_app(self.a, "z" * 40)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.a.db.close()
        self.tmp.cleanup()

    async def test_auth_and_bounds(self):
        path = "/v1/trades/ETHUSDT?after=0"
        self.assertEqual((await self.client.get(path)).status, 401)
        headers = {"Authorization": "Bearer " + "z" * 40}
        r = await self.client.get(path, headers=headers)
        self.assertEqual(r.status, 200)
        self.assertEqual(r.headers["Cache-Control"], "no-store")
        self.assertEqual((await r.json())["venue"], "nobitex")
        self.assertEqual((await self.client.get("/v1/trades/BTCUSDT", headers=headers)).status, 404)
        self.assertEqual((await self.client.get(path + "&limit=1001", headers=headers)).status, 400)
        self.assertEqual((await self.client.get(path.replace("after=0", "after=-1"), headers=headers)).status, 400)
