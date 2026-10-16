# ساخت و تأیید نسخهٔ ۶۷۲ (v2.0.0)

گزارش کوتاه یک جلسهٔ ساخت. مرجع قوانین محصول همچنان [`CURRENT_CONTRACT_FA.md`](CURRENT_CONTRACT_FA.md) است.

## نتیجه

- APK نسخهٔ ۶۷۲ (`versionCode = 672`, `versionName = 2.0.0`) با کلید ثابت پروژه امضا و بیرون از مخزن تحویل شد؛ بایت‌های APK در گیت نگه داشته نمی‌شوند.
- نسخهٔ عمومی همان نسخه در Release با برچسب `v2.0.0` است: `https://github.com/parsapeyman10/ichimoko/releases/download/v2.0.0/AurumEdge-v2.0.0.apk`.

## پیش‌نیازها در این محیط

| مورد | وضعیت |
|---|---|
| JDK 17 (Temurin 17.0.9) | نصب شد: `pip install jdk4py==17.0.9.2` |
| `aiohttp==3.13.3`, `centrifuge-python==0.6.0` | در `tick-archive/.venv` |
| `cryptography`, `PyYAML` | برای بازبینی خروجی APK |
| Android SDK 35، build-tools 35.0.0، Gradle 8.9 | در این محیط قابل دانلود نیست (دسترسی شبکه محدود به GitHub/npm/PyPI است؛ `dl.google.com` و مخازن Maven بسته‌اند) → ساخت با CI خود مخزن انجام شد |

## ساخت

ورک‌فلوی `.github/workflows/main.yml` در run `38781911466` روی شاخهٔ همین جلسه: نصب SDK، `lintDebug`، `testDebugUnitTest`، تست ابزارهای پایتون، `assembleDebug`/`assembleRelease`، `apksigner verify` و کنترل تداوم امضا با نسخهٔ منتشرشده — همه موفق.

خروجی Actions از این محیط قابل دانلود نیست، بنابراین یک **مرحلهٔ موقت** به ورک‌فلو اضافه شد که بایت‌های ساخته‌شده را روی همان شاخه کامیت می‌کرد. آن مرحله و کامیت‌هایش حالا حذف شده‌اند و ورک‌فلو دوباره بیت‌به‌بیت همان فایل مبنای مخزن است.

## تأییدها

- `sha256` ساخت تازه: `5f0d1ac5af3736b8fd00b0480cb1205b569fd4b40ec8100d492e3e547e76e4ab`؛ نسخهٔ عمومی Release: `4215a49987043719490633453d9aca169a91a7982d9992ec06991500a90a3d08` (هر دو ۱۲٬۴۵۸٬۹۱۷ بایت).
- `upgrade-compatibility.json` تولیدشده در CI: `applicationId = com.aurum.edge`، `versionCode = 672`، `minSdk = 26`، `targetSdk = 35`، ABIها `arm64-v8a` و `armeabi-v7a`، `debuggable = false`، `embeddedProxyEntries = []`، `torAbis = []`.
- گواهی امضای هر دو APK: `137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04` (همان مقدار پین‌شدهٔ [`update/updater-signer.sha256`](../update/updater-signer.sha256))، کلید RSA-4096 با اعتبار تا ۲۰۳۶.
- بازبینی مستقل و بدون Android SDK با پارس مستقیم APK Signing Block: امضای v2 معتبر و گواهی برابر مقدار پین‌شده.
- `requiresAndroidInstallerConfirmation = true` و `physicalDeviceInstallTested = false`؛ نصب روی گوشی واقعی آزموده نشده است.

## نصب

package و گواهی با نسخهٔ نصب‌شده یکی است، پس نصب روی نسخهٔ فعلی بدون حذف اطلاعات و بدون پاک شدن ژورنال ممکن است. اگر اندروید نصب را رد کرد، اثر انگشت گواهی نسخهٔ نصب‌شده در تب بروزرسانی باید با مقدار بند بالا مقایسه شود؛ در صورت اختلاف، حذف نصب پیش از تهیهٔ نسخهٔ پشتیبان انجام نشود.
