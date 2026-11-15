# Aurum Edge Desktop — Python Cross-Platform

اپلیکیشن desktop با **Python 3.11+ و Tkinter** یک source مشترک برای Windows، macOS و Linux دارد. Qt، CMake، Ninja و Visual Studio لازم نیستند.

> فایل اجرایی یکسانِ قابل اجرا روی همهٔ سیستم‌عامل‌ها وجود ندارد. PyInstaller باید روی هر سیستم‌عامل مقصد اجرا شود: Windows برای EXE ویندوز، macOS برای binary مک، و Linux برای binary لینوکس.

## قابلیت‌ها

- بارگذاری و ممیزی CSV کندلی یا bid/ask با پاک‌سازی timestamp تکراری و OHLC نامعتبر
- candle chart با Tkinter Canvas و archive در قالب CSV/JSON
- بک‌تست deterministic `Ichimoku 8/24/72 TK-cross baseline` با Fee، Slippage، SL-first و Trend Runner
- ماشین‌حساب ریسک، حجم، margin، liquidation تقریبی و TradePlanGuard
- ژورنال SQLite محلی، تقویم ماهانه و خروجی CSV/JSON/PDF ساده
- NeoWave، Neural Lab جدا از بک‌تست، quote عمومی صرفاً مشاهده‌ای و بررسی دستی Release

ژورنال به‌صورت محلی در مسیر مناسب هر OS ذخیره می‌شود:

| سیستم‌عامل | مسیر دادهٔ محلی |
|---|---|
| Windows | `%APPDATA%\AurumEdgePython` |
| macOS | `~/Library/Application Support/AurumEdgePython` |
| Linux | `$XDG_DATA_HOME/AurumEdgePython` یا `~/.local/share/AurumEdgePython` |

این برنامه ابزار تحلیل تاریخی و Paper Trading است؛ API key، حساب کارگزاری یا ارسال سفارش واقعی ندارد.

## اجرای source

Tkinter باید همراه Python نصب باشد:

| سیستم‌عامل | دستور |
|---|---|
| Windows | `windows\run_windows.bat` |
| macOS / Linux | `sh windows/run_desktop.sh` |

در Ubuntu/Debian، اگر `tkinter` وجود ندارد، بستهٔ distro مانند `python3-tk` را نصب کنید. روی Python رسمی Windows و Python.org برای macOS، Tkinter معمولاً همراه runtime است.

## ساخت native artifact

### Windows

روی این فایل دوبارکلیک کنید یا در Command Prompt اجرا کنید:

```bat
windows\build_windows.bat
```

خروجی:

```text
windows\dist-local\windows\Aurum_Edge_Windows.exe
```

### macOS و Linux

ابتدا executable permission بدهید (یک‌بار):

```sh
chmod +x windows/build_desktop.sh windows/run_desktop.sh windows/build_macos.command
```

سپس:

```sh
sh windows/build_desktop.sh
```

روی macOS می‌توانید `windows/build_macos.command` را نیز اجرا کنید. خروجی‌ها:

```text
windows/dist-local/macos/Aurum_Edge_macOS
windows/dist-local/linux/Aurum_Edge_Linux
```

همهٔ wrapperها به `windows/build_desktop.py` می‌رسند. این builder:

1. Python 3.11+ و sourceها را اعتبارسنجی می‌کند؛
2. برای ساخت واقعی، Tkinter را بررسی می‌کند؛
3. تست‌های deterministic را اجرا می‌کند؛
4. یک virtual environment محلی می‌سازد؛
5. فقط PyInstaller را در همان محیط نصب می‌کند؛
6. artifact native مربوط به همان host را می‌سازد.

در Windows، `build_windows.bat` به‌صورت خودکار Pythonهای نصب‌شده را برای Tkinter سالم بررسی می‌کند، یک runtime سالم را انتخاب می‌کند و در هر build محیط تولیدی `.build-venv` را بازسازی می‌کند؛ بنابراین حذف دستی venv لازم نیست. خود builder نیز اگر Tkinter فقط داخل `.build-venv` خراب باشد، آن environment را یک‌بار خودکار حذف و دوباره می‌سازد. برای اجبار به بازسازی از ابتدا در اجرای مستقیم builder نیز می‌توانید اجرا کنید:

```sh
python windows/build_desktop.py --recreate-venv
```

در Windows همین گزینه از طریق BAT نیز قابل ارسال است:

```bat
windows\build_windows.bat --recreate-venv
```

اگر **host Python** هنگام `import tkinter` با `0xC0000005` یا access violation crash کند، مشکل از نصب Python/Tcl-Tk ویندوز است، نه source برنامه. Python رسمی 64-bit را از Apps/Installer با گزینهٔ Repair اصلاح یا دوباره نصب کنید (Tcl/Tk فعال باشد)، سپس builder را دوباره اجرا کنید.

برای بررسی portable source بدون ساخت artifact:

```sh
python windows/build_desktop.py --check
```

## تست‌ها

```sh
python windows/test_aurum_core.py
```

CI روی Windows، macOS و Linux، compile-check و تست core را اجرا می‌کند.

## محدودیت‌ها

- مسیر tick واقعی داخل کندل وجود ندارد؛ برخورد هم‌زمان SL/TP با SL-first مدل می‌شود.
- quote عمومی ممکن است با قیمت قابل اجرا اختلاف داشته باشد.
- Neural Lab فقط گزارش آموزشی deterministic و in-sample است؛ OOS یا سودآوری تضمین نمی‌کند.
- PDF خروجی یک گزارش متنی ساده با فونت استاندارد PDF است تا dependency runtime اضافه نشود.
