# زیر و بم «استراتژی بیس» — `BASE_ICHIMOKU`

> مرجع کد: `android/app/src/main/java/com/aurum/edge/core/IchimokuStrategyEngine.kt`
> و `android/app/src/main/java/com/aurum/edge/core/TradePlanGuard.kt`
> نسخهٔ مبنا: ۳.۹.۵۵ (کد ۷۶۴). هر عدد و هر آستانه در این سند **عیناً از کد** استخراج شده،
> نه از توضیحات بازاریابی. شمارهٔ خط‌ها برای ردیابی سریع آمده است.

---

## ۰. شناسنامهٔ استراتژی

| مورد | مقدار در کد | خط |
|---|---|---|
| شناسه | `BASE_ICHIMOKU` | ۳۶–۴۹ |
| عنوان | استراتژی بیس (ترکیب کامل ۴گانه: پاتل + تاتل + هوسودا + پلویل) | ۳۸ |
| نشان | «ترکیب ۴گانه · بیس جامع» | ۳۹ |
| خانواده | `hasPatel = hasTuttle = hasHosoda = hasPeloille = true` → `combinationCount = 4` | ۴۴–۴۷ |
| دستهٔ ترکیب | «ترکیب کامل چهارگانه (۴گانه)» | ۳۴۰ |
| R:R بهینهٔ پایه | `baseOptimalRr = 2.25` | ۴۸ |
| یادگیری عصبی | مجاز (`neuralLearningEnabled = true` پیش‌فرض) | ۳۱ |
| نیاز به انتخاب صریح | **ندارد** (`requiresExplicitOptIn = false`) → همیشه در فهرست فعال | ۲۹ |
| وضعیت پیش‌فرض | اگر شناسه خالی یا ناشناخته باشد، موتور **همین** استراتژی را برمی‌گرداند | ۳۵۷–۳۷۰ |

**پیامد مهم:** `normalizeStrategyId("")` → `BASE_ICHIMOKU` و `fromId("چیز-نامعلوم")` → `BASE_ICHIMOKU`.
یعنی هر نمادی که هیچ‌وقت استراتژی برایش انتخاب نشده باشد، با همین روش ارزیابی می‌شود.

### چرا «۴گانه»؟

| ستون | صاحب مکتب | چیزی که از او در کد زنده مانده |
|---|---|---|
| تعادل | Manesh Patel | کش لاستیکی کیجون (شرط ۸)، اولویت سنگر حد ضرر `KIJUN_26`، Preserve Mode |
| ساختار | Mark Tuttle | آزادی چیکو (شرط ۶–۷)، لبهٔ کومو / SSB فلت (شرط ۹)، اولویت `KUMO_EDGE` در SL |
| موج و زمان | Goichi Hosoda | موج N/V/E/NT، نقطهٔ S، چرخهٔ زمانی ۹/۱۷/۲۶/۳۳/۴۲/۶۵/۷۶/۱۲۹/۱۷۲ (شرط ۱۰)، همهٔ اهداف TP |
| پلاتو | Karen Péloille | دیوارهای افقی تعادلی MTF (`flatEquilibriumLevels`)، کاتانا، سقفگذاری TP پشت مانع |

از این چهار پرچم، سه چیز به‌طور **مستقیم** در محاسبه تغییر می‌کند:

```kotlin
enablePullbackTrigger = hasPatel || hasHosoda || hasPeloille || !hasTuttle   // BASE → true
enableBreakoutTrigger = hasTuttle || hasHosoda || hasPeloille || !hasPatel   // BASE → true
requireHigherTfAlignment = hasPeloille || combinationCount >= 2 || ...       // BASE → true
requireChikouOpenSpace   = hasTuttle  || hasPeloille || combinationCount >= 2 // BASE → true
requireFutureKumoSlope   = hasPatel  || hasTuttle  || combinationCount >= 2   // BASE → true
minRiskReward = if (hasHosoda && combinationCount == 1) 1.8 else minAllowedRr   // BASE → 1.5
```
(خط ۳۱۹۹–۳۲۲۰)

یعنی در «بیس» همهٔ سخت‌گیری‌ها **روشن** است، اما سخت‌گیری مخصوص تک‌مدل هوسودا (R:R پایه ۱.۸) اعمال نمی‌شود.

---

## ۱. داده، تایم‌فریم و ترتیب پنجره‌ها

### ۱.۱ سه لایهٔ زمانی

| لایه | فیلد کانفیگ | پیش‌فرض | نقش |
|---|---|---|---|
| ماکرو | `macroIntervalLabel` | `4H` | فقط «اجازهٔ جهت» (وتوی خفیف) |
| استراتژی | `strategyIntervalLabel` | `1H` | تعادل، موج هوسودا، ER، سنگر حد ضرر |
| ماشه | `triggerIntervalLabel` | `15m` (یا `5m`) | کندل تصمیم، ورود، اسنایپر، حجم، شدو |

موتور زنده دو چیدمان را **هم‌زمان** ارزیابی می‌کند (`evaluateDualTimeframeStrategy`، خط ۴۴۳۸):

- `4H → 1H → 15m`
- `1H → 15m → 5m`

پس روی هر نماد، «بیس» دو گزارش جدا دارد؛ یکی با ماشهٔ ۱۵ دقیقه و یکی با ماشهٔ ۵ دقیقه.
هنگام باز کردن معامله، `report.triggerInterval` تعیین می‌کند ۱R و سقف زمانی چطور حساب شوند.

### ۱.۲ پیش‌نیاز کندل

```kotlin
val minBarsNeeded = max(config.senkouBPeriod + 5, 35)   // = max(57, 35) = 57 کندل بسته
if (closedBars.size < minBarsNeeded) return null        // snapshot ساخته نمی‌شود
```
(خط ۱۴۵۰–۱۴۵۲)

اگر هر یک از سه snapshot تهی باشد، گزارش با `INSUFFICIENT_DATA` و اکشن `NO_TRADE` برمی‌گردد (خط ۳۱۳۱).
یعنی با ۵۶ کندل بسته در ۴ ساعته، حتی اگر همه‌چیز صعودی باشد، هیچ تصمیمی صادر نمی‌شود.

### ۱.۳ هم‌ترازی پایه (Basis Alignment)

```kotlin
alignCandlesToSpotBasis(candles, referenceClose, liveSpotPrice)
```
(خط ۲۳۱۶)

اگر اختلاف `liveSpotPrice − referenceClose` بین **۳ نقطهٔ پایه (0.03٪)** و **۱۲٪** باشد، کل سری با
همان دلتا جابه‌جا می‌شود تا بیسیس فیوچرز/اسپات یا USDT/USD باعث خطای فاصله از کیجون و رد شدن
سیگنال نشود. بیشتر از ۱۲٪ = داده مشکوک، دست نمی‌زند. کمتر از ۳ bps = نویز، دست نمی‌زند.

### ۱.۴ هیچ نگاهی به آینده

در بک‌تست، سه پنجره از `BacktestSeries.closedUpTo(candles, size, now)` می‌آید که فقط کندل‌هایی را
برمی‌گرداند که `t + size <= now` (`BacktestSeries.kt` خط ۶۰). تعریف «لحظهٔ تصمیم»:

```kotlin
val now = bar.time + triggerSize   // یعنی لحظهٔ بسته شدن کندل ماشه
```
(خط ۲۲۵)

---

## ۲. آناتومی snapshot — هر عددی که در شرط‌ها استفاده می‌شود

`computeSnapshot(candles, config)` (خط ۱۴۴۲) یک `IchimokuBarSnapshot` می‌سازد. فقط روی
کندل‌های **بسته‌شده** کار می‌کند (`filter { it.closed }.ifEmpty { ordered }`).

### ۲.۱ خطوط پایه (9 / 26 / 52 / 26)

```
Tenkan(k)  = (بالاترین High ۹ کندل + پایین‌ترین Low ۹ کندل) / 2
Kijun(k)   = (بالاترین High ۲۶ کندل + پایین‌ترین Low ۲۶ کندل) / 2
SpanB_raw  = (بالاترین High ۵۲ کندل + پایین‌ترین Low ۵۲ کندل) / 2
SpanA_raw  = (Tenkan + Kijun) / 2
```
(خط ۱۴۶۴–۱۴۷۰)

دو ابر وجود دارد و این تفکیک حیاتی است:

| نام | منبع | معنا |
|---|---|---|
| `currentSpanA/B` | منبع در `idx − 25` | ابر **زیر/بالای قیمت امروز** (جابه‌جایی ۲۶) |
| `futureSpanA/B` | منبع در `idx` (امروز) | ابری که **۲۶ کندل بعد** رسم می‌شود |

`disp = (displacement − 1).coerceAtLeast(1)` → با پیش‌فرض ۲۶ می‌شود ۲۵ (خط ۱۴۷۲)؛
یعنی جابه‌جایی در کد عملاً `displacement − 1` است (۲۵ به‌جای ۲۶). تفاوت فقط یک کندل است،
اما برای بازتولید دقیق اعداد همین یک کندل را باید در نظر گرفت.

### ۲.۲ وضعیت کومو

```kotlin
kumoTop    = max(currentSpanA, currentSpanB)
kumoBottom = min(currentSpanA, currentSpanB)
isPriceAboveKumo = close > kumoTop
isPriceBelowKumo = close < kumoBottom
isFutureKumoBullish = futureSpanA > futureSpanB
isFutureSpanARising     = futureSpanA >= prevFutureSpanA - 1e-8
isFutureSpanBNonFalling = futureSpanB >= prevFutureSpanB - 1e-8
```
(خط ۹۳۴–۹۴۴)

### ۲.۳ ATR(14)

```
TR = max(High−Low, |High−PrevClose|, |Low−PrevClose|)
ATR14 = میانگین ۱۴ مقدار آخر TR، با کف close × 0.0005
```
(خط ۱۴۹۴–۱۵۰۸)

این ATR **خام** است. بعداً در `TradePlanGuard.robustAtr` داخل دالان نوسان همان نماد/تایم‌فریم برش
می‌خورد (خط ۲۷۱)؛ بنابراین ۱R هرگز از یک کندل گلِیچ‌خورده متورم نمی‌شود.

### ۲.۴ آزادی چیکو (شرط ۶)

```kotlin
chikouIdx = lastIdx − 26
corridorEnd = min(lastIdx, chikouIdx + 6)          // راهروی ۷ کندلی
for (k in chikouIdx..corridorEnd) {
    if (closeNow <= max(barK.high, max(kumoTopK, tkTopK))) chikouBullishOpen = false
    if (closeNow >= min(barK.low,  min(kumoBotK, tkBotK))) chikouBearishOpen = false
}
```
(خط ۱۵۱۰–۱۵۲۸)

یعنی «آزادی چیکو» در این کد سه‌بعدی است: کلوز فعلی باید از **بدنه و سایهٔ کندل ۲۶ دورهٔ قبل**،
از **تنکان/کیجون همان کندل** و از **ابر همان کندل**، و همچنین از ۶ کندل بعد از آن، عبور کرده باشد.
این سخت‌گیرانه‌ترین فیلتر ساختاری استراتژی است.

### ۲.۵ سقف/کف ساختاری چیکو (شرط ۷ — تاتل)

