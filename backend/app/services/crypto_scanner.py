"""Read-only, evidence-based crypto *candidate* screen. Not a pump prediction or order engine.

Universe: CoinGecko's first 200 USD market-cap entries. Strict prefilters on its reported
supply, volume and momentum; independent Binance SPOT symbol, book, 24h ticker and twelve
*completed* hourly candles. If a provider fails, do not serve yesterday's candidates.
"""
from __future__ import annotations

import asyncio
import math
import re
import time
from collections import Counter
from datetime import datetime, timedelta, timezone

import httpx

from app.config import Settings

CG_URL = "https://api.coingecko.com/api/v3/coins/markets"
BINANCE = "https://data-api.binance.vision/api/v3"
MAX_SHORTLIST = 12
CACHE_SECONDS = 120
FILTERS = {
    "universe": "CoinGecko: فقط صفحهٔ اول ۲۰۰ دارایی برتر بر اساس ارزش بازار USD؛ حداکثر ۱۲ نماد برای راستی‌آزمایی در بایننس",
    "market_cap_usd": "۵۰ میلیون تا ۵ میلیارد",
    "volume_24h_usd": "حداقل ۱۵ میلیون؛ نسبت گردش به ارزش بازار ۸٪ تا ۱۰۰٪",
    "supply": "عرضهٔ درگردش / حداکثر عرضه حداقل ۵۰٪ و FDV / ارزش بازار حداکثر ۲",
    "momentum": "۱ساعته CoinGecko: ۰٫۶٪ تا ۴٪؛ ۲۴ساعته: ۲٪ تا ۱۴٪؛ هفتگی: ۱۰٪- تا ۳۵٪",
    "venue": "فقط نماد قابل‌معاملهٔ SPOT/USDT در Binance؛ نه قرارداد شورت و نه نماد اهرمی",
    "freshness": "CoinGecko حداکثر ۵ دقیقه؛ تیکر Binance حداکثر ۲ دقیقه؛ کندل بسته حداکثر ۷۵ دقیقه",
    "independent_price": "شناسهٔ جفت CoinGecko/Binance باید یکسان باشد؛ اختلاف قیمت بازار حداکثر ۱٫۵٪، تیکر جفت حداکثر ۲٪ و اسپرد دفتر سفارش حداکثر ۰٫۳٪",
    "binance_liquidity": "حجم ۲۴ساعته حداقل ۵ میلیون USDT و حداقل ۵۰۰ معامله",
    "completed_candles": "۱۲ کندل بستهٔ ساعتی؛ میانگین حجم ۳ ساعت آخر / ۹ ساعت قبلی ۱٫۸ تا ۸؛ خرید تیکر ۵۴٪ تا ۷۸٪؛ رشد ۳ساعته ۱٪ تا ۸٪",
}


def _number(value) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return result if math.isfinite(result) else None


def _recent_millis(value, now: datetime, max_age_ms: int) -> bool:
    n = _number(value)
    return n is not None and -60_000 <= now.timestamp() * 1000 - n <= max_age_ms


def _recent_iso(value, now: datetime, max_age: timedelta) -> bool:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        age = now - parsed.astimezone(timezone.utc)
    except (AttributeError, TypeError, ValueError):
        return False
    return -timedelta(minutes=1) <= age <= max_age


def preselect(coins: list, now: datetime) -> list[dict]:
    """Missing/stale fields exclude a coin. Duplicate tickers have ambiguous venue identity."""
    if not isinstance(coins, list) or not 1 <= len(coins) <= 200:
        raise ValueError("فهرست بازار ناقص یا بزرگ‌تر از حد انتظار است")
    symbols = Counter(str(c.get("symbol", "")).upper() for c in coins if isinstance(c, dict))
    result = []
    for coin in coins:
        if not isinstance(coin, dict):
            continue
        code = str(coin.get("symbol", "")).upper()
        coin_id = coin.get("id")
        if not re.fullmatch(r"[A-Z0-9]{2,12}", code) or symbols[code] != 1 or not isinstance(coin_id, str) or not re.fullmatch(r"[a-z0-9-]{2,90}", coin_id):
            continue
        if code.endswith(("UP", "DOWN", "BULL", "BEAR", "3L", "3S")):
            continue
        if not _recent_iso(coin.get("last_updated"), now, timedelta(minutes=5)):
            continue
        cap = _number(coin.get("market_cap"))
        fdv = _number(coin.get("fully_diluted_valuation"))
        volume = _number(coin.get("total_volume"))
        supply = _number(coin.get("circulating_supply"))
        max_supply = _number(coin.get("max_supply"))
        price = _number(coin.get("current_price"))
        h1 = _number(coin.get("price_change_percentage_1h_in_currency"))
        d1 = _number(coin.get("price_change_percentage_24h_in_currency"))
        w1 = _number(coin.get("price_change_percentage_7d_in_currency"))
        if None in (cap, fdv, volume, supply, max_supply, price, h1, d1, w1):
            continue
        if not (50_000_000 <= cap <= 5_000_000_000 and
                volume >= 15_000_000 and .08 <= volume / cap <= 1.0 and
                max_supply > 0 and supply > 0 and .5 <= supply / max_supply <= 1.01 and
                0 < fdv / cap <= 2 and price > 0 and
                .6 <= h1 <= 4 and 2 <= d1 <= 14 and -10 <= w1 <= 35):
            continue
        result.append(coin)
    return sorted(result, key=lambda c: _number(c["total_volume"]) or 0, reverse=True)[:MAX_SHORTLIST]


