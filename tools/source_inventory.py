#!/usr/bin/env python3
"""Deterministic inventory of the 15-symbol scanner universe and its 1-to-1 dedicated sources.

This script does not fetch live market data or invent candle history. It reads the
actual Kotlin catalog definitions so documentation and tests fail if a symbol is added
without a unique 1-to-1 source mapping.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
WATCH_CATALOG = ROOT / "android/app/src/main/java/com/aurum/edge/data/WatchCatalog.kt"
CRYPTO_CATALOG = ROOT / "android/app/src/main/java/com/aurum/edge/data/CryptoCatalog.kt"
DEFAULT_OUT = ROOT / "docs/source-candidates-230.csv"


@dataclass(frozen=True)
class SourceRow:
    symbol: str
    asset_class: str
    candle_primary: str
    candle_secondary: str
    candle_paid_fallback: str
    watch_sources: str
    paper_execution_policy: str
    iran_direct_status: str


DEDICATED_CANDLE_SOURCES = {
    "XAU/USD": "DUKASCOPY_XAU (https://datafeed.dukascopy.com/datafeed/XAUUSD/...)",
    "XAG/USD": "DUKASCOPY_XAG (https://datafeed.dukascopy.com/datafeed/XAGUSD/...)",
    "DOW": "YAHOO_DOW (https://query1.finance.yahoo.com/v8/finance/chart/%5EDJI interval=5m range=60d)",
    "EUR/USD": "YAHOO_EURUSD (https://query1.finance.yahoo.com/v8/finance/chart/EURUSD=X interval=5m range=60d)",
    "GBP/USD": "DUKASCOPY_GBPUSD (https://datafeed.dukascopy.com/datafeed/GBPUSD/...)",
    "USD/JPY": "YAHOO_USDJPY (https://query1.finance.yahoo.com/v8/finance/chart/JPY=X interval=5m range=60d)",
    "EUR/JPY": "DUKASCOPY_EURJPY (https://datafeed.dukascopy.com/datafeed/EURJPY/...)",
    "GBP/JPY": "YAHOO_GBPJPY (https://query1.finance.yahoo.com/v8/finance/chart/GBPJPY=X interval=5m range=60d)",
    "AUD/USD": "DUKASCOPY_AUDUSD (https://datafeed.dukascopy.com/datafeed/AUDUSD/...)",
    "USD/CHF": "YAHOO_USDCHF (https://query1.finance.yahoo.com/v8/finance/chart/CHF=X interval=5m range=60d)",
    "BTC/USDT": "BINANCE_SPOT (https://data-api.binance.vision/api/v3/klines symbol=BTCUSDT interval=5m 1500 bars)",
    "ETH/USDT": "OKX_SPOT (https://www.okx.com/api/v5/market/candles instId=ETH-USDT bar=5m 1500 bars)",
    "SOL/USDT": "GATE_SPOT (https://api.gateio.ws/api/v4/spot/candlesticks currency_pair=SOL_USDT interval=5m 1500 bars)",
    "BNB/USDT": "MEXC_SPOT (https://api.mexc.com/api/v3/klines symbol=BNBUSDT interval=5m 1500 bars)",
    "XRP/USDT": "KRAKEN_SPOT (https://api.kraken.com/0/public/OHLC pair=XRPUSDT interval=5 1500 bars)",
}

DEDICATED_WATCH_SOURCES = {
    "XAU/USD": "gold_api_xau",
    "XAG/USD": "gold_api_xag",
    "DOW": "yahoo_dow",
    "EUR/USD": "yahoo_eurusd",
    "GBP/USD": "swissquote_gbpusd",
    "USD/JPY": "yahoo_usdjpy",
    "EUR/JPY": "swissquote_eurjpy",
    "GBP/JPY": "yahoo_gbpjpy",
    "AUD/USD": "swissquote_audusd",
    "USD/CHF": "swissquote_usdchf",
    "BTC/USDT": "binance_btc_spot",
    "ETH/USDT": "okx_eth_spot",
    "SOL/USDT": "gate_sol_spot",
    "BNB/USDT": "mexc_bnb_spot",
    "XRP/USDT": "kraken_xrp_spot",
}


def crypto_symbols(text: str | None = None) -> list[str]:
    raw = text if text is not None else CRYPTO_CATALOG.read_text(encoding="utf-8")
    rows = re.findall(r'CryptoSymbol\("([A-Z0-9]+/USDT)"', raw)
    if len(rows) != 5 or len(set(rows)) != 5:
        raise ValueError(f"expected 5 unique crypto symbols, got {len(rows)} ({len(set(rows))} unique)")
    return rows


def scanner_symbols(crypto: list[str] | None = None, text: str | None = None) -> list[str]:
    raw = text if text is not None else WATCH_CATALOG.read_text(encoding="utf-8")
    rows = re.findall(r'id\s*=\s*"([A-Z0-9/]+)"', raw)
    if len(rows) != 15 or len(set(rows)) != 15:
        raise ValueError(f"expected 15 unique scanner symbols, got {len(rows)} ({len(set(rows))} unique)")
    return rows


def classify(symbol: str) -> SourceRow:
    if symbol not in DEDICATED_CANDLE_SOURCES or symbol not in DEDICATED_WATCH_SOURCES:
        raise ValueError(f"unclassified symbol: {symbol}")
    candle_source = DEDICATED_CANDLE_SOURCES[symbol]
    watch_source = DEDICATED_WATCH_SOURCES[symbol]
    if symbol.endswith("/USDT"):
        return SourceRow(
            symbol=symbol,
            asset_class="CRYPTO",
            candle_primary=candle_source,
            candle_secondary="M15 (3x M5 = 500 bars) & H1 (12x M5 = 125 bars) converted from 1500 M5 candles",
            candle_paid_fallback="none",
            watch_sources=watch_source,
            paper_execution_policy="compression_breakout_on_1500_M5_candles_plus_converted_M15_H1",
            iran_direct_status="dedicated_1_to_1_exchange_source",
        )
    if symbol in {"XAU/USD", "XAG/USD"}:
        return SourceRow(
            symbol=symbol,
            asset_class="METALS",
            candle_primary=candle_source,
            candle_secondary="M15 (500 bars) & H1 (125 bars) converted from 1500 M5 candles + Ichimoku 513/1024",
            candle_paid_fallback="none",
            watch_sources=watch_source,
            paper_execution_policy="ichimoku_8_24_72_plus_513_1024_and_converted_M15_H1",
            iran_direct_status="dedicated_1_to_1_metals_source",
        )
    if symbol == "DOW":
        return SourceRow(
            symbol=symbol,
            asset_class="INDEX",
            candle_primary=candle_source,
            candle_secondary="M15 (500 bars) & H1 (125 bars) converted from 1500 M5 candles + Ichimoku 513/1024",
            candle_paid_fallback="none",
            watch_sources=watch_source,
            paper_execution_policy="ichimoku_8_24_72_plus_513_1024_and_converted_M15_H1",
            iran_direct_status="dedicated_1_to_1_index_source",
        )
    return SourceRow(
        symbol=symbol,
        asset_class="FOREX",
        candle_primary=candle_source,
        candle_secondary="M15 (500 bars) & H1 (125 bars) converted from 1500 M5 candles + Ichimoku 513/1024",
        candle_paid_fallback="none",
        watch_sources=watch_source,
        paper_execution_policy="ichimoku_8_24_72_plus_513_1024_and_converted_M15_H1",
        iran_direct_status="dedicated_1_to_1_forex_source",
    )


def build_inventory() -> list[SourceRow]:
    crypto = crypto_symbols()
    rows = [classify(symbol) for symbol in scanner_symbols(crypto)]
    if len(rows) != 15:
        raise ValueError(f"expected 15 rows, got {len(rows)}")
    if len({r.candle_primary for r in rows}) != 15:
        raise ValueError("each symbol must have a distinct primary candle source")
    if len({r.watch_sources for r in rows}) != 15:
        raise ValueError("each symbol must have a distinct watch source")
    return rows


def write_csv(path: Path = DEFAULT_OUT) -> list[SourceRow]:
    rows = build_inventory()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(SourceRow.__annotations__.keys())
        for row in rows:
            writer.writerow([
                row.symbol,
                row.asset_class,
                row.candle_primary,
                row.candle_secondary,
                row.candle_paid_fallback,
                row.watch_sources,
                row.paper_execution_policy,
                row.iran_direct_status,
            ])
    return rows


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    inventory = write_csv(out)
    print(f"wrote {len(inventory)} rows to {out}")
