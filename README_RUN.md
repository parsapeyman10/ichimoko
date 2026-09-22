# اجرای Trading روی ویندوز / لوکال

> این نسخه فقط با **دیتای واقعی** کار می‌کند. قبل از اجرا `backend/.env` را از `backend/.env.example`
> بساز و `AURUM_TWELVE_DATA_API_KEY` را پر کن. بدون کلید، رابط وضعیت «بدون کلید» نشان می‌دهد و
> هیچ چارت یا سیگنالی ساخته نمی‌شود (نسخهٔ دمو وجود ندارد).


## خطای `ModuleNotFoundError: No module named 'app'` چرا پیش آمد؟
شما دستور `python backend/app/main.py` را از ریشه پروژه زدید. پایتون وقتی فایل را مستقیم اجرا می‌کند، پوشه `backend` را به `sys.path` اضافه نمی‌کند و `from app.config import ...` پیدا نمی‌شود.

**رفع شد:** در `backend/app/main.py` الان `sys.path` خودکار اضافه می‌شود، پس `python backend/app/main.py` هم کار می‌کند.

## سه روش درست اجرا (PowerShell ویندوز)

### 1) ساده‌ترین — از ریشه پروژه
```powershell
pip install -r backend/requirements.txt
python run.py
# یا با reload
python run.py --reload --port 8000
```

### 2) با uvicorn مستقیم (توصیه شده)
```powershell
cd C:\path\to\ichimoko
pip install -r backend/requirements.txt
uvicorn app.main:app --reload --app-dir backend --host 0.0.0.0 --port 8000
# یا
python -m uvicorn app.main:app --reload --app-dir backend
```

### 3) اجرای مستقیم فایل (الان رفع شد)
```powershell
python backend/app/main.py
# یا
cd backend
python -m app.main
python app/main.py
```

بعد از اجرا:
- API: http://127.0.0.1:8000/docs
- Health: http://127.0.0.1:8000/api/v1/health
- بک‌تست 3m: http://127.0.0.1:8000/api/v1/backtest/run?timeframe=3m
- لیست تریدهای 2026: http://127.0.0.1:8000/api/v1/backtest/trades-ytd?timeframe=3m
- فرانت‌اند: `npm run dev` در ریشه، سپس http://127.0.0.1:5173

## نکته PowerShell
اگر `uvicorn` پیدا نشد: `python -m pip install uvicorn fastapi` یا `pip install -r backend/requirements.txt`
