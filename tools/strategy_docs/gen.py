#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
سند جداگانه برای هر استراتژی می‌سازد: مشخصات + شرط‌ها + کد + پیام‌ها + راه بررسی.

همه‌چیز از خود کد خوانده می‌شود (نه از حافظه):
  - فهرست استراتژی‌ها و مشخصاتشان: enum BitcoinStrategyModel در IchimokuStrategyEngine.kt
  - شرط‌ها: رشته‌های StrategyConditionCheck / Rule در همان فایل و موتورهای اختصاصی
  - کد: بخش‌های موتور که با کامنت «──» علامت خورده‌اند + فایل‌های اختصاصی

اجرا:  python3 tools/strategy_docs/gen.py
خروجی: docs/strategies/*.md  و  docs/strategies/code/*.kt
"""
from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "android/app/src/main/java/com/aurum/edge"
ENGINE = SRC / "core/IchimokuStrategyEngine.kt"
OUT = REPO / "docs/strategies"
CODE_OUT = OUT / "code"

FA = "۰۱۲۳۴۵۶۷۸۹"


def fa(n: int | str) -> str:
    return "".join(FA[int(c)] if c.isdigit() else c for c in str(n))


# ────────────────────────────────────────────────────────────── enum
ENUM_RE = re.compile(r"^    ([A-Z][A-Z0-9_]*)\($")

FIELDS = {
    "id": r'id = "([^"]*)"',
    "title": r'titleFa = "([^"]*)"',
    "badge": r'badgeFa = "([^"]*)"',
    "author": r'authorRefFa = "([^"]*)"',
    "summary": r'summaryFa = "([^"]*)"',
}


def parse_enum() -> list[dict]:
    lines = ENGINE.read_text(encoding="utf-8").split("\n")
    start = next(i for i, l in enumerate(lines) if l.startswith("enum class BitcoinStrategyModel"))
    end = next((i for i in range(start, len(lines)) if lines[i].strip() == ";"), None)
    if end is None:
        end = next(i for i in range(start, len(lines)) if lines[i].startswith("}"))
    entries, cur = [], None
    for line in lines[start:end]:
        m = ENUM_RE.match(line)
        if m:
            if cur:
                entries.append(cur)
            cur = {"name": m.group(1)}
            continue
        if cur is None:
            continue
        for key, rx in FIELDS.items():
            hit = re.search(rx, line)
            if hit:
                cur[key] = hit.group(1)
        for flag in ("hasPatel", "hasTuttle", "hasHosoda", "hasPeloille"):
            hit = re.search(flag + r" = (true|false)", line)
            if hit:
                cur[flag] = hit.group(1) == "true"
        hit = re.search(r"baseOptimalRr = ([0-9.]+)", line)
        if hit:
            cur["rr"] = float(hit.group(1))
        if "requiresExplicitOptIn = true" in line:
            cur["optIn"] = True
        if "neuralLearningEnabled = false" in line:
            cur["neural"] = False
    if cur:
        entries.append(cur)
    for e in entries:
        e.setdefault("hasPatel", True)
        e.setdefault("hasTuttle", True)
        e.setdefault("hasHosoda", True)
        e.setdefault("hasPeloille", True)
        e.setdefault("rr", 2.15)
        e.setdefault("optIn", False)
        e.setdefault("neural", True)
        e["books"] = sum(e[k] for k in ("hasPatel", "hasTuttle", "hasHosoda", "hasPeloille"))
    return entries


# ────────────────────────────────────────────────────── کد: بخش‌ها
MARK = re.compile(r"^\s*// ── (.+?)\s*─*\s*$")


def engine_segments() -> list[dict]:
    """بخش‌های evaluateBitcoinStrategy را از کامنت‌های «// ──» جدا می‌کند."""
    lines = ENGINE.read_text(encoding="utf-8").split("\n")
    fn_start = next(i for i, l in enumerate(lines) if "fun evaluateBitcoinStrategy(" in l)
    marks = [(i, MARK.match(l).group(1)) for i, l in enumerate(lines)
             if i > fn_start and MARK.match(l)]
    segs = []
    for idx, (i, title) in enumerate(marks):
        end = marks[idx + 1][0] if idx + 1 < len(marks) else len(lines)
        segs.append({
            "title": title,
            "from": i + 1,
            "to": end,
            "code": "\n".join(lines[i:end]).rstrip(),
        })
    return segs