```kotlin
lookbackStart = lastIdx − 26
chikouPeak  = max(close[k] for k in lookbackStart until lastIdx)
chikouTrough= min(close[k] for k in lookbackStart until lastIdx)
```
(خط ۱۵۳۰–۱۵۳۸) — توجه: از **کلوز** استفاده می‌شود، نه High/Low.

### ۲.۶ فلت بودن (تعریف ریاضی «تخت»)

```kotlin
flatTol = max(close × 0.0003, ATR14 × 0.04)     // خط ۱۵۴۱
isCurrentSpanBFlat = |SSB − SSB[−1]| ≤ flatTol && |SSB − SSB[−2]| ≤ flatTol
isKijunFlat        = |Kijun − Kijun[−1]| ≤ flatTol
isTenkanFlat       = |Tenkan − Tenkan[−1]| ≤ flatTol
```

### ۲.۷ پلاتوهای افقی پلویل (Feed-کنندهٔ سقف TP)

```kotlin
for (i in max(3, lastIdx−80)..lastIdx) {
    if (|Kijun[i] − Kijun[i−1]| ≤ flatTol && |Kijun[i] − Kijun[i−2]| ≤ flatTol) rawPlateaus += Kijun[i]
    if (|SSB[i]   − SSB[i−1]|   ≤ flatTol && |SSB[i]   − SSB[i−2]|   ≤ flatTol)   rawPlateaus += SSB[i]
}
mergedPlateaus = mergeNearbyLevels(rawPlateaus, max(ATR×0.35, close×0.0015))
```
(خط ۱۶۲۲–۱۶۳۶)

یعنی هر نقطه‌ای که کیجون یا SSB در ۸۰ کندل اخیر **دست‌کم ۳ کندل پشت‌سرهم** تخت بوده، یک
«دیوار افقی» است؛ سطوح نزدیک به هم با تلورانس `0.35 ATR` یکی می‌شوند.

### ۲.۸ C-Clamp، کاتانا، ER14، حجم، شدو

```kotlin
cClampWarn = isKijunFlat && isCurrentSpanBFlat && distanceToKijunAtr > maxKijunExtensionAtr × 1.05
katana     = |Tenkan − Kijun| ≤ flatTol × 1.2
ER14       = |close − close[−14]| / Σ|close[i] − close[i−1]|  (۰..۱، کف پیش‌فرض ۰.۵)
volRatio20 = volume / میانگین حجم ۲۰ کندل
bodyRatio  = |close − open| / (high − low)
CLV        = (close − low) / (high − low)
upperWick  = (high − max(open,close)) / range
lowerWick  = (min(open,close) − low) / range
```
(خط ۱۶۴۶–۱۶۷۸)

ER (نسبت کارایی کافمن) همان «روند خالص در برابر نویز» است: جابه‌جایی خالص ۱۴ کندل تقسیم بر
مجموع مسیر طی‌شده. ER=۱ یعنی خط راست.

### ۲.۹ فرکتال ۵ کندلی بیل ویلیامز (سنگر SL)

```kotlin
downFractal = minOf(low) در ۹ کندل آخر          // مقدار پیش‌فرض
for (i in lastIdx−2 downTo max(2, lastIdx−28)) {
    if (low[i] ≤ low[i−1] && low[i] ≤ low[i−2] && low[i] ≤ low[i+1] && low[i] ≤ low[i+2]) {
        downFractal = low[i]; break            // اولین فرکتال از راست
    }
}
```
(خط ۱۷۸۹–۱۸۰۸) — یعنی فرکتالی که تا ۲ کندل قبل از آخر تمام شده باشد معتبر است (نیازی به
تأیید ۲ کندل بعد نیست، چون `i ≤ lastIdx−2`).

### ۲.۱۰ «تاج» هیدن کراس (که در بیس هم استفاده می‌شود!)

در `computeSnapshot` کراس‌های برابری تنکان/کیجون شناسایی می‌شوند:
دنباله‌ای از کندل‌ها که `|Tenkan − Kijun| ≤ 0.06 ATR` و طولش `≥ MIN_EQUAL_CROSS_BARS` باشد.
سطح = میانگین آن نوار. «تاج» = سطح کراس ± (اندازهٔ کراس × `CROWN_EXTENSION_RATIO`) در جهت شکست.
`nearestCrownLevel` = نزدیک‌ترین سطح کراس یا تاج، در جهت معامله (خط ۱۹۸۵).

**نکتهٔ مهم:** این لایه صریحاً به بیس هم تزریق شده است — هم در شرط ۹ (دیوار تاج) و هم در
ساخت پلاتوها (`hiddenCrossWall`، خط ۳۸۰۱). پس اعداد «بیس» امروز، بیسِ خالص چهار مکتب نیست.

---

## ۳. جهت معامله (Long/Short) — قبل از هر شرطی

```kotlin
val bullishBias = stratSnap.isPriceAboveKumo ||
    (macroSnap?.isPriceAboveKumo == true && trigSnap.isPriceAboveKumo)
val bearishBias = stratSnap.isPriceBelowKumo ||
    (macroSnap?.isPriceBelowKumo == true && trigSnap.isPriceBelowKumo)
```
(خط ۳۲۲۶–۳۲۲۹)

```
candidateDirection =
    bullishBias && !bearishBias  → BUY
    bearishBias && !bullishBias  → SELL
    Tenkan(1H) ≥ Kijun(1H)       → BUY      ← تساوی/ابهام
    else                          → SELL
```
(خط ۳۲۴۲–۳۲۵۳)

سه نکته:

1. **جهت از قبل قفل می‌شود و در طول شرط‌ها عوض نمی‌شود.** هر ۱۶ شرط «دوطرفه» نوشته شده‌اند
   اما با `isLong` فعلی سنجیده می‌شوند.
2. حالت ابهام (هر دو bias روشن یا هر دو خاموش) با یک قاعدهٔ ساده حل می‌شود: ترتیب تنکان/کیجون
   در تایم ۱ ساعته.
3. اگر قیمت ۱H داخل کومو باشد و ۴H هم بی‌طرف باشد، جهت فقط با تنکان ≥ کیجون انتخاب می‌شود؛
   بعد در شرط ۱ (`isPriceAboveKumo`) همان جهت رد می‌شود. یعنی «ابهام» هزینه‌اش یک شرط شکست‌خورده است.

---

## ۴. ماشهٔ ورود — `detectEntryTrigger` (خط ۴۶۶۶)

شرط نمی‌گوید «کی وارد شو»؛ ماشه می‌گوید **«الان»**. چهار ماشه به‌ترتیب اولویت بررسی می‌شوند و
اولین تطابق برمی‌گردد. اگر هیچ‌کدام → `NONE` و ورود هرگز انجام نمی‌شود.

پیش‌نیاز: `bars.size >= 5`. بافر شکست:

```kotlin
breakoutBuf = if (SSB فلت در هر دو لایه) ATR × breakoutBufferAtr  else  ATR × 0.03
```
(خط ۴۶۸۱–۴۶۸۵). با پیش‌فرض `breakoutBufferAtr = 0.15` → ۰.۱۵ ATR در حالت فلت و ۰.۰۳ ATR در حالت عادی.

### ماشه ۰ — `EARLY_KATANA_GENESIS` (شکار تولد موج)

خرید:
```
recentKatanaRelease == true
&& close > open
&& close > max(Tenkan, Kijun)
&& close > kumoTop
&& chikouBullishOpenSpace
&& distanceToKijunAtr ≤ 1.15
```
(خط ۴۶۹۲–۴۷۰۱). فروش، آینهٔ همین (خط ۴۷۳۵–۴۷۴۴).

`recentKatanaRelease` (خط ۱۶۷۶–۱۶۷۹): در یکی از ۳ کندل آخر، `|Tenkan−Kijun| ≤ 0.18 ATR` بوده
و الان فاصله باز شده و `bodyRatio ≥ 0.32`. یعنی فشردگی دو خط تعادلی → رها شدن → کندل بدنه‌دار.

### ماشه ۱ — `PULLBACK_BOUNCE` (پولبک به تعادل)

خرید:
```
در یکی از ۴ کندل (۳ قبلی + جاری):
    low ≤ max(Tenkan, Kijun) + 0.35 ATR
    && close ≥ Kijun − 0.20 ATR
و کندل جاری:
    close > open && close ≥ Tenkan && close > kumoTop
```
(خط ۴۷۰۴–۴۷۱۴)

### ماشه ۲ — `VALIDATED_BREAKOUT` (شکست معتبر)

خرید:
```
(close[−1] ≤ kumoTop || close[−2] ≤ kumoTop || close[−1] ≤ Kijun)
&& close > max(kumoTop, Kijun) + breakoutBuf
&& close ≥ chikouResistancePeak + breakoutBuf
&& close > open
```
(خط ۴۷۱۷–۴۷۲۱). دو نکته: عبور باید **بسته‌شده** باشد (نه شدو)، و باید هم‌زمان از سقف ساختاری
چیکو (تاتل) هم رد شود.

### ماشه ۳ — `SANYAKU_TK_CONTINUATION` (ادامهٔ موج سوم)

```
sanyakuBullishOrder
&& threeCandleBullishImpulse
&& close ≥ max(high[−1], high[−2])
&& distanceToKijunAtr ≤ maxKijunExtensionAtr × 0.85      // = 1.53 ATR
```
(خط ۴۷۲۲–۴۷۲۷)

`threeCandleBullishImpulse` = از ۳ کندل آخر، دست‌کم ۲ کندل صعودی و کلوز جاری ≥ بیشترین کلوز آن‌ها.

### جمع‌بندی ماشه

| ماشه | ماهیت | نقطهٔ قوت | نقطهٔ ضعف |
|---|---|---|---|
| ۰ کاتانا | تولد روند | زودترین ورود | نویز زیاد، نیاز به ۶ هم‌زمانی |
| ۱ پولبک | بازگشت به تعادل | ۱R کوچک، R:R خوب | در روندهای تک‌جهتهٔ قوی دیر می‌رسد |
| ۲ شکست | عبور ساختاری | بالاترین کیفیت | بعد از حرکت، ورود «گران» |
| ۳ سانیاکو | ادامه | در موج ۳ عالی | مستعد ورود در انتهای موج |

---

## ۵. ناحیهٔ اسنایپر و ۱R — قلب هندسهٔ معامله

```kotlin
val candleGoldenPocket = if (isLong) low + (high − low) × 0.55
                         else        high − (high − low) × 0.55

sniperOptimal = if (isLong) max(Tenkan, min(livePrice, goldenPocket))
                else        min(Tenkan, max(livePrice, goldenPocket))

sniperLow  (long)  = min(Tenkan, sniperOptimal) − 0.12 ATR
sniperHigh (long)  = max(sniperOptimal, livePrice) + 0.35 ATR
sniperLow  (short) = min(sniperOptimal, livePrice) − 0.35 ATR
sniperHigh (short) = max(Tenkan, sniperOptimal) + 0.12 ATR

distToSniperAtr  = |livePrice − sniperOptimal| / ATR
sniperEntryScore = clamp(100 − distToSniperAtr × 55, 15, 100)
```
(خط ۳۵۷۷–۳۶۰۰)

