import asyncio

import httpx

from app.config import Settings
from app.services.integration_probe import _probe_openai, _probe_twelve, _run


def test_twelve_probe_validates_contract_without_returning_key():
    async def scenario():
        def handler(request):
            assert request.url.path == "/time_series"
            assert request.url.params["apikey"] == "private-test-key"
            return httpx.Response(200, json={
                "meta": {"symbol": "XAU/USD", "interval": "1day", "timezone": "UTC"},
                "values": [{"datetime": "2026-09-29", "close": "3800"}],
            })

        settings = Settings(_env_file=None, twelve_data_api_key="private-test-key")
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await _probe_twelve(settings, client)
        assert result["state"] == "ok"
        assert "private-test-key" not in repr(result)

    asyncio.run(scenario())


def test_twelve_probe_rejected_key_is_safe_and_explicit():
    async def scenario():
        def handler(_request):
            return httpx.Response(200, json={"status": "error", "code": 401, "message": "private-test-key"})

        settings = Settings(_env_file=None, twelve_data_api_key="private-test-key")
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            name, result = await _run("twelve_data", lambda http: _probe_twelve(settings, http), client)
        assert name == "twelve_data"
        assert result["state"] == "error"
        assert "private-test-key" not in repr(result)
        assert "کلید" in result["error"]

    asyncio.run(scenario())


def test_openai_probe_rejects_query_bearing_base_without_network():
    async def scenario():
        settings = Settings(
            _env_file=None,
            openai_api_key="private-test-key",
            openai_base_url="https://relay.example.org/v1?token=private-test-key",
        )
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _request: httpx.Response(500))) as client:
            result = await _probe_openai(settings, client)
        assert result["state"] == "error"
        assert "private-test-key" not in repr(result)

    asyncio.run(scenario())
