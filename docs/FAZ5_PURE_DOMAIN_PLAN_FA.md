# فاز ۵ — لایهٔ domain خالص و جابجایی AppContainer (طرح disastro-free)

این فایل **طرح التصميم** است، نه کد اجرا شده. هیچ فایل کد در این فاز تغییر نکرده است.
دلیل: جابجایی `MarketState` و وابستگانش (۶ نوع از `core/Models.kt`) **+`AppContainer` از `core/` به `app/`** باعث تغییر ۱۰۰+ خط import در ]);

بدون JDK/Gradle امکان کامپایل نیست، بنابراین یک import فراموش شده یعنی شکست 빌ド. این طرح را در časِ دسترسی به دستگاه واقعی اجرا کنید.

---

## ۱.現状 —where the layering breaks

### وابستگی‌های core → data (enjoy barrier)
| فایل | خط | imported از data | دلیل |
|---|---|---|---|
| `core/AppContainer.kt` | 5–26 | ۲۲ کلاس (.repository, stores, clients, coordinator…) | ریشهٔ ترکیب (DI) — مشروع، باید در `app/` باشد نه `core/` |
| `core/AlertDiagnostics.kt` | 3 | `MarketState` | قانون/domain — باید `MarketState` را از `domain/` بخواند |
| `core/IctEntryRules.kt` | 3 | `MarketState` | قوانین ورود — باید `domain/` |
| `core/PaperAlertRules.kt` | 3 | `MarketState` | قوانین هشدار — باید `domain/` |
| `core/PaperAutoRules.kt` | 3 | `MarketState` | قوانین ورود خودکار — باید `domain/` |

### وابستگی‌های data → core (intended direction, 144 import)
داده به درین است — صحیح است، تغییر نمی‌کند.

### مشکل crirical
`MarketState` در `data/MarketRepository.kt:53` تعریف شده و از ۶ نوع در `core/Models.kt` استفاده می‌کند:
- `Interval`, `Candle`, `Signal`, `SignalAction`, `FeedMode`, `FeedStatus`

اگر فقط `MarketState` را به `domain/` جابجا کنیم و ۶ نوع در `core/` بمانیم:
- `domain → core` (چون `MarketState` از انواع core استفاده می‌کند)
- `core → domain` (چون ۴ قانون core از `MarketState` استفاده می‌کنند)
- **چرخه وابستگی (circular)**: `core ↔ domain` — نامفهوم و نامسئولانه.

---

## ۲. هدف معماری

```
                    UI (Compose, ViewModel)
                           │
                    StateFlow / Intent
                           │
         ┌─────────────────▼─────────────────┐
         │ domain/  (پراتی، بدون وابستگی به data/core) │
         │   MarketState, Interval, Candle, Signal,   │
         │   SignalAction, FeedMode, FeedStatus        │
         │   StrategySpec, StrategyEvaluator (فاز ۴.۳)   │
         └─────────────────┬─────────────────┘
                           │
         ┌─────────────────▼─────────────────┐
         │ data/  (مخازن، کلاینت‌ها، کش، پایگاه داده) │
         │   MarketRepository (ساز MarketState از domain) │
         │   Stores, Clients, WatchRepository …           │
         └─────────────────┬─────────────────┘
                           │
         ┌─────────────────▼─────────────────┐
         │ app/  (composition root)             │
         │   AppContainer (DI، سیم‌کشی تمام لایه‌ها)   │
         │   AurumApplication                     │
         └─────────────────┬─────────────────┘
                           │
                    UI (ViewModel, Screen‌ها)
```

**قاعده طلایی:** `domain` به هیچ لایه‌ای وابسته نیست. `data` به `domain` وابسته است. `app` (AppContainer) به هر دو وابسته است.

---

## ۳. فایل‌های جدیدی که باید ساخته شود

### `domain/MarketState.kt`
نسخهٔ دقیق `data class MarketState` فعلی (از `data/MarketRepository.kt:53`) با imports از `domain/` فقط (یعنی ۶ نوع را هم در `domain/` می‌آوریم).

### `domain/Interval.kt`، `domain/Candle.kt`، `domain/Signal.kt`، `domain/SignalAction.kt`، `domain/FeedMode.kt`، `domain/FeedStatus.kt`
کپی دقیق از `core/Models.kt` با همان کد، تغییر نام پکیج به `com.aurum.edge.domain`.

> **نکته:** `core/Models.kt` بعد از جابجایی ۶ نوع، ۶ type کم می‌کند. بقیه انواع در `Models.kt` permanec می‌کنند. هیچ فایلی که از Types باقی‌مانده import می‌کند، stuk نمی‌خورد.

---

## ۴. ترتیب مهاجرت (مرحله‌ای، هر مرحلهٔ پس از اجرا باید بازبینی ایستا شود)

### مرحلهٔ ۵.۱ — ساخت domain/ و جابجایی ۶ نوع پایه (بدون touching core)
1. `domain/Interval.kt` بسازید (کپی از `core/Models.kt:26`)
2. `domain/Candle.kt` بسازید (کپی از `core/Models.kt:13`)
3. `domain/SignalAction.kt` بسازید (کپی از `core/Models.kt:48`)
4. `domain/Signal.kt` بسازید (کپی از `core/Models.kt:292`)
5. `domain/FeedMode.kt` بسازید (کپی از `core/Models.kt:319`)
6. `domain/FeedStatus.kt` بسازید (کپی از `core/Models.kt:329`)
7. **تغییر import** در **همهٔ فایل‌هایی که این ۶ نوع را از `core.` import می‌کنند** به `domain.`:
   - `Interval`: ۳۵ فایل
   - `Candle`: ۳۳ فایل
   - `SignalAction`: ۲۵ فایل
   - `Signal`: ۱۲ فایل
   - `FeedStatus`: ۳ فایل
   - `FeedMode`: ۵ فایل
   - (یک فایل ممکن است چندین نوع import کند — gathered union حدود ۸۰–۱۰۰ فایل)

