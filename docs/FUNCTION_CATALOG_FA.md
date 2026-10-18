# کاتالوگ کامل فانکشن‌های مخزن (استخراج خودکار از سورس)

> این فایل با `docs/flowchart/generate_flowchart.py` ساخته می‌شود؛ دستی ویرایشش نکنید.
> مجموع: 257 فایل · 36429 خط · 1043 فانکشن. نقشهٔ تصویری تعاملی: `docs/flowchart/index.html`

## اپ اندروید — کد اصلی — `android/app/src/main/java/com/aurum/edge` (145 فایل · 825 فانکشن)

### `AurumApplication.kt` — 17 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onCreate()` | AurumApplication | 12 | — |

### `MainActivity.kt` — 25 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onCreate()` | MainActivity | 13 | — |

### `core/AlertDiagnostics.kt` — 137 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `checks()` | AlertDiagnostics | 22 | — |
| `ago()` | AlertDiagnostics | 129 | — |

### `core/AppContainer.kt` — 258 خط · 4 فانکشن
> Process-wide wiring. Single source of truth for settings, real market data, journal and engine.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `exportJournalPdf()` | AppContainer | 155 | — |
| `downloadCandles()` | AppContainer | 173 | — |
| `cachedCandles()` | AppContainer | 241 | — |
| `fetchTradeCandles()` | AppContainer | 249 | — |

### `core/CryptoCompressionPolicy.kt` — 43 خط · 2 فانکشن
> London and New York DST are evaluated by their own IANA clocks; no Asian session.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `londonOrNewYork()` | CryptoCompressionPolicy | 18 | — |
| `quoteBlocker()` | CryptoCompressionPolicy | 28 | — |

### `core/FeedLiveness.kt` — 40 خط · 4 فانکشن
> This is a presentation/safety downgrade ONLY: it never fabricates a tick or extends its time.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `hasRecentReceipt()` | FeedLiveness | 10 | — |
| `display()` | FeedLiveness | 15 | — |
| `freshDetail()` | FeedLiveness | 29 | — |
| `looksLikeLiveTickProvider()` | FeedLiveness | 35 | — |

### `core/ForexNewsDecision.kt` — 50 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `assess()` | ForexNewsDecisions | 19 | `#fn-entrysetup` در نقشهٔ HTML |

### `core/HistoryPolicy.kt` — 41 خط · 4 فانکشن
> Unified 5-minute (M5) candle history policy:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `chartTargetCandles()` | HistoryPolicy | 30 | — |
| `providerRequestSize()` | HistoryPolicy | 33 | — |
| `deepProviderRequestSize()` | HistoryPolicy | 36 | — |
| `trimForCache()` | HistoryPolicy | 39 | — |

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

### `core/MarketHours.kt` — 135 خط · 9 فانکشن
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
| `marketLabel()` | MarketHours | 126 | — |
| `nextAt()` | MarketHours | 130 | — |

### `core/MarketPlaybook.kt` — 539 خط · 13 فانکشن
> «کدام روش، برای کدام بازار، در چه ساعتی» — the per-market method router.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `familyOf()` | MarketPlaybook | 133 | — |
| `sessionOf()` | MarketPlaybook | 156 | — |
| `isThinLiquidity()` | MarketPlaybook | 198 | — |
| `minRewardBpsFor()` | MarketPlaybook | 209 | — |
| `costRewardMultipleFor()` | MarketPlaybook | 224 | — |
| `rrBounds()` | MarketPlaybook | 231 | — |
| `stopAtrBounds()` | MarketPlaybook | 239 | — |
| `assess()` | MarketPlaybook | 258 | `#fn-entrysetup` در نقشهٔ HTML |
| `chooseMethod()` | MarketPlaybook | 348 | — |
| `efficiencyRatio()` | MarketPlaybook | 488 | — |
| `median()` | MarketPlaybook | 497 | — |
| `buildReason()` | MarketPlaybook | 504 | — |
| `fmt()` | MarketPlaybook | 537 | — |

### `core/MarketTrend.kt` — 689 خط · 16 فانکشن
> «روند کلی بازار» — چه چیزی اندازه گرفته می‌شود و چطور به خودِ معامله اضافه می‌شود.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `higherFor()` | MarketTrend | 213 | — |
| `symbolTrend()` | MarketTrend | 230 | — |
| `measure()` | MarketTrend | 310 | — |
| `structureOf()` | MarketTrend | 377 | — |
| `directionOf()` | MarketTrend | 404 | — |
| `strengthOf()` | MarketTrend | 411 | — |
| `overall()` | MarketTrend | 422 | — |
| `familyTrendOf()` | MarketTrend | 480 | — |
| `dollarBias()` | MarketTrend | 496 | — |
| `riskTone()` | MarketTrend | 524 | — |
| `alignmentOf()` | MarketTrend | 547 | — |
| `entryGate()` | MarketTrend | 566 | — |
| `contextOf()` | MarketTrend | 665 | — |
| `sideLabel()` | MarketTrend | 678 | — |
| `fmt()` | MarketTrend | 684 | — |
| `fmtPct()` | MarketTrend | 687 | — |

### `core/Models.kt` — 783 خط · 17 فانکشن
> Real market data contracts.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fromLabel()` | Interval | 39 | — |
| `of()` | AssetClass | 64 | — |
| `fromId()` | StrategyKind | 101 | — |
| `activeLabels()` | SignalProfile | 132 | — |
| `persistName()` | SignalProfile | 146 | — |
| `forStrategy()` | SignalProfile | 162 | — |
| `fromName()` | SignalProfile | 172 | — |
| `matches()` | IchimokuEntryEvidence | 209 | — |
| `matches()` | CryptoBreakoutEvidence | 227 | — |
| `matches()` | Als5OpportunityRecord | 275 | — |
| `matches()` | IctPriceActionRecord | 432 | — |
| `from()` | PaperConditionRecord | 558 | — |
| `from()` | PaperOpportunity | 582 | — |
| `from()` | SymbolTrendRecord | 625 | — |
| `from()` | MarketTrendRecord | 663 | — |
| `from()` | MtfSnapshotRecord | 707 | — |
| `strategyFor()` | AppSettings | 781 | — |

### `core/OilEntrySafety.kt` — 14 خط · 1 فانکشن
> Fail-closed quarantine for NEW paper oil entries until the user's losing trades can be

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | OilEntrySafety | 12 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperAlertRules.kt` — 43 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | PaperAlertRules | 9 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperAutoRules.kt` — 100 خط · 2 فانکشن
> A technical candidate alert can be enabled while automatic paper entry is OFF.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | PaperAutoRules | 10 | `#fn-autorules` در نقشهٔ HTML |
| `opportunityBlocker()` | PaperAutoRules | 57 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperOrderRules.kt` — 161 خط · 5 فانکشن
> سقف اهرم همان بازار، از [VenueSpecs]: جفت‌ارز اصلی ۳۰:۱ · جفت‌ارز غیراصلی و طلا ۲۰:۱ ·

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `defaultLeverageFor()` | PaperOrderRules | 34 | — |
| `paperable()` | PaperOrderRules | 37 | — |
| `unitFor()` | PaperOrderRules | 43 | — |
| `quotePnlToUsd()` | PaperOrderRules | 57 | — |
| `preview()` | PaperOrderRules | 66 | `#fn-ticket` در نقشهٔ HTML |

### `core/PaperPortfolioPolicy.kt` — 70 خط · 1 فانکشن
> Closed trades, including those from an earlier installation, remain in the UTC-day loss

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocker()` | PaperPortfolioPolicy | 18 | `#fn-autorules` در نقشهٔ HTML |

### `core/PaperTrailing.kt` — 40 خط · 1 فانکشن
> prices only. Original stop/risk stays immutable for R and portfolio budgeting. On an OHLC

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `advance()` | PaperTrailing | 11 | `#fn-settle` در نقشهٔ HTML |

### `core/TechnicalEvidence.kt` — 59 خط · 4 فانکشن
> do not use them to authorize new automatic entries. News is a separate AI supervision layer.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `items()` | TechnicalEvidence | 21 | — |
| `confirmed()` | TechnicalEvidence | 23 | — |
| `confirmed()` | TechnicalEvidence | 34 | — |
| `confirmedRecords()` | TechnicalEvidence | 49 | — |

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

