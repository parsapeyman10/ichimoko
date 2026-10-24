# راهنمای اپ اندروید Aurum Edge

اپلیکیشن تنها شامل سه صفحهٔ اصلی است:

- `ui/tabs/home/HomeScreen.kt`: صفحهٔ کامل خانه.
- `ui/tabs/chart/ChartScreen.kt` و `TradingViewEmbed.kt`: صفحهٔ کامل چارت TradingView.
- `ui/tabs/update/UpdateScreen.kt`: صفحهٔ کامل بروزرسانی درون‌برنامه‌ای.

سایر تب‌ها، موتورهای استراتژی قبلی، اسکنرها و سرویس‌های پس‌زمینه حذف شده‌اند و فایل‌های دادهٔ قدیمی هنگام اجرای اپ پاک می‌شوند.

## اجرا و آزمون

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```
