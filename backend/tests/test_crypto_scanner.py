from __future__ import annotations

from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.services.crypto_scanner import CryptoScanner, confirm_spot, preselect

NOW = datetime(2026, 9, 23, 12, 25, tzinfo=timezone.utc)
HOUR_MS = 3_600_000


def coin(now=NOW):
    return {
        "id": "demo-coin", "symbol": "dem", "name": "Demo Coin", "market_cap": 100_000_000,
        "fully_diluted_valuation": 125_000_000, "total_volume": 25_000_000,
        "circulating_supply": 80_000_000, "max_supply": 100_000_000,
        "current_price": 2.0, "price_change_percentage_1h_in_currency": 1.2,
        "price_change_percentage_24h_in_currency": 5.0,
        "price_change_percentage_7d_in_currency": 10.0,
        "last_updated": (now - timedelta(minutes=1)).isoformat(),
    }


def coin_pair(now=NOW):
    return {"tickers": [{
        "base": "DEM", "target": "USDT", "coin_id": "demo-coin", "target_coin_id": "tether",
        "market": {"identifier": "binance", "has_trading_incentive": False},
        "is_stale": False, "is_anomaly": False,
        "converted_last": {"usd": 2.0}, "converted_volume": {"usd": 8_000_000},
        "bid_ask_spread_percentage": 0.1,
        "timestamp": (now - timedelta(minutes=2)).isoformat(),
    }]}


def venue(now=NOW):
    code = "DEMUSDT"
    info = {"symbols": [{"symbol": code, "baseAsset": "DEM", "quoteAsset": "USDT",
                         "status": "TRADING", "isSpotTradingAllowed": True}]}
    ticker = {"symbol": code, "lastPrice": "2", "quoteVolume": "8000000",
              "priceChangePercent": "5", "count": 900, "closeTime": int(now.timestamp() * 1000) - 30_000}
    book = {"symbol": code, "bidPrice": "1.999", "askPrice": "2.001"}
    hour = int(now.timestamp() * 1000) // HOUR_MS * HOUR_MS
    bars = []
    for i in range(12):
        start = hour - (12 - i) * HOUR_MS
        q = 400_000 if i < 9 else 1_200_000
        close = 1.95 if i == 8 else 2.0 if i >= 9 else 1.89
        bars.append([start, "1.9", "2.1", "1.8", str(close), "250000", start + HOUR_MS - 1,
                     str(q), 150, "100000", str(q * .60), "0"])
    bars.append([hour, "2", "2.1", "1.9", "2", "1000", hour + HOUR_MS - 1,
                 "1000", 1, "500", "600", "0"])  # forming: MUST be excluded
    return info, ticker, book, bars


def test_strict_market_and_identity_prefilters():
    assert preselect([coin()], NOW) == [coin()]
    assert preselect([coin(), {**coin(), "id": "imposter"}], NOW) == []  # ticker collision
    for field, value in [("max_supply", None), ("total_volume", 100),
                         ("price_change_percentage_1h_in_currency", -3),
                         ("market_cap", 100_000), ("last_updated", "2020-01-01T00:00:00Z"),
                         ("current_price", float("nan"))]:
        assert preselect([{**coin(), field: value}], NOW) == [], field
    with pytest.raises(ValueError):
        preselect([], NOW)  # provider error, not a valid empty screen


def test_spot_confirmation_requires_closed_candles_exact_coin_identity_and_price_agreement():
    info, ticker, book, bars = venue()
    pair = coin_pair()
    result = confirm_spot(coin(), info, ticker, book, bars, NOW, pair)
    assert result and result["symbol"] == "DEMUSDT"
    assert result["volume_ratio_3h"] == 3
    assert result["taker_buy_ratio_3h"] == .6
    assert result["coingecko_pair_at"] == pair["tickers"][0]["timestamp"]
    assert result["last_closed_candle_at"] != datetime.fromtimestamp(bars[-1][6] / 1000, timezone.utc).isoformat()
    assert confirm_spot(coin(), info, ticker, {**book, "askPrice": "2.02"}, bars, NOW, pair) is None
    assert confirm_spot({**coin(), "current_price": 2.1}, info, ticker, book, bars, NOW, pair) is None
    assert confirm_spot(coin(), info, {**ticker, "closeTime": 0}, book, bars, NOW, pair) is None
    assert confirm_spot(coin(), info, ticker, book, bars[:-4], NOW, pair) is None
    no_spike = [[*bar] for bar in bars]
    for row in no_spike[-4:-1]:
        row[7] = "400000"
    assert confirm_spot(coin(), info, ticker, book, no_spike, NOW, pair) is None
    assert confirm_spot(coin(), {"symbols": [{**info["symbols"][0], "isSpotTradingAllowed": False}]},
                        ticker, book, bars, NOW, pair) is None
    assert confirm_spot(coin(), info, ticker, book, [[*row] for row in bars[:2]] + bars[3:], NOW, pair) is None
    for changed in ({"coin_id": "different-asset"}, {"is_stale": True},
                    {"converted_last": {"usd": 4}}, {"timestamp": "2020-01-01T00:00:00Z"}):
        mismatched = {"tickers": [{**pair["tickers"][0], **changed}]}
        assert confirm_spot(coin(), info, ticker, book, bars, NOW, mismatched) is None


