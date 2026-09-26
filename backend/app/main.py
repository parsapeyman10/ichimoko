import sys as _sys
from pathlib import Path as _Path

# Allow `python backend/app/main.py` direct execution (Windows)
if str(_Path(__file__).resolve().parent.parent) not in _sys.path:
    _sys.path.insert(0, str(_Path(__file__).resolve().parent.parent))

import asyncio
import contextlib
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import ORJSONResponse

from app.config import get_settings
from app.models import (
    BrokerConfig,
    Candle,
    Impact,
    JournalEntry,
    NewsRequest,
    SmallAccountConfig,
    StrategyContext,
    StrategyRequest,
    Timeframe,
    TradeSignal,
)
from app.services import journal as journal_svc
from app.services.backtest import run_backtest, stress_test_from_trades
from app.services.broker import BROKERS, RECOMMENDED, get_broker
from app.services.candle_builder import CandleBuilder
from app.services.forward_test import run_forward_test
from app.services.execution_gate import OrderIntent, preflight as order_preflight, status as execution_status
from app.services.history import DataUnavailable, load_history
from app.services.market_feed import market_ticks
from app.services.news_feed import NewsAggregator
from app.services.persian_news import PersianNewsFeed
from app.services.web_news import WebNewsFeed
from app.services.crypto_scanner import CryptoScanner
from app.services.sentiment import SentimentEngine
from app.services.strategy import evaluate_scalp, explain_profitability
from app.services.ytd_trades import get_ytd_report

settings = get_settings()


def market_provider_label() -> str:
    """Which real feed is actually driving market data right now — never a fixed label."""
    return "twelve_data" if settings.has_market_key else "spot_fallback"


class MarketHub:
    """In-process live state for the real feed. Contains provider data only."""

    def __init__(self) -> None:
        self.subscribers: set[asyncio.Queue] = set()
        self.history: dict[Timeframe, deque[Candle]] = defaultdict(lambda: deque(maxlen=2000))
        self.latest: dict[Timeframe, Candle] = {}
        self.last_tick: dict[str, Any] | None = None
        self.feed_status: dict[str, Any] = {
            "state": "starting",
            "detail": None,
            "last_tick_at": None,
            "provider": market_provider_label(),
        }

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

    def set_status(self, state: str, detail: str | None = None) -> None:
        self.feed_status.update({"state": state, "detail": detail})
        self.publish({"type": "feed.status", "status": state, "detail": detail})

    def public_feed_status(self, now: datetime | None = None) -> dict[str, Any]:
        """A socket that remains open without quotes is NOT evidence of a live market."""
        result = self.feed_status.copy()
        last_tick = result.get("last_tick_at")
        clock = now or datetime.now(timezone.utc)
        if result["state"] in ("live", "polling") and (
                not isinstance(last_tick, datetime) or last_tick.tzinfo is None or
                not 0 <= (clock - last_tick).total_seconds() <= 90):
            result.update(state="stale", detail="آخرین تیک منبع قدیمی است؛ فید قابل اتکا نیست")
        return result


hub = MarketHub()
sentiment = SentimentEngine(settings)
news_aggregator = NewsAggregator(settings)
persian_news = PersianNewsFeed(settings)
web_news = WebNewsFeed(settings)
crypto_scanner = CryptoScanner(settings)