def seg_by(segs: list[dict], needle: str) -> dict | None:
    for s in segs:
        if needle in s["title"]:
            return s
    return None


# ───────────────────────────────────────────── شرط‌های موتور (لیبل‌ها)
CHECK_RE = re.compile(r'StrategyConditionCheck\(\s*(\d+),\s*"([^"]{4,260})"', re.S)


def _block(text: str, anchor: str) -> str:
    """از خطِ anchor تا بسته‌شدن listOf را برمی‌گرداند."""
    i = text.index(anchor)
    j = text.index("\n        )\n", i)
    return text[i:j]


def condition_sets() -> dict[str, list[tuple[int, str]]]:
    text = ENGINE.read_text(encoding="utf-8")
    sets = {}
    for key, anchor in (("main", "val baseSixteenConditions = listOf("),):
        rows = CHECK_RE.findall(_block(text, anchor))
        uniq = {}
        for idx, label in rows:
            uniq[int(idx)] = label
        sets[key] = [(k, uniq[k]) for k in sorted(uniq)]
    return sets


def rules_from(path: Path) -> list[str]:
    """عنوان شرط‌های موتورهای اختصاصی (Rule(...)) را برمی‌گرداند."""
    text = path.read_text(encoding="utf-8")
    return re.findall(r'Rule\(\s*\d+,\s*\n?\s*"([^"]{4,120})"', text)


def constants_from(path: Path) -> list[tuple[str, str]]:
    text = path.read_text(encoding="utf-8")
    return re.findall(r"const val ([A-Z0-9_]+)\s*(?::\s*[A-Za-z]+)?\s*=\s*([^\n/]+)", text)


# ─────────────────────────────────────────────── نگاشت استراتژی → کد
CHECK_KEYS = {
    "۱": "Check 1:", "۲": "Check 2:", "۳": "Check 3:", "۴": "Check 4:", "۵": "Check 5:",
    "۶": "Check 6:", "۷": "Check 7:", "۸": "Check 8:", "۹": "Check 9:", "۱۰": "Check 10:",
    "۱۱": "Check 11:", "۱۲": "Check 12:", "۱۳": "Check 13:", "۱۴": "Check 14:",
    "۱۵": "Check 15:", "۱۶": "Check 16:", "۱۷": "Check 17",
}
SCHOOL_FA = {"patel": "پاتل", "tuttle": "تاتل", "hosoda": "هوسودا", "peloille": "پلویل"}
SCHOOL_CHECKS = {
    "patel": ["۳", "۸"],
    "tuttle": ["۷", "۹"],
    "hosoda": ["۵", "۱۰"],
    "peloille": ["۵", "۹"],
}
SHARED_CHECKS = ["۱", "۲", "۴", "۶", "۱۱", "۱۲", "۱۳", "۱۴", "۱۵", "۱۶", "۱۷"]
EXTRA_SEGMENTS = {
    "sniper": "Compute Sniper Entry Zone",
    "rr": "محاسبه «عدد مناسب R:R»",
    "plan": "موتور واحد ایمن‌سازی پلن",
}

# مدل‌های ویژه‌ای باقی نمانده‌اند: با حذف ترکیب‌ها، متود جامع ۹ لایه (پیمان)، BTC ۵۲ کندلی،
# پارسا و هیدن کراس، تنها پنج مدل ایچیموکو در enum مانده است که همه‌شان از یک خط لولهٔ مشترک
# می‌گذرند. ساختار این دیکشنری برای آینده نگه داشته شده است.
SPECIAL: dict[str, dict] = {}


def slugify(e: dict) -> str:
    special = SPECIAL.get(e["id"])
    if special:
        return special["slug"]
    return e["id"].lower().replace("_", "-")


