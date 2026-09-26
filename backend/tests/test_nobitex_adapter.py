from __future__ import annotations

import asyncio

import httpx
import pytest

from app.services.nobitex_adapter import (
    NobitexAdapter,
    NobitexAdapterError,
    NobitexExecutionDisabled,
)


def stats_response(src="btc", dst="usdt", *, latest=2_000_000_000, best_buy=1_999_000_000,
                    best_sell=2_001_000_000, is_closed=False, day_change="1.5"):
    return {
        "status": "ok",
        "stats": {
            f"{src}-{dst}": {
                "isClosed": is_closed,
                "bestSell": str(best_sell),
                "bestBuy": str(best_buy),
                "latest": str(latest),
                "dayChange": day_change,
            }
        },
    }


def orderbook_response(bid="1999000000", ask="2001000000", last_update=1_700_000_000_000):
    return {"status": "ok", "lastUpdate": last_update, "bids": [[bid, "0.1"]], "asks": [[ask, "0.2"]]}


class StubResponse:
    def __init__(self, payload: dict, status_code: int = 200, content: bytes = b"{}"):
        self._payload = payload
        self.status_code = status_code
        self.content = content

    def raise_for_status(self):
        if self.status_code >= 400:
            request = httpx.Request("GET", "https://apiv2.nobitex.ir/x")
            raise httpx.HTTPStatusError("boom", request=request,
                                         response=httpx.Response(self.status_code, request=request))

    def json(self):
        return self._payload


class StubClient:
    """Records every URL requested; only market/stats and orderbook are wired."""

    def __init__(self, stats_payload=None, book_payload=None, book_status=200):
        self.stats_payload = stats_payload
        self.book_payload = book_payload
        self.book_status = book_status
        self.requested_paths: list[str] = []

    async def get(self, url, params=None):
        self.requested_paths.append(url)
        if "/market/stats" in url:
            return StubResponse(self.stats_payload)
        if "/orderbook/" in url:
            return StubResponse(self.book_payload or {}, status_code=self.book_status)
        raise AssertionError(f"unexpected Nobitex endpoint requested: {url}")


def run(coro):
    return asyncio.run(coro)


def test_quote_parses_real_shaped_stats_and_orderbook():
    adapter = NobitexAdapter()
    client = StubClient(stats_response(), orderbook_response())
    quote = run(adapter.quote("BTCUSDT", client))
    assert quote.symbol == "BTCUSDT"
    assert quote.latest == 2_000_000_000
    assert quote.best_buy == 1_999_000_000
    assert quote.best_sell == 2_001_000_000
    assert quote.order_book_bid == 1_999_000_000
    assert quote.order_book_ask == 2_001_000_000
    assert quote.order_book_updated_at is not None
    assert quote.is_closed is False
    assert any("/market/stats" in path for path in client.requested_paths)
    assert any("/v3/orderbook/BTCUSDT" in path for path in client.requested_paths)


def test_quote_rejects_unknown_symbol_without_any_network_call():
    adapter = NobitexAdapter()
    client = StubClient()
    with pytest.raises(NobitexAdapterError):
        run(adapter.quote("DOGEUSDT", client))
    assert client.requested_paths == []


def test_quote_fails_closed_when_stats_missing_the_market():
    adapter = NobitexAdapter()
    client = StubClient({"status": "ok", "stats": {}}, orderbook_response())
    with pytest.raises(NobitexAdapterError):
        run(adapter.quote("BTCUSDT", client))


def test_quote_survives_a_missing_orderbook_using_stats_as_source_of_truth():
    adapter = NobitexAdapter()
    client = StubClient(stats_response(), book_status=500)
    quote = run(adapter.quote("BTCUSDT", client))
    assert quote.latest == 2_000_000_000
    assert quote.order_book_bid is None
    assert quote.order_book_ask is None


def test_order_affecting_methods_are_fail_closed_and_never_call_the_network():
    adapter = NobitexAdapter()
    with pytest.raises(NobitexExecutionDisabled):
        run(adapter.instrument_rules("BTCUSDT"))
    with pytest.raises(NobitexExecutionDisabled):
        run(adapter.submit(intent=None, user_id="u1"))
    with pytest.raises(NobitexExecutionDisabled):
        run(adapter.cancel("order-1"))
    with pytest.raises(NobitexExecutionDisabled):
        run(adapter.reconcile("order-1"))