یعنی نقطهٔ ورود = **سخاوتمندانه‌ترین** نقطهٔ بین «تنکان» و «پاکت طلایی ۵۵٪ بدنه» است.
برای خرید، تنکان نقش کف را بازی می‌کند (ورود روی حمایت داینامیک)، برای فروش نقش سقف.
نمرهٔ دقت: هر ۱ ATR فاصله، ۵۵ درصد از نمره کم می‌کند؛ سقف فاصله برای عبور از شرط ۱۶ عملاً ۰.۳۵ ATR است.

**ناحیهٔ اسنایپر چطور اجرا می‌شود؟** در مسیر تیک‌به‌تیک (`AurumViewModel`، خط ۷۱۶):
هر گزارشِ «مسلح» که `isPriceInSniperWindow(tick.price)` باشد و شرایط ایمنی را رد کند، در همان
قیمت تیک با `TradePlanGuard.rebaseToLive` اجرا می‌شود. یعنی معامله در **وسط** کندل هم می‌تواند
باز شود، نه فقط روی کلوز.

---

## ۶. شانزده شرط — یکی‌یکی، با فرمول

همهٔ ۱۶ شرط باید هم‌زمان پاس شوند. هیچ‌کدام «امتیازی» نیست؛ همه **وتوی سخت** هستند.
در کد: `allSixteenPassed = modelCoreConditions.all { it.passed }` (خط ۴۱۵۱).

### ساختار کد

```
خط ۳۲۶۰–۳۳۹۰ : شرط ۱ تا ۹
خط ۳۵۷۶–۳۶۷۲ : ناحیهٔ اسنایپر + حد ضرر ساختاری (پیش‌نیاز شرط ۱۰ و ۱۲)
خط ۳۶۷۳–۳۷۰۰ : شرط ۱۰ و ۱۱
خط ۳۷۰۷–۳۷۷۰ : عدد بهینهٔ R:R و انتخاب موج هدف
خط ۳۸۰۰–۳۹۳۵ : دیوار پلاتو، سقف ۳R، TradePlanGuard، شرط ۱۲
خط ۳۹۳۸–۳۹۷۵ : شرط ۱۳ تا ۱۶
خط ۳۹۷۷–۴۰۹۵ : ساخت لیست baseSixteenConditions
```

---

### شرط ۱ — رژیم کوموی ۳ تایم‌فریم

```
long : price(1H) > kumoTop(1H)  &&  price(trigger) > kumoTop(trigger)
       && (macro == null || 4H زیر کومو نیست)
short: آینهٔ همین
```
(خط ۳۲۶۰–۳۲۷۷)

نکتهٔ نامتقارن: در تایم ۱ ساعته و ماشه **باید** بالای کومو باشی، اما در ماکرو فقط «نباید زیر کومو باشی»
کافی است. یعنی قیمت داخل کوموی ۴ ساعته اشکالی ندارد، اگر ۱H و ۱۵m بالای ابر باشند.
وقتی `requireHigherTfAlignment = false` باشد یا `macroSnap == null`، این بخش رد می‌شود.

### شرط ۲ — چیدمان ۵ خط سانیاکو

```kotlin
sanyakuBullishOrder = close > kumoTop
    && tenkan >= kijun − 0.05 ATR
    && kijun  >= kumoBottom − 0.10 ATR
```
(خط ۹۴۸–۹۵۲)

باید هم در ۱H و هم در تایم ماشه برقرار باشد (خط ۳۲۷۹–۳۲۸۳).
دو تلورانس `0.05` و `0.10` ATR اجازه می‌دهد خطوط «تقریباً مرتب» باشند، چون در بازار واقعی
تنکان همیشه دقیقاً بالای کیجون نیست.

### شرط ۳ — کراس و شیب پویا تنکان/کیجون

```
long (1H):
    tenkan ≥ kijun
    && tenkan ≥ prevTenkan − 0.05 ATR     // شیب تنکان خیلی منفی نباشد
    && kijun  ≥ prevKijun  − 0.02 ATR     // شیب کیجون تقریباً نزولی نباشد
long (trigger):
    tenkan ≥ prevTenkan − 0.05 ATR
```
(خط ۳۲۹۰–۳۰۷)

یعنی تنکان می‌تواند کمی خلاف جهت خم شود (نفس‌گیری)، اما کیجون — که ستون تعادل است — نباید
بچرخد. این تفکیک «نفس» از «شکست» است.

### شرط ۴ — جهت و شیب ابر آینده (۲۶ کندل جلوتر)

```
long (1H):
    futureSpanA > futureSpanB            // ابر آینده صعودی
    && futureSpanA ≥ prevFutureSpanA     // SSA در حال رشد
    && futureSpanB ≥ prevFutureSpanB     // SSB در حال ریزش نیست
long (trigger): futureSpanA > futureSpanB
```
(خط ۳۳۰۹–۳۳۱۹)

این مهم‌ترین شرط «پیش‌نگر» است: چون ابر آینده از دادهٔ **امروز** ساخته می‌شود، رشد آن یعنی
ساختار ۲۶ کندل بعد هم‌جهت است.

### شرط ۵ — ضخامت ابر آینده و ایمنی از پیچش

```kotlin
minThickStrat = minFutureKumoThicknessAtr            // 0.05
minThickTrig  = max(minThickStrat × 0.80, 0.02)      // 0.04
futureThicknessOk = thickness(1H) ≥ 0.05 ATR && thickness(trigger) ≥ 0.04 ATR
thickness = |futureSpanA − futureSpanB| / ATR
```
(خط ۳۲۲۱–۳۳۲۸)

ابر نازک = احتمال «Twist» (تعویض ناگهانی SSA/SSB) بالا. ۰.۰۵ ATR حداقلِ پذیرش است.
تایم ماشه ۲۰٪ آسان‌گیرتر است چون نوسانش کمتر است.

### شرط ۶ — آزادی سه‌بعدی چیکو (تاتل)

```
long: chikouBullishOpenSpace در ۱H و در trigger
```
(خط ۳۳۳۲–۳۳۴۴، فرمول در §۲.۴)

اگر `requireChikouOpenSpace = false` باشد رد می‌شود — در بیس روشن است.

### شرط ۷ — شکست سقف/کف ساختاری چیکو

```
long : close(trigger) ≥ chikouResistancePeak − 0.15 ATR
short: close(trigger) ≤ chikouSupportTrough  + 0.15 ATR
```
(خط ۳۳۴۶–۳۳۵۶)

چیکو باید از **بالاترین کلوز ۲۶ کندل قبل** رد شده باشد. تلورانس ۰.۱۵ ATR یعنی «دقیقاً روی خط
هم قبول است». این همان تأیید «شکست ساختار» تاتل است، نه فقط «قیمت بالای ابر».

### شرط ۸ — کش لاستیکی کیجون و نبود C-Clamp (پاتل)

```kotlin
extAtr = distanceToKijunAtr(1H)
notOverextended = extAtr ≤ maxKijunExtensionAtr        // پیش‌فرض 1.8
    && !stratSnap.isCClampWarning
    && !trigSnap.isCClampWarning
```
(خط ۳۳۵۸–۳۳۶۸)

دو فیلتر جدا:
- **کشیدگی عددی:** قیمت ۱H بیش از ۱.۸ ATR از کیجون فاصله نداشته باشد.
- **C-Clamp:** اگر کیجون **و** SSB هر دو تخت‌اند و قیمت > ۱.۸۹ ATR دور است (`1.8 × 1.05`)،
  بازار در دام «کشش مصنوعی» است.

منطق پاتل: در روند سالم، برگشت به کیجون رخ می‌دهد. اگر قیمت زیادی دور شده، ورود یعنی خریدن
سقف پولبک.

### شرط ۹ — جاذبهٔ SSB فلت + دیوار تاج + تعادل کاتانا

دو بخش مستقل که با `&&` به هم چسبیده‌اند (خط ۳۳۷۰–۳۴۰۹):

**۹-الف: جاذبهٔ SSB فلت**
```kotlin
reqGravityClearance = if (SSB فلت در ۱H یا trigger) ATR × breakoutBufferAtr else 0
long : close(trigger) ≥ kumoTop(trigger) + reqGravityClearance
short: close(trigger) ≤ kumoBottom(trigger) − reqGravityClearance
```
یعنی وقتی SSB تخت است (پلاتو)، عبور باید با **بافر ۰.۱۵ ATR** انجام شده باشد؛ نه دقیقاً روی لبه.

**۹-ب: دیوار تاج هیدن کراس**
```kotlin
// ۳.۹.۵۶: کل این بلوک با includeHiddenCrossLayer خاموش‌شدنی است (پیش‌فرض روشن)
if (effectiveConfig.includeHiddenCrossLayer && nearestCrownLevel > 0) {
    wallAhead = (long) crown > close  /  (short) crown < close
    hiddenCrossWallClearance = !wallAhead || |crown − close| ≥ ATR × breakoutBufferAtr
}
```
اگر تاجی **روبروی** قیمت و نزدیک‌تر از ۰.۱۵ ATR باشد، ورود رد می‌شود (دیوار در مسیر است).
همین لایه در ساخت `allPlateaus` هم شرکت می‌کند و سقف حد سود را عقب می‌کشد؛ با خاموش‌کردن
کلید، «بیس خالص چهارگانه» به‌دست می‌آید.

### شرط ۱۰ — هندسهٔ موج N هوسودا، نقطهٔ S و چرخهٔ زمان

```kotlin
sPointOk = wave?.sPointHeld != false        // اگر موج ساخته نشده، پاس
timeCycleExhausted = wave?.isNearTimeCycleChangeDay == true && trigSnap.isTenkanFlat
cond10Passed = sPointOk && !timeCycleExhausted
```
(خط ۳۶۷۳–۳۶۸۲)

`computeHosodaWave` (خط ۲۲۱۵) روی کندل‌های **۱ ساعته** و آخرین ۶۵ کندل بسته:

```
خرید:
    C = کف ۶ کندل آخر
    B = سقف بین C−22 .. C−1
    A = کف بین B−45 .. B
    waveHeight = max(B − A, max(distanceToKijun×2.4, ATR×3.0))
    pointA = B − waveHeight
    pointC = clamp(C, A+0.3ATR .. B−0.3ATR)

    targetV  = B + (B − C)
    targetN  = C + (B − A)
    targetE  = B + (B − A)
    targetNT = C + (C − A)
    هر چهار هدف با کفِ ATR فیلتر می‌شوند:
        V ≥ close + 1.5 ATR   N ≥ close + 2.2 ATR
        E ≥ close + 3.0 ATR   NT ≥ close + 1.2 ATR

    sPointHeld   = pointC ≥ kumoBottom
    barsSinceWaveStart = آخرین ایندکس − idxA + 1
    nearestTimeCycleNumber = نزدیک‌ترین عدد از {9,17,26,33,42,65,76,129,172}
    isNearTimeCycleChangeDay = |nearest − barsSinceWaveStart| ≤ 1
```
(خط ۲۲۱۸–۲۲۷۰؛ فروش آینه است، خط ۲۲۷۹–۲۳۲۳)

