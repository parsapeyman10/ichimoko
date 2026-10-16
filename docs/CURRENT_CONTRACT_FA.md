# قرارداد فعلی مخزن (نسخهٔ فعلی — ۱۵ نماد با سورس اختصاصی ۱-به-۱ و ۱۵۰۰ کندل M5)

این فایل خلاصهٔ قوانین فعال در کد فعلی مخزن است.

## ۱. دامنهٔ ۱۵ نماد مجاز (`V1Universe` و `WatchCatalog`)

همهٔ نمادهای قدیمی و اضافی حذف شده‌اند و تنها **۱۵ نماد** زیر در کل برنامه، دیده‌بان، چارت و اسکنر فعال هستند:

- **فلزات گران‌بها (۲ نماد):**
  - `XAU/USD` (انس جهانی طلا)
  - `XAG/USD` (انس جهانی نقره)
- **شاخص (۱ نماد):**
  - `DOW` (شاخص داوجونز — `US30` / `^DJI`)
- **فارکس مناسب ایچیموکو (۷ نماد):**
  - `EUR/USD`، `GBP/USD`، `USD/JPY`، `EUR/JPY`، `GBP/JPY`، `AUD/USD`، `USD/CHF`
- **رمزارزهای پرحجم و پرمعامله (۵ نماد):**
  - `BTC/USDT`، `ETH/USDT`، `SOL/USDT`، `BNB/USDT`، `XRP/USDT`

نمایش نمودار TradingView برای هر ۱۵ نماد حفظ شده است (`TradingViewSymbols.kt`).

## ۲. نگاشت ۱-به-۱ و انحصاری سورس برای هر نماد (`SourceRouting` و `SourceCatalog`)

هر نماد **دقیقاً از یک سورس اختصاصی** داده می‌گیرد و **هیچ دو نمادی سورس مشترک ندارند**:

| نماد | دسته | سورس اختصاصی کندل ۵ دقیقه | سورس اختصاصی دیده‌بان |
|---|---|---|---|
| `XAU/USD` | فلزات | `DUKASCOPY_XAU` | `gold_api_xau` |
| `XAG/USD` | فلزات | `DUKASCOPY_XAG` | `gold_api_xag` |
| `DOW` | شاخص | `YAHOO_DOW` (`^DJI`) | `yahoo_dow` |
| `EUR/USD` | فارکس | `YAHOO_EURUSD` (`EURUSD=X`) | `yahoo_eurusd` |
| `GBP/USD` | فارکس | `DUKASCOPY_GBPUSD` | `swissquote_gbpusd` |
| `USD/JPY` | فارکس | `YAHOO_USDJPY` (`JPY=X`) | `yahoo_usdjpy` |
| `EUR/JPY` | فارکس | `DUKASCOPY_EURJPY` | `swissquote_eurjpy` |
| `GBP/JPY` | فارکس | `YAHOO_GBPJPY` (`GBPJPY=X`) | `yahoo_gbpjpy` |
| `AUD/USD` | فارکس | `DUKASCOPY_AUDUSD` | `swissquote_audusd` |
| `USD/CHF` | فارکس | `YAHOO_USDCHF` (`CHF=X`) | `swissquote_usdchf` |
| `BTC/USDT` | رمزارز | `BINANCE_SPOT` | `binance_btc_spot` |
| `ETH/USDT` | رمزارز | `OKX_SPOT` | `okx_eth_spot` |
| `SOL/USDT` | رمزارز | `GATE_SPOT` | `gate_sol_spot` |
| `BNB/USDT` | رمزارز | `MEXC_SPOT` | `mexc_bnb_spot` |
| `XRP/USDT` | رمزارز | `KRAKEN_SPOT` | `kraken_xrp_spot` |

## ۳. سیاست تایم‌فریم ۵ دقیقه و ۱۵۰۰ کندل (`HistoryPolicy` و `MtfAnalyzer`)

- دریافت داده از شبکه **فقط در تایم‌فریم ۵ دقیقه (`M5`)** انجام می‌شود.
- همیشه **۱۵۰۰ کندل آخر ۵ دقیقه** (`LIVE_MIN_CANDLES = 1500`، `LIVE_REQUEST_CANDLES = 1500`) دریافت و نگهداری می‌شود.
- تایم‌فریم‌های **۱۵ دقیقه (`M15`)** و **۱ ساعت (`H1`)** مستقیماً از روی ۱۵۰۰ کندل ۵ دقیقه تبدیل و محاسبه می‌شوند (`MtfAnalyzer.convertFromM5`):
  - هر **۳ کندل ۵ دقیقه = ۱ کندل ۱۵ دقیقه** (مجموعاً ۵۰۰ کندل `M15`).
  - هر **۱۲ کندل ۵ دقیقه = ۱ کندل ۱ ساعته** (مجموعاً ۱۲۵ کندل `H1`).
- روی تایم‌فریم ۵ دقیقه، علاوه بر ایچیموکو پایه (`8/24/72` با جابه‌جایی `24`)، خطوط بزرگ‌تر ایچیموکو با دوره‌های **۵۱۳** (`line513`) و **۱۰۲۴** (`line1024`) محاسبه می‌شوند تا جهت تایم‌فریم‌های بالاتر مستقیماً روی ۵ دقیقه مشخص شود.

## ۴. قوانین خرید و فروش (`SignalEngine`، `CryptoCompressionEngine` و `V1Scoring`)

- **رمزارزها (`BTC/USDT`، `ETH/USDT`، `SOL/USDT`، `BNB/USDT`، `XRP/USDT`):**
  - قوانین خرید و فروش همان سیستم **فشردگی و انفجار (Compression & Breakout)** در `CryptoCompressionEngine` است (فشردگی ۲۰ کندلی، شکست با حجم/ADX/چیکو، فیلتر کلان ایچیموکو ۵۲/۱۰۳ + هم‌راستایی `M15`/`H1` تبدیل‌شده از `M5`، و مدیریت ریسک/تریل مبتنی بر ATR).
- **طلا، نقره، شاخص داوجونز و فارکس (`XAU/USD`، `XAG/USD`، `DOW` و ۷ جفت فارکس):**
  - قوانین خرید و فروش مبتنی بر **ایچیموکو چندتایم‌فریمی** (تایم‌فریم `M5` + خطوط کلان `513` و `1024` + ایچیموکو تایم‌فریم‌های `M15` و `H1` تبدیل‌شده از `M5`) به همراه اندیکاتورهای مکمل (**EMA200، RSI، MACD، ADX و ساختار پرایس‌اکشن/ATR**) است.
