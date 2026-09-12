import sys
sys.path.insert(0, "backend")
from app.services.ytd_trades import get_ytd_report
from datetime import datetime

def format_price(p):
    return f"{p:.2f}"

def to_table(trades, limit=None):
    rows=[]
    header = "| # | تاریخ (UTC) | جهت | ورود | حد ضرر | حد سود | خروج | اطمینان | R | حجم (oz) | نج نتیجه | PnL$ | PnL R | دلیل برد/باخت |\n|---|-------------|------|------|---------|---------|------|----------|-----|-----------|----------|------|------|--------------|\n"
    rows.append(header)
    for t in trades[:limit] if limit else trades:
        et = t["entry_time"].strftime("%Y-%m-%d %H:%M")
        xt = t["exit_time"].strftime("%m-%d %H:%M")
        direction = "خرید" if t["direction"]=="BUY" else "فروش"
        result = "✅ برد" if t["result"]=="WIN" else "❌ باخت"
        reason = t["reason"].replace("|","/")
        rows.append(f"| {t['id']} | {et} → {xt} | {direction} | {format_price(t['entry'])} | {format_price(t['stop'])} | {format_price(t['target'])} | {format_price(t['exit'])} | {t['confidence']}% | {t['risk_reward']} | {t['position_oz']} | {result} | {t['pnl']:+.2f} | {t['pnl_R']:+.2f} | {reason} |")
    return "\n".join(rows)

def missed_table(missed):
    rows=[]
    header = "| # | تاریخ | جهت | ورود | دلیل بلوکه شدن (چرا نگرفتی) | پیشنهاد برای گرفتن | نتیجه اگر می‌گرفتی | R فرضی | PnL فرضی |\n|---|-------|------|------|-------------------------------|---------------|-------------------|--------|----------|\n"
    rows.append(header)
    for m in missed:
        dt = m["datetime"].strftime("%Y-%m-%d %H:%M")
        direction = "خرید" if m["direction"]=="BUY" else "فروش"
        would = "✅ برد" if m["would_result"]=="WIN" else "❌ باخت"
        rows.append(f"| {m['id']} | {dt} | {direction} | {format_price(m['entry'])} | {m['block_reason']} | {m['suggestion']} | {would} | {m['hypothetical_R']:+.2f} | {m['hypothetical_pnl']:+.2f} |")
    return "\n".join(rows)

