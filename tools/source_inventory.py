#!/usr/bin/env python3
"""Reproducible complete candidate inventory, NOT an Iran reachability audit.
No network requests, secrets, user holdings, guessed aliases or generated market data.
"""
import csv
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / 'android/app/src/main/java/com/aurum/edge/data'


def rows():
    crypto = re.findall(r'CryptoSymbol\("([A-Z0-9]+/USDT)",', (DATA / 'CryptoCatalog.kt').read_text())
    raw = (DATA / 'WatchCatalog.kt').read_text().split('val scannerSymbols:')[1].split(') + CryptoCatalog.ids')[0]
    other = re.findall(r'"([A-Z/]+)"', raw)
    assert len(crypto) == 200 and len(other) == 30
    assert len(set(crypto + other)) == 230
    duk = dict(re.findall(r'"([A-Z]+/[A-Z]+)" -> "([A-Z]+)"', (DATA / 'DukascopyHistoryClient.kt').read_text()))
    yahoo = dict(re.findall(r'"([A-Z]+/[A-Z]+)" -> "([A-Z]+=X)"', (DATA / 'PublicCandleHistoryClient.kt').read_text()))
    for symbol in other + crypto:
        if symbol in crypto:
            candidates = f"Gate:{symbol.replace('/', '_')};OKX:{symbol.replace('/', '-')};Kraken:{symbol.replace('BTC/', 'XBT/').replace('DOGE/', 'XDG/')};MEXC:{symbol.replace('/', '')}"
            group, restriction = 'crypto', 'Exact active SPOT/USDT listing + complete fresh M5/M15/H1 required; radar only, not fills'
        else:
            group = 'forex' if symbol in yahoo else 'commodity'
            candidates = ';'.join(([f"Yahoo:{yahoo[symbol]}"] if symbol in yahoo else []) +
                                  ([f"Dukascopy:{duk[symbol]}"] if symbol in duk else []))
            restriction = 'Optional Twelve Data requires key and exact instrument validation'
            if not candidates:
                restriction = 'NO KEYLESS EXACT HISTORY ADAPTER; do not substitute futures/ETF/CFD or invent prices'
        yield dict(symbol=symbol, category=group, keyless_history_candidates=candidates or 'NONE',
                   iran_network_status='NOT_TESTED_FROM_IRAN; phone evaluates actual responses',
                   selection='same timestamp comparison; no averaging/stitching; known conflicts block entries',
                   limitations=restriction)


if __name__ == '__main__':
    result = list(rows())
    writer = csv.DictWriter(sys.stdout, fieldnames=list(result[0]))
    writer.writeheader()
    writer.writerows(result)
