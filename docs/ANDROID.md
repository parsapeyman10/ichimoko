# ساخت و نصب APK آزمایشی اندروید

> این مخزن فایل workflow فعال `.github/workflows/main.yml` دارد. کلید API داده‌خوانی عمداً **داخل APK کامپایل نمی‌شود**. APK با نام «release» در CI فعلی با کلید **debug** ساخته می‌شود و تنها برای تست شخصی است، نه فروش/انتشار. اخطار «سازنده ناشناخته» Play Protect را نمی‌توان از درون APK خاموش کرد؛ [راهنمای نصب GitHub و امضای مالک](PLAY_PROTECT_FA.md).

## ساخت روی رایانه

نیازمندی: JDK 17، Android SDK 35 و build-tools 35.0.0.

```bash
cd android
./gradlew testDebugUnitTest assembleDebug assembleRelease
# ویندوز: gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

خروجی‌ها: `android/app/build/outputs/apk/debug/app-debug.apk` و `.../release/app-release.apk`.

## ساخت با GitHub Actions

روی تغییرات `android/**` در PR و شاخهٔ اصلی، یا با `Run workflow` در **Actions → Android APK**، ابتدا تست JVM و سپس بیلد انجام می‌شود. تست ناموفق جلوی ساخت/آپلود APK را می‌گیرد. لاگ ساخت و تست‌ها را نیز بررسی کن. در اجرای موفق، artifact نسخهٔ اجرا را از Artifacts دانلود و zip را باز کن؛ APKهای preview داخل آن هستند، اما هیچ artifact موقتی مسیر بروزرسانی داخل اپ نیست.

برای این‌که APK جدید روی نصب قبلی **آپدیت** شود باید هر سه شرط برقرار باشد: شناسهٔ پکیج همان `com.aurum.edge` بماند، `versionCode` بالاتر باشد (نسخهٔ فعلی `12` / `1.2.9` است)، و امضای APK با همان کلید نسخهٔ قبلی انجام شود. تب «بروزرسانی» هم manifest عمومی و هم GitHub Releases عمومی را بررسی می‌کند؛ اگر manifest فقط metadata داشته باشد، Release دارای APK دیگر پنهان نمی‌شود. با روشن‌کردن «دانلود خودکار نسخهٔ جدید»، بررسی هنگام باز شدن برنامه انجام می‌شود و APK پس از اعتبارسنجی دانلود و نصاب Android باز می‌شود. Android نصب بی‌صدا را مجاز نمی‌کند؛ اولین نصب/بازگشت از تنظیمات فقط یک تأیید کاربر و اجازهٔ «نصب از این منبع» می‌خواهد. artifact خام Actions برای موبایل استفاده نمی‌شود چون می‌تواند 401 بدهد.

برای انتشار واقعی، `versionCode`/`versionName` را افزایش دهید و tag دقیق `v<versionName>` بسازید؛ workflow روی tag پس از test، lint، کنترل package، حجم و SHA-256، asset نسخه‌دار `AurumEdge-v<versionName>.apk` را در GitHub Release عمومی منتشر می‌کند. updater فقط همین نام و مسیر release همین مخزن را می‌پذیرد، package، گواهی امضای APK و metadata حجم/SHA-256 (اگر کنار release منتشر شده باشد) را قبل از بازکردن Package Installer بررسی می‌کند و debug/asset متفرقه را رد می‌کند. چهار secret ورودی امضای CI عبارت‌اند از `AURUM_RELEASE_KEYSTORE_BASE64`، `AURUM_RELEASE_STORE_PASSWORD`، `AURUM_RELEASE_KEY_ALIAS` و `AURUM_RELEASE_KEY_PASSWORD`؛ keystore هرگز داخل repository نیست. `android/tools/build-owner-apk.sh` هم مسیر ساخت مالک‌امضا با keystore ثابت است؛ APKهای debug/CI بدون این کلید برای آپدیت نسخهٔ نصب‌شده قابل اعتماد نیستند. پس از نصب، برای پرشدن خودکار ژورنال باید در تنظیمات ابتدا «پایش پس‌زمینه» و بعد «ورود خودکار کاغذی» را فعال کنید؛ این مسیر فقط روی قیمت واقعی رکورد PAPER می‌سازد و سفارش واقعی ارسال نمی‌کند.

پس از نصب روی گوشی (Android 8+)، اگر فایل APK را از خارج فروشگاه نصب می‌کنی اجازهٔ «نصب از این منبع» را فقط برای مدیر فایل مورد استفاده بده. کلید داده‌خوانی Twelve Data را **در خود تب تنظیمات برنامه** وارد کن؛ کلید معاملاتی را آنجا وارد نکن. برای دیده‌بان منابع عمومی، کلید چارت لازم نیست. تیترهای مستقیم «اخبار وب» و تقویم Forex Factory بدون بک‌اند در گوشی دریافت می‌شوند؛ فقط **لایهٔ خبر/AI و هشدار واجد شرایط** نیازمند بک‌اند پروژه روی دامنهٔ HTTPS عمومی هستند. فیدهای RSS عمومی ناشران در خود بک‌اند تعریف شده‌اند. اسکرپ صفحهٔ محدود/سفارش واقعی فعال نیست. آدرس `localhost` روی گوشی آدرس سیستم توسعه نیست. این نسخهٔ اپ فقط فارکس است: نمادهای XAU/USD و جفت‌ارزهای اصلی؛ فضاهای نوبیتکس/بورس/کریپتو حذف شده‌اند.

برای مراحل تست اعلان صدای دلخواه و ژورنال شرایط: [ALERTS_FA.md](ALERTS_FA.md). برای وضعیت همهٔ قابلیت‌ها، اتصال news/MetaTrader و الزامات سفارش واقعی: [ROADMAP_FA.md](ROADMAP_FA.md).
