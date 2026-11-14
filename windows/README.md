# Aurum Edge — Windows Desktop (Python)

نسخهٔ Windows اکنون کاملاً با **Python 3.11+ و Tkinter** ساخته می‌شود. پیاده‌سازی قبلی C++/Qt، CMake، Ninja و Visual Studio از این deliverable حذف شده‌اند.

## قابلیت‌های برنامه

- بارگذاری و ممیزی CSV کندلی یا bid/ask با پاک‌سازی timestamp تکراری و OHLC نامعتبر
- چارت candle داخلی با Tkinter، آرشیو CSV/JSON و بدون وابستگی به مرورگر
- بک‌تست deterministic `Ichimoku 8/24/72 TK-cross baseline` با ورود کندل بعدی، Fee، Slippage، SL-first و Trend Runner
- ماشین‌حساب ریسک، حجم، margin، liquidation تقریبی و TradePlanGuard
- ژورنال SQLite محلی، تقویم ماهانه و خروجی CSV/JSON/PDF ساده
- تحلیل NeoWave مبتنی بر pivotهای ATR و گزارش Neural Lab جدا از خروجی بک‌تست
- دریافت اختیاری quote عمومی و بررسی دستی GitHub Release؛ هیچ API key، اتصال حساب، route سفارش یا نصب خودکار ندارد

> این برنامه ابزار تحلیل تاریخی و Paper Trading است؛ توصیهٔ سرمایه‌گذاری، تضمین سود یا ارسال سفارش واقعی نیست.

## اجرای مستقیم source

Windows 10/11 و Python 3.11 یا جدیدتر از [python.org](https://www.python.org/downloads/windows/) لازم است. هنگام نصب Python گزینهٔ **Add Python to PATH** را فعال کنید.

روی این فایل دوبارکلیک کنید:

```text
windows\run_windows.bat
```

Tkinter همراه نصب رسمی Python ویندوز است و برای اجرای source نیازی به `pip install` نیست.

## ساخت EXE با BAT

برای ساخت فایل اجرایی Windows فقط این فایل را اجرا کنید:

```text
windows\build_windows.bat
```

Batch به‌ترتیب زیر عمل می‌کند:

1. Python 3.11+ و Tkinter را بررسی می‌کند؛
2. تست‌های deterministic core را اجرا می‌کند؛
3. یک virtual environment محلی `.build-venv` می‌سازد؛
4. فقط `PyInstaller` را در همان محیط نصب می‌کند؛
5. برنامه را به EXE تک‌فایلی تبدیل می‌کند.

خروجی محلی در این مسیر است:

```text
windows\dist-local\Aurum_Edge_Windows.exe
```

فولدرهای build و فایل EXE توسط `.gitignore` نادیده گرفته می‌شوند و نباید commit شوند.

## اجرای تست‌ها

از ریشهٔ repository:

```bat
python windows\test_aurum_core.py
```

## محدودیت‌های مدل

- مسیر tick واقعی داخل کندل وجود ندارد؛ در برخورد هم‌زمان SL و TP، SL برای محافظه‌کاری مقدم است.
- quote عمومی ممکن است با قیمت قابل اجرا اختلاف داشته باشد.
- Neural Lab فقط گزارش آموزشی deterministic و in-sample است؛ پارامترهای بک‌تست را تغییر نمی‌دهد و OOS یا سودآوری را تضمین نمی‌کند.
- PDF خروجی یک گزارش متنی ساده با فونت استاندارد PDF است تا برنامه در زمان اجرا dependency خارجی نداشته باشد.
