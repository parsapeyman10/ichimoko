# Aurum Edge Desktop — معماری PyQt6 چندسکویی

> نام فایل برای سازگاری با لینک‌های قبلی حفظ شده است. desktop app دیگر native C++/Qt نیست؛ source مشترک Python آن روی Windows، macOS و Linux اجرا می‌شود و رابط آن با PyQt6 پیاده‌سازی شده است.

## تصمیم فنی

- **زبان و runtime:** Python 3.11+، PyQt6 و کتابخانهٔ استاندارد
- **رابط:** `QMainWindow`، `QTabWidget`، فرم‌ها و جدول‌های PyQt6؛ `QPainter` داخلی برای candle chart
- **Build:** `windows\build_windows.bat` در Windows و `windows/build_desktop.sh` در macOS/Linux با PyInstaller؛ بدون C++ source، CMake، Ninja یا Visual Studio
- **دادهٔ محلی:** `sqlite3` استاندارد Python برای ژورنال و `csv` استاندارد برای import/export
- **اجرای موازی:** `threading.Thread` و signal/event bus Qt برای خواندن CSV، بک‌تست، quote و بررسی update، بدون قفل‌کردن UI
- **بسته‌بندی:** artifact تک‌فایلی native هر host با PyInstaller و `--collect-all PyQt6`؛ خروجی build در Git نگه‌داری نمی‌شود

## مسیر داده

```text
CSV candle / bid-ask → Parse + Validation → Candle Series → Ichimoku 8/24/72
                                                        ↘ Backtest / Stats / Trade Ledger
                                                        ↘ QPainter Chart / SQLite Journal
```

اپلیکیشن همچنان یک ابزار تاریخی و Paper Trading است. API key، حساب کارگزاری، route ارسال سفارش یا نصب خودکار فایل ناشناس ندارد.

## ساختار جدید

| مسیر | وظیفه |
|---|---|
| `windows/aurum_core.py` | modelهای داده، CSV، Ichimoku، بک‌تست، ریسک، NeoWave، Neural Lab، SQLite و export |
| `windows/aurum_edge.py` | رابط PyQt6، چارت QPainter، tabهای کاربردی و workerهای signal-based |
| `windows/requirements.txt` | dependency runtime: PyQt6 |
| `windows/requirements-build.txt` | PyQt6 به‌همراه PyInstaller برای بسته‌بندی |
| `windows/test_aurum_core.py` | تست‌های deterministic بدون نیاز به display |
| `windows/run_windows.bat` | اجرای source با Python نصب‌شده در Windows و check PyQt6 |
| `windows/run_desktop.sh` | اجرای source در macOS/Linux و check PyQt6 |
| `windows/build_desktop.py` | builder مشترک؛ فقط artifact native همان host را می‌سازد |
| `windows/build_windows.bat` / `windows/build_desktop.sh` | wrapper ساخت برای Windows / macOS و Linux |
| `.github/workflows/desktop-python.yml` | CI Python/PyQt6 روی Windows، macOS و Linux |

## ویژگی‌های قابل استفاده

| قابلیت | وضعیت |
|---|---|
| CSV واقعی و حذف ردیف خراب/تکراری/حجم صفر | پیاده‌سازی و تست شده |
| چارت کندلی | QPainter داخلی؛ حداکثر ۱۵۰۰ کندل آخر رسم می‌شود |
| بک‌تست baseline با Fee، Slippage، Risk و Trend Runner | پیاده‌سازی deterministic |
| برخورد هم‌زمان SL/TP | SL-first محافظه‌کارانه |
| ژورنال پایدار و تقویم ماهانه | SQLite محلی |
| Archive candle و خروجی journal | CSV/JSON و PDF متنی ساده |
| ماشین‌حساب ریسک و TradePlanGuard | حجم، margin، liquidation تقریبی و دالان ATR |
| NeoWave و Neural Lab | تحلیل ساختاری و گزارش in-sample جدا از خروجی بک‌تست |
| Quote و update | درخواست عمومی اختیاری، فقط مشاهده و بررسی دستی |

## محدودیت‌ها

1. مسیر واقعی tick داخل کندل موجود نیست؛ نتیجهٔ بک‌تست به feed و هزینه‌های واقعی venue حساس است.
2. PDF ساده عمداً با فونت استاندارد PDF تولید می‌شود تا dependency runtime اضافه نشود.
3. Neural Lab فقط یک گزارش آموزشی deterministic است؛ نه اعتبارسنجی out-of-sample و نه بهینه‌ساز پارامتر.
4. quote عمومی قیمت قابل اجرا نیست و نباید مبنای سفارش واقعی باشد.
5. PyInstaller cross-compile نمی‌کند: EXE باید روی Windows، binary مک روی macOS و binary لینوکس روی Linux با builder همان host ساخته و تست شود. محیط لینوکس artifact ویندوزی یا macOS تولید/اجرا نمی‌کند.