### `data/AiConnectionDiagnostics.kt` — 35 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `describe()` | AiConnectionDiagnostics | 10 | — |

### `data/AiCredentialScope.kt` — 35 خط · 3 فانکشن
> Switching to a GLM profile must never forward an existing Claude/OpenAI key to LLMsRelay.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `sameNewsClientConfig()` | AiCredentialScope | 10 | — |
| `format()` | AiCredentialScope | 17 | — |
| `key()` | AiCredentialScope | 20 | — |

### `data/AiEntryProof.kt` — 24 خط · 2 فانکشن
> execution of PaperAutoTrader's model-review branch, never presumed AI performance.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fromAction()` | AiEntryEffect | 14 | — |
| `fromResponse()` | AiEntryEffect | 16 | `#fn-ai-plan` در نقشهٔ HTML |

### `data/AiNewsPresentation.kt` — 27 خط · 1 فانکشن
> as AI analysis. A keyword classifier, stale verdict or an unrelated gold card is never AI.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `forHeadline()` | AiNewsPresentation | 10 | — |

### `data/AiProvider.kt` — 253 خط · 17 فانکشن
> Direct, on-device AI calls with the user's OWN key. Three request formats are supported and

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `isLlmRelay()` | AiProvider | 32 | — |
| `providerError()` | AiProvider | 35 | — |
| `isAnthropic()` | AiProvider | 45 | — |
| `usesAnthropic()` | AiProvider | 53 | — |
| `anthropicMessagesUrl()` | AiProvider | 60 | — |
| `openAiChatUrl()` | AiProvider | 66 | — |
| `modelsUrl()` | AiProvider | 72 | — |
| `listModels()` | AiProvider | 82 | — |
| `parseModelIds()` | AiProvider | 113 | — |
| `completeJson()` | AiProvider | 121 | — |
| `probe()` | AiProvider | 135 | — |
| `completeText()` | AiProvider | 150 | — |
| `requestBody()` | AiProvider | 163 | — |
| `post()` | AiProvider | 184 | — |
| `extractAnthropicText()` | AiProvider | 221 | — |
| `extractOpenAiText()` | AiProvider | 233 | — |
| `parseJsonObjectLoose()` | AiProvider | 243 | — |

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

### `data/AppUpdateRepository.kt` — 678 خط · 25 فانکشن
> Sideload-friendly updater. Android still requires the system package installer confirmation;

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `installedSigningDiagnostic()` | AppUpdateRepository | 110 | — |
| `checkForUpdate()` | AppUpdateRepository | 144 | `#fn-updater` در نقشهٔ HTML |
| `downloadAvailable()` | AppUpdateRepository | 214 | — |
| `downloadAvailableLocked()` | AppUpdateRepository | 219 | — |
| `installDownloaded()` | AppUpdateRepository | 306 | — |
| `resumePendingInstall()` | AppUpdateRepository | 342 | — |
| `canInstallUnknownSources()` | AppUpdateRepository | 346 | — |
| `openInstallPermissionSettings()` | AppUpdateRepository | 349 | — |
| `chooseUpdate()` | AppUpdateRepository | 359 | `#fn-updater` در نقشهٔ HTML |
| `versionRank()` | AppUpdateRepository | 371 | — |
| `checkManifest()` | AppUpdateRepository | 377 | `#fn-updater` در نقشهٔ HTML |
| `checkLatestRelease()` | AppUpdateRepository | 417 | `#fn-updater` در نقشهٔ HTML |
| `requestJson()` | AppUpdateRepository | 486 | — |
| `downloadApk()` | AppUpdateRepository | 519 | `#fn-updater` در نقشهٔ HTML |
| `sha256()` | AppUpdateRepository | 597 | — |
| `signingCertificateDigests()` | AppUpdateRepository | 614 | — |
| `isTrustedReleaseAsset()` | AppUpdateRepository | 628 | — |
| `isTrustedMetadataAsset()` | AppUpdateRepository | 636 | — |
| `normalizeSha256()` | AppUpdateRepository | 644 | — |
| `safeFileToken()` | AppUpdateRepository | 653 | — |
| `semanticVersion()` | AppUpdateRepository | 658 | — |
| `compareVersions()` | AppUpdateRepository | 662 | — |
| `string()` | AppUpdateRepository | 671 | — |
| `long()` | AppUpdateRepository | 672 | — |
| `boolean()` | AppUpdateRepository | 676 | — |

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

### `data/CandleSourceHealth.kt` — 35 خط · 2 فانکشن
> Validates that the symbol's single dedicated source returned 1500 fresh, closed M5 candles

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `requiredSize()` | CandleSourceHealth | 12 | — |
| `ready()` | CandleSourceHealth | 19 | — |

### `data/CatalogPaperTrader.kt` — 72 خط · 3 فانکشن
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

### `data/CryptoFamilies.kt` — 17 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `label()` | CryptoFamilies | 5 | — |

### `data/CryptoIntervalChoice.kt` — 16 خط · 1 فانکشن
> become an entry base; M15/H1 remain independent confirmation, never entry choices.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `pick()` | CryptoIntervalChoice | 13 | — |

### `data/CryptoRadarBundle.kt` — 83 خط · 2 فانکشن
> 1-to-1 single-source crypto bundle:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `dedicatedSource()` | Snapshot | 38 | — |
| `fetch()` | CryptoRadarBundle | 48 | — |

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

### `data/CryptoVenuePolicy.kt` — 17 خط · 1 فانکشن
> Candles used to place levels and the executed entry/exit tick must belong to the

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `compatible()` | CryptoVenuePolicy | 8 | — |

### `data/DecisionLogStore.kt` — 72 خط · 2 فانکشن
> Passwords, model prompts, API keys and raw feed URLs are deliberately not stored here.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `append()` | DecisionLogStore | 45 | — |
| `toLayer()` | DecisionLogStore | 71 | — |

### `data/DukascopyHistoryClient.kt` — 289 خط · 12 فانکشن
> Keyless deep historical candles from Dukascopy's public datafeed.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchCandles()` | DukascopyHistoryClient | 46 | — |
| `fetchDailyMinuteCandles()` | DukascopyHistoryClient | 80 | — |
| `fetchMonthlyHourCandles()` | DukascopyHistoryClient | 112 | — |
| `fetchYearlyDayCandles()` | DukascopyHistoryClient | 144 | — |
| `fetchBytesOrNull()` | DukascopyHistoryClient | 173 | — |
| `two()` | Result | 193 | — |
| `zeroMonth()` | Result | 194 | — |
| `instrument()` | Result | 196 | — |
| `pointScale()` | Result | 230 | — |
| `parseCandlePayload()` | Result | 236 | — |
| `parseRawCandlePayload()` | Result | 249 | — |
| `validOhlc()` | Result | 281 | — |

### `data/ForexCalendarRepository.kt` — 111 خط · 7 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `online()` | ForexCalendarState | 33 | — |
| `highImpactUsdWindow()` | ForexCalendarState | 37 | — |
| `parseForexCalendar()` | — | 43 | — |
| `str()` | ForexCalendarState | 47 | — |
| `metric()` | ForexCalendarState | 58 | — |
| `refreshNow()` | ForexCalendarRepository | 84 | — |
| `refresh()` | ForexCalendarRepository | 86 | `#fn-market-refresh` در نقشهٔ HTML |

### `data/GeminiAiStudio.kt` — 53 خط · 7 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `isOfficialNativeEndpoint()` | GeminiAiStudio | 13 | — |
| `nativeBase()` | GeminiAiStudio | 19 | — |
| `modelId()` | GeminiAiStudio | 24 | — |
| `usesNative()` | GeminiAiStudio | 29 | — |
| `migratePreset()` | GeminiAiStudio | 34 | — |
| `isOfficialOpenAiEndpoint()` | GeminiAiStudio | 41 | — |
| `openAiModelId()` | GeminiAiStudio | 49 | — |

### `data/GeminiHttpDiagnostics.kt` — 44 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `failure()` | GeminiHttpDiagnostics | 24 | — |

