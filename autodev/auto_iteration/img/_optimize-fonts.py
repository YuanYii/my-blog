#!/usr/bin/env python3
"""
一次性脚本：把 8 张业务图 + index.html 的字号统一升级
(autodev/auto_iteration/img/ 目录下)

支持两种 CSS 写法：
  A) 单行：  .selector { ...; font-size: 14px; ... }
  B) 多行：  .selector {
                ...
                font-size: 14px;
                ...
             }

设计原则：
  - 阅读类（rule/desc/table/td/file/step）升 1-2px
  - 标签类（code/scenario/metric/tag）升 1px
  - mermaid node/edge 升 2-3px（最关键）
  - 移动端 .boundary-table 从 12 升到 14（之前 12 太小）
  - 不动：.eyebrow / .badge / h1 / h2 / body 基线
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

IMG_DIR = Path(__file__).parent  # 脚本所在目录 = img/
GLOB = "*.html"

# (CSS selector 子串, before_value, after_value)
RULES: list[tuple[str, int, int]] = [
    # === mermaid（所有图）===
    (".mermaid .nodeLabel",                                       15, 18),
    (".mermaid .edgeLabel",                                       13, 15),

    # === 决策卡片（7 张 07-02 图）===
    (".decision-stage",                                           13, 14),
    (".decision-rule",                                            14, 16),
    (".decision-code",                                            13, 14),

    # === 边界场景表（7 张 07-02 图）===
    (".boundary-table",                                           15, 16),
    (".boundary-table th",                                        14, 15),
    (".boundary-table .scenario",                                 13, 14),
    (".boundary-table .response",                                 13, 14),

    # === 文件清单（7 张 07-02 图）===
    (".file-name",                                                14, 15),
    (".file-desc",                                                14, 15),

    # === backup-restore 专属 ===
    (".step-list li",                                             13, 15),
    (".guard-text",                                               13, 14),
    (".guard-title",                                              12, 13),

    # === 图例（所有图）===
    (".legend-item",                                              14, 15),

    # === index.html 卡片元素 ===
    (".tag",                                                      12, 13),
    (".metric",                                                   12, 13),
    (".tip-desc",                                                 14, 15),
    (".btn",                                                      14, 15),
    (".topnav",                                                   14, 15),
]

# 移动端专属：只匹配在 @media (max-width: 768px) 块内的 .boundary-table
MOBILE_RULE: tuple[int, int] = (12, 14)
MOBILE_SELECTOR = ".boundary-table"
MOBILE_BLOCK_MARKER = "@media (max-width: 768px)"


def get_block_selector(lines: list[str], line_idx: int) -> str | None:
    """返回 line_idx 所在 CSS 块的 selector 文本（找最近上面含 { 的行）。"""
    for j in range(line_idx - 1, max(-1, line_idx - 20), -1):
        prev = lines[j]
        if "{" in prev:
            return prev[:prev.index("{")].strip()
        if "}" in prev and "{" not in prev:
            return None  # 已经出块
    return None


def is_in_mobile_block(lines: list[str], line_idx: int) -> bool:
    """判断 line_idx 是否在 @media (max-width: 768px) 块内（只看外层）。"""
    depth = 0
    for j in range(line_idx - 1, max(-1, line_idx - 50), -1):
        prev = lines[j]
        if "}" in prev:
            depth += prev.count("}")
        if "{" in prev:
            depth -= prev.count("{")
            if depth < 0:
                # 进入这个块了；检查是不是 mobile 块
                full = "".join(lines[max(0, j - 2):j + 1])
                return MOBILE_BLOCK_MARKER in full
    return False


def apply_rules_to_line(line: str, rules: list[tuple[str, int, int]]) -> tuple[str, bool]:
    """对单行应用一组规则。"""
    did_change = False
    for selector, before, after in rules:
        if selector not in line:
            continue
        pattern = re.compile(rf"(font-size:\s*){before}px")
        if pattern.search(line):
            line = pattern.sub(rf"\g<1>{after}px", line)
            did_change = True
    return line, did_change


def upgrade_one_file(path: Path) -> tuple[int, list[str]]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    changes: list[str] = []
    n = 0

    for i, line in enumerate(lines):
        # 只处理含 font-size 的行
        if "font-size:" not in line:
            continue

        # 模式 A：同行有 selector（单行 CSS）
        new_line, did = apply_rules_to_line(line, RULES)
        if did:
            lines[i] = new_line
            n += 1
            changes.append(f"  L{i+1} (inline): {line.strip()[:90]}")
            continue

        # 模式 B：跨行 — 找最近上面含 { 的 selector
        sel = get_block_selector(lines, i)
        if not sel:
            continue

        in_mobile = is_in_mobile_block(lines, i)

        # 移动端块内：只处理 .boundary-table
        if in_mobile:
            if MOBILE_SELECTOR in sel:
                new_line, did = apply_rules_to_line(
                    line, [(MOBILE_SELECTOR, *MOBILE_RULE)]
                )
                if did:
                    lines[i] = new_line
                    n += 1
                    changes.append(f"  L{i+1} (mobile/{sel[:30]}): {line.strip()[:90]}")
            continue

        # 普通块：应用 RULES（按 selector 子串匹配）
        for selector, before, after in RULES:
            if selector in sel:
                pattern = re.compile(rf"(font-size:\s*){before}px")
                if pattern.search(line):
                    line = pattern.sub(rf"\g<1>{after}px", line)
                    lines[i] = line
                    n += 1
                    changes.append(f"  L{i+1} ({sel[:30]}): {line.strip()[:90]}")
                    break

    if n > 0:
        path.write_text("".join(lines), encoding="utf-8")

    return n, changes


def main():
    files = sorted(IMG_DIR.glob(GLOB))
    if not files:
        print(f"❌ {IMG_DIR} 下没有 html 文件")
        sys.exit(1)

    total = 0
    for f in files:
        n, changes = upgrade_one_file(f)
        if n > 0:
            print(f"\n✅ {f.name}: {n} 处改动")
            for c in changes[:8]:
                print(c)
            if len(changes) > 8:
                print(f"  ... 还有 {len(changes)-8} 处")
            total += n
        else:
            print(f"⏭️  {f.name}: 无需改动")

    print(f"\n📊 总计 {total} 处字号升级（{len(files)} 个文件）")


if __name__ == "__main__":
    main()
