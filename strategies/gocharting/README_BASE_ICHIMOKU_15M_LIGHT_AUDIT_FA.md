# `BASE_ICHIMOKU` Lite برای GoCharting / Lipi v2 — فقط محاسبات native 15m

فایل عملیاتی این مسیر فقط این است:

- [`BASE_ICHIMOKU_15m_Light_Audit.lipi`](BASE_ICHIMOKU_15m_Light_Audit.lipi)

این یک **strategy تشخیصی و بک‌تست GoCharting** است. سفارش‌ها فقط در شبیه‌ساز strategy ثبت می‌شوند و به صرافی، Binance، حساب کاربر یا API معاملهٔ واقعی ارسال نمی‌شوند.

> این نسخه عمداً Lite و کاملاً native-15m است. ادعای parity بیت‌به‌بیت با نسخهٔ اصلی چهارلایهٔ `BASE_ICHIMOKU` ندارد و نتیجهٔ آن نباید سودآوری تضمین‌شده یا مجوز معاملهٔ واقعی تلقی شود.

## محدودهٔ قطعی

- فقط interval استاندارد **15m** پذیرفته است. روی interval دیگر dashboard عبارت `CHANGE TO 15m` را نشان می‌دهد و هیچ entry معتبری ایجاد نمی‌شود.
- **هیچ اطلاعات یا کندل 30m، 1H یا timeframe دیگری ساخته، محاسبه یا مصرف نمی‌شود.** هیچ آرایه، bucket یا resampling مربوط به timeframe بالاتر در فایل وجود ندارد.
- تمام bias، confirmation، trigger، gate و محاسبهٔ SL/TP فقط از کندل‌های کامل و native چارت 15m به‌دست می‌آیند.
- مسیرهای قبلی 5 دقیقه‌ای از تحویل عملیاتی کنار گذاشته شده‌اند.

## افزودن به GoCharting

1. یک نماد با دادهٔ پیوسته باز کنید و interval را دقیقاً روی **`15m`** بگذارید.
2. دست‌کم **80 کندل 15m** بارگذاری کنید؛ دادهٔ بیشتر برای بک‌تست بهتر است.
3. Script Editor را باز کنید، کل محتوای فایل `.lipi` را paste کنید، ذخیره و به چارت اضافه کنید.
4. جدول بالا-راست باید ابتدا `15m OK` و پس از warm-up، `READY` را نشان دهد. `WARM-UP: need 80 x 15m` در شروع طبیعی است.

محاسبه و signal فقط وقتی `barstate.isconfirmed` است انجام می‌شود؛ بنابراین از کندل نیمه‌کاره یا look-ahead استفاده نمی‌کند.

این محیط به GoCharting متصل نیست؛ compile و اجرای نهایی باید در Script Editor خود GoCharting تأیید شود. در صورت خطا، متن کامل خطا، نام نماد، interval و screenshot را ارسال کنید.

## چرخهٔ تک‌معامله‌ای

در هر لحظه فقط یک lifecycle مجاز است:

1. signal روی close کندل 15m سفارش next-open را submit می‌کند.
2. از همان لحظه dashboard وضعیت `LOCKED: ONE TRADE` را نشان می‌دهد و signalهای بعدی نادیده گرفته می‌شوند.
3. lock در تمام مدت position باقی می‌ماند.
4. تنها پس از بسته‌شدن کامل position آزاد می‌شود؛ بنابراین pyramiding، ورود تکراری و reversal هم‌زمان انجام نمی‌شود.

اسکریپت marker ورود جداگانه رسم نمی‌کند تا با marker داخلی GoCharting دوباره‌کاری نشود. `Long` یا `Short` و `Exit` که GoCharting نشان می‌دهد، به‌ترتیب ورود و خروج همان یک معامله‌اند؛ دو معاملهٔ هم‌زمان نیستند.

## فقط خطوط واقعی SL و TP

روی چارت فقط دو خط مربوط به معامله رسم می‌شود:

