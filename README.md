# Trading — ساخت اپ تحلیل ترید

Native Android (Kotlin/Compose), React/Vite web terminal and FastAPI research backend. Market candles and chart signals use **real Twelve Data observations**; unavailable providers cause explicit offline/empty states, never fabricated candles or trades. Imported MetaTrader/educational OHLC CSV is **user-supplied, unverified research data** and never becomes a live feed. **Real orders are disabled.**

> **2026-09-27:** the Android app was redesigned to be **forex-only** — XAU/USD plus the major pairs (EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CHF, USD/CAD, NZD/USD). The Nobitex, Iranian stocks/Agah and crypto workspaces were fully removed from the Android code. The web terminal and backend are unchanged and out of scope for this pass.
>
> **2026-10-08 (v1.3.4):** the market-wide trend layer is live — see `MarketTrend` below; every journal entry now records whether it was opened **with** or **against** the measured trend.
>
> **2026-10-08:** the Android universe is multi-market again — the continuous 50+ symbol radar covers crypto, gold/silver, oil/natgas/copper, FX majors and crosses, US stocks and indices, with **one paper slot per asset class** and per-market rules (see `MarketPlaybook` below). The Iranian broker/exchange workspaces stay deleted, and real orders stay disabled.

**راهنمای فارسی وضعیت دقیق قابلیت‌ها و نیازهای ادامه:** [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md).
**پاسخ ساده به «اساس ترید این اپ چیست؟»:** [docs/TRADING_BASICS_FA.md](docs/TRADING_BASICS_FA.md).

## Current capabilities