def gate_table(e: dict) -> list[tuple[str, str, str]]:
    """همان چهار خطی که کد از پرچم مکتب‌ها می‌سازد (خط ۳۵۱۲ به بعد)."""
    hp, ht, hh, hpeloille = e["hasPatel"], e["hasTuttle"], e["hasHosoda"], e["hasPeloille"]
    n = e["books"]
    rows = [
        ("ماشهٔ پولبک", "hasPatel یا hasHosoda یا hasPeloille یا بی‌تاتل",
         hp or hh or hpeloille or not ht),
        ("ماشهٔ شکست", "hasTuttle یا hasHosoda یا hasPeloille یا بی‌پاتل",
         ht or hh or hpeloille or not hp),
        ("الزام همسویی تایم بالا", "hasPeloille یا ترکیب ۲گانه و بیشتر", hpeloille or n >= 2),
        ("الزام فضای آزاد چیکو", "hasTuttle یا hasPeloille یا ترکیب ۲گانه و بیشتر", ht or hpeloille or n >= 2),
        ("الزام شیب ابر آینده", "hasPatel یا hasTuttle یا ترکیب ۲گانه و بیشتر", hp or ht or n >= 2),
    ]
    return [(name, cond, "روشن" if val else "خاموش") for name, cond, val in rows]


INTERP = {
    "${config.macroInterval.label}/${config.strategyInterval.label}/${config.triggerInterval.label}":
        "تایم بالاتر/استراتژی/ماشه",
}


def strip_no(label: str) -> str:
    clean = re.sub(r"^[۰-۹0-9]+\.\s*", "", label)
    for k, v in INTERP.items():
        clean = clean.replace(k, v)
    return clean


def code_block(lang: str, body: str, fold_over: int = 85, summary: str = "") -> str:
    block = f"```{lang}\n{body}\n```\n"
    if len(body.split("\n")) > fold_over:
        return f"<details><summary>{summary or 'کد کامل'} ({fa(len(body.split(chr(10))))} خط)</summary>\n\n{block}\n</details>\n\n"
    return block



TESTS_ROOT = REPO / "android/app/src/test/java/com/aurum/edge"
DATA_HINT = """# دادهٔ واقعی را یک‌بار باز کن (فایل زیپ در مخزن است)
unzip -o data/xau/xauusd-m15-both-2025-01-01-2026-11-09.zip \
      'xauusd-m15-bid-*' -d /tmp/csv
"""


def anchor_block(lines: list[str], prefix: str, stop: str = "") -> tuple[int, int, str] | None:
    start = next((i for i, l in enumerate(lines) if l.startswith(prefix)), None)
    if start is None:
        return None
    if stop:
        end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith(stop)), None)
        if end:
            while end > start and lines[end - 1].strip() == "":
                end -= 1
            return start + 1, end, "\n".join(lines[start:end])
    j = start + 1
    while j < len(lines):
        if lines[j].startswith("        val ") and lines[j - 1].strip() == "" and j > start + 1:
            break
        if lines[j].startswith("        // ── "):
            break
        j += 1
    while j > start and lines[j - 1].strip() == "":
        j -= 1
    return start + 1, j, "\n".join(lines[start:j])


def tests_for(model_id: str) -> list[str]:
    hits = sorted(p.relative_to(REPO).as_posix()
                  for p in TESTS_ROOT.rglob("*.kt")
                  if model_id in p.read_text(encoding="utf-8"))
    return hits


def verify_block(e: dict) -> str:
    mid = e["id"]
    lab = {
        "DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER": "tools/strategy_lab/run.sh 8000 11 pey",
        "BTC_52_CANDLE_REGIME": "tools/strategy_lab/run.sh 8000 11 btc\ntools/strategy_lab/run.sh 8000 11 diag",
        "HIDDEN_CROSS": "tools/strategy_lab/run.sh 8000 11 hx",
        "PARSA_52_LINE_TURN": "tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD PARSA_52_LINE_TURN",
    }.get(mid, "tools/strategy_lab/run.sh 8000 11 mon /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD " + mid
          + "\ntools/strategy_lab/run.sh 8000 11 rng /tmp/csv/xauusd-m15-bid-2025-01-01-2026-11-09.csv XAU/USD " + mid)
    tests = tests_for(mid)
    code = (DATA_HINT + "\n# همین روش، جدا از بقیه (بک‌تست ماه‌به‌ماه)\n" + lab +
            "\n\n# همهٔ جاهایی که در کد صدا زده می‌شود\ngrep -rn \"" + mid + "\" android/app/src/main/java/com/aurum/edge | head -20")
    out = code_block("bash", code)
    if tests:
        out += "\nتست‌هایی که همین روش را مستقیم می‌سنجند:\n\n"
        for t in tests:
            out += f"- `{t}`\n"
    else:
        out += ("\nاین روش تست اختصاصی ندارد؛ با تست‌های مشترک موتور سنجیده می‌شود: "
                "`BaseStrategyDeepDiveTest`، `BaseStrategyBookGateTest`، `BacktestMonthlyTest`.\n")
    return out