نکتهٔ مهم: **kumoBottom** در `sPointHeld` از snapshot **۱ ساعته** می‌آید، و `pointC` هم
از کندل ۱ ساعته. پس «نقطهٔ S نشکسته» = کف اصلاح بالای کف ابر ۱ ساعته.

### شرط ۱۱ — قدرت کندل ماشه و حجم

```
long : close > open && CLV ≥ 0.45 && bodyRatio ≥ 0.22
short: close < open && CLV ≤ 0.55 && bodyRatio ≥ 0.22
&& volRatio20 ≥ minVolumeRatio20        // پیش‌فرض 0.60
```
(خط ۳۶۸۳–۳۶۹۷)

سه چیز: جهت کندل، بسته‌شدن در نیمهٔ درست، و بدنهٔ حداقل ۲۲٪ دامنه. آستانهٔ حجم ۰.۶ واقعاً
آسان‌گیر است (۶۰٪ میانگین) — عملاً فقط کندل‌های بی‌حجم کامل را رد می‌کند.

> **۳.۹.۵۶:** عبارت `driveBookRuleOk` که همیشه `true` بود از این شرط حذف شد، پس صورت شرط
> دقیقاً همان چیزی است که در گزارش دیده می‌شود.
>
> ```kotlin
> val cond11Passed = directionalCandleOk && volumeMomentumOk
> ```

### شرط ۱۲ — فضای باز و R:R در دالان [۱.۵ .. ۳.۰]

این پیچیده‌ترین شرط است. سه مرحله دارد:

**مرحلهٔ الف — انتخاب هدف خام**
```kotlin
entryAnchorForTp = (long) max(livePrice, sniperOptimal) else min(...)
optTpDist = stopDist × computedOptimalRr
rawPrimaryTp = chosenWavePair?.second ?: entryAnchorForTp ± optTpDist
```
(خط ۳۷۵۰–۳۷۹۵)

`chosenWavePair` از بین `{NT, V, N, E}` انتخاب می‌شود با این فیلترها (خط ۳۷۴۹–۳۷۶۲):

```
در جهت درست نسبت به max/min(livePrice, sniperOptimal)
&& min(|target−live|, |target−sniper|) / stopDist ≥ requiredMinRr    (≥ 1.5)
&& max(|target−live|, |target−sniper|) / stopDist ≤ 3.00
```
و از بین واجدین، آن که `|R: R هدف − computedOptimalRr|` کمینه است.
اگر هیچ موجی در دالان نبود → هدف = `entryAnchor ± stopDist × computedOptimalRr`.

**مرحلهٔ ب — سقف‌گذاری پشت دیوار پلاتو**
```kotlin
allPlateaus = plateaus(4H) + plateaus(1H) + hiddenCrossWall
nearestObstacle = (long) اولین پلاتو > livePrice + 0.4 ATR
                (short) آخرین پلاتو < livePrice − 0.4 ATR
```
(خط ۳۸۰۰–۳۸۱۱)

اگر فاصله تا مانع، در واحد R، دست‌کم ۱.۵ باشد، هدف به **۰.۰۸ ATR قبل از مانع** کوتاه می‌شود
(خط ۳۸۱۳–۳۸۳۲). اگر بعد از کوتاه‌شدن هم R:R ≥ ۱.۵ بماند، همین هدف پایین‌تر پذیرفته می‌شود.

این دقیقاً منطق پلویل است: هدف را پشت اولین دیوار افقی نگذار، زیرا بازار آنجا برمی‌گردد.

**مرحلهٔ ج — قفل سخت در [۱.۵R .. ۳.۰R]**
```kotlin
// ۳.۹.۵۶ — یک لنگر، یک ۱R، دو کران متقارن:
long : minAllowedTp = sniper + stopDist × requiredMinRr
       maxAllowedTp = sniper + stopDist × maxAllowedRr
       effectiveTp  = clamp(unclampedTp, minAllowedTp, minAllowedTp .. maxAllowedTp)
short: minAllowedTp = sniper − stopDist × requiredMinRr  (کف ۰.۰۰۰۰۱)
                    maxAllowedTp = sniper − stopDist × maxAllowedRr
```
(خط ۳۸۶۴–۳۸۷۹)

> **چرا عوض شد:** پیش از ۳.۹.۵۶ کران پایین با «ورودِ دورتر تا SL» و کران بالا با «ورودِ
> نزدیک‌تر تا SL» بسته می‌شد. با یک شکاف کوچک بین تیک زنده و ناحیهٔ اسنایپر، این دو قاعده
> دالان را به سمت سقف ۳R می‌فشردند و هدف از R:R بهینهٔ همان معامله فاصله می‌گرفت. حالا هر دو
> کران همان `stopDist` شرط ۱۲ هستند و `TradePlanGuard` هم با همین هندسه کار می‌کند.

این کلید فهم «۱R» است: کد **دو ۱R** دارد.

| ۱R | مبدأ | کاربرد |
|---|---|---|
| `stopDist` | `max(\|sniper−SL\|, \|live−SL\|)` | محاسبهٔ R:R شرط‌ها، انتخاب موج، سقف پلاتو |
| `finalStopDist` (پس از Guard) | پلن نهایی | گزارش، حجم پوزیشن، خروج، سقف ۳R |

چون در گام بعد `TradePlanGuard` حد ضرر را به دالان مجاز جمع می‌کند، مقدار نهایی معمولاً
کمی با `stopDist` اختلاف دارد.

### موتور ایمن‌سازی پلن — `TradePlanGuard.sanitizePlan`

```kotlin
guardedPlan = sanitizePlan(
    symbol, action, entryPrice = sniperOptimal,
    candidateStopLoss = technicalSl, candidateTakeProfit = effectiveTp,
    desiredRiskReward = computedOptimalRr, referenceAtr = ATR(trigger),
    triggerInterval, strategyAtr = ATR(1H)
)
```
(خط ۳۸۴۸–۳۸۵۹)

داخل Guard (خط ۳۳۵–۴۹۰):

```
۱. ATR مقاوم:  raw.coerceIn(price × maxStopPct/9 , price × maxStopPct/1.6)
۲. floorDist = max(min(structuralFloor, corridorCap), minStopPct×price,
                   atrFactorMin×ATR, venueFloor)       // atrFactorMin = 0.80
۳. ceilingDist = max(minStopPct×price, min(maxStopPct×price, atrFactorMax×ATR))  // 2.10
۴. فاصلهٔ حد ضرر:  coerceIn(floorDist, stopCeiling)
۵. فاصلهٔ حد سود:  اگر ≥ 1.5R باشد همان، وگرنه stopDist × desiredRr
                   سپس coerceIn(stopDist×1.5, min(stopDist×3.0, targetCeiling))
۶. R:R نهایی:      coerceIn(1.5, 3.0)
۷. اهرم:           min(سقف نماد, floor(1/(stopPct×2.5 + کارمزد + مارجین)))
```
با `atrFactorMin = 0.80` و `atrFactorMax = 2.10`، فاصلهٔ حد ضرر همیشه بین ۰.۸۰ و ۲.۱۰ ATR
می‌ماند (به‌علاوهٔ سقف درصدی تایم‌فریم). این همان چیزی است که «۱R متورم» را حذف می‌کند.

اگر Guard پلن را رد کند (`REJECTED`)، خروجی گزارش اعداد خام ساختاری را نگه می‌دارد اما
`isUsable = false` است و در مسیر ورود خودکار با پیام «پلن نامعتبر» کنار گذاشته می‌شود.

### شرط ۱۳ — کارایی جهت‌دار روند (ER14) و فیلتر ضد رِنج

```kotlin
erStrat = ER14(1H);  erTrig = ER14(trigger)
minErReq = minEfficiencyRatio          // 0.20
dualFlatChop = isKijunFlat(1H) && isCurrentSpanBFlat(1H)
            && isKijunFlat(trigger) && isCurrentSpanBFlat(trigger) && erStrat < minErReq
htfKijunCounterTrap = (long)  close(trigger) > kijun(trigger)
                          && close(1H) < kijun(1H) − 0.25 ATR(1H)
cond13Passed = (erStrat ≥ 0.20 || erTrig ≥ 0.22) && !dualFlatChop && !htfKijunCounterTrap
```
(خط ۳۷۰۷–۳۷۱۸)

سه چیز سنجیده می‌شود:
- **کارایی:** اگر تایم ۱H کافی نبود، تایم ماشه با آستانهٔ ۰.۲۲ (سخت‌گیرانه‌تر) می‌تواند نجات دهد.
- **رِنج دوگانه:** کیجون و SSB در **هر دو** لایه تخت + ER پایین = بازار فرسایشی.
- **تلهٔ کیجون ۱ ساعته:** ورود خرید در حالی که قیمت ۱H زیر کیجون است = خرید در خلاف تعادل بالادست.

### شرط ۱۴ — ضد فیک‌بریک‌اوت و رد شدوی معکوس

```kotlin
adverseWick   = (long) upperWickRatio else lowerWickRatio
favorableTail = (long) lowerWickRatio else upperWickRatio
cond14Passed = adverseWick ≤ maxAdverseWickRatio        // 0.45
    && (bodyRatio ≥ 0.22 || favorableTail ≥ 0.25)
```
(خط ۳۹۴۷–۳۹۵۸)

منطق: کندل ماشه نباید سایهٔ بالایی بزرگ داشته باشد (فروشندگان فعال)؛ و اگر هم بدنه کوچک بود،
باید «دُم تأیید» پایین داشته باشد (خریداران دفاع کرده باشند). یک بار در شرط ۱۱ هم بدنه چک
می‌شود؛ اینجا مسیر جایگزین `favorableTail` باز است.

### شرط ۱۵ — فاز روند: Genesis / Core فقط

```kotlin
trendPhase = when {
    !cond13Passed || dualFlatChop                              → CHOP_RANGE
    !notOverextended || (waveBars > 55 && Tenkan فلت در هر دو)  → LATE_EXHAUSTION
    waveBars ≤ 18 || recentKatanaRelease
        || triggerType == EARLY_KATANA_GENESIS                 → GENESIS
    else                                                        → CORE_EXPANSION
}
cond15Passed = trendPhase == GENESIS || trendPhase == CORE_EXPANSION
```
(خط ۳۷۱۹–۳۷۲۶، ۳۹۶۰–۳۹۶۳)

`waveBars = wave?.barsSinceWaveStart ?: 12`. یعنی سه حالت:
- **GENESIS:** موج جوان (≤ ۱۸ کندل)، یا کاتانا تازه رها شده، یا ماشهٔ ۰.
- **CORE_EXPANSION:** هر چیز دیگری که نه رِنج است نه اشباع.
- **ممنوع:** `CHOP_RANGE` و `LATE_EXHAUSTION`.

نکته: شرط ۱۵ عملاً شرط ۱۳ را تکرار می‌کند (اگر ۱۳ رد شود، فاز `CHOP_RANGE` می‌شود). پس
اگر ۱۳ پاس شود، ۱۵ فقط وقتی رد می‌شود که `LATE_EXHAUSTION` باشد. یعنی **تنها** راه شکست ۱۵
این است: بیش از ۱.۸ ATR از کیجون دور بودن، یا موج > ۵۵ کندل با تنکانِ تخت در هر دو لایه.

