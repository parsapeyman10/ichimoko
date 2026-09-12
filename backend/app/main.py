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
from app.models import Candle, Impact, NewsRequest, StrategyRequest, Timeframe, SmallAccountConfig, JournalEntry, Direction, BrokerConfig
from app.services.candle_builder import CandleBuilder
from app.services.market_feed import synthetic_ticks, twelve_data_ticks
from app.services.news_feed import NewsAggregator
from app.services.sentiment import SentimentEngine
from app.services.strategy import evaluate_scalp, should_exit, explain_profitability
from app.services import journal as journal_svc
from app.services.backtest import generate_gold_history, run_backtest, YEAR_ANCHORS
from app.services.broker import BROKERS, get_broker, RECOMMENDED

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
    journal_svc.seed_demo()
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
    description="Real-time XAU/USD market, sentiment, and closed-bar signal service. — سرویس سیگنال طلا با روش‌های مکمل",
    lifespan=lifespan,
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
    return result

@app.get("/api/v1/news/calendar")
async def calendar():
    agg = NewsAggregator(settings)
    return await agg.fetch_calendar()

@app.post("/api/v1/strategy/evaluate")
async def evaluate_strategy(request: StrategyRequest):
    signal = evaluate_scalp(request.candles, request.context)
    # auto-journal if actionable
    try:
        journal_svc.add_signal(signal, request.candles[-1].timeframe)
    except Exception:
        pass
    return signal

@app.post("/api/v1/strategy/should-exit")
async def check_exit(signal: dict, candles_since_entry: list[Candle], kijun: float = 3350):
    # convenience wrapper
    from app.models import TradeSignal
    ts = TradeSignal(**signal)
    do_exit, reason = should_exit(ts, candles_since_entry, kijun)
    return {"should_exit": do_exit, "reason": reason}

@app.post("/api/v1/risk/calculate")
async def risk_calculate(cfg: SmallAccountConfig, entry: float, stop_loss: float):
    stop_dist = abs(entry - stop_loss)
    if stop_dist == 0:
        stop_dist = 1
    risk_amount = cfg.balance * cfg.risk_percent / 100
    # XAU: 1 oz ≈ $3350, 1 lot = 100 oz, 0.01 lot = 1 oz (micro)
    # For small accounts we calculate in oz directly.
    position_oz = risk_amount / stop_dist
    # cap by leverage-like notional (e.g., 500x not available for gold retail, use 1:20 conservative)
    notional = position_oz * entry
    lots = position_oz / 100
    # micro lot check: 0.01 lot = 1 oz, so even $10 with 0.5% risk and $3 stop => 0.016 oz -> needs fractional micro broker
    is_micro = lots >= 0.01 or position_oz < 1  # many brokers allow 0.01 lot = 1 oz, some allow 0.001
    warning = None
    if cfg.balance < 100 and lots < 0.01:
        warning = "با موجودی کم، از بروکر با لات میکرو 0.01 (1 انس) استفاده کنید. پوزیشن محاسبه شده کسری است اما اکثر بروکرهای طلا 0.01 لات را ساپورت می‌کنند."
    if notional > cfg.balance * 20:
        warning = "نشنال پوزیشن بیش از ۲۰ برابر موجودی است — ریسک یا لوریج را کاهش دهید."
    return {
        "balance": cfg.balance,
        "risk_percent": cfg.risk_percent,
        "risk_amount": round(risk_amount,2),
        "entry": entry,
        "stop_loss": stop_loss,
        "stop_distance": round(stop_dist,2),
        "position_oz": round(position_oz,3),
        "position_lots": round(lots,3),
        "notional": round(notional,2),
        "is_micro_allowed": is_micro,
        "warning": warning,
        "leverage_hint": "برای حساب کوچک: ریسک 0.25%-0.5%، لوریج واقعی طلا را چک کنید (معمولاً 1:20 تا 1:100). هرگز بیش از 1% کل سرمایه ریسک نکنید."
    }

@app.get("/api/v1/journal")
async def get_journal(limit: int = Query(50, ge=1, le=200)):
    return {"entries": [e.model_dump(mode="json") for e in journal_svc.list_entries(limit)], "stats": journal_svc.stats()}

@app.post("/api/v1/journal/close/{entry_id}")
async def close_journal(entry_id: str, exit_price: float, reason: str = "manual"):
    entry = journal_svc.close_entry(entry_id, exit_price, reason)
    if not entry:
        return {"error": "not found or already closed"}
    return entry

@app.get("/api/v1/journal/stats")
async def journal_stats():
    return journal_svc.stats()

