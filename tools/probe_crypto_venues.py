#!/usr/bin/env python3
"""Read-only public venue coverage audit for the 5 CryptoCatalog USDT pairs.

This tool never places orders, never uses API keys, and never fabricates candle
series. It checks public symbol listings and optionally probes 1500 closed
candles for M5 (from which M15 and H1 are converted).
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

CATALOG_PATH = (
    pathlib.Path(__file__).resolve().parents[1]
    / "android/app/src/main/java/com/aurum/edge/data/CryptoCatalog.kt"
)
SYMBOL_RE = re.compile(r'CryptoSymbol\("([A-Z0-9]+/USDT)",\s*"([A-Z0-9]+)"')
REQUIRED_CLOSED_BARS = 1500
TIMEFRAMES: Tuple[Tuple[str, int], ...] = (
    ("5m", 5 * 60_000),
)


def parse_catalog_symbols(text: str) -> List[str]:
    symbols = [match.group(1) for match in SYMBOL_RE.finditer(text)]
    if len(symbols) != len(set(symbols)):
        raise ValueError("duplicate symbol in CryptoCatalog")
    return symbols


def load_catalog_symbols(path: pathlib.Path = CATALOG_PATH) -> List[str]:
    symbols = parse_catalog_symbols(path.read_text(encoding="utf-8"))
    if len(symbols) != 5:
        raise ValueError(f"expected 5 crypto symbols, found {len(symbols)}")
    return symbols


def default_get_json(url: str, timeout: float = 15.0) -> object:
    req = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "aurum-venue-audit/1.0"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read(25_000_000)
    return json.loads(raw.decode("utf-8"))


def binance_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict):
        return set()
    rows = payload.get("symbols")
    if not isinstance(rows, list):
        return set()
    out: Set[str] = set()
    for row in rows:
        if (
            isinstance(row, dict)
            and row.get("status") == "TRADING"
            and row.get("quoteAsset") == "USDT"
            and row.get("isSpotTradingAllowed") is not False
        ):
            base = row.get("baseAsset")
            if isinstance(base, str) and base:
                out.add(f"{base.upper()}/USDT")
    return out


def okx_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict) or payload.get("code") != "0":
        return set()
    rows = payload.get("data")
    if not isinstance(rows, list):
        return set()
    out: Set[str] = set()
    for row in rows:
        if (
            isinstance(row, dict)
            and row.get("instType") == "SPOT"
            and row.get("state") == "live"
            and row.get("quoteCcy") == "USDT"
        ):
            base = row.get("baseCcy")
            if isinstance(base, str) and base:
                out.add(f"{base.upper()}/USDT")
    return out


def bybit_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict) or payload.get("retCode") != 0:
        return set()
    result = payload.get("result")
    rows = result.get("list") if isinstance(result, dict) else None
    if not isinstance(rows, list):
        return set()
    out: Set[str] = set()
    for row in rows:
        if (
            isinstance(row, dict)
            and row.get("status") == "Trading"
            and row.get("quoteCoin") == "USDT"
        ):
            base = row.get("baseCoin")
            if isinstance(base, str) and base:
                out.add(f"{base.upper()}/USDT")
    return out


def kucoin_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict) or payload.get("code") != "200000":
        return set()
    rows = payload.get("data")
    if not isinstance(rows, list):
        return set()
    out: Set[str] = set()
    for row in rows:
        if (
            isinstance(row, dict)
            and row.get("enableTrading") is True
            and row.get("quoteCurrency") == "USDT"
        ):
            base = row.get("baseCurrency")
            if isinstance(base, str) and base:
                out.add(f"{base.upper()}/USDT")
    return out


def gate_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, list):
        return set()
    out: Set[str] = set()
    for row in payload:
        if (
            isinstance(row, dict)
            and row.get("trade_status") == "tradable"
            and str(row.get("quote", "")).upper() == "USDT"
        ):
            base = row.get("base")
            if isinstance(base, str) and base:
                out.add(f"{base.upper()}/USDT")
    return out


def mexc_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict):
        return set()
    rows = payload.get("symbols")
    if not isinstance(rows, list):
        return set()
    out: Set[str] = set()
    for row in rows:
        if (
            isinstance(row, dict)
            and str(row.get("status")) in {"1", "ENABLED", "TRADING"}
            and row.get("quoteAsset") == "USDT"
            and row.get("isSpotTradingAllowed") is not False
        ):
            base = row.get("baseAsset")
            if isinstance(base, str) and base:
                out.add(f"{base.upper()}/USDT")
    return out


def kraken_spot_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict) or payload.get("error"):
        return set()
    result = payload.get("result")
    if not isinstance(result, dict):
        return set()
    out: Set[str] = set()
    for row in result.values():
        if not isinstance(row, dict) or row.get("status") not in {None, "online"}:
            continue
        wsname = row.get("wsname")
        if isinstance(wsname, str) and wsname.endswith("/USDT"):
            base = wsname.split("/", 1)[0].upper()
            base = {"XBT": "BTC", "XDG": "DOGE"}.get(base, base)
            out.add(f"{base}/USDT")
    return out


def nobitex_usdt_pairs(payload: object) -> Set[str]:
    if not isinstance(payload, dict) or payload.get("status") != "ok":
        return set()
    stats = payload.get("stats")
    if not isinstance(stats, dict):
        return set()
    out: Set[str] = set()
    for key, row in stats.items():
        if (
            isinstance(key, str)
            and key.lower().endswith("-usdt")
            and isinstance(row, dict)
            and not row.get("isClosed", False)
        ):
            base = key[:-5].upper()
            if base:
                out.add(f"{base}/USDT")
    return out


VENUES: Tuple[Tuple[str, str, Callable[[object], Set[str]]], ...] = (
    ("binance_vision", "https://data-api.binance.vision/api/v3/exchangeInfo?permissions=SPOT", binance_spot_usdt_pairs),
    ("okx_spot", "https://www.okx.com/api/v5/public/instruments?instType=SPOT", okx_spot_usdt_pairs),
    ("bybit_spot", "https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000", bybit_spot_usdt_pairs),
    ("kucoin_spot", "https://api.kucoin.com/api/v2/symbols", kucoin_spot_usdt_pairs),
    ("gate_spot", "https://api.gateio.ws/api/v4/spot/currency_pairs", gate_spot_usdt_pairs),
    ("mexc_spot", "https://api.mexc.com/api/v3/exchangeInfo", mexc_spot_usdt_pairs),
    ("kraken_spot", "https://api.kraken.com/0/public/AssetPairs", kraken_spot_usdt_pairs),
    ("nobitex_spot", "https://apiv2.nobitex.ir/market/stats", nobitex_usdt_pairs),
)


def coverage_summary(
    catalog_symbols: Sequence[str],
    venue_pairs: Dict[str, Set[str]],
) -> Dict[str, object]:
    catalog = list(catalog_symbols)
    by_venue: Dict[str, Dict[str, object]] = {}
    covered_any: Set[str] = set()
    non_binance_covered: Set[str] = set()
    for name, pairs in venue_pairs.items():
        matched = [s for s in catalog if s in pairs]
        missing = [s for s in catalog if s not in pairs]
        covered_any.update(matched)
        if name != "binance_vision":
            non_binance_covered.update(matched)
        by_venue[name] = {
            "supported_count": len(matched),
            "missing_count": len(missing),
            "missing_sample": missing[:15],
        }
    return {
        "catalog_count": len(catalog),
        "covered_by_any_venue": len(covered_any),
        "covered_by_non_binance_venue": len(non_binance_covered),
        "missing_from_all_non_binance": [s for s in catalog if s not in non_binance_covered],
        "venues": by_venue,
    }


def validate_closed_bars(
    bars: Sequence[Tuple[int, float, float, float, float, float]],
    interval_ms: int,
    now_ms: int,
    required: int = REQUIRED_CLOSED_BARS,
) -> Tuple[bool, str]:
    closed = [bar for bar in bars if bar[0] + interval_ms <= now_ms]
    closed.sort(key=lambda item: item[0])
    if len(closed) < required:
        return False, f"only {len(closed)}/{required} closed bars"
    tail = closed[-required:]
    for prev, cur in zip(tail, tail[1:]):
        if cur[0] <= prev[0]:
            return False, "non-increasing timestamps"
        if cur[0] - prev[0] != interval_ms:
            return False, f"gap {cur[0] - prev[0]}ms != {interval_ms}ms"
    for ts, o, h, l, c, v in tail:
        if ts <= 0 or min(o, h, l, c) <= 0 or h < max(o, c) or l > min(o, c) or v < 0:
            return False, "invalid OHLCV"
    age = now_ms - (tail[-1][0] + interval_ms)
    if age < 0 or age > interval_ms + 90_000:
        return False, f"stale last bar age={age}ms"
    return True, f"{len(tail)} closed contiguous bars"


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-out", type=pathlib.Path, help="Optional JSON report path.")
    args = parser.parse_args(argv)
    symbols = load_catalog_symbols()
    venue_pairs: Dict[str, Set[str]] = {}
    errors: Dict[str, str] = {}
    for name, url, parser_fn in VENUES:
        try:
            venue_pairs[name] = parser_fn(default_get_json(url))
        except Exception as exc:  # pragma: no cover - network/runtime report path
            venue_pairs[name] = set()
            errors[name] = f"{type(exc).__name__}: {exc}"
    report = coverage_summary(symbols, venue_pairs)
    report["errors"] = errors
    report["checked_at_ms"] = int(time.time() * 1000)
    rendered = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True)
    if args.json_out:
        args.json_out.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