### شرط ۱۶ — نقطه‌زنی میلی‌متری ورود

```kotlin
distFromTenkanAtr = |close − Tenkan| / ATR(trigger)
// ۳.۹.۵۶: این عدد حالا از پروفایل استراتژی می‌آید (قبلاً همیشه پیش‌فرض بود)
maxSniperDist = effectiveConfig.maxSniperDistanceAtr.coerceIn(0.20, 0.55)   // پیش‌فرض 0.35
cond16Passed = distToSniperAtr ≤ maxSniperDist
            && distFromTenkanAtr ≤ maxSniperDist + 0.50        // = 0.85
```
(خط ۳۹۶۴–۳۹۷۵)

یعنی ورود باید حداکثر ۰.۳۵ ATR از نقطهٔ بهینه فاصله داشته باشد و کلوز حداکثر ۰.۸۵ ATR از تنکان.
اگر ۱۵ شرط اول پاس باشند اما این یکی نه، گزارش وارد حالت **«مسلح برای نقطه‌زنی»** می‌شود:

```kotlin
sniperArmedPendingRetest = firstFifteenPassed && triggerFired && !cond16Passed
```
(خط ۴۲۳۸–۴۲۳۹) و در `finalAction` هم سیگنال خرید/فروش صادر می‌شود (خط ۴۲۸۵–۴۲۸۹)،
اما `isActionable` نیست (چون `readiness` سیگنال نیست). این گزارش‌ها در `sniperArmedReports`
می‌آیند و فقط از مسیر تیک اجرا می‌شوند: «در کمین لمس قیمت اسنایپر».

---

## ۷. حد ضرر ساختاری — `computeOptimalStructuralStopLoss` (خط ۲۴۱۰)

### ۷.۱ پنج سنگر

| سنگر | فرمول خرید | وزن در بیس |
|---|---|---|
| `FRACTAL_5BAR` | `min(lastDownFractalLow, low) − buffer` | ۳ |
| `KIJUN_26` | `kijun − buffer` | ۱ |
| `RIBBON_KIJUN30_EMA34` | `min(kijun30, ema34) − buffer × 0.90` | ۲ |
| `KUMO_EDGE` | `kumoBottom − buffer × 0.85` | ۴ |
| `WAVE_S_POINT` | `(wave.pointC ?: kijun) − buffer` | ۵ |

فروش آینهٔ کامل است (بالای سنگر + بافر). خط ۲۴۴۵–۲۵۰۵.

### ۷.۲ بافر ضد استاپ‌هانت

```kotlin
sweepShieldBuffer = ATR × ( slBufferAtr.coerceIn(0.18, 0.42)
                          + 0.16 × adverseWick.coerceIn(0.0, 0.65) )
```
(خط ۲۴۳۸)

با `slBufferAtr = 0.25` و شدوی مخالف صفر → **۰.۲۵ ATR**. با شدوی ۰.۴۵ (سقف شرط ۱۴) →
`0.25 + 0.072 = 0.322 ATR`. یعنی هرچه سایهٔ مخالف بزرگ‌تر، حد ضرر عقب‌تر می‌رود — چون سایهٔ
بزرگ یعنی استاپ‌هانت احتمالی و نباید در محدودهٔ آن ایستاد.

### ۷.۳ انتخاب سنگر — ترتیب مکتب

`stopAnchorPriorityFor(BASE_ICHIMOKU)` (خط ۲۵۳۰):

```kotlin
order = LinkedHashSet()
if (hasPatel)    order += [KIJUN_26, RIBBON_KIJUN30_EMA34, FRACTAL_5BAR, KUMO_EDGE, WAVE_S_POINT]
if (hasTuttle)   order += [KUMO_EDGE, FRACTAL_5BAR, KIJUN_26, RIBBON_KIJUN30_EMA34, WAVE_S_POINT]
if (hasHosoda)   order += [WAVE_S_POINT, KIJUN_26, FRACTAL_5BAR, KUMO_EDGE, RIBBON_KIJUN30_EMA34]
if (hasPeloille) order += [KUMO_EDGE, RIBBON_KIJUN30_EMA34, KIJUN_26, FRACTAL_5BAR, WAVE_S_POINT]
```

چون `LinkedHashSet` ترتیب درج را حفظ می‌کند، ترتیب نهایی برای **بیس** این است:

> **KIJUN_26 → RIBBON_KIJUN30/EMA34 → FRACTAL_5BAR → KUMO_EDGE → WAVE_S_POINT**

یعنی اولین سنگرِ موجود که در دالان `[floorDist .. ceilingDist]` بیفتد برنده است. عملاً
حد ضرر «بیس» تقریباً همیشه روی **کیجون ۲۶ تایم ماشه** می‌نشیند — نه نقطهٔ S هوسودا.
این انتخاب ارث پاتل است و به همین دلیل هم معمولاً نزدیک‌ترین سنگر به قیمت است.

اگر هیچ سنگری در دالان نبود → `chooseStructuralAnchor` (خط ۷۵۲ در Guard) نزدیک‌ترین
سنگرِ بالای کف را برمی‌دارد و فاصله را به دالان clamp می‌کند.
اگر باز هم نشد → `min(entry − minSafeDist, entry − 0.5 ATR)`.

### ۷.۴ دالان مجاز

```
floorDist = TradePlanGuard.stopDistanceFloor(...)     ≥ min(structuralFloor, corridorCap)
                                                      , minStopPct×price
                                                      , 0.80 × ATR
ceilingDist = TradePlanGuard.stopDistanceCeiling(...)  ≤ 2.10 × ATR و maxStopPct×price
```
`minSafeStructuralStopDistance` (خط ۲۳۶۰) برای کریپتو:
```
max(venueMin × 3, max(pctFloor, max(0.82 × ATR_trigger, 0.28 × ATR_1H)))
pctFloor = BTC/ETH و M5 → 0.16٪ │ BTC/ETH → 0.20٪ │ M5 سایر → 0.20٪ │ بقیه → 0.25٪
```

### ۷.۵ کف مطلق نهایی

```kotlin
finalStopDist = guardedStopDist.coerceAtLeast(ATR(trigger) × 0.25)
```
(خط ۳۸۶۴–۳۸۶۶) — هیچ حد ضرری نزدیک‌تر از ۰.۲۵ ATR از ورود پذیرفته نمی‌شود.

---

## ۸. حد سود و «عدد مناسب R:R»

### ۸.۱ عدد بهینه چطور ساخته می‌شود

```kotlin
modelBaseOptimalRr = activeModel.baseOptimalRr.coerceIn(1.50, 3.00)     // BASE → 2.25

phaseRrBonus = GENESIS → +0.25 │ CORE_EXPANSION → +0.12 │ بقیه → 0
erRrBonus    = clamp((ER_1H + ER_trigger − 0.50) × 0.45, −0.15, +0.35)
volRrBonus   = if (volRatio20 ≥ 1.20) +0.12 else 0

computedOptimalRr = round2(clamp(2.25 + phase + er + vol, requiredMinRr, 3.00))
```
(خط ۳۷۲۹–۳۷۴۵)

بازهٔ عملی برای بیس: از `2.25 − 0.15 = 2.10` تا `2.25 + 0.25 + 0.35 + 0.12 = 2.97`.

### ۸.۲ `requiredMinRr` و `maxAllowedRr`

```kotlin
requiredMinRr  = effectiveConfig.minRiskReward.coerceIn(1.50, 2.85)     // پیش‌فرض → 1.50
maxAllowedRr   = 3.00
```
(خط ۳۷۰۳–۳۷۰۴). اگر یادگیری عصبی یا شخصیت نماد `minRiskReward` را بالا ببرد، `requiredMinRr`
همان‌قدر سخت‌گیر می‌شود (تا سقف ۲.۸۵). یعنی «بیس» می‌تواند با یادگیری، سخت‌گیرتر شود اما
هیچ‌وقت آسان‌گیرتر از ۱.۵ نمی‌شود.

### ۸.۳ انتخاب از امواج هوسودا

| برچسب | فرمول خرید | فرمول فروش |
|---|---|---|
| موج NT | `C + (C − A)` | `C − (A − C)` |
| موج V | `B + (B − C)` | `B − (C − B)` |
| موج N | `C + (B − A)` | `C − (A − B)` |
| موج E | `B + (B − A)` | `B − (A − B)` |

همه با کف ATR فیلتر می‌شوند: `NT ≥ close+1.2 ATR`، `V ≥ close+1.5`، `N ≥ close+2.2`، `E ≥ close+3.0`.
پس «موج E» ذاتاً دورترین هدف است و معمولاً به‌خاطر سقف ۳R رد می‌شود.

انتخاب نهایی: نزدیک‌ترین هدف به `computedOptimalRr` از بین واجدین دالان `[1.5R..3.0R]`.

### ۸.۴ `runnerArmedAbove3R = false`

(خط ۳۹۲۸). هیچ «رایدِر» بالای ۳R وجود ندارد. سقف سود مطلق ۳R است و
`evaluateOpenTradeManagement` هم در `currentR ≥ 3.0` (اگر `riskReward ≤ 3.0001` و استاپ
قفل نشده باشد) معامله را می‌بندد (خط ۴۹۷۳–۴۹۷۶).

### ۸.۵ نمونهٔ عددی (فرضی، برای درک مکانیک)

فرض: خرید، `live = 2400`, `sniperOptimal = 2398`, `ATR(trigger) = 4.0`, `Kijun(trigger) = 2390`,
فاز `GENESIS`, `ER(1H) = 0.35`, `ER(trigger) = 0.30`, `volRatio20 = 1.05`.

**گام ۱ — حد ضرر ساختاری**
```
buffer             = 4.0 × 0.25 = 1.0
anchorKijun26      = 2390 − 1.0 = 2389     → |2398−2389| = 9.0 = 2.25 ATR
دالان مجاز         = [0.80×4 , 2.10×4] = [3.2 , 8.4]
9 > 8.4 → KIJUN_26 خارج دالان، رد می‌شود
سنگر بعدی RIBBON   = 2386  → فاصله 12 → خارج دالان
سنگر بعدی FRACTAL  = 2393  → فاصله 5 = 1.25 ATR → داخل دالان ✅ ⇒ technicalSl = 2393.0
```

**گام ۲ — عدد بهینهٔ R:R**
```
modelBase = 2.25
phase     = +0.25 (GENESIS)
er        = clamp((0.35 + 0.30 − 0.50) × 0.45, −0.15, +0.35) = 0.0675
vol       = 0  (چون volRatio20 = 1.05 < 1.20)
computedOptimalRr = round2(2.25 + 0.25 + 0.0675) = 2.57
requiredMinRr = 1.50      maxAllowedRr = 3.00
```

**گام ۳ — هدف خام از امواج هوسودا**

`stopDist = max(|2398−2393|, |2400−2393|) = 7.0`

