# دسته‌بندی ۱۵ نماد و این‌که هر کدام از کجا می‌آید

این سند «نقشهٔ مسیر داده» است: برای هر نماد، منبع **کندل** (تاریخچهٔ M5/M15/H1)، منبع **تیک**
(قیمت لحظه‌ای/ورود/خروج)، ردیف **دیده‌بان**، و وضعیت **Twelve Data**. هرچه اینجا نوشته شده از
کد خوانده شده و با فراخوانی زندهٔ همان ناشر در **۲۰۲۶-۱۰-۲۰** راستی‌آزمایی شده است.

منابع کد:
`V1Universe.kt` (فهرست نمادها) · `SourceRouting.kt` (ترتیب منابع) ·
`NobitexCryptoReference.kt` + `CryptoRadarBundle.kt` (کریپتو) ·
`SpotFallbackClient.kt` (مسیر تیک) · `WatchCatalog.kt` (ردیف دیده‌بان) ·
`TwelveDataCoverage.kt` (پوشش Twelve Data) · سقف‌ها: [`SOURCE_LIMITS_FA.md`](SOURCE_LIMITS_FA.md).

## ۰) قانون‌هایی که روی همهٔ ۱۵ نماد حکمرانی می‌کنند

1. **یک ناشر برای کل بسته:** M5 + M15 + H1 هر نماد از **یک** ناشر می‌آیند؛ اگر یکی از سه بازه
   ناقص/کهنه باشد، کل بسته از ناشر بعدی آزموده می‌شود. هیچ بازه‌ای از M5 ساخته نمی‌شود.
2. **تیک، جدا از کندل:** قیمت لحظه‌ای از مسیر تیک می‌آید و نمی‌تواند کندل بسازد؛ برعکس هم
   کندل جای تیک را نمی‌گیرد.
3. **بدون جا‌زدن برچسب:** `BTC/USD` هیچ‌وقت `BTC/USDT` نمی‌شود، آتی/ETF هیچ‌وقت DOW نمی‌شود،
   قیمت طلا هیچ‌وقت نقره نمی‌شود. `PublicCandleHistoryClient.yahooChartSymbol` برای `XAU/*`،
   `XAG/*` و هر `*USDT` مقدار `null` می‌دهد.
4. **ورود کریپتو فقط هم‌صرافی:** کندل و معاملهٔ اجراشده باید از یک صرافی باشند
   (`CryptoVenuePolicy.sameVenue`)؛ نوبیتکس مرجع است و بقیه فقط‌خواندنی با برچسب خودشان.
5. **fail-closed:** اگر منبع پاسخ نداد، خطا نمایش داده می‌شود؛ عدد ساختگی یا کندل وصله‌ای نداریم.

## ۱) فلزات — ۲ نماد

| نماد | کندل بدون کلید | کندل با کلید | تیک (قیمت لحظه‌ای) | ردیف دیده‌بان | Twelve Data |
|---|---|---|---|---|---|
| `XAU/USD` | **Dukascopy Swiss** (`XAUUSD`، دو مسیر انتقال: نمودار JSON + آرشیو bi5) | **Twelve Data (مرجع طلا)** → Dukascopy | **Gold-API** `/price/XAU` → **Swissquote** `XAU/USD`؛ با کلید: ابتدا WebSocket Twelve، بعد همین‌ها | `gold_api_xau` | ⚠️ کالا؛ پلن Grow به بالا |
| `XAG/USD` | **Dukascopy Metal** (`XAGUSD`) | **Dukascopy** → Twelve Data | **Swissquote** `XAG/USD` (دو‌طرفه: bid/ask) → **Gold-API** `/price/XAG` (پاسخ باید `XAG` باشد، تک‌قیمت) | `gold_api_xag` | ⚠️ کالا؛ پلن Grow به بالا |

نکته‌ها:
- تنها نمادی که Twelve Data برایش **اول** است طلاست (`SourceRouting.twelveDataFirst` فقط
  `XAU/USD` را true می‌کند)؛ نقره همان ترتیب Dukascopy-اول را دارد.
- هر دو مسیر تیک با برچسب خودشان ثبت می‌شوند؛ تست `MetalQuoteRoutingTest` ثابت می‌کند پاسخ
  `XAU` هرگز به‌جای `XAG` پذیرفته نمی‌شود.
- راستی‌آزمایی زنده: Gold-API و Swissquote هر دو برای `XAU`/`XAG` پاسخ BBO/قیمت دادند.

## ۲) شاخص — ۱ نماد

| نماد | کندل بدون کلید | کندل با کلید | تیک | دیده‌بان | Twelve Data |
|---|---|---|---|---|---|
| `DOW` | **Yahoo `^DJI`** → Dukascopy `USA30IDXUSD` | همان → Twelve Data | **Yahoo `^DJI`** (`regularMarketPrice` + `regularMarketTime`) | `yahoo_dow` | ❌ شناسهٔ متفاوت |

