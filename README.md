# Trading — ساخت اپ تحلیل ترید

Native Android (Kotlin/Compose), React/Vite web terminal and FastAPI research backend. Market candles and chart signals use **real Twelve Data observations**; unavailable providers cause explicit offline/empty states, never fabricated candles or trades. Imported MetaTrader/educational OHLC CSV is **user-supplied, unverified research data** and never becomes a live feed. **Real orders are disabled.**

> **2026-09-27:** the Android app was redesigned to be **forex-only** — XAU/USD plus the major pairs (EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CHF, USD/CAD, NZD/USD). The Nobitex, Iranian stocks/Agah and crypto workspaces were fully removed from the Android code. The web terminal and backend are unchanged and out of scope for this pass.

**راهنمای فارسی وضعیت دقیق قابلیت‌ها و نیازهای ادامه:** [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md).

## Current capabilities

- **Android (forex-only)**: chart + Ichimoku/signal/MTF/paper journal/backtest/walk-forward; the read-only watch covers **XAU/USD and the seven major pairs** (separate read-only Twelve Data watch key, plus a Yahoo mirror for the four USD-quote pairs). Watch prices distinguish `CONFIRMED / CONFLICT / UNVERIFIED / NO_DATA` and never feed orders or chart signals — JPY/CHF/CAD are single-source and honestly never `CONFIRMED`. Without a Twelve Data key, live prices fall back to the keyless Swissquote BBO feed (Gold-API for gold), never fabricated values. Forex chart bars use Twelve Data; **live entry eligibility** also needs independently checked, model-backed web news.
- **Removed workspaces (2026-09-27):** the Nobitex, Iranian stocks/Agah and global-crypto workspaces — tabs, screens, repositories, scanners, watch sources and settings — were deleted from the Android app, not hidden. English Forex Factory events and publisher headlines keep the explicit button that opens the short text in Google Translate outside the app. [Symbols, sources and limits](docs/WORKSPACES_FA.md).
- **Journal trade replay**: every journal row (open or closed) can draw itself on the **real candles of that period** — the stored entry/SL/TP/exit levels over bars from this device's verified cache, or a fresh read-only Twelve Data download when the cache does not reach back. Coverage is labelled `FULL`/`PARTIAL`/missing; a period the phone never received stays empty instead of being filled with invented bars, the levels come from the saved record (never recomputed by today's engine), and the picture is a record of a PAPER entry, not a broker fill or a new signal.
- **Journal truth / opt-in automatic PAPER**: Chart Entry/SL/TP lines are *signal plans*, not executed trades. Android «ژورنال» contains only entries actually persisted on the device; closed positions update stats on settlement, and damaged journal files are never silently replaced with `[]`. Manual LONG/SHORT still has SL/TP preview, risk caps, fresh quotes and confirmation. With a separately confirmed Settings toggle and foreground monitoring, automatic **paper-only** entries require all eight technical checks, matching fresh server-model news with publisher evidence (ninth check), live quote + MTF guard, then atomically record once per signal bar; real orders remain disabled. Without a deployed HTTPS backend plus explicitly consented server AI key, ninth check is UNKNOWN and **no automatic entry occurs**. Historical backtests cannot use current news to claim nine-way confirmation.
- **Verified educational alerts (XAU/USD only)**: With foreground monitoring and notifications enabled, only a fresh 9/9 model-backed opportunity that also passes MTF/risk/exposure checks can trigger an alert. If opt-in auto-paper is ON, the **entry** alert plays only after the journal's atomic write succeeds; on failure no entry/candidate sound is emitted. If auto-paper is OFF, a separately labeled **candidate-only** alert can play. Choose an Android audio file through the document picker and preview its brief app-played sound, or use the phone's default notification channel. Candidate history is persisted/deduplicated separately from trades; actual paper entries snapshot nine conditions in the journal. Notification/DND/background limits still apply; sound on real phones is not verified by CI. See [phone checklist](docs/ALERTS_FA.md).
- **Publisher web news**: Forex news puts the Forex Factory weekly calendar first, then loads attributed FXStreet/BLS headlines plus optional Persian-language public feeds. Headlines and provider links arrive **directly on the phone without server configuration**. Feed errors/age and display-only cached items are labeled; BLS monthly reports are not called breaking news. The optional HTTPS backend independently evaluates news for trading; direct headlines NEVER create `NewsGate.CLEAR` or AI evidence. Missing/stale backend feeds mean `UNKNOWN`; opt-in rule-based news pause affects **manual paper entries**, while the separate XAU/USD model-backed ninth confluence is always mandatory for *signal/automatic* paper eligibility. The optional licensed `/news/fa` feed remains separate; neither is an economic calendar. A limited free-tier Gemini 2.5 Flash-Lite BYOK option (or the legacy OpenAI provider) lives only on the server with an explicit publisher-rights consent flag; keyword fallback NEVER counts as AI.
- **One-tap free historical downloads + educational CSV imports**: Android «یادگیری» gets EUR/USD and EUR/GBP *daily reference rates* from ECB via Frankfurter and *monthly* World Bank gold averages via the public-domain DataHub CSV without a key. Optional daily XAU/USD spot bars require Twelve Data commodity access, which may be a paid tier. It validates provenance/date/asset and exports locally to CSV. These are research data, not tradable quotes; ECB reference rates and monthly gold averages are **not candles**. Separately, the Android file picker or public HTTPS URL accepts historical MT4/MT5 CSV/TSV and common educational OHLC datasets with `timestamp`/`datetime` or `DATE`+`TIME`, `OPEN/HIGH/LOW/CLOSE` and optional volume (explicit timezone, OHLC and timeframe validation), isolated from live trading and cache. Binary MT5 formats/Bridge not connected.
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

Requires JDK 17 + Android SDK 35. Alternatively use the **Android APK** GitHub Actions workflow (`.github/workflows/main.yml`) for debug and release **test** APK artifacts; `assembleRelease` depends on `testDebugUnitTest`, so failed tests prevent artifact upload. This build keeps package id `com.aurum.edge` and bumps Android to `versionCode=2` / `versionName=1.1.0`; updating over a previous install still requires the same signing certificate; use the owner-signing flow documented in [docs/ANDROID.md](docs/ANDROID.md) for stable updates. Enter the read-only Twelve Data key in the app's Settings after installation; baking even a GitHub Actions secret into an APK would disclose it. Details: [docs/ANDROID.md](docs/ANDROID.md), [android/README.md](android/README.md).

Decision support only, not financial advice. The screen covers a limited liquid spot universe, **not a meme-coin pump detector**. No official TSETMC contract, Nobitex/MT5 order adapter or real order path is claimed ready; live trading requires an independently audited, authenticated execution system, venue rules, permitted data/news and user/broker approvals. CI must compile the changed APK before it can be called installable.