دالان پذیرش موج: `min(فاصله‌ها)/7 ≥ 1.5` و `max(فاصله‌ها)/7 ≤ 3.0` → بازهٔ مجاز فاصله = **10.5 .. 21.0**
منطقهٔ هدف = **≥ 2410.5** (نسبت به 2400) و **≥ 2408.5** (نسبت به 2398).
فرض: موج `V` روی 2419 و موج `N` روی 2431 می‌افتد →
`V` = 2.71R ✅ (نزدیک‌ترین به 2.57) · `N` = 4.4R ❌ (بیش از سقف ۳R) ⇒ `chosenWavePair = موج V @ 2419`

**گام ۴ — سقف‌گذاری پشت دیوار پلاتو**

فرض: نزدیک‌ترین پلاتو ۴ ساعته بالای `live + 0.4 ATR` روی **2416** است.
فاصله تا پلاتو = `min(2416−2400, 2416−2398) = 16` → `16 / 7 = 2.29R ≥ 1.5` ⇒ سقف اعمال می‌شود:
```
هدف بافردار = 2416 − 0.08×4 = 2415.68
(این هدف هم ≥ 1.5R است ⇒ پذیرفته می‌شود)
```

**گام ۵ — قفل سخت [۱.۵R .. ۳.۰R] (با technicalSl، نه با stopDist)**

اینجا نامتقارنی کد مهم است:
```
minAllowedTp = max(live, sniper) + |max(live,sniper) − SL| × 1.5 = 2400 + 7.0×1.5 = 2410.5
maxAllowedTp = min(live, sniper) + |min(live,sniper) − SL| × 3.0 = 2398 + 5.0×3.0 = 2413.0
2415.68 > 2413.0  ⇒  effectiveTp = 2413.0
```
یعنی سقف عملی هدف با **کوچک‌ترین** فاصلهٔ ورود تا حد ضرر حساب می‌شود (۵ واحد)، نه با `stopDist` (۷ واحد).

**گام ۶ — TradePlanGuard**

ورودی: `entryPrice = sniperOptimal = 2398`, `candidateStopLoss = 2393`, `desiredRiskReward = 2.57`
```
rawStopDist = 5.0 ؛ کف = max(0.80×4, …) = 3.2 ؛ سقف = 2.10×4 = 8.4  ⇒ 5.0 داخل دالان
rawTargetDist = |2413 − 2398| = 15.0 ؛ 15/5 = 3.0 ≥ 1.5 ⇒ همان 15 می‌ماند
targetUpperBound = max(5×1.5, min(5×3.0, targetCeiling)) = 15.0  ⇒ 15.0
R:R نهایی = 3.00
```

**نتیجهٔ گزارش**
```
Entry = 2398.00 │ SL = 2393.00 (فرکتال ۵ کندلی، ۱.۲۵ ATR) │ TP = 2413.00
۱R = 5.00 (۰.۲۱٪ قیمت) │ پاداش = 15.00 │ R:R = 1:3.00  ← قاعده از 2.57 به سقف ۳ رفت
```
درس این مثال: **قفل ۳R و نامتقارنی `min/max` باعث می‌شود سقف ۳.۰ خیلی بیشتر از آنچه به‌نظر
می‌رسد فعال شود.** هر جا پلاتو یا موج، هدف را نزدیک سقف ببرد، Guard آن را روی ۳.۰۰ می‌نشاند.

---

## ۹. از «۱۶ شرط» تا «سیگنال» — منطق آمادگی

```kotlin
kijunGatePassed  = true                    // برای بیس همیشه (لایهٔ کیجون خاموش است)
firstFifteenPassed = همهٔ شرط‌ها به‌جز ۱۶
triggerFired     = triggerType != NONE
allSixteenPassed = همهٔ ۱۶ شرط
```
(خط ۴۱۵۲–۴۱۶۴)

```
baseReadiness =
    همه ۱۶ + ماشه + long      → SIGNAL_BUY
    همه ۱۶ + ماشه + short     → SIGNAL_SELL
    (۱۶ کامل ولی گیت کیجون نه) → BLOCKED        ← برای بیس اتفاق نمی‌افتد
    مسلحِ اسنایپر یا passedCount ≥ 11 → WATCH_SETUP
    else                      → BLOCKED
```
(خط ۴۲۴۱–۴۲۵۲)

```
finalAction =
    وتوی نئوویو                    → NO_TRADE      ← برای بیس هرگز (لایه خاموش)
    آمادگی سیگنال                  → BUY / SELL
    مسلحِ اسنایپر                  → BUY / SELL
    else                           → NO_TRADE
```
(خط ۴۲۷۷–۴۲۹۷)

**پس شرط لازم و کافی برای سیگنال بیس:**
`۱۶ شرط پاس` **و** `triggerType ≠ NONE`.
فقط همین. گیت کیجون، نئوویو، و اجماع ۹ لایه برای بیس وجود ندارند.

### `isActionable` — دروازهٔ مسیر خودکار دوره‌ای

```kotlin
isActionable = (SIGNAL_BUY || SIGNAL_SELL) && action != NO_TRADE
            && suggestedEntry != null && suggestedStopLoss != null && primaryTakeProfit != null
```
(خط ۱۳۳۸)

گزارشِ «مسلحِ اسنایپر» این شرط را ندارد → فقط از مسیر تیک اجرا می‌شود.

### امتیازها

```kotlin
confidence    = (passed / 16) × 84 + (if triggerFired) 16        → 0..100
phaseBonus    = GENESIS +8 │ CORE +5 │ LATE −15 │ CHOP −25
erBonus       = clamp((ER_1H + ER_trigger) × 5, 0, 10)
rrBonus       = clamp(rr × 2, 0, 8)
sniperBonus   = clamp(sniperEntryScore × 0.06, 0, 6)
coreTrendScore = clamp(confidence × 0.74 + phase + er + rr + sniper, 0, 100)
```
(خط ۴۱۶۶–۴۱۷۶). این `coreTrendScore` است که ترتیب اولویت معاملات خودکار را تعیین می‌کند
(`sortedByDescending { it.coreTrendScore }`، خط ۲۰۹۰).

---

## ۱۰. مسیر اجرا — از گزارش تا پوزیشن

### ۱۰.۱ آنکر قیمت لحظه‌ای

گزارش قیمت `sniperOptimal` را پیشنهاد می‌دهد، اما اجرا روی **تیک زنده** لنگر می‌شود:

```kotlin
guarded = TradePlanGuard.rebaseToLive(liveEntryPrice = tick, planAnchorEntry = sniperOptimal, ...)
```
(خط ۲۱۰۹ در `AurumViewModel`، خط ۴۸۹ در Guard)

داخل `rebaseToLive`:
```
drift = |tick − anchor|
limit = max(1.25 × ATR, 3.00 × minStopPct × price)      // stalenessLimit
اگر drift > limit → پلن REJECTED («پلن کهنه است»)
وگرنه SL و TP با همان drift جابه‌جا و دوباره sanitize می‌شوند
```
یعنی اگر بین بستهٔ شدن کندل و رسیدن تیک، قیمت بیش از ۱.۲۵ ATR جابه‌جا شده باشد، سیگنال
سوخته تلقی می‌شود.

### ۱۰.۲ دروازهٔ ایمنی ورود — `EntrySafetyGate`

هفت وتوی مستقل (خط ۱۲۴–۲۲۵):

| # | مانع | پیش‌فرض |
|---|---|---|
| ۱ | سقف پوزیشن باز هم‌زمان | ۳ |
| ۲ | سقف پوزیشن روی یک نماد | ۱ |
| ۳ | سقف ضرر روزانه | از تنظیمات |
| ۴ | سقف افت سرمایه | از تنظیمات |
| ۵ | بازار بسته (`MarketHours`) | — |
| ۶ | انقضای ماشه | ۳ کندل |
| ۷ | نسبت کارمزد رفت‌وبرگشت به ریسک | ≤ ۲۵٪ |

`DOW` در `M1/M3/M5/M15` هم ممنوع است (`LOW_TIMEFRAME_RESTRICTED_SYMBOLS`).

### ۱۰.۳ حجم پوزیشن

```kotlin
calc = RiskAndCalendarEngine.calculatePosition(
    accountBalance, riskPercent, entry, guarded.stopLoss, guarded.takeProfit, ...
)
if (calc == null || calc.riskReward < 1.5) → ورود انجام نمی‌شود
```
`riskPercent` پیش‌فرض ۰.۵٪، کف ۰.۱٪، سقف ۱۰٪ (خط ۸۲۹ در ViewModel). اگر
`useFixedDollarRisk` روشن باشد (پیش‌فرض: بله، ۵ دلار) همان مبلغ ریسک ثابت است.
در بک‌تست: `riskPercent` بین ۰.۰۵٪ و ۵٪ و حجم = `(equity × risk٪) / 1R`.

### ۱۰.۴ قید ضد تکرار

هر ترکیب «استراتژی + نماد + تایم‌فریم» فقط **یک** معاملهٔ باز می‌تواند داشته باشد و هر کندل
ماشه فقط **یک بار** شلیک می‌کند (`hasOpenSameStrategyTf` + `lastAutoFiredBarTimeByKey`، خط ۲۰۷۵–۲۰۸۵).

---

## ۱۱. خروج و مدیریت معامله

سه موتور جدا در سه لایه اجرا می‌شوند و اولین برنده، معامله را می‌بندد.

### ۱۱.۱ برخورد قیمت با SL/TP

مسیر زنده: `evaluateAutoExit` (خط ۴۵۷ در `RiskAndCalendarEngine`) — TP همان‌جا داخل
`[1.5R .. 3.0R]` نسبت به ورود **دوباره** clamp می‌شود (خط ۴۷۸–۴۹۶). یعنی حتی اگر پلن
اشتباه ذخیره شده باشد، خروج همیشه در همان دالان رخ می‌دهد.
همچنین یک محافظ ۱۰ ثانیه‌ای برای کریپتو: در ۱۰ ثانیهٔ اول پس از ورود، حد ضرر ۱۵٪ فاصله‌اش
عقب‌تر می‌رود تا اسپایک/بیسیس، معاملهٔ تازه را استاپ نکند (خط ۴۶۲–۴۷۷).

در بک‌تست: اگر در یک کندل هم SL و هم TP لمس شود، پیش‌فرض **بدبینانه** = حد ضرر
(`optimisticSameBarFill = false`، خط ۴۱۰).

### ۱۱.۲ خروج هوشمند در سود — فقط بعد از +۲R

`MIN_SMART_EXIT_PROFIT_R = 2.0` (خط ۱۴۲۷). قانون: **هیچ‌وقت** قبل از +۲R بسته نمی‌شود،
حتی اگر روند مرده باشد. `inProfit` از `currentR ≥ 0.35` شروع می‌شود اما خروج نه.

`evaluateTrendHealthAndProfitExit` (خط ۴۷۸۸) شش نشانهٔ «اتمام روند» دارد:

