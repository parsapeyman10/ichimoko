import asyncio
import json
import math
import random
from datetime import datetime, timezone
from collections.abc import AsyncIterator
import websockets
from app.config import Settings
from app.models import Tick


async def twelve_data_ticks(settings: Settings) -> AsyncIterator[Tick]:
    if not settings.twelve_data_api_key:
        raise RuntimeError("AURUM_TWELVE_DATA_API_KEY is not configured")
    url = f"wss://ws.twelvedata.com/v1/quotes/price?apikey={settings.twelve_data_api_key}"
    async with websockets.connect(url, ping_interval=20, ping_timeout=10) as socket:
        await socket.send(json.dumps({"action": "subscribe", "params": {"symbols": settings.market_symbol}}))
        async for raw in socket:
            message = json.loads(raw)
            if message.get("event") != "price":
                continue
            price = float(message["price"])
            spread = 0.18
            timestamp = datetime.fromtimestamp(float(message.get("timestamp", datetime.now(timezone.utc).timestamp())), timezone.utc)
            yield Tick(symbol=settings.market_symbol, timestamp=timestamp, bid=price - spread / 2, ask=price + spread / 2, provider="twelve_data")


async def synthetic_ticks(settings: Settings) -> AsyncIterator[Tick]:
    """Deterministic-ish preview feed. Clearly marked and never used for execution."""
    price, n = 3358.42, 0
    while True:
        n += 1
        price += math.sin(n / 13) * 0.018 + random.uniform(-0.055, 0.055)
        spread = 0.18 + random.uniform(-0.02, 0.02)
        yield Tick(symbol=settings.market_symbol, bid=price - spread / 2, ask=price + spread / 2, provider="synthetic")
        await asyncio.sleep(0.5)
