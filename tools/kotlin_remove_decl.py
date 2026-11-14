#!/usr/bin/env python3
"""Remove a top-level/member Kotlin declaration (with its KDoc + annotations) by name.

Brace-matched, comment-inclusive. Refuses to act unless exactly one matching
declaration exists in the file, so it can never delete the wrong block.
"""
import re
import sys


def find_decl(lines, name):
    pat = re.compile(r'^\s{0,8}(?:[\w]+ +)*(?:fun|val|var|class|object|interface)\s+' + re.escape(name) + r'\b')
    hits = [i for i, line in enumerate(lines) if pat.search(line)]
    if len(hits) != 1:
        raise SystemExit(f"expected exactly 1 declaration of {name}, found {len(hits)}")
    return hits[0]


def block_start(lines, decl):
    """Walk upward past annotations and a KDoc/line-comment block."""
    i = decl
    # annotations directly above
    while i - 1 >= 0 and lines[i - 1].strip().startswith('@'):
        i -= 1
    # KDoc /** ... */ directly above
    if i - 1 >= 0 and lines[i - 1].strip().endswith('*/'):
        j = i - 1
        while j >= 0 and '/**' not in lines[j] and '/*' not in lines[j]:
            j -= 1
        if j >= 0:
            i = j
    # single-line comments directly above
    while i - 1 >= 0 and lines[i - 1].strip().startswith('//'):
        i -= 1
    return i


def block_end(lines, decl):
    """End line index (inclusive) of the declaration starting at `decl`.

    Handles three shapes:
      * block body  — `fun f() { ... }` (brace matched)
      * one-line expression body — `fun f() = expr`
      * wrapped expression body — `fun f(...): T =` followed by continuation lines
        (paren/operator continuation), never a brace-delimited block.
    """
    first = lines[decl]
    has_brace = '{' in first
    has_eq = '=' in first and not re.search(r'[!<>]=', first)
    if has_eq and not has_brace:
        # expression body: consume wrapped continuation lines
        end = decl
        def trailing_operator(line):
            t = line.rstrip()
            return t.endswith(('+', '=', '(', ',', '?', ':', '||', '&&', '.'))
        while end + 1 < len(lines):
            nxt = lines[end + 1]
            if not nxt.strip():
                break
            depth = lines[end].count('(') - lines[end].count(')')
            starts_op = nxt.lstrip().startswith(('+', '.', '?', ':', '||', '&&'))
            if depth > 0 or trailing_operator(lines[end]) or starts_op:
                end += 1
                continue
            break
        return end
    text = '\n'.join(lines[decl:])
    depth = 0
    started = False
    for idx, ch in enumerate(text):
        if ch == '{':
            depth += 1
            started = True
        elif ch == '}':
            depth -= 1
            if started and depth == 0:
                consumed = text[: idx + 1]
                return decl + consumed.count('\n')
    raise SystemExit(f"could not find end of block starting at line {decl + 1}")


def main():
    path, name = sys.argv[1], sys.argv[2]
    src = open(path, encoding='utf-8').read()
    lines = src.split('\n')
    decl = find_decl(lines, name)
    start = block_start(lines, decl)
    end = block_end(lines, decl)
    # drop one trailing blank line left behind
    removed = lines[start: end + 1]
    rest = lines[:start] + lines[end + 1:]
    if start < len(rest) and rest[start].strip() == '' and (start == 0 or rest[start - 1].strip() == ''):
        del rest[start]
    if '--apply' in sys.argv:
        open(path, 'w', encoding='utf-8').write('\n'.join(rest))
        print(f"REMOVED {name}  {path}:{start + 1}-{end + 1}  ({end - start + 1} lines)")
    else:
        print(f"WOULD REMOVE {name}  {path}:{start + 1}-{end + 1}  ({end - start + 1} lines)")
        for line in removed[:4]:
            print("    | " + line)
        print("    | ...")


if __name__ == '__main__':
    main()
