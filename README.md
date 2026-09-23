# Trading — ساخت اپ تحلیل ترید

Native Android (Kotlin/Compose), React/Vite web terminal and FastAPI research backend. Market candles and chart signals use **real Twelve Data observations**; unavailable providers cause explicit offline/empty states, never fabricated candles or trades. Imported MetaTrader CSV is **user-supplied, unverified research data** and never becomes a live feed. **Real Nobitex/MT5 orders are disabled.**

**راهنمای فارسی وضعیت دقیق قابلیت‌ها و نیازهای ادامه:** [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md).

## Current capabilities

- **Android**: chart + Ichimoku/signal/MTF/paper journal/backtest/walk-forward; multi-symbol read-only watchlist (BTC, ETH, XAU, AAPL, USD/IRT, Iranian 18K gold) with per-symbol source selection and per-symbol *read-only* Twelve Data key, independent source prices, `CONFIRMED / CONFLICT / UNVERIFIED / NO_DATA`, paginated local SQLite quote history. Chart prices/technical bars still use Twelve Data; **live entry eligibility** now also needs independently checked, model-backed web news.
- **Journal truth / opt-in automatic PAPER**: Chart Entry/SL/TP lines are *signal plans*, not executed trades. Android «ژورنال» contains only entries actually persisted on the device; closed positions update stats on settlement, and damaged journal files are never silently replaced with `[]`. Manual LONG/SHORT still has SL/TP preview, risk caps, fresh quotes and confirmation. With a separately confirmed Settings toggle and foreground monitoring, automatic **paper-only** entries require all eight technical checks, matching fresh server-model news with publisher evidence (ninth check), live quote + MTF guard, then atomically record once per signal bar; real orders remain disabled. Without a deployed HTTPS backend plus explicitly consented server AI key, ninth check is UNKNOWN and **no automatic entry occurs**. Historical backtests cannot use current news to claim nine-way confirmation.
- **Verified educational alerts (XAU/USD only)**: With foreground monitoring and notifications enabled, only a fresh 9/9 model-backed opportunity that also passes MTF/risk/exposure checks may alert (independently of the auto-paper switch). Choose an Android audio file through the document picker, preview its brief app-played sound or use the phone's default notification channel. Alerts are persisted/deduplicated across restarts as **candidates, never trades**; actual paper entries snapshot the nine conditions in the journal. Notification/DND/background limits still apply; sound on real phones is not verified by CI. See [phone checklist](docs/ALERT_NOBITEX_FA.md).
- **Nobitex public-data training, no trading access**: Visible under Android «رمزارز», with direct public GET of BTCUSDT/BTCIRT OHLC + stats, strict schema/freshness checks and one-tap CSV export; no key or backend needed. A separate **USDT** practice ledger allows confirmed manual **BTCUSDT Spot BUY paper only**, simulated ask entry/later observed bid SL/TP/exit and an isolated journal (no fees, slippage or real fills). BTCIRT practice is **blocked**: on 2026-09-23 its OHLC price was ~1/10 of the stats price and the historical unit is not explicitly specified; raw history is labeled unverified. No XAU 9/9 AI claim, spot short, exchange account, or order endpoint is added. [Source contracts and caveats](docs/ALERT_NOBITEX_FA.md).
- **Publisher web news**: Android «دیده‌بان ← اخبار وب» shows short, attributed RSS excerpts from IRIB, YJC, Eghtesaad24, CoinDesk and BLS via an HTTPS backend. Missing/stale feeds mean `UNKNOWN`; opt-in rule-based news pause affects **manual paper entries**, while the separate XAU/USD model-backed ninth confluence is always mandatory for *signal/automatic* paper eligibility. The optional licensed `/news/fa` feed remains separate; neither is an economic calendar. An optional OpenAI model key lives only on the server, with an explicit publisher-rights consent flag; keyword fallback NEVER counts as AI.
- **Crypto screen**: new Android «رمزارز» tab, strictly read-only; CoinGecko market/supply/momentum prefilter plus exact coin-ID verification against CoinGecko's Binance pair tickers, Binance Spot volume/book/completed-candle checks, source times, cached-scan labels/expiry, thresholds and honest unavailable/empty states. Candidates are **not** predictions of a pump.
- **One-tap free historical downloads**: Android «یادگیری» gets EUR/USD and EUR/GBP *daily reference rates* from ECB via Frankfurter and *monthly* World Bank gold averages via the public-domain DataHub CSV without a key; AAPL/MSFT daily OHLC comes from Twelve Data with the user's free read-only key. Optional daily XAU/USD spot bars require Twelve Data commodity access, which may be a paid tier. It validates provenance/date/asset and exports locally to CSV. These are research data, not tradable quotes; ECB reference rates and monthly gold averages are **not candles**. Separately, the Android file picker or public HTTPS URL accepts historical MT4/MT5 CSV/TSV (explicit timezone, OHLC and timeframe validation), isolated from live trading and cache. Binary MT5 formats/Bridge not connected.
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

`GET /api/v1/data/status`, `/api/v1/news/web`, `/api/v1/crypto/candidates`, `/api/v1/execution/status` and `/docs` expose provider readiness. Android model-backed XAU alerts and the CoinGecko/Binance scanner need this backend deployed over publicly reachable **HTTPS** and its address entered in Settings; the separate public Nobitex data/training section does **not**. `localhost` on the phone is not your PC. Without a Twelve Data key market-candle routes return 503 on purpose; news/scanner work independently of it when their sources are reachable.

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