def confirm_spot(coin: dict, exchange_info: dict, ticker: dict, book: dict, klines: list,
                 now: datetime, coin_tickers: dict) -> dict | None:
    """All criteria must hold at both providers. Never use a forming hourly candle."""
    code = coin["symbol"].upper()
    symbol = code + "USDT"
    pairs = exchange_info.get("symbols") if isinstance(exchange_info, dict) else None
    if not isinstance(pairs, list):
        raise ValueError("قواعد بازار Binance موجود نیست")
    if len(pairs) != 1:
        return None  # not listed on this exact venue/quote
    pair = pairs[0]
    if not isinstance(pair, dict) or (pair.get("symbol"), pair.get("baseAsset"), pair.get("quoteAsset"),
                                     pair.get("status"), pair.get("isSpotTradingAllowed")) != (
                                     symbol, code, "USDT", "TRADING", True):
        return None
    if not isinstance(ticker, dict) or not isinstance(book, dict) or not isinstance(klines, list):
        raise ValueError("پاسخ تیکر یا کندل نامعتبر است")
    if ticker.get("symbol") != symbol or book.get("symbol") != symbol:
        return None
    price, bid, ask = (_number(ticker.get("lastPrice")), _number(book.get("bidPrice")),
                       _number(book.get("askPrice")))
    volume = _number(ticker.get("quoteVolume"))
    d1 = _number(ticker.get("priceChangePercent"))
    trades = _number(ticker.get("count"))
    cg_price = _number(coin.get("current_price"))
    if None in (price, bid, ask, volume, d1, trades, cg_price) or not _recent_millis(ticker.get("closeTime"), now, 120_000):
        return None
    if not (0 < bid <= ask and price > 0 and cg_price > 0 and volume >= 5_000_000 and
            trades >= 500 and 1 <= d1 <= 18):
        return None
    mid = (bid + ask) / 2
    spread = (ask - bid) / mid * 100
    if spread > .3 or abs(mid / price - 1) > .005 or abs(cg_price / price - 1) > .015:
        return None
    # Market-level CG price alone does not prove that a symbol refers to this asset on Binance.
    # CoinGecko's *per-coin* Binance ticker binds the exact coin ID to the venue's base/quote.
    pair_list = coin_tickers.get("tickers") if isinstance(coin_tickers, dict) else None
    if not isinstance(pair_list, list):
        raise ValueError("CoinGecko شناسهٔ جفت بازار را برنگرداند")
    def valid_pair(item) -> bool:
        if not isinstance(item, dict):
            return False
        market = item.get("market")
        last = item.get("converted_last")
        volume_on_pair = item.get("converted_volume")
        pair_price = _number(last.get("usd")) if isinstance(last, dict) else None
        pair_volume = _number(volume_on_pair.get("usd")) if isinstance(volume_on_pair, dict) else None
        pair_spread = _number(item.get("bid_ask_spread_percentage"))
        return (item.get("base") == code and item.get("target") == "USDT" and
                item.get("coin_id") == coin["id"] and item.get("target_coin_id") == "tether" and
                isinstance(market, dict) and market.get("identifier") == "binance" and
                market.get("has_trading_incentive") is not True and
                item.get("is_stale") is False and item.get("is_anomaly") is False and
                _recent_iso(item.get("timestamp"), now, timedelta(minutes=10)) and
                pair_price is not None and pair_price > 0 and abs(pair_price / price - 1) <= .02 and
                pair_volume is not None and pair_volume >= 1_000_000 and
                pair_spread is not None and 0 <= pair_spread <= .5)

    pair = next((item for item in pair_list if valid_pair(item)), None)
    if pair is None:
        return None
    if len(klines) < 12:
        return None
    # Binance returns the *forming* candle in limit=13; discard by close time.
    closed = [bar for bar in klines if isinstance(bar, list) and len(bar) >= 11 and
              (_number(bar[6]) or math.inf) < now.timestamp() * 1000]
    closed = closed[-12:]
    if len(closed) != 12 or not _recent_millis(closed[-1][6], now, 75 * 60_000):
        return None
    opens = [_number(row[0]) for row in closed]
    quotes = [_number(row[7]) for row in closed]
    buys = [_number(row[10]) for row in closed]
    closes = [_number(row[4]) for row in closed]
    counts = [_number(row[8]) for row in closed]
    if any(v is None for v in opens + quotes + buys + closes + counts):
        return None
    # Check contiguous 1h bars so a trading suspension cannot manufacture a volume spike.
    if any(abs(opens[i] - opens[i - 1] - 3_600_000) > 1000 for i in range(1, 12)):
        return None
    baseline = sum(quotes[:9]) / 9
    recent = sum(quotes[-3:])
    if baseline <= 0 or recent <= 0 or any(q < 200_000 for q in quotes[-3:]) or any(n < 100 for n in counts[-3:]):
        return None
    ratio = recent / 3 / baseline
    buy_ratio = sum(buys[-3:]) / recent
    change_3h = (closes[-1] / closes[-4] - 1) * 100 if closes[-4] > 0 else -100
    if not (1.8 <= ratio <= 8 and recent >= 1_500_000 and .54 <= buy_ratio <= .78 and
            1 <= change_3h <= 8 and abs(closes[-1] / price - 1) <= .03):
        return None
    return {
        "id": coin["id"], "name": str(coin.get("name", code))[:80], "symbol": symbol,
        "price_usd": price, "market_cap_usd": _number(coin["market_cap"]),
        "volume_24h_usd": _number(coin["total_volume"]), "binance_volume_24h_usdt": volume,
        "change_1h_pct": _number(coin["price_change_percentage_1h_in_currency"]),
        "change_24h_pct": _number(coin["price_change_percentage_24h_in_currency"]),
        "change_7d_pct": _number(coin["price_change_percentage_7d_in_currency"]),
        "change_3h_pct": round(change_3h, 2), "volume_ratio_3h": round(ratio, 2),
        "taker_buy_ratio_3h": round(buy_ratio, 3), "spread_pct": round(spread, 3),
        "supply_ratio": round(_number(coin["circulating_supply"]) / _number(coin["max_supply"]), 3),
        "coingecko_at": coin["last_updated"],
        "coingecko_pair_at": pair["timestamp"],
        "binance_at": datetime.fromtimestamp(float(ticker["closeTime"]) / 1000, timezone.utc).isoformat(),
        "last_closed_candle_at": datetime.fromtimestamp(float(closed[-1][6]) / 1000, timezone.utc).isoformat(),
        "sources": ["CoinGecko USD markets + coin-ID Binance pair", "Binance Spot USDT ticker/book/1h klines"],
        "link": "https://www.coingecko.com/en/coins/" + coin["id"],
    }


