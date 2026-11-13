# `BASE_ICHIMOKU` Lite برای GoCharting / Lipi v2 — فقط چارت 15 دقیقه

فایل عملیاتی این مسیر فقط این است:

- [`BASE_ICHIMOKU_15m_30m_1h_Light_Audit.lipi`](BASE_ICHIMOKU_15m_30m_1h_Light_Audit.lipi)

این یک **strategy تشخیصی و بک‌تست GoCharting** است. سفارش‌ها فقط در شبیه‌ساز strategy ثبت می‌شوند و به صرافی، Binance، حساب کاربر یا API معاملهٔ واقعی ارسال نمی‌شوند.

> این نسخه عمداً Lite است و ادعای parity بیت‌به‌بیت با نسخهٔ اصلی چهارلایهٔ `BASE_ICHIMOKU` ندارد. مسیرهای `Hidden Cross`، `Parsa`، `BTC-52`، `Peyman` و `NeoWave` در آن نیستند؛ همچنین نتیجهٔ آن نباید به‌عنوان سودآوری تضمین‌شده یا مجوز معاملهٔ واقعی تفسیر شود.

## محدودهٔ قطعی

- **فقط interval استاندارد 15m** پذیرفته است؛ چارت‌های 5m، 30m، 1H، Renko و Range ورودی معتبر این فایل نیستند.
- اگر interval صحیح نباشد، ردیف اول dashboard صریحاً `CHANGE TO 15m` را نمایش می‌دهد و هیچ entry معتبری ساخته نمی‌شود.
- زنجیرهٔ تصمیم‌گیری دقیقاً این است: **15m trigger/confirmation → 30m strategy → 1H macro**.
- 30m و 1H درون خود اسکریپت و فقط از کندل‌های 15m کامل ساخته می‌شوند. درخواست timeframe خارجی یا look-ahead وجود ندارد.
- مسیرهای قبلی 5 دقیقه‌ای از تحویل عملیاتی کنار گذاشته شده‌اند.

## افزودن به GoCharting

1. یک نماد با دادهٔ پیوسته باز کنید و interval را دقیقاً روی **`15m`** بگذارید.
2. دست‌کم **230 کندل 15m پیوسته** بارگذاری کنید؛ بهتر است تاریخچهٔ بیشتری داشته باشید. warm-up به 57 کندل 1H کامل نیاز دارد.
3. Script Editor را باز کنید، کل محتوای فایل `.lipi` را paste کنید، ذخیره و به چارت اضافه کنید.
4. جدول بالا-راست را بررسی کنید: ابتدا `15m OK` و پس از warm-up، `READY` ظاهر می‌شود. `WARM-UP: need 57 H1` در شروع طبیعی است.
5. bucketهای 30m و 1H با دقیقهٔ UTC بسته می‌شوند (`:00/:15/:30/:45`). اگر تاریخچه از وسط یک bucket شروع شود، آن bucket عمداً استفاده نمی‌شود.

این محیط به GoCharting متصل نیست؛ بنابراین compile و اجرای نهایی باید در Script Editor خود GoCharting تأیید شود. اگر خطایی دیدید، متن کامل خطا، نام نماد، interval و screenshot چارت را ارسال کنید.

## بدون look-ahead

اسکریپت فقط وقتی `barstate.isconfirmed` است داده را مصرف می‌کند:

- یک 30m پس از دو کندل کامل 15m (`:00 + :15` یا `:30 + :45`) منتشر می‌شود.
- یک 1H پس از چهار کندل کامل 15m (`:00` تا `:45`) منتشر می‌شود.
- snapshotهای 30m/1H تا بسته‌شدن کامل bucket بعدی به‌روزرسانی نمی‌شوند.
- trigger و confirmation بومی 15m نیز فقط روی candle تأییدشده ارزیابی می‌شوند.

پس هیچ کندل HTF در حال ساخت و هیچ قیمت آینده‌ای وارد شرط ورود نمی‌شود.

## چرخهٔ تک‌معامله‌ای

در هر لحظه فقط یک lifecycle مجاز است:

1. signal روی close کندل 15m سفارش next-open را submit می‌کند.
2. از همان لحظه state به `LOCKED: ONE TRADE` می‌رود؛ signalهای Long/Short بعدی تا پایان چرخه نادیده گرفته می‌شوند.
3. بعد از fill شدن سفارش، همان lock در تمام مدت position باقی می‌ماند.
4. lock تنها بعد از بسته‌شدن کامل position آزاد می‌شود؛ بنابراین pyramiding، ورود هم‌جهت تکراری و reversal هم‌زمان رخ نمی‌دهد.

