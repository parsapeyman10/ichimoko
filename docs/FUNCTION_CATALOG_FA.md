# کاتالوگ کامل فانکشن‌های مخزن (استخراج خودکار از سورس)

> این فایل با `docs/flowchart/generate_flowchart.py` ساخته می‌شود؛ دستی ویرایشش نکنید.
> مجموع: 258 فایل · 45557 خط · 1357 فانکشن. نقشهٔ تصویری تعاملی: `docs/flowchart/index.html`

## اپ اندروید — کد اصلی — `android/app/src/main/java/com/aurum/edge` (140 فایل · 851 فانکشن)

### `AurumApplication.kt` — 17 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onCreate()` | AurumApplication | 12 | — |

### `MainActivity.kt` — 25 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onCreate()` | MainActivity | 13 | — |

### `core/AlertDiagnostics.kt` — 107 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `checks()` | AlertDiagnostics | 18 | — |
| `ago()` | AlertDiagnostics | 99 | — |

### `core/AppContainer.kt` — 249 خط · 4 فانکشن
> Process-wide wiring. Single source of truth for settings, real market data, journal and engine.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `exportJournalPdf()` | AppContainer | 146 | — |
| `downloadCandles()` | AppContainer | 164 | — |
| `cachedCandles()` | AppContainer | 232 | — |
| `fetchTradeCandles()` | AppContainer | 240 | — |

### `core/BtcAresModels.kt` — 114 خط · 1 فانکشن
> BTC-ARES — per-engine report and aggregate record for BTC/USDT.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `matches()` | BtcAresRecord | 80 | — |

### `core/BtcAresPolicy.kt` — 53 خط · 2 فانکشن
> Paper-entry safeguards for the BTC-ARES channel.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `appliesTo()` | BtcAresPolicy | 28 | — |
| `quoteBlocker()` | BtcAresPolicy | 30 | — |

### `core/CryptoCompressionPolicy.kt` — 43 خط · 2 فانکشن
> London and New York DST are evaluated by their own IANA clocks; no Asian session.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `londonOrNewYork()` | CryptoCompressionPolicy | 18 | — |
| `quoteBlocker()` | CryptoCompressionPolicy | 28 | — |

### `core/EurAresModels.kt` — 200 خط · 3 فانکشن
> EUR-ARES — Adaptive Euro-Dollar Regime, Execution & Signal Intelligence System (EUR/USD, M5).

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `matches()` | EurAresRecord | 147 | — |
| `of()` | EurPips | 198 | — |
| `format()` | EurPips | 199 | — |

### `core/EurAresPolicy.kt` — 231 خط · 9 فانکشن
> EUR-ARES execution policy for EUR/USD paper entries.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `appliesTo()` | EurAresPolicy | 63 | — |
| `clocks()` | EurAresPolicy | 66 | — |
| `sessionAt()` | EurAresPolicy | 85 | — |
| `sessionLabel()` | EurAresPolicy | 112 | — |
| `isTradableSession()` | EurAresPolicy | 122 | — |
| `roundTripCostPips()` | EurAresPolicy | 130 | — |
| `netRewardRisk()` | EurAresPolicy | 145 | — |
| `positionLots()` | EurAresPolicy | 159 | — |
| `quoteBlocker()` | EurAresPolicy | 194 | — |

### `core/FeedLiveness.kt` — 40 خط · 4 فانکشن
> This is a presentation/safety downgrade ONLY: it never fabricates a tick or extends its time.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `hasRecentReceipt()` | FeedLiveness | 10 | — |
| `display()` | FeedLiveness | 15 | — |
| `freshDetail()` | FeedLiveness | 29 | — |
| `looksLikeLiveTickProvider()` | FeedLiveness | 35 | — |

### `core/HistoryPolicy.kt` — 58 خط · 6 فانکشن
> Candle-count policy (the old flat "1500 M5 candles for everything" rule is retired):

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchSize()` | HistoryPolicy | 32 | — |
| `minSize()` | HistoryPolicy | 40 | — |
| `chartTargetCandles()` | HistoryPolicy | 47 | — |
| `providerRequestSize()` | HistoryPolicy | 50 | — |
| `deepProviderRequestSize()` | HistoryPolicy | 53 | — |
| `trimForCache()` | HistoryPolicy | 56 | — |

### `core/IctEntryRules.kt` — 147 خط · 3 فانکشن
> Plan an SL beyond the swept wick and TP *before* the opposing range line. This

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `withSafePlan()` | IctEntryRules | 23 | — |
| `assess()` | IctEntryRules | 58 | `#fn-entrysetup` در نقشهٔ HTML |
| `approvedEvidence()` | IctEntryRules | 114 | — |

### `core/LiveTradeValuation.kt` — 38 خط · 2 فانکشن
> An entry is a saved fill, NEVER a replacement for a missing current quote.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `from()` | LiveTradeValuation | 17 | — |
| `total()` | LiveTradeValuation | 34 | — |

### `core/MarketHours.kt` — 131 خط · 8 فانکشن
> Price/provider timestamps still decide freshness.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `weekendClosedFor()` | MarketHours | 31 | — |
| `closedFor()` | MarketHours | 41 | — |
| `forexWeekendClosed()` | MarketHours | 52 | — |
| `scheduledWeekendClosed()` | MarketHours | 55 | — |
| `sessionWindowFor()` | MarketHours | 69 | — |
| `sessionWindow()` | MarketHours | 96 | — |
| `sessionWindow()` | MarketHours | 98 | — |
| `nextAt()` | MarketHours | 126 | — |

### `core/MarketPlaybook.kt` — 536 خط · 13 فانکشن
> «کدام روش، برای کدام بازار، در چه ساعتی» — the per-market method router.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `familyOf()` | MarketPlaybook | 130 | — |
| `sessionOf()` | MarketPlaybook | 153 | — |
| `isThinLiquidity()` | MarketPlaybook | 195 | — |
| `minRewardBpsFor()` | MarketPlaybook | 206 | — |
| `costRewardMultipleFor()` | MarketPlaybook | 221 | — |
| `rrBounds()` | MarketPlaybook | 228 | — |
| `stopAtrBounds()` | MarketPlaybook | 236 | — |
| `assess()` | MarketPlaybook | 255 | `#fn-entrysetup` در نقشهٔ HTML |
| `chooseMethod()` | MarketPlaybook | 345 | — |
| `efficiencyRatio()` | MarketPlaybook | 485 | — |
| `median()` | MarketPlaybook | 494 | — |
| `buildReason()` | MarketPlaybook | 501 | — |
| `fmt()` | MarketPlaybook | 534 | — |

### `core/MarketTrend.kt` — 678 خط · 16 فانکشن
> «روند کلی بازار» — چه چیزی اندازه گرفته می‌شود و چطور به خودِ معامله اضافه می‌شود.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `higherFor()` | MarketTrend | 205 | — |
| `symbolTrend()` | MarketTrend | 222 | — |
| `measure()` | MarketTrend | 299 | — |
| `structureOf()` | MarketTrend | 366 | — |
| `directionOf()` | MarketTrend | 393 | — |
| `strengthOf()` | MarketTrend | 400 | — |
| `overall()` | MarketTrend | 411 | — |
| `familyTrendOf()` | MarketTrend | 469 | — |
| `dollarBias()` | MarketTrend | 485 | — |
| `riskTone()` | MarketTrend | 513 | — |
| `alignmentOf()` | MarketTrend | 536 | — |
| `entryGate()` | MarketTrend | 555 | — |
| `contextOf()` | MarketTrend | 654 | — |
| `sideLabel()` | MarketTrend | 667 | — |
| `fmt()` | MarketTrend | 673 | — |
| `fmtPct()` | MarketTrend | 676 | — |

### `core/Models.kt` — 801 خط · 19 فانکشن
> Real market data contracts.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fromLabel()` | Interval | 39 | — |
| `of()` | AssetClass | 64 | — |
| `fromId()` | StrategyKind | 101 | — |
| `activeLabels()` | SignalProfile | 129 | — |
| `persistName()` | SignalProfile | 143 | — |
| `fromName()` | SignalProfile | 160 | — |
| `matches()` | IchimokuEntryEvidence | 197 | — |
| `matches()` | CryptoBreakoutEvidence | 215 | — |
| `matches()` | Als5OpportunityRecord | 263 | — |
| `matches()` | IctPriceActionRecord | 426 | — |
| `from()` | PaperConditionRecord | 558 | — |
| `from()` | PaperOpportunity | 582 | — |
| `from()` | SymbolTrendRecord | 625 | — |
| `from()` | MarketTrendRecord | 663 | — |
| `from()` | MtfSnapshotRecord | 707 | — |
| `apiKeyFor()` | AppSettings | 788 | — |
| `hasKeyFor()` | AppSettings | 791 | — |
| `dedicatedKeySymbols()` | AppSettings | 794 | — |
| `strategyFor()` | AppSettings | 799 | — |

### `core/MonitorCadence.kt` — 34 خط · 2 فانکشن
> Adaptive monitoring cadence, proportional to the traded timeframe:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `isHot()` | MonitorCadence | 25 | — |
| `sweepDelayMs()` | MonitorCadence | 29 | — |

### `core/OilEntrySafety.kt` — 14 خط · 1 فانکشن
> Fail-closed quarantine for NEW paper oil entries until the user's losing trades can be

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | OilEntrySafety | 12 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperAlertRules.kt` — 42 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | PaperAlertRules | 8 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperAutoRules.kt` — 136 خط · 2 فانکشن
> News/AI layers were removed: only feed freshness, source integrity, technical evidence

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | PaperAutoRules | 10 | `#fn-autorules` در نقشهٔ HTML |
| `opportunityBlocker()` | PaperAutoRules | 96 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperOrderRules.kt` — 161 خط · 5 فانکشن
> سقف اهرم همان بازار، از [VenueSpecs]: جفت‌ارز اصلی ۳۰:۱ · جفت‌ارز غیراصلی و طلا ۲۰:۱ ·

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `defaultLeverageFor()` | PaperOrderRules | 34 | — |
| `paperable()` | PaperOrderRules | 37 | — |
| `unitFor()` | PaperOrderRules | 43 | — |
| `quotePnlToUsd()` | PaperOrderRules | 57 | — |
| `preview()` | PaperOrderRules | 66 | `#fn-ticket` در نقشهٔ HTML |

### `core/PaperPortfolioPolicy.kt` — 85 خط · 1 فانکشن
> Closed trades, including those from an earlier installation, remain in the UTC-day loss

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | PaperPortfolioPolicy | 18 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperTrailing.kt` — 40 خط · 1 فانکشن
> prices only. Original stop/risk stays immutable for R and portfolio budgeting. On an OHLC

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `advance()` | PaperTrailing | 11 | `#fn-settle` در نقشهٔ HTML |

### `core/TechnicalEvidence.kt` — 106 خط · 4 فانکشن
> do not use them to authorize new automatic entries. News is a separate AI supervision layer.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `items()` | TechnicalEvidence | 42 | — |
| `confirmed()` | TechnicalEvidence | 44 | — |
| `confirmed()` | TechnicalEvidence | 55 | — |
| `confirmedRecords()` | TechnicalEvidence | 94 | — |

### `core/TradeQuotePolicy.kt` — 10 خط · 1 فانکشن
> A historical candle is useful for chart analysis but must never impersonate a trade tick.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `accepts()` | TradeQuotePolicy | 7 | — |

### `core/TradeReplay.kt` — 130 خط · 3 فانکشن
> Chart window of an *already recorded* paper trade.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `barTimeOf()` | TradeReplay | 42 | — |
| `window()` | TradeReplay | 52 | — |
| `detail()` | TradeReplay | 99 | — |

### `core/V1Universe.kt` — 67 خط · 4 فانکشن
> Focused 15-symbol trading universe:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `normalize()` | V1Universe | 44 | — |
| `valid()` | V1Universe | 52 | — |
| `watchlist()` | V1Universe | 57 | — |
| `fromStored()` | CategoryStrategy | 65 | — |

### `core/VenueSpecs.kt` — 172 خط · 6 فانکشن
> واقعی‌ترین مرجع قابل‌اتکا برای مقادیر SL/TP/Risk: به‌جای اعداد دلبخواهی، همان اعدادی که

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `spreadCostUsd()` | VenueSpec | 46 | — |
| `commissionUsd()` | VenueSpec | 50 | — |
| `minStopDistance()` | VenueSpec | 58 | — |
| `of()` | VenueSpecs | 77 | — |
| `isMajorPair()` | VenueSpecs | 163 | — |
| `minStopDistance()` | VenueSpecs | 171 | — |

### `core/VolumeSessionStats.kt` — 26 خط · 1 فانکشن
> Require at least five separate closed H1 bars at EVERY UTC hour within 14 days. Zero/unknown

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `cryptoHours()` | VolumeSessionStats | 13 | — |

### `core/XagAresModels.kt` — 156 خط · 2 فانکشن
> ALS-5S — per-engine report and aggregate record for XAG/USD (silver).

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `unavailable()` | XagGoldContext | 65 | — |
| `matches()` | XagAresRecord | 119 | — |

### `core/XagAresPolicy.kt` — 122 خط · 5 فانکشن
> ALS-5S entry safeguards for silver paper entries.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `appliesTo()` | XagAresPolicy | 35 | — |
| `roundTripCostPerOz()` | XagAresPolicy | 42 | — |
| `netRewardRisk()` | XagAresPolicy | 52 | — |
| `positionOz()` | XagAresPolicy | 65 | — |
| `quoteBlocker()` | XagAresPolicy | 92 | — |

### `data/AppNetwork.kt` — 105 خط · 6 فانکشن
> when Android routes this app through it. No embedded proxy, custom DNS or TLS bypass. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `refreshSystemNetwork()` | AppNetwork | 36 | — |
| `observeNetwork()` | AppNetwork | 47 | — |
| `select()` | Route | 62 | — |
| `connectFailed()` | Route | 64 | — |
| `builder()` | AppNetwork | 69 | — |
| `connectionAcquired()` | Route | 80 | — |

### `data/AppUpdateRepository.kt` — 755 خط · 26 فانکشن
> Sideload-friendly updater. Android still requires the system package installer confirmation;

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `installedSigningDiagnostic()` | AppUpdateRepository | 112 | — |
| `checkForUpdate()` | AppUpdateRepository | 162 | `#fn-updater` در نقشهٔ HTML |
| `describeChannel()` | Seen | 193 | — |
| `downloadAvailable()` | AppUpdateRepository | 247 | — |
| `downloadAvailableLocked()` | AppUpdateRepository | 252 | — |
| `installDownloaded()` | AppUpdateRepository | 371 | — |
| `resumePendingInstall()` | AppUpdateRepository | 407 | — |
| `canInstallUnknownSources()` | AppUpdateRepository | 411 | — |
| `openInstallPermissionSettings()` | AppUpdateRepository | 414 | — |
| `chooseUpdate()` | AppUpdateRepository | 424 | `#fn-updater` در نقشهٔ HTML |
| `versionRank()` | AppUpdateRepository | 436 | — |
| `checkManifest()` | AppUpdateRepository | 442 | `#fn-updater` در نقشهٔ HTML |
| `checkLatestRelease()` | AppUpdateRepository | 488 | `#fn-updater` در نقشهٔ HTML |
| `requestJson()` | AppUpdateRepository | 563 | — |
| `downloadApk()` | AppUpdateRepository | 596 | `#fn-updater` در نقشهٔ HTML |
| `sha256()` | AppUpdateRepository | 674 | — |
| `signingCertificateDigests()` | AppUpdateRepository | 691 | — |
| `isTrustedReleaseAsset()` | AppUpdateRepository | 705 | — |
| `isTrustedMetadataAsset()` | AppUpdateRepository | 713 | — |
| `normalizeSha256()` | AppUpdateRepository | 721 | — |
| `safeFileToken()` | AppUpdateRepository | 730 | — |
| `semanticVersion()` | AppUpdateRepository | 735 | — |
| `compareVersions()` | AppUpdateRepository | 739 | — |
| `string()` | AppUpdateRepository | 748 | — |
| `long()` | AppUpdateRepository | 749 | — |
| `boolean()` | AppUpdateRepository | 753 | — |

