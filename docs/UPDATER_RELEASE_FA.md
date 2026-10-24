# انتشار نسخه و دلیل «بروزرسانی از داخل اپ انجام نمی‌شود»

این سند به یک پرسش مشخص جواب می‌دهد: **چرا اپ با زدن «بررسی نسخه جدید» خودش را بروز نمی‌کند؟**
جواب در بیشتر موارد یک باگ نیست؛ **نسخهٔ بالاتری منتشر نشده است**. اپ فقط APK عمومی با
`versionCode` بزرگ‌تر از نصب فعلی را می‌پذیرد؛ هیچ مسیر دیگری (merge روی `main`، artifact
Actions، یا تغییر کد بدون انتشار) به گوشی نمی‌رسد.

## ۱) اندازه‌گیری زندهٔ ۲۰۲۶-۱۰-۲۰

```
python3 tools/diagnose_in_app_update.py --installed-code 676 --installed-name 2.0.4
# verdict: NO_UPDATE_FOUND
# answer: نسخهٔ جدیدتری که همهٔ گیت‌های آپدیتر را رد کند پیدا نشد؛ نصب کنونی 2.0.4 (676) است.
```

| نصب کنونی | جدیدترین انتشار عمومی | نتیجه | درست است؟ |
|---|---|---|---|
| 2.0.4 (۶۷۶) | `v2.0.4` (۶۷۶) | `NO_UPDATE_FOUND` | بله — نسخهٔ بالاتری وجود ندارد |
| 2.0.3 (۶۷۵) | `v2.0.4` (۶۷۶) | `UPDATE_AVAILABLE` | بله |
| 1.3.55 (۶۷۱) | `v2.0.4` (۶۷۶) | `UPDATE_AVAILABLE` | بله |

پس اگر نصب گوشی ۶۷۶ باشد، «بروزرسانی نیست» **رفتار درست** است. درمانش انتشار ۶۷۷ است.

## ۲) قانون انتشار در این مخزن

`.github/workflows/main.yml` انتشار عمومی را فقط در سه حالت اجازه می‌دهد
(`PUBLIC_UPDATER_RELEASE`):

1. push روی `main` با پیام commit که با `release: publish ` شروع شود؛
2. push یک تگ `v*`؛
3. `workflow_dispatch` با `publish_release=true` روی `main` یا یک شاخهٔ `arena/*`.

merge معمولی روی `main` فقط **validation build** است: تست و lint اجرا می‌شود، APK ساخته می‌شود،
ولی Release عمومی ساخته نمی‌شود و `versionCode` هم بالا نمی‌رود. برای همین کد جدید merge‌شده
تا وقتی نسخهٔ بعدی منتشر نشود به گوشی نمی‌رسد.

`versionCode` را **قبل از** انتشار در `android/app/build.gradle.kts` بالا ببر. مرحلهٔ
«Resolve and validate app version» فقط برای dispatch می‌تواند آن را با ورودی override کند.

## ۳) انتشار نسخهٔ بعدی

```bash
# ۱. نسخه در فایل build
#    versionCode = 678 ، versionName = "2.0.6"  (همیشه یکی بیشتر از آخرین انتشار)
# ۲. کامیت و push روی main
git commit -am "release: publish v2.0.6 (678)"
git push origin main
```

یا بدون commit روی `main` (توکن با دسترسی `actions: write` لازم است):

```bash
gh workflow run main.yml --ref main \
  -f version_name=2.0.6 -f version_code=678 -f publish_release=true
```

همین کار از شاخهٔ `arena/*` هم مجاز است، پس می‌توان بدون merge منتشر کرد
(`version_name`/`version_code` خالی = همان مقادیر `build.gradle.kts`):

```bash
gh workflow run main.yml --ref arena/b726ddc6-ichimoko \
  -f version_name=2.0.5 -f version_code=677 -f publish_release=true
```