async def run_market_pipeline() -> None:
    """Consume real ticks; on failure report the failure and never emit invented data."""
    # Aggregate the real tick stream into the timeframes the terminal offers.
    builders = {
        timeframe: CandleBuilder(timeframe)
        for timeframe in (Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1)
    }
    backoff = 2
    while True:
        try:
            hub.set_status(
                "connecting",
                "اتصال به Twelve Data…" if settings.has_market_key else "اتصال به فید رایگان خودکار طلا (Swissquote/Gold-API)…",
            )
            async for tick in market_ticks(settings):
                source_state = "live" if tick.provider == "twelve_data:ws" else "polling"
                if tick.provider == "twelve_data:ws":
                    source_detail = None
                elif tick.provider == "twelve_data:rest":
                    source_detail = "تیک از کندل REST ناشر؛ قیمت درون کندل زنده نیست"
                else:
                    source_detail = "فید رایگان خودکار (بدون کلید) — Swissquote/Gold-API؛ نرخ‌دهی هر چند ثانیه، نه tick-by-tick"
                if hub.feed_status["state"] != source_state or hub.feed_status.get("provider") != tick.provider:
                    hub.set_status(source_state, source_detail)
                hub.last_tick = tick.model_dump(mode="json")
                hub.feed_status.update(
                    {"state": source_state, "detail": source_detail, "last_tick_at": datetime.now(timezone.utc),
                     "provider": tick.provider}
                )
                active_bars: dict[str, dict] = {}
                for timeframe, builder in builders.items():
                    completed, active = builder.ingest(tick)
                    hub.latest[timeframe] = active
                    if completed:
                        hub.history[timeframe].append(completed)
                        hub.publish({"type": "candle.closed", "payload": completed.model_dump(mode="json")})
                    active_bars[timeframe.value] = active.model_dump(mode="json")
                # One message per tick carrying every forming bar.
                hub.publish({"type": "market.update", "tick": hub.last_tick, "candles": active_bars})
                backoff = 2
        except asyncio.CancelledError:
            raise
        except DataUnavailable as exc:
            hub.set_status("unavailable", str(exc))
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)
        except Exception as exc:
            # A network error string may contain the request URL and its secret API key.
            hub.set_status("error", f"اختلال در پردازش فید ({type(exc).__name__})")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)


async def run_news_pipeline() -> None:
    """Poll a licensed source, classify concurrently, emit alerts only for real articles."""
    while True:
        try:
            articles = await news_aggregator.fetch()
            if articles:
                results = await asyncio.gather(*(sentiment.analyze(article) for article in articles[:20]))
                for article, result in zip(articles, results):
                    hub.publish(
                        {
                            "type": "news.sentiment",
                            "article": article.model_dump(mode="json"),
                            "sentiment": result.model_dump(mode="json"),
                            "alert": result.impact is Impact.HIGH and result.confidence >= 75,
                        }
                    )
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
    title="Trading API",
    version="1.0.0",
    default_response_class=ORJSONResponse,
    description=(
        "Real-data XAU/USD market, sentiment and closed-bar signal service. "
        "هیچ داده ساختگی تولید نمی‌شود؛ در نبود دیتای واقعی، خطای صریح برمی‌گردد."
    ),
    lifespan=lifespan,
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


# ─── status ────────────────────────────────────────────────────────────
@app.get("/api/v1/health")
async def health():
    feed = hub.public_feed_status()
    return {
        "status": "ok",
        "environment": settings.environment,
        "market_data": {
            "provider": market_provider_label(),
            "configured": settings.has_market_key,
            "feed_state": feed["state"],
            "detail": feed["detail"],
            "last_tick_at": feed["last_tick_at"],
        },
        "news": news_aggregator.status(),
        "subscribers": len(hub.subscribers),
        "timestamp": datetime.now(timezone.utc),
    }


@app.get("/api/v1/data/status")
async def data_status():
    """Explicit data-truth endpoint: what is live, what is cached, and what is missing."""
    per_timeframe = {}
    for timeframe in Timeframe:
        records = len(hub.history[timeframe])
        if timeframe in hub.latest:
            records += 1
        per_timeframe[timeframe.value] = {
            "live_candles": records,
            "last_bar": hub.latest[timeframe].timestamp if timeframe in hub.latest else None,
        }
    return {
        "provider": market_provider_label(),
        "api_key_configured": settings.has_market_key,
        "symbol": settings.market_symbol,
        "feed": hub.public_feed_status(),
        "timeframes": per_timeframe,
        "policy": (
            "فقط داده واقعی منتشر می‌شود. بدون کلید Twelve Data، یک فید رایگان و کاملاً خودکار "
            "(Swissquote/Gold-API) جای آن را می‌گیرد — نیازی به تنظیم دستی نیست. اگر اینترنت قطع باشد "
            "یا هیچ منبع واقعی در دسترس نباشد، اندپوینت‌ها خطا برمی‌گردانند و هیچ کندل، قیمت یا خبری "
            "ساخته نمی‌شود."
        ),
    }


# ─── market ────────────────────────────────────────────────────────────
@app.get("/api/v1/market/{timeframe}/candles", response_model=list[Candle])
async def candles(timeframe: Timeframe, limit: int = Query(300, ge=1, le=2000)):
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if records:
        return records[-limit:]
    # Fall back to real provider history (still real data, just fetched on demand).
    try:
        fetched = await load_history(settings, timeframe, output_size=limit)
    except DataUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return fetched[-limit:]


