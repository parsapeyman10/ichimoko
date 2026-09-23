# ساخت و نصب APK آزمایشی اندروید

> این مخزن فایل workflow فعال `.github/workflows/main.yml` دارد. کلید API داده‌خوانی عمداً **داخل APK کامپایل نمی‌شود**. APK با نام «release» در CI با کلید **debug** امضا شده و تنها برای تست شخصی است، نه فروش/انتشار.

## ساخت روی رایانه

نیازمندی: JDK 17، Android SDK 35 و build-tools 35.0.0.

```bash
cd android
./gradlew testDebugUnitTest assembleDebug assembleRelease
# ویندوز: gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

خروجی‌ها: `android/app/build/outputs/apk/debug/app-debug.apk` و `.../release/app-release.apk`.

## ساخت با GitHub Actions

روی تغییرات `android/**` در PR و شاخهٔ اصلی، یا با `Run workflow` در **Actions → Android APK**، تست‌های JVM قبل از APK اجرا می‌شوند. اگر تست/بیلد خطا داد **Artifact ساخته نمی‌شود**؛ لاگ مرحلهٔ `Unit tests, then build debug + release APK` را بررسی کن. در اجرای موفق، `aurum-edge-apk` را از Artifacts دانلود و zip را باز کن؛ دو فایل `AurumEdge-debug.apk` و `AurumEdge-release.apk` دارد.

پس از نصب روی گوشی (Android 8+)، اگر فایل APK را از خارج فروشگاه نصب می‌کنی اجازهٔ «نصب از این منبع» را فقط برای مدیر فایل مورد استفاده بده. کلید داده‌خوانی Twelve Data را **در خود تب تنظیمات برنامه** وارد کن؛ کلید معاملاتی را آنجا وارد نکن. برای دیده‌بان منابع عمومی، کلید چارت لازم نیست. اخبار فارسی نیازمند سرور HTTPS و فید قانونی پیکربندی‌شده است. آدرس `localhost` روی گوشی آدرس سیستم توسعه نیست.

برای وضعیت واقعی همهٔ قابلیت‌ها، اتصال news/MetaTrader و الزامات سفارش واقعی: [ROADMAP_FA.md](ROADMAP_FA.md).