### `data/BinanceAggregateStream.kt` — 46 خط · 5 فانکشن
> Callback only queues the original publication; journal IO runs on the consumer coroutine.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `open()` | BinanceAggregateStream | 26 | `#fn-journal-open` در نقشهٔ HTML |
| `onOpen()` | Invalid | 31 | — |
| `onMessage()` | Invalid | 32 | — |
| `onFailure()` | Invalid | 37 | — |
| `onClosed()` | Invalid | 40 | — |

### `data/BinanceSpotClient.kt` — 160 خط · 12 فانکشن
> aggTrade is one aggregate per taker order/price, not every constituent fill. No API key.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ticker()` | BinanceSpotClient | 32 | — |
| `parseAggregate()` | BinanceSpotClient | 35 | — |
| `parseAggregate()` | BinanceSpotClient | 41 | — |
| `field()` | BinanceSpotClient | 47 | — |
| `parsePage()` | BinanceSpotClient | 58 | — |
| `parseCandles()` | BinanceSpotClient | 69 | — |
| `num()` | BinanceSpotClient | 74 | — |
| `resolution()` | BinanceSpotClient | 90 | — |
| `get()` | BinanceSpotClient | 99 | — |
| `fetchAggregates()` | BinanceSpotClient | 114 | — |
| `fetchQuote()` | BinanceSpotClient | 128 | — |
| `fetchCandles()` | BinanceSpotClient | 132 | — |

### `data/CandleCache.kt` — 80 خط · 6 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `file()` | CandleCache | 20 | — |
| `verify()` | CandleCache | 25 | — |
| `load()` | CandleCache | 39 | — |
| `save()` | CandleCache | 48 | — |
| `merge()` | CandleCache | 64 | — |
| `clear()` | CandleCache | 73 | — |

### `data/CandleSourceHealth.kt` — 30 خط · 2 فانکشن
> Validates that a source returned enough fresh, closed, ordered candles for

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `requiredSize()` | CandleSourceHealth | 12 | — |
| `ready()` | CandleSourceHealth | 14 | — |

### `data/CatalogPaperTrader.kt` — 74 خط · 3 فانکشن
> timestamped trade ticks. Nobitex executed USDT trades are separate from UDF candle closes.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `tickMonitored()` | CatalogQuotePolicy | 19 | — |
| `rejection()` | CatalogQuotePolicy | 23 | — |
| `review()` | CatalogPaperTrader | 43 | `#auto-entry` در نقشهٔ HTML |

### `data/ChartSymbolChoices.kt` — 25 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `search()` | ChartSymbolChoices | 15 | — |
| `display()` | ChartSymbolChoices | 24 | — |

### `data/CryptoCatalog.kt` — 47 خط · 3 فانکشن
> Top 5 high-volume, most actively traded USDT spot crypto pairs.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `find()` | CryptoCatalog | 33 | — |
| `isCrypto()` | CryptoCatalog | 34 | — |
| `digitsFor()` | CryptoCatalog | 36 | — |

### `data/CryptoIntervalChoice.kt` — 16 خط · 1 فانکشن
> become an entry base; M15/H1 remain independent confirmation, never entry choices.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `pick()` | CryptoIntervalChoice | 13 | — |

### `data/CryptoRadarBundle.kt` — 129 خط · 3 فانکشن
> Crypto history bundle (5 USDT spot pairs):

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `dedicatedSource()` | Snapshot | 46 | — |
| `fetch()` | CryptoRadarBundle | 56 | — |
| `readBundle()` | CryptoRadarBundle | 108 | — |

### `data/CryptoRadarCandleClient.kt` — 364 خط · 23 فانکشن
> Exact USDT pair is checked against the venue's live SPOT listing, not a guessed ticker.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `removeEldestEntry()` | Cached | 47 | — |
| `get()` | CryptoRadarCandleClient | 50 | — |
| `pair()` | CryptoRadarCandleClient | 63 | — |
| `checkListing()` | CryptoRadarCandleClient | 74 | — |
| `fetch()` | CryptoRadarCandleClient | 122 | — |
| `fetchUncached()` | CryptoRadarCandleClient | 136 | — |
| `reusable()` | CryptoRadarCandleClient | 209 | — |
| `fetchWatchTrade()` | CryptoRadarCandleClient | 216 | — |
| `validMexcListing()` | CryptoRadarCandleClient | 236 | — |
| `parseMexc()` | CryptoRadarCandleClient | 242 | — |
| `parseMexcTrade()` | CryptoRadarCandleClient | 256 | — |
| `parseKrakenTrade()` | CryptoRadarCandleClient | 262 | — |
| `parseKraken()` | CryptoRadarCandleClient | 277 | — |
| `parseGateTrade()` | CryptoRadarCandleClient | 295 | — |
| `parseOkxTrade()` | CryptoRadarCandleClient | 304 | — |
| `checkedTrade()` | CryptoRadarCandleClient | 314 | — |
| `parseGate()` | CryptoRadarCandleClient | 323 | — |
| `parseOkx()` | CryptoRadarCandleClient | 335 | — |
| `checkedCandle()` | CryptoRadarCandleClient | 348 | — |
| `text()` | CryptoRadarCandleClient | 359 | — |
| `array()` | CryptoRadarCandleClient | 360 | — |
| `obj()` | CryptoRadarCandleClient | 361 | — |
| `string()` | CryptoRadarCandleClient | 362 | — |

### `data/CryptoVenuePolicy.kt` — 57 خط · 5 فانکشن
> Crypto venue identity: candles used to place levels and the executed entry/exit tick must

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `compatible()` | CryptoVenuePolicy | 13 | — |
| `sameVenue()` | CryptoVenuePolicy | 19 | — |
| `venueOf()` | CryptoVenuePolicy | 26 | — |
| `entryVenue()` | CryptoVenuePolicy | 46 | — |
| `tickVenueAvailable()` | CryptoVenuePolicy | 53 | — |

### `data/DecisionLogStore.kt` — 117 خط · 5 فانکشن
> Passwords, model prompts, API keys and raw feed URLs are deliberately not stored here.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `newThread()` | DecisionLogStore | 56 | — |
| `submit()` | DecisionLogStore | 69 | — |
| `append()` | DecisionLogStore | 77 | — |
| `appendOnWorker()` | DecisionLogStore | 88 | — |
| `toLayer()` | DecisionLogStore | 116 | — |

### `data/DukascopyHistoryClient.kt` — 456 خط · 20 فانکشن
> Keyless historical candles from Dukascopy's public datafeed.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchCandles()` | DukascopyHistoryClient | 58 | — |
| `fetchChartCandles()` | DukascopyHistoryClient | 114 | — |
| `fetchFileCandles()` | DukascopyHistoryClient | 153 | — |
| `chartProvider()` | DukascopyHistoryClient | 179 | — |
| `fileProvider()` | DukascopyHistoryClient | 181 | — |
| `fetchDailyMinuteCandles()` | DukascopyHistoryClient | 184 | — |
| `fetchMonthlyHourCandles()` | DukascopyHistoryClient | 216 | — |
| `fetchYearlyDayCandles()` | DukascopyHistoryClient | 248 | — |
| `fetchBytesOrNull()` | DukascopyHistoryClient | 277 | — |
| `two()` | FileCandles | 301 | — |
| `zeroMonth()` | FileCandles | 302 | — |
| `instrument()` | FileCandles | 304 | — |
| `chartInstrument()` | FileCandles | 339 | — |
| `chartInterval()` | FileCandles | 346 | — |
| `pointScale()` | FileCandles | 356 | — |
| `parseCandlePayload()` | FileCandles | 362 | — |
| `parseRawCandlePayload()` | FileCandles | 375 | — |
| `parseChartPayload()` | FileCandles | 412 | — |
| `number()` | FileCandles | 445 | — |
| `validOhlc()` | FileCandles | 448 | — |

### `data/HistorySourceSelection.kt` — 19 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `record()` | HistorySourceAudits | 16 | — |

### `data/JournalPdfExporter.kt` — 95 خط · 2 فانکشن
> Renders a REAL PDF straight from the trades already saved in [com.aurum.edge.data.JournalStore] —

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `export()` | JournalPdfExporter | 26 | — |
| `ensureSpace()` | JournalPdfExporter | 42 | — |

### `data/JournalStore.kt` — 618 خط · 11 فانکشن
> Journal of *paper* trades executed at *real* prices.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `load()` | JournalStore | 52 | — |
| `persist()` | JournalStore | 71 | — |
| `open()` | JournalStore | 88 | `#fn-journal-open` در نقشهٔ HTML |
| `applyCryptoClosedBars()` | JournalStore | 333 | `#fn-settle` در نقشهٔ HTML |
| `settle()` | JournalStore | 362 | `#fn-settle` در نقشهٔ HTML |
| `noteTickCoverageGap()` | JournalStore | 423 | — |
| `settleTick()` | JournalStore | 438 | `#fn-settle` در نقشهٔ HTML |
| `settleBinanceBatch()` | JournalStore | 514 | `#fn-settle` در نقشهٔ HTML |
| `close()` | JournalStore | 567 | — |
| `clear()` | JournalStore | 586 | — |
| `stats()` | JournalStore | 589 | — |

### `data/JsonPath.kt` — 49 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `first()` | JsonPath | 12 | — |
| `number()` | JsonPath | 21 | — |
| `numbers()` | JsonPath | 26 | — |
| `read()` | JsonPath | 34 | `#fn-alignment` در نقشهٔ HTML |

### `data/MarketEntryReason.kt` — 28 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `forClass()` | MarketEntryReason | 8 | — |

### `data/MarketRepository.kt` — 1127 خط · 39 فانکشن
> Reference frames must come from the publisher of the BASE, including manual crypto mode.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `canRefreshSamePublisherFrames()` | — | 38 | — |
| `hasCurrentRestBar()` | — | 44 | — |
| `isCurrentIntervalTick()` | — | 50 | — |
| `marketClosed()` | MarketRepository | 103 | — |
| `barsSnapshot()` | MarketRepository | 162 | — |
| `barsClosedCount()` | MarketRepository | 164 | — |
| `withBars()` | MarketRepository | 166 | — |
| `attach()` | MarketRepository | 173 | — |
| `start()` | MarketRepository | 178 | — |
| `stop()` | MarketRepository | 217 | — |
| `restart()` | MarketRepository | 239 | — |
| `setInterval()` | MarketRepository | 247 | — |
| `refreshNow()` | MarketRepository | 253 | — |
| `loadCacheThenRefresh()` | MarketRepository | 258 | — |
| `refresh()` | MarketRepository | 279 | `#fn-market-refresh` در نقشهٔ HTML |
| `hasRecentStream()` | MarketRepository | 533 | — |
| `reportRestFailure()` | MarketRepository | 536 | — |
| `startPolling()` | MarketRepository | 546 | — |
| `startStream()` | MarketRepository | 559 | — |
| `stillCurrent()` | MarketRepository | 585 | — |
| `collectSpotFallback()` | MarketRepository | 588 | — |
| `streamTwelve()` | MarketRepository | 694 | — |
| `startWatchdog()` | MarketRepository | 754 | `#fn-publish` در نقشهٔ HTML |
| `registerNetworkCallback()` | MarketRepository | 801 | — |
| `onAvailable()` | MarketRepository | 806 | — |
| `onLost()` | MarketRepository | 821 | — |
| `onTick()` | MarketRepository | 835 | `#fn-publish` در نقشهٔ HTML |
| `publishStreamUnavailable()` | MarketRepository | 920 | — |
| `publishClosed()` | MarketRepository | 932 | — |
| `publishDelayed()` | MarketRepository | 942 | — |
| `publishOffline()` | MarketRepository | 966 | — |
| `trimCachedBars()` | MarketRepository | 975 | — |
| `persistCache()` | MarketRepository | 981 | — |
| `evaluateAndPublish()` | MarketRepository | 989 | — |
| `publishCandles()` | MarketRepository | 993 | `#fn-publish` در نقشهٔ HTML |
| `nobitexFrame()` | MarketRepository | 1081 | — |
| `marketProviderLabel()` | MarketRepository | 1098 | — |
| `currentPeriodStart()` | MarketRepository | 1108 | — |
| `hasInternet()` | MarketRepository | 1113 | — |

### `data/NobitexCryptoReference.kt` — 119 خط · 4 فانکشن
> Nobitex is the app's **reference venue for crypto**.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `serves()` | NobitexReference | 25 | — |
| `isHistoryProvider()` | NobitexReference | 29 | — |
| `fetch()` | NobitexCryptoBundle | 69 | — |
| `fetchFrame()` | NobitexCryptoBundle | 108 | — |

### `data/NobitexHistoryClient.kt` — 186 خط · 7 فانکشن
> Crypto candles from Nobitex, for networks where Binance is unreachable.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `resolution()` | NobitexHistoryClient | 45 | — |
| `ticker()` | NobitexHistoryClient | 60 | — |
| `parseUdf()` | NobitexHistoryClient | 72 | — |
| `column()` | NobitexHistoryClient | 86 | — |
| `fetchCandles()` | NobitexHistoryClient | 122 | — |
| `fetchWindow()` | NobitexHistoryClient | 145 | — |
| `fetchThreeMinuteCandles()` | NobitexHistoryClient | 160 | — |

### `data/NobitexOrderBook.kt` — 68 خط · 2 فانکشن
> Best two-sided prices from Nobitex's public order book (`GET /v2/orderbook/{SYMBOL}`).

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse()` | NobitexOrderBook | 38 | — |
| `levels()` | NobitexOrderBook | 60 | — |

### `data/NobitexPublicTradeStream.kt` — 152 خط · 9 فانکشن
> recoverable subscriptions. Each OPEN pair gets a subscription (not a Binance ticker), and

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `open()` | NobitexPublicTradeStream | 34 | `#fn-journal-open` در نقشهٔ HTML |
| `onSubscribed()` | Invalid | 43 | — |
| `onSubscribing()` | Invalid | 47 | — |
| `onUnsubscribed()` | Invalid | 50 | — |
| `onPublication()` | Invalid | 53 | — |
| `parse()` | NobitexStreamTrade | 74 | — |
| `subscribed()` | TradeStreamCursor | 104 | — |
| `rest()` | TradeStreamCursor | 113 | — |
| `publication()` | TradeStreamCursor | 122 | — |

