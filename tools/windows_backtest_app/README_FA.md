# نرم‌افزار بک‌تست ویندوز — تماماً Python

این پوشه یک برنامهٔ مستقل Windows برای بک‌تست تاریخی CSV پانزده‌دقیقه‌ای است:

- فقط **Python + Tkinter** در زمان اجرا؛ Qt، Android Studio، APK، Emulator و اتصال صرافی ندارد.
- منطق آن نسخهٔ عملیاتی فعلی `BASE_ICHIMOKU`، یعنی **native 15m** است؛ دادهٔ 30m و 1H تولید یا مصرف نمی‌شود.
- ورود در open کندل بعدی شبیه‌سازی می‌شود؛ SL-first برای لمس هم‌زمان SL/TP اعمال می‌شود.
- فاصلهٔ Tenkan/Kijun و Kumo native-15m در entry و Smart Trend Exit بررسی می‌شوند؛ خروج 20 کندلی/time-stop ندارد.
- برای هر موج Ichimoku فقط یک entry هم‌جهت می‌گیرد؛ بسته‌شدن معامله به‌تنهایی re-entry همان موج را مجاز نمی‌کند.
- گزارش CSV و JSON تولید می‌کند و هیچ سفارش زنده‌ای ارسال نمی‌کند.

> این برنامه ابزار بک‌تست و audit است، نه توصیهٔ سرمایه‌گذاری، نه تضمین سود، و نه اتصال معاملهٔ واقعی.

## فایل‌های مهم

| فایل | کاربرد |
|---|---|
| `base_ichimoku_backtest_app.py` | رابط گرافیکی ویندوز و اجرای بک‌تست |
| `native15_engine.py` | engine مستقل و dependency-free بک‌تست |
| `run_windows_backtest_app.bat` | اجرای مستقیم source با Python نصب‌شده |
| `build_windows_backtest_app.bat` | ساخت محلی فایل `.exe` با PyInstaller |
| `test_native15_engine.py` | تست‌های سریع engine |

## پیش‌نیاز Windows

- Windows 10/11 x64
- Python **3.11 یا جدیدتر** از [python.org](https://www.python.org/downloads/windows/)
- هنگام نصب Python گزینهٔ **Add Python to PATH** را فعال کنید.

برای اجرای source هیچ `pip install` لازم نیست؛ Tkinter همراه Python رسمی ویندوز است.

## اجرای مستقیم بدون ساخت EXE

از File Explorer روی این فایل دوبارکلیک کنید:

```text
run_windows_backtest_app.bat
```

یا در Command Prompt:

```bat
tools\windows_backtest_app\run_windows_backtest_app.bat
```

## ساخت اپ ویندوز با BAT

برای ساخت محلی executable، فقط این فایل را اجرا کنید:

```text
build_windows_backtest_app.bat
```

Batch به‌صورت خودکار این کارها را انجام می‌دهد:

1. یک virtual environment محلی به نام `.build-venv` می‌سازد؛
2. فقط `PyInstaller` را داخل همان محیط نصب می‌کند؛
3. برنامهٔ تک‌فایلی Windows را می‌سازد.

خروجی روی کامپیوتر ویندوز شما در این مسیر تولید می‌شود:

```text
tools\windows_backtest_app\dist-local\BASE_ICHIMOKU_15m_Backtest.exe
```

این repository فقط source و BAT لازم برای Build محلی را نگه می‌دارد؛ فایل EXE یا installer در repository تولید/commit نمی‌شود.

## استفاده از برنامه

1. CSV پانزده‌دقیقه‌ای GoCharting را انتخاب کنید.
2. پوشهٔ خروجی را تعیین کنید.
3. ابتدا `Target R:R` را انتخاب و سپس `Quantity` را وارد کنید.
4. در صورت نیاز Smart Trend Exit و تعداد تأییدهای آن را تغییر دهید.
5. روی **Run native 15m backtest** کلیک کنید.

ورودی CSV باید headerهای زمان و OHLC داشته باشد. نام‌های متعارف زیر پذیرفته می‌شوند:

```text
timestamp/time/datetime/date, open/o, high/h, low/l, close/c, volume/v
```

زمان می‌تواند Unix seconds، Unix milliseconds یا ISO-8601 باشد. CSV باید روی مرز 15m باشد. اگر gap دارد، به‌صورت پیش‌فرض رد می‌شود؛ فقط در صورت آگاهی از اثر آن گزینهٔ `Allow 15m gaps` را روشن کنید.

## خروجی‌ها

| فایل | محتوا |
|---|---|
| `trades.csv` | معاملات بسته، SL/TP، دلیل خروج، PnL و equity |
| `signals.csv` | signalهای strict که وارد صف next-open شده‌اند |
| `equity_curve.csv` | equity پس از هر معاملهٔ بسته |
| `summary.json` | تنظیمات، ممیزی CSV، diagnostics و خلاصهٔ معیارها |

## Smart Trend Exit

خروج هوشمند فقط با کندل بسته‌شدهٔ 15m فعال می‌شود. در حالت پیش‌فرض حداقل 2 تأیید خلاف‌روند از وضعیت Kijun، Tenkan/Kijun و Kumo لازم است. علاوه بر آن، فاصلهٔ Tenkan/Kijun باید در حال جمع‌شدن باشد؛ اگر خود Tenkan/Kijun خلاف جهت position cross کنند، همان تغییر ساختار جای این تأیید فاصله را می‌گیرد. خروج در open کندل بعدی شبیه‌سازی می‌شود و reversal فوری ایجاد نمی‌کند.

## اجرای تست‌ها

از ریشهٔ repository:

```bat
python tools\windows_backtest_app\test_native15_engine.py
```

## محدودیت‌ها

- مدل intrabar واقعی/tick path ندارد؛ در صورت لمس هم‌زمان SL و TP، برای محافظه‌کاری SL انتخاب می‌شود.
- هزینه، اسپرد و لغزش واقعی هر venue ممکن است با مقدار واردشده فرق داشته باشد.
- این Python engine بازنویسی مستقل منطق native-15m Lipi است؛ به‌دلیل تفاوت order emulator، نباید parity بیت‌به‌بیت یا سودآوری تضمین‌شده ادعا شود.
