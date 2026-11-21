# Aurum Edge

اپلیکیشن اندروید **Aurum Edge** دارای ۴ صفحهٔ اصلی است:

1. **خانه (`HomeScreen`)** — نمایش وضعیت سشن بازار، ترکینگ پوزیشن‌های باز و خلاصهٔ پورتفو
2. **چارت (`ChartScreen`)** — چارت زندهٔ TradingView با ایچیموکو (`8 / 24 / 72`) و انتخاب نماد و تایم‌فریم
3. **ژورنال و ریسک (`JournalRiskScreen`)** — مدیریت سرمایه و سقف ضرر روزانه/کلی، ماشین‌حساب خودکار حجم/لات و نسبت R:R، ثبت معامله با رصد خودکار قیمت لحظه‌ای برای برخورد به TP/SL، و تقویم ماهانهٔ سود و زیان بر اساس روز
4. **بروزرسانی (`UpdateScreen`)** — بررسی، دانلود و نصب مستقیم نسخه‌های جدید APK از داخل برنامه

## اپلیکیشن desktop Aurum Edge — Python خالص و چندسکویی

نسخهٔ desktop اصلی در [`windows/README.md`](windows/README.md) با **Python + PyQt6** برای Windows، macOS و Linux پیاده‌سازی شده است؛ رابط آن با widgetهای PyQt6 و `QPainter` بازنویسی شده و هیچ C++ source، CMake، Ninja یا Visual Studio در این deliverable وجود ندارد. برنامه CSV/چارت، بک‌تست Ichimoku baseline، ژورنال SQLite، تقویم، ریسک، NeoWave، Neural Lab، quote عمومیِ صرفاً مشاهده‌ای و بررسی دستی Release را ارائه می‌دهد. برای اجرای source ابتدا `python -m pip install -r windows/requirements.txt` را اجرا کنید؛ سپس در Windows فایل [`windows/build_windows.bat`](windows/build_windows.bat) و برای macOS/Linux فایل [`windows/build_desktop.sh`](windows/build_desktop.sh) را برای ساخت native اجرا کنید. هر سیستم‌عامل باید artifact native خود را بسازد؛ یک EXE واحد قابل اجرای همهٔ سیستم‌عامل‌ها نیست. این برنامه historical-analysis و paper-journal است و سفارش واقعی نمی‌فرستد.

## ابزار مستقل بک‌تست native 15m — Python خالص

ابزار تخصصی CSV پانزده‌دقیقه‌ای در [`tools/windows_backtest_app/README_FA.md`](tools/windows_backtest_app/README_FA.md) قرار دارد. این ابزار next-open، SL-first، SL/TP و Smart Trend Exit را روی دادهٔ native 15m بررسی می‌کند و هیچ time-stop 20 کندلی یا دادهٔ 30m/1H ندارد. برای ساخت EXE آن، [`build_windows_backtest_app.bat`](tools/windows_backtest_app/build_windows_backtest_app.bat) را اجرا کنید. این ابزار نیز فقط بک‌تست تاریخی است و سفارش واقعی ارسال نمی‌کند.

## شبیه‌ساز مستقل BASE_ICHIMOKU چهارگانه (قدیمی‌تر)

این ابزار قدیمی‌تر از 1H و 4H resample‌شده استفاده می‌کند؛ بنابراین برای deliverable جدید **native 15m only** جایگزین یا dependency نیست. برای بک‌تست CSV پانزده‌دقیقه‌ای GoCharting با مدل خالص چهارگانهٔ `BASE_ICHIMOKU`، راهنمای
[`tools/base_ichimoku_simulator/README_FA.md`](tools/base_ichimoku_simulator/README_FA.md) را ببینید.
این ابزار پایتون مستقل فقط شبیه‌ساز تاریخی است و به صرافی سفارش واقعی نمی‌فرستد.

## بازبینی تصویری در GoCharting

راهنمای اجرای Lipi v2 در GoCharting در
[`strategies/gocharting/README_BASE_ICHIMOKU_15M_LIGHT_AUDIT_FA.md`](strategies/gocharting/README_BASE_ICHIMOKU_15M_LIGHT_AUDIT_FA.md)
آمده است. فایل عملیاتی سبک فقط برای چارت **15m**،
[`BASE_ICHIMOKU_15m_Light_Audit.lipi`](strategies/gocharting/BASE_ICHIMOKU_15m_Light_Audit.lipi)،
تمام محاسبات را فقط از کندل‌های native 15m انجام می‌دهد و هیچ داده یا محاسبهٔ 30m/1H ندارد. فقط خطوط واقعی SL قرمز و TP سبز را روی چارت نشان می‌دهد؛ خط Entry، indicator و label محاسباتی ندارد و R:R نیز پیش از quantity توسط کاربر تعیین می‌شود. این strategy فقط بک‌تست/شبیه‌سازی است و سفارش واقعی ارسال نمی‌کند.

## TradingView Pine Script v5 — `BASE Main-Core native 15m`

نسخهٔ Pine Script v5 برای TradingView در
[`strategies/tradingview/README_BASE_ICHIMOKU_MAIN_CORE_NATIVE_15M_FA.md`](strategies/tradingview/README_BASE_ICHIMOKU_MAIN_CORE_NATIVE_15M_FA.md)
قرار دارد. این strategy مستقل از Lipi، هستهٔ قابل‌انتقال `BASE_ICHIMOKU` را فقط از کندل‌های native 15m محاسبه می‌کند: Kumo/TK/Sanyaku، آیندهٔ Kumo، Chikou، Kijun/C-Clamp، SSB flat، ER14، candle/volume، Hosoda guard، Sniper pocket، R:R و execution economics. نسخهٔ دقیق Main چهارلایه نیست و هیچ داده یا منطق 1H/4H، `request.security` یا resampling ندارد. فقط برای Strategy Tester/بک‌تست است و به صرافی سفارش واقعی نمی‌فرستد.

## TradingView Pine Script v5 — `Ichimoku Book Synthesis`

استراتژی مستقلِ ساخته‌شده فقط از یافته‌های قابل‌صورت‌بندی کتاب‌های trading در Drive در
[`strategies/tradingview/README_ICHIMOKU_BOOK_SYNTHESIS_FA.md`](strategies/tradingview/README_ICHIMOKU_BOOK_SYNTHESIS_FA.md)
قرار دارد. این فایل از `BASE_ICHIMOKU` یا strategyهای دیگر dependency نمی‌گیرد: context استاندارد Ichimoku، clearance Chikou، BB squeeze breakout، RSI14/EMA89/SMA200، Kijun reclaim و bracket ثابت Kijun/ATR با هدف 2R تا 3R را در یک strategy مستقل برای Strategy Tester پیاده می‌کند. به سفارش واقعی متصل نیست و نتیجهٔ آن باید روی داده و هزینهٔ واقعی هر venue جداگانه بک‌تست شود.
