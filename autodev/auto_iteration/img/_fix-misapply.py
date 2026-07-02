#!/usr/bin/env python3
"""
fix-script：修复 _optimize-fonts.py 第一轮误改的 3 类错配。

误改路径（mode B 用"最近含 { 的行"做 selector，单行 CSS 闭合后被下一块复用）：
  1. 8 张图：.mermaid .edgeLabel 被当 .mermaid .nodeLabel 处理（15→18 而非 13→15）
  2. 4 张单行压缩图：.decision-code 被当 .decision-rule 处理（14→16 而非 13→14）
  3. 4 张单行压缩图：.boundary-table th 被当 .boundary-table 处理（15→16 而非 14→15）

idempotent：已是正确值的不动。
"""
from __future__ import annotations
import re
from pathlib import Path

IMG_DIR = Path(__file__).parent

ALL_DIAGRAMS = sorted(IMG_DIR.glob("2026*.html"))
SINGLE_LINE_DIAGRAMS = {
    "20260702-attachment-lifecycle.html",
    "20260702-dashboard-aggregation.html",
    "20260702-page-view-tracking.html",
    "20260702-zip-import-state-machine.html",
}

# (filename_filter, selector_substring, wrong_value, correct_value)
# filename_filter: 'all' | 'single_line' | specific filename
FIXES = [
    ("all",         ".mermaid .edgeLabel",   18, 15),
    ("single_line", ".decision-code",         16, 14),
    ("single_line", ".boundary-table th",     16, 15),
]


def main():
    total = 0
    for f in ALL_DIAGRAMS:
        text = f.read_text(encoding="utf-8")
        n = 0
        changes = []
        new_lines = []
        for line in text.splitlines(keepends=True):
            new_line = line
            for fname_filter, selector, wrong, correct in FIXES:
                if fname_filter == "all":
                    apply = True
                elif fname_filter == "single_line":
                    apply = f.name in SINGLE_LINE_DIAGRAMS
                else:
                    apply = (f.name == fname_filter)
                if not apply:
                    continue
                if selector not in new_line:
                    continue
                pattern = re.compile(rf"(font-size:\s*){wrong}px")
                if pattern.search(new_line):
                    new_line = pattern.sub(rf"\g<1>{correct}px", new_line)
                    n += 1
                    changes.append(f"    {line.strip()[:90]}")
            new_lines.append(new_line)
        if n > 0:
            f.write_text("".join(new_lines), encoding="utf-8")
            print(f"\n✅ {f.name}: {n} 处修复")
            for c in changes[:5]:
                print(c)
            if len(changes) > 5:
                print(f"    ... 还有 {len(changes)-5} 处")
            total += n
        else:
            print(f"⏭️  {f.name}: 无需修复")
    print(f"\n📊 总计修复 {total} 处")


if __name__ == "__main__":
    main()
