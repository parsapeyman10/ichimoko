#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
سازندهٔ PDF «راهنمای کامل صفحات و ویژگی‌های Aurum Edge».

این اسکریپت متن راهنما را از دادهٔ ساختاریافتهٔ همین فایل می‌سازد تا هر بار با تغییر اپ،
راهنما هم قابل بازتولید باشد.

پیش‌نیازها:
    pip install fpdf2 uharfbuzz
    # فونت فارسی وزیرمتن (بارگیری از GitHub):
    curl -sL https://codeload.github.com/rastikerdar/vazirmatn/tar.gz/refs/heads/master -o /tmp/vaz.tgz
    tar xzf /tmp/vaz.tgz -C /tmp

اجرا:
    python3 tools/build_app_guide_pdf.py \
        --font-dir /tmp/vazirmatn-master/fonts/ttf \
        --out docs/AURUM_EDGE_GUIDE_FA.pdf
"""

from __future__ import annotations

import argparse
import os
from datetime import date

from fpdf import FPDF
from fpdf.enums import XPos, YPos

APP_NAME_FA = "Aurum Edge"
GOLD = (138, 109, 31)
GOLD_SOFT = (196, 168, 96)
NAVY = (18, 27, 40)
INK = (24, 24, 24)
MUTED = (95, 95, 95)
LINE = (214, 210, 198)
BG_SOFT = (247, 245, 239)
GREEN = (22, 120, 74)
RED = (168, 45, 45)

def _sanitize(text: str) -> str:
    """پاک‌سازی متن پیش از رندر.

    فلش‌های ← و → اگر بلافاصله کنار یک واژهٔ لاتین بیایند، الگوریتم دوجهتهٔ fpdf2
    حرف اول واژهٔ لاتین را جدا می‌کند («M arketState»). به‌جای فلش از واژهٔ فارسی
    «سپس» استفاده می‌کنیم که هم معنای جریان را می‌رساند و هم این خطا را ندارد.
    """
    cleaned = str(text)
    for arrow in ("←", "→", "⟵", "⟶", "↔"):
        cleaned = cleaned.replace(f" {arrow} ", " سپس ").replace(arrow, " سپس ")
    while "  " in cleaned:
        cleaned = cleaned.replace("  ", " ")
    return cleaned.strip()


def fa_digits(value) -> str:
    """ارقام لاتین را به ارقام فارسی تبدیل می‌کند (برای شمارهٔ صفحه و فهرست)."""
    table = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")
    return str(value).translate(table)


VERSION = "3.9.39"
VERSION_CODE = "748"
BUILD_DATE_FA = "۱۴۰۵/۰۸/۱۰ · 2026-11-01"

# --------------------------------------------------------------------------------------
# محتوا
# --------------------------------------------------------------------------------------

INTRO = {
    "purpose": (
        "این سند همان چیزی است که برای «پیگیری و خطایابی» لازم است: نقشهٔ همهٔ صفحه‌ها، "
        "همهٔ کارت‌ها، همهٔ عددهایی که روی صفحه می‌بینید و فرمول دقیق هر عدد. اگر عددی با "
        "انتظار شما نخواند، با شمارهٔ صفحهٔ همین سند و جدول عیب‌یابی، می‌توان مسیر خطا را "
        "تا فایل و تابع دقیق دنبال کرد."
    ),
    "howto": [
        "نام تب و نام کارت را از همین سند پیدا کنید (بخش ۲ تا ۸).",
        "عدد دیده‌شده و عدد انتظاری را یادداشت کنید؛ جدول «منبع اعداد» می‌گوید هر عدد از کدام فرمول می‌آید.",
        "در بخش «جدول عیب‌یابی» (بخش ۱۰) نشانه را جست‌وجو کنید: علت، فایل، تابع و اصلاح نوشته شده است.",
        "برای متن‌های هوش عصبی، از دکمهٔ «📋 کپی» همان کارت استفاده کنید و متن را ضمیمهٔ گزارش کنید.",
        "اگر ایراد ظاهری بود، از بخش ۱۳ (چک‌لیست بازبینی ظاهری) استفاده کنید تا مورد جا نیفتد.",
    ],
    "rules": [
        "هیچ عددی در این اپ «حدسی» نمایش داده نمی‌شود؛ هر عدد از یک تابع مشخص می‌آید.",
        "حد ضرر، حد سود و اهرم از یک موتور واحد (TradePlanGuard) رد می‌شوند و R:R هیچ‌وقت زیر ۱:۱.۵ نمی‌رود.",
        "لیکوییدیشن همیشه حداقل ۲.۵R دورتر از حد ضرر نگه داشته می‌شود؛ اهرم رمزارز: BTC/ETH ده، میجرها هفت، آلت‌کوین‌ها پنج.",
        "ژورنال، آرشیو تصویری و آموخته‌های شبکه عصبی با نصب نسخهٔ جدید پاک نمی‌شوند.",
        "گیت شبکهٔ عصبی دو کلیدی است: رسیدن به ۱۰٬۰۰۰ معاملهٔ یادگیری‌شده + روشن‌کردن آن توسط خود شما.",
        "لایهٔ نئوویو پیش‌فرض خاموش است و هر لحظه می‌تواند کنار گذاشته شود.",
    ],
}

TABS = [
    ("خانه", "HomeScreen", "خلاصهٔ پول، وضعیت ربات، رادار ۱۵ نماد و معاملات باز"),
    ("استراتژی (رادار)", "CryptoEntryPathCard + BitcoinIchimokuStrategyCard", "شرایط باز شدن ترید رمزارز و کارت کامل تحلیل ایچیموکو"),
    ("چارت", "ChartScreen", "نمودار کندلی، ابر ایچیموکو، پلن ورود/استاپ/هدف"),
    ("ژورنال", "JournalRiskScreen", "تقویم معاملات، معاملات باز، ماشین‌حساب حجم، خروجی/بکاپ، آرشیو تصویری"),
    ("هوش عصبی", "NeuralBrainScreen", "۱۹ کارت توضیحی + کپی متن + خروجی PDF"),
    ("تنظیمات", "SettingsScreen", "موجودی و ریسک، ربات، ۱۶ استراتژی، کالیبراسیون، داوری پلن، آلارم‌ها"),
    ("آپدیت", "UpdateScreen", "بررسی نسخه، دانلود APK، گواهی امضا، نصب"),
]

HOME_TILES = [
    ("موجودی پایه (تیتر کارت)", "settings.accountBalance", "مقداری که خودتان در تنظیمات وارد کرده‌اید + سود/زیان معاملات بسته‌شدهٔ ثبت‌شده از مسیرهای اپ", "تنظیمات ← موجودی و ریسک"),
    ("اکوئیتی", "موجودی پایه + سود/زیان شناور", "ارزش لحظه‌ای حساب با قیمت‌های فعلی معاملات باز", "AccountReconciliation.snapshot"),
    ("شناور", "مجموع ارزش لحظه‌ای معاملات باز", "قیمت زنده؛ اگر بازار بسته باشد آخرین قیمت معلوم", "LiveTradeValuation.total"),
    ("مارجین آزاد", "اکوئیتی − مارجین درگیر", "می‌تواند منفی شود (با رنگ قرمز)", "AccountLedgerSnapshot.freeMarginUsd"),
    ("تحقق‌یافته (ژورنال)", "مجموع سود/زیان معاملات بسته‌شده", "شامل کارمزد و اسپرد همان معامله", "JournalStore.stats().netPnl"),
    ("مارجین درگیر", "مجموع (نوشنال ÷ اهرم) معاملات باز", "اهرام از موتور TradePlanGuard می‌آید", "PaperTrade.effectiveMarginUsd"),
    ("ریسک هر معامله", "ریسک ثابت دلاری یا موجودی × درصد ریسک", "هر دو حالت در تنظیمات موجود است", "AccountReconciliation.riskPerTradeUsd"),
    ("برآیند ژورنال", "همان تحقق‌یافته (نام قدیمی)", "برای سازگاری خواندن باقی مانده است", "JournalStore.stats().netPnl"),
    ("نرخ برد", "بردها ÷ معاملات بسته‌شده", "معاملات باز در مخرج نیستند", "JournalStore.stats().winRate"),
    ("معاملات باز", "تعداد معاملات با isOpen", "از فایل ژورنال خوانده می‌شود", "JournalStore.trades"),
]

HOME_CARDS = [
    ("رادار هم‌زمان ایچیموکو (15m + 5m)", "پیل‌های وضعیت پایش/هوش مصنوعی، وضعیت سه بازار، سه آلارم تست صدا، نوار شبکهٔ عصبی و شمارش سیگنال آمادهٔ ورود."),
    ("بنر دسته‌های بازار", "چهار دستهٔ رمزارز، طلا/نقره، فارکس و داوجونز با تعداد معاملهٔ باز و علت ورود هر نماد."),
    ("معاملات باز", "برای هر پوزیشن: نماد، جهت، ورود، SL/TP، اهرم، لیکوییدیشن، مارجین، فرمول R:R، دسته‌بندی سه کندل قبل، سلامت روند و قوانین کارمزد."),
    ("دستیار ایچیموکو و نکات اصلاحی", "تحلیل چهار ستون هوسودا، امواج I/V/N/P/S، چرخهٔ زمان و فهرست نکات قابل اصلاح با دکمهٔ کپی."),
]

STRATEGY_ITEMS = [
    ("شرایط باز شدن ترید رمزارز", "هشت شرط: رمزارز بودن، کامل بودن الگوی نئوویو، تأیید پس‌ساختاری، هم‌جهتی، حداقل قواعد پاس‌شده، حداقل اطمینان، پلن معتبر با R:R ≥ ۱.۵ و نبود وتوی تایم‌فریم بالاتر."),
    ("کارت استراتژی ایچیموکو", "امتیاز ایچیموکوی جهانی، درجهٔ استاد، موج هوسودا، چرخهٔ زمان، اهداف کیسانچی، پلن ورود/استاپ/هدف، اهرم متناسب و دلیل داوری پلن."),
    ("تله‌متری ۹ کتاب درایو", "توافق کتاب‌ها، شرایط رد شده و پیشنهاد ورود/انتظار با متن فارسی."),
]

CHART_ITEMS = [
    "کندل‌های ۵ و ۱۵ دقیقه با ابر کومو، تنکان/کیجون، چیکو و شیب‌ها.",
    "جعبهٔ پوزیشن به سبک تریدینگ‌ویو: ناحیهٔ سود (سبز)، ورود، ناحیهٔ ضرر (قرمز) و برچسب R:R.",
    "بازرس ابر آینده و پیچش (Twist) برای چند کندل جلوتر.",
    "انتخاب نماد از نوار بالا و همگام‌سازی با تب‌های خانه و ژورنال.",
]

JOURNAL_SUBTABS = [
    ("📅 تقویم ژورنال", [
        "اولین بخش صفحه؛ ناوبری ماه، سه کارت خلاصهٔ ماه، شبکهٔ هفت‌ستونی و پنل روز.",
        "خانه‌ها مربعی و کاملاً هم‌اندازه‌اند (aspectRatio ۱:۱)؛ شدت رنگ نمایانگر بزرگی سود/زیان روز است، نه اندازهٔ خانه.",
        "اندازهٔ عددها ثابت است: شمارهٔ روز ۱۱، تعداد معاملات ۹ و مبلغ روز ۹ (همه sp).",
        "حلقهٔ فیروزه‌ای = امروز، حلقهٔ طلایی = روز انتخاب‌شده؛ مبالغ با قالب فشرده (+$12.4 / −$1.2k).",
    ]),
    ("📜 معاملات باز", [
        "جدول پوزیشن‌های باز با قیمت زنده، سود/زیان شناور، SL/TP، اهرم، لیکوییدیشن و دکمهٔ بستن.",
        "اگر بازار نماد بسته باشد، بستن با قیمت کهنه انجام نمی‌شود.",
    ]),
    ("🧮 ماشین‌حساب حجم", [
        "ورودی موجودی، درصد ریسک، ورود، SL و TP؛ خروجی حجم، مارجین، اهرم، لیکوییدیشن، کارمزد و R:R.",
        "همان موتور TradePlanGuard و RiskAndCalendarEngine که ربات استفاده می‌کند.",
    ]),
    ("📊 خروجی و بکاپ", [
        "خروجی CSV/PDF کارنامه، کپی خلاصهٔ اجرایی در کلیپ‌بورد و پشتیبان‌گیری/بازیابی ژورنال.",
    ]),
    ("📸 آرشیو تصویری", [
        "اسکرین‌شات خودکار لحظهٔ بسته شدن هر معامله با برچسب نماد، استراتژی، نتیجه و حجم فایل؛ خروجی PDF آرشیو.",
    ]),
]

NEURAL_CARDS = [
    "زمان‌بندی سشن و یادگیری زمان خروج",
    "پنل رگرسیون ماتریسی پارامترها (Ridge L2 + Huber IRLS)",
    "دستیار ترید ایچیموکو (هوسودا + پاتل + ۹ کتاب درایو)",
    "نکات قابل اصلاح و ارتقاء بعدی",
    "مغز شبکهٔ عصبی (FT-Attention + SwiGLU + AdamW)",
    "دفتر یادگیری و انتقال دانش بین نسخه‌ها",
    "درک زندهٔ وضعیت بازارها و علت حرکت",
    "هوش خبری کلان و تقویم اقتصادی",
    "تیونینگ خودکار پارامترها (CEM + Bayesian)",
    "تیونینگ اختصاصی هر استراتژی",
    "کالبدشکافی تریدهای تارگت و استاپ",
    "ماتریس برتری استراتژی‌ها",
    "هوش سشن‌های معاملاتی",
    "خلاصهٔ آموخته‌ها به زبان ساده",
    "دانش شبکه به تفکیک چهار دستهٔ بازار",
    "تله‌های ممنوعه و الگوهای طلایی",
    "وزن ۱۶ ویژگی ایچیموکو",
    "ارزیابی لحظه‌ای ۱۵ نماد",
    "درس‌های استخراج‌شده از معاملات",
]

SETTINGS_SECTIONS = [
    ("🤖 ربات معامله‌گر و پایش ۲۴/۷", "روشن/خاموش کردن ربات، پایش پس‌زمینه، فاصلهٔ اسکن و آلارم‌ها."),
    ("🎯 ۱۶ استراتژی ایچیموکو", "فعال/غیرفعال کردن هم‌زمان استراتژی‌ها روی ۱۵ و ۵ دقیقه."),
    ("💰 موجودی و ریسک", "موجودی پایه، سرمایهٔ پایه، ریسک ثابت دلاری یا درصدی، حد ضرر روزانه و حداکثر افت."),
    ("🛡️ داوری پلن SL/TP/Leverage", "برای هر ۱۵ نماد: ۱R داخل دالان نوسان، هدف در دسترس، R:R در بازه و اهرم امن."),
    ("🛠️ کالیبراسیون پارامترها", "حداقل R:R، کشیدگی کیجون، بافر حد ضرر، فیلترهای چیکو/کومو و حالت حفظ سود."),
    ("⏰ آلارم‌های مراحل معامله", "آلارم ورود، حد سود و حد ضرر با صدا و متن مستقل."),
]

DEBUG_ROWS = [
    ("اکوئیتی با موجودی + برآیند ژورنال نمی‌خواند",
     "موجودی پایه فقط با بسته‌شدن معامله از مسیرهای اپ تغییر می‌کند؛ حذف/ویرایش دستی معامله یا تغییر دستی موجودی می‌تواند اختلاف بسازد",
     "AccountReconciliation · JournalStore.applyBalanceDelta",
     "قاعده در بخش ۲ توضیح داده شده؛ دکمهٔ «هم‌ترازسازی موجودی با ژورنال» در کارت هدر اختلاف را صفر می‌کند"),
    ("مبلغ روز در تقویم به‌هم‌ریخته دیده می‌شد",
     "اندازهٔ متن و ارتفاع خانه به بزرگی سود روز وابسته بود",
     "JournalRiskScreen.CalendarDayCell",
     "از ۳.۹.۳۷ خانه‌ها مربعی و هم‌اندازه و اندازهٔ عددها ثابت (۹sp) است"),
    ("پیل‌ها از کادر بیرون می‌زدند",
     "ردیف‌های بدون وزن در Row",
     "HomeScreen · NeuralBrainScreen · SettingsScreen",
     "از ۳.۹.۳۶ ردیف‌های برچسب/پیل وزن‌دار شده‌اند و ردیف‌های پرتراکم اسکرول‌شو هستند"),
    ("کارت‌ها همیشه بسته باز می‌شدند",
     "پارامتر initiallyExpanded در SectionCard نادیده گرفته می‌شد",
     "ui/components/Common.kt · SectionCard",
     "از ۳.۹.۳۶ مقدار اولیه رعایت می‌شود"),
    ("اهرم رمزارز پایین و پوزیشن‌ها بی‌اثر شده بودند",
     "سقف اهرم رمزارز دستی به ۳x/۵x کاهش یافته بود",
     "core/VenueSpecs.kt",
     "از ۳.۹.۳۶: BTC/ETH ده، میجرها هفت، آلت‌کوین پنج؛ گارد ۲.۵R دست‌نخورده"),
    ("معاملهٔ کریپتو باز نمی‌شود",
     "یکی از هشت شرط مسیر رمزارز برقرار نیست (اغلب قواعد نئوویو یا وتوی تایم‌فریم بالاتر)",
     "core/NeoWaveEngine.kt · CryptoEntryPathCard",
     "کارت «شرایط باز شدن ترید رمزارز» می‌گوید کدام شرط مانده است"),
    ("عدد ریسک هر معامله با تنظیمات نمی‌خواند",
     "در نسخه‌های قبل کاشی ریسک همیشه مبلغ ثابت دلاری را نشان می‌داد، حتی در حالت درصدی",
     "HomeScreen · AccountReconciliation.riskPerTradeUsd",
     "از ۳.۹.۳۷ کاشی ریسک همان قاعدهٔ موتور را نشان می‌دهد و حالت را هم می‌نویسد"),
    ("سود/زیان شناور از واقع کمتر است",
     "برای نمادی که قیمت لحظه‌ای ندارد، آخرین قیمت معلوم استفاده می‌شود",
     "HomeScreen · AccountLedgerSnapshot.floatingNoteFa",
     "خط ℹ️ زیر کارت هدر تعداد معاملات بدون قیمت را می‌گوید"),
    ("مارجین آزاد صفر نشان داده می‌شد",
     "محاسبهٔ قبلی مقدار منفی را به صفر می‌برید",
     "AccountLedgerSnapshot.freeMarginUsd",
     "از ۳.۹.۳۷ مقدار واقعی (حتی منفی) با رنگ قرمز نمایش داده می‌شود"),
    ("متن هوش عصبی قابل کپی نبود",
     "کلیپ‌بورد فقط در ژورنال و کارت نکات وجود داشت",
     "NeuralBrainScreen · AurumViewModel.copyNeuralBrainSection",
     "از ۳.۹.۳۶ هر ۱۹ کارت دکمهٔ کپی دارد + دو دکمهٔ سراسری"),
    ("سود/زیان معاملهٔ حذف‌شده در موجودی مانده بود",
     "deleteTrade موجودی را اصلاح نمی‌کرد",
     "JournalStore.deleteTrade",
     "از ۳.۹.۳۷ حذف/افزودن معاملهٔ بسته‌شده اثر مالی‌اش را روی موجودی هم برمی‌گرداند"),
    ("نسخهٔ جدید نصب نمی‌شود",
     "امضای APK با نسخهٔ نصب‌شده یکی نیست یا versionCode بالاتر نیست",
     "UpdateScreen · docs/UPDATER_RELEASE_FA.md",
     "اثر انگشت گواهی نصب و کانال Release در همان صفحه مقایسه می‌شود"),
]

GLOSSARY = [
    ("اکوئیتی", "موجودی پایه + سود/زیان شناور؛ ارزش لحظه‌ای حساب."),
    ("مارجین", "بخشی از حساب که به‌عنوان وثیقهٔ پوزیشن قفل می‌شود (نوشنال ÷ اهرم)."),
    ("۱R", "فاصلهٔ ورود تا حد ضرر؛ ریسک همان معامله."),
    ("R:R", "نسبت فاصلهٔ ورود تا حد سود به ۱R؛ در این اپ در بازهٔ ۱:۱.۵ تا ۱:۳.۰ قفل است."),
    ("لیکوییدیشن", "قیمتی که پوزیشن با اهرم صفر می‌شود؛ همیشه حداقل ۲.۵R دورتر از حد ضرر است."),
    ("E[R]", "امید ریاضی هر معامله بر حسب R."),
    ("Profit Factor", "مجموع سود ÷ مجموع ضرر."),
    ("BCE", "خطای آنتروپی متقاطع شبکهٔ عصبی؛ کمتر بهتر."),
    ("دالان نوسان", "بازهٔ مجاز حد ضرر بر اساس ATR همان تایم‌فریم."),
    ("وتوی تایم‌فریم بالاتر", "اگر الگوی تأییدشدهٔ مخالف در ۴ ساعته باشد، ورود رمزارز بسته می‌شود."),
]

VERSIONS = [
    ("۳.۹.۳۹", "۷۴۸", "اصلاح تناقض‌های گزارش هوش عصبی (تصمیم ورود، علامت باخت، توجه یکنواخت)"),
    ("۳.۹.۳۸", "۷۴۷", "بکاپ ZIP اسکرین‌شات‌ها در تب آپدیت"),
    ("۳.۹.۳۷", "۷۴۶", "هم‌ترازسازی اعداد صفحهٔ نخست + خانه‌های مربعی تقویم"),
    ("۳.۹.۳۶", "۷۴۵", "اهرم متناسب رمزارز + کپی توضیحات هوش عصبی + بازبینی ظاهری سراسری"),
    ("۳.۹.۳۵", "۷۴۴", "بازسازی صفحهٔ ژورنال + قواعد درجه و دامنهٔ نیلی"),
    ("۳.۹.۳۴", "۷۴۳", "ژورنال تقویم‌محور + سپردن تنظیمات نئوویو به هوش عصبی"),
    ("۳.۹.۳۳", "۷۴۲", "بخش مستقل «شرایط باز شدن ترید رمزارز»"),
    ("۳.۹.۳۲", "۷۴۱", "بازنگری شرط ورود رمزارز با همان روش نئوویو"),
    ("۳.۹.۳۱", "۷۴۰", "تیونینگ خودکار آستانه‌های نئوویو"),
    ("۳.۹.۳۰", "۷۳۹", "نئوویو به‌عنوان لایهٔ تأیید اختیاری (پیش‌فرض خاموش)"),
    ("۳.۹.۲۹", "۷۳۸", "پاک‌سازی و پایداری موتور"),
    ("۳.۹.۲۸", "۷۳۷", "گیت دوکلیدی هوش عصبی + دفتر یادگیری"),
    ("۳.۹.۲۷", "۷۳۶", "تقویت شبکهٔ عصبی (FT-Attention + SwiGLU)"),
    ("۳.۹.۲۶", "۷۳۵", "موتور واحد SL/TP/اهرم (TradePlanGuard)"),
]

FILES = [
    ("ui/tabs/home/HomeScreen.kt", "کارت پول، رادار، معاملات باز"),
    ("core/AccountReconciliation.kt", "دفتر واحد حساب: اکوئیتی، مارجین آزاد، تطبیق موجودی"),
    ("ui/tabs/journal/JournalRiskScreen.kt", "ژورنال و تقویم مربعی"),
    ("ui/tabs/neural/NeuralBrainScreen.kt", "۱۹ کارت هوش عصبی و دکمه‌های کپی"),
    ("core/NeuralReportText.kt", "ساخت متن قابل‌کپی گزارش عصبی"),
    ("ui/shell/AurumViewModel.kt", "وضعیت‌ها، اکشن‌ها، کلیپ‌بورد و هم‌ترازسازی"),
    ("data/JournalStore.kt", "ذخیرهٔ معاملات، آمار و اصلاح موجودی"),
    ("data/TradeSnapshotArchiveManager.kt", "آرشیو تصاویر، ساخت ZIP بکاپ و بازخوانی"),
    ("core/TradePlanGuard.kt", "SL/TP/اهرم و داوری پلن"),
    ("core/VenueSpecs.kt", "مشخصات بازار، اهرم مرجع و کارمزد"),
    ("core/IchimokuStrategyEngine.kt", "موتور ۱۶ استراتژی و شرایط ورود"),
    ("core/NeoWaveEngine.kt", "لایهٔ نئوویو و وتوی چندتایم‌فریمی"),
    ("core/NeuralSelfLearningEngine.kt", "شبکهٔ عصبی، گیت و آمار"),
    ("ui/components/Common.kt", "SectionCard، StatTile، Pill، CopyTextPill"),
]

VISUAL_CHECKLIST = [
    "هر ردیف «برچسب + پیل» یا «برچسب + سوییچ» باید وزن‌دار باشد تا در عرض کم بیرون نزند.",
    "متن‌های بلند فارسی حداکثر دو خط با ellipsis؛ عددها یک خط با اندازهٔ ثابت.",
    "همهٔ خانه‌های تقویم مربعی و هم‌اندازه‌اند؛ رنگ تغییر می‌کند نه اندازه.",
    "مبالغ با ایزوله‌سازی جهت رندر شوند تا علامت منفی و جداکنندهٔ هزارگان جابه‌جا نشود.",
    "کاشی‌های آماری با ارتفاع یکسان (حداقل ۵۰dp) و مقدار دوخطی وسط‌چین.",
    "برچسب تب‌های پایین در صورت نیاز دوخطی و وسط‌چین؛ هیچ برچسبی بریده نشود.",
    "کارت‌های بازشو باید وضعیت اولیهٔ خود (initiallyExpanded) را رعایت کنند.",
]

# --------------------------------------------------------------------------------------
# رندر
# --------------------------------------------------------------------------------------


class Guide(FPDF):
    def __init__(self, font_dir: str, toc_map: dict | None = None, total_pages: int = 0):
        super().__init__(orientation="P", unit="mm", format="A4")
        self.font_dir = font_dir
        self.toc_map = toc_map or {}
        self.total_pages = total_pages
        self.sections: list[tuple[str, int]] = []
        self.set_auto_page_break(True, margin=18)
        self.set_margins(16, 16, 16)
        self.add_font("Vazir", "", os.path.join(font_dir, "Vazirmatn-Regular.ttf"))
        self.add_font("Vazir", "B", os.path.join(font_dir, "Vazirmatn-Bold.ttf"))
        self.set_text_shaping(True)
        self.alias_nb_pages()

    # ---------- وضعیت قلم ----------
    def body_font(self, size=10.5, style=""):
        self.set_font("Vazir", style, size)
        self.set_text_color(*INK)

    def header(self):
        if self.page_no() == 1:
            return
        self.set_font("Vazir", "", 8)
        self.set_text_color(*MUTED)
        self.set_y(8)
        self.cell(
            0, 5,
            f"راهنمای کامل صفحات و ویژگی‌های {APP_NAME_FA} · نسخهٔ {VERSION}",
            align="R",
        )
        self.set_draw_color(*LINE)
        self.set_line_width(0.2)
        self.line(self.l_margin, 14, self.w - self.r_margin, 14)
        self.set_y(20)

    def footer(self):
        if self.page_no() == 1:
            return
        self.set_y(-14)
        self.set_font("Vazir", "", 8)
        self.set_text_color(*MUTED)
        total = fa_digits(self.total_pages) if self.total_pages else fa_digits(self.page_no())
        self.cell(0, 6, f"صفحهٔ {fa_digits(self.page_no())} از {total}", align="C")

    # ---------- بلوک‌های محتوا ----------
    def cover(self):
        self.add_page()
        self.set_fill_color(*NAVY)
        self.rect(0, 0, self.w, 120, style="F")
        self.set_fill_color(*GOLD_SOFT)
        self.rect(0, 120, self.w, 2.5, style="F")

        self.set_y(34)
        self.set_font("Vazir", "B", 27)
        self.set_text_color(255, 255, 255)
        self.cell(0, 14, APP_NAME_FA, align="C", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.set_font("Vazir", "B", 15)
        self.set_text_color(*GOLD_SOFT)
        self.cell(0, 11, "راهنمای کامل صفحات، کارت‌ها و ویژگی‌ها", align="C", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.set_font("Vazir", "", 11)
        self.set_text_color(225, 225, 225)
        self.cell(0, 9, "به‌همراه جدول منبع اعداد و راهنمای خطایابی", align="C", new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        self.set_y(134)
        self.body_font(11)
        for line in [
            f"نسخهٔ سند: \u200e{VERSION}\u200e · کد \u200e{VERSION_CODE}\u200e",
            f"تاریخ: {BUILD_DATE_FA}",
            "مخزن: \u200eparsapeyman10/ichimoko\u200e · شاخهٔ انتشار: \u200earena/f97d4078-ichimoko\u200e",
        ]:
            self.cell(0, 8, line, align="C", new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        intro = (
            "این سند مرجع رسمی بازبینی اپ است: هر صفحه چه کار می‌کند، هر عدد از کدام فرمول می‌آید، "
            "و اگر عددی با انتظار شما نخواند، دقیقاً کدام فایل و کدام تابع باید بررسی شود. "
            "بخش ۳ (تب خانه) و بخش ۱۱ (جدول عیب‌یابی) بیشترین کاربرد را در گزارش خطا دارند."
        )
        box_w = self.w - self.l_margin - self.r_margin
        self.body_font(10.5)
        lines = self._wrap(intro, box_w - 12, 10.5)
        box_h = len(lines) * 6.5 + 14
        box_y = 168
        self.set_fill_color(*BG_SOFT)
        self.set_draw_color(*LINE)
        self.set_line_width(0.3)
        self.rect(self.l_margin, box_y, box_w, box_h, style="DF")
        self.set_xy(self.l_margin + 6, box_y + 7)
        self.rtl_block(intro, 10.5, 6.5, "", x=self.l_margin + 6, width=box_w - 12)

    def toc(self):
        self.add_page()
        self.h1("فهرست")
        entries = [
            ("۱", "مقدمه و روش استفاده برای خطایابی"),
            ("۲", "نقشهٔ کلی اپ و جریان داده"),
            ("۳", "تب خانه: کارت پول و منبع هر عدد"),
            ("۴", "تب استراتژی (رادار)"),
            ("۵", "تب چارت"),
            ("۶", "تب ژورنال و پنج زیرتب"),
            ("۷", "تب هوش عصبی و دکمه‌های کپی"),
            ("۸", "تب تنظیمات"),
            ("۹", "تب آپدیت"),
            ("۱۰", "موتورهای محاسباتی"),
            ("۱۱", "جدول عیب‌یابی"),
            ("۱۲", "واژه‌نامهٔ عددها و فرمول‌ها"),
            ("۱۳", "تاریخچهٔ نسخه‌ها"),
            ("۱۴", "پیوست: فایل‌های کلیدی و چک‌لیست بازبینی ظاهری"),
        ]
        available = self.w - self.l_margin - self.r_margin
        page_col = 16
        for num, title in entries:
            page = self.toc_map.get(title, "")
            y = self.get_y()
            self.body_font(11)
            # عنوان از سمت راست، شمارهٔ صفحه از سمت چپ
            self.set_xy(self.w - self.r_margin - (available - page_col), y)
            self.set_font("Vazir", "B", 11)
            self.cell(available - page_col, 8, f"{title}", align="R")
            self.set_font("Vazir", "", 10)
            self.set_text_color(*MUTED)
            self.set_xy(self.l_margin, y)
            self.cell(page_col, 8, fa_digits(page) if page else "—", align="C")
            self.set_text_color(*INK)
            self.set_y(y + 8)

    def rtl_block(self, text, size, line_h, style="", x=None, width=None, color=None):
        """چیدن یک پاراگراف راست‌به‌چپ خط‌به‌خط.

        دلیل وجود این تابع: چرخ‌کردن خطوط توسط multi_cell در fpdf2 با متن دوجهته
        (فارسی + شناسه‌های لاتین) گاهی یک واژهٔ لاتین را از میان می‌شکند
        («M arketState»). پس خودمان خطوط را می‌شکنیم و هر خط را با cell
        (که هیچ شکستنی انجام نمی‌دهد) می‌کشیم.
        """
        self.set_font("Vazir", style, size)
        if color is not None:
            self.set_text_color(*color)
        full = self.w - self.l_margin - self.r_margin
        if x is None:
            x = self.l_margin
        if width is None:
            width = full
        for raw in str(text).split("\n"):
            lines = self._wrap(raw, width, size, style)
            for line in lines:
                y = self.get_y()
                self.set_xy(x, y)
                self.cell(width, line_h, line, align="R", new_x=XPos.LMARGIN, new_y=YPos.NEXT)

    def h1(self, text):
        bare = text.split(". ", 1)[1] if ". " in text else text
        self.sections.append((bare, self.page_no()))
        self.ensure_space(26)
        y = self.get_y()
        self.set_fill_color(*GOLD_SOFT)
        self.rect(self.w - self.r_margin - 3, y + 1, 3, 9, style="F")
        self.set_font("Vazir", "B", 16)
        self.set_text_color(*NAVY)
        self.set_xy(self.l_margin, y)
        self.rtl_block(text, 16, 9, "B", width=self.w - self.l_margin - self.r_margin - 8, color=NAVY)
        self.ln(2)

    def h2(self, text, color=GOLD):
        self.ensure_space(18)
        self.ln(1)
        self.set_font("Vazir", "B", 12)
        self.set_text_color(*color)
        self.rtl_block("▌ " + text, 12, 7.5, "B", color=color)
        self.set_text_color(*INK)
        self.ln(1)

    def h3(self, text):
        self.ensure_space(14)
        self.set_font("Vazir", "B", 10.8)
        self.set_text_color(*NAVY)
        self.rtl_block(text, 10.8, 6.6, "B", color=NAVY)
        self.set_text_color(*INK)

    def p(self, text, size=10.5, color=INK, h=6.2, style=""):
        self.body_font(size, style)
        self.set_text_color(*color)
        self.rtl_block(text, size, h, style, color=color)
        self.ln(0.8)

    def bullets(self, items, size=10.2):
        self.body_font(size)
        self.set_text_color(*INK)
        for item in items:
            self.rtl_block("• " + item, size, 6.0, "")
        self.ln(1)

    def callout(self, title, text, color=GOLD, fill=BG_SOFT):
        available = self.w - self.l_margin - self.r_margin
        self.body_font(9.8)
        self.set_text_color(*INK)
        # اندازه‌گیری بدون shaping کمی پهن‌تر از رندر واقعی است؛ با ضریب جبران می‌کنیم تا کادر خالی نماند
        lines = self._wrap(text.replace("\n", " "), (available - 10) * 1.18, 9.8)
        height = 8 + len(lines) * 5.2 + (6 if title else 0)
        self.ensure_space(height + 4)
        y = self.get_y()
        self.set_fill_color(*fill)
        self.set_draw_color(*color)
        self.set_line_width(0.3)
        self.rect(self.l_margin, y, available, height, style="DF")
        self.set_fill_color(*color)
        self.rect(self.w - self.r_margin - 2.2, y, 2.2, height, style="F")
        self.set_xy(self.l_margin + 4, y + 3)
        if title:
            self.set_font("Vazir", "B", 9.8)
            self.set_text_color(*color)
            self.cell(available - 8, 5, title, align="R")
            self.set_xy(self.l_margin + 4, y + 8.5)
        self.set_font("Vazir", "", 9.8)
        self.set_text_color(*INK)
        self.rtl_block(text.replace("\n", " "), 9.8, 5.2, "", x=self.l_margin + 4, width=available - 8, color=INK)
        self.set_y(y + height + 3)
        self.set_text_color(*INK)

    def _wrap(self, text, width, size, style=""):
        """شکستن متن به خطوط؛ واژه‌های بلند لاتین (مسیر فایل، نام تابع) هم بریده می‌شوند تا از خانه بیرون نزنند."""
        text = _sanitize(text)
        self.set_font("Vazir", style, size)
        words = text.split(" ")
        lines, current = [], ""
        for word in words:
            trial = (current + " " + word).strip()
            if self.get_string_width(trial) <= width:
                current = trial
                continue
            if current:
                lines.append(current)
                current = ""
            if self.get_string_width(word) <= width:
                current = word
                continue
            # واژهٔ بسیار بلند: کاراکتر به کاراکتر می‌شکنیم
            chunk = ""
            for ch in word:
                if self.get_string_width(chunk + ch) <= width:
                    chunk += ch
                else:
                    lines.append(chunk)
                    chunk = ch
            current = chunk
        if current:
            lines.append(current)
        return lines

    def table(self, headers, rows, widths=None, size=9.2, head_size=9.4, align="R"):
        available = self.w - self.l_margin - self.r_margin
        n = len(headers)
        widths = widths or [1] * n
        total = sum(widths)
        cols = [available * w / total for w in widths]
        line_h = 4.9
        pad = 1.8

        def row_height(values, fs, style=""):
            hmax = 0
            for i, val in enumerate(values):
                lines = self._wrap(str(val), cols[i] - 2 * pad, fs, style)
                hmax = max(hmax, len(lines))
            return hmax * line_h + 2 * pad

        # هدر
        hh = row_height(headers, head_size, "B")
        self.ensure_space(hh + 10)
        y = self.get_y()
        self.set_fill_color(NAVY)
        x_right = self.w - self.r_margin
        for i, header in enumerate(headers):
            w = cols[i]
            x = x_right - w
            self.rect(x, y, w, hh, style="F")
            self.set_font("Vazir", "B", head_size)
            self.set_text_color(255, 255, 255)
            lines = self._wrap(str(header), w - 2 * pad, head_size, "B")
            ty = y + pad
            for line in lines:
                self.set_xy(x + pad, ty)
                self.cell(w - 2 * pad, line_h, line, align="C")
                ty += line_h
            x_right = x
        self.set_text_color(*INK)
        y += hh

        # ردیف‌ها
        for idx, row in enumerate(rows):
            rh = row_height(row, size)
            if y + rh > self.h - 20:
                self.add_page()
                y = self.get_y()
            fill = BG_SOFT if idx % 2 == 0 else (255, 255, 255)
            self.set_fill_color(*fill)
            self.set_draw_color(*LINE)
            self.set_line_width(0.15)
            x_right = self.w - self.r_margin
            for i, val in enumerate(row):
                w = cols[i]
                x = x_right - w
                self.rect(x, y, w, rh, style="DF")
                self.set_font("Vazir", "", size)
                self.set_text_color(*INK)
                lines = self._wrap(str(val), w - 2 * pad, size)
                ty = y + pad
                for line in lines:
                    self.set_xy(x + pad, ty)
                    self.cell(w - 2 * pad, line_h, line, align="R")
                    ty += line_h
                x_right = x
            y += rh
        self.set_y(y + 3)

    def kv(self, rows, size=9.6, label_w_ratio=0.32):
        available = self.w - self.l_margin - self.r_margin
        label_w = available * label_w_ratio
        value_w = available - label_w
        line_h = 5.0
        pad = 1.8
        for label, value in rows:
            lines_l = self._wrap(str(label), label_w - 2 * pad, size, "B")
            lines_v = self._wrap(str(value), value_w - 2 * pad, size)
            rh = max(len(lines_l), len(lines_v)) * line_h + 2 * pad
            if self.get_y() + rh > self.h - 20:
                self.add_page()
            y = self.get_y()
            self.set_draw_color(*LINE)
            self.set_line_width(0.15)
            self.set_fill_color(255, 255, 255)
            self.rect(self.w - self.r_margin - value_w, y, value_w, rh, style="DF")
            self.set_fill_color(*BG_SOFT)
            self.rect(self.w - self.r_margin - value_w - label_w, y, label_w, rh, style="DF")
            self.set_font("Vazir", "B", size)
            ty = y + pad
            for line in lines_l:
                self.set_xy(self.w - self.r_margin - value_w - label_w + pad, ty)
                self.cell(label_w - 2 * pad, line_h, line, align="R")
                ty += line_h
            self.set_font("Vazir", "", size)
            ty = y + pad
            for line in lines_v:
                self.set_xy(self.w - self.r_margin - value_w + pad, ty)
                self.cell(value_w - 2 * pad, line_h, line, align="R")
                ty += line_h
            self.set_y(y + rh)
        self.ln(3)

    def ensure_space(self, h):
        if self.get_y() + h > self.h - 20:
            self.add_page()

    def numbered(self, items, size=10.2):
        self.body_font(size)
        self.set_text_color(*INK)
        for i, item in enumerate(items, start=1):
            self.rtl_block(f"{fa_digits(i)}. {item}", size, 6.0, "")
        self.ln(1)


def build(font_dir: str, out_path: str, toc_map: dict | None = None, total_pages: int = 0) -> dict:
    pdf = Guide(font_dir, toc_map=toc_map, total_pages=total_pages)
    pdf.cover()
    pdf.toc()

    # ۱
    pdf.add_page()
    pdf.h1("۱. مقدمه و روش استفاده برای خطایابی")
    pdf.h2("این سند چه کاری می‌کند")
    pdf.p(INTRO["purpose"])
    pdf.h2("پنج قدم گزارش دقیق یک ایراد")
    pdf.numbered(INTRO["howto"])
    pdf.h2("قاعده‌های ثابت اپ (نباید تغییر کنند)")
    pdf.bullets(INTRO["rules"])
    pdf.callout(
        "قاعدهٔ طلایی",
        "هر عددی که روی صفحه می‌بینید باید در همین سند فرمول داشته باشد. اگر فرمولی پیدا نکردید، "
        "خودش یک ایراد مستندسازی است: همان عدد را با نام کارت گزارش کنید تا فرمولش اضافه شود.",
    )

    # ۲
    pdf.add_page()
    pdf.h1("۲. نقشهٔ کلی اپ و جریان داده")
    pdf.p(
        "اپ هفت تب دارد. هر تب یک صفحهٔ Compose است و از یک ViewModel مشترک (AurumViewModel) داده می‌گیرد. "
        "داده‌ها در سه مخزن نگه‌داری می‌شوند: ژورنال معاملات، تنظیمات و دفتر یادگیری شبکهٔ عصبی."
    )
    pdf.table(
        ["تب", "فایل صفحه", "کار اصلی"],
        TABS,
        widths=[1.1, 2.2, 3.2],
    )
    pdf.h2("جریان داده در یک نگاه")
    pdf.bullets([
        "فید قیمت → MarketState → استراتژی‌ها (IchimokuStrategyEngine) → گزارش هر نماد.",
        "گزارش استراتژی + گزارش تایم‌فریم بالاتر → تصمیم ورود رمزارز (NeoWaveEngine.gate).",
        "تصمیم ورود → TradePlanGuard (SL/TP/اهرم) → RiskAndCalendarEngine (حجم و مارجین) → ژورنال.",
        "معاملهٔ بسته‌شده → JournalStore → تنظیم موجودی + آمار + آرشیو تصویری + دفتر یادگیری عصبی.",
        "تنظیمات ↔ SettingsStore؛ شبکهٔ عصبی ↔ NeuralLearnedLedger؛ همهٔ این‌ها با هم به صفحهٔ نخست می‌رسند.",
    ])
    pdf.callout(
        "نکتهٔ مهم برای خطایابی",
        "اگر عددی در دو صفحه مختلف فرق داشت، اول ببینید هر دو صفحه از یک منبع می‌خوانند یا نه. "
        "مثلاً «اکوئیتی» فقط در صفحهٔ خانه ساخته می‌شود و منبعش دفتر واحد حساب است؛ "
        "اگر جایی عدد دیگری دیدید، آن صفحه منبع دیگری دارد و باید یکی شود.",
        color=RED,
        fill=(252, 245, 245),
    )

    # ۳
    pdf.add_page()
    pdf.h1("۳. تب خانه: کارت پول و منبع هر عدد")
    pdf.p(
        "بالای صفحهٔ خانه یک کارت تیره وجود دارد («هدر پورتفو»). تیتر آن موجودی پایه است و زیرش سه ردیف "
        "سه‌کاشی‌ای اضافه شده است. از نسخهٔ ۳.۹.۳۷ همهٔ این عددها از یک شیء واحد "
        "(AccountLedgerSnapshot) ساخته می‌شوند تا با هم بخوانند."
    )
    pdf.h2("سه رابطهٔ ثابت")
    pdf.callout(
        "معادله‌های کارت پول",
        "۱) اکوئیتی = موجودی پایه + سود/زیان شناور\n"
        "۲) مارجین آزاد = اکوئیتی − مارجین درگیر   (می‌تواند منفی شود)\n"
        "۳) موجودی سازگار با ژورنال = سرمایهٔ پایه + سود/زیان تحقق‌یافته\n"
        "اختلاف = موجودی پایه − موجودی سازگار با ژورنال",
    )
    pdf.h2("جدول منبع اعداد کارت پول")
    pdf.table(
        ["کاشی", "فرمول", "توضیح", "منبع در کد"],
        HOME_TILES,
        widths=[1.5, 2.0, 2.6, 2.4],
        size=8.6,
    )
    pdf.h2("چرا در نسخه‌های قبل اختلاف دیده می‌شد؟")
    pdf.bullets([
        "«موجودی پایه» و «برآیند ژورنال» دو عدد مستقل بودند: یکی مبلغی که شما وارد می‌کنید و با هر بسته‌شدن معامله تغییر می‌کند، دیگری مجموعهٔ همهٔ معامله‌های بسته‌شده از ابتدای ژورنال. اگر سابقهٔ ژورنال پاک/وارد می‌شد یا معامله‌ای حذف می‌شد، این دو از هم جدا می‌ماندند.",
        "«اکوئیتی» فقط موجودی + شناور بود؛ بنابراین «اکوئیتی − موجودی» هرگز با «برآیند ژورنال» جمع نمی‌شد، چون برآیند قبلاً داخل موجودی بود.",
        "کاشی «ریسک هر معامله» همیشه مبلغ ثابت دلاری را نشان می‌داد، حتی وقتی حالت ریسک روی درصدی بود.",
        "«مارجین آزاد» مقدار منفی را به صفر می‌برید و ایراد را پنهان می‌کرد.",
        "حذف یک معاملهٔ بسته‌شده، سود/زیانش را در موجودی باقی می‌گذاشت.",
    ])
    pdf.h2("چه چیزی اصلاح شد")
    pdf.bullets([
        "همهٔ کاشی‌ها از دفتر واحد حساب (AccountReconciliation) ساخته می‌شوند: موجودی، تحقق‌یافته، شناور، اکوئیتی، مارجین درگیر، مارجین آزاد، ریسک و آمار ژورنال.",
        "زیر کاشی‌ها دو خط توضیح آمد: معادلهٔ کامل حساب و «قاعدهٔ ریسک + سرمایهٔ پایه».",
        "خط وضعیت تطبیق حساب: سبز اگر موجودی = سرمایهٔ پایه + تحقق‌یافته، طلایی اگر اختلاف دارد (با ذکر علت).",
        "دکمهٔ «🔄 هم‌ترازسازی موجودی با ژورنال» فقط با فشردن شما اختلاف را صفر می‌کند؛ هیچ عددی خودکار تغییر نمی‌کند.",
        "حذف/افزودن معاملهٔ بسته‌شده حالا اثر مالی‌اش را روی موجودی هم برمی‌گرداند.",
    ])
    pdf.h2("بقیهٔ کارت‌های صفحهٔ خانه")
    pdf.kv(HOME_CARDS)

    # ۴ استراتژی
    pdf.add_page()
    pdf.h1("۴. تب استراتژی (رادار)")
    pdf.p(
        "این تب دو کارت اصلی دارد: کارت «شرایط باز شدن ترید رمزارز» که همیشه باز است و کارت کامل "
        "استراتژی ایچیموکو. هدف این تب این است که بدانید چرا یک نماد وارد معامله می‌شود یا نمی‌شود."
    )
    pdf.kv(STRATEGY_ITEMS)
    pdf.h2("هشت شرط ورود رمزارز")
    pdf.numbered([
        "نماد از دستهٔ رمزارز باشد.",
        "الگوی نئوویو کامل و تأییدشده باشد.",
        "تأیید پس‌ساختاری (ساختار موج + دامنهٔ داینامیک) برقرار باشد.",
        "جهت الگو با جهت سیگنال ایچیموکو هم‌جهت باشد.",
        "تعداد قواعد پاس‌شدهٔ نئوویو به آستانهٔ آموخته‌شده برسد (پیش‌فرض ۸).",
        "اطمینان شمارش به آستانه برسد (پیش‌فرض ۶۵٪).",
        "پلن معتبر با R:R ≥ ۱.۵ و بدون وتو وجود داشته باشد.",
        "تایم‌فریم بالاتر (۴ ساعته) الگوی تأییدشدهٔ مخالف نداشته باشد.",
    ])
    pdf.callout(
        "نکته",
        "این هشت شرط «زمانی که معامله باز می‌شود» را کنترل می‌کنند؛ روش تحلیلی نئوویو و آستانهٔ R:R و "
        "گارد ضدلیکوییدیشن تغییر نکرده‌اند.",
        color=GREEN,
        fill=(245, 250, 246),
    )

    # ۵ چارت
    pdf.add_page()
    pdf.h1("۵. تب چارت")
    pdf.p("چارت برای دیدن ساختار بازار و پلن پیشنهادی قبل از ورود است.")
    pdf.bullets(CHART_ITEMS)
    pdf.h2("عددهای روی چارت")
    pdf.table(
        ["عدد", "معنی"],
        [
            ("تنکان / کیجون", "میانگین‌های کوتاه و میان‌مدت ایچیموکو (۹ و ۲۶ دوره)"),
            ("سنکو A / B", "لبه‌های ابر کومو؛ ضخامت ابر = قدرت حمایت/مقاومت"),
            ("چیکو", "قیمت بسته‌شده ۲۶ دوره عقب‌تر؛ مقایسه با قیمت حال"),
            ("پیچش ابر", "نقطه‌ای که ترتیب سنکو A و B عوض می‌شود"),
            ("R:R", "نسبت پاداش به ریسک پلن؛ همیشه در بازهٔ ۱:۱.۵ تا ۱:۳.۰"),
        ],
        widths=[1.4, 4.2],
    )

    # ۶ ژورنال
    pdf.add_page()
    pdf.h1("۶. تب ژورنال و پنج زیرتب")
    pdf.p(
        "ژورنال قلب اپ است: ثبت معاملات، آمار، تقویم، ماشین‌حساب و پشتیبان‌گیری. ترتیب زیرتب‌ها "
        "عمداً «تقویم ← معاملات باز ← ماشین‌حساب ← خروجی/بکاپ ← آرشیو» است تا اول چیزی که هر روز "
        "می‌بینید بالا باشد."
    )
    for title, items in JOURNAL_SUBTABS:
        pdf.h2(title)
        pdf.bullets(items)
    pdf.h2("مشخصات فنی خانه‌های تقویم (نسخهٔ ۳.۹.۳۷)")
    pdf.table(
        ["ویژگی", "مقدار"],
        [
            ("شکل خانه", "مربع دقیق (aspectRatio ۱:۱) — همان چیزی که خواسته شد"),
            ("اندازه", "در هر ردیف و بین ردیف‌ها کاملاً یکسان"),
            ("شمارهٔ روز", "اندازهٔ ثابت ۱۱sp، وزن Bold"),
            ("تعداد معاملات روز", "اندازهٔ ثابت ۹sp"),
            ("مبلغ سود/زیان روز", "اندازهٔ ثابت ۹sp، قالب فشرده مثل +$12.4 یا −$1.2k"),
            ("متغیر رنگی", "فقط شدت رنگ پس‌زمینه و نوار پایین، نه اندازه"),
            ("امروز / روز انتخاب‌شده", "حلقهٔ فیروزه‌ای / حلقهٔ طلایی"),
            ("خانه‌های خالی", "هم‌اندازه و بی‌متن تا شبکه دندانه‌دار نشود"),
        ],
        widths=[1.6, 4.0],
    )
    pdf.callout(
        "چرا قبلاً به‌هم‌ریخته بود",
        "ارتفاع خانه از بزرگی سود/زیان روز ساخته می‌شد (۵۶ تا ۸۰dp) و متن مبلغ با اندازهٔ پیش‌فرض "
        "typography داخل خانهٔ کوچک جا نمی‌شد؛ نتیجه این بود که عددها اندازهٔ متفاوت و بعضی‌ها بریدگی "
        "داشتند. حالا اندازه از رنگ جدا شده است.",
        color=RED,
        fill=(252, 245, 245),
    )

    # ۷ هوش عصبی
    pdf.add_page()
    pdf.h1("۷. تب هوش عصبی و دکمه‌های کپی")
    pdf.p(
        "این تب ۱۹ کارت توضیحی دارد. از نسخهٔ ۳.۹.۳۶ هر کارت یک دکمهٔ «📋 کپی» در سرصفحه دارد و "
        "بالای صفحه هم دو دکمهٔ سراسری وجود دارد: «کپی کل توضیحات» و «کپی خلاصهٔ وضعیت»."
    )
    pdf.h2("فهرست کارت‌ها")
    pdf.numbered(NEURAL_CARDS, size=9.8)
    pdf.h2("گزارش هوش عصبی: چطور بخوانیم (بدون تناقض)")
    pdf.table(
        ["اصطلاح", "معنی دقیق", "اشتباه رایج"],
        [
            ("مشاهدهٔ خوشه", "هر بسته‌شدن معامله یک مشاهده در آن خوشه ثبت می‌کند (تجمعی)", "با «معاملهٔ یکتا» یکی نیست"),
            ("معاملهٔ یادگیری‌شده", "شمارندهٔ یکتای دفتر یادگیری، تطبیق‌شده با ژورنال", "این عدد مرجع است"),
            ("سهم توجه", "اگر یکنواخت باشد یعنی شبکه هنوز ویژگی را ترجیح نداده", "معیار تفکیک، «اثر خالص وزن» است"),
            ("سه عدد R:R", "کف قانون ۱.۵۰ · کف رگرسیون · کف تیونینگ خودکار", "عدد اعمالی = کف تیونینگ"),
            ("وضعیت ورود", "✅ مجاز · ⚠️ پرخطر (گیت خاموش) · ⛔ ممنوع (گیت فعال)", "«پرخطر» یعنی ممنوع نیست"),
        ],
        widths=[1.5, 3.0, 1.6],
    )
    pdf.h2("ساختار متن کپی‌شده")
    pdf.p(
        "متن کپی‌شده را موتور متن هوش عصبی می‌سازد: سرصفحه، تاریخ تولید، خط جداکننده، سپس هر بخش با "
        "نشانگر ▌ و پانویس نسخه. خطوط خالی حذف می‌شوند و اعداد با جداکنندهٔ هزارگان لاتین نوشته می‌شوند."
    )
    pdf.callout(
        "گیت شبکهٔ عصبی",
        "گیت دو کلیدی است: (۱) رسیدن به ۱۰٬۰۰۰ معاملهٔ یادگیری‌شده و (۲) روشن‌کردن آن توسط شما. "
        "تا هر دو برقرار نشود، شبکه فقط یاد می‌گیرد و در تصمیم ورود دخالت نمی‌کند.",
        color=GOLD,
    )

    # ۸ تنظیمات
    pdf.add_page()
    pdf.h1("۸. تب تنظیمات")
    pdf.p("هر چیزی که در تنظیمات تغییر می‌دهید باید در همان صفحه دیده شود؛ هیچ تنظیم پنهانی وجود ندارد.")
    pdf.kv(SETTINGS_SECTIONS, size=9.8)
    pdf.h2("عددهای کلیدی پیش‌فرض")
    pdf.table(
        ["تنظیم", "پیش‌فرض", "اثر"],
        [
            ("موجودی پایه", "۱۰۰٬۰۰۰ دلار", "پایهٔ محاسبهٔ ریسک و مارجین"),
            ("ریسک هر معامله", "۵ دلار یا درصدی از موجودی", "حجم پوزیشن و فاصلهٔ ۱R"),
            ("حداقل R:R", "۱:۱.۵", "هدف حداقل ۱.۵ برابر ریسک"),
            ("حداکثر R:R", "۱:۳.۰", "سقف هدف برای واقعی‌بودن"),
            ("بافر ضدلیکوییدیشن", "۲.۵R", "فاصلهٔ لیکوییدیشن از حد ضرر"),
            ("سقف مارجین", "۳۰٪ حساب", "بیشتر از این، حجم کوچک‌تر می‌شود"),
            ("گیت عصبی", "۱۰٬۰۰۰ معامله + اجازهٔ شما", "فیلتر ورودهای بی‌کیفیت"),
            ("لایهٔ نئوویو", "خاموش", "لایهٔ تأیید اختیاری رمزارز"),
        ],
        widths=[1.5, 1.8, 2.6],
    )

    # ۹ آپدیت
    pdf.add_page()
    pdf.h1("۹. تب آپدیت")
    pdf.bullets([
        "بررسی نسخه از سه کانال: مانیفست داخل Release، مانیفست شاخهٔ main و فهرست Releases.",
        "دانلود APK عمومی و بررسی نام بسته، گواهی امضا، versionCode، حجم و SHA-256 پیش از باز کردن نصب‌کننده.",
        "نمایش اثر انگشت گواهی نصب فعلی در کنار گواهی کانال انتشار تا پیش از دانلود معلوم شود فایل نصب می‌شود یا نه.",
        "بررسی خودکار زودتر از هر ۱۵ دقیقه تکرار نمی‌شود (سهمیهٔ API)؛ دکمهٔ «بررسی نسخهٔ جدید» همیشه فوری است.",
        "نصب بی‌صدا در اندروید ممکن نیست؛ تأیید نهایی در پنجرهٔ سیستم انجام می‌شود.",
    ])
    pdf.h2("کارت «بکاپ اسکرین‌شات‌های آرشیو»")
    pdf.table(
        ["دکمه", "کار", "نتیجه"],
        [
            ("💾 ذخیره در دانلود", "ساخت ZIP از همهٔ تصاویر آرشیو و کپی در حافظهٔ گوشی", "Download/AurumEdge/Screenshots"),
            ("📤 ارسال", "همان ZIP از طریق Share Sheet اندروید", "تلگرام، واتساپ، ایمیل، درایو"),
            ("📥 بازخوانی از فایل ZIP", "افزودن تصاویر و فهرست آن ZIP به آرشیو فعلی", "ادغام بدون پاک کردن آرشیو"),
            ("🔄 تازه‌سازی آمار", "خواندن دوبارهٔ تعداد و حجم تصاویر", "عددهای کارت به‌روز می‌شوند"),
        ],
        widths=[1.6, 2.6, 1.8],
    )
    pdf.bullets([
        "منبع اعداد کارت: `TradeSnapshotArchiveManager.archiveBasket` (تعداد تصویر، حجم خام تصاویر و تعداد رکورد فهرست).",
        "ساخت ZIP: `buildSnapshotZipBundle` — تصاویر در پوشهٔ `screenshots/`، فهرست رکوردها و یک `README_FA.txt`.",
        "تصاویر جداگانه از قبل در گالری (`Pictures/AurumEdge/TradeArchive`) و `Download/AurumEdge/Archive` هم هستند؛ کارت آپدیت فقط آن‌ها را یک‌جا جمع می‌کند.",
    ])
    pdf.callout(
        "هشدار",
        "حذف نصب قبل از نصب نسخهٔ جدید ممکن است ژورنال، آرشیو و آموخته‌های شبکه را پاک کند. "
        "اگر امضا یکی نبود، اول پشتیبان بگیرید.",
        color=RED,
        fill=(252, 245, 245),
    )

    # ۱۰ موتورها
    pdf.add_page()
    pdf.h1("۱۰. موتورهای محاسباتی")
    pdf.h2("موتور واحد SL/TP/اهرم (TradePlanGuard)")
    pdf.bullets([
        "فاصلهٔ حد ضرر از پنج سنگر ساختاری و ATR مقاوم همان تایم‌فریم ساخته می‌شود و داخل «دالان نوسان» می‌ماند.",
        "حد سود طوری تنظیم می‌شود که R:R در بازهٔ ۱:۱.۵ تا ۱:۳.۰ بماند.",
        "اهرم = کمینهٔ سقف مرجع نماد و عددی که لیکوییدیشن را حداقل ۲.۵R دور نگه دارد.",
        "اگر پلن کهنه باشد (قیمت از لنگر فاصله گرفته باشد) ورود رد می‌شود و دلیل فارسی نمایش داده می‌شود.",
    ])
    pdf.h2("سقف اهرم هر بازار")
    pdf.table(
        ["نماد", "سقف اهرم", "دلیل"],
        [
            ("BTC/USDT · ETH/USDT", "۱۰x", "عمیق‌ترین دفتر سفارش رمزارز"),
            ("SOL, BNB, XRP, DOGE, ADA, AVAX, LINK, TON, DOT, TRX, LTC, BCH, SUI, NEAR", "۷x", "میجر با نقدینگی بالا"),
            ("سایر آلت‌کوین‌ها", "۵x", "ریسک نویز نقدینگی و اسلیپیج"),
            ("طلا · نقره · فارکس · داوجونز", "۱۰x", "بدون تغییر"),
        ],
        widths=[2.4, 1.0, 2.4],
        size=8.8,
    )
    pdf.h2("ریسک، حجم و مارجین")
    pdf.bullets([
        "ریسک هر معامله = مبلغ ثابت دلاری (اگر روشن باشد) وگرنه موجودی × درصد ریسک.",
        "حجم = ریسک ÷ ارزش یک واحد در فاصلهٔ حد ضرر.",
        "مارجین = نوشنال ÷ اهرم؛ اگر از ۳۰٪ حساب بیشتر شود، حجم کوچک‌تر می‌شود.",
        "سود/زیان هر معامله شامل کارمزد رفت‌وبرگشت و اسپرد است.",
    ])
    pdf.h2("لایهٔ نئوویو و گیت عصبی")
    pdf.bullets([
        "نئوویو ساختار موج را با دامنهٔ داینامیک (بر پایهٔ ATR کوتاه/بلند) می‌شمارد و قواعد الگو را می‌سنجد.",
        "وتوی تایم‌فریم بالاتر: الگوی تأییدشدهٔ مخالف در ۴ ساعته، ورود رمزارز را می‌بندد.",
        "گیت عصبی فقط با دو کلید (۱۰٬۰۰۰ معامله + اجازهٔ کاربر) فعال می‌شود و آمار شرایط ممنوعه را می‌سازد.",
    ])

    # ۱۱ عیب‌یابی
    pdf.add_page()
    pdf.h1("۱۱. جدول عیب‌یابی")
    pdf.p("ستون آخر همان کاری است که باید انجام شود یا در نسخهٔ جاری انجام شده است.")
    pdf.table(
        ["نشانه", "علت", "محل در کد", "راه‌حل"],
        DEBUG_ROWS,
        widths=[1.8, 2.2, 2.4, 2.1],
        size=8.0,
        head_size=8.6,
    )

    # ۱۲ واژه‌نامه
    pdf.add_page()
    pdf.h1("۱۲. واژه‌نامهٔ عددها و فرمول‌ها")
    pdf.kv(GLOSSARY, size=9.8, label_w_ratio=0.26)

    # ۱۳ نسخه‌ها
    pdf.h1("۱۳. تاریخچهٔ نسخه‌ها")
    pdf.table(
        ["نسخه", "کد", "موضوع"],
        VERSIONS,
        widths=[0.9, 0.8, 4.4],
    )
    pdf.p(
        "برای جزئیات هر نسخه: CHANGELOG_FA.md در ریشهٔ مخزن؛ برای انتشار و امضا: "
        "docs/UPDATER_RELEASE_FA.md.",
        size=9.8,
        color=MUTED,
    )

    # ۱۴ پیوست
    pdf.add_page()
    pdf.h1("۱۴. پیوست: فایل‌های کلیدی و چک‌لیست بازبینی ظاهری")
    pdf.h2("فایل‌هایی که بیشتر برای خطایابی لازم می‌شوند")
    pdf.table(
        ["فایل", "نقش"],
        FILES,
        widths=[2.6, 3.2],
        size=9.0,
    )
    pdf.h2("چک‌لیست بازبینی ظاهری (هر نسخه یک‌بار)")
    pdf.bullets(VISUAL_CHECKLIST, size=9.8)
    pdf.h2("دستورهای ساخت و انتشار")
    pdf.p(
        "ساخت APK و انتشار فقط از شاخهٔ arena/f97d4078-ichimoko انجام می‌شود؛ کامیت با پیشوند "
        "«release: publish » بیلد و انتشار را راه می‌اندازد. آزمون‌های موتور روی JVM اجرا می‌شوند و "
        "به دستگاه نیاز ندارند.",
        size=9.8,
    )
    pdf.callout(
        "یادآوری",
        "این PR عمداً merge نمی‌شود؛ انتشار فقط از همین شاخه انجام می‌گیرد.",
        color=GOLD,
    )

    pdf.output(out_path)
    pdf.total_pages_actual = pdf.page_no()
    return {title: page for title, page in pdf.sections}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--font-dir", default="/tmp/vazirmatn-master/fonts/ttf")
    parser.add_argument("--out", default="docs/AURUM_EDGE_GUIDE_FA.pdf")
    args = parser.parse_args()

    first = build(args.font_dir, args.out)
    total = 0
    try:
        from pypdf import PdfReader  # اختیاری؛ فقط برای شمارش صفحه
        total = len(PdfReader(args.out).pages)
    except Exception:
        try:
            import pypdfium2 as pdfium
            total = len(pdfium.PdfDocument(args.out))
        except Exception:
            total = 0
    # پاس دوم: شمارهٔ صفحه‌ها در فهرست و شمارهٔ کل در پانویس
    build(args.font_dir, args.out, toc_map=first, total_pages=total)
    print(f"wrote {args.out} ({total} pages)")


if __name__ == "__main__":
    main()
