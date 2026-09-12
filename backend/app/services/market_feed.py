"""
Live market ticks — real provider data only.

1. Twelve Data price WebSocket (true streaming).
2. If the socket is unavailable, a REST polling loop over real bars (also real data,
   just slower) is used. There is no synthetic tick generator in this codebase: when the
   provider cannot be reached the pipeline reports the failure and emits nothing.
"""
from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from datetime import datetime, timezone

import websockets

from app.config import Settings
from app.models import Tick, Timeframe
from app.services.history import DataUnavailable, load_history

REST_POLL_SECONDS = 20


async def twelve_data_ticks(settings: Settings) -> AsyncIterator[Tick]:
    """Streaming ticks. Raises [DataUnavailable] when a key is missing or the socket fails."""
    if not settings.has_market_key:
        raise DataUnavailable("AURUM_TWELVE_DATA_API_KEY تنظیم نشده است — تغذیه بازار غیرفعال است")

    url = f"wss://ws.twelvedata.com/v1/quotes/price?apikey={settings.twelve_data_api_key}"
    async with websockets.connect(url, ping_interval=20, ping_timeout=10) as socket:
        await socket.send(json.dumps({"action": "subscribe", "params": {"symbols": settings.market_symbol}}))
        async for raw in socket:
            message = json.loads(raw)
            event = message.get("event")
            if event == "heartbeat":
                await socket.send(json.dumps({"action": "heartbeat"}))
                continue
            if event in ("error", "disconnect"):
                raise DataUnavailable(message.get("message", "WebSocket provider error"))
            if event != "price":
                continue
            price = float(message["price"])
            timestamp = datetime.fromtimestamp(
                float(message.get("timestamp") or datetime.now(timezone.utc).timestamp()),
                timezone.utc,
            )
            # The price endpoint publishes a single mid price: bid == ask, and no spread is invented.
            yield Tick(
                symbol=settings.market_symbol,
                timestamp=timestamp,
                bid=price,
                ask=price,
                provider="twelve_data:ws",
            )


async def twelve_data_poll_ticks(settings: Settings) -> AsyncIterator[Tick]:
    """REST polling of real 1-minute bars; used when the socket is not available."""
    last_closed: datetime | None = None
    while True:
        candles = await load_history(settings, Timeframe.M1, output_size=10, use_cache=False)
        latest = candles[-1]
        if latest.timestamp != last_closed:
            last_closed = latest.timestamp
            yield Tick(
                symbol=settings.market_symbol,
                timestamp=latest.timestamp,
                bid=latest.close,
                ask=latest.close,
                provider="twelve_data:rest",
            )
        await asyncio.sleep(REST_POLL_SECONDS)


async def market_ticks(settings: Settings) -> AsyncIterator[Tick]:
    """
    Streaming first, REST polling as a *real-data* fallback.
    Both paths raise [DataUnavailable] instead of producing anything invented.
    """
    if not settings.has_market_key:
        raise DataUnavailable("کلید Twelve Data تنظیم نشده است — هیچ داده‌ای تولید نمی‌شود")
    try:
        async for tick in twelve_data_ticks(settings):
            yield tick
    except DataUnavailable:
        raise
    except Exception as exc:  # socket failed → keep going with real REST data
        async for tick in twelve_data_poll_ticks(settings):
            yield tick
