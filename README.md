# Trading — ساخت اپ تحلیل ترید

Native Android (Kotlin/Compose), React/Vite web terminal and FastAPI research backend. Market candles and chart signals use **real Twelve Data observations**; unavailable providers cause explicit offline/empty states, never fabricated candles or trades. Imported MetaTrader CSV is **user-supplied, unverified research data** and never becomes a live feed. **Real Nobitex/MT5 orders are disabled.**

**راهنمای فارسی وضعیت دقیق قابلیت‌ها و نیازهای ادامه:** [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md).

## Current capabilities

- **Android**: chart + Ichimoku/signal/MTF/paper journal/backtest/walk-forward; multi-symbol read-only watchlist (BTC, ETH, XAU, AAPL, USD/IRT, Iranian 18K gold) with per-symbol source selection and per-symbol *read-only* Twelve Data key, independent source prices, `CONFIRMED / CONFLICT / UNVERIFIED / NO_DATA`, paginated local SQLite quote history. Live chart/strategy still uses Twelve Data alone.
- **Persian news**: optional licensed Persian RSS/Atom through the backend (Android news view); conservative high-impact analysis and opt-in fail-closed pause for **new paper trades**. No feed/license = no headlines and guard `UNKNOWN`; not a full economic calendar.
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
# For licensed Persian news configure AURUM_FA_NEWS_RSS_URL and AURUM_FA_NEWS_ALLOWED_HOST.
uvicorn app.main:app --host 0.0.0.0 --port 8000
python -m pytest -q
```

`GET /api/v1/data/status`, `/api/v1/news/fa`, `/api/v1/execution/status` and `/docs` describe provider readiness. Without a Twelve Data key, market routes return 503 on purpose. News route works independently of a market-data key.

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

Requires JDK 17 + Android SDK 35. Alternatively use the **Android APK** GitHub Actions workflow (`.github/workflows/main.yml`) for debug and debug-signed “release” **test** APK artifacts; on the current remote workflow JVM tests are non-blocking, so inspect their result separately. The proposed fail-on-test workflow change needs GitHub `workflows` permission to push. Enter the read-only Twelve Data key in the app's Settings after installation; baking even a GitHub Actions secret into an APK would disclose it. Details: [docs/ANDROID.md](docs/ANDROID.md), [android/README.md](android/README.md).

Decision support only, not financial advice. No official TSETMC contract, Nobitex/MT5 integration, Meme Scanner or real order path is claimed ready; live trading requires an independently audited server-side execution system, licensed data/news and user/broker approvals.
