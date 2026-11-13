#!/usr/bin/env python3
"""Windows desktop GUI for the dependency-free native-15m backtest engine.

Run directly with Python or package locally on Windows through
build_windows_backtest_app.bat. It has no Android, Qt, broker or exchange API.
"""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import traceback
from pathlib import Path
from tkinter import BOTH, END, LEFT, RIGHT, X, Y, BooleanVar, StringVar, Tk, filedialog, messagebox
from tkinter import ttk

from native15_engine import (
    BacktestResult,
    CsvValidationError,
    Settings,
    iso_utc,
    read_15m_csv,
    run_backtest,
    write_reports,
)

APP_TITLE = "BASE Ichimoku — Native 15m Python Backtest"


class BacktestApp:
    def __init__(self, root: Tk) -> None:
        self.root = root
        self.root.title(APP_TITLE)
        self.root.minsize(1080, 720)
        self.root.geometry("1320x820")
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.running = False
        self.last_output: Path | None = None

        self.csv_path = StringVar()
        self.output_path = StringVar()
        self.symbol = StringVar(value="XAUUSDT")
        self.start_equity = StringVar(value="1000")
        self.quantity = StringVar(value="1")
        self.target_rr = StringVar(value="2.25")
        self.cost_fraction = StringVar(value="0.0021")
        self.sl_buffer = StringVar(value="0.25")
        self.smart_exit = BooleanVar(value=True)
        self.smart_confirmations = StringVar(value="2")
        self.allow_gaps = BooleanVar(value=False)
        self.status = StringVar(value="Ready — select a 15m CSV export.")
        self.summary = StringVar(value="No backtest has run yet.")

        self._build_layout()
        self.root.after(80, self._drain_events)

    def _build_layout(self) -> None:
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("Header.TLabel", font=("Segoe UI", 11, "bold"))
        style.configure("Status.TLabel", padding=(8, 6))

        outer = ttk.Frame(self.root, padding=12)
        outer.pack(fill=BOTH, expand=True)
        outer.columnconfigure(0, weight=1)
        outer.rowconfigure(2, weight=1)

        title = ttk.Label(outer, text="BASE Ichimoku — Native 15m Python Backtest", style="Header.TLabel")
        title.grid(row=0, column=0, sticky="w")
        ttk.Label(outer, text="Historical simulation only. No exchange API and no live orders.").grid(row=0, column=1, sticky="e")

        config = ttk.LabelFrame(outer, text="Backtest setup", padding=10)
        config.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(10, 8))
        for column in (1, 3, 5):
            config.columnconfigure(column, weight=1)

        self._path_row(config, 0, "15m CSV", self.csv_path, self._choose_csv, "Browse CSV")
        self._path_row(config, 1, "Output folder", self.output_path, self._choose_output, "Choose folder")

        self._entry(config, 2, 0, "Symbol", self.symbol)
        self._entry(config, 2, 2, "Start equity", self.start_equity)
        # Keep R:R visibly before quantity, matching the strategy's setup
        # order and making the selected bracket objective explicit first.
        self._entry(config, 2, 4, "Target R:R", self.target_rr)
        self._entry(config, 3, 0, "Backtest quantity", self.quantity)
        self._entry(config, 3, 2, "Round-trip cost fraction", self.cost_fraction)
        self._entry(config, 3, 4, "SL buffer (ATR)", self.sl_buffer)

        ttk.Checkbutton(config, text="Enable smart trend exit", variable=self.smart_exit).grid(row=4, column=0, columnspan=2, sticky="w", pady=(8, 0))
        ttk.Label(config, text="Smart-exit confirmations").grid(row=4, column=2, sticky="w", pady=(8, 0))
        ttk.Combobox(config, textvariable=self.smart_confirmations, values=("2", "3"), state="readonly", width=8).grid(row=4, column=3, sticky="w", pady=(8, 0))
        ttk.Checkbutton(config, text="Allow 15m gaps", variable=self.allow_gaps).grid(row=4, column=4, sticky="w", pady=(8, 0))

        actions = ttk.Frame(config)
        actions.grid(row=5, column=0, columnspan=6, sticky="ew", pady=(12, 0))
        self.run_button = ttk.Button(actions, text="Run native 15m backtest", command=self._start_backtest)
        self.run_button.pack(side=LEFT)
        ttk.Button(actions, text="Open report folder", command=self._open_reports).pack(side=LEFT, padx=(8, 0))
        ttk.Button(actions, text="Clear log", command=lambda: self.log.delete("1.0", END)).pack(side=LEFT, padx=(8, 0))
        ttk.Label(actions, textvariable=self.status, style="Status.TLabel").pack(side=RIGHT)

        body = ttk.PanedWindow(outer, orient="vertical")
        body.grid(row=2, column=0, columnspan=2, sticky="nsew")
        result_frame = ttk.LabelFrame(body, text="Results", padding=8)
        log_frame = ttk.LabelFrame(body, text="Run log", padding=8)
        body.add(result_frame, weight=4)
        body.add(log_frame, weight=1)

        ttk.Label(result_frame, textvariable=self.summary, style="Header.TLabel", justify="left").pack(fill=X, pady=(0, 8))
        columns = ("#", "Side", "Trigger", "Entry UTC", "Exit UTC", "Entry", "SL", "TP", "Exit", "Reason", "Net PnL", "R", "Equity")
        self.trades = ttk.Treeview(result_frame, columns=columns, show="headings", height=17)
        widths = {"#": 52, "Side": 70, "Trigger": 100, "Entry UTC": 170, "Exit UTC": 170, "Entry": 88, "SL": 88, "TP": 88, "Exit": 88, "Reason": 130, "Net PnL": 96, "R": 70, "Equity": 100}
        for column in columns:
            self.trades.heading(column, text=column)
            self.trades.column(column, width=widths[column], minwidth=50, anchor="center")
        scroll_y = ttk.Scrollbar(result_frame, orient="vertical", command=self.trades.yview)
        scroll_x = ttk.Scrollbar(result_frame, orient="horizontal", command=self.trades.xview)
        self.trades.configure(yscrollcommand=scroll_y.set, xscrollcommand=scroll_x.set)
        self.trades.pack(side=LEFT, fill=BOTH, expand=True)
        scroll_y.pack(side=RIGHT, fill=Y)
        scroll_x.pack(side="bottom", fill=X)

        self.log = __import__("tkinter").Text(log_frame, height=8, wrap="word", state="normal")
        log_scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=log_scroll.set)
        self.log.pack(side=LEFT, fill=BOTH, expand=True)
        log_scroll.pack(side=RIGHT, fill=Y)
        self._log("Application started. Use a continuous 15m GoCharting CSV export.")

    def _path_row(self, parent: ttk.LabelFrame, row: int, title: str, variable: StringVar, command: object, button_text: str) -> None:
        ttk.Label(parent, text=title).grid(row=row, column=0, sticky="w", pady=3)
        ttk.Entry(parent, textvariable=variable).grid(row=row, column=1, columnspan=4, sticky="ew", padx=(8, 8), pady=3)
        ttk.Button(parent, text=button_text, command=command).grid(row=row, column=5, sticky="ew", pady=3)

    def _entry(self, parent: ttk.LabelFrame, row: int, column: int, title: str, variable: StringVar) -> None:
        ttk.Label(parent, text=title).grid(row=row, column=column, sticky="w", pady=(8, 0))
        ttk.Entry(parent, textvariable=variable, width=18).grid(row=row, column=column + 1, sticky="ew", padx=(8, 18), pady=(8, 0))

    def _choose_csv(self) -> None:
        chosen = filedialog.askopenfilename(title="Select 15m CSV", filetypes=[("CSV files", "*.csv"), ("All files", "*.*")])
        if chosen:
            self.csv_path.set(chosen)
            if not self.output_path.get().strip():
                self.output_path.set(str(Path(chosen).with_suffix("").parent / "backtest_reports"))

    def _choose_output(self) -> None:
        chosen = filedialog.askdirectory(title="Select report folder")
        if chosen:
            self.output_path.set(chosen)

    def _settings(self) -> Settings:
        csv_text = self.csv_path.get().strip()
        if not csv_text:
            raise ValueError("Select a 15m CSV first")
        return Settings(
            symbol=self.symbol.get(),
            start_equity=float(self.start_equity.get()),
            quantity=float(self.quantity.get()),
            target_rr=float(self.target_rr.get()),
            round_trip_cost_fraction=float(self.cost_fraction.get()),
            sl_buffer_atr=float(self.sl_buffer.get()),
            smart_exit_enabled=self.smart_exit.get(),
            smart_exit_confirmations=int(self.smart_confirmations.get()),
            allow_gaps=self.allow_gaps.get(),
        ).normalized()

    def _start_backtest(self) -> None:
        if self.running:
            return
        try:
            settings = self._settings()
            csv_path = Path(self.csv_path.get().strip())
            output = Path(self.output_path.get().strip()) if self.output_path.get().strip() else csv_path.parent / "backtest_reports"
        except (ValueError, OSError) as exc:
            messagebox.showerror(APP_TITLE, str(exc))
            return
        self.running = True
        self.run_button.configure(state="disabled")
        self.status.set("Loading CSV…")
        self._log(f"Starting: {csv_path}")
        worker = threading.Thread(target=self._run_worker, args=(csv_path, output, settings), daemon=True)
        worker.start()

    def _run_worker(self, csv_path: Path, output: Path, settings: Settings) -> None:
        try:
            candles, audit = read_15m_csv(csv_path, allow_gaps=settings.allow_gaps)
            self.events.put(("log", f"Loaded {len(candles):,} valid candles; gaps: {audit.gaps:,}."))
            self.events.put(("status", "Running native 15m replay…"))

            def progress(current: int, total: int) -> None:
                if current % 500 == 0 or current == total:
                    self.events.put(("status", f"Running native 15m replay… {current:,}/{total:,}"))

            result = run_backtest(candles, settings, audit, progress)
            paths = write_reports(result, output)
            self.events.put(("done", (result, paths)))
        except Exception as exc:  # Show useful GUI feedback while preserving a full log.
            self.events.put(("error", (str(exc), traceback.format_exc())))

    def _drain_events(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "log":
                    self._log(str(payload))
                elif kind == "status":
                    self.status.set(str(payload))
                elif kind == "done":
                    result, paths = payload  # type: ignore[misc]
                    self._show_result(result, paths)  # type: ignore[arg-type]
                elif kind == "error":
                    message, details = payload  # type: ignore[misc]
                    self._finish_error(str(message), str(details))
        except queue.Empty:
            pass
        self.root.after(80, self._drain_events)

    def _show_result(self, result: BacktestResult, paths: dict[str, Path]) -> None:
        for row in self.trades.get_children():
            self.trades.delete(row)
        for trade in result.trades:
            self.trades.insert("", END, values=(
                trade.number,
                trade.side,
                trade.trigger,
                iso_utc(trade.entry_time),
                iso_utc(trade.exit_time),
                f"{trade.entry:.5f}",
                f"{trade.stop:.5f}",
                f"{trade.target:.5f}",
                f"{trade.exit:.5f}",
                trade.exit_reason,
                f"{trade.net_pnl:.4f}",
                f"{trade.r_multiple:.3f}",
                f"{trade.equity_after:.2f}",
            ))
        summary = result.summary
        self.summary.set(
            f"Closed trades: {summary['closed_trades']}   |   Net P&L: {summary['net_pnl']:.2f}   |   "
            f"Ending equity: {summary['ending_equity']:.2f}   |   Win rate: {summary['win_rate_percent']:.2f}%   |   "
            f"Profit factor: {summary['profit_factor']}   |   Max DD: {summary['max_drawdown_percent']:.2f}%"
        )
        self.last_output = paths["summary"].parent
        self.status.set("Backtest complete.")
        self._log("Reports written:")
        for name, path in paths.items():
            self._log(f"  {name}: {path}")
        if result.open_trade:
            self._log("A final position remains open because neither SL, TP nor smart exit closed it before CSV end.")
        self.running = False
        self.run_button.configure(state="normal")

    def _finish_error(self, message: str, details: str) -> None:
        self.running = False
        self.run_button.configure(state="normal")
        self.status.set("Backtest failed — see log.")
        self._log("ERROR: " + message)
        self._log(details)
        messagebox.showerror(APP_TITLE, message)

    def _open_reports(self) -> None:
        target = self.last_output or (Path(self.output_path.get()) if self.output_path.get().strip() else None)
        if target is None or not target.exists():
            messagebox.showinfo(APP_TITLE, "Run a backtest first, or select an existing output folder.")
            return
        if sys.platform.startswith("win"):
            os.startfile(str(target))  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.run(["open", str(target)], check=False)
        else:
            subprocess.run(["xdg-open", str(target)], check=False)

    def _log(self, message: str) -> None:
        self.log.insert(END, message.rstrip() + "\n")
        self.log.see(END)


def main() -> int:
    root = Tk()
    BacktestApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
