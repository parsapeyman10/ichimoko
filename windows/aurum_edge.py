#!/usr/bin/env python3
"""Aurum Edge desktop application — PyQt6 edition.

The interface is intentionally a basic PyQt rewrite: all trading calculations stay
in aurum_core, while this module owns only presentation, local-file selection and
background UI work. It has no broker credentials or live order routing.
"""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from PyQt6.QtCore import QObject, Qt, pyqtSignal
from PyQt6.QtGui import QColor, QFont, QPainter, QPen
from PyQt6.QtWidgets import (
    QApplication, QCheckBox, QFileDialog, QGridLayout, QGroupBox, QHBoxLayout,
    QLabel, QLineEdit, QMainWindow, QMessageBox, QPushButton, QTableWidget, QTableWidgetItem,
    QTabWidget, QVBoxLayout, QWidget,
)

from aurum_core import (
    BacktestConfig, BacktestResult, Candle, CsvAudit, JournalStore,
    NeoWaveVerdict, analyze_neo_wave, calculate_position, export_candles,
    export_journal, monthly_calendar, neo_wave_dynamic_amplitude,
    neo_wave_pivots, read_csv, run_ichimoku_baseline, sanitize_trade_plan,
    train_neural_baseline,
)

APP_TITLE = "Aurum Edge — PyQt Desktop"
APP_VERSION = "0.4.0-pyqt"
BG, PANEL, TEXT, MUTED, GREEN, RED, ACCENT = "#0c1220", "#131d30", "#e5ecf7", "#91a0b8", "#29c995", "#ff6580", "#67a5ff"


def application_data_root() -> Path:
    if os.name == "nt":
        return Path(os.getenv("APPDATA") or Path.home()) / "AurumEdgePyQt"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "AurumEdgePyQt"
    return Path(os.getenv("XDG_DATA_HOME") or (Path.home() / ".local" / "share")) / "AurumEdgePyQt"


def utc_text(time_ms: int) -> str:
    return datetime.fromtimestamp(time_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M")


class EventBus(QObject):
    event = pyqtSignal(str, object)


class CandleChart(QWidget):
    """Small dependency-free OHLC renderer for the latest candle window."""
    def __init__(self) -> None:
        super().__init__()
        self.candles: list[Candle] = []
        self.setMinimumHeight(300)

    def set_candles(self, candles: list[Candle]) -> None:
        self.candles = candles[-1500:]
        self.update()

    def paintEvent(self, _event: object) -> None:  # noqa: N802 - Qt API spelling
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(BG))
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        if not self.candles:
            painter.setPen(QColor(MUTED))
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, "CSV را بارگذاری کنید")
            return
        width, height = max(300, self.width()), max(240, self.height())
        left, right, top, bottom = 14.0, width - 72.0, 16.0, height - 22.0
        high, low = max(bar.high for bar in self.candles), min(bar.low for bar in self.candles)
        spread = max(high - low, 1e-9)
        y = lambda price: bottom - (price - low) / spread * (bottom - top)
        painter.setFont(QFont("Arial", 8))
        for grid in range(5):
            yy = top + (bottom - top) * grid / 4
            painter.setPen(QPen(QColor("#1d2a40"), 1))
            painter.drawLine(int(left), int(yy), int(right), int(yy))
            painter.setPen(QColor(MUTED))
            painter.drawText(int(right + 4), int(yy + 4), f"{high - spread * grid / 4:.5g}")
        step = (right - left) / len(self.candles)
        body = max(1.0, min(10.0, step * 0.68))
        for index, bar in enumerate(self.candles):
            x = left + (index + 0.5) * step
            color = QColor(GREEN if bar.close >= bar.open else RED)
            painter.setPen(QPen(color, max(1, int(step * 0.08))))
            painter.drawLine(int(x), int(y(bar.high)), int(x), int(y(bar.low)))
            y_top, y_bottom = y(max(bar.open, bar.close)), y(min(bar.open, bar.close))
            painter.fillRect(int(x - body / 2), int(y_top), max(1, int(body)), max(1, int(y_bottom - y_top)), color)


class AurumEdgeWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.candles: list[Candle] = []
        self.audit: CsvAudit | None = None
        self.backtest: BacktestResult | None = None
        self.neowave: NeoWaveVerdict | None = None
        self.bus = EventBus()
        self.bus.event.connect(self._handle_event)
        self.journal = JournalStore(application_data_root() / "journal.sqlite")
        self.setWindowTitle(APP_TITLE)
        self.resize(1400, 880)
        self.setMinimumSize(1080, 700)
        self._style()
        self._build()
        self.statusBar().showMessage("آماده — ابزار تحلیل تاریخی و Paper Trading؛ بدون سفارش واقعی.")

    def _style(self) -> None:
        self.setStyleSheet(f"""
            QMainWindow, QWidget {{ background: {BG}; color: {TEXT}; font-family: Arial; font-size: 13px; }}
            QTabWidget::pane {{ border: 1px solid #263652; }}
            QTabBar::tab {{ background: {PANEL}; color: {TEXT}; padding: 9px 14px; margin: 1px; }}
            QTabBar::tab:selected {{ background: {ACCENT}; color: {BG}; font-weight: bold; }}
            QGroupBox {{ border: 1px solid #2a3c5c; border-radius: 5px; margin-top: 12px; padding: 10px; color: {ACCENT}; font-weight: bold; }}
            QGroupBox::title {{ subcontrol-origin: margin; left: 10px; padding: 0 4px; }}
            QLineEdit, QComboBox, QTableWidget {{ background: {PANEL}; color: {TEXT}; border: 1px solid #31435f; border-radius: 3px; padding: 5px; }}
            QPushButton {{ background: #214873; color: white; border: 0; border-radius: 4px; padding: 8px 12px; }}
            QPushButton:hover {{ background: #2d6099; }}
            QPushButton:disabled {{ background: #394455; color: #9ca7b6; }}
            QHeaderView::section {{ background: #1b2c49; color: {TEXT}; padding: 6px; border: 0; }}
            QStatusBar {{ background: {PANEL}; color: {MUTED}; }}
        """)

    def _build(self) -> None:
        root = QWidget(); outer = QVBoxLayout(root); outer.setContentsMargins(14, 12, 14, 12)
        header = QHBoxLayout()
        title = QLabel("AURUM EDGE"); title.setStyleSheet(f"color: {ACCENT}; font-size: 22px; font-weight: bold;")
        subtitle = QLabel("PyQt6 · دادهٔ محلی · بدون API معامله")
        subtitle.setStyleSheet(f"color: {MUTED};")
        header.addWidget(title); header.addWidget(subtitle); header.addStretch()
        outer.addLayout(header)
        self.tabs = QTabWidget(); outer.addWidget(self.tabs)
        self.setCentralWidget(root)
        self._home_tab(); self._chart_tab(); self._backtest_tab(); self._risk_tab(); self._journal_tab(); self._calendar_tab(); self._neowave_tab(); self._live_tab(); self._settings_tab()

    def _page(self, title: str) -> tuple[QWidget, QVBoxLayout]:
        page = QWidget(); layout = QVBoxLayout(page); layout.setContentsMargins(14, 14, 14, 14)
        self.tabs.addTab(page, title)
        return page, layout

    @staticmethod
    def _heading(text: str) -> QLabel:
        label = QLabel(text); label.setStyleSheet(f"color: {ACCENT}; font-size: 20px; font-weight: bold;"); label.setWordWrap(True)
        return label

    @staticmethod
    def _muted(text: str) -> QLabel:
        label = QLabel(text); label.setStyleSheet(f"color: {MUTED};"); label.setWordWrap(True)
        return label

    @staticmethod
    def _table(headers: list[str]) -> QTableWidget:
        table = QTableWidget(0, len(headers)); table.setHorizontalHeaderLabels(headers)
        table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        table.setAlternatingRowColors(True); table.verticalHeader().setVisible(False)
        table.horizontalHeader().setStretchLastSection(True)
        return table

    @staticmethod
    def _put(table: QTableWidget, rows: list[list[object]]) -> None:
        table.setRowCount(0)
        for values in rows:
            row = table.rowCount(); table.insertRow(row)
            for column, value in enumerate(values):
                table.setItem(row, column, QTableWidgetItem(str(value)))

    @staticmethod
    def _fields(items: list[tuple[str, str]], columns: int = 4) -> tuple[QWidget, dict[str, QLineEdit]]:
        box = QGroupBox("تنظیمات"); grid = QGridLayout(box); fields: dict[str, QLineEdit] = {}
        for index, (key, value) in enumerate(items):
            row, column = divmod(index, columns); column *= 2
            grid.addWidget(QLabel(key), row, column)
            field = QLineEdit(value); fields[key] = field; grid.addWidget(field, row, column + 1)
        return box, fields

    def _home_tab(self) -> None:
        _, layout = self._page("خانه")
        layout.addWidget(self._heading("مرکز فرماندهی Aurum Edge"))
        layout.addWidget(self._muted("نسخهٔ پایهٔ PyQt6. تمام تحلیل‌ها روی CSV محلی انجام می‌شوند؛ هیچ کلید API، حساب یا سفارش واقعی وجود ندارد."))
        card = QGroupBox("وضعیت داده و بک‌تست"); card_layout = QVBoxLayout(card)
        self.home_data, self.home_backtest, self.home_neowave = QLabel("CSV بارگذاری نشده"), QLabel("نتیجهٔ بک‌تست: —"), QLabel("NeoWave: —")
        for label in (self.home_data, self.home_backtest, self.home_neowave): label.setWordWrap(True); card_layout.addWidget(label)
        layout.addWidget(card); button = QPushButton("انتخاب CSV"); button.clicked.connect(self._choose_csv); layout.addWidget(button); layout.addStretch()

    def _chart_tab(self) -> None:
        _, layout = self._page("چارت و CSV")
        bar = QHBoxLayout(); choose = QPushButton("انتخاب CSV"); choose.clicked.connect(self._choose_csv)
        archive = QPushButton("Archive CSV/JSON"); archive.clicked.connect(self._export_candles)
        self.data_label = self._muted("CSV بارگذاری نشده")
        bar.addWidget(choose); bar.addWidget(archive); bar.addWidget(self.data_label); bar.addStretch(); layout.addLayout(bar)
        self.chart = CandleChart(); layout.addWidget(self.chart, 1)
        layout.addWidget(self._muted("حداکثر ۱٬۵۰۰ کندل آخر رسم می‌شود؛ بک‌تست روی تمام دادهٔ بارگذاری‌شده اجرا می‌شود."))

    def _backtest_tab(self) -> None:
        _, layout = self._page("بک‌تست")
        layout.addWidget(self._heading("Ichimoku 8/24/72 TK-cross baseline")); layout.addWidget(self._muted("ورود کندل بعدی، هزینه، لغزش، SL-first و Trend Runner. این مدل ادعای Peyman parity یا سودآوری ندارد."))
        form, self.bt = self._fields([("سرمایه", "1000"), ("ریسک %", "0.25"), ("Fee هر سمت", "0.001"), ("Slippage", "0.0001"), ("Target R", "2.0")], 5)
        self.bt_long, self.bt_short, self.bt_runner = QCheckBox("Long"), QCheckBox("Short"), QCheckBox("Trend Runner")
        self.bt_long.setChecked(True); self.bt_short.setChecked(True); self.bt_runner.setChecked(True)
        actions = QHBoxLayout(); actions.addWidget(self.bt_long); actions.addWidget(self.bt_short); actions.addWidget(self.bt_runner)
        self.backtest_button = QPushButton("اجرای بک‌تست"); self.backtest_button.clicked.connect(self._start_backtest); actions.addWidget(self.backtest_button); actions.addStretch(); form.layout().addLayout(actions, 2, 0, 1, 10)
        layout.addWidget(form); self.bt_summary = QLabel("CSV را بارگذاری کنید."); self.bt_summary.setWordWrap(True); layout.addWidget(self.bt_summary)
        self.trade_table = self._table(["#", "جهت", "ورود UTC", "خروج UTC", "ورود", "SL", "TP", "خروج", "دلیل", "PnL", "R", "Equity"]); layout.addWidget(self.trade_table, 1)

    def _risk_tab(self) -> None:
        _, layout = self._page("ریسک")
        layout.addWidget(self._heading("ماشین‌حساب ریسک و محافظ پلن")); layout.addWidget(self._muted("محاسبهٔ حجم و محافظه‌کاری SL برای Paper Trading؛ بدون ایجاد سفارش."))
        form, self.risk = self._fields([("نماد", "XAU/USD"), ("موجودی $", "10000"), ("ریسک %", "0.5"), ("ورود", "2000"), ("SL", "1990"), ("TP", "2020"), ("ATR مرجع", "0")])
        actions = QHBoxLayout(); calculate = QPushButton("محاسبهٔ ریسک"); calculate.clicked.connect(self._calculate_risk); guard = QPushButton("اصلاح محافظه‌کارانهٔ پلن"); guard.clicked.connect(self._guard_plan); actions.addWidget(calculate); actions.addWidget(guard); actions.addStretch(); form.layout().addLayout(actions, 2, 0, 1, 8)
        layout.addWidget(form); self.risk_result, self.guard_result = QLabel("مقادیر را وارد و محاسبه کنید."), self._muted("")
        self.risk_result.setWordWrap(True); self.guard_result.setWordWrap(True); layout.addWidget(self.risk_result); layout.addWidget(self.guard_result); layout.addStretch()

    def _journal_tab(self) -> None:
        _, layout = self._page("ژورنال")
        layout.addWidget(self._heading("ژورنال محلی SQLite"))
        form, self.journal_fields = self._fields([("نماد", "BTC/USD"), ("جهت", "BUY"), ("ورود", ""), ("SL", ""), ("TP", ""), ("خروج", ""), ("PnL", "")])
        buttons = QHBoxLayout(); add = QPushButton("ثبت"); add.clicked.connect(self._add_journal); export = QPushButton("خروجی CSV/JSON/PDF"); export.clicked.connect(self._export_journal); buttons.addWidget(add); buttons.addWidget(export); buttons.addStretch(); form.layout().addLayout(buttons, 2, 0, 1, 8)
        layout.addWidget(form); self.journal_table = self._table(["نماد", "جهت", "ورود", "SL", "TP", "خروج", "PnL", "زمان UTC"]); layout.addWidget(self.journal_table, 1); self._refresh_journal()

    def _calendar_tab(self) -> None:
        _, layout = self._page("تقویم")
        layout.addWidget(self._heading("تقویم عملکرد ماهانه")); bar = QHBoxLayout(); now = datetime.now()
        self.calendar_year, self.calendar_month = QLineEdit(str(now.year)), QLineEdit(str(now.month)); refresh = QPushButton("نمایش"); refresh.clicked.connect(self._refresh_calendar)
        for text, field in (("سال", self.calendar_year), ("ماه", self.calendar_month)): bar.addWidget(QLabel(text)); field.setMaximumWidth(100); bar.addWidget(field)
        bar.addWidget(refresh); bar.addStretch(); layout.addLayout(bar); self.calendar_result = QLabel(); self.calendar_result.setWordWrap(True); layout.addWidget(self.calendar_result)
        self.calendar_table = self._table(["روز", "PnL", "تعداد معامله"]); layout.addWidget(self.calendar_table, 1); self._refresh_calendar()

    def _neowave_tab(self) -> None:
        _, layout = self._page("NeoWave")
        layout.addWidget(self._heading("تحلیل ساختاری NeoWave")); layout.addWidget(self._muted("پیوت‌ها فقط از کندل‌های بسته ساخته می‌شوند؛ نبود ساختار معتبر خنثی است."))
        button = QPushButton("تحلیل دادهٔ بارگذاری‌شده"); button.clicked.connect(self._analyze_neowave); layout.addWidget(button)
        self.neowave_result = QLabel("CSV را بارگذاری کنید."); self.neowave_result.setWordWrap(True); layout.addWidget(self.neowave_result)
        self.pivot_table = self._table(["Index", "نوع", "قیمت", "زمان UTC"]); layout.addWidget(self.pivot_table, 1)

    def _live_tab(self) -> None:
        _, layout = self._page("قیمت زنده")
        layout.addWidget(self._heading("Quote عمومی — فقط مشاهده")); layout.addWidget(self._muted("Quote عمومی قیمت قابل اجرا نیست و اتصال حساب یا ارسال سفارش ندارد."))
        bar = QHBoxLayout(); self.live_symbol = QLineEdit("BTC/USDT"); self.live_symbol.setMaximumWidth(220); get = QPushButton("دریافت Quote"); get.clicked.connect(self._fetch_quote); bar.addWidget(self.live_symbol); bar.addWidget(get); bar.addStretch(); layout.addLayout(bar)
        self.live_result = QLabel("Quote دریافت نشده است."); self.live_result.setWordWrap(True); self.live_result.setStyleSheet(f"color: {ACCENT}; font-size: 20px;"); layout.addWidget(self.live_result); layout.addStretch()

    def _settings_tab(self) -> None:
        _, layout = self._page("تنظیمات")
        layout.addWidget(self._heading("قرارداد نسخهٔ PyQt6"))
        layout.addWidget(QLabel("• رابط PyQt6 برای Windows، macOS و Linux\n• CSV و journal محلی هستند و API key نگه‌داری نمی‌شود.\n• برخورد هم‌زمان SL/TP با SL-first مدل می‌شود.\n• ساخت artifact با PyInstaller و بدون نصب خودکار فایل ناشناس انجام می‌شود."))
        self.update_result = self._muted(f"نسخهٔ محلی: {APP_VERSION}"); self.update_result.setWordWrap(True); layout.addWidget(self.update_result)
        check = QPushButton("بررسی GitHub Release"); check.clicked.connect(self._check_update); layout.addWidget(check); layout.addStretch()

    def _choose_csv(self) -> None:
        filename, _ = QFileDialog.getOpenFileName(self, "انتخاب CSV", "", "CSV files (*.csv);;All files (*.*)")
        if filename:
            self.statusBar().showMessage("در حال خواندن CSV در Worker Thread…")
            threading.Thread(target=self._load_csv_worker, args=(Path(filename),), daemon=True).start()

    def _load_csv_worker(self, path: Path) -> None:
        try:
            candles, audit = read_csv(path); amplitude = neo_wave_dynamic_amplitude(candles); pivots = neo_wave_pivots(candles, amplitude)
            self.bus.event.emit("loaded", (path, candles, audit, pivots, analyze_neo_wave(candles, amplitude)))
        except Exception as error: self.bus.event.emit("error", f"خطای CSV: {error}")

    def _loaded(self, payload: tuple[Path, list[Candle], CsvAudit, list[object], NeoWaveVerdict | None]) -> None:
        path, self.candles, self.audit, pivots, self.neowave = payload; audit = self.audit
        text = f"{path.name} · {audit.accepted_rows:,} کندل · {audit.inferred_interval} · حذف‌شده: {audit.skipped_rows + audit.duplicate_rows + audit.flat_rows_dropped}"
        self.data_label.setText(text); self.home_data.setText(text); self.chart.set_candles(self.candles); self.backtest = None; self.bt_summary.setText("آمادهٔ اجرای بک‌تست."); self.home_backtest.setText("نتیجهٔ بک‌تست: —"); self._put(self.trade_table, []); self._put(self.pivot_table, [[item.index, "HIGH" if item.is_high else "LOW", f"{item.price:.6g}", utc_text(item.time_ms)] for item in pivots[-30:]]); self._show_neowave(); self.statusBar().showMessage("CSV بارگذاری شد.")

    def _config(self) -> BacktestConfig:
        return BacktestConfig(float(self.bt["سرمایه"].text()), float(self.bt["ریسک %"].text()) / 100, float(self.bt["Fee هر سمت"].text()), float(self.bt["Slippage"].text()), float(self.bt["Target R"].text()), self.bt_long.isChecked(), self.bt_short.isChecked(), self.bt_runner.isChecked())

    def _start_backtest(self) -> None:
        if not self.candles: QMessageBox.warning(self, APP_TITLE, "ابتدا CSV را بارگذاری کنید."); return
        try: config = self._config(); config.validate()
        except ValueError as error: QMessageBox.critical(self, APP_TITLE, f"تنظیمات نامعتبر: {error}"); return
        self.backtest_button.setEnabled(False); self.statusBar().showMessage("بک‌تست در Worker Thread در حال اجرا است…"); threading.Thread(target=self._backtest_worker, args=(config,), daemon=True).start()

    def _backtest_worker(self, config: BacktestConfig) -> None:
        try:
            result = run_ichimoku_baseline(self.candles, config, lambda done, total: self.bus.event.emit("status", f"بک‌تست: {done:,}/{total:,}"))
            self.bus.event.emit("backtest", (result, train_neural_baseline(self.candles, result.trades, self.audit.suggested_symbol if self.audit else "")))
        except Exception as error: self.bus.event.emit("error", f"خطای بک‌تست: {error}")

    def _show_backtest(self, payload: tuple[BacktestResult, object]) -> None:
        self.backtest, neural = payload; self.backtest_button.setEnabled(True); stats = self.backtest.stats
        text = f"معامله: {stats.trades} | برد: {stats.win_rate:.2f}% | PF: {stats.profit_factor:.3f} | انتظار: {stats.expectancy_r:.3f}R | خالص: ${stats.net_pnl:.2f} | DD: {stats.max_drawdown_pct:.2f}%"
        if neural.samples: text += f" | Neural Lab: {neural.samples} نمونه، win estimate {neural.predicted_win_rate_pct:.1f}%، دقت in-sample {neural.in_sample_accuracy_pct:.1f}%"
        self.bt_summary.setText(text); self.home_backtest.setText(text); self.statusBar().showMessage(f"بک‌تست کامل شد؛ حساب نهایی: ${stats.final_equity:.2f}")
        self._put(self.trade_table, [[index, item.side, utc_text(item.entry_time_ms), utc_text(item.exit_time_ms), f"{item.entry:.6g}", f"{item.stop:.6g}", f"{item.target:.6g}", f"{item.exit:.6g}", item.reason, f"{item.net_pnl:.2f}", f"{item.r_multiple:.2f}", f"{item.equity_after:.2f}"] for index, item in enumerate(self.backtest.trades, 1)])

    def _calculate_risk(self) -> None:
        try:
            item = calculate_position(self.risk["نماد"].text(), float(self.risk["موجودی $"].text()), float(self.risk["ریسک %"].text()), float(self.risk["ورود"].text()), float(self.risk["SL"].text()), float(self.risk["TP"].text()))
            if not item: raise ValueError("جهت ورود/SL/TP نامعتبر است.")
            self.risk_result.setText(f"{item.side} | بودجه ریسک ${item.risk_budget_usd:.2f} | حجم {item.position_units:.4f} / لات {item.standard_lots:.4f} | R:R 1:{item.risk_reward:.2f} | اهرم {item.leverage}x | مارجین ${item.margin_required_usd:.2f} | لیکوییدیشن {item.liquidation_price:.6g} | {item.plan_note}")
        except ValueError as error: QMessageBox.critical(self, APP_TITLE, str(error))

    def _guard_plan(self) -> None:
        try:
            entry, stop, target = float(self.risk["ورود"].text()), float(self.risk["SL"].text()), float(self.risk["TP"].text()); side = "SELL" if stop > entry else "BUY"
            item = sanitize_trade_plan(self.risk["نماد"].text(), side, entry, stop, target, 2.0, float(self.risk["ATR مرجع"].text()), 15)
            self.guard_result.setText(f"TradePlanGuard: {'تأیید' if item.usable else 'رد'}{' / اصلاح‌شده' if item.adjusted else ''} | ورود {item.entry:.6g} · SL {item.stop_loss:.6g} · TP {item.take_profit:.6g} · R:R 1:{item.risk_reward:.2f} · اهرم {item.recommended_leverage}x · {item.note}")
        except ValueError as error: QMessageBox.critical(self, APP_TITLE, str(error))

    def _add_journal(self) -> None:
        try:
            values = self.journal_fields; self.journal.add_trade(values["نماد"].text(), values["جهت"].text(), float(values["ورود"].text()), float(values["SL"].text()), float(values["TP"].text()), float(values["خروج"].text()), float(values["PnL"].text()))
            for key in ("ورود", "SL", "TP", "خروج", "PnL"): values[key].clear()
            self._refresh_journal(); self._refresh_calendar(); self.statusBar().showMessage("معامله در ژورنال SQLite ثبت شد.")
        except (ValueError, sqlite3.Error) as error: QMessageBox.critical(self, APP_TITLE, f"ثبت ژورنال ناموفق: {error}")

    def _refresh_journal(self) -> None:
        self._put(self.journal_table, [[row["symbol"], row["side"], f"{float(row['entry']):.6g}", f"{float(row['stop']):.6g}", f"{float(row['target']):.6g}", f"{float(row['exit']):.6g}", f"{float(row['net_pnl']):.2f}", utc_text(int(row["closed_at_ms"]))] for row in self.journal.recent()])

    def _export_journal(self) -> None:
        filename, _ = QFileDialog.getSaveFileName(self, "ذخیرهٔ ژورنال", "journal.csv", "CSV (*.csv);;JSON (*.json);;PDF (*.pdf)")
        if filename:
            try: export_journal(self.journal.recent(1000), Path(filename)); self.statusBar().showMessage(f"خروجی ژورنال ذخیره شد: {Path(filename).name}")
            except OSError as error: QMessageBox.critical(self, APP_TITLE, str(error))

    def _export_candles(self) -> None:
        if not self.candles: QMessageBox.warning(self, APP_TITLE, "ابتدا CSV را بارگذاری کنید."); return
        filename, _ = QFileDialog.getSaveFileName(self, "Archive کندل", "candles.csv", "CSV (*.csv);;JSON (*.json)")
        if filename:
            try: export_candles(self.candles, Path(filename)); self.statusBar().showMessage(f"Archive ذخیره شد: {Path(filename).name}")
            except OSError as error: QMessageBox.critical(self, APP_TITLE, str(error))

    def _refresh_calendar(self) -> None:
        try:
            report = monthly_calendar(self.journal, int(self.calendar_year.text()), int(self.calendar_month.text()))
            self.calendar_result.setText(f"PnL: ${float(report['net']):.2f} | برد/باخت: {report['wins']} / {report['losses']} | Win rate: {float(report['win_rate']):.1f}% | روز سبز/قرمز: {report['winning_days']} / {report['losing_days']}")
            self._put(self.calendar_table, [[item["day"], f"{float(item['net']):.2f}", item["count"]] for item in report["entries"]])
        except ValueError: self.calendar_result.setText("سال یا ماه نامعتبر است.")

    def _analyze_neowave(self) -> None:
        if not self.candles: QMessageBox.warning(self, APP_TITLE, "ابتدا CSV را بارگذاری کنید."); return
        amplitude = neo_wave_dynamic_amplitude(self.candles); pivots = neo_wave_pivots(self.candles, amplitude); self.neowave = analyze_neo_wave(self.candles, amplitude); self._put(self.pivot_table, [[item.index, "HIGH" if item.is_high else "LOW", f"{item.price:.6g}", utc_text(item.time_ms)] for item in pivots[-30:]]); self._show_neowave()

    def _show_neowave(self) -> None:
        if not self.neowave: text = "ساختار معتبر NeoWave با دادهٔ فعلی شناسایی نشد؛ داور خنثی است."
        else:
            item = self.neowave; text = f"{item.badge} | {item.rules_passed}/{item.rules_total} قاعده | اطمینان {item.confidence_pct:.1f}% | {'تأییدشده' if item.confirmed else 'بدون تأیید'} | هدف {item.next_target:.6g} | ابطال {item.invalidation_price:.6g}"
        self.neowave_result.setText(text); self.home_neowave.setText("NeoWave: " + text)

    def _fetch_quote(self) -> None:
        symbol = self.live_symbol.text().strip().upper()
        if symbol: self.live_result.setText("در حال دریافت Quote عمومی…"); threading.Thread(target=self._quote_worker, args=(symbol,), daemon=True).start()

    def _quote_worker(self, symbol: str) -> None:
        compact = symbol.replace("/", "")
        try:
            if symbol.endswith(("USDT", "USDC")) or compact.startswith(("BTC", "ETH", "SOL")):
                url = f"https://data-api.binance.vision/api/v3/ticker/price?symbol={urllib.parse.quote(compact)}"
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "AurumEdgePyQt/0.4"}), timeout=8) as response: price, provider = float(json.loads(response.read().decode("utf-8"))["price"]), "Binance public market data"
            else:
                ticker = {"XAU/USD": "GC=F", "XAG/USD": "SI=F", "EUR/USD": "EURUSD=X", "USD/JPY": "JPY=X"}.get(symbol, compact); url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(ticker)}?interval=1m&range=1d"
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "AurumEdgePyQt/0.4"}), timeout=8) as response: price, provider = float(json.loads(response.read().decode("utf-8"))["chart"]["result"][0]["meta"]["regularMarketPrice"]), "Yahoo public chart"
            self.bus.event.emit("quote", f"{symbol}: {price:,.8g} | {provider} | {datetime.now().strftime('%H:%M:%S')} — فقط مشاهده / Paper Trading")
        except (urllib.error.URLError, KeyError, ValueError, TimeoutError) as error: self.bus.event.emit("quote", f"Quote دریافت نشد: {error}")

    def _check_update(self) -> None:
        self.update_result.setText("در حال بررسی GitHub Releases…"); threading.Thread(target=self._update_worker, daemon=True).start()

    def _update_worker(self) -> None:
        try:
            request = urllib.request.Request("https://api.github.com/repos/parsapeyman10/ichimoko/releases/latest", headers={"User-Agent": "AurumEdgePyQt/0.4", "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(request, timeout=10) as response: payload = json.loads(response.read().decode("utf-8"))
            self.bus.event.emit("update", f"آخرین Release: {payload.get('tag_name', '—')} · {payload.get('html_url', '')}")
        except urllib.error.HTTPError as error: self.bus.event.emit("update", "هنوز Release عمومی برای ویندوز منتشر نشده است." if error.code == 404 else f"بررسی update ناموفق: HTTP {error.code}")
        except (urllib.error.URLError, TimeoutError, ValueError) as error: self.bus.event.emit("update", f"بررسی update ناموفق: {error}")

    def _handle_event(self, name: str, payload: object) -> None:
        if name == "loaded": self._loaded(payload)  # type: ignore[arg-type]
        elif name == "backtest": self._show_backtest(payload)  # type: ignore[arg-type]
        elif name == "status": self.statusBar().showMessage(str(payload))
        elif name == "error": self.backtest_button.setEnabled(True); self.statusBar().showMessage(str(payload)); QMessageBox.critical(self, APP_TITLE, str(payload))
        elif name == "quote": self.live_result.setText(str(payload))
        elif name == "update": self.update_result.setText(str(payload))

    def closeEvent(self, event: object) -> None:  # noqa: N802 - Qt API spelling
        self.journal.close(); event.accept()  # type: ignore[attr-defined]


def main() -> int:
    app = QApplication(sys.argv); app.setApplicationName("Aurum Edge")
    window = AurumEdgeWindow(); window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
