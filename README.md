# Trading

An XAU/USD intelligence workspace that runs on **real market data only** — a native Android app,
a desktop/mobile web terminal, and a FastAPI engine.

> **Data policy (hard rule):** every candle, price, headline and trade report in this project comes
> from a real provider (Twelve Data for prices, an optional licensed source for news). When the
> provider is unreachable or no key is configured, the UI shows an explicit **offline / no-key**
> state with the last real cached data. No module in this repository invents candles, prices, news,
> performance numbers or trades. There is no demo mode.

## What "practice" means here

The only training concept in the project is learning from real history:

- **Backtest** — replays the live strategy over real candles.
- **Walk-forward** — older part of the real series in-sample, newer part out-of-sample.
- **Paper journal** — signals generated from real prices, settled later against real prices.
- **Walk-forward** — older part of the real series in-sample, newer part out-of-sample, with an
  explicit verdict (kept on the device so the numbers can be re-checked later).
- **Learning tab in the app** — runs the real backtest **and** the walk-forward split on your phone,
  then stores the report in the journal tab.

The Android app needs no server: provider access, indicators, the signal engine, multi-timeframe
aggregation, backtesting, the paper journal and the background monitor all run on the device.

## Repository map

```text
android/                     Native Kotlin + Jetpack Compose app (Trading)
  app/src/main/java/com/aurum/edge/
    core/                    Models + AppContainer (dependency wiring)
    data/                    Twelve Data client, settings, cache, journal, market repository
    engine/                  Indicators, signal engine, real-candle backtester
    notify/, service/        Notifications + foreground monitor
    ui/                      Chart, Signal, Learn, Journal, Settings screens
src/                         React + Vite terminal (desktop/mobile web)
  lib/api.ts                 API client (surfaces backend errors, never fabricates)
  lib/feed.ts                Real-candle feed: REST + WebSocket, offline detection
  components/                Chart, signal, MTF, prediction, ensemble, backtest panels
backend/app/
  main.py                    FastAPI routes + real feed pipeline
  services/history.py        Twelve Data history loader (memory + disk cache)
  services/market_feed.py    WebSocket ticks + REST polling, no synthetic source
  services/backtest.py       Real-candle replay of the live strategy
  services/forward_test.py   In-sample / out-of-sample split on real candles
  services/*                 Indicators, strategy, MTF, features, sentiment, journal
docs/ANDROID.md              Build the APK through GitHub Actions
docs/ARCHITECTURE.md         System design notes
```

## Run the API

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env          # put your Twelve Data key in AURUM_TWELVE_DATA_API_KEY
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

- `GET /api/v1/health` — process health.
- `GET /api/v1/data/status` — whether a real key is configured and what the feed is doing.
- `GET /docs` — full route list.

Without `AURUM_TWELVE_DATA_API_KEY` the data routes answer **503** with a Persian explanation; that
is intentional, and the web/app UI displays it instead of a chart.

## Run the web terminal

```bash
npm install
npm run dev            # frontend on :5173
npm run dev:backend    # API on :8000 (separate terminal, or use npm run dev:all)
```

The terminal shows the live feed state (live / polling / offline / no-key) and marks any cached
data as cached.

## Build the Android APK

The APK is built by GitHub Actions — nothing to install locally:

1. Add the workflow file `.github/workflows/android.yml` to the repository (it is included in this
   workspace; GitHub blocks automation tokens from pushing workflow files).
2. Optional: add a repository secret `TD_API_KEY` with your Twelve Data key so the APK ships
   pre-configured. Without it, the app asks for the key on first run.
3. Run the **Android APK** workflow (or push a change under `android/`) and download the
   `aurum-edge-apk` artifact — it contains the debug and release APKs.

Details and troubleshooting: [`docs/ANDROID.md`](docs/ANDROID.md).

## Tests

```bash
cd backend && .venv/bin/python -m pytest -q
```

`backend/tests/test_real_data_policy.py` is a guard rail: it asserts that history loading, the news
aggregator and the backtester refuse to produce data when the provider is unavailable, and that no
synthetic generator remains in the pipeline.

## Important

Decision support only — not financial advice. A phone is not an execution venue: keep ingestion,
signal generation and risk limits server-side, keep a broker-side kill switch, and validate contract
specifications and costs before risking money.
