#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
سازندهٔ دفترچهٔ PDF فارسی «Aurum Edge».

چرا این‌طور؟ محیط ساخت PDF فارسی ندارد (نه Pango، نه Chromium)، پس با fpdf2 و
HarfBuzz همین‌جا شکل‌دهی حروف فارسی و راست‌به‌چپ انجام می‌شود. قلم متن از بستهٔ
Vazirmatn (زیرمجموعهٔ عربی) گرفته شده و برای واژه‌های لاتین، DejaVu جایگزین است.

اجرا:  python3 docs/manual/build_manual.py
خروجی: docs/AurumEdge-Manual-FA.pdf
"""
from __future__ import annotations

import sys
from pathlib import Path

from fpdf import FPDF

HERE = Path(__file__).resolve().parent
FONT_REG = HERE / "AurumText.ttf"
FONT_DJ = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
FONT_EMOJI = HERE / "NotoEmoji-400.ttf"
OUT = HERE.parent / "AurumEdge-Manual-FA.pdf"

GOLD = (150, 108, 12)
TEAL = (13, 74, 92)
INK = (28, 30, 34)
MUTED = (98, 102, 110)
RULE = (206, 210, 216)
BOX = (246, 243, 234)
RED = (150, 40, 40)

PAGE_W, PAGE_H = 210, 297
M = 17  # حاشیه


class Manual(FPDF):
    def __init__(self) -> None:
        super().__init__(format="A4", unit="mm")
        self.set_auto_page_break(False)
        self.set_margins(M, M, M)
        # یک قلم یکپارچه: فارسی + لاتین + ایموجی. قلم جایگزین لازم نیست و اگر
        # لازم شود، fpdf2 متن را تکه می‌کند و چیدمان راست‌به‌چپ به هم می‌ریزد.
        self.add_font("vaz", "", str(FONT_REG))
        self.set_text_shaping(True)
        self.toc: list[tuple[int, str, int]] = []
        self._toc_pages: dict[str, int] = {}
        self.current_section = ""

    # ── ابزارهای پایه ─────────────────────────────────────────────
    def content_w(self) -> float:
        return PAGE_W - 2 * M

    def footer(self, color=MUTED, rule=RULE) -> None:
        """پانویس: شمارهٔ صفحه چپ، عنوان راست. بعد از کشیدن، y به بالای صفحه برمی‌گردد."""
        fa = "۰۱۲۳۴۵۶۷۸۹"
        pnum = "".join(fa[int(c)] for c in str(self.page_no()))
        half = self.content_w() / 2
        self.set_font("vaz", "", 8)
        self.set_text_color(*color)
        self.set_draw_color(*rule)
        self.line(M, PAGE_H - 14, PAGE_W - M, PAGE_H - 14)
        self.set_xy(M, PAGE_H - 12)
        self.cell(half, 6, f"صفحهٔ {pnum}", align="L")
        self.set_xy(M + half, PAGE_H - 12)
        self.cell(half, 6, "دفترچهٔ راهنمای Aurum Edge", align="R")

    def new_page(self, section: str = "") -> None:
        self.add_page()
        if section:
            self.current_section = section
        self.footer()
        self.set_y(M)

    # ── بلوک‌ها ───────────────────────────────────────────────────
    def h1(self, text: str) -> None:
        """سر فصل. اگر جای کافی روی صفحه مانده باشد، فصل همان‌جا ادامه می‌یابد."""
        room_left = self.get_y() < 175 and self.page_no() > 1 and self.current_section != "فهرست"
        if room_left:
            self.ln(9)
            self.current_section = text
            page = self.page_no()
        else:
            page = self.page_no() + 1
            self.new_page(text)
        self._toc_pages.setdefault(text, page)
        self.toc.append((1, text, self._toc_pages[text]))
        self.set_fill_color(*TEAL)
        self.rect(M, self.get_y(), self.content_w(), 1.6, style="F")
        self.ln(6)
        self.set_font("vaz", "", 20)
        self.set_text_color(*TEAL)
        self.multi_cell(self.content_w(), 11, text, align="R", new_x="LMARGIN", new_y="NEXT")
        self.ln(4)

    def h2(self, text: str) -> None:
        self._need(16)
        self._toc_pages.setdefault(text, self.page_no())
        self.toc.append((2, text, self._toc_pages[text]))
        self.ln(3)
        self.set_font("vaz", "", 14)
        self.set_text_color(*GOLD)
        self.multi_cell(self.content_w(), 8, text, align="R", new_x="LMARGIN", new_y="NEXT")
        self.set_draw_color(*RULE)
        self.line(M, self.get_y() + 1, PAGE_W - M, self.get_y() + 1)
        self.ln(4)

    def h3(self, text: str) -> None:
        self._need(12)
        self.ln(1.5)
        self.set_font("vaz", "", 11.8)
        self.set_text_color(*INK)
        self.multi_cell(self.content_w(), 7, text, align="R", new_x="LMARGIN", new_y="NEXT")
        self.ln(1.5)

    def p(self, text: str, size: float = 10.6, color=INK, lh: float = 6.4) -> None:
        self.set_font("vaz", "", size)
        self.set_text_color(*color)
        self._need(lh * 2)
        self.multi_cell(self.content_w(), lh, text, align="R", new_x="LMARGIN", new_y="NEXT")
        self.ln(2)

    def bullets(self, items: list[str], size: float = 10.4, lh: float = 6.2) -> None:
        for it in items:
            self.set_font("vaz", "", size)
            self.set_text_color(*INK)
            self._need(lh * 2)
            self.set_x(M + 4)
            self.multi_cell(self.content_w() - 6, lh, "•  " + it, align="R", new_x="LMARGIN", new_y="NEXT")
            self.ln(0.8)
        self.ln(1.5)

    def numlist(self, items: list[str], size: float = 10.4, lh: float = 6.2) -> None:
        for i, it in enumerate(items, 1):
            self.set_font("vaz", "", size)
            self.set_text_color(*INK)
            self._need(lh * 2)
            self.set_x(M + 4)
            fa = "۰۱۲۳۴۵۶۷۸۹"
            n = "".join(fa[int(c)] for c in str(i))
            self.multi_cell(self.content_w() - 6, lh, f"{n}.  " + it, align="R", new_x="LMARGIN", new_y="NEXT")
            self.ln(0.8)
        self.ln(1.5)

    def callout(self, title: str, body: str, color=TEAL) -> None:
        self._need(24)
        self.set_font("vaz", "", 9.6)
        lines = self.multi_cell(
            self.content_w() - 10, 5.6, f"{title}\n{body}",
            align="R", dry_run=True, output="LINES",
        )
        h = len(lines) * 5.6 + 6
        y = self.get_y()
        self.set_fill_color(*BOX)
        self.rect(M, y, self.content_w(), h, style="F")
        self.set_fill_color(*color)
        self.rect(PAGE_W - M - 1.4, y, 1.4, h, style="F")
        self.set_xy(M + 4, y + 2.6)
        self.set_font("vaz", "", 9.6)
        self.set_text_color(*color)
        self.multi_cell(self.content_w() - 10, 5.6, title, align="R", new_x="LMARGIN", new_y="NEXT")
        self.set_x(M + 4)
        self.set_text_color(*INK)
        self.multi_cell(self.content_w() - 10, 5.6, body, align="R", new_x="LMARGIN", new_y="NEXT")
        self.set_y(y + h + 3)

    def table(self, headers: list[str], rows: list[list[str]], widths: list[float] | None = None,
              size: float = 8.8, header_color=TEAL) -> None:
        n = len(headers)
        if widths is None:
            widths = [1.0 / n] * n
        total = self.content_w()
        cols = [total * w for w in widths]
        self.set_font("vaz", "", size)

        def row_height(cells: list[str], widths_mm: list[float]) -> float:
            tallest = 0.0
            for cell, w in zip(cells, widths_mm):
                self.set_font("vaz", "", size)   # اندازه‌گیری و رسم باید با همان قلم باشند
                lines = self.multi_cell(w - 3, 5.0, str(cell), align="R", dry_run=True, output="LINES")
                tallest = max(tallest, len(lines) * 5.0)
            return tallest + 2.4

        # سرستون
        hh = row_height(headers, cols)
        self._need(hh + 12)
        y = self.get_y()
        self.set_fill_color(*header_color)
        self.rect(M, y, total, hh, style="F")
        x = PAGE_W - M
        for cell, w in zip(headers, cols):
            x -= w
            self.set_xy(x + 1.5, y + 1.2)
            self.set_text_color(255, 255, 255)
            self.set_font("vaz", "", size)
            self.multi_cell(w - 3, 5.0, str(cell), align="R")
        self.set_y(y + hh)

        # ردیف‌ها
        for idx, cells in enumerate(rows):
            rh = row_height(cells, cols)
            if self.get_y() + rh > PAGE_H - 18:
                self.new_page()
                y = self.get_y()
                self.set_fill_color(*header_color)
                self.rect(M, y, total, hh, style="F")
                x = PAGE_W - M
                for cell, w in zip(headers, cols):
                    x -= w
                    self.set_xy(x + 1.5, y + 1.2)
                    self.set_text_color(255, 255, 255)
                    self.multi_cell(w - 3, 5.0, str(cell), align="R")
                self.set_y(y + hh)
            y = self.get_y()
            if idx % 2 == 1:
                self.set_fill_color(247, 248, 250)
                self.rect(M, y, total, rh, style="F")
            self.set_draw_color(*RULE)
            self.line(M, y, PAGE_W - M, y)
            x = PAGE_W - M
            for cell, w in zip(cells, cols):
                x -= w
                self.set_xy(x + 1.5, y + 1.2)
                self.set_text_color(*INK)
                self.set_font("vaz", "", size)
                self.multi_cell(w - 3, 5.0, str(cell), align="R")
                self.set_draw_color(*RULE)
                self.line(x, y, x, y + rh)
            self.set_y(y + rh)
        self.set_draw_color(*RULE)
        self.line(M, self.get_y(), PAGE_W - M, self.get_y())
        self.ln(4)

    def _need(self, h: float) -> None:
        if self.get_y() + h > PAGE_H - 20:
            self.new_page()

    # ── جلد و فهرست ───────────────────────────────────────────────
    def cover(self, version: str, code: str, date_fa: str) -> None:
        self.add_page()
        self.footer()
        self.set_y(M)
        self.set_fill_color(*TEAL)
        self.rect(0, 0, PAGE_W, 92, style="F")
        self.set_xy(M, 26)
        self.set_font("vaz", "", 30)
        self.set_text_color(255, 255, 255)
        self.multi_cell(self.content_w(), 15, "Aurum Edge", align="R", new_x="LMARGIN", new_y="NEXT")
        self.set_xy(M, 48)
        self.set_font("vaz", "", 15)
        self.multi_cell(self.content_w(), 9, "دفترچهٔ کامل راهنما و توضیح کارکرد", align="R",
                        new_x="LMARGIN", new_y="NEXT")
        self.set_xy(M, 62)
        self.set_font("vaz", "", 10.5)
        self.multi_cell(self.content_w(), 6.5,
                        "هر صفحه، هر دکمه، هر پیام — با عدد و دلیل", align="R", new_x="LMARGIN", new_y="NEXT")

        self.set_y(108)
        self.set_text_color(*INK)
        self.set_font("vaz", "", 11)
        self.multi_cell(self.content_w(), 7.5,
                        "این دفترچه برای همان برنامه‌ای نوشته شده که روی گوشی خودت نصب است. "
                        "هرچه در برنامه می‌بینی — از وارد کردن فایل CSV تا پیام‌های خطا — این‌جا "
                        "توضیح داده شده است: چه کار می‌کند، چرا وجود دارد و اگر پیامی دیدی یعنی چه.",
                        align="R", new_x="LMARGIN", new_y="NEXT")
        self.ln(6)
        self.table(
            ["قلم", "مقدار"],
            [["نسخهٔ برنامه", f"{version} (کد {code})"],
             ["تاریخ سند", date_fa],
             ["مخاطب", "کاربر نهایی برنامه روی گوشی اندروید"],
             ["زبان", "فارسی · اعداد فارسی"]],
            widths=[0.35, 0.65],
        )
        self.set_y(PAGE_H - 46)
        self.set_font("vaz", "", 9)
        self.set_text_color(*MUTED)
        self.multi_cell(self.content_w(), 5.6,
                        "هشدار: این برنامه ابزار تحلیل و بک‌تست است، نه مشاور سرمایه‌گذاری. "
                        "هیچ سودی تضمین نمی‌شود و تصمیم معامله با خودت است.",
                        align="R", new_x="LMARGIN", new_y="NEXT")

    TOC_PER_PAGE = 30

    def toc_page(self) -> None:
        """صفحه‌های فهرست را می‌سازد؛ شمارهٔ صفحه‌ها از گذر قبلی می‌آید."""
        slots = max(1, getattr(self, "toc_slots", 1))
        entries = list(getattr(self, "toc_entries", []))
        fa = "۰۱۲۳۴۵۶۷۸۹"

        def fmt(n: int) -> str:
            return "".join(fa[int(c)] for c in str(n))

        for slot in range(slots):
            self.new_page("فهرست")
            if slot == 0:
                self.set_font("vaz", "", 18)
                self.set_text_color(*TEAL)
                self.cell(self.content_w(), 12, "فهرست مطالب", align="R",
                          new_x="LMARGIN", new_y="NEXT")
                self.ln(2)
            for level, text, pno in entries[slot * self.TOC_PER_PAGE:(slot + 1) * self.TOC_PER_PAGE]:
                if level == 1:
                    self.ln(2.0)
                    self.set_font("vaz", "", 10.4)
                    self.set_text_color(*TEAL)
                else:
                    self.set_font("vaz", "", 9.2)
                    self.set_text_color(*MUTED)
                self.set_x(M + (0 if level == 1 else 7))
                w = self.content_w() - (0 if level == 1 else 7)
                self.cell(w - 13, 6.2, text, align="R")
                self.set_text_color(*MUTED)
                self.set_font("vaz", "", 9.0)
                self.cell(13, 6.2, fmt(pno), align="L", new_x="LMARGIN", new_y="NEXT")


    def back_cover(self, version: str, code: str, date_fa: str) -> None:
        """پشت جلد: بستن دفترچه با یک صفحهٔ آرام و اطلاعات نسخه."""
        self.new_page("پشت جلد")
        self.set_y(26)
        self.set_font("vaz", "", 26)
        self.set_text_color(*TEAL)
        self.multi_cell(self.content_w(), 13, "همین یک صفحه را به خاطر بسپار", align="R",
                        new_x="LMARGIN", new_y="NEXT")
        self.ln(2)
        self.set_font("vaz", "", 10.6)
        self.set_text_color(*INK)
        self.multi_cell(self.content_w(), 6.6,
                        "هر عددی در این دفترچه از اجرای واقعی روی دادهٔ واقعی آمده است، نه از تخمین. اگر "
                        "روزی عددی با واقعیت نخواند، همان عدد را بازبینی می‌کنیم — با شمارش ردیف‌ها و "
                        "بازمحاسبهٔ مستقل. و هر تغییری که سود را عوض کند، پیش از انتشار روی داده "
                        "گذشته اندازه‌گیری می‌شود.",
                        align="R", new_x="LMARGIN", new_y="NEXT")
        self.ln(6)
        self.table(
            ["قلم", "مقدار"],
            [["نسخهٔ سند", f"{version} · کد {code}"],
             ["تاریخ", date_fa],
             ["تعداد صفحه", "۲۴ صفحهٔ A4"],
             ["قلم متن", "Vazirmatn (فارسی) · DejaVu (لاتین) · Noto Emoji"]],
            widths=[0.32, 0.68],
        )
        self.ln(2)
        self.set_font("vaz", "", 9.4)
        self.set_text_color(*MUTED)
        self.multi_cell(self.content_w(), 6.0,
                        "هشدار: این برنامه ابزار تحلیل و بک‌تست است، نه مشاور سرمایه‌گذاری. هیچ سودی "
                        "تضمین نمی‌شود و تصمیم معامله با خودت است.",
                        align="R", new_x="LMARGIN", new_y="NEXT")
        self.set_fill_color(*TEAL)
        self.rect(0, PAGE_H - 40, PAGE_W, 40, style="F")
        self.set_xy(M, PAGE_H - 30)
        self.set_font("vaz", "", 12)
        self.set_text_color(255, 255, 255)
        self.multi_cell(self.content_w(), 8, "Aurum Edge · دفترچهٔ راهنمای فارسی", align="R",
                        new_x="LMARGIN", new_y="NEXT")
        self.footer(color=(226, 234, 238), rule=(70, 120, 136))


def build(content_module, out_path: Path, toc_pages: dict[str, int] | None = None,
          toc_slots: int = 1, toc_entries: list | None = None) -> tuple[dict[str, int], list]:
    pdf = Manual()
    pdf._toc_pages = dict(toc_pages or {})
    pdf.toc_slots = toc_slots
    pdf.toc_entries = list(toc_entries or [])
    content_module.render(pdf)
    pdf.output(str(out_path))
    return dict(pdf._toc_pages), list(pdf.toc)


def main() -> None:
    sys.path.insert(0, str(HERE))
    import build_font  # noqa: WPS433
    import content  # noqa: WPS433

    # قلم یکپارچه همیشه از روی همین متن‌ها ساخته می‌شود تا نویسه‌ای بی‌قلم نمانَد.
    build_font.main()

    # گذر ۰: فقط تعداد سرفصل‌ها را می‌شمارد تا بدانیم فهرست چند صفحه می‌خواهد.
    _, entries = build(content, Path("/tmp/_manual_pass0.pdf"), toc_slots=1)
    slots = max(1, -(-len(entries) // Manual.TOC_PER_PAGE))
    # گذر ۱: شمارهٔ صفحهٔ هر سر را با همان تعداد صفحهٔ فهرست جمع می‌کند.
    pages, _ = build(content, Path("/tmp/_manual_pass1.pdf"), toc_slots=slots)
    # گذر ۲: نسخهٔ نهایی، با فهرست پُر‌شده.
    build(content, OUT, pages, toc_slots=slots,
          toc_entries=[(lvl, txt, pages.get(txt, pno)) for lvl, txt, pno in entries])
    print(f"ساخته شد: {OUT}  ·  {len(entries)} سرفصل  ·  {slots} صفحه فهرست")


if __name__ == "__main__":
    main()
