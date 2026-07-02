#!/usr/bin/env python3
"""
audit-script：审计 9 个文件的 font-size 是否符合 RULES 期望。

期望：
  .mermaid .nodeLabel    18
  .mermaid .edgeLabel    15
  .decision-stage        14
  .decision-rule         16
  .decision-code         14
  .boundary-table        16
  .boundary-table th     15
  .boundary-table .scenario 14
  .boundary-table .response 14
  .file-name             15
  .file-desc             15
  .step-list li          15  (backup-restore only)
  .guard-text            14  (backup-restore only)
  .guard-title           13  (backup-restore only)
  .legend-item           15
  .tag                   13  (index only)
  .metric                13  (index only)
  .tip-desc              15  (index only)
  .btn                   15  (index only)
  .topnav                15  (index only)
  mobile .boundary-table 14  (仅 @media 块内)
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

IMG_DIR = Path(__file__).parent

EXPECTED = {
    ".mermaid .nodeLabel": 18,
    ".mermaid .edgeLabel": 15,
    ".decision-stage": 14,
    ".decision-rule": 16,
    ".decision-code": 14,
    ".boundary-table": 16,
    ".boundary-table th": 15,
    ".boundary-table .scenario": 14,
    ".boundary-table .response": 14,
    ".file-name": 15,
    ".file-desc": 15,
    ".step-list li": 15,
    ".guard-text": 14,
    ".guard-title": 13,
    ".legend-item": 15,
    ".tag": 13,
    ".metric": 13,
    ".tip-desc": 15,
    ".btn": 15,
    ".topnav": 15,
}

MOBILE_EXPECTED = 14  # .boundary-table inside @media (max-width: 768px)


def get_block_selector_via_stack(lines, line_idx):
    """用花括号栈找出 line_idx 真正所属的 selector。"""
    stack = []
    for j in range(line_idx):
        line = lines[j]
        if '{' in line:
            sel_part = line[:line.index('{')].strip()
            stack.append(sel_part)
            rest = line[line.index('{')+1:]
            # 配对关
            n_close = rest.count('}')
            for _ in range(n_close):
                if stack:
                    stack.pop()
        n_close = line.count('}')
        n_open = line.count('{')
        # 上面已经处理了同一行开+关，剩余的关
        # 实际上："xxx { ... }" 一行同时开和关，sel_part 后面跟着 {rest}
        # rest.count('}') 已经处理。line.count('}') 包含 rest 里的 }，所以不减
        # 修正：直接按 line.count('}') pop
        for _ in range(n_close):
            if len(stack) > 0 and ('{' not in line or line.find('}') > line.find('{')):
                # 仅当关没在开前面时 pop
                # 简化：如果 { } 在同一行且 } 在 { 之后，已经在 rest 处理过了
                pass
    return stack[-1] if stack else None


def audit_file(path: Path) -> list[str]:
    """审计一个文件，返回错配描述列表（空 = 完美）。"""
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    issues = []

    # 跟踪花括号栈
    selector_stack = []
    in_media = False
    media_depth = 0  # 在 @media 块内的花括号深度（从 0 开始算）

    for i, line in enumerate(lines):
        # 简化版：每行先算开/关
        n_open = line.count('{')
        n_close = line.count('}')

        # 处理开括号
        if n_open > 0:
            sel_part = line[:line.index('{')].strip()
            selector_stack.append(sel_part)
            # 是否进入 @media 块
            if "@media" in sel_part:
                in_media = True
                media_depth = 1
            elif in_media:
                media_depth += 1

        # 处理关括号
        for _ in range(n_close):
            if selector_stack:
                popped = selector_stack.pop()
                if in_media:
                    media_depth -= 1
                    if media_depth == 0:
                        in_media = False

        # 找 font-size
        if "font-size:" not in line:
            continue

        m = re.search(r"font-size:\s*(\d+)px", line)
        if not m:
            continue
        actual = int(m.group(1))

        # 当前 selector = 栈顶
        cur_selector = selector_stack[-1] if selector_stack else None

        # 找最匹配的 EXPECTED key
        if cur_selector is None:
            continue  # 不在已知块内
        # 优先在 EXPECTED 里找包含 cur_selector 的
        matched_key = None
        for key in EXPECTED:
            if key in cur_selector:
                matched_key = key
                break

        if matched_key is None:
            # 不在 EXPECTED 列表里（如 .hero p 16px, .decision-title 16px, .footer 14px 等）
            # 这些是已知不动的，跳过
            continue

        expected = EXPECTED[matched_key]

        if in_media and matched_key == ".boundary-table":
            # mobile 块
            if actual != MOBILE_EXPECTED:
                issues.append(f"  L{i+1} [{cur_selector}] mobile: {actual}px, 期望 {MOBILE_EXPECTED}px")
        else:
            if actual != expected:
                issues.append(f"  L{i+1} [{cur_selector}]: {actual}px, 期望 {expected}px")

    return issues


def main():
    files = sorted(IMG_DIR.glob("*.html"))
    if not files:
        print("❌ 没有 html 文件")
        sys.exit(1)

    total_issues = 0
    for f in files:
        issues = audit_file(f)
        if issues:
            print(f"\n❌ {f.name}: {len(issues)} 处不符")
            for it in issues[:10]:
                print(it)
            if len(issues) > 10:
                print(f"  ... 还有 {len(issues)-10} 处")
            total_issues += len(issues)
        else:
            print(f"✅ {f.name}")
    print(f"\n{'='*40}")
    if total_issues == 0:
        print("🎉 全部文件均符合 RULES 期望")
    else:
        print(f"⚠️  共 {total_issues} 处不符")


if __name__ == "__main__":
    main()