class CryptoScanner:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._lock = asyncio.Lock()
        self._last_attempt = 0.0
        self._last_success: datetime | None = None
        self._scanned = 0
        self._preselected = 0
        self._candidates: list[dict] = []
        self._error: str | None = None
        self._online = False

    async def _fetch_json(self, client: httpx.AsyncClient, url: str, *, params: dict | None = None,
                          headers: dict | None = None):
        response = await client.get(url, params=params, headers=headers)
        response.raise_for_status()  # 401, 429, 403 and 3xx are NOT treated as empty scans
        if len(response.content) > 3_000_000:
            raise ValueError("پاسخ دادهٔ بازار بیش از حد بزرگ است")
        return response.json()

    async def _confirm(self, client: httpx.AsyncClient, coin: dict, now: datetime,
                       cg_headers: dict | None) -> dict | None:
        symbol = coin["symbol"].upper() + "USDT"
        try:
            info = await self._fetch_json(client, BINANCE + "/exchangeInfo", params={"symbol": symbol})
        except httpx.HTTPStatusError as exc:
            # Binance returns -1121 (HTTP 400) for a validly formed but unlisted symbol.
            # Only that *specific* response means 'not on this venue'; 403/429/5xx fail closed.
            try:
                unlisted = exc.response.status_code == 400 and exc.response.json().get("code") == -1121
            except (ValueError, AttributeError):
                unlisted = False
            if unlisted:
                return None
            raise
        if not isinstance(info, dict) or not isinstance(info.get("symbols"), list):
            raise ValueError("پاسخ قواعد بازار نامعتبر است")
        if not info["symbols"]:
            return None
        pair, ticker, book, bars = await asyncio.gather(
            self._fetch_json(client, f"https://api.coingecko.com/api/v3/coins/{coin['id']}/tickers",
                             params={"exchange_ids": "binance", "order": "volume_desc", "page": 1}, headers=cg_headers),
            self._fetch_json(client, BINANCE + "/ticker/24hr", params={"symbol": symbol}),
            self._fetch_json(client, BINANCE + "/ticker/bookTicker", params={"symbol": symbol}),
            self._fetch_json(client, BINANCE + "/klines", params={"symbol": symbol, "interval": "1h", "limit": 13}),
        )
        return confirm_spot(coin, info, ticker, book, bars, now, pair)

    async def _scan(self) -> None:
        now = datetime.now(timezone.utc)
        try:
            headers = {"x-cg-demo-api-key": self.settings.coingecko_demo_api_key.strip()} if self.settings.coingecko_demo_api_key else None
            async with httpx.AsyncClient(timeout=9, follow_redirects=False) as client:
                coins = await self._fetch_json(client, CG_URL, headers=headers, params={
                    "vs_currency": "usd", "order": "market_cap_desc", "per_page": 200, "page": 1,
                    "sparkline": "false", "price_change_percentage": "1h,24h,7d",
                })
                selected = preselect(coins, now)
                self._scanned = len(coins)
                self._preselected = len(selected)
                sem = asyncio.Semaphore(3)

                async def bounded(coin: dict):
                    async with sem:
                        return await self._confirm(client, coin, now, headers)

                checked = await asyncio.gather(*(bounded(c) for c in selected))
            self._candidates = sorted((c for c in checked if c), key=lambda c: c["volume_ratio_3h"], reverse=True)
            self._online = True
            self._last_success = datetime.now(timezone.utc)
            self._error = None
        except Exception as exc:
            self._online = False
            self._candidates = []  # NEVER publish stale candidates or imply 'no candidates' on failure
            if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code in (401, 403):
                self._error = "دسترسی CoinGecko یا Binance رد شد؛ کلید Demo و محدودیت منطقه/ناشر را روی سرور بررسی کنید"
            elif isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code == 429:
                self._error = "محدودیت نرخ CoinGecko یا Binance؛ نامزدهای قبلی حذف شدند"
            else:
                self._error = f"دریافت/اعتبارسنجی CoinGecko یا Binance انجام نشد ({type(exc).__name__})"

    async def snapshot(self) -> dict:
        async with self._lock:
            refresh = self._last_attempt == 0.0 or time.monotonic() - self._last_attempt >= CACHE_SECONDS
            if refresh:
                self._last_attempt = time.monotonic()
                try:
                    await asyncio.wait_for(self._scan(), timeout=35)
                except TimeoutError:
                    self._online = False
                    self._candidates = []
                    self._error = "زمان دریافت دو منبع بیش از ۳۵ ثانیه شد؛ نتیجهٔ قبلی حذف شد"
            return {
                "status": {
                    "state": "online" if self._online else "unavailable",
                    "provider": "CoinGecko + Binance Spot" if self._preselected and self._online else (
                        "CoinGecko (پیش‌فیلتر؛ Binance نیازی به بررسی نداشت)" if self._online else
                        "CoinGecko + Binance Spot (داده نامعتبر)"),
                    "error": self._error,
                    "last_success_at": self._last_success.isoformat() if self._last_success else None,
                    "cached": self._online and not refresh,  # report reuse truthfully, never relabel it as a fresh scan
                },
                "checked_at": (self._last_success if self._online and self._last_success else datetime.now(timezone.utc)).isoformat(),
                "scanned": self._scanned if self._online else 0,
                "preselected": self._preselected if self._online else 0,
                "filters": FILTERS,
                "candidates": self._candidates if self._online else [],
                "note": "صرفاً غربالگری داده‌های تاریخیِ لحظه‌ای؛ پامپ قطعی، احتمال سود یا سیگنال سفارش نیست. دادهٔ ناقص نتیجهٔ خالی معتبر نیست. معاملهٔ شورت Spot در این صفحه وجود ندارد.",
            }