def tidy(path: Path) -> None:
    """پیش از هر جدول/بلوک کد یک خط خالی می‌گذارد تا مارک‌داون همه‌جا درست رندر شود."""
    lines = path.read_text(encoding="utf-8").split("\n")
    out, fence = [], False
    for line in lines:
        inside = fence
        if line.startswith("```"):
            fence = not fence
        if not inside and out and out[-1].strip():
            if line.startswith("|") and not out[-1].startswith("|"):
                out.append("")
            elif line.startswith("```") and not out[-1].startswith("<"):
                out.append("")
        out.append(line)
    path.write_text("\n".join(out), encoding="utf-8")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    CODE_OUT.mkdir(parents=True, exist_ok=True)
    segs = engine_segments()
    sets = condition_sets()
    entries = parse_enum()

    used_files: list[str] = []
    index_rows = []

    for pos, e in enumerate(entries, start=1):
        special = SPECIAL.get(e["id"], {})
        slug = slugify(e)
        fname = f"{pos:02d}-{slug}.md"
        parts: list[str] = []

        parts.append(f"# {fa(pos)} · {e['title']}\n")
        parts.append(f"`{e['id']}` · {e['badge']}\n")

        parts.append("\n## مشخصات\n")
        parts.append("| قلم | مقدار |\n|---|---|\n")
        parts.append(f"| شناسهٔ کد | `{e['id']}` |\n")
        parts.append(f"| نشان | {e['badge']} |\n")
        books = []
        for key, label in (("hasPatel", "پاتل"), ("hasTuttle", "تاتل"),
                           ("hasHosoda", "هوسودا"), ("hasPeloille", "پلویل")):
            if e[key]:
                books.append(label)
        independent = e["id"] in ("DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER", "BTC_52_CANDLE_REGIME",
                                  "PARSA_52_LINE_TURN", "HIDDEN_CROSS")
        if independent:
            parts.append("| مکتب‌ها | — (روش مستقل؛ شرط‌های خودش را دارد) |\n")
        else:
            parts.append(f"| مکتب‌ها | {' + '.join(books) if books else '—'} |\n")
        parts.append(f"| تعداد مکتب | {fa(e['books']) + ' از ۴' if e['books'] else '— (روش مستقل)'} |\n")
        parts.append(f"| R:R پایه | {fa(e['rr'])} (در بازهٔ قفل‌شدهٔ ۱٫۵ تا ۳٫۰) |\n")
        parts.append(f"| منبع/مرجع | {e.get('author', '—')} |\n")
        parts.append(f"| انتخاب صریح کاربر | {'بله — پیش‌فرض خاموش' if e['optIn'] else 'نه — خودکار در رادار'} |\n")
        parts.append(f"| یادگیری عصبی | {'فعال' if e['neural'] else 'مستثنا (بدون آموزش/گیت)'} |\n")

        parts.append("\n## خلاصه\n")
        parts.append(e.get("summary", "—") + "\n")
        if special.get("notes"):
            parts.append("\n" + special["notes"] + "\n")
        if special.get("extra_md"):
            parts.append("\n" + special["extra_md"] + "\n")

        if not special:
            parts.append("\n## دروازه‌هایی که این استراتژی روشن می‌کند\n")
            parts.append("این پنج قاعده، دقیقاً همان چیزی است که کد از پرچم مکتب‌ها می‌سازد "
                         "(`IchimokuStrategyEngine.kt`، بلوک `effectiveConfig`):\n\n")
            parts.append("| دروازه | قاعدهٔ کد | وضعیت |\n|---|---|---|\n")
            for name, cond, val in gate_table(e):
                parts.append(f"| {name} | `{cond}` | {val} |\n")

            parts.append("\n## شرط‌های ارزیابی\n")
            want = set(SHARED_CHECKS)
            for school in ("patel", "tuttle", "hosoda", "peloille"):
                if e[f"has{school.capitalize()}"]:
                    want.update(SCHOOL_CHECKS[school])
            parts.append("| # | شرط | مربوط به |\n|---|---|---|\n")
            for idx, label in sets["main"]:
                key = fa(idx)
                if key not in want:
                    continue
                owner = "مشترک"
                for school, checks in SCHOOL_CHECKS.items():
                    if key in checks and e[f"has{school.capitalize()}"]:
                        owner = ("مکتب " + SCHOOL_FA[school]) if owner == "مشترک" \
                            else owner + " + " + SCHOOL_FA[school]
                parts.append(f"| {fa(idx)} | {strip_no(label)} | {owner} |\n")
            parts.append("\nشرط‌های پایه (۱، ۲، ۴، ۶) و لایهٔ کیفیت اجرا (۱۱ تا ۱۷) برای همهٔ ۵ مدل "
                         "ایچیموکو یکی است؛ فقط شرط‌های مکتبی (۳، ۵، ۷، ۸، ۹، ۱۰) با پرچم مکتب‌ها "
                         "روشن/خاموش می‌شوند. توضیح کامل در سند «۰۰ — موتور مشترک».\n")

        # ── شرط‌ها و کد
        anchor_cache: list[tuple[int, int, str]] = []
        if special.get("cond_set") == "btc":
            parts.append("\n## شرط‌ها\n")
            parts.append("این روش شرط‌هایش را جدا حساب می‌کند، نه با فهرست ۱۷تایی:\n\n")
            parts.append("| # | شرط |\n|---|---|\n")
            for idx, label in sets["btc"]:
                parts.append(f"| {fa(idx)} | {strip_no(label)} |\n")
            parts.append("\n")

        if special.get("engine_rules"):
            path = SRC / special["engine_rules"]
            rules = rules_from(path)
            consts = constants_from(path)
            parts.append("\n## شرط‌ها\n")
            parts.append(f"موتور اختصاصی: `{special['engine_rules']}`\n\n")
            parts.append("| # | شرط |\n|---|---|\n")
            for i, label in enumerate(rules, start=1):
                parts.append(f"| {fa(i)} | {strip_no(label)} |\n")
            if consts:
                parts.append("\n| ثابت | مقدار |\n|---|---|\n")
                for name, value in consts:
                    parts.append(f"| `{name}` | `{value.strip()}` |\n")
            parts.append("\n")

        parts.append("\n## کد\n")
        picked: list[dict] = []
        if not special:
            code_want = want if pos == 1 else {k for k in want if k not in SHARED_CHECKS}
            if not code_want:
                code_want = want
            for key in sorted(code_want, key=lambda k: int(FA.index(k)) if k in FA else 99):
                seg = seg_by(segs, CHECK_KEYS[key])
                if seg:
                    picked.append(seg)
        else:
            for needle in special.get("segments", []):
                seg = seg_by(segs, needle)
                if seg:
                    picked.append(seg)
        if pos == 1 or special:
            for needle in EXTRA_SEGMENTS.values():
                seg = seg_by(segs, needle)
                if seg and seg not in picked:
                    picked.append(seg)
        picked.sort(key=lambda s: s["from"])
        extra_bodies = [b for _, b, _ in anchor_cache]

        if special.get("anchor_blocks"):
            parts.append("### شرط‌ها و منطق خودِ روش (کد اصلی)\n")
            lines = ENGINE.read_text(encoding="utf-8").split("\n")
            for item in special["anchor_blocks"]:
                title, prefix = item[0], item[1]
                stop = item[2] if len(item) > 2 else ""
                got = anchor_block(lines, prefix, stop)
                if got:
                    a, b, body = got
                    anchor_cache.append((a, b, body))
                    parts.append(f"**{title}** — خطوط {fa(a)} تا {fa(b)}\n")
                    parts.append(code_block("kotlin", body))

        if picked:
            parts.append("### بخش‌های موتور (`core/IchimokuStrategyEngine.kt`)\n")
            if not special and pos != 1:
                parts.append("کد شرط‌های مشترک (۱، ۲، ۴، ۶ و ۱۱ تا ۱۷) این‌جا تکرار نشده؛ "
                             "در سند [00-shared-pipeline.md](00-shared-pipeline.md) است. "
                             "پایین فقط کد شرط‌های مکتبیِ همین روش آمده.\n\n")
            for seg in picked:
                parts.append(f"**{seg['title']}** — خطوط {fa(seg['from'])} تا {fa(seg['to'])}\n")
                parts.append(code_block("kotlin", seg["code"]))

        if special.get("functions"):
            text = ENGINE.read_text(encoding="utf-8").split("\n")
            for fname_ in special["functions"]:
                body = extract_decl(text, fname_)
                if body:
                    parts.append(f"### `{fname_}()` — بخش اختصاصی پیمان\n")
                    parts.append(code_block("kotlin", body))

        copies = list(special.get("code_files", []))
        if copies:
            sub = CODE_OUT / fname.replace(".md", "")
            sub.mkdir(parents=True, exist_ok=True)
            parts.append("### کد کامل این استراتژی (پوشهٔ جداگانه)\n")
            parts.append("| فایل | خط | داخل این سند |\n|---|---|---|\n")
            for rel in copies:
                src = SRC / rel
                dst = sub / Path(rel).name
                shutil.copyfile(src, dst)
                used_files.append(f"{sub.name}/{dst.name}")
                body = src.read_text(encoding="utf-8").rstrip()
                n = len(body.split("\n"))
                inline = n <= 300
                parts.append(f"| `code/{sub.name}/{dst.name}` | {fa(n)} | "
                             f"{'کامل، پایین' if inline else 'فقط در فایل کپی'} |\n")
                if inline:
                    parts.append(f"\n**{rel}**\n")
                    parts.append(code_block("kotlin", body))
            if any(len((SRC / r).read_text(encoding='utf-8').split('\n')) > 300 for r in copies):
                parts.append("\nفایل‌های بلندتر از ۳۰۰ خط داخل سند تکرار نشده‌اند تا سند سبک بماند؛ "
                             "نسخهٔ کاملشان در همان پوشهٔ `code/` و در فایل زیپ هست.\n")

        # ── پیام‌ها
        msgs: list[str] = []
        for seg in picked:
            msgs.extend(re.findall(r'"([^"\n]{18,120})"', seg["code"]))
        for body in extra_bodies:
            msgs.extend(re.findall(r'"([^"\n]{18,120})"', body))
        msgs = [m for m in msgs if re.search(r"[\u0600-\u06FF]", m)]
        seen, uniq = set(), []
        for m in msgs:
            if m in seen or "${" in m:
                continue
            seen.add(m)
            uniq.append(m)
        if uniq:
            parts.append("\n## پیام‌هایی که در همین بخش‌ها ساخته می‌شود\n")
            parts.append("| متن |\n|---|\n")
            for m in uniq[:14]:
                parts.append(f"| {m} |\n")

        # ── بررسی
        parts.append("\n## چطور خودت بررسی کنی\n")
        parts.append(verify_block(e))
        if special.get("code_files"):
            parts.append("\n> این استراتژی کد اختصاصی دارد؛ برای مرور کامل، فایل‌های "
                         "`code/` را جدا بردار.\n")

        (OUT / fname).write_text("".join(parts), encoding="utf-8")
        index_rows.append((pos, e, fname, slug))

    write_shared(segs, sets)
    for md in OUT.glob("*.md"):
        tidy(md)
    write_index(index_rows, sets, used_files)
    tidy(OUT / "README.md")
    make_zip()
    print(f"ساخته شد: {len(index_rows)} سند + index + zip در {OUT}")



