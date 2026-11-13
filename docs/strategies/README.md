# کارنامهٔ استراتژی‌ها — Aurum Edge

برای هر استراتژی یک سند جدا: مشخصات، شرط‌ها، کد، پیام‌ها و راه بررسی. همه‌چیز از خود سورس استخراج شده است (نه از حافظه) و با `python3 tools/strategy_docs/gen.py` بازتولید می‌شود.

## فهرست

| # | استراتژی | نشان | مکتب‌ها | R:R پایه | سند |
|---|---|---|---|---|---|
| ۱ | استراتژی بیس (ترکیب کامل ۴گانه: پاتل+تاتل+هوسودا+پلویل) | ترکیب ۴گانه · بیس جامع | پاتل + تاتل + هوسودا + پلویل | ۲.۲۵ | [01-base-ichimoku.md](01-base-ichimoku.md) |
| ۲ | مدل تکی ۱: پولبک تعادلی کیجون + Preserve Mode (پاتل) | تکی · منش پاتل | پاتل | ۱.۸۵ | [02-patel-kijun-pullback.md](02-patel-kijun-pullback.md) |
| ۳ | مدل تکی ۲: شکست کومو با فیلتر سقف چیکو (تاتل) | تکی · مارک تاتل | تاتل | ۲.۳ | [03-tuttle-kumo-breakout.md](03-tuttle-kumo-breakout.md) |
| ۴ | مدل تکی ۳: امواج N هوسودا و اعداد زمانی (الیوت/هوسودا) | تکی · هوسودا/الیوت | هوسودا | ۲.۵ | [04-elliott-hosoda-wave.md](04-elliott-hosoda-wave.md) |
| ۵ | مدل تکی ۴: پلاتوهای افقی SSB/Kijun و کاتانا (پلویل) | تکی · کارن پلویل | پلویل | ۱.۹۵ | [05-peloille-mtf-plateau.md](05-peloille-mtf-plateau.md) |
| ۶ | ترکیب ۲گانه (پاتل + تاتل): پولبک کیجون + شکست چیکو | ۲گانه · پاتل+تاتل | پاتل + تاتل | ۲.۱ | [06-combo-patel-tuttle.md](06-combo-patel-tuttle.md) |
| ۷ | ترکیب ۲گانه (پاتل + هوسودا): تعادل کیجون + موج N و زمان | ۲گانه · پاتل+هوسودا | پاتل + هوسودا | ۲.۲۵ | [07-combo-patel-hosoda.md](07-combo-patel-hosoda.md) |
| ۸ | ترکیب ۲گانه (پاتل + پلویل): پولبک کیجون + پلاتوی فلت و کاتانا | ۲گانه · پاتل+پلویل | پاتل + پلویل | ۱.۹۵ | [08-combo-patel-peloille.md](08-combo-patel-peloille.md) |
| ۹ | ترکیب ۲گانه (تاتل + هوسودا): بریک‌اوت کومو + امواج N/E هوسودا | ۲گانه · تاتل+هوسودا | تاتل + هوسودا | ۲.۴۵ | [09-combo-tuttle-hosoda.md](09-combo-tuttle-hosoda.md) |
| ۱۰ | ترکیب ۲گانه (تاتل + پلویل): شکست کومو/چیکو + فیلتر فلت MTF | ۲گانه · تاتل+پلویل | تاتل + پلویل | ۲.۱۵ | [10-combo-tuttle-peloille.md](10-combo-tuttle-peloille.md) |
| ۱۱ | ترکیب ۲گانه (هوسودا + پلویل): هندسه موج N + پلاتوی فلت و کاتانا | ۲گانه · هوسودا+پلویل | هوسودا + پلویل | ۲.۳ | [11-combo-hosoda-peloille.md](11-combo-hosoda-peloille.md) |
| ۱۲ | ترکیب ۳گانه (پاتل + تاتل + هوسودا): کیجون + چیکو + موج N | ۳گانه · پاتل+تاتل+هوسودا | پاتل + تاتل + هوسودا | ۲.۳۵ | [12-combo-patel-tuttle-hosoda.md](12-combo-patel-tuttle-hosoda.md) |
| ۱۳ | ترکیب ۳گانه (پاتل + تاتل + پلویل): کیجون + چیکو + پلاتوی فلت | ۳گانه · پاتل+تاتل+پلویل | پاتل + تاتل + پلویل | ۲.۱۵ | [13-combo-patel-tuttle-peloille.md](13-combo-patel-tuttle-peloille.md) |
| ۱۴ | ترکیب ۳گانه (پاتل + هوسودا + پلویل): کیجون + موج N + فلت MTF | ۳گانه · پاتل+هوسودا+پلویل | پاتل + هوسودا + پلویل | ۲.۲۵ | [14-combo-patel-hosoda-peloille.md](14-combo-patel-hosoda-peloille.md) |
| ۱۵ | ترکیب ۳گانه (تاتل + هوسودا + پلویل): بریک‌اوت + موج N + فلت MTF | ۳گانه · تاتل+هوسودا+پلویل | تاتل + هوسودا + پلویل | ۲.۴ | [15-combo-tuttle-hosoda-peloille.md](15-combo-tuttle-hosoda-peloille.md) |
| ۱۶ | استراتژی ۱۶ · استراتژی پیمان (الیوت–نئوویو) | استراتژی پیمان · الیوت · نئوویو | هوسودا | ۲.۲۵ | [16-peyman-drive-books.md](16-peyman-drive-books.md) |
| ۱۷ | بیت‌کوین · ساختار ۵۲ کندلی و شکست/بازآزمایی | BTC · پنجرهٔ ۵۲ کندل | — | ۲.۲ | [17-btc-52-candle.md](17-btc-52-candle.md) |
| ۱۸ | استراتژی پارسا · چرخش ابر پس از فلت ۵۲ | پارسا · فلت >۱۷ + چرخش + کراس | — | ۲.۱۵ | [18-parsa-52-line-turn.md](18-parsa-52-line-turn.md) |
| ۱۹ | هیدن کراس · کراس برابری، تاج و ابر مخفی | هیدن کراس · ابر مخفی + تاج | — | ۲.۰ | [19-hidden-cross.md](19-hidden-cross.md) |

