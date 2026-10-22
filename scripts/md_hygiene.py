#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Markdown 卫生检查与修复（DiPlay Lite 私有分支专用）

两类编辑器污染：
  1) 异常字符：正文混入西里尔(U+0400-04FF)/希腊(U+0370-03FF)/假名(U+3040-30FF)/韩文(U+AC00-D7AF)
  2) 换行污染：空行里夹空格/制表符  \r\n[ \t]+\r\n ；孤立 \r 后跟空格再 \n  \r[ \t]+\n

不改动正常 Markdown 行尾双空格（硬换行语法）。
用法：
  python scripts/md_hygiene.py scan    # 只报告
  python scripts/md_hygiene.py fix     # 报告并修复
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 扫描范围
SCAN_DIRS = ["工程文档", "docs"]
SCAN_ROOT_FILES = ["当前状态.md", "README.md", "README.zh-CN.md", "CHANGELOG.md", "SECURITY.md"]


def bad_char(ch: str) -> bool:
    o = ord(ch)
    # 西里尔
    if 0x0400 <= o <= 0x04FF:
        return True
    if 0x0370 <= o <= 0x03FF:
        return True
    if 0x3040 <= o <= 0x30FF:
        return True
    if 0xAC00 <= o <= 0xD7AF:
        return True
    return False


def iter_files():
    for d in SCAN_DIRS:
        base = os.path.join(ROOT, d)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [x for x in dirnames if x not in (".git", "node_modules")]
            for fn in filenames:
                if fn.lower().endswith(".md"):
                    yield os.path.join(dirpath, fn)
    for fn in SCAN_ROOT_FILES:
        p = os.path.join(ROOT, fn)
        if os.path.exists(p):
            yield p


PAT1 = re.compile(rb"\r\n[ \t]+\r\n")   # 空行里夹空白
PAT2 = re.compile(rb"\r[ \t]+\n")        # 孤立 \r + 空白 + \n
PAT_LOOSE_BLANK = re.compile(rb"\n[ \t]+\n")     # LF 版空行夹空白（仅报告）
PAT_LONE_CR = re.compile(rb"\r(?!\n)")           # 孤立 \r（仅报告）
PAT_MULTI_BLANK = re.compile(rb"(?:\r\n){4,}")   # 4 行以上连续空行（仅报告）


def dominant_eol(b: bytes) -> bytes:
    """判定文件主导行尾：CRLF 或 LF。返回 b'\r\n' 或 b'\n'"""
    crlf = b.count(b"\r\n")
    bare_lf = b.count(b"\n") - crlf
    return b"\r\n" if crlf >= bare_lf else b"\n"


P_BLANK_CRLF = re.compile(rb"\r\n[ \t]+\r\n")   # 空行里夹空白（CRLF 形态）
P_BLANK_LF = re.compile(rb"\n[ \t]+\n")         # 空行里夹空白（LF 形态）
P_CR_BEFORE_EOL = re.compile(rb"\r(?=[ \t]+\n)")  # 孤立 \r 紧贴行尾空格：只删 \r，保留行尾双空格
P_LONE_CR = re.compile(rb"\r(?!\n)")            # 其余孤立 \r（充当了行终止符）


def fix_newlines(b: bytes):
    """
    规范两类清理，行尾统一到「文件自身的主导行尾」（本项目 08-路线图 / 11-开源合规 是 LF 文件，
    不能盲目输出 \r\n）。
      1) 空行里夹空白     \r\n[ \t]+\r\n -> 空行（保留一个空行，清掉空白）
      2) 孤立 \r + 行尾空格 \r[ \t]+\n   -> 只删 \r，保留行尾双空格（硬换行语法不动）
         其余孤立 \r                     -> 主导行尾
    返回 (修复后 bytes, 修复次数)
    """
    eol = dominant_eol(b)
    n = 0

    # 1) 空行里夹空白
    pat, rep = (P_BLANK_CRLF, eol + eol) if eol == b"\r\n" else (P_BLANK_LF, b"\n\n")
    while True:
        b2, c = pat.subn(rep, b)
        if c == 0:
            break
        n += c
        b = b2

    # 2a) 孤立 \r 紧贴行尾空格 -> 只删 \r
    b, c = P_CR_BEFORE_EOL.subn(rb"", b)
    n += c

    # 2b) 其余孤立 \r -> 主导行尾
    b, c = P_LONE_CR.subn(eol, b)
    n += c

    # 3) 统一行尾到主导行尾
    if eol == b"\n":
        b, c = _count_sub(b, rb"\r\n", rb"\n")
    else:
        b, c = _count_sub(b, rb"(?<!\r)\n", rb"\r\n")
    n += c
    return b, n