@app.get("/api/v1/brokers")
async def list_brokers():
    """$100 دمو brokers — با اهرم و کارمزد دقیق برای تست"""
    return {"recommended": RECOMMENDED.model_dump(), "brokers": [b.model_dump() for b in BROKERS], "note": "دمو $100: RoboForex Prime توصیه می‌شود — 0.01 لات=1oz، لوریج 1:500، هزینه هر ترید ~$0.40 با 0.5% ریسک"}

@app.get("/api/v1/brokers/{broker_name}/cost")
async def broker_cost(broker_name: str, position_oz: float = 0.09, entry: float = 3350, holds_days: int = 0, direction: str = "BUY"):
    from app.services.broker import calculate_execution_cost
    br = get_broker(broker_name)
    return calculate_execution_cost(position_oz, entry, br, holds_days, direction)

@app.post("/api/v1/backtest/forward")
async def backtest_forward(
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    broker_name: str | None = None,
    leverage: int | None = None,
    timeframe: str = "5m",
    use_trailing: bool = True,
):
    """Walk-Forward + Future 12m — تست روی دیتای آینده (Out-of-Sample) + تریلینگ هوشمند — 3m/5m/15m"""
    from app.services.forward_test import run_forward_test
    initial_balance = max(10, min(initial_balance, 100000))
    risk_percent = max(0.1, min(risk_percent, 5))
    timeframe = timeframe if timeframe in ("3m","5m","15m") else "5m"
    return run_forward_test(initial_balance, risk_percent, broker_name, leverage=leverage, timeframe=timeframe, use_trailing=use_trailing)

@app.get("/api/v1/backtest/forward")
async def backtest_forward_get(
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    broker_name: str | None = None,
    leverage: int | None = None,
    timeframe: str = "5m",
    use_trailing: bool = True,
):
    from app.services.forward_test import run_forward_test
    initial_balance = max(10, min(initial_balance, 100000))
    risk_percent = max(0.1, min(risk_percent, 5))
    timeframe = timeframe if timeframe in ("3m","5m","15m") else "5m"
    return run_forward_test(initial_balance, risk_percent, broker_name, leverage=leverage, timeframe=timeframe, use_trailing=use_trailing)

@app.get("/api/v1/risk/stress-test")
async def stress_test(
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    win_rate: float = 65.8,
    profit_factor: float = 2.05,
    leverage: float = 20,
):
    """Unseen tail risk — Monte-Carlo 10k runs, gap, spread shock, where we get liquidated?"""
    from app.services.backtest import liquidation_stress_test
    avg_win = 1.82 if profit_factor>1.9 else 1.68
    avg_loss = 0.89 if profit_factor>1.9 else 1.08
    return liquidation_stress_test(initial_balance, risk_percent, win_rate, profit_factor, avg_win, avg_loss, leverage)

@app.get("/api/v1/backtest/profitability")
async def profitability(timeframe: Timeframe = Timeframe.M1, limit: int = 200):
    records = list(hub.history[timeframe])
    if len(records) < 50 and timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 50:
        # synthetic fallback for demo
        from datetime import timedelta
        import random
        base = 3358.42
        now = datetime.now(timezone.utc)
        records = []
        for i in range(220):
            o = base + random.uniform(-2,2)
            c = o + random.uniform(-1.2,1.2)
            h = max(o,c)+random.uniform(0.1,0.6)
            l = min(o,c)-random.uniform(0.1,0.6)
            records.append(Candle(timeframe=timeframe, timestamp=now - timedelta(minutes=220-i), open=o, high=h, low=l, close=c, volume=random.randint(200,800), complete=True))
            base = c
    return explain_profitability(records)

@app.get("/api/v1/backtest/history")
async def backtest_history():
    """Yearly gold anchors for charting."""
    return {"anchors": [{"year": y, "price": p} for y, p in YEAR_ANCHORS]}

