# -*- coding: utf-8 -*-
"""تعریف فلوچارت‌های سطح ۰ (نقشهٔ کلان) و سطح ۱ (جریان‌های اصلی زمان اجرا).

معماری فعلیِ پیاده‌سازی‌شده (ریفکتور کامل‌شده و سبز در CI):
- خبر، تقویم اقتصادی و نقش AI به‌طور کامل از برنامه حذف شده‌اند (هیچ گره/گیت خبری یا AI وجود ندارد).
- سیاست ۱۵۰۰ کندل و ساخت تایم‌فریم از M5 حذف؛ هر فریم (۴۰۰/۳۰۰/۲۵۰) مستقل و واقعی از سورس گرفته می‌شود.
- دروازهٔ verifiedMarket با «گارد فید» سبک جایگزین شده است.
- سرویس پایش تطبیقی است (MonitorCadence): پایه ۶۰ ثانیه، داغ ۲۰ ثانیه، پوزیشن باز لحظه‌ای.
- همهٔ منابع رایگان؛ سورس بی‌پاسخ صراحتاً اعلام می‌شود و پاسخ‌گو «منبع اصلی» می‌گردد (SourcePromotion).
نوع گره‌ها: start, end, proc, dec (تصمیم), io, data/store, sub (زیرنمودار), ext (بیرونی)
"""


def N(i, t, label, sub="", link=""):
    return {"id": i, "t": t, "label": label, "sub": sub, "link": link}


def E(f, t, label="", k=""):
    return {"f": f, "t": t, "label": label, "k": k}


def CH(i, title, level, file, desc, nodes, edges):
    return {"id": i, "title": title, "level": level, "file": file,
            "desc": desc, "nodes": nodes, "edges": edges}