- **Android (forex-only)**: chart + Ichimoku/signal/MTF/paper journal/backtest/walk-forward; the Home screen shows the scheduled forex/gold weekend close/open time in a corner (New York schedule, not a holiday guarantee). The read-only watch covers **XAU/USD and the seven major pairs** (separate read-only Twelve Data watch key, plus a Yahoo mirror for all working symbols). Watch prices distinguish `CONFIRMED / CONFLICT / UNVERIFIED / NO_DATA` and never feed orders. Every online chart/research path requests at least **3000 real candles**: fixed public Yahoo history first, with Twelve Data only as the last fallback when a key is configured; fewer bars are labelled as a provider limitation, not filled synthetically. Live ticks try the labelled public Swissquote BBO feed first (Gold-API for gold), with Twelve Data WebSocket only as the last fallback, never fabricated values; the active-symbol fallback is polled every 1 second and displays bid/ask/spread when the source publishes them. **Live paper entry eligibility** is based on the Ichimoku technical engine plus enabled options/guards; nearby news is recorded for journal mining, not used as an entry condition.
- **Removed workspaces (2026-09-27):** the Nobitex, Iranian stocks/Agah and global-crypto workspaces — tabs, screens, repositories, scanners, watch sources and settings — were deleted from the Android app, not hidden. English Forex Factory events and publisher headlines keep the explicit button that opens the short text in Google Translate outside the app. [Symbols, sources and limits](docs/WORKSPACES_FA.md).
- **Per-market method router (`core/MarketPlaybook.kt`)**: one engine cannot be right for gold, FX, crypto, equities and natgas at once, so before any entry the market itself is measured — family from the symbol, real DST-aware session (New York/London/Tokyo/Sydney), and regime from **closed candles only** (Kaufman ER over 20 bars + ATR14 vs its 60-bar median; under 40 bars is explicitly "unknown", never a guess). It returns the only legal method (gold/FX trend-pullback in London+NY, FX mean-reversion **only** in an Asian range, crypto breakout/momentum only, US equities opening drive, oil breakout in NY, natgas strong-trend-only) plus the parameters that go with it: minimum score/confidence, allowed R:R band, allowed stop distance in ATR multiples, a **minimum real reward in bps** and a cost multiple (reward ≥ 5–6× the true round-trip cost). Stand-aside everywhere: weekend, the 17:00 NY rollover hour, volatility spikes, a dead tape, a spread wider than 25 % of ATR, or too little history. The last two numbers are also enforced inside `PaperOrderRules.preview` (clock-free), so a manual ticket whose target does not pay for its own cost cannot be created at all. Rationale and the full matrix: [docs/TRADING_BASICS_FA.md §4.3](docs/TRADING_BASICS_FA.md).
- **Market-wide trend (`core/MarketTrend.kt`)**: the "overall market trend" is measured, never guessed, in three layers — (1) the instrument's own trend on its **reference timeframe**, aggregated from the same real closed candles (M5→H1, M15→H4, H1→H4, H4→D1) and scored by six independent votes (price vs EMA50, EMA20 vs EMA50, EMA50 slope in ATR units, Kaufman ER, confirmed HH/HL vs LH/LL pivots, net 50-bar displacement in ATR units) with `≥+2` up / `≤−2` down and a 0–100 strength; under 60 closed bars it stays `UNKNOWN` and says so. (2) **Breadth** across the same continuous 50+ symbol sweep (net share of rising symbols, plus a per-family breakdown). (3) **Dollar direction** from the six majors with the correct sign per pair, and the **risk tone** from risk assets (crypto/equities/indices) versus gold and the dollar. It is then added to trades: trend-following methods may not enter against the measured trend, a range fade is only legal when there is no trend, a risk-asset entry against the market tone costs +6 minimum score and +5 minimum confidence, missing data raises the bar instead of blocking, the ranking prefers trend-aligned candidates, and a snapshot of the whole read (`MarketTrendRecord`) is stored on every journal entry and shown in that trade's window on the chart page. Position sizing deliberately does **not** change, so journal statistics stay comparable. Derivation and rules: [docs/TRADING_BASICS_FA.md §4.4](docs/TRADING_BASICS_FA.md).
- **«Why is there no trade?» diagnostics**: the Signal tab states the live prerequisites instead of staying silent — key/feed, price freshness and history depth, the background-monitor service, Android notification channels, local journal/opportunity files, news/AI evidence, the 8/8 technical + ICT gate, and the **continuous symbol scan itself** (a radar that has not swept for over three minutes turns red with its age and error). The AI companion's connection is checked first and a drop is **announced** (research notification + in-app message + a live row in that card); trading then continues on the technical rules alone, never on an invented model opinion. Symbol scanning also keeps sweeping in the foreground loop, independently of the background-monitor switch.
- **Journal trade replay**: every journal row (open or closed) can draw itself on the **real candles of that period** — the stored entry/SL/TP/exit levels over bars from this device's verified cache, or a fresh 3000-bar provider download (Twelve Data with a key, public Yahoo history without one) when the cache does not reach back. Coverage is labelled `FULL`/`PARTIAL`/missing; a period the phone never received stays empty instead of being filled with invented bars, the levels come from the saved record (never recomputed by today's engine), and the picture is a record of a PAPER entry, not a broker fill or a new signal.
- **Journal truth / opt-in automatic PAPER**: Chart Entry/SL/TP lines are *signal plans*, not executed trades. Android «ژورنال» contains only entries actually persisted on the device; closed positions update stats on settlement, and damaged journal files are never silently replaced with `[]`. Manual LONG/SHORT still has SL/TP preview, risk caps, fresh quotes and confirmation. With a separately confirmed Settings toggle and foreground monitoring, automatic **paper-only** entries require all eight technical checks, enabled optional filters, live quote + MTF/ICT guard, then atomically record once per signal bar; real orders remain disabled. News/calendar is no longer a paper-entry condition: nearby model-backed news is saved as journal-mining context only, and UNKNOWN/CONFLICT news does not erase the technical score. Historical backtests cannot use current news to claim news confirmation.
- **Verified educational alerts (XAU/USD only)**: With foreground monitoring and notifications enabled, only a fresh 8-condition technical opportunity from the base Ichimoku engine plus enabled options/guards, MTF/risk/exposure and ICT checks can trigger an alert. News/calendar is not an entry condition; nearby attributed news is mined into the journal when available. If opt-in auto-paper is ON, the **entry** alert plays only after the journal's atomic write succeeds; on failure no entry/candidate sound is emitted. If auto-paper is OFF, a separately labeled **candidate-only** alert can play. Choose an Android audio file through the document picker and preview its brief app-played sound, or use the phone's default notification channel. Candidate history is persisted/deduplicated separately from trades; actual paper entries snapshot technical/options conditions in the journal. Notification/DND/background limits still apply; sound on real phones is not verified by CI. See [phone checklist](docs/ALERTS_FA.md).
- **Publisher web news**: Forex news puts the Forex Factory weekly calendar first, then loads attributed FXStreet/BLS headlines plus optional Persian-language public feeds. Headlines and provider links arrive **directly on the phone without server configuration**. Feed errors/age and display-only cached items are labeled; BLS monthly reports are not called breaking news. The optional HTTPS backend independently evaluates news for trading; direct headlines NEVER create `NewsGate.CLEAR` or AI evidence. Missing/stale backend feeds mean `UNKNOWN`; news is displayed and mined near paper trades for later journal analysis, but it no longer gates signal/automatic paper eligibility. The optional licensed `/news/fa` feed remains separate; neither is an economic calendar. A limited free-tier Gemini 2.5 Flash-Lite BYOK option (or the legacy OpenAI provider) lives only on the server with an explicit publisher-rights consent flag; keyword fallback NEVER counts as AI.
- **One-tap free historical downloads + educational CSV imports**: Android «یادگیری» runs backtest/walk-forward on a 3000-candle real provider window even without a Twelve key (fixed public Yahoo history; no arbitrary URL). The separate CSV exporter still gets EUR/USD and EUR/GBP *daily reference rates* from ECB via Frankfurter and *monthly* World Bank gold averages via the public-domain DataHub CSV without a key. Optional daily XAU/USD spot bars require Twelve Data commodity access, which may be a paid tier. It validates provenance/date/asset and exports locally to CSV. These are research data, not tradable quotes; ECB reference rates and monthly gold averages are **not candles**. Separately, the Android file picker or public HTTPS URL accepts historical MT4/MT5 CSV/TSV and common educational OHLC datasets with `timestamp`/`datetime`/date-only or `DATE`+`TIME`, `OPEN/HIGH/LOW/CLOSE` and optional/blank volume (explicit timezone, OHLC and timeframe validation, including 1D daily research files), isolated from live trading and cache. Binary MT5 formats/Bridge not connected.
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

