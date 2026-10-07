# 🚀 Trading — اجرای رابط درست و حسابی (ویندوز)

> راهنمای امکانات جدید APK و موارد عمداً ناتمام: [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md). مراحل زیر برای ترمینال وب است، نه خبر فارسی/معاملهٔ واقعی.

## دو نکتهٔ مهم قبل از شروع
۱) بک‌اند و فرانت **جدا** هستند و هر دو باید روشن باشند: بک‌اند روی `8000` و فرانت روی `5173`.
۲) این نسخه **فقط با دیتای واقعی** کار می‌کند. کلید Twelve Data برای تاریخچهٔ فوری/عمیق‌تر اختیاری است؛ بدون کلید، فید واقعی و خودکار Swissquote/Gold-API برای طلا تلاش می‌شود. اگر provider هنوز حداقل دادهٔ واقعی لازم را جمع نکرده باشد، وضعیت «آفلاین/دادهٔ ناکافی» نمایش داده می‌شود؛ هیچ نسخهٔ دمو، padding یا interpolation وجود ندارد.

## سریع‌ترین راه — یک کلیک

### PowerShell (توصیه)
```powershell
cd C:\path\to\ichimoko
git pull
.\start.ps1
# اگر ارور ExecutionPolicy داد:
powershell -ExecutionPolicy Bypass -File start.ps1
```

### یا CMD
```cmd
cd C:\path\to\ichimoko
git pull
start.bat
```

این هر دو پنجره را باز می‌کند:
- **Backend:** http://127.0.0.1:8000/docs (باید `{ "status": "ok" }` برگرداند)
- **Frontend:** http://127.0.0.1:5173 (ترمینال طلا)

## دستی — اگر اسکریپت کار نکرد

**ترمینال ۱ — بک‌اند:**
```powershell
cd C:\path\to\ichimoko
pip install -r backend/requirements.txt
python run.py --reload
# یا
uvicorn app.main:app --reload --app-dir backend --host 0.0.0.0 --port 8000
```

**ترمینال ۲ — فرانت:**
```powershell
cd C:\path\to\ichimoko
npm install
npm run dev
# برو به http://127.0.0.1:5173
```

## چک‌لیست وقتی رابط بالا نمیاد

1. **بک‌اند روشنه؟** برو http://127.0.0.1:8000/api/v1/health — باید ok برگرده. اگر نرفت، لاگ ترمینال بک‌اند را بفرست.
2. **فرانت روشنه؟** ترمینال فرانت باید `VITE ready in ...` بگه. اگر `npm` ارور داد: `node --version` باید v18+ باشه.
3. **CORS؟** فیکس شد — الان `localhost` و `127.0.0.1` هر دو مجازند.
4. **ModuleNotFoundError: No module named 'app'؟** یعنی `git pull` نکردی. فیکس در `42c94c9` است.
5. **صفحه سفید؟** F12 → Console → ارور را بفرست. معمولاً `Failed to fetch /api` یعنی بک‌اند خاموشه.

## کلید تاریخچهٔ عمیق Twelve Data (اختیاری)
1. اگر تاریخچهٔ فوری/عمیق‌تر می‌خواهی، در `backend/.env` این خط را پر کن (کلید خواندنی از twelvedata.com):
   ```
   AURUM_TWELVE_DATA_API_KEY=کلید-خودت
   ```
2. بک‌اند را دوباره اجرا کن. حالا:
   - http://127.0.0.1:8000/api/v1/config/status → مقصد همهٔ کلیدهای تنظیم‌شده و خطای تنظیمات را بدون نمایش مقدار کلید نشان می‌دهد؛ برای اتصال زنده `?live_probe=true` را فقط هنگام نیاز اضافه کن
   - http://127.0.0.1:8000/api/v1/data/status → باید `api_key_configured: true` و وضعیت فید را نشان دهد
   - http://127.0.0.1:8000/api/v1/market/5m/candles?limit=50 → کندل‌های واقعی
   - داخل رابط: چارت زنده → بک‌تست → Walk-Forward → ژورنال

اگر کلید نباشد یا اینترنت قطع شود، همه‌جا پیام صریح «آفلاین / بدون کلید» دیده می‌شود — نه دیتای ساختگی.

## ساخت اپ اندروید (APK با همان امضای ثابت)
راهنمای ساخت با امضای ثابت و نیازمندی‌های نصبی: [`docs/BUILD_SIGNING_FA.md`](docs/BUILD_SIGNING_FA.md) و [`docs/ANDROID.md`](docs/ANDROID.md).
- برای ساخت کلید ثابت در لینوکس/مک: `./android/tools/generate-keystore.sh`
- برای ساخت کلید ثابت در ویندوز: `.\android\tools\generate-keystore.ps1`
- برای بیلد محلی امضاشده در لینوکس/مک: `./android/tools/build-owner-apk.sh ~/aurum-private/aurum-edge.jks aurum-edge`
- برای بیلد محلی امضاشده در ویندوز: `.\android\tools\build-owner-apk.ps1 -KeystorePath "$HOME\aurum-private\aurum-edge.jks" -Alias "aurum-edge"`
- بررسی نیازمندی‌های محیط: `./android/tools/check-env.sh`
- APKهای پیش‌نمایش تستی هم در GitHub Actions ساخته می‌شوند.

اگر باز هم بالا نیومد، همین ۲ تا خروجی رو بفرست:
```powershell
python --version; node --version; npm --version
curl http://127.0.0.1:8000/api/v1/health
```