# ─── news ──────────────────────────────────────────────────────────────
@app.post("/api/v1/news/analyze")
async def analyze_news(request: NewsRequest):
    return await sentiment.analyze(request)


@app.get("/api/v1/news/headlines")
async def news_headlines():
    articles = await news_aggregator.fetch()
    return {
        "status": news_aggregator.status(),
        "articles": [a.model_dump(mode="json") for a in articles],
    }


@app.get("/api/v1/news/calendar")
async def calendar():
    events = await news_aggregator.fetch_calendar()
    return {"status": news_aggregator.status(), "events": events}


@app.get("/api/v1/news/fa")
async def persian_headlines():
    """Custom licensed Persian feed (kept for backwards compatibility)."""
    return await persian_news.snapshot()


@app.get("/api/v1/news/web")
async def web_headlines():
    """Public publisher RSS: attributed short excerpts; partial coverage cannot clear the guard."""
    return await web_news.snapshot()


@app.get("/api/v1/crypto/candidates")
async def crypto_candidates():
    """Read-only, strict spot-market screening; NOT pump prediction or a trade intent."""
    return await crypto_scanner.snapshot()


# ─── real execution boundary (intentionally disabled until independently audited) ──
@app.get("/api/v1/execution/status")
async def get_execution_status():
    return execution_status()


@app.post("/api/v1/execution/preflight")
async def check_order_preflight(intent: OrderIntent):
    return order_preflight(intent)


@app.post("/api/v1/execution/orders")
async def submit_real_order(intent: OrderIntent):
    # No broker signing, network call, account credentials or acceptance of client-provided
    # 'safe' flags. This endpoint cannot execute even if a malicious client calls it.
    raise HTTPException(status_code=503, detail="ارسال سفارش واقعی غیرفعال است؛ اتصال احراز هویت‌شده و حسابرسی‌شده نصب نشده است")


# ─── strategy / risk ───────────────────────────────────────────────────
@app.post("/api/v1/strategy/evaluate")
async def evaluate_strategy(request: StrategyRequest):
    signal = evaluate_scalp(request.candles, request.context)
    with contextlib.suppress(Exception):
        journal_svc.add_signal(signal, request.candles[-1].timeframe)
    return signal


@app.post("/api/v1/strategy/should-exit")
async def check_exit(signal: dict, candles_since_entry: list[Candle], kijun: float):
    from app.services.strategy import should_exit

    ts = TradeSignal(**signal)
    do_exit, reason = should_exit(ts, candles_since_entry, kijun)
    return {"should_exit": do_exit, "reason": reason}


@app.post("/api/v1/risk/calculate")
async def risk_calculate(cfg: SmallAccountConfig, entry: float, stop_loss: float):
    stop_dist = abs(entry - stop_loss) or 1.0
    risk_amount = cfg.balance * cfg.risk_percent / 100
    position_oz = risk_amount / stop_dist
    notional = position_oz * entry
    lots = position_oz / 100
    min_lot_oz = 1.0  # 0.01 lot on gold
    executable_oz = max(position_oz, min_lot_oz)
    actual_risk = executable_oz * stop_dist
    return {
        "balance": cfg.balance,
        "risk_percent": cfg.risk_percent,
        "risk_amount": round(risk_amount, 2),
        "entry": entry,
        "stop_loss": stop_loss,
        "stop_distance": round(stop_dist, 2),
        "position_oz": round(position_oz, 3),
        "position_lots": round(lots, 3),
        "notional": round(notional, 2),
        "min_lot_oz": min_lot_oz,
        "executable_oz": round(executable_oz, 3),
        "executable_risk_usd": round(actual_risk, 2),
        "executable_risk_pct": round(actual_risk / cfg.balance * 100, 2) if cfg.balance else None,
        "warning": (
            f"حجم دقیق {position_oz:.3f} انس زیر حداقل لات بروکر ({min_lot_oz} انس) است؛ "
            f"کوچک‌ترین معامله ممکن {actual_risk:.2f}$ ریسک دارد "
            f"({actual_risk / cfg.balance * 100:.2f}% حساب)."
            if position_oz < min_lot_oz
            else None
        ),
    }


# ─── journal ───────────────────────────────────────────────────────────
@app.get("/api/v1/journal")
async def get_journal(limit: int = Query(50, ge=1, le=200)):
    entries = journal_svc.list_entries(limit)
    return {"entries": [e.model_dump(mode="json") for e in entries], "stats": journal_svc.stats()}


