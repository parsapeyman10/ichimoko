# Trading — ساخت اپ تحلیل ترید

Native Android (Kotlin/Compose), React/Vite web terminal and FastAPI research backend. Market candles and chart signals use **real Twelve Data observations**; unavailable providers cause explicit offline/empty states, never fabricated candles or trades. Imported MetaTrader CSV is **user-supplied, unverified research data** and never becomes a live feed. **Real Nobitex/MT5 orders are disabled.**

**راهنمای فارسی وضعیت دقیق قابلیت‌ها و نیازهای ادامه:** [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md).

## Current capabilities

- **Android**: chart + Ichimoku/signal/MTF/paper journal/backtest/walk-forward; multi-symbol read-only watchlist (BTC, ETH, XAU, AAPL, USD/IRT, Iranian 18K gold) with per-symbol source selection and per-symbol *read-only* Twelve Data key, independent source prices, `CONFIRMED / CONFLICT / UNVERIFIED / NO_DATA`, paginated local SQLite quote history. Live chart/strategy still uses Twelve Data alone.
- **Two-sided paper tickets**: Android «معامله» now has manual LONG/SHORT with SL/TP preview, confirmation, risk/aggregate-exposure caps, quote freshness and one-open-position-per-symbol; engine BUY/SELL paper entries share the same checks. No live order or broker/spot short is claimed.
- **Publisher web news**: Android «دیده‌بان ← اخبار وب» shows short, attributed RSS excerpts from IRIB, YJC, Eghtesaad24, CoinDesk and BLS via an HTTPS backend. Missing/stale feeds mean `UNKNOWN`; opt-in news pause blocks **new paper entries only**. The optional licensed `/news/fa` feed remains separate; neither is an economic calendar.
- **Crypto screen**: new Android «رمزارز» tab, strictly read-only; CoinGecko market/supply/momentum prefilter plus exact coin-ID verification against CoinGecko's Binance pair tickers, Binance Spot volume/book/completed-candle checks, source times, cached-scan labels/expiry, thresholds and honest unavailable/empty states. Candidates are **not** predictions of a pump.
- **MetaTrader research**: Android file picker or public HTTPS URL for historical MT4/MT5 CSV/TSV (explicit timezone, OHLC and timeframe validation), isolated from live trading and cache. Binary MT5 formats/Bridge not connected.
- **Reports**: 18 descriptive metrics on Android paper/backtest/out-of-sample reports; backend/web backtest report includes counts by side, average win/loss/duration, streaks and per-trade, **nonannualized** Sharpe. Undefined ratios display `—`.
- **Execution API boundary**: `/api/v1/execution/status` and `/preflight` explain blockers; `/orders` always returns 503. No trading credential is sent/stored in the APK.

## Run backend

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# Put your own AURUM_TWELVE_DATA_API_KEY in .env for web chart/backtests.
# Public publisher RSS news works without a feed key; CoinGecko Demo key is recommended
# for the read-only crypto screen: AURUM_COINGECKO_DEMO_API_KEY (never inside the APK).
uvicorn app.main:app --host 0.0.0.0 --port 8000
python -m pytest -q
```

`GET /api/v1/data/status`, `/api/v1/news/web`, `/api/v1/crypto/candidates`, `/api/v1/execution/status` and `/docs` expose provider readiness. Android news and crypto tabs need this backend deployed over publicly reachable **HTTPS** and its address entered in Settings; `localhost` on the phone is not your PC. Without a Twelve Data key market-candle routes return 503 on purpose; news/scanner work independently of it when their sources are reachable.

## Run web terminal

```bash
npm ci
npm run dev           # :5173; proxies /api and /ws to backend :8000
npm run build         # TypeScript + production build
```

## Build Android

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

Requires JDK 17 + Android SDK 35. Alternatively use the **Android APK** GitHub Actions workflow (`.github/workflows/main.yml`) for debug and debug-signed “release” **test** APK artifacts; the current remote workflow has a separate non-blocking JVM-test step, but `assembleRelease` itself now depends on `testDebugUnitTest` in Gradle, so failed tests prevent artifact upload. The proposed workflow-only hardening change still needs GitHub `workflows` permission to push. Enter the read-only Twelve Data key in the app's Settings after installation; baking even a GitHub Actions secret into an APK would disclose it. Details: [docs/ANDROID.md](docs/ANDROID.md), [android/README.md](android/README.md).

Decision support only, not financial advice. The screen covers a limited liquid spot universe, **not a meme-coin pump detector**. No official TSETMC contract, Nobitex/MT5 order adapter or real order path is claimed ready; live trading requires an independently audited, authenticated execution system, venue rules, permitted data/news and user/broker approvals. CI must compile the changed APK before it can be called installable.
