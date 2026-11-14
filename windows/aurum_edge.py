#!/usr/bin/env python3
"""Aurum Edge Windows desktop application — pure Python/Tkinter edition.

This is the runtime replacement for the previous C++/Qt program.  It is designed
to run from source with CPython or be packaged into a single Windows executable by
build_windows.bat.  It is strictly a historical-analysis and paper-journal app.
"""

from __future__ import annotations

import json
import os
import queue
import sqlite3
import subprocess
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from tkinter import BOTH, END, LEFT, RIGHT, X, Y, BooleanVar, Canvas, StringVar, Tk, filedialog, messagebox
from tkinter import ttk

from aurum_core import (
    BacktestConfig,
    BacktestResult,
    Candle,
    CsvAudit,
    CsvError,
    GuardedTradePlan,
    JournalStore,
    NeoWaveVerdict,
    RiskCalculation,
    analyze_neo_wave,
    calculate_position,
    export_candles,
    export_journal,
    monthly_calendar,
    neo_wave_dynamic_amplitude,
    neo_wave_pivots,
    read_csv,
    run_ichimoku_baseline,
    sanitize_trade_plan,
    train_neural_baseline,
)

APP_TITLE = "Aurum Edge — Python Desktop"
APP_VERSION = "0.2.0-python"
BG, PANEL, TEXT, MUTED, GREEN, RED, ACCENT = "#0c1220", "#131d30", "#e5ecf7", "#91a0b8", "#29c995", "#ff6580", "#67a5ff"