@app.post("/api/v1/journal/close/{entry_id}")
async def close_journal(entry_id: str, exit_price: float, reason: str = "manual"):
    entry = journal_svc.close_entry(entry_id, exit_price, reason)
    if not entry:
        return {"error": "not found or already closed"}
    return entry


@app.get("/api/v1/journal/stats")
async def journal_stats():
    return journal_svc.stats()


# ─── brokers (reference data) ──────────────────────────────────────────
@app.get("/api/v1/brokers")
async def list_brokers():
    return {
        "recommended": RECOMMENDED.model_dump(),
        "brokers": [b.model_dump() for b in BROKERS],
        "note": (
            "این‌ها مشخصات واقعی بروکرهاست (اسپرد/کمیسیون/حداقل لات). هیچ عملکردی به آن‌ها نسبت "
            "داده نمی‌شود؛ هزینه‌ها باید با شرایط حساب خودت کنترل شود."
        ),
    }


@app.get("/api/v1/brokers/{broker_name}/cost")
async def broker_cost(
    broker_name: str,
    position_oz: float = 1.0,
    entry: float = 3350,
    holds_days: int = 0,
    direction: str = "BUY",
):
    from app.services.broker import calculate_execution_cost

    return calculate_execution_cost(position_oz, entry, get_broker(broker_name), holds_days, direction)


# ─── backtest (real candles only) ──────────────────────────────────────
async def _backtest_payload(
    timeframe: str,
    bars: int,
    initial_balance: float,
    risk_percent: float,
    spread: float,
    commission_per_oz: float,
    broker_name: str | None,
    min_position_oz: float,
    use_trailing: bool,
    source: str = "auto",
) -> dict:
    tf = Timeframe(timeframe) if timeframe in {t.value for t in Timeframe} else Timeframe.M5
    if broker_name:
        broker: BrokerConfig = get_broker(broker_name)
        spread = broker.spread_gold
        commission_per_oz = broker.commission_per_oz or commission_per_oz
    if source == "futures_proxy":
        # Explicit opt-in: real, deep COMEX gold futures (GC=F) history to validate the rule
        # engine immediately. Never silently mixed with XAU/USD spot — always labeled distinctly.
        import httpx

        from app.services.futures_history import fetch_futures_history

        try:
            async with httpx.AsyncClient(timeout=20) as client:
                candles = await fetch_futures_history(client, tf, limit=bars)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        data_source_label = (
            "gc_futures_proxy — کندل‌های واقعی فیوچرز طلای کوموکس (GC=F)، نه اسپات XAU/USD؛ "
            "فقط برای اعتبارسنجی قواعد روی تاریخچهٔ عمیق تا زمانی‌که فید اسپات تاریخچهٔ کافی جمع کند"
        )
    else:
        try:
            candles = await load_history(settings, tf, output_size=bars)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        data_source_label = market_provider_label()
    result = run_backtest(
        candles,
        initial_balance=max(10.0, min(initial_balance, 100000.0)),
        risk_percent=max(0.1, min(risk_percent, 5.0)),
        spread=spread,
        commission_per_oz=commission_per_oz,
        min_position_oz=min_position_oz,
        use_trailing=use_trailing,
        data_source=data_source_label,
    )
    if broker_name:
        result["broker"] = get_broker(broker_name).model_dump()
    return result


@app.get("/api/v1/backtest/run")
async def backtest_run_get(
    timeframe: str = "5m",
    bars: int = Query(1500, ge=220, le=5000),
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    min_position_oz: float = 1.0,
    broker_name: str | None = None,
    use_trailing: bool = True,
    source: str = Query("auto", pattern="^(auto|futures_proxy)$"),
):
    """Replay the live strategy over real candles (auto spot/Twelve Data, or opt-in GC=F futures)."""
    return await _backtest_payload(
        timeframe, bars, initial_balance, risk_percent, spread, commission_per_oz, broker_name, min_position_oz, use_trailing, source
    )


@app.post("/api/v1/backtest/run")
async def backtest_run_post(
    timeframe: str = "5m",
    bars: int = Query(1500, ge=220, le=5000),
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    min_position_oz: float = 1.0,
    broker_name: str | None = None,
    use_trailing: bool = True,
    source: str = Query("auto", pattern="^(auto|futures_proxy)$"),
):
    return await _backtest_payload(
        timeframe, bars, initial_balance, risk_percent, spread, commission_per_oz, broker_name, min_position_oz, use_trailing, source
    )


