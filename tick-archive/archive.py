"""Durable, same-venue public trade archive; never accepts account/position data.

The local sequence is a DATABASE cursor, not a Nobitex trade ID. Coverage gaps are
first-class rows. No REST page can prove a missing WebSocket interval is complete.
"""
import asyncio
import hmac
import json
import logging
import math
import os
import signal
import sqlite3
import time
from pathlib import Path

from aiohttp import web
from centrifuge import (Client, ClientEventHandler, SubscriptionEventHandler)

LOG = logging.getLogger("tick_archive")
SOCKET = "wss://ws.nobitex.ir/connection/websocket"
DEFAULT_SYMBOLS = "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,ADAUSDT,DOGEUSDT,AVAXUSDT,LINKUSDT,TRXUSDT,TONUSDT,DOTUSDT,LTCUSDT,BCHUSDT,SHIBUSDT,UNIUSDT,ATOMUSDT,XLMUSDT,NEARUSDT,APTUSDT,ARBUSDT,OPUSDT,FILUSDT,ICPUSDT,ETCUSDT,HBARUSDT,VETUSDT,INJUSDT,SUIUSDT,SEIUSDT,AAVEUSDT,ALGOUSDT,PEPEUSDT,WIFUSDT,FETUSDT,RENDERUSDT,TIAUSDT,LDOUSDT,GRTUSDT,SANDUSDT,MANAUSDT,AXSUSDT,POLUSDT,RUNEUSDT,ENAUSDT"


def now_ms():
    return int(time.time() * 1000)


def validate_trade(value, now):
    if not isinstance(value, dict):
        raise ValueError("invalid trade object")
    at = value.get("time")
    price = value.get("price")
    volume = value.get("volume")
    side = value.get("type")
    # Reject floats for timestamps, nonfinite prices, synthetic or stale publications.
    if isinstance(at, bool) or not str(at).isdigit():
        raise ValueError("invalid timestamp")
    at = int(at)
    try:
        price = float(price)
        volume = float(volume)
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError("invalid quote") from exc
    if (at < now - 45_000 or at > now + 10_000 or
            not math.isfinite(price) or price <= 0 or
            not math.isfinite(volume) or volume < 0 or side not in ("buy", "sell")):
        raise ValueError("stale or invalid trade")
    return {"time": at, "price": price, "volume": volume, "type": side}


