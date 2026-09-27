# 🚀 Trading — اجرای رابط درست و حسابی (ویندوز)

> راهنمای امکانات جدید APK و موارد عمداً ناتمام: [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md). مراحل زیر برای ترمینال وب است، نه خبر فارسی/معاملهٔ واقعی.

## دو نکتهٔ مهم قبل از شروع
۱) بک‌اند و فرانت **جدا** هستند و هر دو باید روشن باشند: بک‌اند روی `8000` و فرانت روی `5173`.
۲) این نسخه **فقط با دیتای واقعی** کار می‌کند. بدون کلید Twelve Data هیچ چارت/سیگنالی ساخته نمی‌شود؛
به‌جای نسخهٔ دمو، وضعیت «آفلاین / بدون کلید» با آخرین دادهٔ واقعیِ کش‌شده نمایش داده می‌شود.

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

## کلید دیتای واقعی (اجباری)
1. در `backend/.env` این خط را پر کن (کلید رایگان از twelvedata.com):
   ```
   AURUM_TWELVE_DATA_API_KEY=کلید-خودت
   ```
2. بک‌اند را دوباره اجرا کن. حالا:
   - http://127.0.0.1:8000/api/v1/data/status → باید `api_key_configured: true` و وضعیت فید را نشان دهد
   - http://127.0.0.1:8000/api/v1/market/5m/candles?limit=50 → کندل‌های واقعی
   - داخل رابط: چارت زنده → بک‌تست → Walk-Forward → ژورنال

اگر کلید نباشد یا اینترنت قطع شود، همه‌جا پیام صریح «آفلاین / بدون کلید» دیده می‌شود — نه دیتای ساختگی.

## ساخت اپ اندروید (APK)
راهنمای کامل: [`docs/ANDROID.md`](docs/ANDROID.md) — APK در GitHub Actions ساخته می‌شود و از بخش
Artifacts همان اجرا دانلود می‌شود.

اگر باز هم بالا نیومد، همین ۲ تا خروجی رو بفرست:
```powershell
python --version; node --version; npm --version
curl http://127.0.0.1:8000/api/v1/health
```