@app.get("/api/v1/backtest/forward")
async def backtest_forward(
    timeframe: str = "5m",
    bars: int = Query(1500, ge=400, le=5000),
    split: float = 0.7,
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    use_trailing: bool = True,
):
    """Walk-forward on real candles: older part in-sample, newer part out-of-sample."""
    try:
        return await run_forward_test(
            settings,
            timeframe=timeframe,
            output_size=bars,
            split=split,
            initial_balance=initial_balance,
            risk_percent=risk_percent,
            spread=spread,
            commission_per_oz=commission_per_oz,
            use_trailing=use_trailing,
        )
    except DataUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/api/v1/backtest/forward")
async def backtest_forward_post(
    timeframe: str = "5m",
    bars: int = Query(1500, ge=400, le=5000),
    split: float = 0.7,
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    use_trailing: bool = True,
):
    return await backtest_forward(
        timeframe, bars, split, initial_balance, risk_percent, spread, commission_per_oz, use_trailing
    )


@app.get("/api/v1/backtest/trades-ytd")
async def trades_ytd(timeframe: str = "5m", year: int | None = None):
    """Real trade list for the requested year, bounded by what the API plan can deliver."""
    try:
        return await get_ytd_report(settings, timeframe=timeframe, year=year)
    except DataUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.get("/api/v1/backtest/profitability")
async def profitability(timeframe: Timeframe = Timeframe.M5, limit: int = Query(300, ge=50, le=2000)):
    """Statistical description of the *real* candles currently available."""
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 60:
        try:
            records = await load_history(settings, timeframe, output_size=limit)
        except DataUnavailable as exc:
            raise HTTPException(
                status_code=503,
                detail=f"{exc} — برای این تحلیل حداقل ۶۰ کندل واقعی لازم است.",
            ) from exc
    return explain_profitability(records[-limit:])


@app.get("/api/v1/backtest/history")
async def backtest_history(days: int = Query(365, ge=30, le=2000)):
    """Real daily candles straight from the provider (used for long-range charting)."""
    try:
        daily = await load_history(settings, Timeframe.D1, output_size=min(days, 5000))
    except DataUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "data_source": market_provider_label(),
        "candles": [
            {"time": c.timestamp.isoformat(), "open": c.open, "high": c.high, "low": c.low, "close": c.close, "volume": c.volume}
            for c in daily
        ],
    }


@app.get("/api/v1/risk/stress-test")
async def stress_test(
    timeframe: str = "5m",
    bars: int = Query(1000, ge=400, le=5000),
    initial_balance: float = 100,
    spread: float = 0.30,
    commission_per_oz: float = 0.05,
    runs: int = Query(5000, ge=500, le=50000),
):
    """
    Risk analysis built by resampling the REAL trades of a real-data backtest.
    No win-rate assumption is ever injected.
    """
    payload = await _backtest_payload(timeframe, bars, initial_balance, 0.5, spread, commission_per_oz, None, 1.0, True)
    report = stress_test_from_trades(payload.get("trades", []), initial_balance, runs=runs)
    report["data_source"] = market_provider_label()
    report["bars_used"] = payload.get("bars")
    return report


@app.post("/api/v1/predict/next")
async def predict_next(request: StrategyRequest):
    from app.services.predictor import explain_prediction

    return explain_prediction(request.candles, request.context)


@app.get("/api/v1/predict/next")
async def predict_next_get(timeframe: Timeframe = Timeframe.M5, limit: int = 220):
    from app.services.predictor import explain_prediction

    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        try:
            records = await load_history(settings, timeframe, output_size=limit)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    return explain_prediction(records[-220:], StrategyContext())


@app.post("/api/v1/mtf/analyze")
async def mtf_analyze(request: StrategyRequest):
    from app.services.features import build_features
    from app.services.mtf_analyzer import mtf_from_m1

    mtf = mtf_from_m1(request.candles)
    feats = build_features(request.candles, request.context)
    return {
        "mtf": mtf,
        "mtf_features": {k: v for k, v in feats.items() if k.startswith("mtf_")},
        "features_used": len(feats),
    }


