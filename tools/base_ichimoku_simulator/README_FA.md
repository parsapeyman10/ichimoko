# شبیه‌ساز پایتون `BASE_ICHIMOKU` چهارگانه

این ابزار سبک و مستقل از کتابخانهٔ خارجی، CSV پانزده‌دقیقه‌ای خروجی GoCharting را می‌گیرد و فقط مسیر خالص مدل
`BitcoinStrategyModel.BASE_ICHIMOKU` را شبیه‌سازی می‌کند:

- ترکیب چهار مکتب **پاتل، تاتل، هوسودا و پلویل**؛
- ۱۷ شرط، چهار trigger استاندارد و `bookConfluenceGate`؛
- ساخت 1H و 4H از پایهٔ 15m، بدون دیدن کندل بالاترِ ناتمام؛
- پلن ساختاری SL/TP، دالان `TradePlanGuard`، هزینهٔ اجرا و گیت اقتصاد معاملهٔ شرط ۱۷؛
- تصمیم فقط پس از بسته‌شدن کندل، fill بازار در **بازشدن کندل 15m بعدی** و اولویت محافظه‌کارانهٔ **SL-first** در لمس هم‌زمان SL/TP.

مرجع منطق، commit زیر است:

```text
f1c9850ae290bbbc5e953c8fdf453c05754928cd
```

مسیرهای `Hidden Cross`، `Parsa 52`، `BTC 52`، `Peyman` و `NeoWave` عمداً در این ابزار وارد نشده‌اند؛ روشن بودن آن‌ها parity «بیس خالص چهارگانه» را مخدوش می‌کند.

> این یک بک‌تست تاریخی است، **نه** اتصال به صرافی یا سفارش‌گذاری واقعی. هیچ API، حساب یا سفارش زنده‌ای را لمس نمی‌کند و سود گذشته، تضمین سود آینده نیست.

---

## پیش‌نیاز

فقط Python 3.11+ لازم است؛ `pip install` یا محیط مجازی لازم نیست.

```bash
python --version
python tools/base_ichimoku_simulator/base_ichimoku_simulator.py --help
```

---

## CSV ورودی

فایل باید کندل‌های UTC و واقعاً پانزده‌دقیقه‌ای داشته باشد. این قالب و aliasهای متعارف آن پذیرفته می‌شوند:

```csv
timestamp,open,high,low,close,volume
1704067200000,2040.10,2041.65,2039.80,2041.25,432.4
```

- زمان می‌تواند millisecond Unix، second Unix یا ISO-8601 باشد.
- نام ستون زمان یکی از `timestamp`, `time`, `datetime`, `date` و نام OHLC به شکل `open/high/low/close` یا `o/h/l/c` است.
- ردیف‌های OHLC نامعتبر حذف و در گزارش شمارش می‌شوند؛ timestamp تکراری فقط یک بار نگه داشته می‌شود.
- timestamp باید دقیقاً بر مرز UTC پانزده‌دقیقه‌ای باشد. بنابراین CSV تایم‌فریم دیگر، به‌جای تولید بک‌تست ظاهراً معتبر ولی نادرست، رد می‌شود.
- به‌صورت پیش‌فرض هر gap پانزده‌دقیقه‌ای خطاست. اگر عمداً می‌خواهید ادامه دهید، `--allow-gaps` بدهید؛ bucketهای ناقص 1H/4H حذف می‌شوند و هرگز در تصمیم دیده نمی‌شوند.

ابتدا فقط اعتبارسنجی کنید:

```bash
python tools/base_ichimoku_simulator/base_ichimoku_simulator.py \
  /path/to/BINANCE_FUTURE_XAUUSDT_15m.csv \
  --validate-only
```

---

## اجرای XAUUSDT

```bash
python tools/base_ichimoku_simulator/base_ichimoku_simulator.py \
  /path/to/BINANCE_FUTURE_XAUUSDT_15m.csv \
  --symbol XAUUSDT \
  --start-equity 1000 \
  --risk-percent 0.5 \
  --cost-multiplier 1.0 \
  --output reports/xauusdt_base
```

در ویندوز نیز launcher سادهٔ زیر همان کد را اجرا می‌کند و **هیچ EXE یا installer تولید نمی‌کند**:

```bat
tools\base_ichimoku_simulator\run_base_ichimoku.bat C:\data\XAUUSDT_15m.csv --symbol XAUUSDT --output reports\xauusdt_base
```

برای آزمون stress هزینه می‌توان ضریب را افزایش داد:

```bash
python tools/base_ichimoku_simulator/base_ichimoku_simulator.py data.csv \
  --symbol XAUUSDT --cost-multiplier 2.0 --output reports/xauusdt_cost_x2
```

### نکتهٔ بسیار مهم دربارهٔ `XAUUSDT`

