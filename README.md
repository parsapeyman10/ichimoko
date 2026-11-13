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

برای دیدن markerهای سیگنال/ردشدن setup روی خود چارت 15 دقیقه‌ای GoCharting با Lipi v2، فایل
[`strategies/gocharting/README_BASE_ICHIMOKU_4TF_15M_AUDIT_FA.md`](strategies/gocharting/README_BASE_ICHIMOKU_4TF_15M_AUDIT_FA.md)
و اسکریپت تشخیصی همراه آن را ببینید. این strategy فقط بک‌تست/شبیه‌سازی است و سفارش واقعی ارسال نمی‌کند.