- `^DJI` همان شاخص نقدی است؛ آتی/ETF یاهو (`YM=F`, `DIA`) هرگز به‌جای آن استفاده نمی‌شود.
- راستی‌آزمایی زنده: `^DJI` با `instrumentType: INDEX` و کندل‌های ۵ دقیقه‌ای پاسخ داد.
- Swissquote برای `DOW` آرایهٔ خالی می‌دهد؛ مسیر تیک شاخص اصلاً Swissquote نیست.

## ۳) فارکس — ۷ نماد

| نماد | کندل بدون کلید | کندل با کلید | تیک | دیده‌بان | Twelve Data |
|---|---|---|---|---|---|
| `EUR/USD` | **Yahoo** `EURUSD=X` → Dukascopy `EURUSD` | همان → Twelve Data | **Swissquote** BBO (دو‌طرفه) | `yahoo_eurusd` | ✅ شناسهٔ یکسان |
| `GBP/USD` | **Dukascopy** `GBPUSD` → Yahoo `GBPUSD=X` | همان → Twelve Data | **Swissquote** BBO | `swissquote_gbpusd` | ✅ |
| `USD/JPY` | **Yahoo** `JPY=X` → Dukascopy `USDJPY` | همان → Twelve Data | **Yahoo** `JPY=X` | `yahoo_usdjpy` | ✅ |
| `EUR/JPY` | **Dukascopy** `EURJPY` → Yahoo `EURJPY=X` | همان → Twelve Data | **Swissquote** BBO | `swissquote_eurjpy` | ✅ |
| `GBP/JPY` | **Yahoo** `GBPJPY=X` → Dukascopy `GBPJPY` | همان → Twelve Data | **Yahoo** `GBPJPY=X` | `yahoo_gbpjpy` | ✅ |
| `AUD/USD` | **Dukascopy** `AUDUSD` → Yahoo `AUDUSD=X` | همان → Twelve Data | **Swissquote** BBO | `swissquote_audusd` | ✅ |
| `USD/CHF` | **Yahoo** `CHF=X` → Dukascopy `USDCHF` | همان → Twelve Data | **Swissquote** BBO | `swissquote_usdchf` | ✅ |

نکته‌ها:
- «Yahoo اول» یا «Dukascopy اول» یعنی همان چیزی که `SourceRouting.DedicatedSource` برای آن نماد
  تعیین کرده؛ ناشر دیگر همیشه جایگزین دوم است و **پاسخ‌گو، برچسب خودش را نگه می‌دارد**.
- Swissquote برای **هر ۷ جفت** تیک می‌دهد. تیک `USD/JPY` و `GBP/JPY` از Yahoo می‌آید تا با
  مسیر کندل هم‌خانواده بماند؛ بقیه (از جمله `EUR/USD`) از Swissquote خوانده می‌شوند.
- **چرا `EUR/USD` از Yahoo به Swissquote منتقل شد:** یاهو فقط یک عدد می‌دهد و bid/ask ندارد.
  EUR-ARES پیش از هر ورود، اسپرد را با ATR مقایسه می‌کند و بدون اسپرد واقعی refuses می‌کند
  («قیمت دوطرفهٔ واقعی موجود نیست»). با تیک تک‌عددی، این جفت **هرگز** نمی‌توانست وارد شود.
  کندل همان Yahoo/Dukascopy است؛ فقط مسیر تیک عوض شد.
- Yahoo دو میزبان دارد (`query1`/`query2`) ولی **یک ناشر** است؛ دو تأیید مستقل نیستند.
- Twelve Data برای هر ۷ جفت با **همان شناسه** کار می‌کند (پلن Basic) و پایین‌ترین اولویت است،
  جز زمان‌هایی که کلید اختصاصی همان نماد گذاشته‌اید.

## ۴) رمزارز — ۵ نماد (نوبیتکس مرجع)

| نماد | کندل M5/M15/H1 | تیک | جایگزین فقط‌خواندنی | Twelve Data |
|---|---|---|---|---|
| `BTC/USDT` | **نوبیتکس** `BTCUSDT` | سوکت معاملات عمومی نوبیتکس + صفحهٔ REST همان صرافی | Binance Spot (`BTCUSDT`) | ⚠️ USD≠USDT |
| `ETH/USDT` | **نوبیتکس** `ETHUSDT` | همان | OKX Spot | ⚠️ |
| `SOL/USDT` | **نوبیتکس** `SOLUSDT` | همان | Gate Spot | ⚠️ |
| `BNB/USDT` | **نوبیتکس** `BNBUSDT` | همان | MEXC Spot | ⚠️ |
| `XRP/USDT` | **نوبیتکس** `XRPUSDT` | همان | Kraken Spot | ⚠️ |

