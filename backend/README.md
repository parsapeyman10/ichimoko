# Trading API

FastAPI service for XAU/USD analysis on **real provider data only**: normalized candles, indicators,
strategy evaluation, real-candle backtests, walk-forward, MTF analysis, news sentiment and the paper
journal.

## Data policy

- Prices/candles come from Twelve Data (`AURUM_TWELVE_DATA_API_KEY`). History is cached (memory +
  disk) so an offline restart can still show the last real bars, clearly marked as cached.
- News comes from a licensed provider when configured; otherwise the list is empty and the status
  says why. No headline or economic-calendar entry is ever invented.
- When the provider is unavailable every data route answers `503` with a Persian explanation.
  Client apps render that state instead of a fabricated chart.
- `backend/tests/test_real_data_policy.py` asserts all of the above.

## Environment

All variables use the `AURUM_` prefix — see `.env.example`.

```bash
AURUM_TWELVE_DATA_API_KEY=...   # required for any market data
AURUM_FMP_API_KEY=...           # optional: news + calendar
AURUM_OPENAI_API_KEY=...        # optional: LLM news analysis (falls back to rules)
AURUM_MARKET_SYMBOL=XAU/USD
```

## Local development

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
pytest -q
```

Useful endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/health` | process health |
| `GET /api/v1/data/status` | key configured? feed state? which timeframes have real bars |
| `GET /api/v1/market/{tf}/candles` | real candles (503 when the provider is down) |
| `GET /api/v1/backtest/run` | replay of the live strategy on real candles |
| `GET /api/v1/backtest/forward` | in-sample vs out-of-sample split of the real series |
| `GET /api/v1/journal` | paper journal built from real signals |
| `WS /ws/v1/market/xauusd` | real ticks pushed from the provider |

The in-process `MarketHub` is suitable for a local preview. In production, put normalized events on
NATS JetStream/Redpanda, make socket gateways stateless consumers, and store completed bars and
decisions in TimescaleDB; Redis is only hot/reconstructible state.
