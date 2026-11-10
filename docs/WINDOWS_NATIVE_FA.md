# Aurum Edge Windows x64 — معماری و نقشهٔ پورت

## تصمیم فنی

- **زبان:** C++23 استاندارد
- **رابط:** Qt6 / QML با رسم Native و RTL فارسی
- **Build:** CMake + Ninja + MSVC 2022 x64
- **دادهٔ محلی:** SQLite برای ژورنال و Memory-mapped CSV برای بک‌تست
- **اجرای موازی:** Worker Thread برای I/O و بک‌تست؛ Thread Pool برای Batch/Training در مراحل بعد
- **بهینه‌سازی Release:** `/O2 /GL /Gw /LTCG`
- **CPU Feature:** باینری عمومی x64 سازگار است؛ AVX2/AVX-512 فقط با Runtime Dispatch و Benchmark

## مرز نسخهٔ فعلی

این برش یک محصول کاملِ جایگزین Android نیست. مسیر واقعی زیر اجرا و تست شده است:

```text
Dukascopy CSV → Parse/Validation → Candle Series → Ichimoku → Backtest → Stats/Trade Ledger
                ↘ Native Candle Chart                 ↘ SQLite Journal
```

Core روی g++ با هشدارهای سخت‌گیرانه کامپایل و تست شده است. Qt SDK در محیط لینوکس فعلی موجود نیست؛ بنابراین Windows GUI/Installer باید با Windows CI و سپس روی دستگاه ویندوز تأیید شود. هیچ ادعای تولید EXE تست‌شده در این محیط وجود ندارد.

## معماری لایه‌ای

| لایه | مسیر | وظیفه |
|---|---|---|
| Domain/Core | `windows/src/core` | داده، سری، اندیکاتور، موتور بک‌تست؛ بدون وابستگی Qt |
| Application | `windows/src/app/AppController.*` | Use Case و هماهنگی Async |
| Infrastructure | `JournalStore.*` و آیندهٔ Network/Export | SQLite، فایل، شبکه |
| Presentation | `windows/qml` و `CandleChart.*` | UI و رسم Native |
| Build/Release | CMake و Windows CI | Test، windeployqt و NSIS |

Core نباید به UI، شبکه یا SQLite وابسته شود. بک‌تست نباید ژورنال واقعی را تغییر دهد.

## Feature Matrix پورت Android

| قابلیت Android | وضعیت Windows |
|---|---|
| CSV واقعی و حذف ردیف خراب/تکراری/حجم صفر | پیاده‌سازی و تست Core |
| مرز کندل بسته و جلوگیری از Look-Ahead | پیاده‌سازی و تست Core |
| چارت کندلی | Native اولیه |
| بک‌تست با هزینه، Slippage و Risk | Baseline واقعی؛ Peyman هنوز نه |
| آمار PF/Expectancy/Equity/DD/Trade Ledger | پیاده‌سازی اولیه |
| ژورنال پایدار | SQLite اولیه |
| Home/Chart/Backtest/Journal/Settings | Vertical Slice |
| ریسک‌کالکولاتور و VenueSpecs | حجم/کارمزد/اسپرد/اهرم/لیکوییدیشن و R:R با تست Core؛ دالان کامل Peyman هنوز نه |
| تقویم ماهانه و Risk Limits | تقویم ماهانه SQLite پیاده شد؛ Risk Limits هنوز نه |
| Live Quotes و Background Monitoring | Quote عمومی Async با Timer اختیاری؛ Background Service و Backfill هنوز نه |
| تمام استراتژی‌های Ichimoku/Peyman | کف ساختاری Stop پورت و تست شد؛ ارزیابی کامل Peyman هنوز نه |
| NeoWave و MTF Confluence | الگوهای Impulse/ZigZag/Flat/Triangles/Diametric/Symmetrical، Gate و MTF پورت و تست شد؛ Golden Test مشترک Kotlin ادامه دارد |
| Neural Learning و Ledger جداگانه | اتاق تمرین Baseline جدا و قطعی؛ شبکه و Ledger کامل Android هنوز نه |
| AI Trader/News | پس از قرارداد Network و Privacy |
| TradePlanGuard/EntrySafetyGate | پیش از هر Auto Paper Trade |
| Archive CSV/JSON/PDF | Archive اتمیک کندل CSV/JSON و ژورنال CSV/JSON/PDF |
| Notifications | Windows Toast |
| Update | بررسی Async نسخهٔ GitHub و باز کردن Release؛ نصب امضاشده/Authenticode هنوز نه |
| مانیتورینگ سرعت و Resource Governor | Benchmark و مرحلهٔ Performance |

## قواعد پورت موتورها

1. برای هر تابع Kotlin یک Golden Dataset مشترک ساخته می‌شود.
2. ورودی و خروجی Kotlin/C++ باید در تلورانس مشخص برابر باشند؛ «شبیه بودن» کافی نیست.
3. هیچ بهینه‌سازی SIMD/Parallel نباید ترتیب حساس به شناور را بدون تست تغییر دهد.
4. Backtest با ژورنال و Neural واقعی هیچ نوشتن مشترکی ندارد.
5. هیچ ویژگی ناقصی با دادهٔ ساختگی یا نتیجهٔ ثابت نمایش داده نمی‌شود.

## برنامهٔ سرعت

- Rolling Deque برای اندیکاتورها به‌جای اسکن مکرر پنجره
- Binary Search برای مرز کندل بسته
- Memory Map و `from_chars` برای CSV
- Worker Thread برای عدم قفل‌شدن UI
- سقف ۱۵۰۰ کندل در View؛ محاسبه روی تمام داده
- در Batch، موازی‌سازی روی Runهای مستقل نه داخل State Machine یک معامله
- Runtime CPUID برای AVX2؛ مسیر Scalar همیشه حفظ می‌شود
- Benchmark با زمان، Memory و نتیجهٔ یکسان؛ هیچ شاخص سرعت ساختگی وجود ندارد

## مرحلهٔ بعد

1. تأیید Windows CI و اجرای QML روی Windows.
2. Golden Test قرارداد `BacktestCsvReader` و `BacktestSeries` با Kotlin.
3. پورت `VenueSpecs` و `TradePlanGuard`.
4. پورت موتور بیس و سپس Peyman با تست برابری.
5. ژورنال کامل، Live Feed و سایر صفحات طبق Matrix.