def _count_sub(b: bytes, pat: bytes, rep: bytes):
    b2, c = re.subn(pat, rep, b)
    return b2, c


def residual(b: bytes):
    """修复后的残留污染计数，应为 0"""
    crlf = b.count(b"\r\n")
    bare_cr = b.count(b"\r") - crlf
    bare_lf = b.count(b"\n") - crlf
    eol = dominant_eol(b)
    mixed = bare_cr + (bare_lf if eol == b"\r\n" else crlf)
    pat = re.compile(rb"\r\n[ \t]+\r\n" if eol == b"\r\n" else rb"\n[ \t]+\n")
    return mixed + len(pat.findall(b))


def scan_file(path, fix=False):
    with open(path, "rb") as f:
        raw = f.read()
    rel = os.path.relpath(path, ROOT)

    # --- 换行污染 ---
    _, nl_count = fix_newlines(raw)
    # 其它统计（只报告不修）
    lone_cr = 0
    nl_fixed = raw
    if fix and nl_count:
        nl_fixed, _ = fix_newlines(raw)

    # --- 异常字符 ---
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return {"rel": rel, "error": "非 UTF-8，跳过", "nl": 0, "chars": []}

    hits = []
    lines = text.split("\n")
    for i, line in enumerate(lines, 1):
        bad = [ch for ch in line if bad_char(ch)]
        if bad:
            hits.append((i, line.rstrip("\r"), "".join(sorted(set(bad)))))

    result = {"rel": rel, "nl": nl_count, "chars": hits, "path": path,
              "eol": "CRLF" if dominant_eol(raw) == b"\r\n" else "LF"}
    if fix:
        new_raw = nl_fixed
        if hits:
            # 删除异常字符
            try:
                new_text = new_raw.decode("utf-8")
            except UnicodeDecodeError:
                new_text = None
            if new_text is not None:
                cleaned = "".join(ch for ch in new_text if not bad_char(ch))
                new_raw = cleaned.encode("utf-8")
        if new_raw != raw:
            with open(path, "wb") as f:
                f.write(new_raw)
            result["written"] = True
    return result


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "scan"
    files = sorted(iter_files())
    total_nl = 0
    total_file_nl = 0
    total_file_char = 0
    total_char_hits = 0
    for p in files:
        r = scan_file(p, fix=(mode == "fix"))
        if r.get("error"):
            print("[ERR ] %s : %s" % (r["rel"], r["error"]))
            continue
        if r["nl"] or r["chars"]:
            if r["nl"]:
                total_nl += r["nl"]
                total_file_nl += 1
            if r["chars"]:
                total_char_hits += len(r["chars"])
                total_file_char += 1
            print("[%s] %-62s 换行=%d 异常行=%d" % (
                "FIX" if (mode == "fix" and r.get("written")) else "HIT",
                r["rel"], r["nl"], len(r["chars"])))
            for (ln, content, chars) in r["chars"][:20]:
                disp = content[:120]
                print("        L%-5d %s  <<%s>>" % (ln, disp, chars))
    print("-" * 70)
    print("扫描文件数: %d" % len(files))
    print("换行污染: %d 处 / %d 个文件" % (total_nl, total_file_nl))
    print("异常字符行: %d 行 / %d 个文件" % (total_char_hits, total_file_char))
    if mode == "fix":
        res = 0
        for p in files:
            with open(p, "rb") as f:
                res += residual(f.read())
        print("复核 -> 残留污染 %d（期望 0）" % res)
    else:
        eols = {}
        for p in files:
            r = scan_file(p, fix=False)
            eols[r["eol"]] = eols.get(r["eol"], 0) + 1
        print("行尾构成: %s" % eols)
    if mode == "fix":
        # 复核
        re_nl = 0
        re_char = 0
        for p in files:
            r = scan_file(p, fix=False)
            re_nl += r.get("nl", 0)
            re_char += len(r.get("chars", []))
        print("复核 -> 换行残留 %d，异常字符残留 %d" % (re_nl, re_char))


if __name__ == "__main__":
    main()