در رابط وب: **Actions → Android APK → Run workflow**، شاخه را انتخاب کن،
`publish_release` را `true` بگذار و Run. حدود ۱۵ دقیقه بعد Release عمومی `v2.0.5` با همین
assetها ساخته می‌شود. انتشار از شاخه یعنی تگ روی همان کامیت شاخه می‌نشیند و **manifest روی
`main` عقب می‌ماند**؛ مرحلهٔ خودکار فقط روی `main` می‌نویسد، پس بعد از انتشار یک‌بار
`python3 tools/refresh_update_manifest.py --tag v2.0.5` را روی همان شاخه اجرا و کامیت کن تا
merge بعدی، `main` را هم‌خوان کند و گیت «manifest کهنه» سبز بماند.

پس از موفقیت، Release شامل `AurumEdge-v2.0.6.apk`، `AurumEdge-v2.0.6.apk.json`،
`aurum-edge.json`، `checksums.sha256` و `upgrade-compatibility.json` است و مرحلهٔ
«Verify public updater release is discoverable» همان APK را بدون توکن دانلود و تأیید می‌کند.
تگ موجود هرگز بازنویسی نمی‌شود.

## ۴) بلافاصله بعد از انتشار: manifest شاخهٔ پیش‌فرض

`update/aurum-edge.json` روی `main` یک **کپی** از متادیتای داخل Release است و به‌محض انتشار نسخهٔ
بعدی کهنه می‌شود. کپی کهنه همان حالتی است که گوشی «چیزی جدید نیست» می‌شنود. ابزار تازه:

```bash
python3 tools/refresh_update_manifest.py --tag v2.0.6          # نوشتن از sidecar همان Release
python3 tools/refresh_update_manifest.py --tag v2.0.6 --check   # فقط بررسی؛ برای CI
```

ابزار فقط sidecar عمومی را می‌خواند (بدون توکن)، `applicationId`/`versionCode`/`sha256`/`sizeBytes`/
`signerSha256`/`apkUrl` را اعتبارسنجی می‌کند، کلیدهای ناشناس را حذف می‌کند و **یادداشت فارسی
موجود را پاک نمی‌کند**. اگر این کار را نکنی، مرحلهٔ «Verify default-branch manifest points at the
newest release» PR بعدی را متوقف می‌کند و پیامش همین دستور را نشان می‌دهد.

از این نسخه، خود workflow هم بعد از انتشار موفق و فقط **روی `main`** همین کار را می‌کند
(مرحلهٔ «Keep the default-branch manifest on the newest release»: فایل را از sidecar همان Release
تازه می‌کند و با پیام `chore(update): … [skip ci]` push می‌کند). دستی‌کردن فقط وقتی لازم است که
انتشار از تگ یا شاخهٔ `arena/*` انجام شود؛ در آن حالت خودِ Release نسخهٔ درست manifest را دارد و
پس از merge باید یک‌بار دستور بالا اجرا شود.

## ۵) گیت‌های روی گوشی و پیام هرکدام

به ترتیب اجرا؛ اولین گیتی که رد شود همان پیام روی کارت «بروزرسانی برنامه» دیده می‌شود:

| گیت | پیام روی صفحه |
|---|---|
| نام/URL asset مورد اعتماد (`AurumEdge-v*.apk` در همان Release) | «برای این نسخه لینک مستقیم APK عمومی تنظیم نشده است» |
| HTTPS + پاسخ ۲۰۰ از میزبان دانلود | «دانلود بروزرسانی از *host* ناموفق بود (کد)» |
| حجم ≤ ۲۰۰MB و کافی بودن فضا | «حجم APK … از سقف مجاز بروزرسانی بیشتر است» |
| SHA-256 با متادیتای انتشار | «اثر انگشت فایل بروزرسانی با منبع هم‌خوان نیست» |
| بستهٔ همان `com.aurum.edge` | «این APK برای … است، اما نصب فعلی …» |
| امضای APK = امضای نصب همین گوشی (و گواهی pin‌شده) | «امضای APK با نصب فعلی یکسان نیست …» |
| `versionCode` بزرگ‌تر از نصب | «این فایل نسخهٔ جدیدتری از نصب فعلی نیست» |
| اجازهٔ «نصب از این برنامه» | «برای نصب باید اجازهٔ … را در Android بدهید» |

