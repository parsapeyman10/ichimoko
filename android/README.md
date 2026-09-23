# Trading — اپ اندروید

اپ بومی این پروژه: **Kotlin + Jetpack Compose**، بدون WebView و **بدون سرور**.
همه‌چیز روی خود گوشی اجرا می‌شود: دریافت دیتای واقعی، اندیکاتورها، موتور سیگنال، تراز چندتایم‌فریم،
بک‌تست، تست خارج از نمونه، ژورنال کاغذی و مانیتور پس‌زمینه.

## قاعدهٔ داده (سخت‌گیرانه)

- هر کندل/قیمتی که به UI می‌رسد از Twelve Data (REST/WebSocket) یا از کش همان داده‌های واقعی روی
  گوشی می‌آید. هیچ‌جای این ماژول تولیدکنندهٔ کندل، قیمت یا معامله وجود ندارد.
- نبود اینترنت یا کلید = حالت صریح **آفلاین / بدون کلید**؛ آخرین کندل‌های واقعیِ کش‌شده با برچسب
  «داده کش‌شده» نمایش داده می‌شوند، نه یک نسخهٔ دموی ساختگی.
- تنها مفهوم «تمرین» در اپ، یادگیری از همین دیتای واقعی است (بک‌تست و تست خارج از نمونه).

## ساخت

### روی کامپیوتر خودت

```bash
cd android
./gradlew assembleRelease          # ویندوز: gradlew.bat assembleRelease
# خروجی: app/build/outputs/apk/release/app-release.apk
```

با کلید Twelve Data از قبل داخل APK:

```bash
# لینوکس/مک
TD_API_KEY=your-key ./gradlew assembleRelease
# ویندوز PowerShell
$env:TD_API_KEY="your-key"; .\gradlew.bat assembleRelease
```

نیازمندی‌ها: JDK 17 و Android SDK (compileSdk 35، build-tools 35.0.0). Android Studio هر دو را دارد.

### روی GitHub Actions (بدون نصب چیزی)

ورک‌فلوی `Android APK` (فایل `.github/workflows/main.yml`) با هر تغییر در پوشهٔ `android/` روی `main`
یا روی PR اجرا می‌شود و APKها را در Artifacts می‌گذارد: `Trading-release.apk` و `Trading-debug.apk`.
راهنمای گام‌به‌گام (فارسی): [`../docs/ANDROID.md`](../docs/ANDROID.md).

## تست‌های JVM

```bash
cd android
./gradlew testDebugUnitTest
```

`app/src/test/java/com/aurum/edge/engine/EngineTruthTest.kt` چهار سیاست را چک می‌کند:

1. تجمیع کندل‌ها در تایم‌فریم بالاتر، مقادیر OHLC/حجم را دست‌نخورده نگه می‌دارد و باکت ناقص را
   حذف می‌کند (به‌جای پر کردن با داده ساختگی).
2. در تست خارج از نمونه، هر معامله فقط داخل پنجرهٔ خودش شمرده می‌شود.
3. حساب کوچک هیچ‌وقت پوزیشن زیر حداقل لات بروکر (۰.۰۱ لات = ۱ انس) نمی‌گیرد.
4. تراز چندتایم‌فریم تا وقتی تاریخ واقعی کافی نباشد، هیچ نظری نمی‌دهد.

کندل‌های داخل تست فقط ورودی تست‌اند و هرگز به UI یا نتایج کاربر نمی‌رسند.

## ساختار

```text
app/src/main/java/com/aurum/edge/
  core/       Models (کندل، سیگنال، ژورنال، رکورد گزارش‌ها) + AppContainer
  data/       کلاینت Twelve Data، تنظیمات، کش، ژورنال، مخزن مارکت (REST + WebSocket)
  engine/     Indicators · SignalEngine · Backtester (شامل walkForward) · MtfAnalyzer
  notify/     نوتیفیکیشن‌ها
  service/    SignalMonitorService (فورگراند، اختیاری)
  ui/         Chart · Signal · Learn · Journal · Settings + CandleChart (Canvas) + theme
```

## مجوزها

`INTERNET` (دیتای واقعی)، `ACCESS_NETWORK_STATE` (تشخیص آفلاین)، `POST_NOTIFICATIONS` +
`FOREGROUND_SERVICE*` (فقط اگر مانیتور پس‌زمینه را روشن کنی). هیچ دسترسی دیگری لازم نیست و کلید API
فقط در حافظهٔ خصوصی خود اپ ذخیره می‌شود.
