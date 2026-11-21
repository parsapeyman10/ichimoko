# Aurum Edge Desktop — PyQt6 Cross-Platform

اپلیکیشن desktop با یک source مشترک **Python 3.11+ + PyQt6** برای Windows، macOS و Linux ساخته شده است. رابط از Tkinter بازنویسی شده و اکنون از widgetهای PyQt6 و `QPainter` برای candle chart استفاده می‌کند. C++/Qt native، CMake، Ninja و Visual Studio برای source یا build لازم نیستند.

> یک فایل اجرایی واحد برای همهٔ سیستم‌عامل‌ها وجود ندارد. PyInstaller باید روی سیستم‌عامل مقصد اجرا شود: Windows برای EXE ویندوز، macOS برای binary مک و Linux برای binary لینوکس.

## قابلیت‌ها

- بارگذاری و ممیزی CSV کندلی یا bid/ask با پاک‌سازی timestamp تکراری و OHLC نامعتبر
- candle chart با `QPainter` و archive در قالب CSV/JSON
- بک‌تست deterministic `Ichimoku 8/24/72 TK-cross baseline` با Fee، Slippage، SL-first و Trend Runner
- ماشین‌حساب ریسک، حجم، margin، liquidation تقریبی و TradePlanGuard
- ژورنال SQLite محلی، تقویم ماهانه و خروجی CSV/JSON/PDF ساده
- NeoWave، Neural Lab جدا از بک‌تست، quote عمومی صرفاً مشاهده‌ای و بررسی دستی Release

ژورنال به‌صورت محلی در مسیر مناسب هر OS ذخیره می‌شود:

| سیستم‌عامل | مسیر دادهٔ محلی |
|---|---|
| Windows | `%APPDATA%\AurumEdgePyQt` |
| macOS | `~/Library/Application Support/AurumEdgePyQt` |
| Linux | `$XDG_DATA_HOME/AurumEdgePyQt` یا `~/.local/share/AurumEdgePyQt` |

این برنامه ابزار تحلیل تاریخی و Paper Trading است؛ API key، حساب کارگزاری یا ارسال سفارش واقعی ندارد.

## اجرای source

یک‌بار PyQt6 را برای Python مورد نظر نصب کنید:

```sh
python -m pip install -r windows/requirements.txt
```

سپس اجرا کنید:

| سیستم‌عامل | دستور |
|---|---|
| Windows | `windows\run_windows.bat` |
| macOS / Linux | `sh windows/run_desktop.sh` |

در Windows runner به‌طور خودکار یک Python 3.11+ مناسب پیدا می‌کند و اگر PyQt6 برای همان interpreter نصب نباشد، دستور نصب دقیق را نشان می‌دهد. در macOS/Linux می‌توان برای انتخاب interpreter از `PYTHON_CMD` استفاده کرد:

```sh
PYTHON_CMD=python3.12 sh windows/run_desktop.sh
```

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
2. تست‌های deterministic core را اجرا می‌کند؛
3. یک virtual environment محلی می‌سازد یا بازسازی می‌کند؛
4. `PyQt6` و `PyInstaller` را فقط در همان محیط از `requirements-build.txt` نصب می‌کند؛
5. import PyQt6 را تأیید می‌کند و dependencyهای Qt را با `--collect-all PyQt6` به artifact اضافه می‌کند؛
6. artifact native مربوط به همان host را می‌سازد.

در Windows، `build_windows.bat` Pythonهای نصب‌شده را پیدا می‌کند و در هر build محیط تولیدی `.build-venv` را بازسازی می‌کند؛ بنابراین نیازی به Tcl/Tk یا حذف دستی venv نیست. در Linux، اگر import PyQt6 نام یک shared library مانند `libxkbcommon.so.0` را گزارش کند، runtimeهای GUI/X11 همان distribution را نصب کنید و build را روی همان Linux هدف تکرار کنید. برای بازسازی از ابتدا در اجرای مستقیم builder نیز می‌توانید اجرا کنید:

```sh
python windows/build_desktop.py --recreate-venv
```

برای بررسی portable source بدون نصب dependency یا ساخت artifact:

```sh
python windows/build_desktop.py --check
```

## تست‌ها

```sh
python windows/test_aurum_core.py
```

CI روی Windows، macOS و Linux، dependencyهای PyQt6 را نصب و import آن را همراه با compile-check و تست core بررسی می‌کند.

## محدودیت‌ها

- مسیر tick واقعی داخل کندل وجود ندارد؛ برخورد هم‌زمان SL/TP با SL-first مدل می‌شود.
- quote عمومی ممکن است با قیمت قابل اجرا اختلاف داشته باشد.
- Neural Lab فقط گزارش آموزشی deterministic و in-sample است؛ OOS یا سودآوری تضمین نمی‌کند.
- PDF خروجی یک گزارش متنی ساده با فونت استاندارد PDF است تا dependency runtime اضافه نشود.
- PyInstaller cross-compile نمی‌کند؛ EXE باید روی Windows، binary مک روی macOS و binary لینوکس روی Linux ساخته و تست شود.
