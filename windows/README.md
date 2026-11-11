# Aurum Edge — Native Windows x64

نسخهٔ مستقل ویندوز با **C++23 و Qt6**؛ APK، Emulator یا .NET استفاده نمی‌شود.

## وضعیت این برش

- [x] Core مستقل از Qt با C++23
- [x] Memory-mapped Dukascopy CSV و اعتبارسنجی داده
- [x] کندل‌های بسته و Resample بدون Look-Ahead
- [x] Ichimoku rolling با پیچیدگی O(n)
- [x] Backtest واقعی baseline با Fee، Slippage، Risk، Ruin و Equity DD
- [x] چارت Native و رابط RTL
- [x] Worker Thread برای CSV/Backtest
- [x] ژورنال SQLite واقعی
- [x] تست Core و Windows CI
- [ ] پورت کامل Peyman/NeoWave/Neural/Live Feed/Export/Updater

مدل فعلی عمداً `Ichimoku 8/24/72 TK-cross baseline` نام دارد و **ادعای برابری با Peyman یا سودآوری ندارد**.

## نیازمندی‌ها

- Windows 10/11 x64
- Visual Studio 2022 با Desktop development with C++
- CMake 3.28+
- Ninja
- Qt 6.8+ با `MSVC2022_64`

## Build با فایل آماده

روی ویندوز فایل `windows\build_windows.bat` را اجرا کنید. این فایل Visual Studio 2022، Kitهای Qt در درایوهای C/D و ابزارهای CMake/Ninja نصب‌شده توسط Qt را پیدا می‌کند، پروژه را در حالت Release می‌سازد و تست‌های Core را اجرا می‌کند. در Qt Maintenance Tool باید **MSVC 2022 64-bit** و **CMake/Ninja** نصب باشند. اگر Qt در مسیر غیرمعمول نصب است:

```bat
windows\build_windows.bat "D:\Qt\6.8.3\msvc2022_64"
```

خروجی در `windows\build-local\aurum_edge.exe` قرار می‌گیرد. این فایل Build و Test انجام می‌دهد و Installer تولید نمی‌کند.

## Build دستی

```powershell
git clone https://github.com/parsapeyman10/ichimoko.git
cd ichimoko
cmake -S windows -B windows/build -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  -DCMAKE_PREFIX_PATH="C:\Qt\6.8.3\msvc2022_64"
cmake --build windows/build --config Release
ctest --test-dir windows/build -C Release --output-on-failure
.\windows\build\aurum_edge.exe
```

### ساخت Installer

```powershell
cpack --config windows/build/CPackConfig.cmake -C Release
```

`windeployqt` DLLها و QML runtime را در پوشهٔ Install قرار می‌دهد. مسیر NSIS باید نصب باشد.

## تنظیم سرعت

- پیش‌فرض Release روی تمام CPUهای x64 سازگار است.
- `AURUM_NATIVE_TUNE=ON` فقط برای Build اختصاصی روی همان کامپیوتر است و نباید در Installer عمومی استفاده شود.
- AVX2/SIMD در مرحلهٔ بعد با Runtime Dispatch اضافه می‌شود؛ هیچ CPU خاصی پیش‌فرض اجباری نیست.
- مصرف تمام هسته‌ها فقط در Batch Backtest/Training موازی است؛ GUI و اتصال شبکه بی‌جهت Thread اشغال نمی‌کنند.

## آزمون Core بدون Qt

```bash
g++ -std=c++23 -O2 -Iwindows/src \
  windows/src/core/*.cpp windows/tests/CoreTests.cpp \
  -o /tmp/aurum-core-tests && /tmp/aurum-core-tests
```