> ⚠️ **بدون کامپایل، import فراموش شده = شکستビルド.** هر فایل را پس از تغییر import بازبینی کنید (class balanced، referenced type exists).

### مرحلهٔ ۵.۲ — جابجایی MarketState به domain/
1. `domain/MarketState.kt` بسازید (کپی از `data/MarketRepository.kt:53`، importها را به `domain.` تغییر دهید)
2. `data/MarketRepository.kt` —delete define `MarketState`، import `MarketState` را از `domain.` اضافه کنید
3. **تغییر import** در ۵ فایل که `MarketState` را از `data.` import می‌کنند:
   - `core/AlertDiagnostics.kt` → `import com.aurum.edge.domain.MarketState`
   - `core/IctEntryRules.kt` → همان
   - `core/PaperAlertRules.kt` → همان
   - `core/PaperAutoRules.kt` → همان
   - `core/AppContainer.kt` → `import com.aurum.edge.domain.MarketState` ( AppContainer هم از `data.` import می‌کند )
4. در فایل‌های `data/` که `MarketState` را استفاده می‌کنند (`CatalogPaperTrader`, `OpenPositionQuotePolicy`, `PairScanner`, `PaperAutoTrader`)، import را از `domain.` کنید.

> ⚠️ `data/` به `domain/` وابسته می‌شود — **دستورالعمل معماری درست است** (data lies on domain).

### مرحلهٔ ۵.۳ — جابجایی AppContainer به app/
1. پکیج `app/` بسازید (مثلاً `com.aurum.edge.app.AppContainer`)
2. `core/AppContainer.kt` را به `app/AppContainer.kt` جابجا کنید (package را تغییر دهید، imports را حفظ کنید)
3. **تغییر import** در ۲ فایل:
   - `AurumApplication.kt:4` → `import com.aurum.edge.app.AppContainer`
   - `ui/shell/AurumViewModel.kt:12` → `import com.aurum.edge.app.AppContainer`
4. AppContainer قبلاً ۲۲ import از `data.` داشت که همگی در `app/`with همان importها کار می‌کنند (چون `app` می‌تواند به `data` import کند).

> بعد از این مرحله، `core/` هیچ import از `data/` ندارد (core→data violation حذف شد).

### مرحلهٔ ۵.۴ — تمیزپایی core/Models.kt
6 نوع جابجا شده را از `core/Models.kt` حذف کنید (فقط همان ۶ نوع، بقیه unchanged بمانند).

---

## ۵. چرا کد در این جلسه تغییر نکرده است

| действие | ریسک بدون کامپایل | دلیل |
|---|---|---|
| جابجایی ۶ نوع به `domain/` + ۱۰۰+ import تغییر | **بالا** | یک import فراموش شده = fail 빌د، و بدون JDK به-built نمی‌شود تشخیص داده نمی‌شود |
| جابجایی `MarketState` + ۵ import تغییر | **متوسط** |データ-файルها باید import جدید را داشته باشند |
| جابجایی `AppContainer` به `app/` + ۲ import تغییر | **کم** | اما depende به مرحلهٔ ۵.۱–۵.۳ است |
| delete کردن ۶ نوع از `core/Models.kt` | **بالا** | اگر یک فایل از نوع حذف شده import کند و import را تغییر نداده باشد، 빌د شکست می‌خورد |

به همین دلیل فاز ۵ را **طرح-designed** نگه داشته‌ام. وقتی به JDK/Gradle دسترسی داشتید، مراحل ۵.۱ → ۵.۲ → ۵.۳ → ۵.۴ را به ترتیب اجرا کنید و بعد از هر مرحلهٔ commit کنید.

---

## ۶. چک‌لیست برای اجرا (وقتی کامپایل 가능 شود)

- [ ] `domain/` ساخته شده و ۶ نوع پایه کپی شده‌اند
- [ ] تمام importهای ۶ نوع (حدود ۸۰–۱۰۰ فایل) به `domain.` تغییر یافته‌اند
- [ ] `domain/MarketState.kt` ساخته شده، `data/MarketRepository.kt` از آن import می‌کند
- [ ] ۵ import `MarketState` در `core/` به `domain.` تغییر یافته‌اند
- [ ] فایل‌های `data/` که `MarketState` استفاده می‌کنند، از `domain.` import می‌کنند
- [ ] `AppContainer` به `app/` جابجا شده، ۲ import سایت-update شده‌اند
- [ ] ۶ نوع حذف شده‌اند از `core/Models.kt`
- [ ] `git diff --stat` حاوی فقط فایل‌های منتظر شده است
- [ ] پروژه در Android Studio کامپایل می‌شود ( tramo没有 خطای unresolved import)

---

## پیوند به فاز ۴.۳

`StrategySpec` و `StrategyEvaluator` (فاز ۴.۳، فایل `engine/StrategySpec.kt` اضافه شده در کامیت `2d8e9e1`) در `engine/` قرار دارند. در فاز ۵، می‌توان این دو را هم به `domain/` منتقل کرد چون-logیک خالصی دارند و به هیچ لایه‌ی زیرساختی وابسته نیستند. این تصمیم برای Rollins_left است.

---

**وضعیت کنونی:** کامیت `2d8e9e1` (فاز ۴.۳ اضافه شده)، فاز ۵ فقط طرح.Desktop است. هیچ کد در فاز ۵ تغییر نکرده است.
