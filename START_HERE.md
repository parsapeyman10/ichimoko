# 🚀 Aurum Edge — اجرای رابط درست و حسابی (ویندوز)

## مشکلت چه بود؟
گفتی «نتونستم رابط درست حسابی بالا بیارم» — معمولاً چون **بک‌اند و فرانت‌اند جدا** هستند و باید هر دو روشن باشند. بک‌اند روی `8000` و فرانت روی `5173` — اگر فقط یکی روشن باشد رابط خالی/خطا می‌ده.

## سریع‌ترین راه — یک کلیک

### PowerShell (توصیه)
```powershell
cd C:\Users\parsa.p\Desktop\git\ichimoko
git pull origin arena/01a09055-ichimoko   # مهم! فیکس ModuleNotFoundError اینجاست
.\start.ps1
# اگر ارور ExecutionPolicy داد:
powershell -ExecutionPolicy Bypass -File start.ps1
```

### یا CMD
```cmd
cd C:\Users\parsa.p\Desktop\git\ichimoko
git pull origin arena/01a09055-ichimoko
start.bat
```

این هر دو پنجره را باز می‌کند:
- **Backend:** http://127.0.0.1:8000/docs (باید `{ "status": "ok" }` برگرداند)
- **Frontend:** http://127.0.0.1:5173 (ترمینال طلا)

## دستی — اگر اسکریپت کار نکرد

**ترمینال ۱ — بک‌اند:**
```powershell
cd C:\Users\parsa.p\Desktop\git\ichimoko
pip install -r backend/requirements.txt
python run.py --reload
# یا
uvicorn app.main:app --reload --app-dir backend --host 0.0.0.0 --port 8000
```

**ترمینال ۲ — فرانت:**
```powershell
cd C:\Users\parsa.p\Desktop\git\ichimoko
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

## تست سریع بعد بالا آمدن
- http://127.0.0.1:8000/api/v1/backtest/run?timeframe=3m → باید JSON با `final_balance` برگرده
- http://127.0.0.1:8000/api/v1/backtest/trades-ytd?timeframe=3m → لیست ۱۹۸ ترید
- داخل رابط: بک‌تست → سوئیچ 3m / 5m / 15m → Forward Test → ژورنال

اگر باز هم بالا نیومد، همین ۲ تا خروجی رو بفرست:
```powershell
python --version; node --version; npm --version
curl http://127.0.0.1:8000/api/v1/health
```
