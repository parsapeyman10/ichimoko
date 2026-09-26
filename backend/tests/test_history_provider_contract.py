"""A successful HTTP response must not relabel another asset or a delayed/corrupt bar as live gold."""
import asyncio
from datetime import datetime, timezone

import httpx
import pytest

from app.config import Settings
from app.models import Timeframe
from app.services import history
from app.services import spot_feed


BAR = {"datetime": "2026-09-24 15:15:00", "open": "3100", "high": "3105", "low": "3098", "close": "3103"}
SETTINGS = Settings(twelve_data_api_key="test-only-placeholder", market_symbol="XAU/USD")


def provider(monkeypatch, payload):
    real_client = httpx.AsyncClient

    def reply(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/time_series"
        return httpx.Response(200, json=payload)

    monkeypatch.setattr(history.httpx, "AsyncClient", lambda **kwargs: real_client(transport=httpx.MockTransport(reply), **kwargs))


def response(**kwargs):
    return {"meta": {"symbol": "XAU/USD", "interval": "5min", "currency": "USD", "timezone": "UTC"},
            "values": [BAR], **kwargs}


def request():
    return asyncio.run(history._request(SETTINGS, "5min", 10, None, None))


def test_provider_time_keeps_seconds_and_rejects_partial_dates():
    parsed = history._parse_datetime("2026-09-24 15:16:47")
    assert parsed == datetime(2026, 9, 24, 15, 16, 47, tzinfo=timezone.utc)
    assert history._parse_datetime("2026-09-24T18:46:47+03:30") == parsed
    assert history._parse_datetime("2026-09-24", allow_date_only=True) == datetime(2026, 9, 24, tzinfo=timezone.utc)
    for bad in ("2026-09-24", "2026-09-24 15:16:47garbage", "2026-09-24 15:16:99"):
        with pytest.raises(ValueError):
            history._parse_datetime(bad)


def test_valid_provider_metadata_and_ohlc(monkeypatch):
    provider(monkeypatch, response())
    bars = request()
    assert len(bars) == 1
    assert bars[0].timestamp == datetime(2026, 9, 24, 15, 15, tzinfo=timezone.utc)
    assert bars[0].volume == 0.0


def test_provider_errors_do_not_reflect_key_or_redirect_it(monkeypatch):
    provider(monkeypatch, {"status": "error", "code": 400, "message": "test-only-placeholder"})
    with pytest.raises(history.DataUnavailable) as exc:
        request()
    assert "test-only-placeholder" not in str(exc.value)


def test_old_or_mismatched_cache_is_display_only_and_never_an_unverified_online_response(monkeypatch, tmp_path):
    provider(monkeypatch, response())
    bars = request()
    monkeypatch.setattr(history, "CACHE_DIR", tmp_path)
    key = history._cache_key("XAU/USD", Timeframe.M5, 10, None, None)
    path = history._cache_path(key)
    path.write_text(history._serialize(bars))
    assert history._read_disk(key, "XAU/USD", Timeframe.M5, 60) == bars
    path.write_text(history._serialize([bars[0].model_copy(update={"symbol": "BTC/USD"})]))
    assert history._read_disk(key, "XAU/USD", Timeframe.M5, 60) is None
    path.write_text(history._serialize(bars))
    # The free spot-fallback store is a completely separate cache from the Twelve Data disk
    # cache under test above; isolate it too so this "no key" assertion never depends on
    # whatever the fallback may or may not have collected on disk from another run.
    monkeypatch.setattr(spot_feed, "store", spot_feed.SpotHistoryStore(path=tmp_path / "spot_fallback.json"))
    no_key = Settings(twelve_data_api_key=None)
    with pytest.raises(history.DataUnavailable):
        asyncio.run(history.load_history(no_key, Timeframe.M5, output_size=10))


@pytest.mark.parametrize("bad", [
    {"meta": {"symbol": "BTC/USD", "interval": "5min"}, "values": [BAR]},
    {"meta": {"symbol": "XAU/USD", "interval": "1min"}, "values": [BAR]},
    {"meta": {"symbol": "XAU/USD", "interval": "5min", "currency": "EUR"}, "values": [BAR]},
    {"values": [BAR]},
    response(meta={"symbol": "XAU/USD", "interval": "5min", "timezone": "Asia/Tehran"}),
    response(values=[BAR, dict(BAR, high="3099")]),
    response(values=[BAR, dict(BAR, close="NaN")]),
    response(values=[BAR, dict(BAR, datetime="2026-09-24 15:17:00")]),
    response(values=[BAR, dict(BAR, datetime="2999-09-24 15:20:00")]),
    response(values=[BAR, dict(BAR, volume="-1")]),
    response(values=[BAR, BAR]),
])
def test_corrupt_batch_is_rejected_without_partial_chart_or_cache(monkeypatch, bad):
    provider(monkeypatch, bad)
    with pytest.raises(history.DataUnavailable):
        request()