### `data/GeminiNativeClient.kt` — 134 خط · 7 فانکشن
> follows redirects with x-goog-api-key. No key in URLs, diagnostics, prompts or bundled APK.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `transport()` | GeminiNativeClient | 20 | — |
| `request()` | GeminiNativeClient | 25 | — |
| `execute()` | GeminiNativeClient | 33 | — |
| `listModels()` | GeminiNativeClient | 46 | — |
| `body()` | GeminiNativeClient | 75 | — |
| `generate()` | GeminiNativeClient | 93 | — |
| `extractText()` | GeminiNativeClient | 104 | — |

### `data/HeadlineImpact.kt` — 62 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `explain()` | HeadlineImpact | 28 | — |
| `matches()` | Subject | 31 | — |

### `data/HistorySourceSelection.kt` — 67 خط · 4 فانکشن
> With two disagreeing publishers we cannot know which is right: fail closed. With

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `record()` | HistorySourceAudits | 16 | — |
| `choose()` | HistorySourceSelection | 28 | — |
| `price()` | Choice | 51 | — |
| `agree()` | Choice | 52 | — |

### `data/JournalPdfExporter.kt` — 95 خط · 2 فانکشن
> Renders a REAL PDF straight from the trades already saved in [com.aurum.edge.data.JournalStore] —

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `export()` | JournalPdfExporter | 26 | — |
| `ensureSpace()` | JournalPdfExporter | 42 | — |

### `data/JournalStore.kt` — 566 خط · 13 فانکشن
> Journal of *paper* trades executed at *real* prices.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `load()` | JournalStore | 56 | — |
| `persist()` | JournalStore | 75 | — |
| `open()` | JournalStore | 92 | `#fn-journal-open` در نقشهٔ HTML |
| `applyCryptoClosedBars()` | JournalStore | 259 | `#fn-settle` در نقشهٔ HTML |
| `attachAiReview()` | JournalStore | 281 | — |
| `attachHoldReview()` | JournalStore | 297 | — |
| `settle()` | JournalStore | 310 | `#fn-settle` در نقشهٔ HTML |
| `noteTickCoverageGap()` | JournalStore | 371 | — |
| `settleTick()` | JournalStore | 386 | `#fn-settle` در نقشهٔ HTML |
| `settleBinanceBatch()` | JournalStore | 462 | `#fn-settle` در نقشهٔ HTML |
| `close()` | JournalStore | 515 | — |
| `clear()` | JournalStore | 534 | — |
| `stats()` | JournalStore | 537 | — |

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

### `data/MarketRepository.kt` — 952 خط · 34 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `canRefreshSamePublisherFrames()` | — | 36 | — |
| `hasCurrentRestBar()` | — | 41 | — |
| `isCurrentIntervalTick()` | — | 47 | — |
| `marketClosed()` | MarketRepository | 101 | — |
| `attach()` | MarketRepository | 149 | — |
| `start()` | MarketRepository | 154 | — |
| `stop()` | MarketRepository | 193 | — |
| `restart()` | MarketRepository | 215 | — |
| `setInterval()` | MarketRepository | 223 | — |
| `refreshNow()` | MarketRepository | 229 | — |
| `loadCacheThenRefresh()` | MarketRepository | 234 | — |
| `refresh()` | MarketRepository | 255 | `#fn-market-refresh` در نقشهٔ HTML |
| `hasRecentStream()` | MarketRepository | 468 | — |
| `reportRestFailure()` | MarketRepository | 471 | — |
| `startPolling()` | MarketRepository | 481 | — |
| `startStream()` | MarketRepository | 492 | — |
| `stillCurrent()` | MarketRepository | 514 | — |
| `collectSpotFallback()` | MarketRepository | 517 | — |
| `startWatchdog()` | MarketRepository | 614 | `#fn-publish` در نقشهٔ HTML |
| `registerNetworkCallback()` | MarketRepository | 661 | — |
| `onAvailable()` | MarketRepository | 666 | — |
| `onLost()` | MarketRepository | 681 | — |
| `onTick()` | MarketRepository | 695 | `#fn-publish` در نقشهٔ HTML |
| `publishStreamUnavailable()` | MarketRepository | 776 | — |
| `publishClosed()` | MarketRepository | 788 | — |
| `publishDelayed()` | MarketRepository | 798 | — |
| `publishOffline()` | MarketRepository | 822 | — |
| `trimCachedBars()` | MarketRepository | 830 | — |
| `persistCache()` | MarketRepository | 836 | — |
| `evaluateAndPublish()` | MarketRepository | 844 | — |
| `publishCandles()` | MarketRepository | 848 | `#fn-publish` در نقشهٔ HTML |
| `currentPeriodStart()` | MarketRepository | 928 | — |
| `hasInternet()` | MarketRepository | 933 | — |
| `lastSyncAt()` | MarketRepository | 940 | — |

### `data/NewsClassifier.kt` — 54 خط · 1 فانکشن
> Rule-based keyword scan of an ALREADY-FETCHED real headline (title+excerpt) — never a model,

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `classify()` | NewsClassifier | 37 | — |

### `data/NewsRepository.kt` — 424 خط · 11 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `resetAndRefresh()` | NewsRepository | 120 | — |
| `refreshNow()` | NewsRepository | 126 | — |
| `refresh()` | NewsRepository | 128 | `#fn-market-refresh` در نقشهٔ HTML |
| `refreshServerMode()` | NewsRepository | 144 | `#fn-news-refresh` در نقشهٔ HTML |
| `sameAiConfig()` | NewsRepository | 168 | — |
| `refreshClientMode()` | NewsRepository | 172 | `#fn-news-refresh` در نقشهٔ HTML |
| `clientAiAnalyze()` | NewsRepository | 260 | — |
| `apiUrl()` | NewsRepository | 334 | — |
| `newsUrl()` | NewsRepository | 343 | — |
| `parseWebNews()` | — | 348 | — |
| `string()` | — | 424 | — |

### `data/NewsResearch.kt` — 76 خط · 3 فانکشن
> if the week's download is missing/stale. Differences never become a gold BUY/SELL.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `metric()` | NewsResearch | 14 | — |
| `gold()` | NewsResearch | 28 | — |
| `headline()` | NewsResearch | 57 | — |

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

### `data/NonCryptoRadarBundle.kt` — 65 خط · 1 فانکشن
> 1-to-1 single-source non-crypto bundle (Gold, Silver, Dow Jones, and 7 Ichimoku Forex pairs):

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetch()` | NonCryptoRadarBundle | 30 | — |

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

### `data/OpenTradeTickMonitor.kt` — 515 خط · 16 فانکشن
> WebSocket publications are the primary ordered path while the user-enabled foreground

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `setFeed()` | OpenTradeTickMonitor | 56 | — |
| `session()` | OpenTradeTickMonitor | 63 | — |
| `stillOpen()` | OpenTradeTickMonitor | 67 | — |
| `forget()` | OpenTradeTickMonitor | 72 | — |
| `setFeedRemoved()` | OpenTradeTickMonitor | 76 | — |
| `poll()` | OpenTradeTickMonitor | 84 | `#fn-tickmonitor` در نقشهٔ HTML |
| `binancePosition()` | OpenTradeTickMonitor | 90 | — |
| `syncCryptoClosedBars()` | OpenTradeTickMonitor | 98 | — |
| `quoteForManualClose()` | OpenTradeTickMonitor | 129 | — |
| `archivePosition()` | OpenTradeTickMonitor | 139 | — |
| `pollArchive()` | OpenTradeTickMonitor | 143 | `#fn-tickmonitor` در نقشهٔ HTML |
| `watchArchive()` | OpenTradeTickMonitor | 179 | — |
| `pollPage()` | OpenTradeTickMonitor | 204 | — |
| `pollBinance()` | OpenTradeTickMonitor | 258 | — |
| `watchBinance()` | OpenTradeTickMonitor | 311 | `#fn-tickmonitor` در نقشهٔ HTML |
| `watch()` | OpenTradeTickMonitor | 423 | `#fn-tickmonitor` در نقشهٔ HTML |