def write_shared(segs: list[dict], sets: dict) -> None:
    out: list[str] = []
    out.append("# ۰۰ · موتور مشترک — چیزی که هر ۵ مدل ایچیموکو با آن سنجیده می‌شود\n")
    out.append("پانزده روش ایچیموکو (بیس، چهار تک‌مکتبی، شش دوتایی، چهار سه‌تایی) یک موتور دارند: "
               "`evaluateBitcoinStrategy` در `core/IchimokuStrategyEngine.kt`. تفاوتشان فقط "
               "«کدام شرط فعال است» و «R:R پایه» است. برای همین، شرط‌های مشترک این‌جا یک‌بار "
               "توضیح داده می‌شوند و در سند هر روش تکرار نمی‌شوند.\n")

    out.append("\n## ۱) مسیر یک تصمیم\n")
    out.append(code_block("text", """کندل بسته (m15 + m1h + m4h)
   ↓ عکس لحظه‌ای: ۵ خط ایچیموکو، ابر آیندهٔ ۲۶ کندل، چیکو، ATR، حجم، ER
   ↓ ۱۷ شرط (بعضی مشترک، بعضی مخصوص یک مکتب)
   ↓ محاسبهٔ عدد R:R در بازهٔ قفل‌شدهٔ ۱٫۵۰ تا ۳٫۰۰
   ↓ Trade Plan Guard: جهت، حد ضرر ساختاری، اندازهٔ پوزیشن، فاصله از قیمت
   ↓ دروازه‌های ایمنی و یادگیری عصبی
   ↓ پلن نهایی (یا رد شدن با دلیل مشخص)"""))

    out.append("\n## ۲) شرط‌های موتور\n")
    out.append("| # | شرط | مالک |\n|---|---|---|\n")
    owners = {
        1: "مشترک", 2: "مشترک", 3: "مکتب پاتل", 4: "مشترک", 5: "هوسودا + پلویل",
        6: "مشترک", 7: "مکتب تاتل", 8: "مکتب پاتل", 9: "تاتل + پلویل", 10: "هوسودا",
        11: "مشترک", 12: "مشترک", 13: "مشترک", 14: "مشترک", 15: "مشترک",
        16: "مشترک", 17: "مشترک",
    }
    for idx, label in sets["main"]:
        out.append(f"| {fa(idx)} | {strip_no(label)} | {owners.get(idx, '—')} |\n")
    out.append("\nشرط‌های ۱ تا ۱۰ پایهٔ تحلیلی‌اند و از مکتب صاحبشان می‌آیند؛ شرط‌های ۱۱ تا ۱۷ "
               "لایهٔ کیفیت اجرا و ایمنی‌اند و روی همهٔ روش‌ها اعمال می‌شوند.\n")

    out.append("\n## ۳) عدد R:R از کجا می‌آید\n")
    out.append("کد، عدد پایهٔ هر روش را داخل بازهٔ قفل‌شده می‌برد و بعد پاداش/جریمهٔ شرایط بازار "
               "را اضافه می‌کند (`IchimokuStrategyEngine.kt`، بخش «محاسبهٔ عدد مناسب R:R»):\n\n")
    out.append("| مرحله | قاعده در کد |\n|---|---|\n")
    out.append("| پایه | `activeModel.baseOptimalRr.coerceIn(1.50, 3.00)` |\n")
    out.append("| پاداش فاز روند | Genesis **+۰٫۲۵** · Core-Expansion **+۰٫۱۲** · بقیه **۰** |\n")
    out.append("| پاداش کارایی جهت‌دار | `((erStrat + erTrig - 0.50) * 0.45)` محدود به **−۰٫۱۵ تا +۰٫۳۵** |\n")
    out.append("| پاداش حجم | نسبت حجم ≥ ۱٫۲۰ → **+۰٫۱۲** |\n")
    out.append("| نهایی | جمع، محدود به بازهٔ `[requiredMinRr .. maxAllowedRr]` (سقف ۳٫۰۰) |\n")

    out.append("\n## ۴) کد مشترک\n")
    shared_titles = [k for k in CHECK_KEYS.values() if k not in
                     (CHECK_KEYS["۳"], CHECK_KEYS["۵"], CHECK_KEYS["۷"], CHECK_KEYS["۸"],
                      CHECK_KEYS["۹"], CHECK_KEYS["۱۰"])]
    picked, seen = [], set()
    for needle in shared_titles:
        seg = seg_by(segs, needle)
        if seg and seg["from"] not in seen:
            seen.add(seg["from"])
            picked.append(seg)
    for needle in EXTRA_SEGMENTS.values():
        seg = seg_by(segs, needle)
        if seg and seg["from"] not in seen:
            seen.add(seg["from"])
            picked.append(seg)
    seg = seg_by(segs, "ماشهٔ «فشردگی")
    if seg:
        picked.append(seg)
    picked.sort(key=lambda x: x["from"])
    for seg in picked:
        out.append(f"\n**{seg['title']}** — خطوط {fa(seg['from'])} تا {fa(seg['to'])}، "
                   "`core/IchimokuStrategyEngine.kt`\n")
        out.append(code_block("kotlin", seg["code"]))

    out.append("\n## ۵) دروازه‌های ایمنی و یادگیری عصبی\n")
    out.append("پیش از ثبت هر سفارش، `EntrySafetyGate` و `NeuralGatePolicy` یک لایهٔ جدا هستند: "
               "پیش‌فرض‌ها سقفِ پوزیشن هم‌زمان ۰ (یعنی بی‌نهایت)، سقف هر نماد ۰، حداکثر سن سیگنال "
               "۳ کندل، و سقف نسبت هزینه/ریسک ۰٫۲۵. هفت ترمز عصبی هم در "
               "`core/NeuralGatePolicy.kt` تعریف شده‌اند. توضیح کامل: "
               "`docs/NEURAL_GATE_AND_SAFETY_FA.md`.\n")
    out.append("\n## ۶) کد لایهٔ عصبی (کامل)\n")
    ng = SRC / "core/NeuralGatePolicy.kt"
    if ng.exists():
        shared_dir = CODE_OUT / "00-shared-pipeline"
        shared_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ng, shared_dir / ng.name)
        out.append(code_block("kotlin", ng.read_text(encoding="utf-8").rstrip()))
    (OUT / "00-shared-pipeline.md").write_text("".join(out), encoding="utf-8")