| # | نشانه | شرط دقیق | کف R برای خروج |
|---|---|---|---|
| ۱ | شکست کیجون / کراس معکوس TK | `close < kijun − 0.06 ATR` یا `tenkan < kijun − 0.04 ATR && tenkanSlope < −0.03` | هر مقدار ≥ ۲ |
| ۲ | تخلیهٔ C-Clamp | `(distToKijun(1H) ≥ 1.85 یا C-Clamp) && adverseWick ≥ 0.48 && close پشت تنکان` | ≥ ۰.۶۵ و ≥ ۲ |
| ۳ | اتمام چرخهٔ هوسودا | `isNearTimeCycleChangeDay && isTenkanFlat && کندل خلاف && close پشت تنکان` | ≥ ۰.۶۰ و ≥ ۲ |
| ۴ | قفل چیکو + سقوط ER | `!chikouOpenSpace && ER14 < 0.20 && close پشت تنکان && کندل خلاف` | ≥ ۰.۵۵ و ≥ ۲ |
| ۵ | RSI موکرجی | `rsiPeakDropLong ≥ 11.5` (فروش: `rsiTroughRiseShort ≥ 14.0`) `&& close پشت تنکان && کندل خلاف` | ≥ ۰.۷۰ و ≥ ۲ |
| ۶ | ۴ قانون کونیتز | `close < EMA34 && close < kijun && (MACD نزولی \|\| DMI نزولی)` | ≥ ۰.۵۰ و ≥ ۲ |

هشدارها (بدون خروج): `distToKijun ≥ 1.75`، `ER14 < 0.24`، افت RSI ≥ ۹ واحد.
امتیاز تداوم: `92 − 18×(تعداد هشدار) − 35×(نشانهٔ مرگبار) + 8 اگر R ≥ 1.5`، محدود به ۱۰..۹۹.

### ۱۱.۳ سقف ۳R و بستن در هدف

`evaluateOpenTradeManagement` (خط ۴۹۵۱)، به همین ترتیب:

```
۱. اگر خروج هوشمند فعال شد و currentR ≥ 2.0     → بستن در قیمت لحظه‌ای
۲. اگر قیمت به TP رسید
   یا (plannedRR ≤ 3.0001 && currentR ≥ 3.0 && استاپ هنوز قفل نشده) → بستن کامل
۳. اگر enablePreserveMode:
      currentR ≥ 1.0  و هنوز BE نشده  → SL = نقطهٔ ورود        (🛡️ BE)
      currentR ≥ 2.0  و تریل بهتر است → SL = max(Tenkan, live − 0.35 ATR)   (🔒 TRAIL)
```
(خط ۴۹۶۳–۵۰۶۰)

نکتهٔ مهم: برخلاف نسخه‌های قدیمی، حد ضرر **پله‌ای** است (BE در ۱R و تریل در ۲R)، اما
حد سود **هرگز** جابه‌جا نمی‌شود. هندسهٔ اولیهٔ R:R دست‌نخورده می‌ماند.
تریل فقط اگر «بهتر» باشد اعمال می‌شود و `updateTradeStopLoss(allowRatchet = true)` اجازهٔ
عقب‌رفتن نمی‌دهد.

### ۱۱.۴ ترمز زمان — `evaluateTimeStop`

```kotlin
DEFAULT_MAX_HOLD_BARS = 20
MIN_R_AT_TIME_STOP    = 0.5
barsHeld = (now − openedAt) / intervalMillis
if (barsHeld ≥ 20 && به TP نرسیده && currentR < 0.5) → بستن
```
(خط ۵۰۷۲–۵۱۲۵)

۲۰ کندل ۱۵ دقیقه‌ای = ۵ ساعت؛ ۲۰ کندل ۵ دقیقه‌ای = ۱ ساعت و ۴۰ دقیقه.
منطق: ماشهٔ پولبک که در پنجرهٔ خودش به هدف نرسیده، دیگر «پولبک در روند» نیست.

### ۱۱.۵ ترتیب واقعی اجرا در حلقهٔ زنده

```
برای هر معاملهٔ باز:
   ۱. evaluateTimeStop         → اگر باید بسته شود، ببند و continue
   ۲. evaluateOpenTradeManagement → خروج هوشمند / TP / 3R / BE / TRAIL
```

---

## ۱۲. جدول مرجع کامل آستانه‌ها

| پارامتر | مقدار پیش‌فرض | بازهٔ مجاز (پس از clamp) | خط |
|---|---|---|---|
| Tenkan / Kijun / SSB / جابه‌جایی | ۹ / ۲۶ / ۵۲ / ۲۶ | ثابت | ۶۲۲–۶۲۵ |
| حداقل کندل snapshot | ۵۷ | ثابت | ۱۴۵۰ |
| `maxKijunExtensionAtr` | ۱.۸ | ۱.۲۵ .. ۲.۵۰ | ۶۲۶ / ۳۲۰۷ |
| `slBufferAtr` | ۰.۲۵ | ۰.۱۵ .. ۰.۵۵ (در بافر: ۰.۱۸ .. ۰.۴۲) | ۶۲۷ / ۲۴۳۸ |
| `breakoutBufferAtr` | ۰.۱۵ | ۰.۰۵ .. ۰.۳۵ | ۶۲۸ |
| `minRiskReward` | ۱.۵ | کف مطلق ۱.۵ | ۶۲۹ |
| `minEfficiencyRatio` | ۰.۲۰ | ۰.۱۴ .. ۰.۴۵ | ۶۳۶ |
| `minVolumeRatio20` | ۰.۶۰ | ۰.۴۰ .. ۱.۱۵ | ۶۳۷ |
| `maxAdverseWickRatio` | ۰.۴۵ | ۰.۲۸ .. ۰.۶۰ | ۶۳۸ |
| `minFutureKumoThicknessAtr` | ۰.۰۵ | ۰.۰۲ .. ۰.۱۸ | ۶۳۹ |
| `maxSniperDistanceAtr` | ۰.۳۵ | ۰.۲۰ .. ۰.۵۵ (در شرط ۱۶) — از ۳.۹.۵۶ از پروفایل استراتژی خوانده می‌شود | ۶۴۶ / ۳۹۶۶ |
| `baseOptimalRr` (بیس) | ۲.۲۵ | ۱.۵۰ .. ۳.۰۰ | ۴۸ |
| `includeHiddenCrossLayer` | `true` | `true` / `false` (کلید A/B در بک‌تست) | ۶۵۵ |
| بافر فشردگی کاتانا | ۰.۱۸ ATR | — | ۱۶۷۶ |
| حداقل بدنهٔ کندل | ۰.۲۲ | — | ۳۶۸۵ / ۳۹۵۲ |
| CLV خرید / فروش | ≥ ۰.۴۵ / ≤ ۰.۵۵ | — | ۳۶۸۵ |
| شدوی مخالف | ≤ ۰.۴۵ | — | ۳۹۵۰ |
| دُم تأیید جایگزین | ≥ ۰.۲۵ | — | ۳۹۵۲ |
| کف فاصلهٔ SL | ۰.۲۵ ATR | — | ۳۸۶۷ |
| کف / سقف ATR فاصلهٔ SL | ۰.۸۰ / ۲.۱۰ ATR | — | ۲۵۹ |
| سقف هدف | ۳.۴۰ ATR و `1.8 × maxStopPct` | — | ۲۶۰ |
| R:R نهایی | ۱.۵ .. ۳.۰ | قفل مطلق | ۲۰۹–۲۱۰ |
| خروج هوشمند | ≥ ۲.۰R | ثابت | ۱۴۲۷ |
| BE / TRAIL | ۱.۰R / ۲.۰R | ثابت | ۵۰۱۵ / ۵۰۴۳ |
| سقف نگه‌داری | ۲۰ کندل | — | ۵۰۷۲ |
| کف R در تایم‌استاپ | ۰.۵ | — | ۵۰۷۵ |
| انقضای ماشه | ۳ کندل | — | EntrySafetyGate ۳۴ |
| سقف پوزیشن هم‌زمان / هر نماد | ۳ / ۱ | — | ۲۸ / ۳۱ |
| کارمزد به ریسک | ≤ ۲۵٪ | — | ۳۷ |

---

## ۱۳. نقاط ضعف و ناسازگاری‌هایی که در کد دیده می‌شود

این بخش تحلیلی است، نه نقل‌قول از کد. هر مورد با شاهد کد آمده است.
وضعیت هر مورد در نسخهٔ **۳.۹.۵۶ (کد ۷۶۵)** با یکی از این برچسب‌ها مشخص شده:

- ✅ **اصلاح شد** — در همان نسخه تغییر کرد.
- 🟡 **کلید گرفت** — رفتار پیش‌فرض حفظ شد ولی اکنون قابل انتخاب/خاموش‌کردن است.
- ⚪ **عمداً حفظ شد** — بررسی شد و تغییرش ناامن یا نادرست بود.
- ❌ **باقی‌مانده** — شناخته‌شده و اصلاح‌نشده (کاندید نسخهٔ بعد).

### ۱۳.۱ «۱۶ شرط» عملاً ۱۴ شرط مستقل است — ✅ اصلاح شد (شرط مرده) / ⚪ عمدی (کوپل ۱۳ و ۱۵)

- **شرط ۱۵ تابع شرط ۱۳ است.** اگر `cond13Passed == false` باشد، `trendPhase = CHOP_RANGE`
  و شرط ۱۵ قطعاً شکست می‌خورد. اگر ۱۳ پاس شود، ۱۵ فقط با `LATE_EXHAUSTION` می‌شکند.
  یعنی «شمارش ۱۶» در گزارش، وزن شرط ۱۳ را دو برابر می‌کند (خط ۳۷۱۹–۳۷۲۶).
- ✅ **`driveBookRuleOk` همیشه `true` بود** (شرط ۱۱) و در ۳.۹.۵۶ حذف شد؛ صورت شرط حالا
  فقط `directionalCandleOk && volumeMomentumOk` است.
- ❌ **`minDriveBooksConsensus` (۶) و `minSniperDistanceAtr` (۰.۶۵) هیچ‌جا در ارزیابی استفاده
  نمی‌شوند.** فقط در `resolveForStrategy` خوانده و منتقل می‌شوند. یعنی این دو پارامتر در پنل
  تنظیمات وجود دارند اما بر «بیس» اثری ندارند. (در ۳.۹.۵۶ دست نخوردند؛ اصلاحشان یا باید
  فیلتر واقعی بسازد یا از تنظیمات حذف شوند — تصمیم محصولی لازم است.)

### ۱۳.۲ `maxSniperDistanceAtr` از اوررایدها پاس نمی‌شد — ✅ اصلاح شد

در بلوک `effectiveConfig` فیلد `maxSniperDistanceAtr` **بازنویسی نمی‌شد**؛ بنابراین پروفایل
استراتژی و تیونینگ عصبی نمی‌توانستند سقف اسنایپر را تغییر دهند و همه‌چیز همیشه روی ۰.۳۵
می‌ماند. در ۳.۹.۵۶:

```kotlin
maxSniperDistanceAtr = (stratOverride?.maxSniperDistanceAtr ?: modelConfig.maxSniperDistanceAtr)
    .coerceIn(0.20, 0.55)
```

و همان برش در `resolveForStrategy` هم تکرار می‌شود، پس هر دو مسیر (ارزیابی زنده و بک‌تست)
یک عدد می‌بینند. سقف سخت ۰.۵۵ باقی می‌ماند تا «نقطه‌زنی میلی‌متری» شل نشود.

### ۱۳.۳ فاصلهٔ راحتی از کیجون در ورود وجود ندارد؛ فقط سقف دارد — ❌ باقی‌مانده

