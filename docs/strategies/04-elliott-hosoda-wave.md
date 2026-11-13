# ۴ · مدل تکی ۳: امواج N هوسودا و اعداد زمانی (الیوت/هوسودا)
`ELLIOTT_HOSODA_WAVE` · تکی · هوسودا/الیوت

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `ELLIOTT_HOSODA_WAVE` |
| نشان | تکی · هوسودا/الیوت |
| مکتب‌ها | هوسودا |
| تعداد مکتب | ۱ از ۴ |
| R:R پایه | ۲.۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | Nicole Elliott / Goichi Hosoda |
| انتخاب صریح کاربر | نه — خودکار در رادار |
| یادگیری عصبی | فعال |

## خلاصه
سوار شدن بر موج سوم هوسودا (موج N از ساختار A-B-C) روی نقطه S با محاسبه دقیق اهداف V، N، E، NT و پایش اعداد زمانی 9، 17، 26 و 33.

## دروازه‌هایی که این استراتژی روشن می‌کند
این پنج قاعده، دقیقاً همان چیزی است که کد از پرچم مکتب‌ها می‌سازد (`IchimokuStrategyEngine.kt`، بلوک `effectiveConfig`):

| دروازه | قاعدهٔ کد | وضعیت |
|---|---|---|
| ماشهٔ پولبک | `hasPatel یا hasHosoda یا hasPeloille یا بی‌تاتل` | روشن |
| ماشهٔ شکست | `hasTuttle یا hasHosoda یا hasPeloille یا بی‌پاتل` | روشن |
| الزام همسویی تایم بالا | `hasPeloille یا ترکیب ۲گانه و بیشتر` | خاموش |
| الزام فضای آزاد چیکو | `hasTuttle یا hasPeloille یا ترکیب ۲گانه و بیشتر` | خاموش |
| الزام شیب ابر آینده | `hasPatel یا hasTuttle یا ترکیب ۲گانه و بیشتر` | خاموش |

## شرط‌های ارزیابی

| # | شرط | مربوط به |
|---|---|---|
| ۱ | رژیم کوموی ۳ تایم‌فریم (تایم بالاتر/استراتژی/ماشه) | مشترک |
| ۲ | چیدمان ۵ خط سانیاکو (Sanyaku Order) | مشترک |
| ۴ | جهت و شیب ابر آینده (۲۶ کندل جلوتر) | مشترک |
| ۵ | ضخامت ابر آینده و ایمنی از پیچش (Twist) | مکتب هوسودا |
| ۶ | رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل | مشترک |
| ۱۰ | هندسه موج N هوسودا، نقطه S و چرخه زمانی | مکتب هوسودا |
| ۱۱ | قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol) | مشترک |
| ۱۲ | فرمول و عدد مناسب R:R (حداقل ۱:۱.۵) و فضای باز فلت | مشترک |
| ۱۳ | کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14) | مشترک |
| ۱۴ | فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس | مشترک |
| ۱۵ | شکار اصل روند (فاز Genesis/Core و عدم اشباع) | مشترک |
| ۱۶ | نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket) | مشترک |
| ۱۷ | اقتصاد اجرا — هزینهٔ رفت‌وبرگشت در برابر ۱R | مشترک |

شرط‌های پایه (۱، ۲، ۴، ۶) و لایهٔ کیفیت اجرا (۱۱ تا ۱۷) برای همهٔ ۱۵ روش ایچیموکو یکی است؛ فقط شرط‌های مکتبی (۳، ۵، ۷، ۸، ۹، ۱۰) با پرچم مکتب‌ها روشن/خاموش می‌شوند. توضیح کامل در سند «۰۰ — موتور مشترک».

## کد
### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)
کد شرط‌های مشترک (۱، ۲، ۴، ۶ و ۱۱ تا ۱۷) این‌جا تکرار نشده؛ در سند [00-shared-pipeline.md](00-shared-pipeline.md) است. پایین فقط کد شرط‌های مکتبیِ همین روش آمده.

**Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل)** — خطوط ۲۹۲۶ تا ۲۹۳۶

```kotlin
        // ── Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل) ────
        val minThickStrat = effectiveConfig.minFutureKumoThicknessAtr
        val minThickTrig = (minThickStrat * 0.80).coerceAtLeast(0.02)
        val futureThicknessOk = stratSnap.futureKumoThicknessAtr >= minThickStrat && trigSnap.futureKumoThicknessAtr >= minThickTrig
        val cond5Detail = String.format(
            Locale.US,
            "ضخامت ابر آینده: %.2f ATR (${config.strategyInterval.label}) · %.2f ATR (${config.triggerInterval.label})",
            stratSnap.futureKumoThicknessAtr,
            trigSnap.futureKumoThicknessAtr,
        )
```
**Check 10: Hosoda N-Wave S-Point & Kihon Suchi Time Cycle Guard** — خطوط ۳۱۶۴ تا ۳۱۷۳

```kotlin
        // ── Check 10: Hosoda N-Wave S-Point & Kihon Suchi Time Cycle Guard ────
        val sPointOk = wave?.sPointHeld != false
        val timeCycleExhausted = wave?.isNearTimeCycleChangeDay == true && trigSnap.isTenkanFlat
        val cond10Passed = sPointOk && !timeCycleExhausted
        val cond10Detail = if (wave != null) {
            "موج (${wave.pointA}→${wave.pointB}→${wave.pointC}) · کندل ${wave.barsSinceWaveStart} (چرخه هوسودا: ${wave.nearestTimeCycleNumber})"
        } else {
            "در حال تکمیل ساختار موج هوسودا"
        }
```

## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
| در حال تکمیل ساختار موج هوسودا |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD ELLIOTT_HOSODA_WAVE
tools/strategy_lab/run.sh 8000 11 rng /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD ELLIOTT_HOSODA_WAVE

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "ELLIOTT_HOSODA_WAVE" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/engine/RiskAndCalendarEngineTest.kt`
