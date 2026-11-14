# Aurum Edge

اپلیکیشن اندروید **Aurum Edge** دارای ۴ صفحهٔ اصلی است:

1. **خانه (`HomeScreen`)** — نمایش وضعیت سشن بازار، ترکینگ پوزیشن‌های باز و خلاصهٔ پورتفو
2. **چارت (`ChartScreen`)** — چارت زندهٔ TradingView با ایچیموکو (`8 / 24 / 72`) و انتخاب نماد و تایم‌فریم
3. **ژورنال و ریسک (`JournalRiskScreen`)** — مدیریت سرمایه و سقف ضرر روزانه/کلی، ماشین‌حساب خودکار حجم/لات و نسبت R:R، ثبت معامله با رصد خودکار قیمت لحظه‌ای برای برخورد به TP/SL، و تقویم ماهانهٔ سود و زیان بر اساس روز
4. **بروزرسانی (`UpdateScreen`)** — بررسی، دانلود و نصب مستقیم نسخه‌های جدید APK از داخل برنامه

## نرم‌افزار بک‌تست ویندوزی — Python خالص، native 15m

برای برنامهٔ desktop جدید Windows که فقط با **Python + Tkinter** اجرا می‌شود و وابستگی به Qt، Android یا API صرافی ندارد، به
[`tools/windows_backtest_app/README_FA.md`](tools/windows_backtest_app/README_FA.md) بروید.
این deliverable فقط CSV پانزده‌دقیقه‌ای را به‌صورت native بررسی می‌کند، ورود next-open، SL-first، SL/TP و Smart Trend Exit دارد و هیچ time-stop 20 کندلی یا دادهٔ 30m/1H ندارد. برای ساخت محلی فایل EXE در Windows، روی
[`build_windows_backtest_app.bat`](tools/windows_backtest_app/build_windows_backtest_app.bat) دوبارکلیک کنید. این ابزار فقط بک‌تست تاریخی است و سفارش واقعی ارسال نمی‌کند.

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