شرط ۱۶ کلوز را حداکثر ۰.۸۵ ATR از تنکان می‌خواهد، اما هیچ کف فاصله‌ای ندارد. یعنی ورود
«چسبیده به تنکان» هم قبول است. در ترکیب با ماشهٔ ۱ (پولبک)، این می‌تواند ورود در نیمهٔ حرکت
را مجاز کند. `sniperEntryScore` این را جریمه می‌کند (۵۵ درصد به ازای هر ATR) اما هیچ شرطی
بر اساس آن نیست.

### ۱۳.۴ الزام «ماشه» فقط با شمارش شرط سنجیده می‌شود، نه با کیفیت ماشه — ❌ باقی‌مانده

`triggerFired = triggerType != NONE`. هر چهار ماشه هم‌ارز هستند. مثلاً
`SANYAKU_TK_CONTINUATION` با `distanceToKijun ≤ 1.53 ATR` می‌تواند ورود دیرهنگام بسازد،
اما در امتیاز `confidence` فقط ۱۶ درصد ثابت می‌گیرد، مثل ماشهٔ کاتانا. تفکیک کیفیت ماشه
وجود ندارد.

### ۱۳.۵ سقف ۳R سود بالقوه را می‌بندد — ⚪ عمدی

`runnerArmedAbove3R = false` (خط ۳۹۲۶) و بستن در `currentR ≥ 3.0` (خط ۴۹۷۳). در روندهای
هوسودایی بزرگ (موج E با کف ۳.۰ ATR)، هدف واقعی همیشه قربانی سقف ۳R می‌شود. این تصمیم
آگاهانه است (کنترل ریسک) اما یعنی انتظار «موج سوم بزرگ» از این استراتژی واقع‌بینانه نیست.

### ۱۳.۶ حد ضرر «بیس» عملاً حد ضرر پاتل است — ⚪ عمدی

با ترتیب `KIJUN_26 → RIBBON → FRACTAL → KUMO → WAVE_S`، تقریباً همیشه کیجون ۲۶ برنده است
و نقطهٔ S هوسودا آخر. یعنی برخلاف توضیح «هم‌پوشانی ۵ سنگر»، در عمل یک سنگر انتخاب می‌شود.
این باعث حد ضرر تنگ‌تر (خوب برای R:R) اما مستعد استاپ در نویز کیجون است.

### ۱۳.۷ «۵۵ کندل» در شرط ۱۵ بی‌اثر است — ❌ باقی‌مانده

شرط `waveBars > 55 && isTenkanFlat در هر دو لایه` برای `LATE_EXHAUSTION` — اما
`computeHosodaWave` فقط `takeLast(65)` می‌گیرد و `barsSinceWaveStart = lastIdx − idxA + 1`
که سقفش ۶۵ است. در عمل این ترکیب به‌ندرت با `notOverextended` هم‌زمان می‌شود.
اثر عملی شرط ۱۵ تقریباً صفر است.

### ۱۳.۸ تزریق لایهٔ هیدن کراس به «بیس» — 🟡 کلید گرفت

`hiddenCrossWall` و `nearestCrownLevel` و بخش ۹-ب شرط ۹ از موتور هیدن کراس می‌آیند. نتیجه:
«بیس» امروز دقیقاً ترکیب چهار مکتب کلاسیک نیست؛ یک لایهٔ پنجم هم دارد. در ۳.۹.۵۶ این لایه با
`BaseStrategyConfig.includeHiddenCrossLayer` (پیش‌فرض `true` = رفتار فعلی) قابل خاموش‌کردن شد و
در تب بک‌تست یک سوییچ مستقیم دارد، پس مقایسهٔ A/B «بیس کامل» با «بیس خالص چهارگانه» یک اجرا
فاصله دارد.

### ۱۳.۹ تناقض بالقوه در شرط ۱ — ⚪ عمدی

در ماکرو فقط «نبود مخالفت» لازم است، اما `bullishBias` برای تعیین جهت، ماکرو را به‌صورت
**ایجابی** (`isPriceAboveKumo == true` همراه با تایم ماشه) استفاده می‌کند. یعنی جهت و
اعتبارسنجی از دو قاعدهٔ متفاوت می‌آیند (خط ۳۲۲۶ در برابر ۳۲۶۶).

### ۱۳.۱۰ `displacement − 1` — ❌ باقی‌مانده

`disp = displacement − 1` (خط ۱۴۷۲) باعث می‌شود ابر جاری با تأخیر ۲۵ کندل رسم شود نه ۲۶.
این یک انحراف یک‌کندلی از تعریف استاندارد ایچیموکو است و روی همهٔ شرط‌های ابری اثر می‌گذارد.

---

## ۱۴. نقشهٔ کد — کجا چه چیزی را پیدا کنم

| موضوع | تابع / نقطه | خط |
|---|---|---|
| تعریف استراتژی | `enum BitcoinStrategyModel` → `BASE_ICHIMOKU` | ۳۶ |
| دستهٔ ترکیب | `combinationCategoryFa` | ۳۴۰ |
| شناسهٔ پیش‌فرض | `normalizeStrategyId` / `fromId` | ۳۵۷ / ۳۶۸ |
| دفترچهٔ قوانین | `driveBookPlaybook()` → شاخهٔ `else` | ۴۸۲ |
| کانفیگ پایه | `data class BaseStrategyConfig` | ۶۱۸ |
| اوررایدها | `resolveForStrategy` / `resolveForSymbol` | ۷۲۳ / ۷۱۲ |
| snapshot | `computeSnapshot` | ۱۴۴۲ |
| پلاتوها | داخل `computeSnapshot` | ۱۶۲۲ |
| فرکتال ۵ کندلی | داخل `computeSnapshot` | ۱۷۸۹ |
| «تاج» هیدن کراس | داخل `computeSnapshot` | ۱۹۸۵ |
| موج هوسودا | `computeHosodaWave` | ۲۲۱۵ |
| کف ایمن حد ضرر | `minSafeStructuralStopDistance` | ۲۳۶۰ |
| حد ضرر ساختاری | `computeOptimalStructuralStopLoss` | ۲۴۱۰ |
| ترتیب سنگرها | `stopAnchorPriorityFor` | ۲۵۳۰ |
| ارزیابی کامل | `evaluateBitcoinStrategy` | ۳۰۷۵ |
| کانفیگ مؤثر | `effectiveConfig` | ۳۱۹۹ |
| جهت | `candidateDirection` | ۳۲۴۲ |
| شرط ۱ .. ۹ | — | ۳۲۶۰ .. ۳۴۰۹ |
| ناحیهٔ اسنایپر | — | ۳۵۷۶ |
| شرط ۱۰ و ۱۱ | — | ۳۶۷۳ / ۳۶۸۳ |
| R:R بهینه | `computedOptimalRr` | ۳۷۳۶ |
| موج هدف | `chosenWavePair` | ۳۷۶۳ |
| دیوار پلاتو | `nearestObstacle` | ۳۸۰۶ |
| قفل ۱.۵R..۳R | `effectiveTp` | ۳۸۳۵ |
| Guard | `sanitizePlan` | ۳۸۴۸ |
| شرط ۱۲ .. ۱۶ | — | ۳۸۸۱ .. ۳۹۷۵ |
| لیست ۱۶ شرط | `baseSixteenConditions` | ۳۹۷۷ |
| آمادگی | `baseReadiness` | ۴۲۴۱ |
| اکشن نهایی | `finalAction` | ۴۲۷۷ |
| گزارش | `StrategyEvaluationReport` | ۴۳۸۰ |
| ماشه | `detectEntryTrigger` | ۴۶۶۶ |
| خروج هوشمند | `evaluateTrendHealthAndProfitExit` | ۴۷۸۸ |
| مدیریت معامله | `evaluateOpenTradeManagement` | ۴۹۵۱ |
| تایم‌استاپ | `evaluateTimeStop` | ۵۰۹۳ |
| Guard: دالان | `stopDistanceFloor` / `stopDistanceCeiling` | ۲۸۲ / ۳۱۴ |
| Guard: پلن | `sanitizePlan` | ۳۳۵ |
| Guard: آنکر زنده | `rebaseToLive` | ۴۸۹ |
| دروازهٔ ورود | `EntrySafetyGate.evaluate` | ۱۲۴ |
| حجم پوزیشن | `RiskAndCalendarEngine.calculatePosition` | ~۳۳۰ |

---

## ۱۵. خلاصهٔ یک‌خطی هر شرط

| # | شرط | در یک جمله |
|---|---|---|
| ۱ | رژیم کوموی ۳ TF | ۱H و ماشه بالای ابر، ۴H حداقل مخالف نباشد |
| ۲ | سانیاکو | کلوز > ابر، تنکان ≈ بالای کیجون، کیجون ≈ بالای ابر |
| ۳ | شیب TK | تنکان بالای کیجون و کیجون در حال چرخش نزولی نباشد |
| ۴ | ابر آینده | SSA > SSB و SSA در حال رشد و SSB در حال ریزش نیست |
| ۵ | ضخامت ابر | ابر آینده ۱H ≥ ۰.۰۵ ATR و ماشه ≥ ۰.۰۴ ATR |
| ۶ | آزادی چیکو | کلوز از کندل، TK و ابر ۲۶ دورهٔ قبل و ۶ کندل بعدش رد شده |
| ۷ | ساختار چیکو | کلوز از بالاترین/پایین‌ترین کلوز ۲۶ کندل قبل رد شده |
| ۸ | کش کیجون | فاصلهٔ ۱H از کیجون ≤ ۱.۸ ATR و بدون C-Clamp |
| ۹ | جاذبه و دیوار | عبور از SSB فلت با ۰.۱۵ ATR بافر و تاجی در مسیر نباشد |
| ۱۰ | موج هوسودا | نقطهٔ S بالای کف ابر ۱H و چرخهٔ زمانی در نقطهٔ تغییر نباشد |
| ۱۱ | کندل ماشه | هم‌جهت، CLV ≥ ۰.۴۵، بدنه ≥ ۲۲٪، حجم ≥ ۶۰٪ میانگین |
| ۱۲ | R:R و فضا | R:R در ۱.۵..۳.۰ و مانع افقی نزدیک‌تر از ۱.۵R نباشد |
| ۱۳ | کارایی | ER ≥ ۲۰٪ (یا ۲۲٪ در ماشه) و نه رِنج دوگانه و نه تلهٔ کیجون ۱H |
| ۱۴ | ضد فیک | شدوی مخالف ≤ ۴۵٪ و (بدنه ≥ ۲۲٪ یا دُم تأیید ≥ ۲۵٪) |
| ۱۵ | فاز روند | فاز Genesis یا Core؛ نه اشباع و نه رِنج |
| ۱۶ | اسنایپر | ورود ≤ ۰.۳۵ ATR از نقطهٔ بهینه و کلوز ≤ ۰.۸۵ ATR از تنکان |

---

*این سند فقط توصیف کد فعلی است؛ هیچ رفتاری را تغییر نمی‌دهد. هر پیشنهاد بهبود باید جدا و
با آزمون تغییر کند، چون هر عدد در جدول §۱۲ مستقیماً روی آمار بک‌تست اثر می‌گذارد.*
