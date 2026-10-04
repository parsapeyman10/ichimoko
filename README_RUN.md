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

---

## معاملهٔ خودکار — بدون هیچ تنظیمی

اتوپایلوت **همراه با سرور خودش روشن می‌شود**. کاری لازم نیست بکنید:

```bash
python run.py
```

همین. از لحظه‌ای که بالا می‌آید، هر **۱۵ ثانیه** بازار را بررسی می‌کند، پوزیشن کاغذی باز و
بسته می‌کند، حجم را خودکار حساب می‌کند و اگر شرایط بد شد خودش می‌ایستد.

پیش‌فرض‌ها (بدون نیاز به هیچ کلید API، چون بایننس عمومی است):

| تنظیم | پیش‌فرض | متغیر محیطی |
|---|---|---|
| موجودی | ۱۰۰۰ دلار | `AURUM_AUTOPILOT_BALANCE` |
| فاصلهٔ بررسی | ۱۵ ثانیه | `AURUM_AUTOPILOT_INTERVAL_SECONDS` |
| تایم‌فریم | 5m | `AURUM_AUTOPILOT_TIMEFRAME` |
| نمادها | BTCUSDT, ETHUSDT, SOLUSDT, BNBUSDT, XRPUSDT | `AURUM_AUTOPILOT_SYMBOLS` |
| روشن/خاموش | روشن | `AURUM_AUTOPILOT_ENABLED` |

برای افزودن جفت‌ارز، `AURUM_TWELVE_DATA_API_KEY` را در `backend/.env` بگذارید و نمادها را
به `AURUM_AUTOPILOT_SYMBOLS` اضافه کنید.

**دیدن وضعیت:** `http://127.0.0.1:5173` → تب «تریدر خودکار»، یا:

```bash
curl http://127.0.0.1:8000/api/v1/autopilot/state
```

**خاموش کردن:** دکمهٔ توقف در UI (یا `POST /api/v1/autopilot/stop`). این تصمیم بعد از
ری‌استارت هم حفظ می‌شود؛ ولی اگر سرور کرش کند یا سیستم ری‌استارت شود، اتوپایلوت خودش
ادامه می‌دهد.

> معاملات **کاغذی**اند: روی کندل واقعی و با کسر اسپرد و کارمزد واقعی، ولی هیچ سفارشی به
> بروکر نمی‌رود. `/api/v1/execution/orders` همیشه ۵۰۳ برمی‌گرداند.