## چطور بگیرم و جدا بررسی کنم

```bash
# همهٔ سندها + کدها در یک فایل
docs/strategies/AurumEdge-Strategies-FA.zip

# یا فقط یکی
sed -n '1,200p' docs/strategies/01-base-ichimoku.md
```

## موتور مشترک

پانزده روش ایچیموکو یک موتور مشترک دارند؛ شرط‌ها، فرمول R:R، اندازهٔ پوزیشن و دروازهٔ ایمنی همه از یک‌جا می‌آید. جزئیاتش در [00-shared-pipeline.md](00-shared-pipeline.md) است تا در هر سند تکرار نشود.

## کد اختصاصی هر روش

| فایل | مربوط به |
|---|---|
| `code/16-peyman-drive-books/CryptoExecutionQualityGate.kt` | سند همان پوشه |
| `code/16-peyman-drive-books/CryptoStructuralBreakEngine.kt` | سند همان پوشه |
| `code/16-peyman-drive-books/NeoWaveCountEnsemble.kt` | سند همان پوشه |
| `code/16-peyman-drive-books/NeoWaveEngine.kt` | سند همان پوشه |
| `code/16-peyman-drive-books/PeymanCryptoEngine.kt` | سند همان پوشه |
| `code/16-peyman-drive-books/PeymanCryptoModels.kt` | سند همان پوشه |
| `code/16-peyman-drive-books/PeymanMarketContextEngine.kt` | سند همان پوشه |
| `code/18-parsa-52-line-turn/ParsaLineTurnEngine.kt` | سند همان پوشه |
| `code/19-hidden-cross/HiddenCrossEngine.kt` | سند همان پوشه |

## شرط‌های ۱۰گانهٔ ۵۲ کندلی (BTC)

| # | شرط |
|---|---|
| ۱ | دادهٔ بستهٔ کامل پنجرهٔ ۵۲ کندلی |
| ۲ | جهت و جایگاه ساختاری ۵۲ کندلی |
| ۳ | شکست بسته‌شده یا بازآزمایی مرز ۵۲ کندلی |
| ۴ | فشردگی/همگرایی پیش از گسترش |
| ۵ | همسویی ابر و تایم‌فریم بالاتر |
| ۶ | شیب Tenkan/Kijun و Senkou A |
| ۷ | آزادی Chikou در مسیر معامله |
| ۸ | کیفیت حرکت شارپ (بدنه، حجم یا گسترش ATR) |
| ۹ | فاصلهٔ قابل‌تعقیب از Kijun |
| ۱۰ | پلن نهایی و R:R حداقل ۱:۱٫۵ |
