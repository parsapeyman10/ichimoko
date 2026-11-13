# Aurum Edge

اپلیکیشن اندروید **Aurum Edge** دارای ۴ صفحهٔ اصلی است:

1. **خانه (`HomeScreen`)** — نمایش وضعیت سشن بازار، ترکینگ پوزیشن‌های باز و خلاصهٔ پورتفو
2. **چارت (`ChartScreen`)** — چارت زندهٔ TradingView با ایچیموکو (`8 / 24 / 72`) و انتخاب نماد و تایم‌فریم
3. **ژورنال و ریسک (`JournalRiskScreen`)** — مدیریت سرمایه و سقف ضرر روزانه/کلی، ماشین‌حساب خودکار حجم/لات و نسبت R:R، ثبت معامله با رصد خودکار قیمت لحظه‌ای برای برخورد به TP/SL، و تقویم ماهانهٔ سود و زیان بر اساس روز
4. **بروزرسانی (`UpdateScreen`)** — بررسی، دانلود و نصب مستقیم نسخه‌های جدید APK از داخل برنامه

## شبیه‌ساز مستقل BASE_ICHIMOKU

برای بک‌تست CSV پانزده‌دقیقه‌ای GoCharting با مدل خالص چهارگانهٔ `BASE_ICHIMOKU`، راهنمای
[`tools/base_ichimoku_simulator/README_FA.md`](tools/base_ichimoku_simulator/README_FA.md) را ببینید.
این ابزار پایتون مستقل فقط شبیه‌ساز تاریخی است و به صرافی سفارش واقعی نمی‌فرستد.

## بازبینی تصویری در GoCharting

راهنمای اجرای Lipi v2 در GoCharting در
[`strategies/gocharting/README_BASE_ICHIMOKU_15M_LIGHT_AUDIT_FA.md`](strategies/gocharting/README_BASE_ICHIMOKU_15M_LIGHT_AUDIT_FA.md)
آمده است. فایل عملیاتی سبک فقط برای چارت **15m**،
[`BASE_ICHIMOKU_15m_Light_Audit.lipi`](strategies/gocharting/BASE_ICHIMOKU_15m_Light_Audit.lipi)،
تمام محاسبات را فقط از کندل‌های native 15m انجام می‌دهد و هیچ داده یا محاسبهٔ 30m/1H ندارد. فقط خطوط واقعی SL قرمز و TP سبز را روی چارت نشان می‌دهد؛ خط Entry، indicator و label محاسباتی ندارد و R:R نیز پیش از quantity توسط کاربر تعیین می‌شود. این strategy فقط بک‌تست/شبیه‌سازی است و سفارش واقعی ارسال نمی‌کند.
