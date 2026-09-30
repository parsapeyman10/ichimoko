# Aurum Edge — اپ بومی اندروید برای پژوهش فارکس

این مخزن فقط اپ **Native Android** با Kotlin و Jetpack Compose را نگه می‌دارد. اپ برای پژوهش و معاملهٔ کاغذی XAU/USD و جفت‌ارزهای اصلی طراحی شده است؛ **ارسال سفارش واقعی عمداً غیرفعال است** و هیچ کلید معاملاتی نباید در اپ وارد یا ذخیره شود.

رابط وب، PWA، React/Vite، Node و اسکریپت‌های اجرای آن در این نسخه حذف شده‌اند.

## ساخت APK

پیش‌نیازها:

- JDK 17
- Android SDK Platform 35 و Build Tools 35.0.0

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

خروجی Debug در مسیر زیر ساخته می‌شود:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

برای ساخت release مالک‌امضا‌شده و انتشار امن، راهنمای [android/README.md](android/README.md) و [docs/ANDROID.md](docs/ANDROID.md) را ببینید. APK ساخته‌شده در CI بدون کلید مالک، صرفاً preview/debug-signed است و کانال به‌روزرسانی پایدار محسوب نمی‌شود.

## بک‌اند اختیاریِ همراه اندروید

پوشهٔ `backend/` رابط وب نیست. این سرویس FastAPI فقط برای قابلیت‌های اختیاری اندروید مثل گیت خبر/AI سمت سرور استفاده می‌شود. بیشتر قابلیت‌های چارت، دادهٔ عمومی، ژورنال، بک‌تست و RSS مستقیم روی گوشی کار می‌کنند.

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # در ویندوز: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

اگر آدرس HTTPS این سرویس را در تنظیمات اپ وارد می‌کنی، آن را عمومی و امن مستقر کن؛ `localhost` لپ‌تاپ از گوشی قابل دسترس نیست. کلیدهای Twelve Data/Gemini/OpenAI فقط باید در محیط سرور یا تنظیمات خصوصی دستگاه قرار گیرند، نه در Git.

## وضعیت و محدودیت‌ها

- دادهٔ ساختگی، کندل ساختگی و معاملهٔ واقعی تولید نمی‌شود.
- بدون دادهٔ تازهٔ تأییدشده، اپ باید وضعیت آفلاین/نامعتبر را نشان دهد.
- بک‌تست و ژورنال کاغذی ابزار آموزشی هستند، نه توصیه یا تضمین سود.
- جزئیات تب‌ها، منابع داده و محدودیت‌ها در [android/README.md](android/README.md) و [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md) آمده است.