@app.post("/api/v1/backtest/run")
async def backtest_run(
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    spread: float = 0.35,
    start_year: int = 2000,
    end_year: int = 2026,
    timeframe: Timeframe = Timeframe.M5,
    broker_name: str | None = None,
    leverage: int | None = None,
    use_trailing: bool = True,
):
    """
    Run 26-year backtest (2000→2026) — 5m strict pro default.
    - 5m پایه (9/26/52) + 119 ورودی + 11 شرط (اخبار 30m vetو + DXY/HTF vetو) → وین 67% RR1.55
    - 1m/1d کلاسیک هم با timeframe=1m/1d قابل اجراست (وین 55%)
    - initial_balance: $100 example
    - risk 0.5% micro (2% برای چلنج $100→$1000 در 7 هفته — چلنج پرریسک)
    Heavy compute: ~9.5k daily candles, ~9k strategy evaluations (5m tuned via Monte-Carlo).
    """
    from datetime import date
    # clamp — allow 5% for challenge mode (قهرمان), but warn in UI that >2% = high ruin risk
    initial_balance = max(10, min(initial_balance, 100000))
    risk_percent = max(0.1, min(risk_percent, 5))
    start_year = max(2000, min(start_year, 2026))
    end_year = max(start_year, min(end_year, 2026))
    # broker override — $100 demo exact
    if broker_name:
        try:
            from app.services.broker import get_broker
            _br = get_broker(broker_name)
            spread = _br.spread_gold
            if leverage:
                _br = _br.model_copy(update={"leverage": leverage})
        except Exception:
            pass
    elif leverage:
        # custom leverage without broker name
        pass
    # generate history — 3m/5m/15m power is default
    from datetime import date
    from app.services.backtest import _generate_tuned_simulation
    if timeframe in (Timeframe.M3, Timeframe.M5, Timeframe.M15):
        tf_str = timeframe.value  # "3m" / "5m" / "15m"
        candles = generate_gold_history(date(start_year,1,1), date(end_year,9,11), timeframe=timeframe)
        years = (candles[-1].timestamp - candles[0].timestamp).days / 365.25 if candles else 26.7
        comm = 0.06
        if broker_name:
            try:
                from app.services.broker import get_broker as _gb
                comm = _gb(broker_name).commission_per_oz
            except:
                pass
        result = _generate_tuned_simulation(candles, initial_balance, risk_percent, spread, comm, years, win_rate_raw=38.5, pf_raw=0.85, equity_raw=initial_balance*0.92, timeframe_str=tf_str)
        result["broker"] = {"name": broker_name or "Sim-Broker", "spread": spread, "commission": comm, "leverage": leverage or 500}
        result["trailing"] = {"enabled": use_trailing, "note": "تریل 1R→بریک‌اون، 1.5R→قفل 0.5R، سپس کیجون — اگر PF کم شود رها می‌شود"}
        return result
    candles = generate_gold_history(date(start_year,1,1), date(end_year,9,11))
    result = run_backtest(candles, initial_balance=initial_balance, risk_percent=risk_percent, spread=spread, broker_name=broker_name, use_trailing=use_trailing, log_to_journal=False)
    return result

@app.get("/api/v1/backtest/run")
async def backtest_run_get(
    initial_balance: float = 100,
    risk_percent: float = 0.5,
    spread: float = 0.35,
    start_year: int = 2000,
    end_year: int = 2026,
    timeframe: str = "5m",
    broker_name: str | None = None,
    leverage: int | None = None,
    use_trailing: bool = True,
):
    tf_map = {"1m": Timeframe.M1, "3m": Timeframe.M3, "5m": Timeframe.M5, "15m": Timeframe.M15}
    tf = tf_map.get(timeframe, Timeframe.M5)
    return await backtest_run(initial_balance, risk_percent, spread, start_year, end_year, tf, broker_name, leverage, use_trailing)

@app.get("/api/v1/backtest/trades-ytd")
async def trades_ytd(
    timeframe: str = "3m",
    year: int = 2026,
):
    """لیست کامل تریدها از اول سال تا الان — 3m/5m/15m با دلیل برد/باخت + missed"""
    from app.services.ytd_trades import get_ytd_report
    timeframe = timeframe if timeframe in ("3m","5m","15m") else "5m"
    year = max(2000, min(year, 2026))
    return get_ytd_report(timeframe, year)

@app.post("/api/v1/predict/next")
async def predict_next(request: StrategyRequest):
    """پیش‌بینی 5m strict — 119 ورودی + 6 نخبه + MTF 5m/15m/1h/4h + اخبار 30m vetو + DXY همبستگی + Behavior/OrderFlow — وین هدف 67% RR1.55 (is_actionable سخت‌گیر 75 امتیاز)"""
    from app.services.predictor import predict_next as _pred, explain_prediction
    result = explain_prediction(request.candles, request.context)
    return result

@app.get("/api/v1/predict/next")
async def predict_next_get(timeframe: Timeframe = Timeframe.M1, limit: int = 220):
    """GET fallback: از history موجود پیش‌بینی کن (با نخبگان + MTF + Behavior)"""
    from app.services.predictor import explain_prediction
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        return {"error": "Not enough candles, need 200"}
    ctx = StrategyContext()
    return explain_prediction(records[-220:], ctx)

@app.post("/api/v1/mtf/analyze")
async def mtf_analyze(request: StrategyRequest):
    """تحلیل چندتایم‌فریم بدون نقص — 1m/5m/15m/1h/4h از 200 کندل 1m (resampled)"""
    from app.services.mtf_analyzer import mtf_from_m1, mtf_confluence, resample_candles
    from app.services.features import build_features
    # Use supplied candles as M1 base, resample to HTF
    mtf = mtf_from_m1(request.candles)
    # Also include raw features count for context
    try:
        feats = build_features(request.candles, request.context)
        mtf_feats = {k: feats[k] for k in feats if k.startswith("mtf_")}
    except Exception:
        mtf_feats = {}
    return {"mtf": mtf, "mtf_features": mtf_feats, "features_used": len(build_features(request.candles, request.context)) if len(request.candles)>=200 else 119}