مثلث `LITE L` یا `LITE S` فقط روی **fill واقعیِ پذیرفته‌شده** رسم می‌شود، نه صرفاً روی signal submit شده و نه روی signal تکراریِ زمان lock. در صورت fill و exit در یک candle تاریخی، همان معامله نیز با fallback مربوط به closed trade ثبت و نشان داده می‌شود.

## نمایش واقعی Entry / SL / TP

برای هر معاملهٔ واقعی سه drawing روی چارت ساخته می‌شود:

| رنگ/شکل | معنا |
|---|---|
| خط نقطه‌چین نقره‌ای | قیمت fill واقعی `strategy.position_avg_price` |
| خط قرمز | `stop` دقیق ارسال‌شده به `strategy.exit` |
| خط سبز | `limit` دقیق ارسال‌شده به `strategy.exit` |

قیمت‌های SL و TP در candle signal محاسبه و هنگام submit در `pendingStopPrice` و `pendingTargetPrice` **freeze** می‌شوند. پس از آن‌که GoCharting fill را گزارش کرد، همان دو مقدار—بدون محاسبهٔ دوباره—به `strategy.exit` ارسال و در کل زمان position با همان مقدار نگه‌داری می‌شوند. بنابراین اگر next-open با gap پر شود، خط Entry قیمت fill شبیه‌ساز را نشان می‌دهد، اما خطوط قرمز/سبز دقیقاً stop/limitهای واقعی همان bracket هستند.

هر سه خط از bar fill تا bar خروج امتداد دارند و پس از خروج حذف نمی‌شوند. label ورود نیز Entry/SL/TP همان معامله را نشان می‌دهد. برای کنترل محدودیت drawingهای GoCharting، **150 bracket کامل آخر** (450 خط و 150 label) نگه‌داری می‌شود؛ با ورود bracket جدیدتر، قدیمی‌ترین bracket حذف می‌شود. این محدودیت جلوی شلوغی و عبور از سقف objectهای پلتفرم را می‌گیرد.

خروج time-stop نیز خط‌ها را در bar خروج پایان می‌دهد؛ اما SL/TP نمایش‌داده‌شده همچنان همان قیمت‌های bracket ارسال‌شده به `strategy.exit` هستند.

## چارت خلوت و dashboard

به‌صورت پیش‌فرض فقط سه guide ایچیموکو باقی می‌ماند:

- Kijun بومی 15m (نارنجی)
- Kijun ساخته‌شدهٔ 30m (سبزآبی step)
- Kijun ساخته‌شدهٔ 1H (بنفش step)

نشانگرهای diagnostic خام (`RAW` و `LOCK`) به‌صورت پیش‌فرض خاموش‌اند. فقط برای عیب‌یابی، گزینهٔ `DEBUG: show raw triggers rejected by gate` را فعال کنید. گزینهٔ `DEBUG ONLY: simulate raw triggers` نیز معامله‌های آزمایشی ایجاد می‌کند و نباید برای ارزیابی BASE یا سودآوری به‌کار رود.

| ردیف dashboard | معنا |
|---|---|
| `15m OK` / `CHANGE TO 15m` | اعتبار timeframe چارت |
| `Closed HTF bars` | تعداد کندل‌های 30m و 1H کامل ساخته‌شده |
| `Data` | وضعیت warm-up یا آماده بودن داده |
| `Bias` | جهت لایه‌های macro/strategy |
| `Trigger` | Katana، Pullback یا Breakout فعلی |
| `Conditions` و `Book gate` | گذر 17 شرط Lite و امتیاز gate |
| `LOCKED: ONE TRADE` | سفارش pending یا position باز است؛ ورود جدید مجاز نیست |

## منطق Lite و تنظیمات

این نسخه از Ichimoku `9/26/52/26`، ATR14، کیفیت candle/volume، Chikou، Kumo، hard-vetoهای `1/3/4/6/17` و triggerهای `Katana`، `Pullback` و `Breakout` استفاده می‌کند. هدف پیش‌فرض `2.25R` است و corridor R:R بین `1.5R` تا `3R` کنترل می‌شود.

`Round-trip cost fraction` به‌طور پیش‌فرض `0.0021` است و `commission_value=0.105` نیز تنها تقریب هزینه برای گزارش بک‌تست است. برای نماد یا broker دیگر، هزینه و quantity را با واقعیت همان بازار تنظیم کنید. تغییر تنظیمات، مقایسه با baseline مرجع را تغییر می‌دهد.

> هشدار: این ابزار توصیهٔ سرمایه‌گذاری نیست. بک‌تست، markerها و خطوط bracket برای visual audit هستند، نه اتصال یا مجوز اجرای معاملهٔ واقعی.