`GET /api/v1/data/status`, `/api/v1/news/web`, `/api/v1/crypto/candidates`, `/api/v1/execution/status` and `/docs` expose provider readiness. Android model-backed XAU alerts and the CoinGecko/Binance scanner need this backend deployed over publicly reachable **HTTPS** and its address entered in Settings; the separate public Nobitex data/training section does **not**. `localhost` on the phone is not your PC. Without a Twelve Data key the backend attempts the automatic real Swissquote/Gold-API spot fallback; until it has observed enough real bars, history/replay endpoints return an explicit 503 rather than fabricating candles. News/scanner work independently of the market key when their sources are reachable.

## Run web terminal

```bash
npm ci
npm run dev           # :5173; proxies /api and /ws to backend :8000
npm run build         # TypeScript + production build
npm run lint          # biome
npm test              # feed contract + feed hook tests
```

The Android workflow does not build the web terminal or the backend, so run these (and `pytest`) locally before merging changes under `src/**` or `backend/**`.

## Build Android

Prerequisites: JDK 17 and Android SDK 35 with build-tools 35.0.0.
Full guide for stable signing and chart capabilities: [docs/BUILD_SIGNING_FA.md](docs/BUILD_SIGNING_FA.md).

```bash
# Verify environment setup
./android/tools/check-env.sh

# Generate stable owner keystore (outside repository)
./android/tools/generate-keystore.sh ~/aurum-private/aurum-edge.jks aurum-edge

# Build APK signed with the stable owner key (Linux/macOS)
./android/tools/build-owner-apk.sh ~/aurum-private/aurum-edge.jks aurum-edge

# Build on Windows (PowerShell)
.\android\tools\build-owner-apk.ps1 -KeystorePath "$HOME\aurum-private\aurum-edge.jks" -Alias "aurum-edge"

# Or standard debug build
cd android
./gradlew testDebugUnitTest assembleDebug
```

Requires JDK 17 + Android SDK 35. Alternatively use the **Android APK** GitHub Actions workflow (`.github/workflows/main.yml`) for debug and release **test** APK artifacts; `assembleRelease` depends on `testDebugUnitTest`, so failed tests prevent artifact upload. This build keeps package id `com.aurum.edge`; `versionName` comes from `android/app/build.gradle.kts` (**1.3.2** at the time of writing) and CI sets `versionCode` to the workflow run number so every published APK outranks the installed one. Before publishing, the workflow re-verifies **signing continuity** with `apksigner`: the certificate SHA-256 of the freshly built release APK must equal the one in the newest public release, otherwise the run stops *before* publishing (an APK signed by a different key would never install over the user's current app). The in-app «بروزرسانی» tab checks both the public update manifest and public GitHub Releases APK assets (not raw Actions artifacts, which can return 401) and prefers the highest downloadable `versionCode`, so an old manifest cannot mask a newer release. The optional «دانلود خودکار نسخهٔ جدید» switch checks on app start, downloads a verified APK and opens Android's installer; Android still requires the final confirmation and a matching signing certificate. A public GitHub Release must contain an owner-signed APK for this channel; the owner-signing flow in `android/tools/build-owner-apk.sh` can produce that asset when the four signing values are configured. Enter the read-only Twelve Data key in the app's Settings after installation; baking even a GitHub Actions secret into an APK would disclose it. Details: [docs/ANDROID.md](docs/ANDROID.md), [android/README.md](android/README.md).

Decision support only, not financial advice. The screen covers a limited liquid spot universe, **not a meme-coin pump detector**. No official TSETMC contract, Nobitex/MT5 order adapter or real order path is claimed ready; live trading requires an independently audited, authenticated execution system, venue rules, permitted data/news and user/broker approvals. CI must compile the changed APK before it can be called installable.
