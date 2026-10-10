# ممیزی امضای اکشن‌های ۴۰۳، ۴۰۴ و ۴۰۵

این ممیزی روی **APKهای بایگانی‌شدهٔ خود GitHub Actions** انجام شد، نه روی APK نصب‌شدهٔ گوشی. در [اجرای ممیزی](https://github.com/parsapeyman10/ichimoko/actions/runs/38026128370)، با `apksigner verify --print-certs` گواهی، با `aapt dump badging` بسته/کد نسخه و با SHA-256 تمامیت فایل در برابر metadata همان اجرای اکشن بررسی شد.

| شمارهٔ اکشن | رویداد/شاخه | APK ریلیز قابل دریافت؟ | بسته / کد نسخه | SHA-256 گواهی امضا | SHA-256 **فایل APK** |
|---|---|---|---|---|---|
| [۴۰۳](https://github.com/parsapeyman10/ichimoko/actions/runs/37820859939) | push به `arena/db1582f2-ichimoko` | بله، `aurum-edge-1.3.5-403` | `com.aurum.edge` / 403 | `137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04` | `7e2bfae3bd43a23fd4820c8db0a6a30871e63baf364d88f7596b4d78951219ab` |
| [۴۰۴](https://github.com/parsapeyman10/ichimoko/actions/runs/37820865337) | pull_request از همان شاخه | **خیر**؛ مرحلهٔ `Upload APK artifacts` رد شد و فقط گزارش تست بارگذاری شد | قابل استخراج از artifact نیست | **نامعلوم**؛ بدون APK یا لاگ امضای قابل‌دسترسی نباید حدس زد | — |
| [۴۰۵](https://github.com/parsapeyman10/ichimoko/actions/runs/37824496461) | push به `main` | بله، `aurum-edge-1.3.5-405` | `com.aurum.edge` / 405 | `137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04` | `d6ec241013ea67fb076ed9dde98d45fe5623bb1a4e2b2b391f6eeeb4b1784da2` |

**نتیجه:** گواهی امضای APKهای ریلیز ۴۰۳ و ۴۰۵ **یکسان است**؛ SHA-256 *خود فایل‌ها* به‌درستی متفاوت است، چون بیلدها/کد نسخه متفاوت‌اند. فایل فعلی Release عمومی ۱.۳.۵ پس از بازگردانی از artifact اجرای ۴۰۵ همان SHA-256 فایل `d6ec…784da2` را دارد. گواهی ریلیز ۱.۳.۶ شاخه نیز در CI با گواهی فایل عمومی ۴۰۵ برابر بوده است، اما **این ثابت نمی‌کند APK نصب‌شدهٔ کاربر همان فایل ۴۰۵ باشد**. شمارهٔ نسخه به‌تنهایی هویت فایل/کلید را ثابت نمی‌کند.

فایل خصوصی `keystore` در Gitِ `main` نیست؛ workflow کلید را از GitHub Actions Secret یا cache بیرون از Git می‌خواند. برای حل اختلاف گزارش‌شده در گوشی، باید **فایل APK دقیقاً نصب‌شده** یا SHA-256 گواهی *همان فایل* را با `apksigner verify --print-certs` بررسی کرد. کلید خصوصی/رمز را در چت یا Git نفرستید. تا آن تطبیق، گیت انتشار عمومی در این شاخه بدون متغیر تأییدشدهٔ `AURUM_VERIFIED_INSTALLED_CERT_SHA256` متوقف می‌شود؛ حذف برنامه برای حل اختلاف ممکن است ژورنال خصوصی را پاک کند.
