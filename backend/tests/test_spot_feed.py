"""The automatic keyless spot-gold fallback must behave exactly like every other real feed
in this codebase: validate everything, never invent a quote or a bar, and never rewrite
history."""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest

BACKEND_ROOT = Path(__file__).resolve().parent.parent
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.config import Settings  # noqa: E402
from app.models import Candle, Tick, Timeframe  # noqa: E402
from app.services import spot_feed  # noqa: E402
from app.services.history import DataUnavailable  # noqa: E402


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def swissquote_payload(bid=4284.9, ask=4285.5, ts=None):
    ts = ts if ts is not None else int(datetime.now(timezone.utc).timestamp() * 1000)
    return [{
        "topo": {"platform": "SwissquoteLtd", "server": "Live5"},
        "spreadProfilePrices": [
            {"spreadProfile": "premium", "bid": bid - 0.1, "ask": ask + 0.1},
            {"spreadProfile": "prime", "bid": bid, "ask": ask},
        ],
        "ts": ts,
    }]


def gold_api_payload(price=4286.2, updated_at=None):
    updated_at = updated_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    return {"currency": "USD", "symbol": "XAU", "price": price, "updatedAt": updated_at}


# ─── individual source parsing ───────────────────────────────────────────
def test_swissquote_quote_is_parsed_and_uses_the_prime_spread():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "forex-data-feed.swissquote.com"
        return httpx.Response(200, json=swissquote_payload(bid=4284.9, ask=4285.5))

    async def run():
        async with _client(handler) as client:
            return await spot_feed._fetch_swissquote(client)

    tick = asyncio.run(run())
    assert tick.symbol == "XAU/USD" and tick.bid == 4284.9 and tick.ask == 4285.5
    assert tick.provider == "spot_fallback:swissquote"


def test_swissquote_rejects_inverted_or_nonpositive_spread():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=swissquote_payload(bid=100.0, ask=90.0))

    async def run():
        async with _client(handler) as client:
            return await spot_feed._fetch_swissquote(client)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


def test_swissquote_rejects_a_stale_timestamp():
    stale_ms = int((datetime.now(timezone.utc) - timedelta(minutes=10)).timestamp() * 1000)

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=swissquote_payload(ts=stale_ms))

    async def run():
        async with _client(handler) as client:
            return await spot_feed._fetch_swissquote(client)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


def test_gold_api_quote_is_parsed():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.gold-api.com"
        return httpx.Response(200, json=gold_api_payload(price=4286.2))

    async def run():
        async with _client(handler) as client:
            return await spot_feed._fetch_gold_api(client)

    tick = asyncio.run(run())
    assert tick.symbol == "XAU/USD" and tick.bid == tick.ask == 4286.2
    assert tick.provider == "spot_fallback:gold-api"


def test_gold_api_rejects_wrong_symbol():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"symbol": "XAG", "price": 30, "updatedAt": datetime.now(timezone.utc).isoformat()})

    async def run():
        async with _client(handler) as client:
            return await spot_feed._fetch_gold_api(client)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


def test_fetch_spot_quote_falls_back_to_gold_api_when_swissquote_fails():
    async def handler(request: httpx.Request) -> httpx.Response:
        if "swissquote" in request.url.host:
            return httpx.Response(500)
        return httpx.Response(200, json=gold_api_payload(price=4290.0))

    async def run():
        async with _client(handler) as client:
            return await spot_feed.fetch_spot_quote(client)

    tick = asyncio.run(run())
    assert tick.provider == "spot_fallback:gold-api" and tick.bid == 4290.0


def test_fetch_spot_quote_raises_when_both_sources_fail():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    async def run():
        async with _client(handler) as client:
            return await spot_feed.fetch_spot_quote(client)

    with pytest.raises(DataUnavailable):
        asyncio.run(run())


# ─── persisted store: real bars only, never rewritten ────────────────────
def test_store_records_bars_and_persists_across_instances(tmp_path):
    path = tmp_path / "spot.json"
    store = spot_feed.SpotHistoryStore(path=path)
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    bar1 = Candle(symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base,
                  open=100, high=101, low=99, close=100.5, volume=3, complete=True)
    bar2 = Candle(symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base + timedelta(minutes=1),
                  open=100.5, high=102, low=100, close=101.5, volume=4, complete=True)
    store.record(bar1)
    store.record(bar2)
    assert [c.close for c in store.history(Timeframe.M1, 10)] == [100.5, 101.5]

    reloaded = spot_feed.SpotHistoryStore(path=path)
    assert [c.close for c in reloaded.history(Timeframe.M1, 10)] == [100.5, 101.5]


def test_store_never_rewrites_or_duplicates_an_already_closed_bar(tmp_path):
    store = spot_feed.SpotHistoryStore(path=tmp_path / "spot.json")
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    first = Candle(symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base,
                    open=100, high=101, low=99, close=100.5, volume=1, complete=True)
    store.record(first)
    replay_same_minute = Candle(symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base,
                                 open=999, high=999, low=999, close=999, volume=1, complete=True)
    store.record(replay_same_minute)
    older = Candle(symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base - timedelta(minutes=5),
                   open=1, high=1, low=1, close=1, volume=1, complete=True)
    store.record(older)
    history = store.history(Timeframe.M1, 10)
    assert len(history) == 1 and history[0].close == 100.5


