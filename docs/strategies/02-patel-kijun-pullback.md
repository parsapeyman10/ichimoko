# ۲ · مدل تکی ۱: پولبک تعادلی کیجون + Preserve Mode (پاتل)
`PATEL_KIJUN_PULLBACK` · تکی · منش پاتل

## مشخصات

| قلم | مقدار |
|---|---|
| شناسهٔ کد | `PATEL_KIJUN_PULLBACK` |
| نشان | تکی · منش پاتل |
| مکتب‌ها | پاتل |
| تعداد مکتب | ۱ از ۴ |
| R:R پایه | ۱.۸۵ (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |
| منبع/مرجع | Manesh Patel (Wiley 2010) |
| انتخاب صریح کاربر | نه — خودکار در رادار |
| یادگیری عصبی | فعال |

## خلاصه
تمرکز بر پولبک قیمت به خط تعادل Kijun-sen در روند هم‌جهت ۳ تایم‌فریم، فیلتر C-Clamp و قفل سود با Preserve Mode روی تنکان.

## دروازه‌هایی که این استراتژی روشن می‌کند
این پنج قاعده، دقیقاً همان چیزی است که کد از پرچم مکتب‌ها می‌سازد (`IchimokuStrategyEngine.kt`، بلوک `effectiveConfig`):

| دروازه | قاعدهٔ کد | وضعیت |
|---|---|---|
| ماشهٔ پولبک | `hasPatel یا hasHosoda یا hasPeloille یا بی‌تاتل` | روشن |
| ماشهٔ شکست | `hasTuttle یا hasHosoda یا hasPeloille یا بی‌پاتل` | خاموش |
| الزام همسویی تایم بالا | `hasPeloille یا ترکیب ۲گانه و بیشتر` | خاموش |
| الزام فضای آزاد چیکو | `hasTuttle یا hasPeloille یا ترکیب ۲گانه و بیشتر` | خاموش |
| الزام شیب ابر آینده | `hasPatel یا hasTuttle یا ترکیب ۲گانه و بیشتر` | روشن |

## شرط‌های ارزیابی

| # | شرط | مربوط به |
|---|---|---|
| ۱ | رژیم کوموی ۳ تایم‌فریم (تایم بالاتر/استراتژی/ماشه) | مشترک |
| ۲ | چیدمان ۵ خط سانیاکو (Sanyaku Order) | مشترک |
| ۳ | کراس و شیب پویا Tenkan / Kijun | مکتب پاتل |
| ۴ | جهت و شیب ابر آینده (۲۶ کندل جلوتر) | مشترک |
| ۶ | رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل | مشترک |
| ۸ | قانون کش لاستیکی کیجون و نبود C-Clamp | مکتب پاتل |
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

**Check 3: Tenkan / Kijun Koten-Gyakuten & Dynamic Equilibrium Slope** — خطوط ۳۶۱۲ تا ۳۶۳۰

```kotlin
        // ── Check 3: Tenkan / Kijun Koten-Gyakuten & Dynamic Equilibrium Slope ──
        val tkAligned = if (isLong) {
            stratSnap.tenkan >= stratSnap.kijun &&
                stratSnap.tenkan >= stratSnap.prevTenkan - stratSnap.atr14 * 0.05 &&
                stratSnap.kijun >= stratSnap.prevKijun - stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan >= trigSnap.prevTenkan - trigSnap.atr14 * 0.05
        } else {
            stratSnap.tenkan <= stratSnap.kijun &&
                stratSnap.tenkan <= stratSnap.prevTenkan + stratSnap.atr14 * 0.05 &&
                stratSnap.kijun <= stratSnap.prevKijun + stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan <= trigSnap.prevTenkan + trigSnap.atr14 * 0.05
        }
        val cond3Detail = String.format(
            Locale.US,
            "شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR",
            trigSnap.tenkanSlopeAtrPerBar,
            trigSnap.kijunSlopeAtrPerBar,
        )
```
**Check 8: Kijun Rubber-Band & Anti-C-Clamp Overextension Filter** — خطوط ۳۶۸۰ تا ۳۶۹۱

```kotlin
        // ── Check 8: Kijun Rubber-Band & Anti-C-Clamp Overextension Filter ─────
        val extAtr = stratSnap.distanceToKijunAtr
        val notOverextended = extAtr <= effectiveConfig.maxKijunExtensionAtr &&
            !stratSnap.isCClampWarning && !trigSnap.isCClampWarning
        val cond8Detail = String.format(
            Locale.US,
            "فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s",
            extAtr,
            effectiveConfig.maxKijunExtensionAtr,
            if (stratSnap.isCClampWarning || trigSnap.isCClampWarning) " · هشدار C-Clamp" else "",
        )
```

## پیام‌هایی که در همین بخش‌ها ساخته می‌شود

| متن |
|---|
| شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR |
| فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s |

## چطور خودت بررسی کنی

```bash
# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip       'xauusd-m15-bid-*' -d /tmp/csv

# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)
tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD PATEL_KIJUN_PULLBACK
tools/strategy_lab/run.sh 8000 11 rng /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD PATEL_KIJUN_PULLBACK

# همهٔ جاهایی که در کد صدا زده می‌شود
grep -rn "PATEL_KIJUN_PULLBACK" android/app/src/main/java/com/aurum/edge | head -20
```

تست‌هایی که همین روش را مستقیم می‌سنجند:

- `android/app/src/test/java/com/aurum/edge/engine/RiskAndCalendarEngineTest.kt`
