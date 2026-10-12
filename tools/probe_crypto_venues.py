#!/usr/bin/env python3
"""Keyless, read-only spot coverage audit for the app's exact USDT catalog.

Checks Kraken and OKX independently, per symbol: online spot listing, 320 contiguous
closed M5/M15/H1 bars and a timestamped public execution. Never turns a venue's
availability into a paper-trade authorization; an open position needs a durable,
same-venue, ordered trade replay implementation as well.

Run: python3 tools/probe_crypto_venues.py > crypto-venue-coverage.json
The runner's IP is not the phone's IP. Results are point-in-time observations.
"""
import concurrent.futures
import datetime as dt
import json
import math
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CATALOG = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/java/com/aurum/edge/data/CryptoCatalog.kt"
INTERVALS = (("M5", 5), ("M15", 15), ("H1", 60))
MIN_CANDLES = 320
# Binance/Nobitex are intentionally not candidates. No USD/USDC/futures substitutions.
KRAKEN = "https://api.kraken.com/0/public/"
OKX = "https://www.okx.com/api/v5/"


def symbols():
    pairs = re.findall(r'CryptoSymbol\("([A-Z0-9]+/USDT)",', CATALOG.read_text())
    if len(pairs) < 40 or len(set(pairs)) != len(pairs):
        raise ValueError("Unexpected crypto catalog; refusing an incomplete audit")
    return pairs


def request_json(base, path, params=None, timeout=8):
    if base not in (KRAKEN, OKX):
        raise ValueError("Host not allowlisted")
    url = base + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "AurumEdge-market-audit/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"HTTP {response.status}")
        data = response.read(10_000_001)
        if len(data) > 10_000_000:
            raise ValueError("Oversize response")
    return json.loads(data)


def checked_bars(rows, minutes, now_ms):
    """Validate the whole required tail. Never pad a missing candle or count the live bar."""
    span = minutes * 60_000
    tail = [row for row in rows if row[0] > 0 and row[0] + span <= now_ms]
    tail = sorted(tail, key=lambda row: row[0])[-MIN_CANDLES:]
    if len(tail) < MIN_CANDLES:
        return f"{len(tail)}/{MIN_CANDLES} closed"
    if any(b[0] - a[0] != span for a, b in zip(tail, tail[1:])):
        return "missing bar"
    if now_ms - (tail[-1][0] + span) > span + 90_000:
        return "stale last bar"
    for _, o, h, l, c, v in tail:
        if not all(math.isfinite(x) for x in (o, h, l, c, v)) or \
                min(o, h, l, c) <= 0 or h < max(o, c) or l > min(o, c) or v < 0:
            return "invalid OHLC"
    return "ok"


def kraken_listing(raw, symbol):
    """Use the published wsname, not a guessed REST response key (XBT/XDG aliases)."""
    alias = symbol.replace("BTC/", "XBT/").replace("DOGE/", "XDG/")
    for name, info in raw.items():
        if info.get("wsname") == alias and info.get("status") == "online":
            return name
    return None


def okx_listing(raw, symbol):
    inst = symbol.replace("/", "-")
    return inst if any(row.get("instId") == inst and row.get("instType") == "SPOT" and
                       row.get("state") == "live" and row.get("baseCcy") == symbol.split("/")[0] and
                       row.get("quoteCcy") == "USDT" for row in raw) else None


def candles(venue, pair, interval, now_ms):
    if venue == "KRAKEN_SPOT":
        body = request_json(KRAKEN, "OHLC", {"pair": pair, "interval": interval})
        if body.get("error"):
            raise ValueError(str(body["error"])[:100])
        result = body["result"]
        keys = [key for key in result if key != "last"]
        if len(keys) != 1:
            raise ValueError("Ambiguous OHLC pair")
        rows = result[keys[0]]
        data = [(int(row[0]) * 1000, *(float(v) for v in row[1:6])) for row in rows]
    else:
        bar = f"{interval}m" if interval < 60 else "1H"
        rows = []
        cursor = None
        for _ in range(2):  # OKX allows 300/page; 320 closed bars require two pages.
            args = {"instId": pair, "bar": bar, "limit": "300"}
            if cursor is not None:
                args["after"] = cursor
            body = request_json(OKX, "market/history-candles", args)
            if body.get("code") != "0":
                raise ValueError(f"OKX candles: {str(body.get('msg'))[:80]}")
            page = body.get("data", [])
            if not page:
                break
            rows.extend(page)
            cursor = page[-1][0]
        data = [(int(row[0]), *(float(v) for v in row[1:6])) for row in rows if row[8] == "1"]
    return checked_bars(data, interval, now_ms)


