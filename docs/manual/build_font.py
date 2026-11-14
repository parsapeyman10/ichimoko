#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
قلم یکپارچهٔ دفترچه را می‌سازد: Vazirmatn (فارسی) + DejaVu (لاتین/نماد) + NotoEmoji (ایموجی).

چرا؟ اگر متن یک خانهٔ جدول از چند قلم بیاید، fpdf2 متن را تکه می‌کند و شمارهٔ
گلیف‌ها به هم می‌ریزد. پس همهٔ نویسه‌های لازم را در یک قلم جمع می‌کنیم تا هیچ
«قلم جایگزین»ی در کار نباشد.

اجرا:  python3 docs/manual/build_font.py
خروجی: docs/manual/AurumText.ttf
"""
from __future__ import annotations

import copy
from pathlib import Path

from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables import ttProgram
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable
from fontTools.ttLib.tables._g_l_y_f import Glyph  # noqa: F401  (typing aid)

HERE = Path(__file__).resolve().parent
BASE = HERE / "Vazirmatn-400.ttf"
DONORS = [
    ("dj", Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")),
    ("ne", HERE / "NotoEmoji-400.ttf"),
]
OUT = HERE / "AurumText.ttf"

EXTRA_CHARS = "".join(chr(c) for c in range(0x20, 0x7F)) + "«»—…؟٪٫٬≤≥×÷±≈·–‘’“”"


def wanted_chars() -> set[str]:
    chars = set(EXTRA_CHARS)
    for name in ("content_p1.py", "content_p2.py", "content_p3.py", "content.py", "build_manual.py"):
        p = HERE / name
        if p.exists():
            chars.update(p.read_text(encoding="utf-8"))
    return {c for c in chars if ord(c) >= 0x20}


def main() -> None:
    dst = TTFont(BASE)
    dst_cmap = dst.getBestCmap()
    glyf, hmtx = dst["glyf"], dst["hmtx"]
    order = list(dst.getGlyphOrder())

    donors = []
    for prefix, path in DONORS:
        f = TTFont(path)
        donors.append((prefix, f, f.getBestCmap()))

    wanted = sorted(wanted_chars())
    added: dict[str, str] = {}
    imported: dict[tuple[str, str], str] = {}

    def import_glyph(prefix: str, src: TTFont, name: str) -> str:
        key = (prefix, name)
        if key in imported:
            return imported[key]
        new_name = name if name not in glyf else f"{prefix}_{name}"
        while new_name in glyf:
            new_name += "_"
        imported[key] = new_name
        g = copy.deepcopy(src["glyf"][name])
        if g.isComposite():
            for comp in g.components:
                comp.glyphName = import_glyph(prefix, src, comp.glyphName)
        else:
            prog = ttProgram.Program()
            prog.fromBytecode(b"")
            g.program = prog  # بدون هیینت؛ برای چاپ لازم نیست
        glyf[new_name] = g
        hmtx[new_name] = src["hmtx"][name]
        order.append(new_name)
        return new_name

    for ch in wanted:
        cp = ord(ch)
        if cp in dst_cmap:
            continue
        for prefix, src, cmap in donors:
            if cp in cmap:
                added[ch] = import_glyph(prefix, src, cmap[cp])
                break

    dst.setGlyphOrder(order)
    dst["maxp"].numGlyphs = len(order)
    dst["hhea"].numberOfHMetrics = len(order)

    for table in dst["cmap"].tables:
        for ch, name in added.items():
            cp = ord(ch)
            if cp > 0xFFFF:
                continue
            table.cmap[cp] = name

    # ایموجی‌ها بیرون از محدودهٔ BMP هستند و cmap فرمت ۴ نمی‌تواند نگهشان دارد
    full = dict(dst.getBestCmap())
    full.update({ord(ch): name for ch, name in added.items()})
    sub = CmapSubtable.newSubtable(12)
    sub.platformID, sub.platEncID, sub.language = 3, 10, 0
    sub.cmap = full
    dst["cmap"].tables.append(sub)

    dst.save(OUT)
    missing = [c for c in wanted if ord(c) not in TTFont(OUT).getBestCmap()]
    print(f"قلم ساخته شد: {OUT}")
    print(f"گلیف‌های افزوده: {len(added)} · نویسه‌های پوشش‌نداده: {len(missing)}")
    if missing:
        print("  ", [f"{c!r}(U+{ord(c):04X})" for c in missing[:20]])


if __name__ == "__main__":
    main()