class Archive:
    def __init__(self, path, symbols):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("PRAGMA busy_timeout=3000")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS trades (
              seq INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL,
              time INTEGER NOT NULL, price REAL NOT NULL, volume REAL NOT NULL,
              side TEXT NOT NULL, offset INTEGER NOT NULL, received_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS trades_symbol_seq ON trades(symbol, seq);
            CREATE INDEX IF NOT EXISTS trades_symbol_received ON trades(symbol, received_at);
            CREATE TABLE IF NOT EXISTS gaps (
              id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL,
              at INTEGER NOT NULL, after_seq INTEGER NOT NULL, reason TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS gaps_symbol_seq ON gaps(symbol, after_seq);
        """)
        self.symbols = set(symbols)
        self.offsets = {}
        self.recoverable = {}
        self.subscribed_once = set()
        self.healthy = True
        self.connected = False
        self.live_symbols = set()
        self.fatal = asyncio.Event()
        # A new process cannot assume that the upstream replay retained the downtime.
        for symbol in symbols:
            self.gap(symbol, "process_start")

    def last_seq(self, symbol):
        return self.db.execute("SELECT COALESCE(MAX(seq),0) FROM trades WHERE symbol=?", (symbol,)).fetchone()[0]

    def gap(self, symbol, reason):
        self.db.execute("INSERT INTO gaps(symbol,at,after_seq,reason) VALUES(?,?,?,?)",
                        (symbol, now_ms(), self.last_seq(symbol), reason))
        LOG.warning("coverage gap %s: %s", symbol, reason)

    def subscribed(self, symbol, was_recovering, recovered, recoverable):
        if symbol in self.subscribed_once and (not was_recovering or not recovered):
            self.gap(symbol, "upstream_recovery_failed")
            self.offsets.pop(symbol, None)
        if symbol not in self.subscribed_once:
            self.subscribed_once.add(symbol)
            self.gap(symbol, "initial_subscription")
            # A process restart is already a documented gap. First subscription to
            # an archive is not tied to historical REST by a shared ID either.
        self.recoverable[symbol] = recoverable

    def publish(self, symbol, data, offset, received_at=None):
        received_at = received_at or now_ms()
        try:
            trade = validate_trade(data, received_at)
        except ValueError:
            self.gap(symbol, "invalid_or_stale_publication")
            return None
        if self.recoverable.get(symbol):
            if not isinstance(offset, int) or offset <= 0:
                self.gap(symbol, "missing_publisher_offset")
            else:
                previous = self.offsets.get(symbol)
                if previous is not None and offset <= previous:
                    return None  # recovered duplicate, never settle twice
                if previous is not None and offset != previous + 1:
                    self.gap(symbol, "publisher_offset_jump")
        else:
            # If recovery is NOT advertised, an offset is not evidence of continuity.
            offset = 0
        with self.db:
            cur = self.db.execute(
                "INSERT INTO trades(symbol,time,price,volume,side,offset,received_at) "
                "VALUES(?,?,?,?,?,?,?)",
                (symbol, trade["time"], trade["price"], trade["volume"],
                 trade["type"], offset, received_at))
        if self.recoverable.get(symbol) and isinstance(offset, int) and offset > 0:
            self.offsets[symbol] = offset
        return cur.lastrowid

    def page(self, symbol, after, limit, from_ms=0):
        requested_after = after
        # Only on a first request: jump to the first server-received execution since
        # this position opened, without walking every historical tick in the archive.
        if after == 0 and from_ms:
            first = self.db.execute(
                "SELECT MIN(seq) FROM trades WHERE symbol=? AND received_at>=?",
                (symbol, from_ms)).fetchone()[0]
            after = first - 1 if first else self.last_seq(symbol)
        rows = self.db.execute(
            "SELECT seq,time,price,volume,side,offset FROM trades "
            "WHERE symbol=? AND seq>? ORDER BY seq LIMIT ?", (symbol, after, limit)).fetchall()
        head = self.last_seq(symbol)
        # Includes gaps on a quiet channel, including one AFTER the last delivered tick.
        gaps = self.db.execute(
            "SELECT at,after_seq,reason FROM gaps WHERE symbol=? AND after_seq>=? "
            "AND after_seq<=? AND at>=? ORDER BY id",
            (symbol, 0 if requested_after == 0 and from_ms else after, max(head, after), from_ms)).fetchall()
        return {"venue": "nobitex", "symbol": symbol, "head": head,
                "live": self.healthy and self.connected and symbol in self.live_symbols,
                "trades": [{"seq": r[0], "time": r[1], "price": r[2],
                            "volume": r[3], "type": r[4], "offset": r[5]} for r in rows],
                "gaps": [{"at": r[0], "afterSeq": r[1], "reason": r[2]} for r in gaps],
                "next": rows[-1][0] if rows else after}


class Subscriber(SubscriptionEventHandler):
    def __init__(self, archive, symbol):
        self.archive = archive
        self.symbol = symbol

    async def on_subscribed(self, ctx):
        self.archive.subscribed(self.symbol, ctx.was_recovering, ctx.recovered, ctx.recoverable)
        self.archive.live_symbols.add(self.symbol)

    async def on_subscribing(self, ctx):
        if self.symbol in self.archive.live_symbols:
            self.archive.gap(self.symbol, "upstream_subscribing")
        self.archive.live_symbols.discard(self.symbol)
        LOG.warning("upstream reconnecting: %s", self.symbol)

    async def on_unsubscribed(self, ctx):
        self.archive.live_symbols.discard(self.symbol)
        self.archive.gap(self.symbol, "upstream_unsubscribed")

    async def on_publication(self, ctx):
        try:
            # SDK awaits callbacks; synchronous durable commits intentionally apply
            # backpressure. If the server drops events meanwhile, recovery/offset checks
            # must expose the gap; there is no bounded volatile drop queue.
            self.archive.publish(self.symbol, ctx.pub.data, ctx.pub.offset)
        except Exception:
            self.archive.healthy = False
            self.archive.fatal.set()
            LOG.exception("disk write failed: refusing to call this archive healthy")
            raise

    async def on_error(self, ctx):
        LOG.error("upstream subscription error %s: %s", self.symbol, ctx)


class ClientEvents(ClientEventHandler):
    def __init__(self, archive):
        self.archive = archive

    async def on_connected(self, ctx):
        self.archive.connected = True
        LOG.info("upstream connected")

    async def on_connecting(self, ctx):
        for symbol in self.archive.live_symbols:
            self.archive.gap(symbol, "upstream_connection_lost")
        self.archive.connected = False
        self.archive.live_symbols.clear()
        LOG.warning("upstream connecting: %s", ctx)

    async def on_disconnected(self, ctx):
        for symbol in self.archive.live_symbols:
            self.archive.gap(symbol, "upstream_connection_lost")
        self.archive.connected = False
        self.archive.live_symbols.clear()
        LOG.warning("upstream disconnected: %s", ctx)

    async def on_error(self, ctx):
        LOG.error("upstream error: %s", ctx)


def make_app(archive, token):
    if len(token) < 32:
        raise ValueError("TICK_ARCHIVE_TOKEN needs at least 32 characters")

    @web.middleware
    async def authorize(request, handler):
        if request.path == "/health":
            return await handler(request)
        if not hmac.compare_digest(request.headers.get("Authorization", ""), "Bearer " + token):
            raise web.HTTPUnauthorized()
        return await handler(request)

    app = web.Application(middlewares=[authorize], client_max_size=4096)

    async def health(_):
        ok = archive.healthy and archive.connected and archive.live_symbols == archive.symbols
        return web.json_response({"ok": ok, "connected": archive.connected,
                                  "subscribed": len(archive.live_symbols),
                                  "configured": len(archive.symbols)}, status=200 if ok else 503)

    async def page(request):
        symbol = request.match_info["symbol"]
        if symbol not in archive.symbols:
            raise web.HTTPNotFound()
        try:
            after = int(request.query.get("after", "0"))
            limit = int(request.query.get("limit", "500"))
            from_ms = int(request.query.get("from", "0"))
        except ValueError:
            raise web.HTTPBadRequest()
        if after < 0 or from_ms < 0 or not 1 <= limit <= 1000:
            raise web.HTTPBadRequest()
        response = web.json_response(archive.page(symbol, after, limit, from_ms if after == 0 else 0))
        response.headers["Cache-Control"] = "no-store"
        return response

    app.router.add_get("/health", health)
    app.router.add_get("/v1/trades/{symbol}", page)
    return app


async def main():
    logging.basicConfig(level=logging.INFO)
    token = os.environ["TICK_ARCHIVE_TOKEN"]
    symbols = [s.strip() for s in os.environ.get("TICK_ARCHIVE_SYMBOLS", DEFAULT_SYMBOLS).split(",")]
    if not symbols or len(set(symbols)) != len(symbols) or any(
            not s.endswith("USDT") or not s[:-4].isalnum() or s != s.upper() for s in symbols):
        raise ValueError("invalid TICK_ARCHIVE_SYMBOLS")
    archive = Archive(os.environ.get("TICK_ARCHIVE_DB", "data/trades.sqlite3"), symbols)
    app = make_app(archive, token)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, host=os.environ.get("TICK_ARCHIVE_HOST", "127.0.0.1"),
                       port=int(os.environ.get("TICK_ARCHIVE_PORT", "8787")))
    await site.start()
    client = Client(SOCKET, events=ClientEvents(archive))
    try:
        subs = [client.new_subscription("public:trades-" + symbol,
                    events=Subscriber(archive, symbol)) for symbol in symbols]
        await client.connect()
        for sub in subs:
            await sub.subscribe()
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        tasks = [asyncio.create_task(stop.wait()), asyncio.create_task(archive.fatal.wait())]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        if archive.fatal.is_set():
            raise RuntimeError("archive storage failed; refusing to serve incomplete data")
    finally:
        await client.disconnect()
        await runner.cleanup()
        archive.db.close()


if __name__ == "__main__":
    asyncio.run(main())