نکتهٔ ۲.۰.۵: اگر Android نتواند فایل دانلودشده را مستقیم بخواند
(`getPackageArchiveInfo` از سرویس بستهٔ سیستم جواب می‌گیرد، نه از خود اپ)، دانلود **قفل نمی‌شود**؛
گواهی اعلام‌شدهٔ همان Release (`signerSha256`) با امضای نصب و گواهی pin مقایسه می‌شود و بررسی
نهایی امضا هنگام نصب با Android است. اگر Release گواهی اعلام نکرده باشد، نصب fail-closed متوقف
می‌شود.

## ۶) وقتی پیام «بررسی کامل انجام نشد» است

یعنی هر دو کانال جواب ندادند؛ متن پیام نام میزبان و نوع خطا را می‌گوید:

- `سهمیهٔ بی‌توکن GitHub API … تمام شده (403)` → ۶۰ درخواست/ساعت برای هر IP؛ چند دقیقه صبر کن.
- `اتصال به raw.githubusercontent.com برقرار نشد` → فیلترینگ/VPN؛ کانال Release مستقل است.
- بررسی خودکار حداکثر هر ۱۵ دقیقه یک‌بار است؛ دکمهٔ «بررسی نسخه جدید» همیشه فوری است.

از ۲.۰.۵ متن «گزارش تشخیص» می‌گوید **هر کانال چه نسخه‌ای منتشر شده دید**، مثلاً
`releases: جدیدترین 2.0.4 (676) — برابر یا قدیمی‌تر از نصب فعلی`؛ بنابراین «نسخه‌ای نیست» از
«بررسی خطا داد» قابل تشخیص است.

## ۷) آزمون‌های مرتبط

| آزمون | چه چیزی را قفل می‌کند |
|---|---|
| `UpdaterChannelOutcomeTest` | کشف نسخهٔ بالاتر با HTTP ساختگی، سکوت نام‌گذاری‌شدهٔ «جدیدترین نسخه»، انتقال `signerSha256`، فاصلهٔ سهمیه |
| `UpdaterDiscoveryTest` | manifest کهنه/مسدود نسخهٔ عمومی را پنهان نمی‌کند |
| `UpdateVerificationTest` | گیت‌های امضا/نسخه و مسیر جایگزین متادیتا، بدون Android |
| `tools/test_refresh_update_manifest.py` | اعتبارسنجی sidecar و بازنویسی manifest (۹ تست، بدون شبکه) |

## ۸) وضعیت در نسخهٔ ۳.۰.۰

- نسخهٔ نصب‌شده در این درخت `3.0.0` با کد `700` است؛ آخرین انتشار عمومی همچنان `v2.0.11`
  (کد `683`) است و `update/aurum-edge.json` دقیقاً همان را توصیف می‌کند. بنابراین روی یک
  نصب ۳.۰.۰، صفحهٔ بروزرسانی درست می‌گوید «نسخهٔ جدیدتری پیدا نشد» تا وقتی ۳.۰.۰ منتشر شود.
- در صفحهٔ بروزرسانی، دو مقدار گواهی دیگر در یک جملهٔ مبهم ادغام نمی‌شوند:
  `AppUpdateRepository.signingSummary()` هم `installedSha256` (خوانده‌شده از
  `PackageManager` همین گوشی) و هم `channelSha256` (گواهی pin‌شده) را جدا برمی‌گرداند؛ متن
  حکم هم از همان تابع می‌آید تا صفحه و گیت نصب یک منبع داشته باشند.
- پس از انتشار ۳.۰.۰، manifest را با
  `python3 tools/refresh_update_manifest.py --tag v3.0.0` به‌روز کن؛ گیت CI نسخهٔ کهنه را رد
  می‌کند. جزئیات کامل: [`RELEASE_3_0_FA.md`](RELEASE_3_0_FA.md).