### `data/PairScanner.kt` — 643 خط · 9 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `attachAutoReview()` | PairScanner | 125 | — |
| `refreshNow()` | PairScanner | 152 | — |
| `sweepOnce()` | PairScanner | 157 | `#fn-scanner-sweep` در نقشهٔ HTML |
| `schedule()` | PairScanner | 162 | — |
| `publishFailure()` | PairScanner | 202 | — |
| `freshTrend()` | PairScanner | 213 | — |
| `rankOpportunities()` | PairScanner | 221 | `#fn-scanner-sweep` در نقشهٔ HTML |
| `sweepAll()` | PairScanner | 224 | `#fn-scanner-sweep` در نقشهٔ HTML |
| `update()` | PairScanner | 248 | — |

### `data/PaperAutoTrader.kt` — 202 خط · 4 فانکشن
> «روند کلی بازار» از پویشِ پیوستهٔ ۵۰+ نماد می‌آید (عرض بازار + جهت دلار + جوّ ریسک).

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `attachTrendSource()` | PaperAutoTrader | 38 | — |
| `stopped()` | PaperAutoTrader | 42 | — |
| `onMarketUpdate()` | PaperAutoTrader | 45 | `#fn-autotrader` در نقشهٔ HTML |
| `auditAiEntry()` | PaperAutoTrader | 193 | `#fn-autotrader` در نقشهٔ HTML |

### `data/PaperOpportunityStore.kt` — 79 خط · 5 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `load()` | PaperOpportunityStore | 29 | — |
| `save()` | PaperOpportunityStore | 47 | — |
| `record()` | PaperOpportunityStore | 63 | — |
| `linkTrade()` | PaperOpportunityStore | 70 | — |
| `clear()` | PaperOpportunityStore | 78 | — |

### `data/PublicCandleHistoryClient.kt` — 248 خط · 11 فانکشن
> Keyless, public historical candles for the no-key mode.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchCandles()` | PublicCandleHistoryClient | 50 | — |
| `detectGaps()` | Result | 101 | — |
| `parseYahooChart()` | PublicCandleHistoryClient | 108 | — |
| `yahooSymbol()` | PublicCandleHistoryClient | 173 | — |
| `encodeYahooPath()` | PublicCandleHistoryClient | 221 | — |
| `requestInterval()` | PublicCandleHistoryClient | 224 | — |
| `range()` | PublicCandleHistoryClient | 234 | — |
| `obj()` | PublicCandleHistoryClient | 244 | — |
| `array()` | PublicCandleHistoryClient | 245 | — |
| `text()` | PublicCandleHistoryClient | 246 | — |
| `num()` | PublicCandleHistoryClient | 247 | — |

### `data/PublicWebNewsRepository.kt` — 216 خط · 10 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `online()` | PublicFeedHealth | 70 | — |
| `parsePublicFeed()` | — | 87 | — |
| `localTag()` | — | 115 | — |
| `field()` | — | 116 | — |
| `cleanFeedText()` | — | 119 | — |
| `safePublisherLink()` | — | 122 | — |
| `parseFeedDate()` | — | 131 | — |
| `refreshNow()` | PublicWebNewsRepository | 149 | — |
| `refresh()` | PublicWebNewsRepository | 152 | `#fn-market-refresh` در نقشهٔ HTML |
| `fetchOne()` | PublicWebNewsRepository | 182 | — |

### `data/PublisherBudget.kt` — 27 خط · 2 فانکشن
> All symbol jobs start immediately. A publisher can still throttle its own requests;

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `tryRead()` | PublisherBudget | 16 | — |
| `read()` | PublisherBudget | 22 | `#fn-alignment` در نقشهٔ HTML |

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

### `data/ResearchAlerts.kt` — 24 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `forex()` | ResearchAlerts | 7 | — |

### `data/ScanFreshness.kt` — 9 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `current()` | ScanFreshness | 7 | — |