def test_public_endpoint_sends_no_order_and_never_returns_stale_candidates(monkeypatch):
    from app import main

    scanner = CryptoScanner(Settings(coingecko_demo_api_key="server-only-secret"))
    monkeypatch.setattr(main, "crypto_scanner", scanner)
    monkeypatch.setattr("app.services.crypto_scanner.datetime", FixedDatetime)
    info, ticker, book, bars = venue()
    requested = []

    async def provider(_client, url, *, params=None, headers=None):
        requested.append((url, params, headers))
        if "coingecko" in url:
            assert headers == {"x-cg-demo-api-key": "server-only-secret"}
            return coin_pair() if url.endswith("/tickers") else [coin()]
        if url.endswith("exchangeInfo"):
            return info
        if url.endswith("ticker/24hr"):
            return ticker
        if url.endswith("ticker/bookTicker"):
            return book
        if url.endswith("klines"):
            return bars
        raise AssertionError(url)

    monkeypatch.setattr(scanner, "_fetch_json", provider)
    client = TestClient(main.app)
    first = client.get("/api/v1/crypto/candidates").json()
    assert first["status"]["state"] == "online"
    assert first["scanned"] == 1 and first["preselected"] == 1
    assert len(first["candidates"]) == 1
    assert "secret" not in str(first)
    assert all("POST" not in request[0] for request in requested)
    second = client.get("/api/v1/crypto/candidates").json()
    assert second["candidates"] == first["candidates"]
    assert first["status"]["cached"] is False and second["status"]["cached"] is True
    assert first["checked_at"] == second["checked_at"]  # cached data is not a fresh check
    assert len(requested) == 6  # 2 CG + 4 Binance; bounded cache, no repeated provider calls

    async def offline(*args, **kwargs):
        raise httpx.ReadTimeout("provider key should not appear in error")

    scanner._last_attempt = 0
    monkeypatch.setattr(scanner, "_fetch_json", offline)
    failed = client.get("/api/v1/crypto/candidates").json()
    assert failed["status"]["state"] == "unavailable"
    assert failed["candidates"] == [] and failed["scanned"] == 0
    assert "key should not" not in str(failed)


def test_no_coins_pass_preselection_does_not_claim_binance_was_contacted(monkeypatch):
    from app import main
    scanner = CryptoScanner(Settings())
    monkeypatch.setattr(main, "crypto_scanner", scanner)
    async def provider(_client, url, *, params=None, headers=None):
        assert "coingecko.com/api/v3/coins/markets" in url
        return [{**coin(datetime.now(timezone.utc)), "market_cap": 20_000_000_000}]
    monkeypatch.setattr(scanner, "_fetch_json", provider)
    result = TestClient(main.app).get("/api/v1/crypto/candidates").json()
    assert result["status"]["state"] == "online"
    assert result["preselected"] == 0 and result["candidates"] == []
    assert "Binance نیازی" in result["status"]["provider"]


def test_unlisted_spot_symbol_is_a_legitimate_empty_scan_but_rate_limits_fail_closed(monkeypatch):
    from app import main
    scanner = CryptoScanner(Settings(coingecko_demo_api_key=None))
    monkeypatch.setattr("app.services.crypto_scanner.datetime", FixedDatetime)
    monkeypatch.setattr(main, "crypto_scanner", scanner)
    calls = []

    async def provider(_client, url, *, params=None, headers=None):
        calls.append(url)
        if "coingecko" in url:
            assert headers is None  # public/keyless attempt; 401/429 must NOT fabricate results
            return [coin()]
        request = httpx.Request("GET", url)
        response = httpx.Response(400, json={"code": -1121}, request=request)
        raise httpx.HTTPStatusError("unknown symbol", request=request, response=response)

    monkeypatch.setattr(scanner, "_fetch_json", provider)
    client = TestClient(main.app)
    unlisted = client.get("/api/v1/crypto/candidates").json()
    assert unlisted["status"]["state"] == "online"
    assert unlisted["candidates"] == [] and unlisted["preselected"] == 1
    assert len(calls) == 2  # no ticker requests for unlisted pair

    async def limited(_client, url, *, params=None, headers=None):
        if "coingecko" in url:
            return [coin()]
        request = httpx.Request("GET", url)
        response = httpx.Response(429, request=request)
        raise httpx.HTTPStatusError("rate limited", request=request, response=response)

    scanner._last_attempt = 0
    monkeypatch.setattr(scanner, "_fetch_json", limited)
    failed = client.get("/api/v1/crypto/candidates").json()
    assert failed["status"]["state"] == "unavailable"
    assert failed["candidates"] == []
    assert "محدودیت نرخ" in failed["status"]["error"]


class FixedDatetime(datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW


def test_confirmed_candidate_carries_real_reasons_and_reference_levels_not_a_recommendation():
    info, ticker, book, bars = venue()
    pair = coin_pair()
    result = confirm_spot(coin(), info, ticker, book, bars, NOW, pair)
    assert result is not None
    assert result["entry_reference_price"] == float(book["askPrice"])
    # stop reference is the real low of the last 3 *closed* 1h candles (index -2 is the last closed one).
    closed_lows = [float(row[3]) for row in bars[-4:-1]]
    assert result["stop_reference_price"] == round(min(closed_lows), 8)
    assert isinstance(result["reasons"], list) and len(result["reasons"]) >= 3
    assert all(isinstance(reason, str) and reason for reason in result["reasons"])
    # Every reason must be traceable to a real numeric field already on the candidate — never a
    # vague or invented claim.
    joined = " ".join(result["reasons"])
    assert "حجم" in joined and "۳ ساعت" in joined