class AurumEdgeApp:
    def __init__(self, root: Tk) -> None:
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("1400x880")
        self.root.minsize(1120, 720)
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.candles: list[Candle] = []
        self.audit: CsvAudit | None = None
        self.backtest: BacktestResult | None = None
        self.neowave: NeoWaveVerdict | None = None
        self.status = StringVar(value="آماده — این ابزار سفارش واقعی ارسال نمی‌کند.")
        self.data_label = StringVar(value="CSV بارگذاری نشده")
        data_root = Path(os.getenv("APPDATA") or Path.home()) / "AurumEdgePython"
        self.journal = JournalStore(data_root / "journal.sqlite")
        self._setup_style()
        self._build()
        self.root.protocol("WM_DELETE_WINDOW", self._close)
        self.root.after(80, self._drain_events)

    def _setup_style(self) -> None:
        self.root.configure(background=BG)
        style = ttk.Style(self.root)
        try: style.theme_use("clam")
        except Exception: pass
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=PANEL)
        style.configure("TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 10))
        style.configure("Card.TLabel", background=PANEL, foreground=TEXT)
        style.configure("Header.TLabel", background=BG, foreground=ACCENT, font=("Segoe UI", 18, "bold"))
        style.configure("Muted.TLabel", background=BG, foreground=MUTED)
        style.configure("TButton", padding=(10, 6))
        style.configure("TNotebook", background=BG, borderwidth=0)
        style.configure("TNotebook.Tab", padding=(14, 8), background=PANEL, foreground=TEXT)
        style.map("TNotebook.Tab", background=[("selected", ACCENT)], foreground=[("selected", BG)])
        style.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=TEXT, rowheight=26)
        style.configure("Treeview.Heading", background="#1b2c49", foreground=TEXT, font=("Segoe UI", 9, "bold"))
        style.map("Treeview", background=[("selected", "#244b78")])
        style.configure("TLabelframe", background=BG, foreground=ACCENT)
        style.configure("TLabelframe.Label", background=BG, foreground=ACCENT, font=("Segoe UI", 10, "bold"))

    def _build(self) -> None:
        header = ttk.Frame(self.root, padding=(16, 12), style="Card.TFrame")
        header.pack(fill=X)
        ttk.Label(header, text="AURUM EDGE", style="Header.TLabel").pack(side=LEFT)
        ttk.Label(header, text="Python / Tkinter · دادهٔ محلی · بدون API معامله", style="Card.TLabel").pack(side=LEFT, padx=16)
        ttk.Label(header, textvariable=self.status, style="Card.TLabel").pack(side=RIGHT)
        self.tabs = ttk.Notebook(self.root)
        self.tabs.pack(fill=BOTH, expand=True, padx=12, pady=(0, 12))
        self._home_tab()
        self._chart_tab()
        self._backtest_tab()
        self._risk_tab()
        self._journal_tab()
        self._calendar_tab()
        self._neowave_tab()
        self._live_tab()
        self._settings_tab()

    def _tab(self, title: str) -> ttk.Frame:
        page = ttk.Frame(self.tabs, padding=16)
        self.tabs.add(page, text=title)
        return page

    def _home_tab(self) -> None:
        page = self._tab("خانه")
        ttk.Label(page, text="مرکز فرماندهی Aurum Edge", style="Header.TLabel").pack(anchor="w")
        ttk.Label(page, text="نسخهٔ Python جایگزین C++/Qt است. تحلیل‌ها روی دادهٔ CSV محلی اجرا می‌شوند و هیچ سفارش، کلید API یا حساب کارگزاری در برنامه وجود ندارد.", style="Muted.TLabel", wraplength=940).pack(anchor="w", pady=(8, 18))
        card = ttk.LabelFrame(page, text="وضعیت داده و بک‌تست", padding=16)
        card.pack(fill=X)
        self.home_data = StringVar(value="CSV بارگذاری نشده")
        self.home_backtest = StringVar(value="نتیجهٔ بک‌تست: —")
        self.home_neowave = StringVar(value="NeoWave: —")
        for value in (self.home_data, self.home_backtest, self.home_neowave):
            ttk.Label(card, textvariable=value, style="Card.TLabel", wraplength=1000).pack(anchor="w", pady=6)
        ttk.Button(page, text="انتخاب CSV", command=self._choose_csv).pack(anchor="w", pady=18)

    def _chart_tab(self) -> None:
        page = self._tab("چارت و CSV")
        toolbar = ttk.Frame(page)
        toolbar.pack(fill=X)
        ttk.Button(toolbar, text="انتخاب CSV", command=self._choose_csv).pack(side=LEFT)
        ttk.Button(toolbar, text="Archive CSV/JSON", command=self._export_candles).pack(side=LEFT, padx=8)
        ttk.Label(toolbar, textvariable=self.data_label, style="Muted.TLabel").pack(side=LEFT, padx=16)
        self.chart = Canvas(page, bg=BG, highlightthickness=0)
        self.chart.pack(fill=BOTH, expand=True, pady=(14, 2))
        self.chart.bind("<Configure>", lambda _event: self._draw_chart())
        ttk.Label(page, text="حداکثر ۱٬۵۰۰ کندل آخر رسم می‌شود؛ محاسبات بک‌تست روی تمام دادهٔ بارگذاری‌شده انجام می‌گیرد.", style="Muted.TLabel").pack(anchor="w", pady=6)

    def _backtest_tab(self) -> None:
        page = self._tab("بک‌تست")
        ttk.Label(page, text="Ichimoku 8/24/72 TK-cross baseline", style="Header.TLabel").pack(anchor="w")
        ttk.Label(page, text="نسخهٔ Python با ورود کندل بعدی، هزینه، لغزش، SL-first و Trend Runner. این مدل baseline مستقل است و ادعای Peyman parity یا سودآوری ندارد.", style="Muted.TLabel", wraplength=1100).pack(anchor="w", pady=(4, 12))
        form = ttk.LabelFrame(page, text="تنظیمات", padding=10); form.pack(fill=X)
        self.bt_equity, self.bt_risk, self.bt_fee, self.bt_slippage, self.bt_target = (StringVar(value=value) for value in ("1000", "0.25", "0.001", "0.0001", "2.0"))
        self.bt_long, self.bt_short, self.bt_runner = BooleanVar(value=True), BooleanVar(value=True), BooleanVar(value=True)
        for column, (label, variable) in enumerate((("سرمایه", self.bt_equity), ("ریسک %", self.bt_risk), ("Fee هر سمت", self.bt_fee), ("Slippage هر سمت", self.bt_slippage), ("Target R", self.bt_target))):
            ttk.Label(form, text=label).grid(row=0, column=column * 2, sticky="w", padx=(0, 5), pady=4)
            ttk.Entry(form, textvariable=variable, width=12).grid(row=0, column=column * 2 + 1, sticky="ew", padx=(0, 14), pady=4)
        ttk.Checkbutton(form, text="Long", variable=self.bt_long).grid(row=1, column=0, sticky="w", pady=(8, 0))
        ttk.Checkbutton(form, text="Short", variable=self.bt_short).grid(row=1, column=1, sticky="w", pady=(8, 0))
        ttk.Checkbutton(form, text="Trend Runner", variable=self.bt_runner).grid(row=1, column=2, sticky="w", pady=(8, 0))
        self.backtest_button = ttk.Button(form, text="اجرای بک‌تست", command=self._start_backtest)
        self.backtest_button.grid(row=1, column=4, columnspan=2, sticky="w", padx=12, pady=(8, 0))
        self.bt_summary = StringVar(value="CSV را بارگذاری کنید.")
        ttk.Label(page, textvariable=self.bt_summary, style="Header.TLabel", wraplength=1200).pack(anchor="w", pady=14)
        columns = ("#", "جهت", "ورود UTC", "خروج UTC", "ورود", "SL", "TP", "خروج", "دلیل", "PnL", "R", "Equity")
        self.trade_tree = self._tree(page, columns, (50, 65, 145, 145, 85, 85, 85, 85, 115, 90, 70, 100), height=18)

    def _risk_tab(self) -> None:
        page = self._tab("ریسک")
        ttk.Label(page, text="ماشین‌حساب ریسک و محافظ پلن", style="Header.TLabel").pack(anchor="w")
        ttk.Label(page, text="این محاسبه حجم و محافظه‌کاری حد ضرر را برای Paper Trading نشان می‌دهد؛ هیچ سفارش واقعی ایجاد نمی‌کند.", style="Muted.TLabel").pack(anchor="w", pady=(4, 12))
        form = ttk.LabelFrame(page, text="پارامترهای پلن", padding=12); form.pack(fill=X)
        self.r_symbol, self.r_balance, self.r_percent, self.r_entry, self.r_stop, self.r_target, self.r_atr = (StringVar(value=value) for value in ("XAU/USD", "10000", "0.5", "2000", "1990", "2020", "0"))
        values = (("نماد", self.r_symbol), ("موجودی $", self.r_balance), ("ریسک %", self.r_percent), ("ورود", self.r_entry), ("SL", self.r_stop), ("TP", self.r_target), ("ATR مرجع", self.r_atr))
        for index, (label, variable) in enumerate(values):
            row, column = divmod(index, 4); column *= 2
            ttk.Label(form, text=label).grid(row=row, column=column, sticky="w", padx=(0, 5), pady=5)
            ttk.Entry(form, textvariable=variable, width=17).grid(row=row, column=column + 1, sticky="ew", padx=(0, 16), pady=5)
        ttk.Button(form, text="محاسبهٔ ریسک", command=self._calculate_risk).grid(row=2, column=0, pady=(10, 0), sticky="w")
        ttk.Button(form, text="اصلاح محافظه‌کارانهٔ پلن", command=self._guard_plan).grid(row=2, column=1, columnspan=2, pady=(10, 0), sticky="w")
        self.risk_result = StringVar(value="مقادیر را وارد و محاسبه کنید.")
        self.guard_result = StringVar(value="")
        ttk.Label(page, textvariable=self.risk_result, wraplength=1160).pack(anchor="w", pady=(18, 6))
        ttk.Label(page, textvariable=self.guard_result, style="Muted.TLabel", wraplength=1160).pack(anchor="w")

    def _journal_tab(self) -> None:
        page = self._tab("ژورنال")
        ttk.Label(page, text="ژورنال محلی SQLite", style="Header.TLabel").pack(anchor="w")
        form = ttk.LabelFrame(page, text="ثبت معاملهٔ بسته", padding=10); form.pack(fill=X, pady=12)
        self.j_symbol, self.j_side, self.j_entry, self.j_stop, self.j_target, self.j_exit, self.j_pnl = (StringVar(value=value) for value in ("BTC/USD", "BUY", "", "", "", "", ""))
        values = (("نماد", self.j_symbol), ("جهت BUY/SELL", self.j_side), ("ورود", self.j_entry), ("SL", self.j_stop), ("TP", self.j_target), ("خروج", self.j_exit), ("PnL", self.j_pnl))
        for index, (label, variable) in enumerate(values):
            ttk.Label(form, text=label).grid(row=0, column=index * 2, sticky="w", padx=(0, 4))
            ttk.Entry(form, textvariable=variable, width=12).grid(row=0, column=index * 2 + 1, sticky="w", padx=(0, 10))
        ttk.Button(form, text="ثبت", command=self._add_journal).grid(row=1, column=0, pady=10, sticky="w")
        ttk.Button(form, text="خروجی CSV/JSON/PDF", command=self._export_journal).grid(row=1, column=1, columnspan=2, pady=10, sticky="w")
        columns = ("نماد", "جهت", "ورود", "SL", "TP", "خروج", "PnL", "زمان UTC")
        self.journal_tree = self._tree(page, columns, (110, 70, 100, 100, 100, 100, 100, 175), height=18)
        self._refresh_journal()

    def _calendar_tab(self) -> None:
        page = self._tab("تقویم")
        ttk.Label(page, text="تقویم عملکرد ماهانه", style="Header.TLabel").pack(anchor="w")
        controls = ttk.Frame(page); controls.pack(fill=X, pady=12)
        now = datetime.now()
        self.calendar_year, self.calendar_month = StringVar(value=str(now.year)), StringVar(value=str(now.month))
        for label, variable in (("سال", self.calendar_year), ("ماه", self.calendar_month)):
            ttk.Label(controls, text=label).pack(side=LEFT); ttk.Entry(controls, textvariable=variable, width=8).pack(side=LEFT, padx=(4, 14))
        ttk.Button(controls, text="نمایش", command=self._refresh_calendar).pack(side=LEFT)
        self.calendar_result = StringVar(value="")
        ttk.Label(page, textvariable=self.calendar_result, wraplength=1100).pack(anchor="w", pady=8)
        self.calendar_tree = self._tree(page, ("روز", "PnL", "تعداد معامله"), (110, 160, 160), height=22)
        self._refresh_calendar()

    def _neowave_tab(self) -> None:
        page = self._tab("NeoWave")
        ttk.Label(page, text="تحلیل ساختاری NeoWave", style="Header.TLabel").pack(anchor="w")
        ttk.Label(page, text="پیوت‌ها فقط از کندل‌های بستهٔ CSV ساخته می‌شوند. نبود ساختار معتبر خنثی است و سیگنال ساختگی تولید نمی‌شود.", style="Muted.TLabel", wraplength=1050).pack(anchor="w", pady=(4, 12))
        ttk.Button(page, text="تحلیل دادهٔ بارگذاری‌شده", command=self._analyze_neowave).pack(anchor="w")
        self.neowave_result = StringVar(value="CSV را بارگذاری کنید.")
        ttk.Label(page, textvariable=self.neowave_result, wraplength=1150).pack(anchor="w", pady=14)
        self.pivot_tree = self._tree(page, ("Index", "نوع", "قیمت", "زمان UTC"), (100, 100, 180, 220), height=20)

    def _live_tab(self) -> None:
        page = self._tab("قیمت زنده")
        ttk.Label(page, text="Quote عمومی — فقط مشاهده", style="Header.TLabel").pack(anchor="w")
        ttk.Label(page, text="هر دریافت از دادهٔ عمومی است؛ به‌دلیل اسپرد، تأخیر و تفاوت provider قیمت اجرایی محسوب نمی‌شود. هیچ اتصال حساب یا سفارشی وجود ندارد.", style="Muted.TLabel", wraplength=1100).pack(anchor="w", pady=(4, 12))
        controls = ttk.Frame(page); controls.pack(fill=X)
        self.live_symbol = StringVar(value="BTC/USDT")
        ttk.Entry(controls, textvariable=self.live_symbol, width=20).pack(side=LEFT)
        ttk.Button(controls, text="دریافت Quote", command=self._fetch_quote).pack(side=LEFT, padx=8)
        self.live_result = StringVar(value="Quote دریافت نشده است.")
        ttk.Label(page, textvariable=self.live_result, style="Header.TLabel", wraplength=1100).pack(anchor="w", pady=24)

    def _settings_tab(self) -> None:
        page = self._tab("تنظیمات")
        ttk.Label(page, text="قرارداد نسخهٔ Python", style="Header.TLabel").pack(anchor="w")
        notes = (
            "• فقط Python استاندارد + Tkinter در runtime؛ Qt، CMake، Ninja و Visual Studio لازم نیست.\n"
            "• CSV و journal روی دستگاه باقی می‌مانند؛ برنامه API key نگه نمی‌دارد.\n"
            "• در برخورد هم‌زمان SL و TP، مدل بک‌تست SL را مقدم می‌دارد.\n"
            "• ساخت EXE فقط با build_windows.bat و PyInstaller انجام می‌شود؛ فایل ناشناس دانلود یا نصب خودکار نمی‌شود."
        )
        ttk.Label(page, text=notes, justify="left", wraplength=1100).pack(anchor="w", pady=14)
        self.update_result = StringVar(value=f"نسخهٔ محلی: {APP_VERSION}")
        ttk.Label(page, textvariable=self.update_result, style="Muted.TLabel", wraplength=1100).pack(anchor="w", pady=8)
        ttk.Button(page, text="بررسی GitHub Release", command=self._check_update).pack(anchor="w")

    def _tree(self, parent: ttk.Frame, columns: tuple[str, ...], widths: tuple[int, ...], height: int) -> ttk.Treeview:
        holder = ttk.Frame(parent); holder.pack(fill=BOTH, expand=True)
        tree = ttk.Treeview(holder, columns=columns, show="headings", height=height)
        for column, width in zip(columns, widths):
            tree.heading(column, text=column); tree.column(column, width=width, anchor="center", minwidth=65)
        ybar = ttk.Scrollbar(holder, orient="vertical", command=tree.yview); xbar = ttk.Scrollbar(holder, orient="horizontal", command=tree.xview)
        tree.configure(yscrollcommand=ybar.set, xscrollcommand=xbar.set)
        tree.pack(side=LEFT, fill=BOTH, expand=True); ybar.pack(side=RIGHT, fill=Y); xbar.pack(side="bottom", fill=X)
        return tree

    def _choose_csv(self) -> None:
        path = filedialog.askopenfilename(title="انتخاب CSV", filetypes=[("CSV", "*.csv"), ("All files", "*.*")])
        if not path: return
        self.status.set("در حال خواندن CSV در Worker Thread…")
        threading.Thread(target=self._load_csv_worker, args=(Path(path),), daemon=True).start()

    def _load_csv_worker(self, path: Path) -> None:
        try:
            candles, audit = read_csv(path)
            amplitude = neo_wave_dynamic_amplitude(candles)
            pivots = neo_wave_pivots(candles, amplitude)
            verdict = analyze_neo_wave(candles, amplitude)
            self.events.put(("loaded", (path, candles, audit, pivots, verdict)))
        except Exception as exc:
            self.events.put(("error", f"خطای CSV: {exc}"))

    def _loaded(self, payload: tuple[Path, list[Candle], CsvAudit, list[object], NeoWaveVerdict | None]) -> None:
        path, self.candles, self.audit, pivots, self.neowave = payload
        audit = self.audit
        self.data_label.set(f"{path.name} · {audit.accepted_rows:,} کندل · {audit.inferred_interval} · حذف‌شده: {audit.skipped_rows + audit.duplicate_rows + audit.flat_rows_dropped}")
        self.home_data.set(self.data_label.get())
        self.status.set("CSV بارگذاری شد.")
        self.backtest = None; self.home_backtest.set("نتیجهٔ بک‌تست: —"); self.bt_summary.set("آمادهٔ اجرای بک‌تست.")
        self._draw_chart(); self._populate_pivots(pivots); self._show_neowave()

    def _draw_chart(self) -> None:
        self.chart.delete("all")
        if not self.candles:
            self.chart.create_text(max(1, self.chart.winfo_width() / 2), max(1, self.chart.winfo_height() / 2), text="CSV را بارگذاری کنید", fill=MUTED, font=("Segoe UI", 13))
            return
        bars = self.candles[-1500:]; width, height = max(300, self.chart.winfo_width()), max(240, self.chart.winfo_height())
        left, right, top, bottom = 14, width - 72, 16, height - 22
        highest, lowest = max(bar.high for bar in bars), min(bar.low for bar in bars); spread = max(highest - lowest, EPSILON)
        y = lambda price: bottom - (price - lowest) / spread * (bottom - top)
        for grid in range(5):
            yy = top + (bottom - top) * grid / 4; self.chart.create_line(left, yy, right, yy, fill="#1d2a40")
            self.chart.create_text(right + 4, yy, text=f"{highest - spread * grid / 4:.5g}", fill=MUTED, anchor="w", font=("Segoe UI", 8))
        step = (right - left) / len(bars); body = max(1, min(10, step * .68))
        for index, bar in enumerate(bars):
            x = left + (index + .5) * step; color = GREEN if bar.close >= bar.open else RED
            self.chart.create_line(x, y(bar.high), x, y(bar.low), fill=color, width=max(1, int(step * .08)))
            self.chart.create_rectangle(x - body / 2, y(max(bar.open, bar.close)), x + body / 2, y(min(bar.open, bar.close)), fill=color, outline=color)

    def _config(self) -> BacktestConfig:
        return BacktestConfig(float(self.bt_equity.get()), float(self.bt_risk.get()) / 100, float(self.bt_fee.get()), float(self.bt_slippage.get()), float(self.bt_target.get()), self.bt_long.get(), self.bt_short.get(), self.bt_runner.get())

    def _start_backtest(self) -> None:
        if not self.candles:
            messagebox.showwarning(APP_TITLE, "ابتدا CSV را بارگذاری کنید."); return
        try: config = self._config(); config.validate()
        except ValueError as exc: messagebox.showerror(APP_TITLE, f"تنظیمات نامعتبر: {exc}"); return
        self.backtest_button.configure(state="disabled"); self.status.set("بک‌تست در Worker Thread در حال اجرا است…")
        threading.Thread(target=self._backtest_worker, args=(config,), daemon=True).start()

    def _backtest_worker(self, config: BacktestConfig) -> None:
        try:
            result = run_ichimoku_baseline(self.candles, config, lambda done, total: self.events.put(("status", f"بک‌تست: {done:,}/{total:,}")))
            neural = train_neural_baseline(self.candles, result.trades, self.audit.suggested_symbol if self.audit else "")
            self.events.put(("backtest", (result, neural)))
        except Exception as exc: self.events.put(("error", f"خطای بک‌تست: {exc}"))

    def _show_backtest(self, payload: tuple[BacktestResult, object]) -> None:
        self.backtest, neural = payload
        self.backtest_button.configure(state="normal")
        stats = self.backtest.stats
        text = f"معامله: {stats.trades} | برد: {stats.win_rate:.2f}% | PF: {stats.profit_factor:.3f} | انتظار: {stats.expectancy_r:.3f}R | خالص: ${stats.net_pnl:.2f} | DD: {stats.max_drawdown_pct:.2f}%"
        self.bt_summary.set(text); self.home_backtest.set(text); self.status.set(f"بک‌تست کامل شد؛ حساب نهایی: ${stats.final_equity:.2f}")
        self._clear_tree(self.trade_tree)
        for index, trade in enumerate(self.backtest.trades, 1):
            self.trade_tree.insert("", END, values=(index, trade.side, self._time(trade.entry_time_ms), self._time(trade.exit_time_ms), f"{trade.entry:.6g}", f"{trade.stop:.6g}", f"{trade.target:.6g}", f"{trade.exit:.6g}", trade.reason, f"{trade.net_pnl:.2f}", f"{trade.r_multiple:.2f}", f"{trade.equity_after:.2f}"))
        if neural.samples:
            self.bt_summary.set(text + f" | Neural Lab: {neural.samples} نمونه، win estimate {neural.predicted_win_rate_pct:.1f}%، دقت in-sample {neural.in_sample_accuracy_pct:.1f}% (نه OOS).")

    def _calculate_risk(self) -> None:
        try:
            result = calculate_position(self.r_symbol.get(), float(self.r_balance.get()), float(self.r_percent.get()), float(self.r_entry.get()), float(self.r_stop.get()), float(self.r_target.get()))
            if not result: raise ValueError("جهت ورود/SL/TP نامعتبر است.")
            self.risk_result.set(f"{result.side} | بودجه ریسک ${result.risk_budget_usd:.2f} | حجم {result.position_units:.4f} / لات {result.standard_lots:.4f} | R:R 1:{result.risk_reward:.2f} | اهرم {result.leverage}x | مارجین ${result.margin_required_usd:.2f} | کمیسیون ${result.commission_usd:.2f} | اسپرد ${result.spread_cost_usd:.2f} | لیکوییدیشن {result.liquidation_price:.6g} ({result.liquidation_buffer_r:.2f}R) | {result.plan_note}")
        except ValueError as exc: messagebox.showerror(APP_TITLE, str(exc))

    def _guard_plan(self) -> None:
        try:
            entry, stop, target = float(self.r_entry.get()), float(self.r_stop.get()), float(self.r_target.get())
            side = "SELL" if stop > entry else "BUY"
            result = sanitize_trade_plan(self.r_symbol.get(), side, entry, stop, target, 2.0, float(self.r_atr.get()), 15)
            self.guard_result.set(f"TradePlanGuard: {'تأیید' if result.usable else 'رد'}{' / اصلاح‌شده' if result.adjusted else ''} | ورود {result.entry:.6g} · SL {result.stop_loss:.6g} · TP {result.take_profit:.6g} · R:R 1:{result.risk_reward:.2f} · اهرم {result.recommended_leverage}x · {result.note}")
        except ValueError as exc: messagebox.showerror(APP_TITLE, str(exc))

    def _add_journal(self) -> None:
        try:
            self.journal.add_trade(self.j_symbol.get(), self.j_side.get(), float(self.j_entry.get()), float(self.j_stop.get()), float(self.j_target.get()), float(self.j_exit.get()), float(self.j_pnl.get()))
            self._refresh_journal(); self._refresh_calendar(); self.status.set("معامله در ژورنال SQLite ثبت شد.")
            for value in (self.j_entry, self.j_stop, self.j_target, self.j_exit, self.j_pnl): value.set("")
        except (ValueError, sqlite3.Error) as exc: messagebox.showerror(APP_TITLE, f"ثبت ژورنال ناموفق: {exc}")

    def _refresh_journal(self) -> None:
        self._clear_tree(self.journal_tree)
        for row in self.journal.recent():
            self.journal_tree.insert("", END, values=(row["symbol"], row["side"], f"{float(row['entry']):.6g}", f"{float(row['stop']):.6g}", f"{float(row['target']):.6g}", f"{float(row['exit']):.6g}", f"{float(row['net_pnl']):.2f}", self._time(int(row["closed_at_ms"]))))

    def _export_journal(self) -> None:
        path = filedialog.asksaveasfilename(title="ذخیرهٔ ژورنال", defaultextension=".csv", filetypes=[("CSV", "*.csv"), ("JSON", "*.json"), ("PDF", "*.pdf")])
        if not path: return
        try: export_journal(self.journal.recent(1000), Path(path)); self.status.set(f"خروجی ژورنال ذخیره شد: {Path(path).name}")
        except OSError as exc: messagebox.showerror(APP_TITLE, str(exc))

    def _export_candles(self) -> None:
        if not self.candles: messagebox.showwarning(APP_TITLE, "ابتدا CSV را بارگذاری کنید."); return
        path = filedialog.asksaveasfilename(title="Archive کندل", defaultextension=".csv", filetypes=[("CSV", "*.csv"), ("JSON", "*.json")])
        if not path: return
        try: export_candles(self.candles, Path(path)); self.status.set(f"Archive ذخیره شد: {Path(path).name}")
        except OSError as exc: messagebox.showerror(APP_TITLE, str(exc))

    def _refresh_calendar(self) -> None:
        try:
            report = monthly_calendar(self.journal, int(self.calendar_year.get()), int(self.calendar_month.get()))
            self.calendar_result.set(f"PnL: ${float(report['net']):.2f} | برد/باخت: {report['wins']} / {report['losses']} | Win rate: {float(report['win_rate']):.1f}% | روز سبز/قرمز: {report['winning_days']} / {report['losing_days']}")
            self._clear_tree(self.calendar_tree)
            for item in report["entries"]: self.calendar_tree.insert("", END, values=(item["day"], f"{float(item['net']):.2f}", item["count"]))
        except ValueError: self.calendar_result.set("سال یا ماه نامعتبر است.")

    def _populate_pivots(self, pivots: list[object]) -> None:
        self._clear_tree(self.pivot_tree)
        for pivot in pivots[-30:]: self.pivot_tree.insert("", END, values=(pivot.index, "HIGH" if pivot.is_high else "LOW", f"{pivot.price:.6g}", self._time(pivot.time_ms)))

    def _analyze_neowave(self) -> None:
        if not self.candles: messagebox.showwarning(APP_TITLE, "ابتدا CSV را بارگذاری کنید."); return
        amplitude = neo_wave_dynamic_amplitude(self.candles); pivots = neo_wave_pivots(self.candles, amplitude); self.neowave = analyze_neo_wave(self.candles, amplitude); self._populate_pivots(pivots); self._show_neowave()

    def _show_neowave(self) -> None:
        if not self.neowave:
            text = "ساختار معتبر NeoWave با دادهٔ فعلی شناسایی نشد؛ داور خنثی است."
        else:
            value = self.neowave
            text = f"{value.badge} | {value.rules_passed}/{value.rules_total} قاعده | اطمینان {value.confidence_pct:.1f}% | {'تأییدشده' if value.confirmed else 'بدون تأیید'} | هدف {value.next_target:.6g} | ابطال {value.invalidation_price:.6g}"
        self.neowave_result.set(text); self.home_neowave.set("NeoWave: " + text)

    def _fetch_quote(self) -> None:
        symbol = self.live_symbol.get().strip().upper()
        if not symbol: return
        self.live_result.set("در حال دریافت Quote عمومی…")
        threading.Thread(target=self._quote_worker, args=(symbol,), daemon=True).start()

    def _quote_worker(self, symbol: str) -> None:
        compact = symbol.replace("/", "")
        try:
            if symbol.endswith(("USDT", "USDC")) or compact.startswith(("BTC", "ETH", "SOL")):
                pair = compact + "T" if symbol.endswith("/USD") else compact
                url = f"https://data-api.binance.vision/api/v3/ticker/price?symbol={urllib.parse.quote(pair)}"
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "AurumEdgePython/0.2"}), timeout=8) as response:
                    payload = json.loads(response.read().decode("utf-8")); price, provider = float(payload["price"]), "Binance public market data"
            else:
                aliases = {"XAU/USD": "GC=F", "XAG/USD": "SI=F", "EUR/USD": "EURUSD=X", "USD/JPY": "JPY=X"}; ticker = aliases.get(symbol, compact)
                url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(ticker)}?interval=1m&range=1d"
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "AurumEdgePython/0.2"}), timeout=8) as response:
                    payload = json.loads(response.read().decode("utf-8")); price, provider = float(payload["chart"]["result"][0]["meta"]["regularMarketPrice"]), "Yahoo public chart"
            self.events.put(("quote", f"{symbol}: {price:,.8g} | {provider} | {datetime.now().strftime('%H:%M:%S')} — فقط مشاهده / Paper Trading"))
        except (urllib.error.URLError, KeyError, ValueError, TimeoutError) as exc: self.events.put(("quote", f"Quote دریافت نشد: {exc}"))

    def _check_update(self) -> None:
        self.update_result.set("در حال بررسی GitHub Releases…")
        threading.Thread(target=self._update_worker, daemon=True).start()

    def _update_worker(self) -> None:
        try:
            request = urllib.request.Request("https://api.github.com/repos/parsapeyman10/ichimoko/releases/latest", headers={"User-Agent": "AurumEdgePython/0.2", "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(request, timeout=10) as response: payload = json.loads(response.read().decode("utf-8"))
            self.events.put(("update", f"آخرین Release: {payload.get('tag_name', '—')} · {payload.get('html_url', '')}"))
        except urllib.error.HTTPError as exc:
            self.events.put(("update", "هنوز Release عمومی برای ویندوز منتشر نشده است." if exc.code == 404 else f"بررسی update ناموفق: HTTP {exc.code}"))
        except (urllib.error.URLError, TimeoutError, ValueError) as exc: self.events.put(("update", f"بررسی update ناموفق: {exc}"))

    def _drain_events(self) -> None:
        try:
            while True:
                name, payload = self.events.get_nowait()
                if name == "loaded": self._loaded(payload)
                elif name == "backtest": self._show_backtest(payload)
                elif name == "status": self.status.set(str(payload))
                elif name == "error": self.status.set(str(payload)); self.backtest_button.configure(state="normal"); messagebox.showerror(APP_TITLE, str(payload))
                elif name == "quote": self.live_result.set(str(payload))
                elif name == "update": self.update_result.set(str(payload))
        except queue.Empty: pass
        self.root.after(80, self._drain_events)

    @staticmethod
    def _clear_tree(tree: ttk.Treeview) -> None:
        tree.delete(*tree.get_children())

    @staticmethod
    def _time(time_ms: int) -> str:
        return datetime.fromtimestamp(time_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M")

    def _close(self) -> None:
        self.journal.close(); self.root.destroy()


def main() -> int:
    root = Tk(); AurumEdgeApp(root); root.mainloop(); return 0


if __name__ == "__main__":
    raise SystemExit(main())
