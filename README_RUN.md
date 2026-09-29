# اجرای Trading روی ویندوز / لوکال

> برای وضعیت امکانات جدید اندروید، خبر فارسی و محدودیت معاملهٔ واقعی: [docs/ROADMAP_FA.md](docs/ROADMAP_FA.md). متن زیر فقط راه‌اندازی ترمینال وبِ تک‌نمادی را شرح می‌دهد.

> این نسخه فقط با **دیتای واقعی** کار می‌کند. `backend/.env` را از `backend/.env.example` بساز.
> کلید Twelve Data برای تاریخچهٔ فوری/عمیق‌تر اختیاری است؛ بدون آن، فید واقعی و خودکار Swissquote/Gold-API برای قیمت طلا تلاش می‌شود و تاریخچه فقط از کندل‌هایی ساخته می‌شود که واقعاً مشاهده شده‌اند. تا وقتی provider حداقل پنجرهٔ لازم را نداده باشد، چارت/بک‌تست خطای صریح می‌دهد (نسخهٔ دمو، padding و interpolation وجود ندارد).


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

برای اجرای تست‌های provider و parser با MockTransport (در محیط توسعه):
```bash
cd backend
pip install -r requirements-dev.txt
pytest -q
```

بعد از اجرا:
- API: http://127.0.0.1:8000/docs
- Health: http://127.0.0.1:8000/api/v1/health
- وضعیت کلیدها و مقصد اتصال، بدون نمایش خود کلیدها: http://127.0.0.1:8000/api/v1/config/status
- probe زندهٔ محدود برای key/URL و شکل پاسخ providerها (در صورت نیاز، با مصرف سهمیه): http://127.0.0.1:8000/api/v1/config/status?live_probe=true
- بک‌تست 3m: http://127.0.0.1:8000/api/v1/backtest/run?timeframe=3m
- لیست تریدهای 2026: http://127.0.0.1:8000/api/v1/backtest/trades-ytd?timeframe=3m
- فرانت‌اند: `npm run dev` در ریشه، سپس http://127.0.0.1:5173

## نکته PowerShell
اگر `uvicorn` پیدا نشد: `python -m pip install uvicorn fastapi` یا `pip install -r backend/requirements.txt`