@app.get("/api/v1/mtf/status")
async def mtf_status(timeframe: Timeframe = Timeframe.M5, limit: int = 220):
    from app.services.mtf_analyzer import mtf_from_m1

    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 60:
        try:
            records = await load_history(settings, timeframe, output_size=limit)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    return mtf_from_m1(records[-220:])


@app.get("/api/v1/mtf/explain")
async def mtf_explain(timeframe: Timeframe = Timeframe.M5):
    from app.services.mtf_analyzer import mtf_from_m1
    from app.services.predictor import explain_prediction

    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        try:
            records = await load_history(settings, timeframe, output_size=220)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    records = records[-220:]
    mtf = mtf_from_m1(records)
    pred = explain_prediction(records, StrategyContext())
    return {
        "mtf": mtf,
        "prediction": {
            "direction": pred["expected_direction"],
            "confidence": pred["confidence"],
            "ev_final": pred.get("expected_value_R_final"),
            "is_actionable": pred["is_actionable"],
        },
    }


@app.get("/api/v1/features/list")
async def features_list():
    from app.services.features import feature_names

    return {"count": len(feature_names()), "features": feature_names()}


@app.post("/api/v1/traders/ensemble")
async def traders_ensemble_post(request: StrategyRequest):
    from app.services.features import build_features
    from app.services.predictor import predict_next as base_pred
    from app.services.top_traders import ensemble as elite_ensemble

    feats = build_features(request.candles, request.context)
    base = base_pred(request.candles, request.context)
    elite = elite_ensemble(feats, base_ev=base["expected_value_R"], base_direction=base["expected_direction"])
    return {"base": base, "elite": elite, "features_used": len(feats)}


@app.get("/api/v1/traders/ensemble")
async def traders_ensemble_get(timeframe: Timeframe = Timeframe.M5):
    from app.services.features import build_features
    from app.services.predictor import predict_next as base_pred
    from app.services.top_traders import ensemble as elite_ensemble

    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        try:
            records = await load_history(settings, timeframe, output_size=220)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    records = records[-220:]
    ctx = StrategyContext()
    feats = build_features(records, ctx)
    base = base_pred(records, ctx)
    elite = elite_ensemble(feats, base_ev=base["expected_value_R"], base_direction=base["expected_direction"])
    return {"base": base, "elite": elite, "features_used": len(feats)}


@app.get("/api/v1/traders/list")
async def traders_list():
    """The five complementary analysis schools and their fixed weights (no performance claims)."""
    from app.services.top_traders import TRADERS_META, TRADER_WEIGHTS

    return {
        "traders": [
            {
                "id": key,
                "name": meta["name"],
                "full": meta["full"],
                "style": meta["style"],
                "desc": meta["desc"],
                "weight": TRADER_WEIGHTS[key],
            }
            for key, meta in TRADERS_META.items()
        ],
        "philosophy": (
            "این‌ها سبک‌های تحلیلی با وزن‌های ثابت هستند، نه ادعای عملکرد. هیچ عدد بازدهی بدون "
            "بک‌تست روی دیتای واقعی گزارش نمی‌شود."
        ),
    }


@app.get("/api/v1/features/explain")
async def features_explain(timeframe: Timeframe = Timeframe.M5):
    from app.services.features import build_features

    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        try:
            records = await load_history(settings, timeframe, output_size=220)
        except DataUnavailable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    records = records[-220:]
    feats = build_features(records, StrategyContext())
    top = sorted(feats.items(), key=lambda item: abs(item[1]), reverse=True)[:15]
    return {"features": feats, "top": top}


@app.websocket("/ws/v1/market/xauusd")
async def market_socket(websocket: WebSocket):
    await websocket.accept()
    queue = hub.subscribe()
    try:
        feed = hub.public_feed_status()
        await websocket.send_json(
            {
                "type": "connected",
                "symbol": settings.market_symbol,
                "provider": market_provider_label(),
                "feed_state": feed["state"],
                "detail": feed["detail"],
            }
        )
        while True:
            message = await queue.get()
            await websocket.send_json(message)
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        hub.unsubscribe(queue)


if __name__ == "__main__":
    import uvicorn as _uvicorn

    print("\n✅ Trading API — اجرا:")
    print("   uvicorn app.main:app --reload --app-dir backend --host 0.0.0.0 --port 8000")
    print("   Docs → http://127.0.0.1:8000/docs\n")
    _uvicorn.run(app, host="0.0.0.0", port=8000)