CHARTS = [

# ═══════════════════════ سطح ۰ ═══════════════════════

CH("arch", "نقشهٔ کل اپ در یک نگاه — اپ چه می‌کند؟ (معماری فعلی)", 0,
   "معماری پیاده‌سازی‌شده · مسیرهای اصلی: data/ (داده) · engine/ (تصمیم) · core/ (ریسک) · ui/ (نمایش)",
   "Aurum Edge یک اپ پایش بازار و معاملهٔ کاغذی است (بدون هیچ سفارش واقعی): برای هر نماد، کندل واقعیِ هر سه تایم‌فریم را مستقل از منبع رایگان خودش می‌گیرد، "
   "هم‌جهتی تایم‌فریم‌ها را چک می‌کند، با موتور سیگنال امتیاز می‌دهد، از گارد فید و گیت‌های ریسک عبور می‌دهد و فقط در ژورنال کاغذی ثبت می‌کند. خبر و تقویم اقتصادی در این طرح وجود ندارند.",
   [
    N("ext", "ext", "منابع رایگان بدون کلید\nصرافی‌ها · Dukascopy · Yahoo · Stooq"),
    N("health", "sub", "ارتقای چسبندهٔ سورس\nپاسخ‌گو = منبع اصلی · مرده اعلام می‌شود", "SourcePromotion", "fn-source-health"),
    N("feed", "sub", "دریافت مستقل هر تایم‌فریم\nM5=۴۰۰ · M15=۳۰۰ · H1=۲۵۰ کندل بسته", "MarketRepository / PairScanner", "market-loop"),
    N("align", "sub", "چک هم‌جهتی چندتایم‌فریمی\nبا کندل واقعی هر فریم", "MtfAnalyzer (بدون تبدیل از M5)", "fn-mtf"),
    N("engine", "sub", "موتور سیگنال واحد\nچهار لایه · از ۱۰۰ امتیاز", "SignalEngine.evaluateLive", "fn-signalengine"),
    N("guard", "sub", "گارد فید\nتازگی رسید داده + سلامت سورس", "MarketState.feedGuard", "feed-guard"),
    N("dec", "dec", "سیگنال قابل‌معامله\nو بی‌مانع است؟"),
    N("wait", "proc", "نمایش علت صبر\n(Blockers شفاف در UI)"),
    N("auto", "sub", "ورود خودکار کاغذی\nصرفاً قواعد فنی و ریسک", "PaperAutoTrader", "auto-entry"),
    N("manual", "sub", "ورود دستی کاربر\nاز تب معامله", "AurumViewModel.submitPaper", "manual-entry"),
    N("journal", "sub", "ژورنال معاملات کاغذی\nثبت اتمیک + سقف ریسک", "JournalStore.open", "fn-journal-open"),
    N("life", "sub", "پایش پوزیشن باز\nتریلینگ · حد ضرر/سود", "OpenTradeTickMonitor + settle", "position-life"),
    N("close", "end", "بسته‌شدن و محاسبهٔ PnL\nآمار و گزارش PDF"),
    N("ui", "io", "رابط کاربری ۸ تب\nخانه، چارت، معامله، دیده‌بان…", "ui/shell/AurumRoot"),
    N("svc", "sub", "سرویس پایش تطبیقی\nریت متناسب با تایم‌فریم و پوزیشن", "SignalMonitorService", "service"),
   ],
   [
    E("ext", "feed", "کندل/تیک واقعی"),
    E("health", "feed", "انتخاب سورس سالم"),
    E("feed", "align"), E("align", "engine"),
    E("engine", "guard", "Signal"),
    E("guard", "dec"),
    E("dec", "wait", "خیر"),
    E("dec", "auto", "بله، خودکار روشن"),
    E("dec", "manual", "بله، کلیک کاربر"),
    E("wait", "feed", "کندل بعدی", "back"),
    E("auto", "journal"), E("manual", "journal"),
    E("journal", "life"), E("life", "close"),
    E("guard", "ui", "نمایش زنده"),
    E("close", "ui", "ژورنال"),
    E("svc", "feed", "وقتی اپ بسته است"),
   ]),

CH("universe", "دامنهٔ ۱۵ نماد و منابع رایگان هر نماد (اصلی + جایگزین)", 0,
   "core/V1Universe.kt · سیاست کندل ۴۰۰/۳۰۰/۲۵۰ · مسیریابی سورس اصلی + fallback رایگان · SourcePromotion",
   "فقط ۱۵ نماد فعال‌اند. هر نماد یک سورس اصلی اختصاصی و یک جایگزین رایگان دوم دارد؛ هر سه تایم‌فریم مستقل از همان سورس گرفته می‌شوند (دیگر هیچ تایم‌فریمی از روی M5 ساخته نمی‌شود). "
   "اگر سورس اصلی داده ندهد، هم در اپ اعلام می‌شود هم به‌صورت خودکار جایگزین فعال می‌گردد.",
   [
    N("s", "start", "درخواست دادهٔ یک نماد"),
    N("valid", "dec", "نماد در دامنهٔ\n۱۵تایی V1 هست؟", "V1Universe.valid"),
    N("rej", "end", "رد — نماد خارج از دامنه\n(فقط سوابق قدیم خوانده می‌شوند)"),
    N("route", "proc", "انتخاب سورس: اصلی اختصاصی\nو جایگزین رایگان دوم", "SourceRouting (اصلی + fallback)"),
    N("metals", "ext", "طلا/نقره: Dukascopy\nجایگزین: Yahoo/Stooq"),
    N("fx", "ext", "فارکس: Dukascopy یا Yahoo\nجایگزین: Stooq/Yahoo"),
    N("idx", "ext", "شاخص داوجونز: Yahoo ^DJI\nجایگزین: Stooq"),
    N("crypto", "ext", "کریپتو: صرافی اختصاصی هر کوین\nجایگزین: صرافی دوم همان جفت"),
    N("fetch", "io", "دریافت مستقل هر تایم‌فریم از سورس\nM5=۴۰۰ · M15=۳۰۰ · H1=۲۵۰"),
    N("closed", "dec", "کندل‌ها بسته، مرتب\nو بدون دادهٔ ساختگی؟"),
    N("dead", "sub", "اعلام «سورس مرده» در اپ\n+ ارتقای جایگزین به اصلی", "SourcePromotion", "fn-source-health"),
    N("fail", "end", "هر دو سورس مرده‌اند:\nبدون سیگنال — داده حدس زده نمی‌شود"),
    N("ready", "end", "سه فریم واقعی آمادهٔ\nچک هم‌جهتی و موتور سیگنال"),
   ],
   [
    E("s", "valid"), E("valid", "rej", "خیر"), E("valid", "route", "بله"),
    E("route", "metals"), E("route", "fx"), E("route", "idx"), E("route", "crypto"),
    E("metals", "fetch"), E("fx", "fetch"), E("idx", "fetch"), E("crypto", "fetch"),
    E("fetch", "closed"), E("closed", "dead", "سورس اصلی خراب"),
    E("dead", "fetch", "تلاش با جایگزین", "back"),
    E("dead", "fail", "جایگزین هم خراب"),
    E("closed", "ready", "بله"),
   ]),

# ═══════════════════════ سطح ۱ ═══════════════════════

CH("startup", "راه‌اندازی اپ و سیم‌کشی وابستگی‌ها", 1,
   "AurumApplication.kt · MainActivity.kt · core/AppContainer.kt · ui/shell/AurumViewModel.kt:startApp",
   "از لحظهٔ بازشدن اپ تا شروع فید بازار: ساخت AppContainer (مرکز همهٔ سرویس‌ها)، اتصال حلقه‌ها و شروع رابط کاربری. در طرح جدید هیچ ریپوی خبر/تقویمی ساخته نمی‌شود.",
   [
    N("s", "start", "شروع پروسهٔ اندروید"),
    N("app", "proc", "AurumApplication.onCreate\nساخت AppContainer", "AurumApplication.kt"),
    N("net", "proc", "شروع ناظر شبکه + ساخت Storeها\nتنظیمات، ژورنال، کش کندل، لاگ تصمیم", "AppNetwork.start · JournalStore…"),
    N("repos", "proc", "ساخت ریپوها: بازار، دیده‌بان\nسلامت سورس‌ها، آپدیت", "MarketRepository · SourceHealth…"),
    N("wire", "proc", "سیم‌کشی: گارد فید داخل MarketState\nاسکنر ↔ ورود خودکار ↔ AI", "AppContainer.init"),
    N("act", "proc", "MainActivity → Compose\nساخت AurumViewModel", "AurumRoot"),
    N("vm", "sub", "ViewModel.startApp\nشروع با طلا + حلقه‌های زنده", "startApp()", "fn-viewmodel"),
    N("svc", "dec", "پایش پس‌زمینه قبلاً\nروشن بوده؟"),
    N("svcstart", "sub", "فعال‌سازی دوبارهٔ سرویس تطبیقی\n(فقط با مجوز اعلان)", "SignalMonitorService.start", "service"),
    N("upd", "sub", "بررسی بروزرسانی\n(حداکثر هر ۱۵ دقیقه)", "checkForAppUpdate", "update-flow"),
    N("live", "end", "اپ زنده: فید، اسکن، UI"),
   ],
   [
    E("s", "app"), E("app", "net"), E("net", "repos"), E("repos", "wire"),
    E("wire", "act"), E("act", "vm"), E("vm", "svc"),
    E("svc", "svcstart", "بله"), E("svc", "upd", "خیر"),
    E("svcstart", "upd"), E("upd", "live"),
   ]),

CH("market-loop", "حلقهٔ دادهٔ نماد منتخب (چارت و تب معامله)", 1,
   "data/MarketRepository.kt",
   "مسیر دادهٔ نمادی که کاربر روی صفحه می‌بیند: کش دستگاه → دریافت مستقل سه تایم‌فریم → استریم/پولینگ تیک → ارزیابی سیگنال → انتشار MarketState همراه وضعیت گارد فید.",
   [
    N("s", "start", "market.start()\n(انتخاب نماد/بازه یا بازگشت UI)"),
    N("cache", "data", "بارگذاری کش کندل دستگاه\nفقط برای نمایش — نه ورود", "CandleCache.load"),
    N("refresh", "sub", "دریافت سه تایم‌فریم مستقل\nسورس اصلی ← جایگزین رایگان", "refresh()", "fn-market-refresh"),
    N("closedm", "dec", "بازار بسته است؟\n(آخر هفته/تعطیل)", "MarketHours.closedFor"),
    N("pubclosed", "io", "انتشار وضعیت «بازار بسته»\nبدون درخواست شبکهٔ دوره‌ای"),
    N("stream", "proc", "استریم WebSocket یا پولینگ\nREST متناسب با وضعیت", "startStream · startPolling"),
    N("tick", "sub", "رسیدن تیک قیمت\nبه‌روزرسانی کندل جاری", "onTick()", "fn-publish"),
    N("publish", "sub", "ارزیابی و انتشار\nSignalEngine + settle + گارد فید", "publishCandles()", "fn-publish"),
    N("state", "io", "MarketState تازه\n→ UI · سرویس · ورود خودکار"),
    N("wd", "proc", "واچ‌داگ هر ۲۰ ثانیه\nتشخیص سکوت فید و اتصال مجدد", "startWatchdog"),
   ],
   [
    E("s", "cache"), E("cache", "refresh"), E("refresh", "closedm"),
    E("closedm", "pubclosed", "بله"), E("closedm", "stream", "خیر"),
    E("stream", "tick"), E("tick", "publish"), E("publish", "state"),
    E("state", "stream", "ادامهٔ حلقه", "back"),
    E("wd", "stream", "بازیابی"),
   ]),

CH("feed-guard", "گارد فید — جایگزین سبک و درستِ دروازهٔ تأیید بازار", 1,
   "data/MarketRepository.kt (انتشار MarketState) · SourcePromotion · data/DecisionLogStore.kt",
   "دروازهٔ سنگین قبلی (ترکیب چهار جریان + ساعت مصنوعی + لایهٔ خبر) حذف شده است. به‌جای آن، هر MarketState از همان لحظهٔ انتشار فقط دو واقعیت را با خودش حمل می‌کند: «رسید داده چقدر تازه است» و «سورسش سالم است یا نه». مصرف‌کننده‌ها مستقیم همین دو را می‌خوانند — بدون واسطه و بدون حدس.",
   [
    N("s", "start", "انتشار MarketState\nاز publishCandles"),
    N("age", "proc", "سن رسید داده با ساعت واقعی:\nالان − زمان دریافت از سورس", "receiptAge = now - fetchedAt"),
    N("fresh", "dec", "رسید تازه است؟\n(آستانه متناسب با تایم‌فریم)"),
    N("health", "dec", "سورس اصلیِ این نماد پاسخ‌گوست؟", "SourcePromotion.ordered"),
    N("stale", "proc", "برچسب «تأخیری» روی همان وضعیت\nقیمت فقط نمایشی — سیگنال برای ورود نامعتبر"),
    N("log", "store", "ثبت نتیجه در لاگ تصمیم\ncandidate / no_signal / stale / error", "DecisionLogStore.append"),
    N("out", "io", "MarketState نهایی\n→ UI · سرویس تطبیقی · ورود خودکار"),
    N("rule", "proc", "قانون مصرف‌کننده‌ها:\nورود فقط با feedFresh=true\nو sourceHealthy=true", "PaperAutoRules همین دو را چک می‌کند"),
   ],
   [
    E("s", "age"), E("age", "fresh"),
    E("fresh", "stale", "کهنه"), E("fresh", "health", "تازه"),
    E("health", "stale", "سورس مرده/ناسالم"), E("health", "log", "سالم"),
    E("stale", "log"), E("log", "out"), E("out", "rule"),
   ]),

CH("scanner", "اسکنر کاتالوگ — پویش تطبیقی هر ۱۵ نماد", 1,
   "data/PairScanner.kt · data/ScanRanking.kt · core/MarketTrend.kt · نرخ پویش: MonitorCadence",
   "اسکنر با ریت تطبیقی (نه ثابت) همهٔ نمادهای دامنه را موازی می‌پوید: برای هر نماد همان موتور سیگنال واحد اجرا می‌شود، فرصت‌ها رتبه‌بندی می‌شوند و پهنای بازار (روند کلی) از خودِ قیمت‌ها ساخته می‌شود. نمادی که سورسش مرده است صراحتاً با همین برچسب گزارش می‌شود.",
   [
    N("s", "start", "شروع دور پویش\n(زمان‌بندی تطبیقی یا refreshNow)"),
    N("all", "sub", "پویش موازی ۱۵ نماد\nهر نماد: دریافت سه فریم + ارزیابی", "sweepAll()", "fn-scanner-sweep"),
    N("status", "io", "وضعیت هر نماد\nفرصت / بسته / مسدود / سورس مرده / خطا"),
    N("rank", "proc", "رتبه‌بندی فرصت‌ها\n۳ تای برتر + بهترین گزینه", "rankOpportunities"),
    N("trend", "proc", "ساخت «روند کلی بازار»\nعرض بازار + جهت دلار + جو ریسک", "MarketTrend.overall"),
    N("cand", "dec", "نامزد واجد شرایط\nوجود دارد؟"),
    N("review", "sub", "بازبینی ورود خودکار کاتالوگ", "CatalogPaperTrader.review", "auto-entry"),
    N("notify", "io", "اعلان فرصت تأییدشده\n(اگر سرویس خاموش و اپ باز است)", "Notifier.notifyRecordedAutoEntry"),
    N("cadence", "sub", "محاسبهٔ خواب تا دور بعد\nبر اساس وضعیت بازار و پوزیشن‌ها", "MonitorCadence.sweepDelayMs", "fn-cadence"),
   ],
   [
    E("s", "all"), E("all", "status"), E("status", "rank"),
    E("rank", "trend"), E("trend", "cand"),
    E("cand", "review", "بله"), E("review", "notify"),
    E("notify", "cadence"), E("cand", "cadence", "خیر"),
    E("cadence", "s", "دور بعد", "back"),
   ]),

CH("auto-entry", "مسیر ورود خودکار کاغذی — از نامزد تا ثبت در ژورنال", 1,
   "data/CatalogPaperTrader.kt · data/PaperAutoTrader.kt · core/AppContainer.kt",
   "دو ورودی دارد: نماد منتخب صفحه و نامزدهای اسکنر کاتالوگ. هر دو به PaperAutoTrader می‌رسند و فقط پس از عبور از گارد فید و همهٔ گیت‌های ریسک در ژورنال ثبت می‌شوند.",
   [
    N("sel", "start", "مسیر A: نماد منتخب\nهر انتشار MarketState تازه"),
    N("cat", "start", "مسیر B: نامزد اسکنر\nهر ۱۵ نماد"),
    N("tickq", "dec", "قیمت مستقل هم‌صرافی\nتازه و معتبر؟", "TradeQuotePolicy.accepts"),
    N("review", "proc", "بررسی صلاحیت کاتالوگ\nنماد پایش‌پذیر + تیک معاملاتی", "CatalogPaperTrader.review"),
    N("main", "sub", "هستهٔ ورود خودکار\nگارد فید + گیت‌های ریسک و AI", "PaperAutoTrader.onMarketUpdate", "fn-autotrader"),
    N("ok", "dec", "معامله در ژورنال\nثبت شد؟"),
    N("notify", "io", "اعلان «ورود کاغذی ثبت شد»\nفقط بعد از نوشتن موفق اتمیک", "Notifier.notifyRecordedAutoEntry"),
    N("status", "io", "نمایش علت رد در وضعیت\nخودکار (شفاف برای کاربر)"),
    N("end", "end", "پایان این دور بررسی"),
   ],
   [
    E("sel", "main"),
    E("cat", "review"), E("review", "tickq"),
    E("tickq", "main", "بله"), E("tickq", "status", "خیر"),
    E("main", "ok"), E("ok", "notify", "بله"), E("ok", "status", "خیر"),
    E("notify", "end"), E("status", "end"),
   ]),

CH("manual-entry", "ورود دستی کاربر از تب معامله", 1,
   "ui/shell/AurumViewModel.kt (submitPaper · openManualPaperTrade · previewManualTicket) · ui/tabs/signal/SignalScreen.kt",
   "کاربر می‌تواند روی سیگنال فعال، با حد ضرر/سود دلخواه بلیت کاغذی بسازد؛ اما قیمت و شرایط لحظهٔ ثبت دوباره راستی‌آزمایی می‌شوند و همان قفل‌های ژورنال اعمال می‌گردد.",
   [
    N("s", "start", "کلیک روی خرید/فروش\nدر تب معامله"),
    N("form", "io", "ورودی کاربر: جهت\nحد ضرر و حد سود"),
    N("preview", "sub", "پیش‌نمایش بلیت\nحجم، ریسک دلاری، هزینه‌ها", "PaperOrderRules.preview", "fn-ticket"),
    N("valid", "dec", "بلیت معتبر است؟\n(RR و کف سود بازار)"),
    N("err", "io", "نمایش خطای دقیق\nبه کاربر (Toast)"),
    N("reverify", "proc", "راستی‌آزمایی لحظهٔ ثبت:\nهمان سیگنال؟ همان قیمت؟ فید تازه؟", "verified() در submitPaper"),
    N("open", "sub", "ثبت اتمیک در ژورنال", "JournalStore.open", "fn-journal-open"),
    N("done", "end", "پوزیشن باز → پایش چرخهٔ حیات"),
   ],
   [
    E("s", "form"), E("form", "preview"), E("preview", "valid"),
    E("valid", "err", "خیر"), E("err", "form", "اصلاح", "back"),
    E("valid", "reverify", "بله"),
    E("reverify", "err", "تغییر کرده"), E("reverify", "open", "ثابت"), E("open", "done"),
   ]),

CH("position-life", "چرخهٔ حیات پوزیشن باز — از ثبت تا تسویه (رصد لحظه‌ای)", 1,
   "data/JournalStore.kt (settle/settleTick) · data/OpenTradeTickMonitor.kt · core/PaperTrailing.kt",
   "تا وقتی پوزیشن باز است رصد لحظه‌ای تیک برقرار است (مستقل از ریت اسکن). پوزیشن فقط با لمس قیمت واقعیِ حد ضرر/هدف (یا بستن دستی با قیمت تازه) بسته می‌شود؛ حد ضرر با تریلینگ فقط به نفع معامله حرکت می‌کند.",
   [
    N("s", "start", "پوزیشن کاغذی باز شد"),
    N("feed", "sub", "رصد لحظه‌ای قیمت نماد\nاستریم/آرشیو/REST", "OpenTradeTickMonitor.watch", "fn-tickmonitor"),
    N("price", "io", "قیمت/کندل واقعی جدید"),
    N("settle", "sub", "تسویه: بررسی حد ضرر/هدف\nو پیش‌برد تریلینگ", "settle · settleTick", "fn-settle"),
    N("hit", "dec", "حد ضرر یا هدف\nلمس شد؟"),
    N("trail", "proc", "به‌روزرسانی استاپ تریلینگ\n(فقط در جهت سود)"),
    N("manual", "dec", "کاربر دستی بست؟"),
    N("quote", "proc", "دریافت قیمت تازهٔ بستن\n(بدون قیمت تازه بسته نمی‌شود)", "quoteForManualClose"),
    N("close", "proc", "بستن و محاسبهٔ PnL واقعی\nبا کمیسیون و اسپرد", "JournalStore.close/settle"),
    N("persist", "store", "ذخیرهٔ اتمیک ژورنال\nو به‌روزرسانی موجودی روز"),
    N("notify", "io", "اعلان بسته‌شدن معامله"),
    N("end", "end", "در آمار و ژورنال می‌ماند"),
   ],
   [
    E("s", "feed"), E("feed", "price"), E("price", "settle"),
    E("settle", "hit"), E("hit", "close", "بله"),
    E("hit", "trail", "خیر"), E("trail", "manual"),
    E("manual", "quote", "بله"), E("quote", "close"),
    E("manual", "feed", "خیر — ادامهٔ رصد لحظه‌ای", "back"),
    E("close", "persist"), E("persist", "notify"), E("notify", "end"),
   ]),

CH("service", "سرویس پایش تطبیقی — ریت متناسب با تایم‌فریم و وضعیت پوزیشن", 1,
   "service/SignalMonitorService.kt · منطق ریت: core/MonitorCadence",
   "دیگر ریت ثابت یک‌دقیقه‌ای برای همه‌چیز نداریم: بدون پوزیشن باز، چک سبک هر ۶۰ ثانیه و ارزیابی کامل دقیقاً روی بسته‌شدن کندل M5 انجام می‌شود؛ نزدیک آستانهٔ امتیاز، ریت تند می‌شود؛ و به‌محض بازشدن پوزیشن، رصد لحظه‌ای تیک فعال می‌گردد. بدون مجوز اعلان یا با خاموشی کاربر، سرویس فوراً می‌ایستد.",
   [
    N("s", "start", "startService\n(فقط با opt-in کاربر)"),
    N("perm", "dec", "مجوز اعلان هست؟\nپایش در تنظیمات روشن؟"),
    N("stop", "end", "توقف خودکار\nبدون پایش مخفی"),
    N("fg", "proc", "شروع فورگراند + اعلان ثابت\n«در حال پایش تطبیقی»"),
    N("open", "dec", "پوزیشن کاغذی باز داریم؟"),
    N("live", "sub", "حالت لحظه‌ای: رصد تیک هر پوزیشن\nبدون خواب دوره‌ای", "OpenTradeTickMonitor", "position-life"),
    N("light", "proc", "حالت سبک (بدون پوزیشن):\nچک تازگی فید و پیش‌امتیاز هر ۶۰ ثانیه"),
    N("hot", "dec", "پیش‌امتیاز نزدیک آستانه؟\n(حالت داغ)"),
    N("fast", "proc", "ریت تند: چک هر ۲۰ ثانیه\nتا بسته‌شدن کندل جاری"),
    N("ondemand", "proc", "ارزیابی کامل موتور\nدقیقاً بعد از بسته‌شدن هر کندل M5\n→ اسکن ۱۵ نماد + ورود خودکار", "هم‌تراز با مرز کندل"),
    N("cadence", "sub", "تصمیم خواب بعدی\nاز روی وضعیت فعلی", "MonitorCadence.sweepDelayMs", "fn-cadence"),
   ],
   [
    E("s", "perm"), E("perm", "stop", "خیر"), E("perm", "fg", "بله"),
    E("fg", "open"),
    E("open", "live", "بله"), E("live", "cadence", "هم‌زمان با اسکن"),
    E("open", "light", "خیر"), E("light", "hot"),
    E("hot", "fast", "بله"), E("hot", "ondemand", "خیر — روی مرز کندل"),
    E("fast", "ondemand"), E("ondemand", "cadence"),
    E("cadence", "open", "چرخهٔ بعد", "back"),
   ]),


CH("watch-flow", "دیده‌بان قیمت — کارت‌های زندهٔ نمادها", 1,
   "data/WatchRepository.kt · data/SourceFetcher.kt · data/WatchCatalog.kt · data/QuoteHistoryStore.kt",
   "دیده‌بان برای نمایش سریع قیمت هر نماد از سورس رایگان خودش می‌خواند و سابقهٔ کوت‌ها را نگه می‌دارد. اگر سورس یک نماد مرده باشد، روی همان کارت صراحتاً «سورس مرده» می‌خورد، نه قیمت ساختگی.",
   [
    N("s", "start", "بازشدن تب دیده‌بان\nیا رفرش دوره‌ای"),
    N("cache", "data", "نمایش فوری کش قبلی", "loadCached"),
    N("sel", "proc", "انتخاب سورس هر نماد\nترجیح کاربر یا پیش‌فرض کاتالوگ", "WatchSettingsStore"),
    N("fetch", "io", "دریافت موازی کوت‌ها\nاز سورس رایگان هر نماد", "SourceFetcher"),
    N("valid", "dec", "کوت تازه و معتبر؟"),
    N("mark", "proc", "برچسب صادقانه روی کارت:\n«کهنه» یا «سورس مرده» — بدون جعل قیمت"),
    N("hist", "store", "ذخیره در سابقهٔ کوت\nنمودار مینی و تاریخچه", "QuoteHistoryStore"),
    N("ui", "io", "کارت‌های دیده‌بان\nقیمت، تغییر، وضعیت منبع"),
   ],
   [
    E("s", "cache"), E("cache", "sel"), E("sel", "fetch"),
    E("fetch", "valid"), E("valid", "hist", "بله"), E("valid", "mark", "خیر"),
    E("hist", "ui"), E("mark", "ui"),
   ]),

CH("update-flow", "بروزرسانی درجا — از GitHub تا نصب امن", 1,
   "data/AppUpdateRepository.kt · update/aurum-edge.json",
   "اپ بدون فروشگاه آپدیت می‌شود: اول مانیفست کنار APK در آخرین Release، بعد مانیفست شاخهٔ main، بعد فهرست Releaseها. APK فقط با تطابق SHA-256 و گواهی امضا نصب می‌شود.",
   [
    N("s", "start", "بررسی آپدیت\n(خودکار هر ≥۱۵دقیقه یا دستی)"),
    N("check", "sub", "یافتن جدیدترین نسخهٔ معتبر\nسه کانال به ترتیب اولویت", "checkForUpdate", "fn-updater"),
    N("new", "dec", "نسخهٔ جدیدتر\nاز نصب‌شده هست؟"),
    N("none", "end", "«به‌روز هستید»"),
    N("auto", "dec", "دانلود خودکار روشن؟"),
    N("wait", "io", "نمایش در تب بروزرسانی\nمنتظر کلیک کاربر"),
    N("dl", "sub", "دانلود APK + راستی‌آزمایی\nSHA-256 و گواهی امضا", "downloadApk", "fn-updater"),
    N("ok", "dec", "امضا و هش\nمطابق است؟"),
    N("rej", "end", "حذف فایل و رد نصب\n(امنیت)"),
    N("inst", "io", "بازکردن نصب‌کنندهٔ اندروید\nتأیید نهایی با کاربر"),
    N("done", "end", "نسخهٔ جدید با حفظ داده‌ها"),
   ],
   [
    E("s", "check"), E("check", "new"),
    E("new", "none", "خیر"), E("new", "auto", "بله"),
    E("auto", "dl", "بله"), E("auto", "wait", "خیر"), E("wait", "dl", "کلیک دانلود"),
    E("dl", "ok"), E("ok", "rej", "خیر"), E("ok", "inst", "بله"),
    E("inst", "done"),
   ]),

CH("ui-map", "نقشهٔ صفحات اپ — کاربر چه می‌بیند؟ (بدون تب خبر)", 1,
   "ui/shell/AurumRoot.kt · ui/shell/TabNavigation.kt · ui/tabs/*",
   "۸ تب: پنج تب اصلی در نوار پایین (خانه، چارت، معامله، دیده‌بان، ژورنال) و سه تب در منوی «بیشتر». تب خبر در طرح جدید حذف شده است. TradingView فقط نمایش تصویری است و منبع محاسبهٔ سیگنال نیست.",
   [
    N("s", "start", "AurumRoot\nپوستهٔ اصلی + هدر وضعیت فید و سلامت سورس"),
    N("home", "io", "خانه\nخلاصهٔ بازار و فرصت‌ها", "HomeScreen"),
    N("chart", "io", "چارت TradingView\nفقط نمایش تصویری", "ChartScreen"),
    N("signal", "io", "معامله / گزارش تصمیم\nچهار لایه، موانع، بلیت دستی", "SignalScreen"),
    N("watch", "io", "دیده‌بان\nکارت قیمت ۱۵ نماد + وضعیت سورس", "MarketWatchScreen"),
    N("journal", "io", "ژورنال معاملات کاغذی\nآمار، نمودار دورهٔ معامله، PDF", "JournalScreen"),
    N("more", "proc", "منوی «بیشتر»"),
    N("update", "io", "بروزرسانی\nنسخه، امضا، نصب", "UpdateScreen"),
    N("settings", "io", "تنظیمات\nریسک، آستانه، حالت هر دسته، ریت پایش", "SettingsScreen"),
    N("api", "io", "API و کلیدها\nفقط کلید AI (Gemini)", "ApiMenuScreen"),
    N("vm", "sub", "همه از یک ViewModel\nمنبع واحد حقیقت", "AurumViewModel", "fn-viewmodel"),
   ],
   [
    E("s", "home"), E("s", "chart"), E("s", "signal"), E("s", "watch"), E("s", "journal"),
    E("s", "more"), E("more", "update"),
    E("more", "settings"), E("more", "api"),
    E("vm", "s", "StateFlow ها"),
   ]),

CH("side-systems", "سامانه‌های جانبی مخزن — بیرون از اپ اندروید", 1,
   "tick-archive/ · tools/ · strategies/ · .github/workflows/main.yml",
   "سه بخش بیرون از اپ: سرور بایگانی تیک نوبیتکس (اختیاری، روی VPS کاربر)، ابزارهای پایتون برای بک‌تست و راستی‌آزمایی انتشار، و خط لولهٔ CI ساخت/انتشار APK.",
   [
    N("arc", "sub", "سرور بایگانی تیک\nNobitex → SQLite → API", "tick-archive/archive.py", "fn-archive"),
    N("phone", "io", "اپ اندروید: بازپخش تیک‌ها\nبرای تسویهٔ پوزیشن کریپتو", "NobitexTickArchiveClient"),
    N("tools", "proc", "ابزارهای پایتون\nبک‌تست ALS-5، پروب صرافی‌ها\nراستی‌آزمایی آپدیت و امضا", "tools/*.py"),
    N("pine", "ext", "استراتژی مرجع Pine Script\nALS-5 طلای M5 برای TradingView", "strategies/als5_gold_m5_strategy.pine"),
    N("ci", "proc", "CI گیت‌هاب: تست + Lint + ساخت\nکنترل applicationId و امضا", ".github/workflows/main.yml"),
    N("rel", "ext", "GitHub Release عمومی\nAPK + مانیفست آپدیت"),
    N("upd", "sub", "مصرف در اپ: آپدیت درجا", "AppUpdateRepository", "update-flow"),
   ],
   [
    E("arc", "phone", "HTTPS + توکن"),
    E("ci", "rel", "انتشار تأییدشده"), E("rel", "upd"),
    E("tools", "ci", "بررسی‌های انتشار"),
    E("pine", "tools", "مرجع بک‌تست"),
   ]),
]