جزئیات مسیر:
- `NobitexCryptoBundle` سه بازه را **مستقل** از نوبیتکس می‌گیرد
  (`/market/udf/history?symbol=BTCUSDT&resolution=5|15|60`). اگر یک بازه کم/کهنه باشد، کل بسته
  نوبیتکس رد می‌شود و **با برچسب همان صرافی** جایگزین (`CryptoRadarBundle`) آزموده می‌شود.
- تیک: `NobitexPublicTradeStream` (Centrifugo) اول، `OpenTradeTickMonitor` با صفحهٔ REST همان
  صرافی به‌عنوان پوشش؛ برای پوزیشن‌های قدیمی `BINANCE_SPOT` فقط `aggTrade` بایننس.
- چارت با بازهٔ غیر M5 یا تاریخچهٔ عمیق: `nobitexFrame` اول، بعد **Binance Spot** با برچسب
  خودش (`Binance Spot / USDT`) — یعنی برای همین پنج جفت `*USDT`؛ هیچ نماد `USD` جایگزین نمی‌شود.
- وضعیت زندهٔ نوبیتکس (۲۰۲۶-۱۰-۲۰): هر پنج جفت در orderbook و در UDF کندل داشتند، از جمله
  `BNBUSDT` (ادعای قدیمی سند که «BNBUSDT در نوبیتکس نیست» دیگر درست نیست).
- **bid/ask رمزارز (افزودهٔ ۲.۰.۹):** تیک معامله فقط یک عدد است و اسپرد ندارد؛ موتورهای BTC-ARES و
  فشردگی بدون اسپرد واقعی وارد نمی‌شوند (و نباید بشوند). پس بهترین خرید/فروش از
  `GET {HOST}/v2/orderbook/{SYMBOL}` همان نوبیتکس خوانده و به تیک معامله چسبانده می‌شود
  (`NobitexOrderBook`). قیمت مرجع همان معاملهٔ اجراشده می‌ماند، زمان قابل‌استفاده **قدیمی‌ترِ** دو
  مأخذ است، و اگر دفتر سفارش در دسترس/تازه نباشد یا خرید و فروش وارونه باشد، تیک **بدون** اسپرد
  برمی‌گردد تا موتور با دلیل روشن رد کند — هیچ اسپردی تخمین زده نمی‌شود.

## ۵) نگاه یک‌خطی (زنجیرهٔ کامل)

```
XAU/USD  کندل: Dukascopy → [Twelve Data]      تیک: Gold-API → Swissquote / [Twelve WS]
XAG/USD  کندل: Dukascopy → [Twelve Data]      تیک: Swissquote (bid/ask) → Gold-API(/price/XAG)
DOW      کندل: Yahoo ^DJI → Dukascopy         تیک: Yahoo ^DJI
EUR/USD  کندل: Yahoo → Dukascopy → [Twelve]   تیک: Swissquote (bid/ask)
GBP/USD  کندل: Dukascopy → Yahoo → [Twelve]   تیک: Swissquote
USD/JPY  کندل: Yahoo → Dukascopy → [Twelve]   تیک: Yahoo
EUR/JPY  کندل: Dukascopy → Yahoo → [Twelve]   تیک: Swissquote
GBP/JPY  کندل: Yahoo → Dukascopy → [Twelve]   تیک: Yahoo
AUD/USD  کندل: Dukascopy → Yahoo → [Twelve]   تیک: Swissquote
USD/CHF  کندل: Yahoo → Dukascopy → [Twelve]   تیک: Swissquote
BTC..XRP کندل: Nobitex → همان صرافی جایگزین   تیک: Nobitex executed trades
```
`[...]` = فقط وقتی کلیدی برای همان نماد وجود دارد (کلید اختصاصی یا کلید عمومی).

## ۶) چند عدد برای برنامه‌ریزی

- هر نماد در هر اسکن **۳ درخواست** می‌خواهد (M5 ۴۰۰ + M15 ۳۰۰ + H1 ۲۵۰ کندل).
- اسکن: ۶۰ ثانیه عادی، ۲۰ ثانیه وقتی نمادی کاندید/نزدیک ورود است.
- Twelve Data با کلید رایگان: ۸ درخواست/دقیقه و ۸۰۰/روز **برای هر کلید**؛ برای همین هر نماد
  می‌تواند کلید جدا داشته باشد (تنظیمات → API → «کلید اختصاصی هر نماد»).
- کدام نماد با کلید رایگان Twelve Data «درست» کار می‌کند و با چه کادنسی: [`SOURCE_LIMITS_FA.md`](SOURCE_LIMITS_FA.md).