### `data/ScanRanking.kt` — 33 خط · 2 فانکشن
> CLOSED candles without authorization for an alert or paper entry (which may be caused

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `rank()` | ScanRanking | 12 | — |
| `byMarket()` | ScanRanking | 24 | — |

### `data/SettingsStore.kt` — 348 خط · 16 فانکشن
> Local settings. The Twelve Data key is a read-only market-data credential

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `migrateAutoDownload()` | SettingsStore | 37 | — |
| `read()` | SettingsStore | 48 | `#fn-alignment` در نقشهٔ HTML |
| `saveMarketCredentials()` | SettingsStore | 106 | — |
| `saveChartSymbol()` | SettingsStore | 127 | — |
| `tickArchiveConfig()` | SettingsStore | 142 | — |
| `tickArchiveUrl()` | SettingsStore | 152 | — |
| `hasTickArchive()` | SettingsStore | 153 | — |
| `saveTickArchive()` | SettingsStore | 156 | — |
| `saveNewsBaseUrl()` | SettingsStore | 171 | — |
| `saveNewsAiConfig()` | SettingsStore | 188 | — |
| `clearNewsAiConfig()` | SettingsStore | 213 | — |
| `dayOpeningEquity()` | SettingsStore | 231 | — |
| `adjustBalance()` | SettingsStore | 245 | — |
| `update()` | SettingsStore | 255 | — |
| `setLastSync()` | SettingsStore | 303 | — |
| `lastSync()` | SettingsStore | 307 | — |

### `data/SigningContinuity.kt` — 5 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `sameCurrentSigners()` | — | 4 | — |

### `data/SourceCatalog.kt` — 241 خط · 1 فانکشن
> 1-to-1 dedicated public watch sources:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `find()` | SourceCatalog | 239 | — |

### `data/SourceFetcher.kt` — 206 خط · 7 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetchAll()` | SourceFetcher | 36 | — |
| `fetchOne()` | SourceFetcher | 80 | — |
| `parseHtmlCarQuote()` | SourceFetcher | 120 | — |
| `parseJsonQuote()` | SourceFetcher | 135 | — |
| `fetchJson()` | SourceFetcher | 170 | — |
| `fetchText()` | SourceFetcher | 172 | — |
| `encode()` | SourceFetcher | 204 | — |

### `data/SourceIntegrity.kt` — 49 خط · 1 فانکشن
> Process-local latch, independent of UI, data receipt status and the active network route.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `resetForTests()` | SourceIntegrity | 44 | — |

### `data/SourceModels.kt` — 102 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse()` | Num | 91 | — |

### `data/SourceRouting.kt` — 100 خط · 6 فانکشن
> Strict 1-to-1 source routing:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `dedicatedFor()` | SourceRouting | 61 | — |
| `cryptoVenueFor()` | SourceRouting | 64 | — |
| `historySources()` | SourceRouting | 67 | — |
| `fromProvider()` | SourceRouting | 72 | — |
| `matchesSource()` | SourceRouting | 85 | — |
| `historyPlan()` | SourceRouting | 95 | — |

### `data/SpotFallbackClient.kt` — 275 خط · 10 فانکشن
> Automatic real spot-price source: Swissquote's public BBO for FX, gold-api.com for gold,

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `get()` | SpotFallbackClient | 60 | — |
| `fetchSwissquote()` | SpotFallbackClient | 73 | — |
| `fetchSwissquoteOnly()` | SpotFallbackClient | 111 | — |
| `fetchGoldApi()` | SpotFallbackClient | 116 | — |
| `cryptoTradeResponse()` | SpotFallbackClient | 144 | — |
| `fetchCryptoTrades()` | SpotFallbackClient | 172 | — |
| `compareGold()` | SpotFallbackClient | 185 | — |
| `fetchQuote()` | SpotFallbackClient | 218 | — |
| `fetchYahooIndexQuote()` | SpotFallbackClient | 239 | — |
| `streamQuotes()` | SpotFallbackClient | 264 | — |

### `data/SpotPriceSelection.kt` — 25 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `gold()` | SpotPriceSelection | 8 | — |

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

### `data/TimeframeFeed.kt` — 42 خط · 1 فانکشن
> Only M5 (1500 closed candles) is fetched over the network.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fetch()` | TimeframeFeed | 19 | — |

### `data/TraderAdvisor.kt` — 723 خط · 26 فانکشن
> The model's own verdict on whether this setup is worth taking at all. It is asked for

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onAiConfigChanged()` | TraderAdvisor | 152 | — |
| `ensureConnection()` | TraderAdvisor | 176 | `#fn-ai-plan` در نقشهٔ HTML |
| `refreshNow()` | TraderAdvisor | 214 | — |
| `refresh()` | TraderAdvisor | 216 | `#fn-market-refresh` در نقشهٔ HTML |
| `listModels()` | TraderAdvisor | 246 | — |
| `probe()` | TraderAdvisor | 253 | — |
| `planTradeWithAi()` | TraderAdvisor | 260 | `#fn-ai-plan` در نقشهٔ HTML |
| `num()` | TraderAdvisor | 295 | — |
| `reviewPaperEntry()` | TraderAdvisor | 338 | — |
| `reviewOpenPositions()` | TraderAdvisor | 394 | `#fn-ai-plan` در نقشهٔ HTML |
| `consumeHoldAlerts()` | TraderAdvisor | 482 | — |
| `reviewOpenPosition()` | TraderAdvisor | 494 | — |
| `consumeBiasFlip()` | TraderAdvisor | 552 | — |
| `analyze()` | TraderAdvisor | 559 | `#fn-mtf` در نقشهٔ HTML |
| `parseWorthAnswer()` | TraderAdvisor | 621 | — |
| `parseHoldReview()` | TraderAdvisor | 639 | — |
| `str()` | WorthAnswer | 641 | — |
| `list()` | WorthAnswer | 642 | — |
| `parseTradeReview()` | TraderAdvisor | 663 | — |
| `str()` | WorthAnswer | 664 | — |
| `list()` | WorthAnswer | 665 | — |
| `parseOpinion()` | TraderAdvisor | 684 | — |
| `str()` | WorthAnswer | 685 | — |
| `list()` | WorthAnswer | 686 | — |
| `toSignal()` | — | 711 | — |
| `averageOrNull()` | — | 722 | — |

### `data/TradingViewSymbols.kt` — 40 خط · 2 فانکشن
> Resolution of the 15 supported app symbols (2 Metals, 1 Dow Jones Index,

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `find()` | TradingViewSymbols | 30 | — |
| `isChartable()` | TradingViewSymbols | 39 | — |

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

### `data/WatchRepository.kt` — 209 خط · 10 فانکشن
> observation and original timestamp, never a made-up fresh quote. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `mergeWatchSource()` | — | 31 | — |
| `loadCached()` | WatchRepository | 63 | — |
| `startAutoRefresh()` | WatchRepository | 77 | — |
| `stopAutoRefresh()` | WatchRepository | 87 | — |
| `refreshNow()` | WatchRepository | 89 | — |
| `ensureLoaded()` | WatchRepository | 93 | — |
| `refresh()` | WatchRepository | 113 | `#fn-market-refresh` در نقشهٔ HTML |
| `page()` | WatchRepository | 191 | `#fn-archive` در نقشهٔ HTML |
| `count()` | WatchRepository | 197 | — |
| `clearHistory()` | WatchRepository | 202 | — |

### `data/WatchSettingsStore.kt` — 72 خط · 6 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `read()` | WatchSettingsStore | 16 | `#fn-alignment` در نقشهٔ HTML |
| `selectSource()` | WatchSettingsStore | 36 | — |
| `setPreferred()` | WatchSettingsStore | 49 | — |
| `apiKeyFor()` | WatchSettingsStore | 57 | — |
| `keyOverride()` | WatchSettingsStore | 60 | — |
| `setKeyOverride()` | WatchSettingsStore | 65 | — |

### `engine/Als5GoldEngine.kt` — 1195 خط · 14 فانکشن
> ALS-5 — Adaptive Liquidity & Structure Trading System for Gold (XAU/USD) on M5.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `confirmedSwings()` | Als5GoldEngine | 177 | — |
| `classifyRegime()` | Als5GoldEngine | 216 | `#fn-als5` در نقشهٔ HTML |
| `evaluateEngineA()` | Als5GoldEngine | 367 | `#fn-als5` در نقشهٔ HTML |
| `evaluateEngineB()` | Als5GoldEngine | 567 | `#fn-als5` در نقشهٔ HTML |
| `evaluateEngineC()` | Als5GoldEngine | 731 | `#fn-als5` در نقشهٔ HTML |
| `computeTarget()` | Als5GoldEngine | 848 | — |
| `netRewardRisk()` | Als5GoldEngine | 873 | — |
| `calculatePositionSize()` | Als5GoldEngine | 887 | `#fn-als5` در نقشهٔ HTML |
| `sessionAllowed()` | Als5GoldEngine | 906 | `#fn-als5` در نقشهٔ HTML |
| `inNewsBlackout()` | Als5GoldEngine | 920 | — |
| `evaluate()` | Als5GoldEngine | 928 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | CandidateSetup | 945 | — |
| `buildConfluenceItems()` | Als5GoldEngine | 1142 | — |
| `item()` | CandidateSetup | 1147 | — |

### `engine/BarFillRules.kt` — 57 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `nextOpen()` | BarFillRules | 14 | — |
| `protectiveExit()` | BarFillRules | 29 | — |
| `nextOpenExit()` | BarFillRules | 45 | — |

### `engine/CryptoCompressionEngine.kt` — 267 خط · 4 فانکشن
> confirmations, never entry candles. This does not backfill missing trades, volumes or quotes.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `blocked()` | CryptoCompressionEngine | 27 | — |
| `evaluate()` | CryptoCompressionEngine | 33 | `#fn-signalengine` در نقشهٔ HTML |
| `valid()` | CryptoCompressionEngine | 252 | — |
| `compressionType()` | CryptoCompressionEngine | 257 | — |

### `engine/CryptoCompressionExit.kt` — 76 خط · 1 فانکشن
> the journal executes an intent only on a later, ordered same-venue trade print.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `assess()` | CryptoCompressionExit | 17 | `#fn-entrysetup` در نقشهٔ HTML |

### `engine/DirectionalIndex.kt` — 35 خط · 1 فانکشن
> No zero-volume fallback or synthetic history is used for entry.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `at()` | DirectionalIndex | 12 | — |

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

### `engine/Indicators.kt` — 310 خط · 15 فانکشن
> Pure indicator math over *real* closed candles.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `donchianMid()` | Indicators | 16 | `#fn-ichimoku` در نقشهٔ HTML |
| `ema()` | Indicators | 32 | `#fn-ichimoku` در نقشهٔ HTML |
| `rsi()` | Indicators | 46 | `#fn-ichimoku` در نقشهٔ HTML |
| `value()` | Indicators | 57 | — |
| `atr()` | Indicators | 73 | `#fn-ichimoku` در نقشهٔ HTML |
| `macdHistogram()` | Indicators | 96 | `#fn-ichimoku` در نقشهٔ HTML |
| `bollinger()` | Indicators | 118 | — |
| `adx()` | Indicators | 134 | `#fn-ichimoku` در نقشهٔ HTML |
| `sessionVwap()` | Indicators | 178 | — |
| `relativeVolume()` | Indicators | 206 | — |
| `cloudAt()` | Ichimoku | 240 | — |
| `spanAAt()` | Ichimoku | 249 | — |
| `spanBAt()` | Ichimoku | 252 | — |
| `higherTimeframeBiasAt()` | Ichimoku | 255 | — |
| `compute()` | Ichimoku | 277 | `#fn-ichimoku` در نقشهٔ HTML |

### `engine/MtfAnalyzer.kt` — 288 خط · 7 فانکشن
> Multi-timeframe confluence, computed **on the phone** from the real candles that were

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `framesFor()` | MtfAnalyzer | 56 | — |
| `weightFor()` | MtfAnalyzer | 67 | — |
| `convertFromM5()` | MtfAnalyzer | 82 | `#fn-mtf` در نقشهٔ HTML |
| `resample()` | MtfAnalyzer | 109 | `#fn-mtf` در نقشهٔ HTML |
| `frameOf()` | MtfAnalyzer | 140 | — |
| `analyze()` | MtfAnalyzer | 222 | `#fn-mtf` در نقشهٔ HTML |
| `direction()` | MtfAnalyzer | 283 | — |

### `engine/NewsConfluence.kt` — 156 خط · 5 فانکشن
> News adds a green/amber/red journal evidence row, not a positive entry prerequisite.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `alignment()` | NewsConfluence | 30 | `#fn-newsconfluence` در نقشهٔ HTML |
| `unknown()` | Alignment | 32 | — |
| `trustedPublisher()` | NewsConfluence | 82 | — |
| `record()` | NewsConfluence | 95 | — |
| `apply()` | NewsConfluence | 111 | `#fn-newsconfluence` در نقشهٔ HTML |

### `engine/PerformanceMetrics.kt` — 99 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fromPaper()` | PerformanceMetrics | 39 | — |
| `from()` | PerformanceMetrics | 45 | — |

### `engine/ResearchEvidence.kt` — 66 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `hasRecordedSignalEvidence()` | ResearchEvidence | 26 | — |
| `hasRecordedNineWay()` | ResearchEvidence | 52 | — |
| `paperCostWhatIf()` | ResearchEvidence | 55 | — |

### `engine/SignalEngine.kt` — 114 خط · 6 فانکشن
> Single V1 closed-bar four-layer paper signal facade.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ichimokuSetting()` | SignalEngine | 43 | — |
| `barsValid()` | SignalEngine | 46 | — |
| `minBars()` | SignalEngine | 53 | — |
| `series()` | SignalEngine | 58 | — |
| `evaluateLive()` | SignalEngine | 79 | `#fn-signalengine` در نقشهٔ HTML |
| `evaluate()` | SignalEngine | 104 | `#fn-signalengine` در نقشهٔ HTML |

### `engine/V1Scoring.kt` — 222 خط · 8 فانکشن
> Four independent layers, each worth 25 points. Only M5/M15/H1 vote on alignment.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `minBarsFor()` | V1Scoring | 25 | — |
| `evaluate()` | V1Scoring | 32 | `#fn-signalengine` در نقشهٔ HTML |
| `blocked()` | V1Scoring | 40 | — |
| `score()` | V1Scoring | 112 | `#fn-v1score-side` در نقشهٔ HTML |
| `item()` | V1Scoring | 132 | — |
| `directionalAlignment()` | V1Scoring | 184 | `#fn-v1score-side` در نقشهٔ HTML |
| `chooseSide()` | V1Scoring | 196 | `#fn-v1score-side` در نقشهٔ HTML |
| `frameBias()` | V1Scoring | 202 | `#fn-v1score-side` در نقشهٔ HTML |

### `notify/AlertSoundPlayer.kt` — 105 خط · 5 فانکشن
> One brief notification-usage clip from a persistable SAF document. Playback is done by the

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `select()` | AlertSoundPlayer | 32 | — |
| `canOpen()` | AlertSoundPlayer | 54 | — |
| `play()` | AlertSoundPlayer | 61 | — |
| `stop()` | AlertSoundPlayer | 95 | — |
| `releaseActive()` | AlertSoundPlayer | 97 | — |

### `notify/Notifier.kt` — 287 خط · 12 فانکشن
> Android system notification delivery with exact channel separation:

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ensureChannels()` | Notifier | 36 | `#fn-notifier` در نقشهٔ HTML |
| `contentIntent()` | Notifier | 82 | — |
| `buildForegroundServiceNotification()` | Notifier | 94 | — |
| `buildMonitorNotification()` | Notifier | 104 | — |
| `notifyResearch()` | Notifier | 108 | — |
| `canNotifyVerified()` | Notifier | 129 | — |
| `notifyTest()` | Notifier | 139 | — |
| `notifyVerifiedOpportunity()` | Notifier | 145 | `#fn-notifier` در نقشهٔ HTML |
| `notifyTradeOpened()` | Notifier | 172 | — |
| `notifyClosedTrade()` | Notifier | 217 | `#fn-notifier` در نقشهٔ HTML |
| `notifyRecordedAutoEntry()` | Notifier | 251 | `#fn-notifier` در نقشهٔ HTML |
| `postVerified()` | Notifier | 262 | — |

### `service/SignalMonitorService.kt` — 451 خط · 8 فانکشن
> Keeps the real Forex feed alive while the app is in the background and alerts only on

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `onBind()` | SignalMonitorService | 61 | — |
| `notificationsPermitted()` | SignalMonitorService | 63 | — |
| `onStartCommand()` | SignalMonitorService | 67 | `#service` در نقشهٔ HTML |
| `postResearchAlert()` | SignalMonitorService | 400 | — |
| `onTimeout()` | SignalMonitorService | 418 | — |
| `onDestroy()` | SignalMonitorService | 422 | — |
| `start()` | SignalMonitorService | 441 | — |
| `stop()` | SignalMonitorService | 446 | — |

### `ui/components/Common.kt` — 405 خط · 14 فانکشن
> Purely orientational for a human reader — never a trading signal or the server's AI news gate. */

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `NewsClassificationRow()` | — | 37 | — |
| `formatPrice()` | — | 64 | — |
| `formatPriceFor()` | — | 83 | — |
| `formatSigned()` | — | 87 | — |
| `formatTime()` | — | 93 | — |
| `formatDateTime()` | — | 98 | — |
| `relativeTime()` | — | 103 | — |
| `SectionCard()` | — | 115 | — |
| `StatTile()` | — | 147 | — |
| `Pill()` | — | 167 | — |
| `FeedBanner()` | — | 179 | — |
| `ConfluenceRow()` | — | 223 | — |
| `EmptyState()` | — | 261 | — |
| `AssetClass4SlotsBanner()` | — | 286 | — |

### `ui/components/SignalCard.kt` — 148 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `SignalSummaryCard()` | — | 27 | — |

### `ui/shared/PerformancePanel.kt` — 66 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `PerformancePanel()` | — | 21 | — |
| `number()` | — | 22 | — |

### `ui/shell/AppHeader.kt` — 72 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AppHeader()` | — | 29 | — |

### `ui/shell/AurumRoot.kt` — 112 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AurumRoot()` | — | 30 | — |
| `open()` | — | 72 | `#fn-journal-open` در نقشهٔ HTML |
| `openChartFor()` | — | 75 | — |

### `ui/shell/AurumViewModel.kt` — 1229 خط · 70 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `resetAiDraftDiagnostics()` | AurumViewModel | 217 | — |
| `startApp()` | AurumViewModel | 283 | `#fn-viewmodel` در نقشهٔ HTML |
| `resumeVisibleForexFeed()` | AurumViewModel | 318 | — |
| `pauseInvisibleForexFeed()` | AurumViewModel | 324 | — |
| `ensureVisibleOnlineLoop()` | AurumViewModel | 337 | `#fn-viewmodel` در نقشهٔ HTML |
| `ensureLiveTickerLoop()` | AurumViewModel | 381 | `#fn-viewmodel` در نقشهٔ HTML |
| `refreshNow()` | AurumViewModel | 483 | — |
| `checkForAppUpdate()` | AurumViewModel | 485 | — |
| `downloadAppUpdate()` | AurumViewModel | 496 | — |
| `setAutoDownloadUpdates()` | AurumViewModel | 503 | — |
| `installDownloadedUpdate()` | AurumViewModel | 511 | — |
| `resumeUpdateInstall()` | AurumViewModel | 513 | — |
| `openUpdateInstallPermission()` | AurumViewModel | 515 | — |
| `refreshWatch()` | AurumViewModel | 517 | — |
| `refreshTraderOpinion()` | AurumViewModel | 520 | — |
| `reviewOpenPositionsNow()` | AurumViewModel | 526 | — |
| `scanPairs()` | AurumViewModel | 538 | — |
| `selectAndTradeBestPick()` | AurumViewModel | 550 | — |
| `refreshNews()` | AurumViewModel | 556 | — |
| `refreshPublicWebNews()` | AurumViewModel | 558 | — |
| `refreshForexCalendar()` | AurumViewModel | 560 | — |
| `saveApiKey()` | AurumViewModel | 562 | — |
| `selectChartSymbol()` | AurumViewModel | 571 | — |
| `saveMarketCredentials()` | AurumViewModel | 598 | — |
| `setCategoryStrategy()` | AurumViewModel | 627 | — |
| `saveRiskPercent()` | AurumViewModel | 637 | — |
| `saveBalance()` | AurumViewModel | 639 | — |
| `saveMinConfidence()` | AurumViewModel | 641 | — |
| `saveSpread()` | AurumViewModel | 644 | — |
| `saveCommission()` | AurumViewModel | 646 | — |
| `setNotifyOnSignal()` | AurumViewModel | 648 | — |
| `selectAlertSound()` | AurumViewModel | 650 | — |
| `resetAlertSound()` | AurumViewModel | 662 | — |
| `reportNotificationTest()` | AurumViewModel | 668 | — |
| `testAlertSound()` | AurumViewModel | 673 | — |
| `setMonitorFlag()` | AurumViewModel | 686 | — |
| `setAutoPaperTrading()` | AurumViewModel | 691 | — |
| `freshPaperQuote()` | AurumViewModel | 701 | — |
| `entryBlocker()` | AurumViewModel | 717 | — |
| `previewManualTicket()` | AurumViewModel | 756 | — |
| `openPaperTrade()` | AurumViewModel | 766 | — |
| `openManualPaperTrade()` | AurumViewModel | 768 | — |
| `submitPaper()` | AurumViewModel | 780 | `#fn-viewmodel` در نقشهٔ HTML |
| `verified()` | AurumViewModel | 786 | — |
| `closePaperTrade()` | AurumViewModel | 878 | — |
| `clearJournal()` | AurumViewModel | 906 | — |
| `clearOpportunityHistory()` | AurumViewModel | 918 | — |
| `clearCache()` | AurumViewModel | 929 | — |
| `selectWatchSource()` | AurumViewModel | 937 | — |
| `setWatchPreferred()` | AurumViewModel | 942 | — |
| `watchKeyOverride()` | AurumViewModel | 944 | — |
| `setWatchKeyOverride()` | AurumViewModel | 946 | — |
| `showWatchHistory()` | AurumViewModel | 959 | — |
| `moreWatchHistory()` | AurumViewModel | 974 | — |
| `clearWatchHistory()` | AurumViewModel | 986 | — |
| `showTradeChart()` | AurumViewModel | 999 | — |
| `refreshVisibleTradeChart()` | AurumViewModel | 1027 | — |
| `downloadTradeChart()` | AurumViewModel | 1050 | — |
| `setInterval()` | AurumViewModel | 1079 | — |
| `saveNewsAiConfig()` | AurumViewModel | 1081 | — |
| `clearNewsAiConfig()` | AurumViewModel | 1103 | — |
| `loadNewsAiModels()` | AurumViewModel | 1124 | — |
| `testNewsAiConnection()` | AurumViewModel | 1147 | — |
| `tickArchiveUrl()` | AurumViewModel | 1173 | — |
| `saveTickArchive()` | AurumViewModel | 1175 | — |
| `saveNewsBaseUrl()` | AurumViewModel | 1186 | — |
| `setPauseOnNews()` | AurumViewModel | 1204 | — |
| `exportJournalPdf()` | AurumViewModel | 1210 | — |
| `consumeToast()` | AurumViewModel | 1221 | — |
| `create()` | AurumViewModelFactory | 1228 | — |

### `ui/shell/TabNavigation.kt` — 91 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AurumBottomBar()` | — | 52 | — |
| `MoreTabsSheet()` | — | 75 | — |

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

### `ui/tabs/journal/JournalScreen.kt` — 1073 خط · 18 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `JournalScreen()` | — | 70 | — |
| `PaperEvidencePanel()` | — | 369 | — |
| `EntryConditionsLine()` | — | 403 | — |
| `conditionTone()` | — | 414 | — |
| `conditionLabel()` | — | 420 | — |
| `TradeRow()` | — | 428 | — |
| `TradeChartDisclosure()` | — | 516 | — |
| `AiReviewDisclosure()` | — | 611 | — |
| `verdictFa()` | — | 643 | — |
| `HoldReviewStatus()` | — | 655 | — |
| `HoldReviewDisclosure()` | — | 689 | — |
| `verdictFa()` | — | 725 | — |
| `IctDisclosure()` | — | 733 | — |
| `ConditionDisclosure()` | — | 765 | — |
| `PnlIncomeCalendarSection()` | — | 789 | — |
| `trendDirectionFa()` | — | 1054 | — |
| `trendAlignmentFa()` | — | 1061 | — |
| `riskToneFa()` | — | 1068 | — |

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

### `ui/tabs/news/PersianNewsScreen.kt` — 219 خط · 6 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `PersianNewsScreen()` | — | 41 | — |
| `CalendarImpactCard()` | — | 110 | — |
| `NewsImpactCard()` | — | 139 | — |
| `importanceLabel()` | — | 192 | — |
| `calendarTimeLabel()` | — | 198 | — |
| `calendarExplanation()` | — | 208 | — |

### `ui/tabs/settings/AiUiState.kt` — 10 خط · 0 فانکشن

### `ui/tabs/settings/ApiMenuScreen.kt` — 74 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `ApiMenuScreen()` | — | 37 | — |

### `ui/tabs/settings/SettingsScreen.kt` — 457 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `SettingsScreen()` | — | 55 | — |
| `startMonitorIfAllowed()` | — | 97 | — |

### `ui/tabs/signal/Als5OpportunityMonitorCard.kt` — 100 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `Als5OpportunityMonitorCard()` | — | 22 | — |

### `ui/tabs/signal/PaperTicketSection.kt` — 117 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `PaperTicketSection()` | — | 35 | — |

### `ui/tabs/signal/SignalScreen.kt` — 933 خط · 12 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `SignalScreen()` | — | 75 | — |
| `EvidenceTimingCard()` | — | 144 | — |
| `MarketPlaybookCard()` | — | 194 | — |
| `MarketTrendCard()` | — | 244 | — |
| `AiProofCard()` | — | 364 | — |
| `WhyNoTradeCard()` | — | 453 | — |
| `fmt2()` | — | 499 | — |
| `PerMarketRecommendations()` | — | 506 | — |
| `BestOpportunityDetailedCard()` | — | 590 | — |
| `TopCandidatesDetailedSection()` | — | 749 | — |
| `CandidateDetailedItemCard()` | — | 775 | — |
| `PairRadarSummaryCard()` | — | 885 | — |

### `ui/tabs/update/UpdateScreen.kt` — 190 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `UpdateScreen()` | — | 32 | — |
| `UpdateInfoCard()` | — | 139 | — |
| `formatUpdateBytes()` | — | 186 | — |

### `ui/tabs/watch/MarketWatchScreen.kt` — 133 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `MarketWatchScreen()` | — | 43 | — |
| `QuoteMainLine()` | — | 126 | — |

### `ui/tabs/watch/WatchUiState.kt` — 12 خط · 0 فانکشن

### `ui/theme/Theme.kt` — 67 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `AurumTheme()` | — | 63 | — |

## اپ اندروید — تست‌ها — `android/app/src/test/java/com/aurum/edge` (100 فایل · 95 فانکشن)

### `MarketPlaybookTest.kt` — 286 خط · 4 فانکشن
> «کدام روش برای کدام بازار» — pinned with FIXED wall clocks and synthetic CLOSED candles, so a

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `candleAt()` | MarketPlaybookTest | 261 | — |
| `trendSeries()` | MarketPlaybookTest | 265 | — |
| `rangeSeries()` | MarketPlaybookTest | 273 | — |
| `withTail()` | MarketPlaybookTest | 281 | — |

### `MarketTrendTest.kt` — 331 خط · 2 فانکشن
> «روند کلی بازار» — pinned with synthetic CLOSED candles and hand-built breadth samples.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `series()` | MarketTrendTest | 35 | — |
| `trend()` | MarketTrendTest | 59 | — |

### `data/AiConnectionDiagnosticsTest.kt` — 40 خط · 0 فانکشن

### `data/AiEntryProofTest.kt` — 54 خط · 1 فانکشن
> {"worth":"false","worth_reason":"plausible but not boolean"}""").jsonObject).worth)

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `plan()` | AiEntryProofTest | 13 | — |

### `data/AiNewsPresentationTest.kt` — 33 خط · 0 فانکشن

### `data/AiTraderTest.kt` — 253 خط · 3 فانکشن
> Truth tests for the on-device AI layer: provider detection, response extraction for BOTH

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `opinionJson()` | AiTraderTest | 121 | — |
| `tradeReviewJson()` | AiTraderTest | 172 | — |
| `holdReviewJson()` | AiTraderTest | 205 | — |

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

### `data/BinancePositionReplayTest.kt` — 145 خط · 3 فانکشن
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

### `data/CryptoRadarBundleTest.kt` — 59 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `closedM5Series()` | CryptoRadarBundleTest | 13 | — |

### `data/CryptoUniverse200Test.kt` — 45 خط · 0 فانکشن

### `data/CryptoVenuePolicyTest.kt` — 18 خط · 0 فانکشن

### `data/GeminiAiStudioTest.kt` — 126 خط · 0 فانکشن
> {"data":[{"id":"models/gemini-3.8-flash"},{"id":"gemini-3.8-flash"}]}

### `data/GeminiHttpDiagnosticsTest.kt` — 26 خط · 0 فانکشن
> {"error":{"message":"synthetic-secret-do-not-echo","details":[{"reason":"$reason","metadata":{"key":"synthetic-secret-do-not-echo"

### `data/GeminiNativeClientTest.kt` — 150 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `root()` | GeminiNativeClientTest | 15 | — |
| `reply()` | GeminiNativeClientTest | 16 | — |
| `client()` | GeminiNativeClientTest | 24 | — |

### `data/GlmRelayTest.kt` — 130 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `client()` | GlmRelayTest | 21 | — |

### `data/GlmSettingsStoreTest.kt` — 35 خط · 0 فانکشن

### `data/HeadlineImpactTest.kt` — 30 خط · 0 فانکشن

### `data/HistorySourceSelectionTest.kt` — 60 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `candidate()` | HistorySourceSelectionTest | 11 | — |

### `data/MarketEntryReasonTest.kt` — 28 خط · 0 فانکشن

### `data/MexcRadarTest.kt` — 28 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `accepted()` | MexcRadarTest | 13 | — |

### `data/NobitexHistoryClientTest.kt` — 71 خط · 1 فانکشن
> Nobitex is the crypto source for networks where Binance answers 451.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `udf()` | NobitexHistoryClientTest | 18 | — |

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

### `data/NonCryptoRadarBundleTest.kt` — 56 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `closedM5Series()` | NonCryptoRadarBundleTest | 13 | — |

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

### `data/SourceIntegrityTest.kt` — 86 خط · 0 فانکشن

### `data/SourceRoutingTest.kt` — 58 خط · 0 فانکشن

### `data/SymbolSearchTest.kt` — 104 خط · 1 فانکشن
> Searching a 400-row exchange by a fragment of a name.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `pair()` | SymbolSearchTest | 16 | — |

### `data/SymbolWorkersTest.kt` — 34 خط · 0 فانکشن

### `data/ThreeMinuteCandlesTest.kt` — 70 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `minute()` | ThreeMinuteCandlesTest | 15 | — |
| `read()` | ThreeMinuteCandlesTest | 55 | `#fn-alignment` در نقشهٔ HTML |

### `data/TradingViewFeedBoundaryTest.kt` — 58 خط · 0 فانکشن

### `data/TradingViewSymbolsTest.kt` — 47 خط · 0 فانکشن

### `data/UpdaterChannelOutcomeTest.kt` — 172 خط · 3 فانکشن
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

### `engine/AlertDiagnosticsTest.kt` — 88 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `report()` | AlertDiagnosticsTest | 43 | — |
| `report()` | AlertDiagnosticsTest | 60 | — |

### `engine/Als5GoldEngineTest.kt` — 146 خط · 0 فانکشن

### `engine/Als5TestFixtures.kt` — 56 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `asConfirmedAls5TestSignal()` | — | 11 | — |

### `engine/CandleCacheContractTest.kt` — 60 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `getFilesDir()` | CandleCacheContractTest | 41 | — |

### `engine/CatalogAutoPolicyTest.kt` — 73 خط · 0 فانکشن

### `engine/CryptoCompressionEngineTest.kt` — 152 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | CryptoCompressionEngineTest | 26 | — |
| `proof()` | CryptoCompressionEngineTest | 94 | — |

### `engine/DukascopyHistoryClientTest.kt` — 43 خط · 0 فانکشن

### `engine/EngineTruthTest.kt` — 79 خط · 3 فانکشن
> JVM tests for the parts of the app that must never invent data.

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `fixture()` | EngineTruthTest | 21 | — |
| `aggregationPreservesRealOhlcAndDropsPartialBuckets()` | EngineTruthTest | 46 | — |
| `mtfNeedsRealHistoryBeforeItSaysAnything()` | EngineTruthTest | 69 | — |

### `engine/FeedNavigationTest.kt` — 67 خط · 0 فانکشن

### `engine/ForexCalendarTest.kt` — 51 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `parse()` | ForexCalendarTest | 21 | — |

### `engine/ForexNewsDecisionTest.kt` — 47 خط · 0 فانکشن

### `engine/HistoryPolicyTest.kt` — 25 خط · 0 فانکشن

### `engine/IchimokuAlignmentTest.kt` — 54 خط · 0 فانکشن

### `engine/IchimokuEntrySetupTest.kt` — 61 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `setup()` | IchimokuEntrySetupTest | 26 | — |

### `engine/IctEntryRulesTest.kt` — 118 خط · 0 فانکشن

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

### `engine/NewsResearchTest.kt` — 75 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `calendar()` | NewsResearchTest | 24 | — |

### `engine/NineWayAutoPaperTest.kt` — 234 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `market()` | NineWayAutoPaperTest | 50 | — |

### `engine/OilEntrySafetyTest.kt` — 26 خط · 0 فانکشن

### `engine/PaperJournalPersistenceTest.kt` — 356 خط · 3 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `journalFile()` | PaperJournalPersistenceTest | 42 | — |
| `mtf()` | PaperJournalPersistenceTest | 49 | — |
| `ictFor()` | PaperJournalPersistenceTest | 51 | — |

### `engine/PaperOpportunityTest.kt` — 133 خط · 0 فانکشن

### `engine/PaperOrderRulesTest.kt` — 129 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `invalid()` | PaperOrderRulesTest | 114 | — |

### `engine/PublicCandleHistoryClientTest.kt` — 75 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `yahooJson()` | PublicCandleHistoryClientTest | 17 | — |
| `nums()` | PublicCandleHistoryClientTest | 19 | — |

### `engine/PublicWebNewsTest.kt` — 127 خط · 1 فانکشن
> .trimIndent()

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `rss()` | PublicWebNewsTest | 38 | — |

### `engine/RecordedPaperEntryAlertTest.kt` — 105 خط · 0 فانکشن

### `engine/ResearchAlertsTest.kt` — 39 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `calendar()` | ResearchAlertsTest | 17 | — |

### `engine/ResearchEvidenceTest.kt` — 82 خط · 0 فانکشن

### `engine/ScanFreshnessTest.kt` — 17 خط · 0 فانکشن

### `engine/ScanRankingTest.kt` — 41 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `row()` | ScanRankingTest | 16 | — |

### `engine/ScanUniverseTest.kt` — 31 خط · 0 فانکشن

### `engine/SettingsStoreTest.kt` — 121 خط · 0 فانکشن

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

### `engine/UnifiedContractTest.kt` — 63 خط · 1 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `trade()` | UnifiedContractTest | 47 | — |

### `engine/V1ContractTest.kt` — 245 خط · 2 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `bars()` | V1ContractTest | 33 | — |
| `trade()` | V1ContractTest | 175 | — |

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

## ابزارهای توسعه و راستی‌آزمایی — `tools` (10 فایل · 89 فانکشن)

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

### `test_probe_crypto_venues.py` — 107 خط · 4 فانکشن

| فانکشن | کلاس/آبجکت | خط | فلوچارت عمیق |
|---|---|---|---|
| `test_catalog_has_5_unique_usdt_pairs()` | ProbeCryptoVenuesTest | 7 | — |
| `test_exchange_parsers_accept_only_live_spot_usdt_pairs()` | ProbeCryptoVenuesTest | 13 | — |
| `test_coverage_summary_Separates_binance_from_fallbacks()` | ProbeCryptoVenuesTest | 78 | — |
| `test_closed_bar_validator_checks_1500_contiguous_fresh_bars()` | ProbeCryptoVenuesTest | 91 | — |

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

