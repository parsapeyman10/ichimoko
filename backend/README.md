# Trading API — تحلیل پژوهشی و مرز اجرای امن

FastAPI برای چارت/بک‌تست XAU/USD از **دادهٔ واقعی Twelve Data**، خبر انگلیسی اختیاری FMP و خبر فارسی RSS/Atom دارای مجوز. اگر دیتای معتبر نبود، خطای ۵۰۳ یا وضعیت صریحِ خالی/نامشخص برگردانده می‌شود؛ شمع و تیتر ساختگی تولید نمی‌شود.

## راه‌اندازی

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env     # کلیدهای خود را فقط در این فایل محلی/Secret Manager بگذار
uvicorn app.main:app --host 0.0.0.0 --port 8000
python -m pytest -q
```

- `AURUM_TWELVE_DATA_API_KEY`: برای کندل چارت و بک‌تست بک‌اند. اپ اندروید کلید خواندنی خودش را جداگانه در تنظیمات دستگاه می‌گیرد.
- `AURUM_FA_NEWS_RSS_URL` + `AURUM_FA_NEWS_ALLOWED_HOST`: URL مجاز HTTPS روی پورت 443 و دامنهٔ دقیق آن. **خودتان مجوز استفاده از فید را تأمین کنید**؛ هیچ فید/تیتر ساختگی یا اسکرپ پیش‌فرض وجود ندارد.
- `AURUM_FA_NEWS_SOURCE`: نام ناشر برای نمایش؛ `AURUM_NEWS_HOLD_MINUTES`: توقف بعد از انتشار خبر پراثر (پیش‌فرض 45). تحلیل ریسک فارسی فعلاً مبتنی بر واژه‌های محافظه‌کارانه است، نه تقویم کامل.
- `AURUM_FMP_API_KEY`: تیترهای انگلیسی اختیاری؛ مسیر تقویم اقتصادی فعلاً خالی است. `AURUM_OPENAI_API_KEY`: برای مسیر `/news/analyze` اختیاری است و جای خبر دارای مجوز را نمی‌گیرد.

## مسیرهای مهم

| مسیر | کاربرد |
|---|---|
| `GET /api/v1/health` و `/api/v1/data/status` | وضعیت فید و کلید داده‌خوانی |
| `GET /api/v1/market/{tf}/candles` | کندل واقعی؛ در نبود داده 503 |
| `GET /api/v1/backtest/run` | بک‌تست روی کندل واقعی با گزارش `performance` (۱۸ شاخص و کارمزد؛ Sharpe هر معامله، نه سالانه) |
| `GET /api/v1/backtest/forward` | تست داخل/خارج نمونه |
| `GET /api/v1/news/fa` | تیترهای فارسی فید دارای مجوز، تحلیل محافظه‌کارانه و `guard: CLEAR / BLOCKED / UNKNOWN`؛ کش قدیمی/فید قطع‌شده = `UNKNOWN` |
| `GET /api/v1/execution/status` | Kill switch و اتصال‌نداشتن Nobitex/MT5 |
| `POST /api/v1/execution/preflight` | دلیل‌های مسدودبودن؛ صرفاً اطلاع‌رسانی، هیچ سفارش صادر نمی‌کند |
| `POST /api/v1/execution/orders` | **همیشه 503** تا آداپتر، احراز هویت، ریسک و ممیزی واقعی افزوده شوند |
| `WS /ws/v1/market/xauusd` | تیک‌های واقعی همان نماد بک‌اند |

بک‌اند فعلی **تک‌نمادی و بدون احراز هویت کاربران** است و برای ترید زنده یا سرویس عمومی آماده نیست. کلید Nobitex، رمز MT5 و توکن Bridge را نه به اپ بده، نه به API فعلی. برنامهٔ اتصال واقعی و موارد ناتمام: [نقشهٔ راه](../docs/ROADMAP_FA.md).
