# Ichimoku Book Synthesis — Squeeze & Reclaim

فایل قابل paste در TradingView / Pine Script v5:

- [`ICHIMOKU_BOOK_SYNTHESIS_STRATEGY.pine`](ICHIMOKU_BOOK_SYNTHESIS_STRATEGY.pine)

این یک strategy تازه و مستقل برای **Strategy Tester** است. هیچ بخشی از منطق strategyهای قبلی را فراخوانی یا به آن‌ها وابسته نمی‌کند. هیچ alert-execution، API یا سفارش واقعی ندارد.

## منشأ قواعد

این نسخه فقط از ایده‌های قابل‌صورت‌بندی در منابع trading موجود در Drive ساخته شده است؛ متن کتاب‌ها کپی نشده و کتاب‌ها به‌عنوان تضمین نتیجه تلقی نشده‌اند:

| منبع بررسی‌شده | ایده‌ای که به قانون مکانیکی تبدیل شده |
|---|---|
| *Trading with Ichimoku Clouds* — Manesh Patel | هم‌گرایی Kumo / TK / Chikou، نقش Kijun به‌عنوان تعادل و سطح ریسک |
| *Ichimoku Charts* — Nicole Elliott | استفاده از Kumo در بازار رونددار، فاصله از تعادل و اهمیت Chikou |
| *Trading with Ichimoku* — Karen Péloille | هم‌زمانی خطوط Ichimoku، pullback به Tenkan/Kijun و بازپس‌گیری روند |
| *Ichimoku Charting & Technical Analysis* | روند نسبت به Kumo، جهت Kijun، و پرهیز از signal تک‌خطی |
| راهنمای Ichimoku Cloud | breakout از Kumo و pullback/reclaim به Kumo یا Kijun |
| راهنمای Ichimoku + Bollinger | BB squeeze، EMA89/SMA200، RSI14 نسبت به خط 50، و کندل breakout قوی |
| برگهٔ Ichimoku Cross System | stop پشت Kijun با بافر ATR و هدف R ثابت |

برگهٔ mechanical cross صراحتاً برای USDJPY در H1 نوشته شده بود؛ بنابراین این فایل ادعا نمی‌کند اعداد آن برای همهٔ دارایی‌ها یا تایم‌فریم‌ها اعتبارسنجی شده‌اند. به همین دلیل اعداد اصلی input هستند و خروجی فقط نتیجهٔ بک‌تست است، نه وعدهٔ عملکرد.

## قانون یکپارچه

### 1. جهت و context

Long فقط وقتی ارزیابی می‌شود که همهٔ موارد زیر هم‌جهت باشند؛ برای Short قرینهٔ آن برقرار است:

1. close بالای Kumo قابل‌مشاهده و Span A بالای Span B باشد؛
2. Tenkan بالای Kijun باشد و هر دو نسبت به کندل قبل نزولی نباشند؛
3. RSI14 بالای 50 باشد؛
4. در حالت پیش‌فرض، close بالای EMA89 و SMA200 باشد؛
5. در حالت پیش‌فرض، Chikou نسبت به corridor هفت‌کندلی قیمت در 26 کندل قبل فضای باز داشته باشد.

هر دو فیلتر MA و Chikou از Inputs خاموش‌شدنی‌اند تا بتوان اثر آن‌ها را در بک‌تست جداگانه اندازه گرفت؛ پیش‌فرض strategy آن‌ها را فعال نگه می‌دارد.

### 2. دو نوع ورود

پس از context، حداقل یکی از دو trigger زیر باید رخ دهد:

- **Squeeze breakout**: BB width به حداکثر `0.75` از میانگین width بیست‌کندلی خودش فشرده شده، فشردگی تمام شده و حداکثر تا شش کندل بعد، یک کندل با بدنهٔ قوی از سقف/کف ثبت‌شدهٔ همان squeeze عبور کند. close باید در جهت Kumo آینده نیز باشد.
- **Kijun reclaim**: در روند موجود، low/high سه کندل اخیر ناحیهٔ Tenkan/Kijun را لمس کرده و اکنون یک کندل قوی با شکست high/low کندل قبل، Tenkan را در جهت روند بازپس می‌گیرد.

کندل قوی به‌طور پیش‌فرض حداقل `55%` بدنه نسبت به range و حداکثر `25%` wick مخالف دارد. این تعریف مکانیکی از «breakout واضح» است؛ ادعای تشخیص کامل الگوهای بصری نیست.

### 3. ریسک و خروج

- ورود در close کندل سیگنال در مدل Strategy Tester ثبت می‌شود (`process_orders_on_close=true`).
- SL در Long: `Kijun − 0.40 × ATR14`؛ در Short قرینه است.
- TP در همان لحظه قفل می‌شود؛ پیش‌فرض `2.50R` و بازهٔ input سخت `2R..3R` است.
- فقط یک position می‌تواند هم‌زمان باز باشد (`pyramiding=0`).
- خروج discretionary، time-stop، افزودن پله‌ای و جابه‌جایی خودکار SL در این نسخه وجود ندارد؛ فقط bracket ثابت SL/TP وجود دارد.

`Backtest quantity` عمداً مقدار ثابت است. کتاب‌ها به ریسک 1٪ تا 2٪ اشاره دارند، اما تبدیل عمومی آن به تعداد قرارداد بدون دانستن ارزش هر tick/point و currency قرارداد غلط است. quantity و commission/slippage را در Strategy Properties متناسب با venue خود تنظیم کنید.

## استفاده

1. فایل Pine را در Pine Editor paste کنید و `Add to chart` بزنید.
2. حداقل 250 کندل برای warm-up EMA89/SMA200 بدهید.
3. مقدار واقعی commission، slippage و اندازهٔ قرارداد را در Strategy Properties وارد کنید؛ فایل مقدار هزینهٔ ساختگی ندارد.
4. ابتدا روی یک نماد، یک session rule و یک timeframe ثابت بک‌تست کنید؛ سپس دوره‌ها را جداگانه به in-sample و out-of-sample تقسیم کنید.
5. Inputs پیش‌فرض را پس از دیدن نتیجه تغییر ندهید تا صرفاً بهترین گذشته را شکار نکنید. هر تغییر باید روی نمونهٔ خارج از نمونه نیز ثبت و ارزیابی شود.

## پنل audit

پنل بالا-راست فقط کندل آخر را توضیح می‌دهد:

- `Cloud`، `TK / RSI`، `Chikou` و `Long-term MA`: context فعلی؛
- `Base = SQUEEZE`: فشردگی در حال ساخت است؛
- `Base = RELEASED`: پنجرهٔ breakout هنوز باز است؛
- `Entry mode`: مشخص می‌کند trigger فعلی squeeze breakout یا Kijun reclaim بوده است؛
- `State = SIGNAL`: همهٔ شروط entry در آخرین bar بسته‌شده پاس شده‌اند.

اگر می‌خواهید کندل تاریخی را بررسی کنید، از Bar Replay استفاده کنید؛ پنل در انتهای chart دربارهٔ setup قدیمی verdict نمی‌دهد.

این ابزار آموزشی و بک‌تستی است، توصیهٔ سرمایه‌گذاری یا تضمین سود نیست.
