# نسخهٔ ۳.۰.۰ — بستهٔ کامل ساخت و بروزرسانی شفاف

این سند مرجع نسخهٔ **۳.۰.۰ (کد ۷۰۰)** است: چرا این نسخه وجود دارد، دقیقاً چه چیزی در آن
عوض شده، چطور ساخته و منتشر می‌شود، و بعد از انتشار چه چیزی باید بررسی شود.

> مرجع قوانین محصول همان [`CURRENT_CONTRACT_FA.md`](CURRENT_CONTRACT_FA.md) است. اگر بین این
> سند و آن یکی اختلاف بود، قرارداد فعلی ملاک است.

---

## ۱) چرا ۳.۰.۰؟

نسخهٔ ۲.۰.۱۱ در GitHub منتشر شد، اما merge نهایی آن در شاخهٔ `main` فقط **۳۱ فایل** را در مخزن
نگه داشته بود؛ صدها فایل Kotlin/Compose که برای ساخت APK لازم‌اند (مدل‌ها، موتورها،
رابط کاربری، سرویس پس‌زمینه، تست‌ها، ابزارهای Python و متادیتای بروزرسانی) در درخت نبودند.
در نتیجه:

- `./gradlew assembleRelease` به‌خاطر کلاس‌های گم‌شده شکست می‌خورد؛
- CI هم نمی‌توانست APK تازه بسازد و فقط به asset قدیمی تکیه می‌کرد؛
- صفحهٔ بروزرسانی درست بود، اما نسخه‌ای برای بروزرسانی وجود نداشت.

۳.۰.۰ همان کمبود را برمی‌گرداند و شمارهٔ نسخه را یک پلهٔ major جلو می‌برد تا نصب‌های ۲.x
بتوانند با همان گواهی به آن بروزرسانی کنند. **هیچ قانون معاملاتی در این نسخه عوض نشده
است.**

## ۲) دامنهٔ این نسخه

| موضوع | وضعیت در ۳.۰.۰ |
|---|---|
| درخت منبع `android/` | کامل؛ ۲۴۷ فایل Kotlin در `main`/`test` به‌همراه منابع، Gradle wrapper و تست‌ها |
| `versionName` | `3.0.0` |
| `versionCode` | `700` (نسخهٔ قبلی: `۲.۰.۱۱` با کد `۶۸۳`) |
| `applicationId` | `com.aurum.edge` (بدون تغییر؛ نام بسته شرط بروزرسانی است) |
| `minSdk` / `targetSdk` / `compileSdk` | `26` / `35` / `35` |
| ABIها | `arm64-v8a`، `armeabi-v7a` |
| امضا | همان گواهی pin‌شدهٔ کانال: `137f3f98…4a04` |
| موتورها و قوانین ریسک | بدون تغییر نسبت به ۲.۰.۱۱ |
| صفحهٔ بروزرسانی | بازطراحی نمایشی (بدون تغییر منطق امنیتی) |

## ۳) صفحهٔ بروزرسانی (همان اطلاعات تصویر)

صفحهٔ **بیشتر → بروزرسانی** از بالا به پایین این‌ها را نشان می‌دهد:

1. **سه کارت:** «نسخه فعلی» (`3.0.0`)، «کد نسخه» (`700`) و «Commit» (هفت کاراکتر اول
   `BuildConfig.GIT_SHA`).
2. **شناسهٔ نصب:** `com.aurum.edge`.
3. **SHA-256 گواهی نصب فعلی:** هش واقعی بستهٔ نصب‌شده روی همین گوشی (از `PackageManager`،
   نه از CI). اگر خوانده نشود، صریح «قابل خواندن نیست» می‌نویسد.
4. **SHA-256 کانال Release:** همان مقداری که در `update/updater-signer.sha256` و
   `BuildConfig.UPDATER_CERT_SHA256` pin شده است.
5. **حکم مقایسه:** «امضای نصب با کانال انتشار یکسان است؛ نصب نهایی با Android است.» یا هشدار
   صریح در صورت تفاوت. این حکم از `AppUpdateRepository.signingSummary()` می‌آید تا متنِ صفحه و
   منطقِ گیتِ نصب یکی باشند.
6. **دانلود خودکار نسخهٔ جدید:** سوییچ با حالت برجسته (قاب طلایی) و توضیح اینکه نصب نهایی
   همیشه یک تأیید Android لازم دارد.
7. **پیام وضعیت + گزارش تشخیص:** نتیجهٔ هر سه کانال؛ مثلاً
   `manifest: 2.0.11 (683) · v2.0.11 · releases: 2.0.11 (683) · v2.0.11`.
8. **دو دکمه:** «بررسی نسخه جدید» (همیشه فوری) و «دانلود و نصب» (تا وجود APK عمومی با
   `https` غیرفعال است).
9. **کارت‌های «سازگاری و محدودیت Android»** و **«نسخهٔ ۳ — چه چیزی آماده شد»**.

### کانال‌های بررسی