### `data/NobitexTickArchiveClient.kt` — 83 خط · 3 فانکشن
> A local sequence is a persistence cursor, not an exchange trade ID; publisher gaps in the

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetch()` | NobitexTickArchiveClient | 28 | — |
| `parse()` | Page | 45 | — |
| `text()` | Page | 48 | — |

### `data/NobitexTradeBatch.kt` — 76 خط · 2 فانکشن
> endpoint: an overlapping page proves coverage only while the client remains connected.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse()` | NobitexTradeBatch | 19 | — |
| `advance()` | TradePageCursor | 54 | `#fn-settle` در نقشهٔ HTML |

### `data/NobitexTradeBudget.kt` — 20 خط · 1 فانکشن
> Reserve at least 40 of 55 attempts for at most two open crypto positions (3s/pair),

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `tryAcquire()` | NobitexTradeBudget | 12 | — |

### `data/NobitexTradeQuote.kt` — 34 خط · 1 فانکشن
> Only a same-pair, recently executed trade can authorize a paper entry or exit.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse()` | NobitexTradeQuote | 17 | — |

### `data/NonCryptoRadarBundle.kt` — 118 خط · 2 فانکشن
> Non-crypto history bundle (Gold, Silver, Dow Jones, 7 Ichimoku Forex pairs):

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetch()` | NonCryptoRadarBundle | 37 | — |
| `readBundle()` | NonCryptoRadarBundle | 93 | — |

### `data/OpenPositionDisplay.kt` — 34 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `record()` | OpenPositionDisplay | 18 | — |
| `forTrade()` | OpenPositionDisplay | 27 | — |
| `onlyOpen()` | OpenPositionDisplay | 32 | — |

### `data/OpenPositionQuotePolicy.kt` — 32 خط · 3 فانکشن
> ordered, same-venue execution feed has been replayed. Crypto valuations must come

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `monitored()` | OpenPositionQuotePolicy | 15 | — |
| `prices()` | OpenPositionQuotePolicy | 18 | — |
| `selectedFallback()` | OpenPositionQuotePolicy | 23 | — |

### `data/OpenTickSnapshot.kt` — 15 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `updated()` | OpenTickSnapshot | 8 | — |

### `data/OpenTradeTickMonitor.kt` — 533 خط · 18 فانکشن
> WebSocket publications are the primary ordered path while the user-enabled foreground

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `setFeed()` | OpenTradeTickMonitor | 57 | — |
| `session()` | OpenTradeTickMonitor | 64 | — |
| `stillOpen()` | OpenTradeTickMonitor | 68 | — |
| `forget()` | OpenTradeTickMonitor | 73 | — |
| `setFeedRemoved()` | OpenTradeTickMonitor | 77 | — |
| `poll()` | OpenTradeTickMonitor | 85 | `#fn-tickmonitor` در نقشهٔ HTML |
| `binancePosition()` | OpenTradeTickMonitor | 91 | — |
| `nobitexPosition()` | OpenTradeTickMonitor | 95 | — |
| `syncCryptoClosedBars()` | OpenTradeTickMonitor | 103 | — |
| `rightVenue()` | Session | 119 | — |
| `quoteForManualClose()` | OpenTradeTickMonitor | 147 | — |
| `archivePosition()` | OpenTradeTickMonitor | 157 | — |
| `pollArchive()` | OpenTradeTickMonitor | 161 | `#fn-tickmonitor` در نقشهٔ HTML |
| `watchArchive()` | OpenTradeTickMonitor | 197 | — |
| `pollPage()` | OpenTradeTickMonitor | 222 | — |
| `pollBinance()` | OpenTradeTickMonitor | 276 | — |
| `watchBinance()` | OpenTradeTickMonitor | 329 | `#fn-tickmonitor` در نقشهٔ HTML |
| `watch()` | OpenTradeTickMonitor | 441 | `#fn-tickmonitor` در نقشهٔ HTML |

### `data/PairScanner.kt` — 716 خط · 10 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `attachAutoReview()` | PairScanner | 135 | — |
| `cryptoBook()` | PairScanner | 165 | — |
| `refreshNow()` | PairScanner | 201 | — |
| `effectiveInterval()` | PairScanner | 212 | — |
| `schedule()` | PairScanner | 217 | — |
| `publishFailure()` | PairScanner | 257 | — |
| `freshTrend()` | PairScanner | 269 | — |
| `rankOpportunities()` | PairScanner | 277 | `#fn-scanner-sweep` در نقشهٔ HTML |
| `sweepAll()` | PairScanner | 280 | `#fn-scanner-sweep` در نقشهٔ HTML |
| `update()` | ScanHistory | 304 | — |

### `data/PaperAutoTrader.kt` — 142 خط · 3 فانکشن
> Purely technical: no AI and no news layer participates in entries anymore. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `attachTrendSource()` | PaperAutoTrader | 36 | — |
| `stopped()` | PaperAutoTrader | 40 | — |
| `onMarketUpdate()` | PaperAutoTrader | 43 | `#fn-autotrader` در نقشهٔ HTML |

### `data/PaperOpportunityStore.kt` — 79 خط · 5 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `load()` | PaperOpportunityStore | 29 | — |
| `save()` | PaperOpportunityStore | 47 | — |
| `record()` | PaperOpportunityStore | 63 | — |
| `linkTrade()` | PaperOpportunityStore | 70 | — |
| `clear()` | PaperOpportunityStore | 78 | — |

### `data/PublicCandleHistoryClient.kt` — 254 خط · 12 فانکشن
> Keyless, public historical candles for the no-key mode.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchCandles()` | PublicCandleHistoryClient | 50 | — |
| `detectGaps()` | Result | 104 | — |
| `parseYahooChart()` | PublicCandleHistoryClient | 111 | — |
| `yahooSymbol()` | PublicCandleHistoryClient | 176 | — |
| `encodeYahooPath()` | PublicCandleHistoryClient | 178 | — |
| `requestInterval()` | PublicCandleHistoryClient | 181 | — |
| `range()` | PublicCandleHistoryClient | 191 | — |
| `obj()` | PublicCandleHistoryClient | 201 | — |
| `array()` | PublicCandleHistoryClient | 202 | — |
| `text()` | PublicCandleHistoryClient | 203 | — |
| `num()` | PublicCandleHistoryClient | 204 | — |
| `yahooChartSymbol()` | — | 208 | — |

### `data/PublisherBudget.kt` — 27 خط · 2 فانکشن
> All symbol jobs start immediately. A publisher can still throttle its own requests;

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `tryRead()` | PublisherBudget | 16 | — |
| `read()` | PublisherBudget | 22 | `#fn-alignment` در نقشهٔ HTML |

### `data/QuoteCoalescer.kt` — 60 خط · 3 فانکشن
> One in-flight fetch per symbol, shared by every caller for a very short window.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `quote()` | QuoteCoalescer | 35 | — |
| `fresh()` | QuoteCoalescer | 47 | — |
| `clear()` | QuoteCoalescer | 52 | — |

### `data/QuoteHistoryStore.kt` — 99 خط · 11 فانکشن
> No artificial count cap; pages are loaded on demand. Clearing it is an explicit user action.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onCreate()` | QuoteHistoryStore | 15 | — |
| `onUpgrade()` | QuoteHistoryStore | 25 | — |
| `append()` | QuoteHistoryStore | 30 | — |
| `latest()` | QuoteHistoryStore | 48 | — |
| `page()` | QuoteHistoryStore | 53 | `#fn-archive` در نقشهٔ HTML |
| `count()` | QuoteHistoryStore | 56 | — |
| `clear()` | QuoteHistoryStore | 64 | — |
| `query()` | QuoteHistoryStore | 71 | — |
| `doubleOrNull()` | QuoteHistoryStore | 79 | — |
| `longOrNull()` | QuoteHistoryStore | 83 | — |
| `toQuote()` | QuoteHistoryStore | 87 | — |

### `data/ScanFreshness.kt` — 9 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `current()` | ScanFreshness | 7 | — |

### `data/ScanHistoryCache.kt` — 67 خط · 4 فانکشن
> Radar candle cache, invalidated by the *bar*, not by the wall clock.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `record()` | ScanHistoryCache | 43 | — |
| `get()` | ScanHistoryCache | 58 | — |
| `invalidate()` | ScanHistoryCache | 61 | — |
| `isFresh()` | ScanHistoryCache | 66 | — |