def latest_trade(venue, pair, now_ms):
    if venue == "KRAKEN_SPOT":
        body = request_json(KRAKEN, "Trades", {"pair": pair, "count": 1})
        if body.get("error"):
            raise ValueError(str(body["error"])[:100])
        keys = [key for key in body["result"] if key != "last"]
        if len(keys) != 1:
            raise ValueError("Ambiguous trade pair")
        rows = body["result"][keys[0]]
        at = int(float(rows[-1][2]) * 1000)
        price = float(rows[-1][0]); amount = float(rows[-1][1])
    else:
        body = request_json(OKX, "market/trades", {"instId": pair, "limit": 1})
        if body.get("code") != "0" or len(body.get("data", [])) != 1:
            raise ValueError("OKX trades unavailable")
        row = body["data"][0]
        if row.get("instId") != pair:
            raise ValueError("Wrong execution pair")
        at = int(row["ts"]); price = float(row["px"]); amount = float(row["sz"])
    if not math.isfinite(price) or not math.isfinite(amount) or \
            price <= 0 or amount <= 0 or at <= 0 or at > now_ms + 10_000:
        raise ValueError("Invalid execution")
    return max(0, now_ms - at)


def probe(venue, pair, now_ms):
    result = {"venue": venue, "pair": pair, "candles": {}, "trade_age_ms": None, "ready": False}
    for label, minutes in INTERVALS:
        try:
            result["candles"][label] = candles(venue, pair, minutes, now_ms)
        except (ValueError, KeyError, IndexError, TypeError, urllib.error.URLError, TimeoutError) as exc:
            result["candles"][label] = f"unavailable: {str(exc)[:85]}"
    try:
        result["trade_age_ms"] = latest_trade(venue, pair, now_ms)
    except (ValueError, KeyError, IndexError, TypeError, urllib.error.URLError, TimeoutError) as exc:
        result["trade_error"] = str(exc)[:85]
    # 90 seconds is the app's live quote freshness window, not a guarantee of future uptime.
    result["ready"] = all(v == "ok" for v in result["candles"].values()) and \
        result["trade_age_ms"] is not None and result["trade_age_ms"] <= 90_000
    return result


def main():
    now_ms = int(time.time() * 1000)
    pairs = symbols()
    sources = {}
    errors = {}
    for venue, base, endpoint, params in (
        ("KRAKEN_SPOT", KRAKEN, "AssetPairs", None),
        ("OKX_SPOT", OKX, "public/instruments", {"instType": "SPOT"}),
    ):
        try:
            raw = request_json(base, endpoint, params, timeout=15)
            if venue == "KRAKEN_SPOT" and raw.get("error"):
                raise ValueError(str(raw["error"])[:100])
            if venue == "OKX_SPOT" and raw.get("code") != "0":
                raise ValueError(str(raw.get("msg"))[:100])
            sources[venue] = raw["result" if venue == "KRAKEN_SPOT" else "data"]
        except (ValueError, KeyError, urllib.error.URLError, TimeoutError) as exc:
            errors[venue] = str(exc)[:100]
    rows = {symbol: {"candidates": [], "suggested": None} for symbol in pairs}
    futures = {}
    # Bounded parallelism: no serial wait per symbol, but never unbounded API traffic.
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for symbol in pairs:
            for venue in ("KRAKEN_SPOT", "OKX_SPOT"):
                raw = sources.get(venue)
                if raw is None:
                    continue
                market = (kraken_listing(raw, symbol) if venue == "KRAKEN_SPOT"
                          else okx_listing(raw, symbol))
                if market:
                    futures[pool.submit(probe, venue, market, now_ms)] = symbol
        for future in concurrent.futures.as_completed(futures):
            symbol = futures[future]
            try:
                rows[symbol]["candidates"].append(future.result())
            except Exception as exc:
                rows[symbol]["candidates"].append({"ready": False, "error": str(exc)[:90]})
    for symbol, row in rows.items():
        row["candidates"].sort(key=lambda candidate: candidate.get("venue", ""))
        ready = [candidate for candidate in row["candidates"] if candidate.get("ready")]
        # This is only a diagnostic suggestion; never change an existing position's venue.
        row["suggested"] = ready[0]["venue"] if ready else None
    print(json.dumps({"observed_at_utc": dt.datetime.fromtimestamp(now_ms / 1000, dt.timezone.utc).isoformat(),
                      "warning": "Runner reachability is not phone reachability. Listing/candles/trade freshness do not certify safe open-position replay or unlimited access.",
                      "source_errors": errors, "symbols": rows}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