| رنگ | معنا |
|---|---|
| قرمز | `stop` دقیق ارسال‌شده به `strategy.exit` |
| سبز | `limit` دقیق ارسال‌شده به `strategy.exit` |

خط Entry، Tenkan/Kijun، Kumo و هر label شامل قیمت یا محاسبات رسم نمی‌شود. SL و TP روی کندل signal محاسبه و در `pendingStopPrice` و `pendingTargetPrice` freeze می‌شوند. پس از گزارش fill توسط GoCharting، همان دو مقدار بدون باز‌محاسبه به `strategy.exit` فرستاده می‌شوند.

دو خط از bar ورود تا bar خروج امتداد دارند و بعد از خروج در تاریخچه باقی می‌مانند. برای رعایت سقف objectهای GoCharting، 200 bracket کامل آخر (400 خط) حفظ می‌شود؛ با معاملهٔ جدیدتر، قدیمی‌ترین جفت SL/TP حذف می‌شود.

## R:R پیش از اندازهٔ معامله

ورودی `Target R:R (set before backtest quantity)` پیش از ورودی quantity قرار دارد. ابتدا R:R دلخواه را در بازهٔ `1.50` تا `3.00` انتخاب کنید و سپس `Backtest quantity (set after R:R)` را برای اندازهٔ معامله وارد کنید. مقدار پیش‌فرض `2.25` است و همان مقدار TP bracket را تعیین می‌کند.

## چارت خلوت و dashboard

روی نمودار فقط کندل‌ها، marker داخلی ورود/خروج GoCharting و خطوط SL/TP دیده می‌شوند. هیچ marker تکراریِ اسکریپت، خط indicator، خط Entry، label قیمت یا متن محاسبات نمایش داده نمی‌شود. خط 30m، خط 1H، جدول شمارش HTF و محاسبهٔ HTF نیز وجود ندارد. نشانگرهای diagnostic خام (`RAW` و `LOCK`) پیش‌فرض خاموش هستند؛ فقط برای عیب‌یابی گزینهٔ `DEBUG: show raw triggers rejected by gate` را فعال کنید.

| ردیف dashboard | معنا |
|---|---|
| `15m OK` / `CHANGE TO 15m` | اعتبار timeframe چارت |
| `Data` | وضعیت warm-up native 15m |
| `Bias` | جهت native 15m |
| `Trigger` | Katana، Pullback یا Breakout |
| `Conditions` و `Book gate` | گذر 17 شرط Lite و امتیاز gate |
| `Model` | تأیید `NATIVE 15m ONLY` |
| `LOCKED: ONE TRADE` | سفارش pending یا position باز است |

## منطق و تنظیمات Lite

نسخهٔ native-15m از Ichimoku `9/26/52/26`، ATR14، کیفیت candle/volume، Chikou، Kumo، hard-vetoهای `1/3/4/6/17` و triggerهای `Katana`، `Pullback` و `Breakout` استفاده می‌کند. R:R توسط کاربر، پیش از تعیین quantity، در بازهٔ `1.5R` تا `3R` انتخاب می‌شود؛ پیش‌فرض `2.25R` است.

`Round-trip cost fraction` به‌طور پیش‌فرض `0.0021` است و `commission_value=0.105` صرفاً تقریب هزینه در گزارش بک‌تست است. quantity، هزینه، ATR-extension و SL buffer از تنظیمات Lipi قابل تغییرند. هیچ time-stop یا خروج اجباری بعد از 20 کندل در این نسخه وجود ندارد؛ position فقط با SL یا TP شبیه‌سازی‌شده بسته می‌شود. تغییر تنظیمات، مقایسه با baseline مرجع را تغییر می‌دهد.

> هشدار: این ابزار توصیهٔ سرمایه‌گذاری نیست. بک‌تست، markerها و خطوط SL/TP صرفاً برای visual audit هستند، نه اتصال یا مجوز اجرای معاملهٔ واقعی.