def test_store_resamples_real_m1_bars_into_higher_timeframes(tmp_path):
    store = spot_feed.SpotHistoryStore(path=tmp_path / "spot.json")
    base = datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc)
    for i in range(10):
        store.record(Candle(
            symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base + timedelta(minutes=i),
            open=100 + i, high=101 + i, low=99 + i, close=100.5 + i, volume=1, complete=True,
        ))
    m5 = store.history(Timeframe.M5, 10)
    assert len(m5) == 2  # 10 real 1m bars -> two real 5m buckets
    assert m5[0].open == 100 and m5[0].close == 104.5
    assert m5[0].high == 105 and m5[0].low == 99


def test_get_history_returns_nothing_when_store_is_empty(tmp_path, monkeypatch):
    monkeypatch.setattr(spot_feed, "store", spot_feed.SpotHistoryStore(path=tmp_path / "empty.json"))
    assert spot_feed.get_history(Timeframe.M1, 100) == []


# ─── the live tick generator: real ticks only, aggregated + persisted ────
def test_spot_fallback_ticks_yields_real_ticks_and_records_closed_bars(tmp_path, monkeypatch):
    monkeypatch.setattr(spot_feed, "store", spot_feed.SpotHistoryStore(path=tmp_path / "spot.json"))
    monkeypatch.setattr(spot_feed, "POLL_SECONDS", 0)

    prices = [4280.0, 4281.0, 4282.0]
    calls = {"n": 0}

    async def fake_quote(client):
        i = calls["n"]
        calls["n"] += 1
        # Force each call into a different minute bucket so a bar closes deterministically.
        ts = datetime(2026, 1, 1, 0, i, 5, tzinfo=timezone.utc)
        price = prices[min(i, len(prices) - 1)]
        return Tick(symbol="XAU/USD", timestamp=ts, bid=price, ask=price, provider="spot_fallback:swissquote")

    monkeypatch.setattr(spot_feed, "fetch_spot_quote", fake_quote)

    async def read():
        stream = spot_feed.spot_fallback_ticks(Settings(twelve_data_api_key=None, market_symbol="XAU/USD"))
        out = []
        try:
            for _ in range(3):
                out.append(await asyncio.wait_for(anext(stream), timeout=1))
        finally:
            await stream.aclose()
        return out

    ticks = asyncio.run(read())
    assert len(ticks) == 3
    assert all(t.symbol == "XAU/USD" for t in ticks)
    # Two full minutes elapsed -> at least one real bar must have been closed and persisted.
    assert len(spot_feed.store.history(Timeframe.M1, 10)) >= 1


def test_spot_fallback_ticks_raises_dataunavailable_when_the_source_fails(monkeypatch):
    async def failing(client):
        raise RuntimeError("network down")

    monkeypatch.setattr(spot_feed, "fetch_spot_quote", failing)

    async def read():
        stream = spot_feed.spot_fallback_ticks(Settings(twelve_data_api_key=None))
        try:
            await asyncio.wait_for(anext(stream), timeout=1)
        finally:
            await stream.aclose()

    with pytest.raises(DataUnavailable):
        asyncio.run(read())


# ─── history.py wiring: automatic fallback, still honest when insufficient ─
def test_load_history_without_a_key_uses_the_free_fallback_when_enough_bars_exist(tmp_path, monkeypatch):
    from app.services import history

    fresh_store = spot_feed.SpotHistoryStore(path=tmp_path / "spot.json")
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    for i in range(5):
        fresh_store.record(Candle(
            symbol="XAU/USD", timeframe=Timeframe.M1, timestamp=base + timedelta(minutes=i),
            open=100 + i, high=101 + i, low=99 + i, close=100.5 + i, volume=1, complete=True,
        ))
    monkeypatch.setattr(spot_feed, "store", fresh_store)

    settings = Settings(twelve_data_api_key=None)
    candles = asyncio.run(history.load_history(settings, Timeframe.M1, output_size=100))
    assert len(candles) == 5
    assert all(c.symbol == "XAU/USD" for c in candles)


def test_load_history_without_a_key_and_without_history_stays_honest(tmp_path, monkeypatch):
    from app.services import history

    monkeypatch.setattr(spot_feed, "store", spot_feed.SpotHistoryStore(path=tmp_path / "empty.json"))
    settings = Settings(twelve_data_api_key=None)
    with pytest.raises(DataUnavailable) as exc:
        asyncio.run(history.load_history(settings, Timeframe.M5, output_size=100))
    # Must no longer read as "you must configure a key" — a key is now optional.
    assert "اختیاری" in str(exc.value)


def test_market_ticks_routes_to_the_free_fallback_without_a_key(monkeypatch):
    from app.services import market_feed

    async def fake_fallback(settings):
        from app.models import Tick
        yield Tick(symbol="XAU/USD", timestamp=datetime.now(timezone.utc), bid=1, ask=1,
                   provider="spot_fallback:swissquote")

    monkeypatch.setattr(spot_feed, "spot_fallback_ticks", fake_fallback)

    async def read():
        return [tick async for tick in market_feed.market_ticks(Settings(twelve_data_api_key=None))]

    ticks = asyncio.run(read())
    assert len(ticks) == 1 and ticks[0].provider == "spot_fallback:swissquote"


def test_market_free_fallback_can_be_disabled_via_config(monkeypatch):
    from app.services import market_feed

    async def read():
        settings = Settings(twelve_data_api_key=None, market_free_fallback_enabled=False)
        return [tick async for tick in market_feed.market_ticks(settings)]

    with pytest.raises(DataUnavailable):
        asyncio.run(read())


def test_load_history_free_fallback_can_be_disabled_via_config(monkeypatch):
    from app.services import history

    settings = Settings(twelve_data_api_key=None, market_free_fallback_enabled=False)
    with pytest.raises(DataUnavailable):
        asyncio.run(history.load_history(settings, Timeframe.M5, output_size=100))