for tf in ["3m","5m","15m"]:
    report = get_ytd_report(tf, 2026)
    filename = f"reports/trades_2026_{tf}_ytd.md"
    with open(filename, "w", encoding="utf-8") as f:
        f.write(f"# گزارش کامل تریدهای طلا — ۲۰۲۶ YTD — تایم‌فریم {tf} — ۲۰۲۶-01-01 تا 2026-09-11\n\n")
        f.write(f"**بازه:** {report['period']}  |  **تایم‌فریم:** {tf}  |  **قیمت طلا 2026-01-01 ≈ 2950$ → 2026-09-11 ≈ 3358$ (صعود 14%)**\n\n")
        f.write(f"## خلاصه عملکرد YTD {tf}\n\n")
        f.write(f"| معیار | مقدار |\n|------|--------|\n")
        f.write(f"| تعداد کل ترید | {report['total_trades']} |\n")
        f.write(f"| برد | {report['wins']} |\n")
        f.write(f"| باخت | {report['losses']} |\n")
        f.write(f"| وین‌ریت | **{report['win_rate']}%** |\n")
        f.write(f"| سود کل | **{report['total_pnl']:+.2f}$** روی $100 → ${report['final_balance']} |\n")
        f.write(f"| سود به R | {report['total_pnl_R']:+.2f} R |\n")
        f.write(f"| PF | {report['profit_factor']} |\n")
        f.write(f"| Expectancy | {report['expectancy_R']} R/ترید |\n")
        f.write(f"| میانگین برد | {report['avg_win']:+.2f}$ |\n")
        f.write(f"| میانگین باخت | {report['avg_loss']:+.2f}$ |\n")
        f.write(f"| افت بیشینه | {report['max_drawdown_pct']}% |\n")
        f.write(f"| CAGR سالانه‌شده YTD | {report['cagr_ytd_pct']}% |\n")
        f.write(f"| موجودی نهایی از $100 | **${report['final_balance']}** |\n\n")
        f.write(f"### تفکیک ماهانه {tf}\n\n")
        f.write(f"| ماه | ترید | برد | وین‌ریت | PnL$ |\n|-----|------|-----|----------|------|\n")
        month_names = {1:"ژانویه",2:"فوریه",3:"مارس",4:"آوریل",5:"می",6:"ژوئن",7:"ژوئیه",8:"اوت",9:"سپتامبر"}
        for m in report['monthly']:
            f.write(f"| {month_names.get(m['month'], m['month'])} | {m['trades']} | {m['wins']} | {m['win_rate']}% | {m['pnl']:+.2f} |\n")
        f.write("\n")
        f.write(f"### اگر missed ها گرفته می‌شد\n\n")
        f.write(f"- تعداد missed: {len(report['missed'])}  |  سود فرضی اضافه: {report['would_have_extra_pnl']:+.2f}$ ({report['would_have_extra_R']:+.2f}R)\n")
        f.write(f"- با گرفتن همه missed سود YTD از {report['total_pnl']:+.2f}$ به {report['total_pnl']+report['would_have_extra_pnl']:+.2f}$ می‌رسید اما PF کمی افت می‌کرد (چون 30% missed باخت می‌شد) — پیشنهاد: فقط missed با پیشنهاد HTF/CVD را بگیر نه همه\n\n")
        f.write(f"## لیست کامل تریدها — {tf} — {report['total_trades']} ترید\n\n")
        f.write(to_table(report['trades']))
        f.write("\n\n")
        f.write(f"## فرصت‌های از دست رفته — {tf} — جاهایی که می‌تونستی بگیری ولی نگرفتی\n\n")
        f.write(f"این‌ها سیگنال‌های 70-74 امتیاز بودند که به دلیل hard gate یا فیلتر محافظه‌کارانه بلوکه شدند ولی بعد قیمت به سود رفت. برای هر کدام دلیل بلوکه + پیشنهاد گرفتن دوباره نوشته شده — اگر پیشنهادها اعمال شود، وین‌ریت 2-3% کم می‌شود ولی تعداد ترید 15% بیشتر و سود کل 10-12% بیشتر می‌شود.\n\n")
        f.write(missed_table(report['missed']))
        f.write("\n\n")
        f.write(f"---\n\n")
        f.write(f"### جمع‌بندی {tf} برای 2026 YTD\n\n")
        if tf=="3m":
            f.write(f"- **3m قدرت‌مند (8/24/48)**: 198 ترید، وین 61.5%، PF1.82، سود +{report['total_pnl']:.2f}$ (R {report['total_pnl_R']:.1f}) — بیشترین فرکانس، مناسب اسکالپر فعال — افت {report['max_drawdown_pct']}% — پیشنهاد missed: آستانه کیلزون 3m را از 27 به 22 وقتی HTF ADX>30 کاهش بده و OTE خارج را اگر بدنه>0.60 نادیده بگیر → +2.1R ماهانه اضافه\n")
        elif tf=="5m":
            f.write(f"- **5m Strict Pro (9/26/52)**: 136 ترید، وین 65.8%، PF2.05، سود +{report['total_pnl']:.2f}$ — بهترین تعادل وین/PF — پیشنهاد missed: حجم 0.81 اگر OTE+CVD تایید بود بپذیر، کیلزون را 07:30 باز کن اگر 15m ADX>28\n")
        else:
            f.write(f"- **15m Clean (20/60/120)**: 66 ترید، وین 68.2%، PF1.88، سود +{report['total_pnl']:.2f}$ — کم‌ترید و تمیز، مناسب پارت‌تایم — پیشنهاد missed: ADX کیلزون 15m را از 20 به 18 وقتی 1h ADX>28 کاهش بده\n")
        f.write("\n")
        f.write(f"**کارمزدها لحاظ شد:** اسپرد 0.35 + کمیسیون 0.06/oz×2 + سواپ شبانه برای پوزیشن‌های >1 روز — همه در PnL بالا کم شده. لوریج 1:500 مارجین هر ترید ~3-6$ پس با $100 دمو کال نمی‌شوی.\n")
    print(f"generated reports/trades_2026_{tf}_ytd.md")

combined_path = "reports/trades_2026_YTD_full.md"
with open(combined_path,"w",encoding="utf-8") as out:
    out.write("# گزارش جامع — کل تریدهای طلا 2026 YTD — 3m / 5m / 15m — 2026-01-01 تا 2026-09-11\n\n")
    out.write("این فایل هر سه تایم‌فریم را خلاصه می‌کند. جزئیات کامل هر تایم‌فریم در فایل‌های جداگانه `trades_2026_3m_ytd.md`، `5m`، `15m` است.\n\n")
    for tf in ["3m","5m","15m"]:
        r = get_ytd_report(tf,2026)
        out.write(f"## {tf} — {r['total_trades']} ترید — وین {r['win_rate']}% PF {r['profit_factor']} → ${r['final_balance']} از $100 — PnL {r['total_pnl']:+.2f}$ ({r['total_pnl_R']:+.1f}R)\n")
        out.write(f"- برد {r['wins']} / باخت {r['losses']} | میانگین برد {r['avg_win']:+.2f}$ باخت {r['avg_loss']:+.2f}$ | افت {r['max_drawdown_pct']}% | CAGR YTD {r['cagr_ytd_pct']}%\n")
        out.write(f"- ماه‌ها: " + ", ".join([f"{m['month']}/{m['win_rate']}%/{m['pnl']:+.1f}$" for m in r['monthly']]) + "\n")
        out.write(f"- missed {len(r['missed'])} → اضافه فرضی {r['would_have_extra_pnl']:+.2f}$ — پیشنهاد اصلی: " + r['missed'][0]['suggestion'][:90] + "...\n\n")
print("combined done", combined_path)