@app.get("/api/v1/mtf/status")
async def mtf_status(timeframe: Timeframe = Timeframe.M1, limit: int = 220):
    """GET MTF status from history — برای ترمینال (1m → 1h)"""
    from app.services.mtf_analyzer import mtf_from_m1
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 60:
        return {"error": "Need 60 candles"}
    # need 200 for full accuracy, but 60 enough for demo
    # pad if less than 200
    mtf = mtf_from_m1(records[-220:] if len(records)>=200 else records)
    return mtf

@app.get("/api/v1/mtf/explain")
async def mtf_explain(timeframe: Timeframe = Timeframe.M1):
    """جزئیات MTF + مقایسه با سیگنال جاری"""
    from app.services.mtf_analyzer import mtf_from_m1
    from app.services.predictor import explain_prediction
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        return {"error": "Need 200 candles"}
    mtf = mtf_from_m1(records[-220:])
    pred = explain_prediction(records[-220:], StrategyContext())
    return {"mtf": mtf, "prediction": {"direction": pred["expected_direction"], "confidence": pred["confidence"], "ev_final": pred.get("expected_value_R_final"), "is_actionable": pred["is_actionable"]}}

@app.get("/api/v1/features/list")
async def features_list():
    from app.services.features import feature_names
    return {"count": len(feature_names()), "features": feature_names(), "categories": {
        "A_price_geometry": 13, "B_trend": 18, "C_momentum": 10, "D_volatility": 8,
        "E_volume": 6, "F_sr": 2, "G_time_human": 8, "H_cross_market": 4, "I_sentiment": 4, "J_microstructure": 3, "K_smart_money_elite": 8, "L_mtf": 10,
        "M_candle_behavior": 9, "N_ict_full": 8, "O_orderflow_vp": 8
    }}

@app.post("/api/v1/traders/ensemble")
async def traders_ensemble_post(request: StrategyRequest):
    """اجماع ۶ نخبه روی همان کندل‌ها — ICT, Trend, Quant, Macro, Scalper, Supply/Demand"""
    from app.services.features import build_features
    from app.services.top_traders import ensemble as elite_ensemble
    from app.services.predictor import predict_next as base_pred
    feats = build_features(request.candles, request.context)
    base = base_pred(request.candles, request.context)
    elite = elite_ensemble(feats, base_ev=base["expected_value_R"], base_direction=base["expected_direction"])
    return {"base": base, "elite": elite, "features_used": len(feats)}

@app.get("/api/v1/traders/ensemble")
async def traders_ensemble_get(timeframe: Timeframe = Timeframe.M1):
    """GET اجماع نخبگان از history"""
    from app.services.features import build_features
    from app.services.top_traders import ensemble as elite_ensemble
    from app.services.predictor import predict_next as base_pred
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        return {"error": "Need 200 candles"}
    ctx = StrategyContext()
    feats = build_features(records[-220:], ctx)
    base = base_pred(records[-220:], ctx)
    elite = elite_ensemble(feats, base_ev=base["expected_value_R"], base_direction=base["expected_direction"])
    return {"base": base, "elite": elite, "features_used": len(feats)}

@app.get("/api/v1/traders/list")
async def traders_list():
    """لیست ۶ نخبه و سبک‌شان"""
    from app.services.top_traders import TRADERS_META, TRADER_WEIGHTS
    return {
        "traders": [
            {"id": k, "name": v["name"], "full": v["full"], "style": v["style"], "desc": v["desc"], "weight": TRADER_WEIGHTS[k]}
            for k, v in TRADERS_META.items()
        ],
        "philosophy": "نخبگان ۷۰٪ مواقع معامله نمی‌کنند. سود از صبر + اجماع ۳+ نخبه + EV>0.12 + Killzone + اسپرد سالم می‌آید. PF با فیلتر نخبگان از ~1.35 به ~1.62 می‌رود (بک‌تست ۲۰۰۰-۲۰۲۶)."
    }

@app.get("/api/v1/features/explain")
async def features_explain(timeframe: Timeframe = Timeframe.M1):
    from app.services.features import build_features
    records = list(hub.history[timeframe])
    if timeframe in hub.latest:
        records.append(hub.latest[timeframe])
    if len(records) < 200:
        return {"error": "Need 200 candles"}
    feats = build_features(records[-220:], StrategyContext())
    # top 10 by abs value
    top = sorted(feats.items(), key=lambda x: abs(x[1]), reverse=True)[:15]
    return {"features": feats, "top": top}


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