def extract_decl(lines: list[str], name: str) -> str:
    """بدنهٔ یک تابع/ویژگی را با شمارش آکولاد برمی‌گرداند."""
    start = next((i for i, l in enumerate(lines) if re.search(rf"\b(fun|val)\s+{re.escape(name)}\b", l)), None)
    if start is None:
        return ""
    depth, began = 0, False
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            began = True
        if began and depth <= 0:
            return "\n".join(lines[start:i + 1])
    return ""


def write_index(rows, sets, used_files) -> None:
    out = ["# کارنامهٔ استراتژی‌ها — Aurum Edge\n\n",
           "برای هر استراتژی یک سند جدا: مشخصات، شرط‌ها، کد، پیام‌ها و راه بررسی. "
           "همه‌چیز از خود سورس استخراج شده است (نه از حافظه) و با "
           "`python3 tools/strategy_docs/gen.py` بازتولید می‌شود.\n\n",
           "## فهرست\n\n",
           "| # | استراتژی | نشان | مکتب‌ها | R:R پایه | سند |\n",
           "|---|---|---|---|---|---|\n"]
    for pos, e, fname, slug in rows:
        books = " + ".join([b for b, k in (("پاتل", "hasPatel"), ("تاتل", "hasTuttle"),
                                           ("هوسودا", "hasHosoda"), ("پلویل", "hasPeloille")) if e[k]]) or "—"
        out.append(f"| {fa(pos)} | {e['title']} | {e['badge']} | {books} | "
                   f"{fa(e['rr'])} | [{fname}]({fname}) |\n")
    out.append("\n## چطور بگیرم و جدا بررسی کنم\n\n")
    out.append(code_block("bash", """# همهٔ سندها + کدها در یک فایل
docs/strategies/AurumEdge-Strategies-FA.zip

# یا فقط یکی
sed -n '1,200p' docs/strategies/01-base-ichimoku.md"""))
    out.append("\n## موتور مشترک\n\n")
    out.append("پنج روش ایچیموکو یک موتور مشترک دارند؛ شرط‌ها، فرمول R:R، اندازهٔ پوزیشن "
               "و دروازهٔ ایمنی همه از یک‌جا می‌آید. جزئیاتش در [00-shared-pipeline.md](00-shared-pipeline.md) "
               "است تا در هر سند تکرار نشود.\n")
    out.append("\n## کد اختصاصی هر روش\n\n")
    if used_files:
        out.append("| فایل | مربوط به |\n|---|---|\n")
        for f in sorted(set(used_files)):
            out.append(f"| `code/{f}` | سند همان پوشه |\n")
    out.append("\n## شرط‌های ۱۷گانهٔ مشترک (همهٔ ۵ روش)\n\n")
    out.append("| # | شرط |\n|---|---|\n")
    for idx, label in sets["main"]:
        out.append(f"| {fa(idx)} | {strip_no(label)} |\n")
    (OUT / "README.md").write_text("".join(out), encoding="utf-8")


def make_zip() -> None:
    zp = OUT / "AurumEdge-Strategies-FA.zip"
    if zp.exists():
        zp.unlink()
    subprocess.run(["zip", "-qr", str(zp), "."], cwd=OUT, check=False)


if __name__ == "__main__":
    main()