طبقه‌بندی مرجع Android ابتدا نمادهای دارای `USDT` را `CRYPTO` می‌داند. بنابراین `XAUUSDT`، حتی با وجود `XAU` در نام، از benchmark هزینهٔ USDT استفاده می‌کند: **۰٫۱۰٪ در هر سمت + اسپرد ۱ bps**. این موضوع عمداً شرط ۱۷ (هزینهٔ رفت‌وبرگشت در برابر 1R) را سخت می‌کند و ممکن است بسیاری یا همهٔ ستاپ‌ها را حذف کند. این خطا نیست و در `report.json` و `top_failed_conditions` قابل مشاهده است.

اگر می‌خواهید اثر benchmark کالای `XAU/USD` را فقط به‌صورت مقایسه‌ای بسنجید، نماد را صریحاً `XAUUSD` بدهید. چنین اجراهایی را با اجرای `XAUUSDT` یکی ندانید.

---

## مدل زمانی و جلوگیری از look-ahead

هر کندل پایه در زمان `t` تا `t + 15m` شکل می‌گیرد. بعد از بسته‌شدن آن:

1. فقط کندل‌های 15m/1H/4H که تا همان لحظه **کاملاً بسته شده‌اند** به engine داده می‌شوند؛
2. trigger فقط روی همین دادهٔ بسته‌شده ارزیابی می‌شود؛
3. اگر signal معتبر باشد، سفارش pending است؛
4. fill فقط روی `open` کندل پانزده‌دقیقه‌ای بعدی انجام می‌شود؛
5. در همان کندل fill و کندل‌های بعدی، SL/TP با `high/low` بررسی می‌شوند؛ اگر هر دو لمس شوند، **SL** انتخاب می‌شود؛
6. سپس time-stop پیش‌فرض ۲۰ کندل اعمال می‌شود.

پلن signal هنگام fill به قیمت open بعدی rebase می‌شود. اگر گپ از حد کهنگی `TradePlanGuard` بزرگ‌تر باشد، ابزار به‌جای وانمودکردن به fill با پلن قدیمی، آن را رد می‌کند.

این سیاست fill صریحاً برای قرارداد درخواست‌شدهٔ *candle-close-only + next-candle fill* است. هم‌سنجی عددی نهایی با خروجی Android/GoCharting فقط بعد از اجرای هر دو روی **همان CSV** معتبر است؛ این README ادعای parity بیت‌به‌بیت یا سودآوری نمی‌کند.

---

## فایل‌های خروجی

مسیر `--output` شامل این فایل‌هاست:

| فایل | محتوا |
|---|---|
| `report.json` | تنظیمات، ممیزی CSV، وضعیت مسیرهای غیرفعال، diagnostics، معیارها و محدودیت‌ها |
| `trades.csv` | تمام معاملات بسته: زمان signal/fill/exit، سمت، trigger، SL/TP، PnL، fee، R و equity بعد از معامله |
| `signals.csv` | signalهای مجاز بیس با تعداد شرط پاس‌شده و امتیازهای book gate |
| `equity_curve.csv` | سرمایهٔ شروع و مقدار سرمایه بعد از هر معاملهٔ بسته |

اگر معامله‌ای تولید نشود، `report.json` همچنان `maximum_conditions_passed`، `best_book_gate_pct` و `top_failed_conditions` را می‌دهد تا «۰ معامله» قابل ممیزی باشد.

---

## آزمون سریع ابزار

```bash
python tools/base_ichimoku_simulator/test_simulator.py
```

این تست‌های بدون وابستگی این قراردادها را کنترل می‌کنند:

- 1H فقط از چهار کندل 15m کامل ساخته می‌شود؛
- یک کندل H1 تا قبل از پایان خودش قابل مشاهده نیست؛
- CSV با timestamp ثانیه‌ای، duplicate و OHLC خراب درست ممیزی می‌شود؛
- rebase fill بعدی همچنان جهت SL/TP و دالان R:R را حفظ می‌کند؛
- هزینهٔ رفت‌وبرگشت از PnL کم می‌شود.

---

## محدودیت‌های صادقانه

- مسیر intrabar واقعی یا tick-level نداریم؛ جزئیات ترتیب high/low در یک کندل نامعلوم است و عمداً SL-first فرض شده است.
- لغزش، funding، بهره/overnight و محدودیت حجم واقعی صرافی مدل نشده‌اند.
- CSV باید از GoCharting export شود؛ این محیط به GoCharting متصل نیست و فایل را آن‌جا compile/run نمی‌کند.
- قبل از هر تصمیم واقعی باید خروجی را روی بازهٔ خارج از نمونه، با هزینه و دادهٔ اجرایی واقعی، اعتبارسنجی مستقل کنید.
