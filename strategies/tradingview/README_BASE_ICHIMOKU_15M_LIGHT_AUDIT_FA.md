# `BASE_ICHIMOKU` Lite برای TradingView / Pine Script v5 — native 15m

فایل TradingView این مسیر است:

- [`BASE_ICHIMOKU_15m_Light_Audit.pine`](BASE_ICHIMOKU_15m_Light_Audit.pine)

این یک strategy مستقل برای **TradingView Pine Script v5** است؛ فایل Lipi گوچارتینگ نیست. سفارش‌ها فقط در Strategy Tester شبیه‌سازی می‌شوند و هیچ API، کلید صرافی یا سفارش واقعی ندارند.

## نحوهٔ استفاده

1. در TradingView یک نمودار با interval دقیق **15m** باز کنید.
2. `Pine Editor` را باز کنید.
3. کل محتوای فایل `.pine` را paste کنید و روی `Add to chart` بزنید.
4. دست‌کم 80 کندل 15m برای warm-up داشته باشید. dashboard باید ابتدا `15m OK` و سپس `READY` را نشان دهد.

روی interval غیر 15m، strategy entry معتبر ایجاد نمی‌کند و dashboard عبارت `CHANGE TO 15m` را نشان می‌دهد.

این محیط به TradingView متصل نیست؛ بنابراین compile/run نهایی باید در Pine Editor حساب خودتان تأیید شود. در صورت خطا، متن خطا و screenshot را ارسال کنید تا همان خط اصلاح شود.

## منطق منتقل‌شده

نسخهٔ Pine همان قرارداد عملیاتی نسخهٔ Lipi را منتقل می‌کند:

- فقط کندل‌های native و بسته‌شدهٔ 15m؛ بدون `request.security`، resampling یا دادهٔ 30m/1H/HTF؛
- Ichimoku `9/26/52/26`، فاصلهٔ Tenkan/Kijun برای شروع `EXPANSION` و فاصلهٔ Kumo در محل فعلی قیمت؛
- triggerهای `KATANA`، `PULLBACK`، `BREAKOUT` و `EXPANSION`؛
- entry در next-open با `process_orders_on_close=false`؛
- یک lifecycle هم‌زمان و فقط یک entry هم‌جهت در هر موج تا reset ساختار native 15m؛
- SL-first در مدل تاریخی strategy engine، bracket ثابت SL/TP و فقط دو خط واقعی SL قرمز و TP سبز؛
- Smart Trend Exit بر مبنای Kijun، وضعیت Tenkan/Kijun، Kumo و جمع‌شدن فاصلهٔ Tenkan/Kijun؛
- بدون time-stop یا خروج اجباری 20 کندلی.

برای جلوگیری از ورود دیر به شروع موج، Kumo جلوتر و Chikou در امتیاز کیفیت باقی می‌مانند اما hard-block ورود نیستند. جایگاه قیمت نسبت به Kumo فعلی، جهت Tenkan/Kijun و expansion شرط‌های سخت entry هستند.

## تفاوت‌های پلتفرم

منطق محاسبات و تصمیم‌گیری در Pine به Lipi منتقل شده است، اما emulator سفارش و جزئیات intrabar TradingView و GoCharting مستقل‌اند. بنابراین هم‌خوانی بیت‌به‌بیت در تعداد/قیمت fill یا سود تضمین نمی‌شود و باید روی همان نماد، بازه، تنظیمات و هزینهٔ معاملاتی آزمون شود.

## محدودیت صریح

این strategy ابزار بک‌تست و audit است؛ نه توصیهٔ سرمایه‌گذاری، نه تضمین سود و نه اتصال اجرای معاملهٔ واقعی.