1. `releases/latest/download/aurum-edge.json` — manifest کنار APK در آخرین Release عمومی.
2. `raw.githubusercontent.com/.../main/update/aurum-edge.json` — manifest شاخهٔ پیش‌فرض.
3. فهرست Releases از GitHub API — اگر هر دو manifest قدیمی یا ناقص باشند.

بررسی خودکار (هنگام باز شدن اپ یا روشن بودن سوییچ) **حداکثر هر ۱۵ دقیقه** یک‌بار انجام
می‌شود؛ دکمهٔ بررسی این فاصله را دور می‌زند. علت: سهمیهٔ بی‌توکن GitHub API برای هر IP
مشترک است و درخواست پشت‌سرهم آن را با خطای ۴۰۱/۴۰۳ تمام می‌کند. artifact خام GitHub Actions
هیچ‌وقت کاندید نصب نیست (بدون ورود، ۴۰۱ می‌دهد).

## ۴) ساخت

پیش‌نیاز: JDK 17 و Android SDK 35 با build-tools 35.0.0.

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

خروجی‌ها:

- `android/app/build/outputs/apk/debug/app-debug.apk` — بستهٔ `com.aurum.edge.debug`؛
  روی نصب عمومی نمی‌نشیند.
- `android/app/build/outputs/apk/release/app-release.apk` — اگر چهار متغیر
  `AURUM_RELEASE_*` تنظیم شده باشند با کلید مالک، وگرنه با کلید debug (فقط پیش‌نمایش).

آزمون‌های سمت ابزار (بدون شبکه، روی همین درخت):

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v   # ۱۳۰ آزمون، همه سبز
```

## ۵) انتشار عمومی ۳.۰.۰

انتشار فقط در سه حالت مجاز است (جزئیات در [`UPDATER_RELEASE_FA.md`](UPDATER_RELEASE_FA.md)):

```bash
# ۱) از همان شاخه/شاخهٔ main با انتخاب صریح نسخه
gh workflow run main.yml --ref main \
  -f version_name=3.0.0 -f version_code=700 -f publish_release=true

# ۲) یا push یک تگ
git tag v3.0.0 && git push origin v3.0.0

# ۳) یا commit با پیام شروع‌شده با  "release: publish "
```

انتشار **بدون** کلید ثابت `AURUM_RELEASE_*` ممنوع است؛ وگرنه APK با امضای debug ساخته می‌شود و
Android آن را روی نصب ۲.x نمی‌پذیرد. کلیدها فقط در Secrets نگهداری می‌شوند
([`BUILD_SIGNING_FA.md`](BUILD_SIGNING_FA.md)).

بعد از انتشار، `update/aurum-edge.json` باید به همان تگ اشاره کند:

```bash
python3 tools/refresh_update_manifest.py --tag v3.0.0
git add update/aurum-edge.json && git commit -m "release: publish v3.0.0 (700)"
```

مرحلهٔ CI «Verify default-branch manifest points at the newest release» اگر manifest از
جدیدترین APK منتشرشده عقب بماند، با خطای صریح متوقف می‌شود.

## ۶) چک‌لیست پس از انتشار

- [ ] `gh release view v3.0.0` سه asset دارد: APK، `AurumEdge-v3.0.0.apk.json`،
      `checksums.sha256`.
- [ ] `sha256` و `sizeBytes` در manifest با asset یکی است.
- [ ] روی گوشی: نسخهٔ ۳ از منوی **بیشتر → بروزرسانی** نصب شده و هر سه کارت `3.0.0 / 700 /
      commit` را نشان می‌دهد.
- [ ] دو هش گواهی (نصب و کانال) یکسان‌اند؛ اگر نیستند، APK با کلید دیگری امضا شده است.
- [ ] دکمهٔ «بررسی نسخه جدید» پیام «نسخهٔ جدیدتری پیدا نشد» می‌دهد؛ یعنی نصب همان آخرین
      انتشار است، نه اینکه بررسی شکست خورده باشد.
- [ ] با نسخهٔ بالاتر بعدی (مثلاً ۷۰۱) همین صفحه باید «نسخه جدید پیدا شد» و دکمهٔ فعال
      «دانلود و نصب» را نشان دهد.

## ۷) فایل‌های کلیدی

| مسیر | نقش |
|---|---|
| `android/app/build.gradle.kts` | `versionCode` / `versionName`، `BuildConfig` بروزرسانی |
| `android/app/src/main/java/com/aurum/edge/data/AppUpdateRepository.kt` | سه کانال، گیت امضا/نسخه/هش، دانلود، نصاب |
| `android/app/src/main/java/com/aurum/edge/ui/tabs/update/UpdateScreen.kt` | صفحهٔ تصویرشده |
| `update/aurum-edge.json` | manifest شاخهٔ پیش‌فرض (اکنون `v2.0.11`) |
| `update/updater-signer.sha256` | گواهی pin‌شدهٔ کانال |
| `.github/workflows/main.yml` | تست، lint، ساخت، گیت manifest و انتشار |
