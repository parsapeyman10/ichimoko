import asyncio
import contextlib
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any
from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import ORJSONResponse
from app.config import get_settings
from app.models import Candle, Impact, NewsRequest, StrategyRequest, Timeframe
from app.services.candle_builder import CandleBuilder
from app.services.market_feed import synthetic_ticks, twelve_data_ticks
from app.services.news_feed import NewsAggregator
from app.services.sentiment import SentimentEngine
from app.services.strategy import evaluate_scalp

settings = get_settings()


class MarketHub:
    """In-process preview hub. Replace with Redis Streams/NATS between replicas."""

    def __init__(self):
        self.subscribers: set[asyncio.Queue] = set()
        self.history: dict[Timeframe, deque[Candle]] = defaultdict(lambda: deque(maxlen=2000))
        self.latest: dict[Timeframe, Candle] = {}
        self.last_tick: dict[str, Any] | None = None

    def subscribe(self) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=128)
        self.subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue) -> None:
        self.subscribers.discard(queue)

    def publish(self, message: dict[str, Any]) -> None:
        for queue in tuple(self.subscribers):
            if queue.full():
                with contextlib.suppress(asyncio.QueueEmpty):
                    queue.get_nowait()  # slow consumers receive the latest state, not stale ticks
            with contextlib.suppress(asyncio.QueueFull):
                queue.put_nowait(message)


hub = MarketHub()
sentiment = SentimentEngine(settings)


async def run_market_pipeline() -> None:
    builders = {Timeframe.M1: CandleBuilder(Timeframe.M1), Timeframe.M5: CandleBuilder(Timeframe.M5)}
    backoff = 1
    while True:
        try:
            source = synthetic_ticks(settings) if settings.use_synthetic_feed else twelve_data_ticks(settings)
            async for tick in source:
                hub.last_tick = tick.model_dump(mode="json")
                candles = {}
                for timeframe, builder in builders.items():
                    completed, active = builder.ingest(tick)
                    hub.latest[timeframe] = active
                    candles[timeframe.value] = active.model_dump(mode="json")
                    if completed:
                        hub.history[timeframe].append(completed)
                        hub.publish({"type": "candle.closed", "payload": completed.model_dump(mode="json")})
                hub.publish({"type": "market.update", "tick": hub.last_tick, "candles": candles})
                backoff = 1
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            hub.publish({"type": "feed.status", "status": "reconnecting", "detail": type(exc).__name__})
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30)


async def run_news_pipeline() -> None:
    """Poll a licensed source, classify concurrently, and emit material alerts."""
    aggregator = NewsAggregator(settings)
    while True:
        try:
            articles = await aggregator.fetch_fmp()
            if articles:
                results = await asyncio.gather(*(sentiment.analyze(article) for article in articles[:20]))
                for article, result in zip(articles, results):
                    event = {
                        "type": "news.sentiment", "article": article.model_dump(mode="json"),
                        "sentiment": result.model_dump(mode="json"),
                        "alert": result.impact is Impact.HIGH and result.confidence >= 75,
                    }
                    hub.publish(event)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            hub.publish({"type": "news.status", "status": "degraded", "detail": type(exc).__name__})
        await asyncio.sleep(30)


@asynccontextmanager
async def lifespan(_: FastAPI):
    tasks = [
        asyncio.create_task(run_market_pipeline(), name="market-pipeline"),
        asyncio.create_task(run_news_pipeline(), name="news-pipeline"),
    ]
    yield
    for task in tasks:
        task.cancel()
    for task in tasks:
        with contextlib.suppress(asyncio.CancelledError):
            await task


app = FastAPI(
    title="Aurum Edge API", version="0.1.0", default_response_class=ORJSONResponse,
    description="Real-time XAU/USD market, sentiment, and closed-bar signal service.", lifespan=lifespan,
)
app.add_middleware(
    CORSMiddleware, allow_origins=settings.allowed_origins,
    allow_credentials=True, allow_methods=["GET", "POST"], allow_headers=["*"],
)


@app.get("/api/v1/health")
async def health():
    return {
        "status": "ok", "environment": settings.environment,
        "feed": "synthetic" if settings.use_synthetic_feed else "twelve_data",
        "subscribers": len(hub.subscribers), "timestamp": datetime.now(timezone.utc),
    }


@app.get("/api/v1/market/{timeframe}/candles", response_model=list[Candle])
async def candles(timeframe: Timeframe, limit: int = Query(300, ge=1, le=2000)):
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    return records[-limit:]


@app.post("/api/v1/news/analyze")
async def analyze_news(request: NewsRequest):
    result = await sentiment.analyze(request)
    # High-impact alerts should be put on a durable notification topic in production.
    return result


@app.post("/api/v1/strategy/evaluate")
async def evaluate_strategy(request: StrategyRequest):
    return evaluate_scalp(request.candles, request.context)


@app.websocket("/ws/v1/market/xauusd")
async def market_socket(websocket: WebSocket):
    await websocket.accept()
    queue = hub.subscribe()
    try:
        await websocket.send_json({
            "type": "connected", "symbol": settings.market_symbol,
            "feed": "synthetic" if settings.use_synthetic_feed else "twelve_data",
        })
        while True:
            message = await queue.get()
            await websocket.send_json(message)
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        hub.unsubscribe(queue)