### `data/ScanRanking.kt` — 33 خط · 2 فانکشن
> CLOSED candles without authorization for an alert or paper entry (which may be caused

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `rank()` | ScanRanking | 12 | — |
| `byMarket()` | ScanRanking | 24 | — |

### `data/SettingsStore.kt` — 345 خط · 15 فانکشن
> Local settings. The Twelve Data key is a read-only market-data credential

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `migrateAutoDownload()` | SettingsStore | 40 | — |
| `readSymbolKeys()` | SettingsStore | 61 | — |
| `saveSymbolKey()` | SettingsStore | 79 | — |
| `symbolKey()` | SettingsStore | 95 | — |
| `read()` | SettingsStore | 97 | `#fn-alignment` در نقشهٔ HTML |
| `saveMarketCredentials()` | SettingsStore | 156 | — |
| `saveChartSymbol()` | SettingsStore | 177 | — |
| `tickArchiveConfig()` | SettingsStore | 192 | — |
| `tickArchiveUrl()` | SettingsStore | 202 | — |
| `hasTickArchive()` | SettingsStore | 203 | — |
| `saveTickArchive()` | SettingsStore | 206 | — |
| `dayOpeningEquity()` | SettingsStore | 227 | — |
| `adjustBalance()` | SettingsStore | 241 | — |
| `update()` | SettingsStore | 251 | — |
| `setLastSync()` | SettingsStore | 299 | — |

### `data/SigningContinuity.kt` — 5 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `sameCurrentSigners()` | — | 4 | — |

### `data/SourceCatalog.kt` — 234 خط · 1 فانکشن
> 1-to-1 dedicated public watch sources:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `find()` | SourceCatalog | 232 | — |

### `data/SourceFetcher.kt` — 208 خط · 7 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchAll()` | SourceFetcher | 36 | — |
| `fetchOne()` | SourceFetcher | 80 | — |
| `parseHtmlCarQuote()` | SourceFetcher | 122 | — |
| `parseJsonQuote()` | SourceFetcher | 137 | — |
| `fetchJson()` | SourceFetcher | 172 | — |
| `fetchText()` | SourceFetcher | 174 | — |
| `encode()` | SourceFetcher | 206 | — |

### `data/SourceIntegrity.kt` — 46 خط · 1 فانکشن
> Process-local latch, independent of UI, data receipt status and the active network route.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `resetForTests()` | SourceIntegrity | 44 | — |

### `data/SourceModels.kt` — 102 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse()` | Num | 91 | — |

### `data/SourcePromotion.kt` — 110 خط · 10 فانکشن
> Sticky source promotion ("the source that answers IS the primary source").

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `key()` | SourcePromotion | 33 | — |
| `healthOf()` | SourcePromotion | 34 | — |
| `attach()` | SourcePromotion | 38 | — |
| `persist()` | SourcePromotion | 50 | — |
| `primaryId()` | SourcePromotion | 58 | — |
| `ordered()` | SourcePromotion | 64 | — |
| `onSuccess()` | SourcePromotion | 76 | — |
| `onFailure()` | SourcePromotion | 86 | — |
| `resetForTests()` | SourcePromotion | 99 | — |
| `summaryFa()` | SourcePromotion | 102 | — |

### `data/SourceRouting.kt` — 165 خط · 10 فانکشن
> Source routing with a dedicated primary per symbol plus free fallbacks:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `dedicatedFor()` | SourceRouting | 68 | — |
| `cryptoVenueFor()` | SourceRouting | 72 | — |
| `usesNobitexReference()` | SourceRouting | 76 | — |
| `twelveDataFirst()` | SourceRouting | 79 | — |
| `historySources()` | SourceRouting | 82 | — |
| `historyCandidates()` | SourceRouting | 93 | — |
| `fromProvider()` | SourceRouting | 115 | — |
| `matchesSource()` | SourceRouting | 128 | — |
| `historyPlan()` | SourceRouting | 138 | — |
| `cryptoPlan()` | SourceRouting | 158 | — |

### `data/SpotFallbackClient.kt` — 420 خط · 18 فانکشن
> Automatic real spot-price source: Swissquote's public BBO for FX and both metals,

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `get()` | SpotFallbackClient | 64 | — |
| `fetchSwissquote()` | SpotFallbackClient | 77 | — |
| `fetchSwissquoteOnly()` | SpotFallbackClient | 115 | — |
| `fetchGoldApi()` | SpotFallbackClient | 126 | — |
| `cryptoTradeResponse()` | SpotFallbackClient | 157 | — |
| `nobitexTwoSided()` | SpotFallbackClient | 188 | — |
| `nobitexBook()` | SpotFallbackClient | 204 | — |
| `fetchCryptoTrades()` | SpotFallbackClient | 226 | — |
| `fetchMetalQuote()` | SpotFallbackClient | 236 | — |
| `fetchCryptoQuote()` | SpotFallbackClient | 261 | — |
| `fetchQuote()` | SpotFallbackClient | 299 | — |
| `fetchQuoteUncached()` | SpotFallbackClient | 306 | — |
| `fetchYahooIndexQuote()` | SpotFallbackClient | 326 | — |
| `readYahooMeta()` | SpotFallbackClient | 345 | — |
| `streamQuotes()` | SpotFallbackClient | 375 | — |
| `clearQuoteCache()` | CryptoTradeCache | 386 | — |
| `resetYahooProbe()` | CryptoTradeCache | 390 | — |
| `tickPlanFor()` | — | 410 | — |

### `data/SymbolSearch.kt` — 186 خط · 6 فانکشن
> Finding a coin by a fragment of its name, in Persian or English.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `normalize()` | SymbolSearch | 84 | — |
| `aliasFor()` | SymbolSearch | 92 | — |
| `score()` | SymbolSearch | 105 | `#fn-v1score-side` در نقشهٔ HTML |
| `rank()` | SymbolSearch | 128 | — |
| `distance()` | SymbolSearch | 145 | — |
| `suggest()` | SymbolSearch | 168 | — |

### `data/SymbolWorkers.kt` — 28 خط · 0 فانکشن
> a universe-wide sweep. A slow symbol does not prevent completed symbols from refreshing.

### `data/SystemNetworkObserver.kt` — 35 خط · 6 فانکشن
> cellular network, open a bypass socket, start a VPN, query an IP service or collect

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onAvailable()` | SystemNetworkObserver | 16 | — |
| `onLost()` | SystemNetworkObserver | 17 | — |
| `onCapabilitiesChanged()` | SystemNetworkObserver | 18 | — |
| `onLinkPropertiesChanged()` | SystemNetworkObserver | 19 | — |
| `start()` | SystemNetworkObserver | 22 | — |
| `refresh()` | SystemNetworkObserver | 29 | `#fn-market-refresh` در نقشهٔ HTML |

### `data/ThreeMinuteCandles.kt` — 40 خط · 2 فانکشن
> No tick or candle is fabricated for minutes without trades; such a gap invalidates the

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fromMinutes()` | ThreeMinuteCandles | 11 | — |
| `takeLastWhileIndexed()` | ThreeMinuteCandles | 34 | — |

### `data/TimeframeFeed.kt` — 136 خط · 3 فانکشن
> Reference alignment frames for the SELECTED market.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetch()` | TimeframeFeed | 24 | — |
| `readFrame()` | TimeframeFeed | 101 | — |
| `healthy()` | TimeframeFeed | 127 | — |

### `data/TradingViewSymbols.kt` — 38 خط · 1 فانکشن
> Resolution of the 15 supported app symbols (2 Metals, 1 Dow Jones Index,

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `find()` | TradingViewSymbols | 30 | — |

### `data/TwelveDataClient.kt` — 213 خط · 12 فانکشن
> Thin, honest client for Twelve Data.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchCandles()` | TwelveDataClient | 45 | — |
| `parseTimeSeries()` | TwelveDataClient | 82 | — |
| `text()` | TwelveDataClient | 86 | — |
| `price()` | TwelveDataClient | 108 | — |
| `streamPrice()` | TwelveDataClient | 129 | — |
| `onOpen()` | TwelveDataClient | 140 | — |
| `onMessage()` | TwelveDataClient | 144 | — |
| `onFailure()` | TwelveDataClient | 165 | — |
| `onClosed()` | TwelveDataClient | 169 | — |
| `parsePriceEvent()` | TwelveDataClient | 178 | — |
| `describeError()` | TwelveDataClient | 195 | — |
| `parseTime()` | TwelveDataClient | 205 | — |

### `data/TwelveDataCoverage.kt` — 72 خط · 3 فانکشن
> What Twelve Data can and cannot serve for THIS app's 15-symbol catalog.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `requestSymbol()` | TwelveDataCoverage | 35 | — |
| `statusOf()` | TwelveDataCoverage | 40 | — |
| `note()` | TwelveDataCoverage | 51 | — |

### `data/UpdateVerification.kt` — 111 خط · 4 فانکشن
> Pure install-gate rules for the in-app updater.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `sha256OrNull()` | UpdateVerification | 33 | — |
| `signerProblem()` | UpdateVerification | 46 | — |
| `metadataSignerProblem()` | UpdateVerification | 71 | — |
| `versionVerdict()` | UpdateVerification | 97 | — |

### `data/VenueRequestPolicy.kt` — 69 خط · 2 فانکشن
> a venue ban. Preserve the real reason and Retry-After; a changed route gets a fresh probe. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `read()` | VenueRequestPolicy | 23 | `#fn-alignment` در نقشهٔ HTML |
| `retryDelay()` | Slot | 62 | — |

### `data/WatchCatalog.kt` — 301 خط · 8 فانکشن
> Focused 15-symbol universe with strict 1-to-1 source assignment:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `scanUniverse()` | WatchCatalog | 172 | — |
| `find()` | WatchCatalog | 175 | — |
| `assess()` | SourceComparison | 193 | `#fn-entrysetup` در نقشهٔ HTML |
| `isFresh()` | SourceComparison | 212 | — |
| `verify()` | SourceComparison | 215 | — |
| `choose()` | WatchDisplay | 285 | — |
| `showable()` | WatchDisplay | 288 | — |
| `readable()` | WatchDisplay | 293 | — |

### `data/WatchRepository.kt` — 199 خط · 8 فانکشن
> observation and original timestamp, never a made-up fresh quote. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `mergeWatchSource()` | — | 30 | — |
| `loadCached()` | WatchRepository | 62 | — |
| `startAutoRefresh()` | WatchRepository | 75 | — |
| `refreshNow()` | WatchRepository | 85 | — |
| `ensureLoaded()` | WatchRepository | 89 | — |
| `refresh()` | WatchRepository | 109 | `#fn-market-refresh` در نقشهٔ HTML |
| `page()` | WatchRepository | 188 | `#fn-archive` در نقشهٔ HTML |
| `count()` | WatchRepository | 194 | — |

### `data/WatchSettingsStore.kt` — 52 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `read()` | WatchSettingsStore | 16 | `#fn-alignment` در نقشهٔ HTML |
| `apiKeyFor()` | WatchSettingsStore | 37 | — |
| `keyOverride()` | WatchSettingsStore | 40 | — |
| `setKeyOverride()` | WatchSettingsStore | 45 | — |

### `engine/Als5GoldEngine.kt` — 1183 خط · 13 فانکشن
> ALS-5 — Adaptive Liquidity & Structure Trading System for Gold (XAU/USD) on M5.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `confirmedSwings()` | Als5GoldEngine | 175 | — |
| `classifyRegime()` | Als5GoldEngine | 214 | `#fn-als5` در نقشهٔ HTML |
| `evaluateEngineA()` | Als5GoldEngine | 365 | `#fn-als5` در نقشهٔ HTML |
| `evaluateEngineB()` | Als5GoldEngine | 565 | `#fn-als5` در نقشهٔ HTML |
| `evaluateEngineC()` | Als5GoldEngine | 729 | `#fn-als5` در نقشهٔ HTML |
| `computeTarget()` | Als5GoldEngine | 846 | — |
| `netRewardRisk()` | Als5GoldEngine | 871 | — |
| `calculatePositionSize()` | Als5GoldEngine | 885 | `#fn-als5` در نقشهٔ HTML |
| `sessionAllowed()` | Als5GoldEngine | 904 | `#fn-als5` در نقشهٔ HTML |
| `evaluate()` | Als5GoldEngine | 919 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | CandidateSetup | 936 | — |
| `buildConfluenceItems()` | Als5GoldEngine | 1130 | — |
| `item()` | CandidateSetup | 1135 | — |

### `engine/BtcAresEngine.kt` — 1009 خط · 24 فانکشن
> BTC-ARES — Bitcoin Adaptive Regime, Execution & Signal Intelligence System.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `noTrade()` | BtcAresEngine | 193 | — |
| `pct()` | BtcAresEngine | 203 | — |
| `confirmedSwings()` | BtcAresEngine | 206 | — |
| `confirmedLows()` | BtcAresEngine | 223 | — |
| `atrPercentile()` | BtcAresEngine | 245 | — |
| `features()` | BtcAresEngine | 254 | — |
| `volatilityOf()` | BtcAresEngine | 329 | — |
| `classifyRegime()` | BtcAresEngine | 337 | `#fn-als5` در نقشهٔ HTML |
| `stopFor()` | BtcAresEngine | 357 | — |
| `targetAt()` | BtcAresEngine | 363 | — |
| `engineA()` | BtcAresEngine | 367 | — |
| `engineB()` | BtcAresEngine | 432 | — |
| `engineC()` | BtcAresEngine | 513 | — |
| `engineD()` | BtcAresEngine | 572 | — |
| `engineE()` | BtcAresEngine | 633 | — |
| `scoreOf()` | BtcAresEngine | 704 | — |
| `report()` | BtcAresEngine | 737 | — |
| `netRewardRisk()` | BtcAresEngine | 766 | — |
| `positionSize()` | BtcAresEngine | 775 | — |
| `confluenceItems()` | BtcAresEngine | 788 | — |
| `item()` | Candidate | 794 | — |
| `evaluate()` | BtcAresEngine | 830 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | Candidate | 847 | — |
| `analyze()` | BtcAresEngine | 1000 | `#fn-mtf` در نقشهٔ HTML |

### `engine/CryptoCompressionEngine.kt` — 264 خط · 4 فانکشن
> confirmations, never entry candles. This does not backfill missing trades, volumes or quotes.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocked()` | CryptoCompressionEngine | 27 | — |
| `evaluate()` | CryptoCompressionEngine | 33 | `#fn-signalengine` در نقشهٔ HTML |
| `valid()` | CryptoCompressionEngine | 249 | — |
| `compressionType()` | CryptoCompressionEngine | 254 | — |

### `engine/CryptoCompressionExit.kt` — 77 خط · 1 فانکشن
> the journal executes an intent only on a later, ordered same-venue trade print.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `assess()` | CryptoCompressionExit | 18 | `#fn-entrysetup` در نقشهٔ HTML |

### `engine/DirectionalIndex.kt` — 35 خط · 1 فانکشن
> No zero-volume fallback or synthetic history is used for entry.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `at()` | DirectionalIndex | 12 | — |

### `engine/EngineRegistry.kt` — 84 خط · 8 فانکشن
> Which engine is allowed to speak for which symbol — one answer, in one place.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `goldSymbols()` | EngineRegistry | 41 | — |
| `btcAresSymbols()` | EngineRegistry | 44 | — |
| `silverAresSymbols()` | EngineRegistry | 47 | — |
| `eurAresSymbols()` | EngineRegistry | 50 | — |
| `engineFor()` | EngineRegistry | 52 | — |
| `autoEntryNote()` | EngineRegistry | 70 | — |
| `assignments()` | EngineRegistry | 74 | — |
| `setupKey()` | EngineRegistry | 81 | — |

### `engine/EurAresEngine.kt` — 1442 خط · 33 فانکشن
> EUR-ARES — Adaptive Euro-Dollar Regime, Execution & Signal Intelligence System.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `confirmedSwings()` | EurAresEngine | 258 | — |
| `confirmedLows()` | EurAresEngine | 276 | — |
| `atrPercentile()` | EurAresEngine | 295 | — |
| `sessionOf()` | EurAresEngine | 305 | — |
| `sessionVolRatio()` | EurAresEngine | 311 | — |
| `asianRange()` | EurAresEngine | 330 | — |
| `londonOpeningRange()` | EurAresEngine | 339 | — |
| `previousDayRange()` | EurAresEngine | 352 | — |
| `roundNumbers()` | EurAresEngine | 363 | — |
| `utcDay()` | EurAresEngine | 372 | — |
| `volatilityOf()` | EurAresEngine | 377 | — |
| `rawRegime()` | EurAresEngine | 387 | — |
| `classifyRegime()` | EurAresEngine | 412 | `#fn-als5` در نقشهٔ HTML |
| `features()` | EurAresEngine | 427 | — |
| `sessionPoints()` | EurAresEngine | 545 | — |
| `rrPoints()` | EurAresEngine | 553 | — |
| `costPoints()` | EurAresEngine | 563 | — |
| `volatilityPoints()` | EurAresEngine | 570 | — |
| `stopFor()` | EurAresEngine | 595 | — |
| `targetAt()` | EurAresEngine | 603 | — |
| `noTrade()` | EurAresEngine | 608 | — |
| `engineA()` | EurAresEngine | 629 | — |
| `engineB()` | EurAresEngine | 740 | — |
| `engineC()` | EurAresEngine | 856 | — |
| `engineD()` | EurAresEngine | 935 | — |
| `engineE()` | EurAresEngine | 1079 | — |
| `engineX()` | EurAresEngine | 1086 | — |
| `report()` | EurAresEngine | 1116 | — |
| `confluenceItems()` | EurAresEngine | 1152 | — |
| `item()` | Candidate | 1158 | — |
| `evaluate()` | EurAresEngine | 1202 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | Candidate | 1219 | — |
| `analyze()` | EurAresEngine | 1434 | `#fn-mtf` در نقشهٔ HTML |

### `engine/IchimokuAlignment.kt` — 110 خط · 5 فانکشن
> candle are projected forward for display, not future observed prices; the visible cloud

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `higherTimeframeAligned()` | Read | 29 | — |
| `trendAligned()` | Read | 43 | `#fn-alignment` در نقشهٔ HTML |
| `rangeAligned()` | Read | 59 | `#fn-alignment` در نقشهٔ HTML |
| `detail()` | Read | 64 | — |
| `read()` | IchimokuAlignment | 83 | `#fn-alignment` در نقشهٔ HTML |

### `engine/IchimokuEntrySetup.kt` — 54 خط · 3 فانکشن
> The cross may be on one of the last two completed bars; compression precedes the cross.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `assess()` | IchimokuEntrySetup | 14 | `#fn-entrysetup` در نقشهٔ HTML |
| `value()` | IchimokuEntrySetup | 20 | — |
| `thin()` | IchimokuEntrySetup | 22 | — |

### `engine/IctRangeAnalyzer.kt` — 259 خط · 7 فانکشن
> Causal, OHLC-only approximations of a range and some ICT vocabulary. This is NOT an

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `analyze()` | IctRangeAnalyzer | 79 | `#fn-mtf` در نقشهٔ HTML |
| `sessionAt()` | IctRangeAnalyzer | 103 | — |
| `wellFormed()` | IctRangeAnalyzer | 114 | — |
| `confirmedRange()` | IctRangeAnalyzer | 121 | — |
| `touches()` | Snapshot | 136 | — |
| `setup()` | IctRangeAnalyzer | 169 | — |
| `result()` | Snapshot | 197 | — |

### `engine/Indicators.kt` — 258 خط · 11 فانکشن
> Pure indicator math over *real* closed candles.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `donchianMid()` | Indicators | 15 | `#fn-ichimoku` در نقشهٔ HTML |
| `ema()` | Indicators | 31 | `#fn-ichimoku` در نقشهٔ HTML |
| `rsi()` | Indicators | 45 | `#fn-ichimoku` در نقشهٔ HTML |
| `value()` | Indicators | 56 | — |
| `atr()` | Indicators | 72 | `#fn-ichimoku` در نقشهٔ HTML |
| `macdHistogram()` | Indicators | 95 | `#fn-ichimoku` در نقشهٔ HTML |
| `adx()` | Indicators | 117 | `#fn-ichimoku` در نقشهٔ HTML |
| `relativeVolume()` | Indicators | 160 | — |
| `cloudAt()` | Ichimoku | 194 | — |
| `higherTimeframeBiasAt()` | Ichimoku | 203 | — |
| `compute()` | Ichimoku | 225 | `#fn-ichimoku` در نقشهٔ HTML |

### `engine/MtfAnalyzer.kt` — 266 خط · 7 فانکشن
> Multi-timeframe confluence, computed **on the phone** from the real candles that were

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `framesFor()` | MtfAnalyzer | 56 | — |
| `weightFor()` | MtfAnalyzer | 67 | — |
| `resample()` | MtfAnalyzer | 82 | `#fn-mtf` در نقشهٔ HTML |
| `frameOf()` | MtfAnalyzer | 113 | — |
| `analyze()` | MtfAnalyzer | 196 | `#fn-mtf` در نقشهٔ HTML |
| `analyzeFrames()` | MtfAnalyzer | 204 | — |
| `direction()` | MtfAnalyzer | 261 | — |

### `engine/PerformanceMetrics.kt` — 99 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fromPaper()` | PerformanceMetrics | 39 | — |
| `from()` | PerformanceMetrics | 45 | — |

### `engine/ResearchEvidence.kt` — 51 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `hasRecordedSignalEvidence()` | ResearchEvidence | 20 | — |
| `hasRecordedNineWay()` | ResearchEvidence | 37 | — |
| `paperCostWhatIf()` | ResearchEvidence | 40 | — |

### `engine/SignalEngine.kt` — 151 خط · 5 فانکشن
> Single V1 closed-bar four-layer paper signal facade.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ichimokuSetting()` | SignalEngine | 42 | — |
| `minBars()` | SignalEngine | 44 | — |
| `series()` | SignalEngine | 49 | — |
| `evaluateLive()` | SignalEngine | 68 | `#fn-signalengine` در نقشهٔ HTML |
| `evaluate()` | SignalEngine | 130 | `#fn-signalengine` در نقشهٔ HTML |

### `engine/V1Scoring.kt` — 215 خط · 8 فانکشن
> Four independent layers, each worth 25 points. Only M5/M15/H1 vote on alignment.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `minBarsFor()` | V1Scoring | 26 | — |
| `evaluate()` | V1Scoring | 28 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | V1Scoring | 36 | — |
| `score()` | V1Scoring | 105 | `#fn-v1score-side` در نقشهٔ HTML |
| `item()` | V1Scoring | 125 | — |
| `directionalAlignment()` | V1Scoring | 177 | `#fn-v1score-side` در نقشهٔ HTML |
| `chooseSide()` | V1Scoring | 189 | `#fn-v1score-side` در نقشهٔ HTML |
| `frameBias()` | V1Scoring | 195 | `#fn-v1score-side` در نقشهٔ HTML |

### `engine/XagAresEngine.kt` — 1228 خط · 27 فانکشن
> ALS-5S — Adaptive Liquidity, Structure & Silver Volatility System (XAG/USD, M5).

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `confirmedSwings()` | XagAresEngine | 235 | — |
| `confirmedLows()` | XagAresEngine | 253 | — |
| `atrPercentile()` | XagAresEngine | 272 | — |
| `sessionOf()` | XagAresEngine | 281 | — |
| `goldContext()` | XagAresEngine | 295 | — |
| `returns()` | Candidate | 309 | — |
| `features()` | XagAresEngine | 348 | — |
| `volatilityOf()` | XagAresEngine | 436 | — |
| `rawRegime()` | XagAresEngine | 444 | — |
| `classifyRegime()` | XagAresEngine | 466 | `#fn-als5` در نقشهٔ HTML |
| `stopFor()` | XagAresEngine | 477 | — |
| `targetAt()` | XagAresEngine | 483 | — |
| `noTrade()` | XagAresEngine | 488 | — |
| `sessionPoints()` | XagAresEngine | 505 | — |
| `executionPoints()` | XagAresEngine | 513 | — |
| `rrPoints()` | XagAresEngine | 531 | — |
| `engineA()` | XagAresEngine | 541 | — |
| `engineB()` | XagAresEngine | 633 | — |
| `engineCRange()` | XagAresEngine | 733 | — |
| `engineCBreakout()` | XagAresEngine | 809 | — |
| `engineX()` | XagAresEngine | 907 | — |
| `report()` | XagAresEngine | 939 | — |
| `confluenceItems()` | XagAresEngine | 968 | — |
| `item()` | Candidate | 974 | — |
| `evaluate()` | XagAresEngine | 1014 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | Candidate | 1031 | — |
| `analyze()` | XagAresEngine | 1218 | `#fn-mtf` در نقشهٔ HTML |

### `notify/AlertSoundPlayer.kt` — 128 خط · 6 فانکشن
> One brief notification-usage clip from a persistable SAF document. Playback is done by the

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `select()` | AlertSoundPlayer | 34 | — |
| `canOpen()` | AlertSoundPlayer | 68 | — |
| `probe()` | AlertSoundPlayer | 77 | — |
| `play()` | AlertSoundPlayer | 84 | — |
| `stop()` | AlertSoundPlayer | 118 | — |
| `releaseActive()` | AlertSoundPlayer | 120 | — |

### `notify/Notifier.kt` — 270 خط · 11 فانکشن
> Android system notification delivery with exact channel separation:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ensureChannels()` | Notifier | 45 | `#fn-notifier` در نقشهٔ HTML |
| `contentIntent()` | Notifier | 87 | — |
| `buildForegroundServiceNotification()` | Notifier | 99 | — |
| `buildMonitorNotification()` | Notifier | 109 | — |
| `canNotifyVerified()` | Notifier | 112 | — |
| `notifyTest()` | Notifier | 122 | — |
| `notifyVerifiedOpportunity()` | Notifier | 128 | `#fn-notifier` در نقشهٔ HTML |
| `notifyTradeOpened()` | Notifier | 155 | — |
| `notifyClosedTrade()` | Notifier | 200 | `#fn-notifier` در نقشهٔ HTML |
| `notifyRecordedAutoEntry()` | Notifier | 234 | `#fn-notifier` در نقشهٔ HTML |
| `postVerified()` | Notifier | 245 | — |

### `service/SignalMonitorService.kt` — 352 خط · 7 فانکشن
> Keeps the real Forex feed alive while the app is in the background and alerts only on

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onBind()` | SignalMonitorService | 53 | — |
| `notificationsPermitted()` | SignalMonitorService | 55 | — |
| `onStartCommand()` | SignalMonitorService | 59 | `#service` در نقشهٔ HTML |
| `onTimeout()` | SignalMonitorService | 319 | — |
| `onDestroy()` | SignalMonitorService | 323 | — |
| `start()` | SignalMonitorService | 342 | — |
| `stop()` | SignalMonitorService | 347 | — |

### `ui/components/Common.kt` — 299 خط · 10 فانکشن
> Price with the exact decimals the instrument is actually quoted in.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `formatPrice()` | — | 29 | — |
| `formatPriceFor()` | — | 48 | — |
| `formatTime()` | — | 52 | — |
| `formatDateTime()` | — | 57 | — |
| `relativeTime()` | — | 62 | — |
| `SectionCard()` | — | 74 | — |
| `StatTile()` | — | 106 | — |
| `Pill()` | — | 126 | — |
| `ConfluenceRow()` | — | 138 | — |
| `AssetClass4SlotsBanner()` | — | 180 | — |

### `ui/shared/PerformancePanel.kt` — 66 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `PerformancePanel()` | — | 21 | — |
| `number()` | — | 22 | — |

### `ui/shell/AurumRoot.kt` — 109 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AurumRoot()` | — | 29 | — |
| `open()` | — | 71 | `#fn-journal-open` در نقشهٔ HTML |
| `openChartFor()` | — | 74 | — |

### `ui/shell/AurumViewModel.kt` — 920 خط · 46 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `startApp()` | AurumViewModel | 197 | `#fn-viewmodel` در نقشهٔ HTML |
| `resumeVisibleForexFeed()` | AurumViewModel | 232 | — |
| `pauseInvisibleForexFeed()` | AurumViewModel | 238 | — |
| `ensureVisibleOnlineLoop()` | AurumViewModel | 251 | `#fn-viewmodel` در نقشهٔ HTML |
| `ensureLiveTickerLoop()` | AurumViewModel | 281 | `#fn-viewmodel` در نقشهٔ HTML |
| `refreshNow()` | AurumViewModel | 383 | — |
| `checkForAppUpdate()` | AurumViewModel | 385 | — |
| `downloadAppUpdate()` | AurumViewModel | 396 | — |
| `setAutoDownloadUpdates()` | AurumViewModel | 403 | — |
| `installDownloadedUpdate()` | AurumViewModel | 411 | — |
| `resumeUpdateInstall()` | AurumViewModel | 413 | — |
| `openUpdateInstallPermission()` | AurumViewModel | 415 | — |
| `refreshWatch()` | AurumViewModel | 417 | — |
| `selectChartSymbol()` | AurumViewModel | 426 | — |
| `saveMarketCredentials()` | AurumViewModel | 453 | — |
| `setCategoryStrategy()` | AurumViewModel | 482 | — |
| `saveRiskPercent()` | AurumViewModel | 492 | — |
| `saveBalance()` | AurumViewModel | 494 | — |
| `saveMinConfidence()` | AurumViewModel | 496 | — |
| `saveSpread()` | AurumViewModel | 499 | — |
| `saveCommission()` | AurumViewModel | 501 | — |
| `setNotifyOnSignal()` | AurumViewModel | 503 | — |
| `selectAlertSound()` | AurumViewModel | 505 | — |
| `resetAlertSound()` | AurumViewModel | 517 | — |
| `reportNotificationTest()` | AurumViewModel | 523 | — |
| `testAlertSound()` | AurumViewModel | 528 | — |
| `setMonitorFlag()` | AurumViewModel | 541 | — |
| `setAutoPaperTrading()` | AurumViewModel | 546 | — |
| `freshPaperQuote()` | AurumViewModel | 555 | — |
| `entryBlocker()` | AurumViewModel | 571 | — |
| `previewManualTicket()` | AurumViewModel | 622 | — |
| `openManualPaperTrade()` | AurumViewModel | 632 | — |
| `submitPaper()` | AurumViewModel | 644 | `#fn-viewmodel` در نقشهٔ HTML |
| `verified()` | AurumViewModel | 650 | — |
| `closePaperTrade()` | AurumViewModel | 736 | — |
| `clearJournal()` | AurumViewModel | 764 | — |
| `saveDedicatedKey()` | AurumViewModel | 781 | — |
| `showTradeChart()` | AurumViewModel | 805 | — |
| `refreshVisibleTradeChart()` | AurumViewModel | 833 | — |
| `downloadTradeChart()` | AurumViewModel | 856 | — |
| `setInterval()` | AurumViewModel | 885 | — |
| `tickArchiveUrl()` | AurumViewModel | 887 | — |
| `saveTickArchive()` | AurumViewModel | 889 | — |
| `exportJournalPdf()` | AurumViewModel | 901 | — |
| `consumeToast()` | AurumViewModel | 912 | — |
| `create()` | AurumViewModelFactory | 919 | — |

### `ui/shell/TabNavigation.kt` — 89 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AurumBottomBar()` | — | 50 | — |
| `MoreTabsSheet()` | — | 73 | — |

### `ui/tabs/chart/ChartScreen.kt` — 145 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ChartScreen()` | — | 43 | — |

### `ui/tabs/chart/TradingViewEmbed.kt` — 37 خط · 1 فانکشن
> app label. Its ID contains the literal `%1`, not the old malformed `%Cloud` string. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `html()` | TradingViewEmbed | 11 | — |

### `ui/tabs/home/HomeScreen.kt` — 605 خط · 4 فانکشن
> صفحه اصلی (Home Screen):

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `HomeScreen()` | — | 64 | — |
| `LiveOpenTradesTrackingSection()` | — | 197 | — |
| `LiveTradeGaugeCard()` | — | 274 | — |
| `AutoPaperCard()` | — | 519 | — |

### `ui/tabs/journal/JournalScreen.kt` — 1032 خط · 17 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `JournalScreen()` | — | 69 | — |
| `PaperEvidencePanel()` | — | 366 | — |
| `EntryConditionsLine()` | — | 400 | — |
| `conditionTone()` | — | 411 | — |
| `conditionLabel()` | — | 417 | — |
| `TradeRow()` | — | 425 | — |
| `TradeChartDisclosure()` | — | 513 | — |
| `AiReviewDisclosure()` | — | 608 | — |
| `verdictFa()` | — | 640 | — |
| `HoldReviewDisclosure()` | — | 648 | — |
| `verdictFa()` | — | 684 | — |
| `IctDisclosure()` | — | 692 | — |
| `ConditionDisclosure()` | — | 724 | — |
| `PnlIncomeCalendarSection()` | — | 748 | — |
| `trendDirectionFa()` | — | 1013 | — |
| `trendAlignmentFa()` | — | 1020 | — |
| `riskToneFa()` | — | 1027 | — |

### `ui/tabs/journal/JournalUiState.kt` — 13 خط · 0 فانکشن

### `ui/tabs/journal/TradeReplayChart.kt` — 179 خط · 6 فانکشن
> Static picture of ONE recorded paper trade on the real candles this phone verified.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `TradeReplayChart()` | — | 41 | — |
| `xOf()` | — | 86 | — |
| `yOf()` | — | 87 | — |
| `level()` | — | 124 | — |
| `marker()` | — | 139 | — |
| `argbOf()` | — | 174 | — |

### `ui/tabs/settings/ApiMenuScreen.kt` — 112 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ApiMenuScreen()` | — | 36 | — |
| `TwelveKeyRows()` | — | 77 | — |

### `ui/tabs/settings/SettingsScreen.kt` — 306 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `SettingsScreen()` | — | 54 | — |
| `startMonitorIfAllowed()` | — | 83 | — |

### `ui/tabs/signal/Als5OpportunityMonitorCard.kt` — 100 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `Als5OpportunityMonitorCard()` | — | 22 | — |

### `ui/tabs/signal/BtcAresEnginesCard.kt` — 183 خط · 4 فانکشن
> BTC-ARES on the trade tab: the regime line, the risk line, and then **one card per engine**.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `BtcAresEnginesCard()` | — | 34 | — |
| `EngineRow()` | — | 112 | — |
| `stateLabel()` | — | 164 | — |
| `volLabel()` | — | 177 | — |

### `ui/tabs/signal/EurAresEnginesCard.kt` — 227 خط · 4 فانکشن
> EUR-ARES on the trade tab: regime, session clocks, pip-based economics and **one card per engine**.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `EurAresEnginesCard()` | — | 38 | — |
| `EngineRow()` | — | 138 | — |
| `eurVolLabel()` | — | 207 | — |
| `eurStateLabel()` | — | 216 | — |

### `ui/tabs/signal/PaperTicketSection.kt` — 117 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `PaperTicketSection()` | — | 35 | — |

### `ui/tabs/signal/SignalScreen.kt` — 291 خط · 2 فانکشن
> صفحه معامله و سیگنال (Signal / Trading Tab) — آنچه واقعاً روی این صفحه رسم می‌شود:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `SignalScreen()` | — | 50 | — |
| `BestOpportunityDetailedCard()` | — | 138 | — |

### `ui/tabs/signal/SilverAresEnginesCard.kt` — 209 خط · 4 فانکشن
> ALS-5S on the trade tab: regime, cost line, and **one card per engine**.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `SilverAresEnginesCard()` | — | 38 | — |
| `EngineRow()` | — | 127 | — |
| `stateLabel()` | — | 190 | — |
| `volLabel()` | — | 203 | — |

### `ui/tabs/update/UpdateScreen.kt` — 193 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `UpdateScreen()` | — | 32 | — |
| `UpdateInfoCard()` | — | 142 | — |
| `formatUpdateBytes()` | — | 189 | — |

### `ui/tabs/watch/MarketWatchScreen.kt` — 133 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `MarketWatchScreen()` | — | 43 | — |
| `QuoteMainLine()` | — | 126 | — |

### `ui/theme/Theme.kt` — 67 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AurumTheme()` | — | 63 | — |

## اپ اندروید — تست‌ها — `android/app/src/test/java/com/aurum/edge` (96 فایل · 111 فانکشن)

### `MarketPlaybookTest.kt` — 286 خط · 4 فانکشن
> «کدام روش برای کدام بازار» — pinned with FIXED wall clocks and synthetic CLOSED candles, so a

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `candleAt()` | MarketPlaybookTest | 261 | — |
| `trendSeries()` | MarketPlaybookTest | 265 | — |
| `rangeSeries()` | MarketPlaybookTest | 273 | — |
| `withTail()` | MarketPlaybookTest | 281 | — |

### `MarketTrendTest.kt` — 343 خط · 3 فانکشن
> «روند کلی بازار» — pinned with synthetic CLOSED candles and hand-built breadth samples.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `series()` | MarketTrendTest | 35 | — |
| `series()` | MarketTrendTest | 37 | — |
| `trend()` | MarketTrendTest | 65 | — |

### `core/MonitorCadenceTest.kt` — 23 خط · 0 فانکشن

### `data/AppNetworkTest.kt` — 90 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `select()` | AppNetworkTest | 31 | — |
| `connectFailed()` | AppNetworkTest | 32 | — |
| `onOpen()` | AppNetworkTest | 80 | — |
| `onFailure()` | AppNetworkTest | 81 | — |

### `data/AutoUpdatePreferenceTest.kt` — 64 خط · 4 فانکشن
> In-app updating is enabled by default from 2.0.2 (owner request), including for an install that

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `context()` | AutoUpdatePreferenceTest | 28 | — |
| `clear()` | AutoUpdatePreferenceTest | 30 | — |
| `store()` | AutoUpdatePreferenceTest | 34 | — |
| `modelDefault()` | AutoUpdatePreferenceTest | 36 | — |

### `data/BinancePositionReplayTest.kt` — 176 خط · 3 فانکشن
> Retiring the old crypto strategy must not disable old same-venue exits.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `file()` | BinancePositionReplayTest | 28 | — |
| `legacy()` | BinancePositionReplayTest | 32 | — |
| `row()` | BinancePositionReplayTest | 41 | — |

### `data/BinanceSpotContractTest.kt` — 93 خط · 4 فانکشن
> {"e":"aggTrade","s":"$symbol","a":$id,"p":"$price","q":"$qty","T":$time,"E":$time}

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `event()` | BinanceSpotContractTest | 17 | — |
| `row()` | BinanceSpotContractTest | 20 | — |
| `fails()` | BinanceSpotContractTest | 22 | — |
| `bar()` | BinanceSpotContractTest | 65 | — |

### `data/ChartSymbolChoicesTest.kt` — 26 خط · 0 فانکشن

### `data/CryptoOnDeviceTest.kt` — 108 خط · 0 فانکشن

### `data/CryptoQuoteTaskIsolationTest.kt` — 43 خط · 0 فانکشن

### `data/CryptoRadarBundleTest.kt` — 68 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `closedSeries()` | CryptoRadarBundleTest | 16 | — |

### `data/CryptoUniverse200Test.kt` — 45 خط · 0 فانکشن

### `data/CryptoVenuePolicyTest.kt` — 47 خط · 0 فانکشن

### `data/DataUsageBudgetTest.kt` — 190 خط · 4 فانکشن
> Guards the three changes that brought the app's traffic down. Each test states the behaviour

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bar()` | DataUsageBudgetTest | 100 | — |
| `frames()` | DataUsageBudgetTest | 102 | — |
| `yahoo()` | DataUsageBudgetTest | 144 | — |
| `meta()` | DataUsageBudgetTest | 155 | — |

### `data/MarketEntryReasonTest.kt` — 28 خط · 0 فانکشن

### `data/MetalQuoteRoutingTest.kt` — 113 خط · 3 فانکشن
> Regression guard for two reported failures that share one cause — a quote that cannot carry a

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bbo()` | MetalQuoteRoutingTest | 33 | — |
| `singlePrice()` | MetalQuoteRoutingTest | 36 | — |
| `spot()` | MetalQuoteRoutingTest | 42 | — |

### `data/MexcRadarTest.kt` — 28 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `accepted()` | MexcRadarTest | 13 | — |

### `data/NobitexCryptoBundleTest.kt` — 77 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `closedSeries()` | NobitexCryptoBundleTest | 20 | — |
| `bundleWith()` | NobitexCryptoBundleTest | 28 | — |

### `data/NobitexHistoryClientTest.kt` — 71 خط · 1 فانکشن
> Nobitex is the crypto source for networks where Binance answers 451.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `udf()` | NobitexHistoryClientTest | 18 | — |

### `data/NobitexOrderBookTest.kt` — 83 خط · 1 فانکشن
> Why this parser is strict: the bid/ask it produces is the only thing standing between a

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `book()` | NobitexOrderBookTest | 18 | — |

### `data/NobitexPublicTradeStreamTest.kt` — 78 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `subscribed()` | NobitexPublicTradeStreamTest | 11 | — |

### `data/NobitexThreeMinuteFetchTest.kt` — 41 خط · 0 فانکشن

### `data/NobitexTickArchiveTest.kt` — 80 خط · 0 فانکشن

### `data/NobitexTradeBatchTest.kt` — 48 خط · 2 فانکشن
> {"time":$ms,"price":"$price","volume":"$volume","type":"buy"}

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `trade()` | NobitexTradeBatchTest | 11 | — |
| `body()` | NobitexTradeBatchTest | 13 | — |

### `data/NobitexTradeBudgetTest.kt` — 25 خط · 0 فانکشن

### `data/NobitexTradeQuoteTest.kt` — 31 خط · 1 فانکشن
> {"time":${now - 32_000},"price":"13.50"},{"time":${now - 2_000},"price":"13.75"}"""), now)

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `body()` | NobitexTradeQuoteTest | 11 | — |

### `data/NonCryptoRadarBundleTest.kt` — 81 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `closedSeries()` | NonCryptoRadarBundleTest | 16 | — |

### `data/OpenPositionDisplayTest.kt` — 55 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `trade()` | OpenPositionDisplayTest | 16 | — |

### `data/OpenPositionQuotePolicyTest.kt` — 56 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `trade()` | OpenPositionQuotePolicyTest | 16 | — |
| `market()` | OpenPositionQuotePolicyTest | 21 | — |

### `data/OpenTickSnapshotTest.kt` — 27 خط · 0 فانکشن

### `data/PublicCandleHistoryClientTest.kt` — 20 خط · 0 فانکشن

### `data/PublisherBudgetTest.kt` — 47 خط · 0 فانکشن

### `data/RadarFrameCacheTest.kt` — 66 خط · 0 فانکشن
> {"id":"SOL_USDT","base":"SOL","quote":"USDT","type":"normal","trade_status":"tradable"}

### `data/SigningContinuityTest.kt` — 15 خط · 0 فانکشن

### `data/SourceIntegrityTest.kt` — 91 خط · 0 فانکشن

### `data/SourceRoutingTest.kt` — 113 خط · 0 فانکشن

### `data/SymbolSearchTest.kt` — 104 خط · 1 فانکشن
> Searching a 400-row exchange by a fragment of a name.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `pair()` | SymbolSearchTest | 16 | — |

### `data/SymbolSourceMapTest.kt` — 75 خط · 0 فانکشن
> Locks the per-symbol source map that docs/SYMBOL_SOURCES_FA.md documents, so the table cannot

### `data/SymbolWorkersTest.kt` — 34 خط · 0 فانکشن

### `data/ThreeMinuteCandlesTest.kt` — 70 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `minute()` | ThreeMinuteCandlesTest | 15 | — |
| `read()` | ThreeMinuteCandlesTest | 55 | `#fn-alignment` در نقشهٔ HTML |

### `data/TradingViewFeedBoundaryTest.kt` — 58 خط · 0 فانکشن

### `data/TradingViewSymbolsTest.kt` — 47 خط · 0 فانکشن

### `data/TwelveDataCoverageTest.kt` — 44 خط · 0 فانکشن
> Twelve Data coverage is a contract about what may be requested, never a promise of data.

### `data/UpdateVerificationTest.kt` — 83 خط · 0 فانکشن
> The updater's install gates, exercised without Android. Each case is a way an in-app update can

### `data/UpdaterChannelOutcomeTest.kt` — 190 خط · 3 فانکشن
> End-to-end replay of what a phone sees on the public update channel, using the real

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `context()` | UpdaterChannelOutcomeTest | 47 | — |
| `getPackageName()` | UpdaterChannelOutcomeTest | 48 | — |
| `repository()` | UpdaterChannelOutcomeTest | 58 | — |

### `data/UpdaterDiscoveryTest.kt` — 76 خط · 2 فانکشن
> discover a public sidecar even when the old main-branch manifest is stale/unreachable. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `repository()` | UpdaterDiscoveryTest | 29 | — |
| `getPackageName()` | UpdaterDiscoveryTest | 49 | — |

### `data/VenueRequestPolicyTest.kt` — 92 خط · 0 فانکشن

### `data/WatchSourceProgressTest.kt` — 32 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `health()` | WatchSourceProgressTest | 8 | — |

### `engine/AlertDiagnosticsTest.kt` — 83 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `report()` | AlertDiagnosticsTest | 39 | — |
| `report()` | AlertDiagnosticsTest | 55 | — |

### `engine/Als5GoldEngineTest.kt` — 149 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `m15Bars()` | Als5GoldEngineTest | 21 | — |

### `engine/Als5TestFixtures.kt` — 56 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `asConfirmedAls5TestSignal()` | — | 11 | — |

### `engine/BtcAresEngineTest.kt` — 286 خط · 4 فانکشن
> BTC-ARES invariants. No network, no exchange, no order: every check is a rule that must hold

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | BtcAresEngineTest | 30 | — |
| `frames()` | BtcAresEngineTest | 39 | — |
| `sampleRecord()` | BtcAresEngineTest | 229 | — |
| `sampleSignal()` | BtcAresEngineTest | 266 | — |

### `engine/CandleCacheContractTest.kt` — 60 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `getFilesDir()` | CandleCacheContractTest | 41 | — |

### `engine/CatalogAutoPolicyTest.kt` — 72 خط · 0 فانکشن

### `engine/CryptoCompressionEngineTest.kt` — 167 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | CryptoCompressionEngineTest | 26 | — |
| `proof()` | CryptoCompressionEngineTest | 109 | — |

### `engine/DukascopyHistoryClientTest.kt` — 80 خط · 0 فانکشن

### `engine/EngineRegistryTest.kt` — 109 خط · 0 فانکشن
> The "similar engines must never interfere" contract, asserted instead of assumed.

### `engine/EngineTruthTest.kt` — 79 خط · 3 فانکشن
> JVM tests for the parts of the app that must never invent data.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fixture()` | EngineTruthTest | 21 | — |
| `aggregationPreservesRealOhlcAndDropsPartialBuckets()` | EngineTruthTest | 46 | — |
| `mtfNeedsRealHistoryBeforeItSaysAnything()` | EngineTruthTest | 69 | — |

### `engine/EurAresEngineTest.kt` — 430 خط · 7 فانکشن
> EUR-ARES invariants. Each assertion is a rule that decides whether a phone may open a

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | EurAresEngineTest | 35 | — |
| `frames()` | EurAresEngineTest | 47 | — |
| `evaluate()` | EurAresEngineTest | 62 | `#fn-signalengine` در نقشهٔ HTML |
| `bars()` | EurAresEngineTest | 188 | — |
| `sampleEngine()` | EurAresEngineTest | 227 | — |
| `sampleRecord()` | EurAresEngineTest | 257 | — |
| `sampleSignal()` | EurAresEngineTest | 288 | — |

### `engine/FeedNavigationTest.kt` — 67 خط · 0 فانکشن

### `engine/HistoryPolicyTest.kt` — 29 خط · 0 فانکشن

### `engine/IchimokuAlignmentTest.kt` — 54 خط · 0 فانکشن

### `engine/IchimokuEntrySetupTest.kt` — 61 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `setup()` | IchimokuEntrySetupTest | 26 | — |

### `engine/IctEntryRulesTest.kt` — 101 خط · 0 فانکشن

### `engine/IctRangeAnalyzerTest.kt` — 133 خط · 7 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `candle()` | IctRangeAnalyzerTest | 17 | — |
| `baseline()` | IctRangeAnalyzerTest | 21 | — |
| `sweep()` | IctRangeAnalyzerTest | 27 | — |
| `shift()` | IctRangeAnalyzerTest | 28 | — |
| `fvg()` | IctRangeAnalyzerTest | 29 | — |
| `retest()` | IctRangeAnalyzerTest | 30 | — |
| `setup()` | IctRangeAnalyzerTest | 31 | — |

### `engine/IctTestBars.kt` — 40 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `readyAt()` | IctTestBars | 15 | — |
| `candle()` | IctTestBars | 16 | — |

### `engine/IndicatorNeutralityTest.kt` — 21 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | IndicatorNeutralityTest | 9 | — |

### `engine/LiveQuoteValidationTest.kt` — 36 خط · 1 فانکشن
> {"event":"price","symbol":"$symbol","timestamp":$timestamp,"price":$price${bid?.let { ",\"bid\":$it" } ?: ""}${ask?.let { ",\"ask\

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `event()` | LiveQuoteValidationTest | 15 | — |

### `engine/LiveTradeValuationTest.kt` — 51 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `trade()` | LiveTradeValuationTest | 13 | — |

### `engine/MarketHoursTest.kt` — 58 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `at()` | MarketHoursTest | 11 | — |

### `engine/NineWayAutoPaperTest.kt` — 102 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `market()` | NineWayAutoPaperTest | 34 | — |

### `engine/OilEntrySafetyTest.kt` — 26 خط · 0 فانکشن

### `engine/PaperJournalPersistenceTest.kt` — 308 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `journalFile()` | PaperJournalPersistenceTest | 41 | — |
| `mtf()` | PaperJournalPersistenceTest | 48 | — |
| `ictFor()` | PaperJournalPersistenceTest | 50 | — |

### `engine/PaperOpportunityTest.kt` — 117 خط · 0 فانکشن

### `engine/PaperOrderRulesTest.kt` — 129 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `invalid()` | PaperOrderRulesTest | 114 | — |

### `engine/PublicCandleHistoryClientTest.kt` — 75 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `yahooJson()` | PublicCandleHistoryClientTest | 17 | — |
| `nums()` | PublicCandleHistoryClientTest | 19 | — |

### `engine/RecordedPaperEntryAlertTest.kt` — 105 خط · 0 فانکشن

### `engine/ResearchEvidenceTest.kt` — 80 خط · 0 فانکشن

### `engine/ScanFreshnessTest.kt` — 17 خط · 0 فانکشن

### `engine/ScanRankingTest.kt` — 41 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `row()` | ScanRankingTest | 16 | — |

### `engine/ScanUniverseTest.kt` — 31 خط · 0 فانکشن

### `engine/SettingsStoreTest.kt` — 150 خط · 0 فانکشن

### `engine/SignalProfileTest.kt` — 50 خط · 0 فانکشن

### `engine/TradeQuotePolicyTest.kt` — 18 خط · 0 فانکشن

### `engine/TradeReplayTest.kt` — 145 خط · 2 فانکشن
> The journal chart may only ever REPLAY bars the device already verified. These tests pin the

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `series()` | TradeReplayTest | 25 | — |
| `trade()` | TradeReplayTest | 30 | — |

### `engine/TwelveDataCandleContractTest.kt` — 103 خط · 2 فانکشن
> {"values":[$latest]}""",

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `json()` | TwelveDataCandleContractTest | 27 | — |
| `parse()` | TwelveDataCandleContractTest | 29 | — |

### `engine/UnifiedContractTest.kt` — 58 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `trade()` | UnifiedContractTest | 42 | — |

### `engine/V1ContractTest.kt` — 223 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | V1ContractTest | 28 | — |
| `trade()` | V1ContractTest | 177 | — |

### `engine/VerifiedAlertChannelsTest.kt` — 29 خط · 0 فانکشن

### `engine/VolumeSessionStatsTest.kt` — 37 خط · 0 فانکشن

### `engine/WatchAndMetricsTest.kt` — 107 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `quote()` | WatchAndMetricsTest | 26 | — |

### `engine/WatchKeyStoreTest.kt` — 32 خط · 0 فانکشن

### `engine/WatchRetryTest.kt` — 58 خط · 1 فانکشن
> {"chart":{"result":[{"meta":{"regularMarketPrice":1.0852,"chartPreviousClose":1.0801,"regularMarketTime":1700000000}}],"error":nul

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `network()` | WatchRetryTest | 20 | — |

### `engine/WatchVerificationReasonsTest.kt` — 77 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `quote()` | WatchVerificationReasonsTest | 27 | — |

### `engine/XagAresEngineTest.kt` — 339 خط · 6 فانکشن
> ALS-5S invariants. Every assertion is a rule that must hold whatever silver does, because these

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | XagAresEngineTest | 32 | — |
| `frames()` | XagAresEngineTest | 44 | — |
| `goldBars()` | XagAresEngineTest | 55 | — |
| `sampleEngine()` | XagAresEngineTest | 218 | — |
| `sampleRecord()` | XagAresEngineTest | 237 | — |
| `sampleSignal()` | XagAresEngineTest | 261 | — |

### `ui/TradingViewEmbedTest.kt` — 28 خط · 0 فانکشن

## سرویس بایگانی تیک (سرور) — `tick-archive` (2 فایل · 34 فانکشن)

### `archive.py` — 297 خط · 24 فانکشن
> Durable, same-venue public trade archive; never accepts account/position data.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `now_ms()` | — | 25 | — |
| `validate_trade()` | — | 29 | `#fn-archive` در نقشهٔ HTML |
| `__init__()` | Archive | 53 | — |
| `last_seq()` | Archive | 83 | — |
| `gap()` | Archive | 86 | — |
| `subscribed()` | Archive | 91 | — |
| `publish()` | Archive | 102 | `#fn-archive` در نقشهٔ HTML |
| `page()` | Archive | 131 | `#fn-archive` در نقشهٔ HTML |
| `__init__()` | Subscriber | 158 | — |
| `on_subscribed()` | Subscriber | 162 | — |
| `on_subscribing()` | Subscriber | 166 | — |
| `on_unsubscribed()` | Subscriber | 172 | — |
| `on_publication()` | Subscriber | 176 | `#fn-archive` در نقشهٔ HTML |
| `on_error()` | Subscriber | 188 | — |
| `__init__()` | ClientEvents | 193 | — |
| `on_connected()` | ClientEvents | 196 | — |
| `on_connecting()` | ClientEvents | 200 | — |
| `on_disconnected()` | ClientEvents | 207 | — |
| `on_error()` | ClientEvents | 214 | — |
| `make_app()` | — | 218 | — |
| `authorize()` | ClientEvents | 223 | — |
| `health()` | ClientEvents | 232 | — |
| `page()` | ClientEvents | 238 | `#fn-archive` در نقشهٔ HTML |
| `main()` | — | 259 | — |

### `tests/test_archive.py` — 87 خط · 10 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `setUp()` | ArchiveTests | 11 | — |
| `tearDown()` | ArchiveTests | 17 | — |
| `trade()` | ArchiveTests | 21 | — |
| `test_first_start_gap_survives_reopen()` | ArchiveTests | 25 | — |
| `test_offset_dedup_jump_and_recovery_gap()` | ArchiveTests | 35 | — |
| `test_stale_does_not_become_fresh_and_no_foreign_symbol()` | ArchiveTests | 47 | — |
| `test_page_order_is_local_sequence_not_millisecond()` | ArchiveTests | 56 | — |
| `asyncSetUp()` | HttpTests | 66 | — |
| `asyncTearDown()` | HttpTests | 72 | — |
| `test_auth_and_bounds()` | HttpTests | 77 | — |

## ابزارهای توسعه و راستی‌آزمایی — `tools` (20 فایل · 361 فانکشن)

### `als5_backtest.py` — 1061 خط · 23 فانکشن
> ALS-5 — Adaptive Liquidity & Structure Gold (XAU/USD) Backtesting & Validation Engine.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `record_candidate()` | OpportunityAudit | 149 | — |
| `summary()` | OpportunityAudit | 157 | — |
| `resample_m5_to_m15()` | — | 164 | — |
| `compute_atr()` | — | 182 | — |
| `compute_ema()` | — | 204 | — |
| `compute_adx()` | — | 214 | — |
| `confirmed_swings()` | — | 242 | — |
| `session_of()` | — | 270 | — |
| `session_allowed()` | — | 285 | — |
| `gold_market_open()` | — | 296 | — |
| `in_news_blackout()` | — | 312 | — |
| `calculate_position_size()` | — | 317 | — |
| `net_reward_risk()` | — | 338 | — |
| `select_target()` | — | 347 | — |
| `counterfactual_outcome()` | — | 360 | — |
| `run_backtest()` | — | 378 | — |
| `summarize_metrics()` | — | 795 | — |
| `group_stats()` | OpportunityAudit | 845 | — |
| `generate_synthetic_fixture()` | — | 881 | — |
| `run_stages_a_to_e()` | — | 932 | — |
| `metrics()` | OpportunityAudit | 970 | — |
| `load_csv_bars()` | — | 1017 | — |
| `main()` | — | 1035 | — |

### `als5s_backtest.py` — 1466 خط · 45 فانکشن
> ALS-5S — offline research harness for silver (XAG/USD) on M5.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `wilder()` | — | 146 | — |
| `true_ranges()` | — | 158 | — |
| `compute_atr()` | — | 169 | — |
| `compute_ema()` | — | 173 | — |
| `compute_rsi()` | — | 186 | — |
| `compute_adx()` | — | 203 | — |
| `compute_macd_histogram()` | — | 241 | — |
| `relative_volume()` | — | 249 | — |
| `confirmed_swings()` | — | 258 | — |
| `percentile_rank()` | — | 278 | — |
| `session_of()` | — | 296 | — |
| `gold_context()` | — | 320 | — |
| `rets()` | GoldContext | 334 | — |
| `volatility_of()` | — | 395 | — |
| `raw_regime()` | — | 405 | — |
| `classify_regime()` | — | 425 | — |
| `build_features()` | — | 437 | — |
| `round_trip_cost_per_oz()` | — | 500 | — |
| `net_reward_risk()` | — | 507 | — |
| `position_oz()` | — | 516 | — |
| `score()` | Candidate | 563 | `#fn-v1score-side` در نقشهٔ HTML |
| `_no_trade()` | — | 568 | — |
| `session_points()` | — | 575 | — |
| `execution_points()` | — | 583 | — |
| `rr_points()` | — | 594 | — |
| `stop_for()` | — | 604 | — |
| `target_at()` | — | 610 | — |
| `engine_a()` | — | 615 | — |
| `engine_b()` | — | 685 | — |
| `engine_c_range()` | — | 758 | — |
| `engine_c_breakout()` | — | 811 | — |
| `engine_x()` | — | 877 | — |
| `decide()` | — | 921 | — |
| `resample()` | — | 1030 | `#fn-mtf` در نقشهٔ HTML |
| `simulate_trade()` | — | 1046 | — |
| `replay()` | — | 1113 | — |
| `summarize()` | — | 1170 | — |
| `group()` | Trade | 1199 | — |
| `generate_synthetic_fixture()` | — | 1233 | — |
| `load_csv_bars()` | — | 1259 | — |
| `_metrics()` | — | 1290 | — |
| `run_stages()` | — | 1296 | — |
| `load_bars()` | — | 1374 | — |
| `report()` | — | 1384 | — |
| `main()` | — | 1422 | — |

### `btc_ares_backtest.py` — 1520 خط · 38 فانکشن
> BTC-ARES — offline validation for the BTC/USDT model that ships in the Android app.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `wilder()` | — | 140 | — |
| `true_ranges()` | — | 153 | — |
| `compute_atr()` | — | 164 | — |
| `compute_ema()` | — | 168 | — |
| `compute_rsi()` | — | 181 | — |
| `compute_adx()` | — | 207 | — |
| `compute_macd_histogram()` | — | 246 | — |
| `relative_volume()` | — | 262 | — |
| `confirmed_swings()` | — | 272 | — |
| `percentile_rank()` | — | 293 | — |
| `volatility_of()` | — | 340 | — |
| `classify_regime()` | — | 350 | — |
| `_no_trade()` | — | 397 | — |
| `stop_for()` | — | 402 | — |
| `target_at()` | — | 409 | — |
| `score_of()` | — | 414 | — |
| `engine_a()` | — | 456 | — |
| `engine_b()` | — | 544 | — |
| `engine_c()` | — | 629 | — |
| `engine_d()` | — | 700 | — |
| `engine_e()` | — | 760 | — |
| `engine_f()` | — | 827 | — |
| `build_features()` | — | 840 | — |
| `position_size()` | — | 943 | — |
| `net_reward_risk()` | — | 963 | — |
| `decide()` | — | 982 | — |
| `replay()` | — | 1103 | — |
| `summarize()` | — | 1206 | — |
| `group()` | Trade | 1237 | — |
| `generate_synthetic_fixture()` | — | 1271 | — |
| `resample()` | — | 1302 | `#fn-mtf` در نقشهٔ HTML |
| `load_csv_bars()` | — | 1322 | — |
| `_stage_metrics()` | — | 1362 | — |
| `run_stages()` | — | 1374 | — |
| `_with_params()` | — | 1431 | — |
| `load_bars()` | — | 1440 | — |
| `report()` | — | 1452 | — |
| `main()` | — | 1486 | — |

### `diagnose_in_app_update.py` — 306 خط · 14 فانکشن
> Replay the in-app updater's decision rules outside the phone.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `pinned_certificate()` | — | 26 | — |
| `normalize_sha256()` | — | 45 | — |
| `trusted_release_asset()` | — | 55 | — |
| `trusted_metadata_asset()` | — | 60 | — |
| `semantic_version()` | — | 65 | — |
| `compare_versions()` | — | 70 | — |
| `version_rank()` | — | 78 | — |
| `fetch_json()` | — | 88 | — |
| `check_manifest()` | — | 116 | — |
| `check_release()` | — | 144 | — |
| `choose_update()` | — | 189 | — |
| `diagnose()` | — | 198 | — |
| `fetch_sidecar()` | — | 207 | — |
| `main()` | — | 258 | — |

### `eur_ares_backtest.py` — 1612 خط · 51 فانکشن
> EUR-ARES — offline research harness for EUR/USD on M5.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `session_at()` | — | 163 | — |
| `is_tradable_session()` | — | 194 | — |
| `wilder()` | — | 211 | — |
| `true_ranges()` | — | 223 | — |
| `compute_atr()` | — | 234 | — |
| `compute_ema()` | — | 238 | — |
| `compute_rsi()` | — | 251 | — |
| `compute_adx()` | — | 267 | — |
| `compute_macd_histogram()` | — | 305 | — |
| `confirmed_swings()` | — | 313 | — |
| `percentile_rank()` | — | 330 | — |
| `round_trip_cost_pips()` | — | 341 | — |
| `net_reward_risk()` | — | 351 | — |
| `position_size()` | — | 360 | — |
| `pips()` | — | 383 | — |
| `utc_day()` | — | 438 | — |
| `asian_range()` | — | 442 | — |
| `london_opening_range()` | — | 452 | — |
| `previous_day_range()` | — | 464 | — |
| `round_numbers()` | — | 477 | — |
| `volatility_of()` | — | 486 | — |
| `raw_regime()` | — | 498 | — |
| `classify_regime()` | — | 520 | — |
| `build_features()` | — | 532 | — |
| `score()` | Candidate | 638 | `#fn-v1score-side` در نقشهٔ HTML |
| `stop_pips()` | Candidate | 644 | — |
| `_no_trade()` | — | 650 | — |
| `session_points()` | — | 658 | — |
| `rr_points()` | — | 662 | — |
| `cost_points()` | — | 674 | — |
| `volatility_points()` | — | 684 | — |
| `stop_for()` | — | 694 | — |
| `target_at()` | — | 701 | — |
| `engine_a()` | — | 706 | — |
| `engine_b()` | — | 789 | — |
| `engine_c()` | — | 872 | — |
| `engine_d()` | — | 931 | — |
| `engine_e()` | — | 1043 | — |
| `engine_x()` | — | 1050 | — |
| `decide()` | — | 1088 | — |
| `simulate_trade()` | — | 1195 | — |
| `replay()` | — | 1263 | — |
| `summarize()` | — | 1309 | — |
| `group()` | Trade | 1340 | — |
| `generate_synthetic_fixture()` | — | 1380 | — |
| `to_h1()` | — | 1407 | — |
| `load_csv_bars()` | — | 1423 | — |
| `_metrics()` | — | 1454 | — |
| `run_stages()` | — | 1461 | — |
| `report()` | — | 1510 | — |
| `main()` | — | 1556 | — |

### `probe_crypto_venues.py` — 297 خط · 14 فانکشن
> Read-only public venue coverage audit for the 5 CryptoCatalog USDT pairs.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse_catalog_symbols()` | — | 31 | — |
| `load_catalog_symbols()` | — | 38 | — |
| `default_get_json()` | — | 45 | — |
| `binance_spot_usdt_pairs()` | — | 55 | — |
| `okx_spot_usdt_pairs()` | — | 75 | — |
| `bybit_spot_usdt_pairs()` | — | 95 | — |
| `kucoin_spot_usdt_pairs()` | — | 115 | — |
| `gate_spot_usdt_pairs()` | — | 134 | — |
| `mexc_spot_usdt_pairs()` | — | 150 | — |
| `kraken_spot_usdt_pairs()` | — | 170 | — |
| `nobitex_usdt_pairs()` | — | 188 | — |
| `coverage_summary()` | — | 220 | — |
| `validate_closed_bars()` | — | 248 | — |
| `main()` | — | 273 | — |

### `probe_twelve_coverage.py` — 216 خط · 9 فانکشن
> Read-only Twelve Data coverage probe for the 15-symbol catalog.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `read_catalog_symbols()` | — | 57 | — |
| `key_env_name()` | — | 77 | — |
| `key_for()` | — | 82 | — |
| `expected_status()` | — | 86 | — |
| `classify()` | — | 96 | — |
| `request_symbol()` | — | 121 | — |
| `plan_requests()` | — | 126 | — |
| `fetch_one()` | — | 139 | — |
| `main()` | — | 165 | — |

### `refresh_update_manifest.py` — 166 خط · 6 فانکشن
> Refresh update/aurum-edge.json from the sidecar of a published updater release.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `_is_digest()` | — | 43 | — |
| `build_manifest()` | — | 47 | — |
| `render()` | — | 96 | — |
| `download_sidecar()` | — | 100 | — |
| `_existing_notes()` | — | 107 | — |
| `main()` | — | 118 | — |

### `source_inventory.py` — 173 خط · 5 فانکشن
> Deterministic inventory of the 15-symbol scanner universe and its 1-to-1 dedicated sources.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `crypto_symbols()` | — | 72 | — |
| `scanner_symbols()` | — | 80 | — |
| `classify()` | — | 88 | — |
| `build_inventory()` | — | 138 | — |
| `write_csv()` | — | 150 | — |

### `test_als5_backtest.py` — 74 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `test_confirmed_swings_never_leak_future_bars()` | Als5BacktestTest | 8 | — |
| `test_m5_to_m15_resampling_converts_every_3_m5_bars()` | Als5BacktestTest | 29 | — |
| `test_position_sizing_enforces_025_pct_risk_and_min_lot()` | Als5BacktestTest | 38 | — |
| `test_stages_a_to_e_harness_executes_reproducibly()` | Als5BacktestTest | 47 | — |

### `test_als5s_backtest.py` — 335 خط · 41 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fixture()` | — | 11 | — |
| `test_pivots_stay_invisible_until_their_right_bars_close()` | PivotTest | 16 | — |
| `test_protective_state_is_entered_immediately()` | RegimeTest | 30 | — |
| `test_leaving_the_protective_state_needs_a_second_bar()` | RegimeTest | 36 | — |
| `test_hysteresis_never_lands_on_an_unconfirmed_label()` | RegimeTest | 44 | — |
| `test_round_trip_cost_per_ounce_matches_the_kotlin_policy()` | CostTest | 53 | — |
| `test_a_wider_spread_is_charged_to_the_trade()` | CostTest | 58 | — |
| `test_200_ounce_round_trip_costs_are_linear_in_size()` | CostTest | 62 | — |
| `test_a_tight_stop_cannot_pay_for_its_own_costs()` | RewardRiskTest | 69 | — |
| `test_a_target_inside_the_cost_is_worthless()` | RewardRiskTest | 77 | — |
| `test_risk_cap_governs_size()` | SizingTest | 83 | — |
| `test_a_sub_minimum_order_is_refused_rather_than_inflated()` | SizingTest | 92 | — |
| `test_relative_volume_is_an_own_history_ratio()` | FeatureTest | 100 | — |
| `test_a_gap_enters_the_true_range_through_the_previous_close()` | FeatureTest | 107 | — |
| `test_engine_a_refuses_without_a_closed_m15_frame()` | FeatureTest | 113 | — |
| `test_the_cross_module_is_unavailable_without_gold()` | GoldModuleTest | 125 | — |
| `test_gold_alone_never_creates_a_direction()` | GoldModuleTest | 133 | — |
| `test_correlation_of_a_scaled_copy_is_one()` | GoldModuleTest | 143 | — |
| `test_replay_never_uses_a_bar_before_it_closes()` | ReplayTest | 154 | — |
| `test_a_refused_tape_reports_its_refusal_reasons()` | ReplayTest | 163 | — |
| `test_zero_trades_summarise_without_dividing_by_zero()` | ReplayTest | 170 | — |
| `test_the_daily_cap_stops_new_entries_for_the_rest_of_the_day()` | ReplayTest | 175 | — |
| `_flat()` | FillTest | 186 | — |
| `test_fill_happens_at_the_next_open_plus_slippage()` | FillTest | 190 | — |
| `test_the_stop_wins_a_tie_against_the_target()` | FillTest | 198 | — |
| `test_a_target_fill_still_pays_its_costs()` | FillTest | 206 | — |
| `test_a_gap_past_the_invalidation_is_refused_not_widened()` | FillTest | 217 | — |
| `test_a_trade_that_never_triggers_expires_at_the_last_close()` | FillTest | 224 | — |
| `test_trailing_stop_moves_only_after_one_r()` | FillTest | 231 | — |
| `test_the_fixture_is_deterministic()` | FixtureTest | 245 | — |
| `test_the_fixture_is_flagged_as_synthetic()` | FixtureTest | 248 | — |
| `test_iso_and_epoch_timestamps_are_both_accepted()` | CsvTest | 254 | — |
| `test_a_csv_without_columns_is_rejected()` | CsvTest | 278 | — |
| `setUpClass()` | PineContractTest | 290 | — |
| `test_it_is_a_v6_strategy_not_an_indicator()` | PineContractTest | 295 | — |
| `test_orders_only_after_a_bar_closes()` | PineContractTest | 301 | — |
| `test_higher_timeframe_requests_cannot_look_ahead()` | PineContractTest | 307 | — |
| `test_pivots_wait_for_their_right_bars()` | PineContractTest | 312 | — |
| `test_costs_and_risk_are_explicit()` | PineContractTest | 316 | — |
| `test_only_a_ready_engine_can_be_chosen()` | PineContractTest | 321 | — |
| `test_no_martingale_or_stop_widening_helpers()` | PineContractTest | 325 | — |

### `test_btc_ares_backtest.py` — 296 خط · 32 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fixture()` | — | 11 | — |
| `test_pivots_stay_invisible_until_their_right_bars_close()` | PivotTest | 16 | — |
| `test_a_flat_series_is_not_a_breakout_of_everything()` | PivotTest | 28 | — |
| `features()` | RegimeTest | 35 | — |
| `test_compression_needs_a_low_adx_and_a_narrow_range()` | RegimeTest | 46 | — |
| `test_extreme_volatility_short_circuits_every_other_regime()` | RegimeTest | 53 | — |
| `test_contradictory_structure_is_uncertain_not_a_bullish_guess()` | RegimeTest | 59 | — |
| `test_range_requires_width_and_quiet_adx()` | RegimeTest | 64 | — |
| `test_engine_f_is_reported_but_never_scored()` | DecisionTest | 73 | — |
| `test_every_decision_carries_six_engines_and_one_owner()` | DecisionTest | 81 | — |
| `test_no_action_without_a_ready_engine()` | DecisionTest | 93 | — |
| `test_net_reward_risk_is_what_the_gate_tests()` | DecisionTest | 103 | — |
| `test_costs_are_never_allowed_to_flatter_a_setup()` | DecisionTest | 113 | — |
| `test_risk_never_exceeds_the_cap()` | SizingTest | 122 | — |
| `test_quantity_respects_the_exchange_step()` | SizingTest | 130 | — |
| `test_a_tiny_account_cannot_open_a_trade()` | SizingTest | 135 | — |
| `test_invalid_levels_return_zero_instead_of_nan()` | SizingTest | 141 | — |
| `test_replay_is_deterministic()` | ReplayTest | 149 | — |
| `test_a_decision_never_reads_bars_that_have_not_closed()` | ReplayTest | 159 | — |
| `test_entry_is_filled_one_bar_after_the_decision()` | ReplayTest | 169 | — |
| `test_costs_are_charged_on_both_legs()` | ReplayTest | 181 | — |
| `test_stop_wins_when_both_levels_are_touched_in_one_bar()` | ReplayTest | 191 | — |
| `trade()` | SummaryTest | 207 | — |
| `test_metrics_are_computed_from_the_trades_not_asserted()` | SummaryTest | 211 | — |
| `test_empty_results_report_zero_win_rate_instead_of_nan()` | SummaryTest | 220 | — |
| `test_drawdown_is_measured_from_the_running_peak()` | SummaryTest | 226 | — |
| `test_iso_and_epoch_times_are_both_accepted()` | CsvTest | 234 | — |
| `test_missing_columns_are_reported_not_guessed()` | CsvTest | 248 | — |
| `test_short_csv_is_refused_by_the_cli_loader()` | CsvTest | 256 | — |
| `test_synthetic_runs_are_labelled_and_never_claim_a_probability()` | ReportTest | 269 | — |
| `test_real_csv_runs_carry_no_synthetic_warning()` | ReportTest | 277 | — |
| `test_stages_cover_each_engine_separately()` | ReportTest | 282 | — |

### `test_eur_ares_backtest.py` — 297 خط · 34 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fixture()` | — | 11 | — |
| `ts()` | — | 15 | — |
| `test_round_trip_cost_is_spread_plus_two_commissions_plus_two_slippages()` | PipMathTest | 20 | — |
| `test_a_pip_is_not_a_pipette()` | PipMathTest | 26 | — |
| `test_net_reward_risk_is_measured_after_costs()` | PipMathTest | 32 | — |
| `test_size_follows_the_stop_in_pips()` | SizingTest | 42 | — |
| `test_a_wider_stop_buys_fewer_lots()` | SizingTest | 49 | — |
| `test_below_the_venue_minimum_is_a_refusal_not_a_bigger_bet()` | SizingTest | 55 | — |
| `test_leverage_caps_the_notional()` | SizingTest | 60 | — |
| `test_the_same_utc_hour_is_a_different_session_in_winter_and_summer()` | SessionTest | 67 | — |
| `test_tokyo_and_new_york_are_classified_without_dst_guesses()` | SessionTest | 77 | — |
| `test_the_rollover_window_is_its_own_state()` | SessionTest | 86 | — |
| `test_pivots_stay_invisible_until_their_right_bars_close()` | StructureTest | 97 | — |
| `test_round_number_grid_is_fifty_pips()` | StructureTest | 106 | — |
| `test_the_previous_day_range_ignores_the_current_day()` | StructureTest | 112 | — |
| `test_regime_hysteresis_protects_immediately()` | StructureTest | 121 | — |
| `test_news_and_macro_are_unavailable_never_directions()` | EngineTest | 133 | — |
| `test_every_engine_prices_the_round_trip()` | EngineTest | 142 | — |
| `test_a_decision_never_acts_without_a_ready_engine()` | EngineTest | 151 | — |
| `test_the_score_is_a_sum_of_its_components()` | EngineTest | 161 | — |
| `test_a_ready_engine_still_needs_net_rr_and_a_cost_cap()` | EngineTest | 171 | — |
| `_flat()` | FillTest | 183 | — |
| `test_fill_is_the_next_open_plus_slippage()` | FillTest | 187 | — |
| `test_the_stop_wins_a_tie_against_the_target()` | FillTest | 195 | — |
| `test_a_gap_past_the_invalidation_is_refused_not_widened()` | FillTest | 203 | — |
| `test_costs_are_charged_in_currency_on_both_sides()` | FillTest | 210 | — |
| `test_a_refused_tape_reports_its_reasons()` | ReplayTest | 222 | — |
| `test_zero_trades_summarise_without_dividing_by_zero()` | ReplayTest | 229 | — |
| `test_the_daily_cap_blocks_new_entries()` | ReplayTest | 234 | — |
| `test_the_rollover_window_blocks_entries_in_the_replay()` | ReplayTest | 241 | — |
| `test_exit_models_are_independent_and_deterministic()` | ReplayTest | 251 | — |
| `test_the_fixture_is_deterministic()` | FixtureAndCsvTest | 263 | — |
| `test_iso_and_epoch_timestamps_are_both_accepted()` | FixtureAndCsvTest | 266 | — |
| `test_a_csv_without_columns_is_rejected()` | FixtureAndCsvTest | 288 | — |

### `test_probe_crypto_venues.py` — 107 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `test_catalog_has_5_unique_usdt_pairs()` | ProbeCryptoVenuesTest | 7 | — |
| `test_exchange_parsers_accept_only_live_spot_usdt_pairs()` | ProbeCryptoVenuesTest | 13 | — |
| `test_coverage_summary_Separates_binance_from_fallbacks()` | ProbeCryptoVenuesTest | 78 | — |
| `test_closed_bar_validator_checks_1500_contiguous_fresh_bars()` | ProbeCryptoVenuesTest | 91 | — |

### `test_probe_twelve_coverage.py` — 48 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `test_catalog_reads_all_fifteen_symbols()` | ProbeTwelveCoverageTest | 7 | — |
| `test_key_env_names_are_stable()` | ProbeTwelveCoverageTest | 15 | — |
| `test_classifier_never_invents_success()` | ProbeTwelveCoverageTest | 20 | — |
| `test_expected_status_is_honest_about_paid_markets()` | ProbeTwelveCoverageTest | 40 | — |

### `test_refresh_update_manifest.py` — 136 خط · 12 فانکشن
> Offline tests for tools/refresh_update_manifest.py — no network, no release access.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `sidecar()` | — | 18 | — |
| `test_accepts_a_real_sidecar_and_keeps_the_known_key_order()` | BuildManifestTest | 41 | — |
| `test_drops_unknown_keys_and_normalises_case()` | BuildManifestTest | 48 | — |
| `test_rejects_anything_the_phone_would_have_to_guess()` | BuildManifestTest | 55 | — |
| `test_the_hand_written_note_survives_a_refresh()` | BuildManifestTest | 73 | — |
| `test_tag_argument_wins_over_the_sidecar()` | BuildManifestTest | 78 | — |
| `setUp()` | CommandLineTest | 84 | — |
| `run_cli()` | CommandLineTest | 92 | — |
| `test_writes_the_manifest_then_reports_it_as_current()` | CommandLineTest | 98 | — |
| `test_check_fails_on_a_stale_manifest()` | CommandLineTest | 109 | — |
| `test_refresh_keeps_the_existing_note_but_updates_the_version()` | CommandLineTest | 118 | — |
| `test_invalid_sidecar_never_writes_a_file()` | CommandLineTest | 127 | — |

### `test_source_inventory.py` — 45 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `test_catalog_has_15_symbols_with_5_crypto_and_10_non_crypto()` | SourceInventoryTest | 10 | — |
| `test_every_symbol_has_unique_dedicated_source()` | SourceInventoryTest | 18 | — |
| `test_csv_matches_committed_inventory()` | SourceInventoryTest | 31 | — |

### `test_verify_apk_upgrade.py` — 93 خط · 11 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `setUp()` | UpgradeCompatibilityTest | 15 | — |
| `check()` | UpgradeCompatibilityTest | 21 | — |
| `test_same_channel_higher_version_is_an_upgrade_not_a_device_install_claim()` | UpgradeCompatibilityTest | 25 | — |
| `test_equal_and_lower_codes_cannot_publish_but_validation_can_run_after_release()` | UpgradeCompatibilityTest | 31 | — |
| `test_rejects_incompatible_package_signer_version_manifest_abi_and_size()` | UpgradeCompatibilityTest | 38 | — |
| `test_proxy_free_upgrade_can_be_architecture_independent_but_must_contain_no_proxy()` | UpgradeCompatibilityTest | 54 | — |
| `check()` | UpgradeCompatibilityTest | 57 | — |
| `test_previous_apk_must_match_installed_channel_too()` | UpgradeCompatibilityTest | 66 | — |
| `test_minimum_sdk_cannot_increase_even_below_current_floor()` | UpgradeCompatibilityTest | 71 | — |
| `test_badging_reads_real_flags_and_rejects_missing_fields()` | UpgradeCompatibilityTest | 76 | — |
| `test_signers_are_exact_not_partial_or_source_stamp_matches()` | UpgradeCompatibilityTest | 84 | — |

### `verify_apk_upgrade.py` — 140 خط · 7 فانکشن
> Read-only release gate. Uses Android SDK tools; never accesses signing private keys.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse_badging()` | — | 18 | — |
| `sdk()` | — | 24 | — |
| `parse_signers()` | — | 44 | — |
| `inspect()` | — | 51 | — |
| `run()` | — | 52 | — |
| `verify()` | — | 81 | — |
| `main()` | — | 119 | — |

### `verify_public_updater.py` — 93 خط · 4 فانکشن
> Anonymous, retryable audit of the actual published APK (no GitHub token needed).

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `request()` | — | 11 | — |
| `get_json()` | — | 18 | — |
| `audit()` | — | 26 | — |
| `main()` | — | 71 | — |

