#!/usr/bin/env python3
"""Read-only Twelve Data coverage probe for the 15-symbol catalog.

Why this exists
---------------
Twelve Data's free Basic plan meters every API key separately (8 credits/minute and
800 credits/day, 1 credit per /time_series symbol) and covers three markets: US
equities, forex and crypto. Commodities (XAU/USD, XAG/USD) and indices (Dow) are paid
features, and the crypto ids that Twelve Data publishes are usually USD-quoted while
this app's crypto symbols are USDT pairs. A USD quote is NOT a USDT quote, and a Dow
future/ETF is NOT the index, so nothing is relabelled.

This tool answers one question with the user's own keys: for which catalog symbols
can Twelve Data return a real, same-id, same-currency series? It never writes files,
never stores keys, never places orders, and prints only counts/status.

Usage
-----
    # one key for everything (spread automatically, 8 requests/minute)
    TWELVE_DATA_KEY=... python3 tools/probe_twelve_coverage.py

    # one dedicated key per symbol (recommended on the free plan)
    TWELVE_DATA_KEY_EUR_USD=... TWELVE_DATA_KEY_XAU_USD=... \
        python3 tools/probe_twelve_coverage.py

    # only the symbols you care about
    python3 tools/probe_twelve_coverage.py --symbols XAU/USD,EUR/USD,DOW

Exit code is 0 when at least one symbol returned data, 2 otherwise, so CI or a cron
job can notice a plan/quota change.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, Iterator, List, Optional, Sequence, Tuple

CATALOG_PATH = "android/app/src/main/java/com/aurum/edge/core/V1Universe.kt"
API_URL = "https://api.twelvedata.com/time_series"
CREDITS_PER_MINUTE = 8
REQUEST_INTERVAL_SECONDS = 60.0 / CREDITS_PER_MINUTE + 0.5
TIMEOUT_SECONDS = 25

# Symbols the app may request with EXACTLY the same id. Anything else needs a paid plan
# (metals/indices) or cannot be proven to be the same instrument (USD vs USDT crypto).
SAME_ID_SYMBOLS = ("EUR/USD", "GBP/USD", "USD/JPY", "EUR/JPY", "GBP/JPY", "AUD/USD", "USD/CHF")


def read_catalog_symbols(path: str = CATALOG_PATH) -> List[str]:
    """Read the 15 catalog symbols from the Kotlin source of truth (no hardcoded copy)."""
    text = open(path, encoding="utf-8").read()
    blocks = re.findall(
        r"val (?:metalSymbols|indexSymbols|forexSymbols|cryptoSymbols)\s*=\s*listOf\((.*?)\)",
        text,
        re.S,
    )
    if len(blocks) != 4:
        raise ValueError("V1Universe symbol lists were not found")
    symbols: List[str] = []
    for block in blocks:
        symbols.extend(re.findall(r'"([A-Z0-9]+(?:/[A-Z]+)?)"', block))
    if len(symbols) != len(set(symbols)):
        raise ValueError("duplicate symbol in V1Universe")
    if len(symbols) != 15:
        raise ValueError(f"expected 15 catalog symbols, parsed {len(symbols)}")
    return symbols


def key_env_name(symbol: str) -> str:
    """TWELVE_DATA_KEY_EUR_USD for EUR/USD; the same convention the app uses per symbol."""
    return "TWELVE_DATA_KEY_" + symbol.replace("/", "_").replace("-", "_")


def key_for(symbol: str, default: str = "") -> str:
    return os.environ.get(key_env_name(symbol), "").strip() or default.strip()


def expected_status(symbol: str) -> str:
    if symbol in SAME_ID_SYMBOLS:
        return "free-same-id"
    if symbol in ("XAU/USD", "XAG/USD"):
        return "paid-commodity"
    if symbol == "DOW":
        return "index-id-mismatch"
    return "crypto-usd-not-usdt"


def classify(payload: object, symbol: str, quote_currency: str = "") -> Tuple[str, str]:
    """Pure classifier: (status, detail). Never invents a successful read."""
    if not isinstance(payload, dict):
        return "bad-response", "پاسخ JSON نبود"
    code = payload.get("code")
    if code not in (None, 200, "200"):
        message = str(payload.get("message") or "")[:120]
        if str(code) == "429":
            return "quota", "سهمیهٔ همین دقیقه/روز تمام شد: " + message
        return f"error-{code}", message
    meta = payload.get("meta")
    if not isinstance(meta, dict):
        return "bad-response", "meta وجود ندارد"
    if str(meta.get("symbol", "")).upper() != symbol.upper():
        return "id-mismatch", f"شناسهٔ پاسخ {meta.get('symbol')} است، نه {symbol}"
    if quote_currency and str(meta.get("currency", "")).upper() != quote_currency.upper():
        return "currency-mismatch", (
            f"واحد پاسخ {meta.get('currency')} است، نه {quote_currency}"
        )
    values = payload.get("values")
    if not isinstance(values, list) or not values:
        return "no-data", "کندلی برنگشت"
    return "ok", f"{len(values)} کندل بسته"


def request_symbol(symbol: str) -> str:
    """The id sent to Twelve Data. Same id only; the caller decides about the rest."""
    return symbol


def plan_requests(
    symbols: Sequence[str],
    default_key: str,
) -> Iterator[Tuple[str, str, str]]:
    """Yield (symbol, key, note) once per symbol; a symbol without any key is skipped."""
    for symbol in symbols:
        key = key_for(symbol, default_key)
        if not key:
            yield symbol, "", "کلید ندارد"
            continue
        yield symbol, key, ""


def fetch_one(symbol: str, key: str) -> object:
    query = urllib.parse.urlencode(
        {
            "symbol": request_symbol(symbol),
            "interval": "5min",
            "outputsize": 5,
            "order": "ASC",
            "timezone": "UTC",
            "apikey": key,
        }
    )
    request = urllib.request.Request(
        f"{API_URL}?{query}", headers={"Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:  # keep the body: Twelve explains the code
        try:
            return json.loads(error.read().decode("utf-8", "replace"))
        except Exception:
            return {"code": error.code, "message": error.reason}
    except Exception as error:  # network/proxy failure is a result too, never a success
        return {"code": "network", "message": str(error)[:120]}


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Twelve Data coverage probe (read-only)")
    parser.add_argument("--symbols", default="", help="comma separated subset of the catalog")
    parser.add_argument("--catalog", default=CATALOG_PATH)
    parser.add_argument("--dry-run", action="store_true", help="print the plan, send nothing")
    parser.add_argument("--sleep", type=float, default=REQUEST_INTERVAL_SECONDS,
                        help="seconds between requests on the same key (free plan: ~8/minute)")
    args = parser.parse_args(argv)

    catalog = read_catalog_symbols(args.catalog)
    wanted = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
    unknown = [s for s in wanted if s not in catalog]
    if unknown:
        print("نماد خارج از فهرست: " + ", ".join(unknown), file=sys.stderr)
        return 2
    symbols = wanted or catalog
    default_key = os.environ.get("TWELVE_DATA_KEY", "").strip()

    plan = list(plan_requests(symbols, default_key))
    if not any(key for _, key, _ in plan):
        print("هیچ کلیدی تنظیم نشده است: TWELVE_DATA_KEY یا TWELVE_DATA_KEY_<SYMBOL> را بدهید.",
              file=sys.stderr)
        return 2
    if args.dry_run:
        for symbol, key, note in plan:
            print(f"{symbol:10s} {expected_status(symbol):20s} "
                  f"{'کلید دارد' if key else 'بدون کلید'} {note}")
        return 0

    used_per_key: Dict[str, float] = {}
    ok = 0
    for symbol, key, note in plan:
        if not key:
            print(f"{symbol:10s} skipped  {note}")
            continue
        last = used_per_key.get(key, 0.0)
        wait = args.sleep - (time.monotonic() - last)
        if last and wait > 0:
            time.sleep(wait)
        used_per_key[key] = time.monotonic()
        payload = fetch_one(symbol, key)
        status, detail = classify(payload, symbol)
        if status == "ok":
            ok += 1
        print(f"{symbol:10s} {status:18s} expected={expected_status(symbol):20s} {detail}")
    print(f"\n{ok}/{len(symbols)} نماد دادهٔ هم‌شناسه داد. "
          "هر «paid-commodity» یا «crypto-usd-not-usdt» با پلن/شناسهٔ فعلی قابل استفاده نیست.")
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
