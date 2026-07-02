#!/usr/bin/env python3
"""
升级 8 张图的 mermaid 紧凑配置 + 拆短 admin-auth 节点超长文字。

设计：
  1. 全局：curve basis → linear（直线不绕弯），padding 12/14 → 8，加 nodeSpacing/rankSpacing
  2. 重点：admin-auth-flow 拆短 4-5 个超长节点（dagre 引擎画图最宽的元凶）
"""
from __future__ import annotations
import re
from pathlib import Path

IMG_DIR = Path(__file__).parent

# ==================== 全局 mermaid 配置升级 ====================
# 8 张图共有的 flowchart 块（backup-restore padding=12, 其他 14）
OLD_FLOWCHART_12 = """    flowchart: {
      useMaxWidth: true,
      htmlLabels: true,
      curve: 'basis',
      padding: 12
    }"""
NEW_FLOWCHART_12 = """    flowchart: {
      useMaxWidth: true,
      htmlLabels: true,
      curve: 'linear',
      padding: 8,
      nodeSpacing: 30,
      rankSpacing: 25
    }"""

OLD_FLOWCHART_14 = """    flowchart: {
      useMaxWidth: true,
      htmlLabels: true,
      curve: 'basis',
      padding: 14
    }"""
NEW_FLOWCHART_14 = """    flowchart: {
      useMaxWidth: true,
      htmlLabels: true,
      curve: 'linear',
      padding: 8,
      nodeSpacing: 30,
      rankSpacing: 25
    }"""

# ==================== admin-auth 节点拆短 ====================
# 现状 → 简化版（拆掉 <br/> 第二第三行）
ADMIN_AUTH_NODE_FIXES = [
    # A0: 3 行 → 1 行
    (
        'A0["<b>阶段 A · shouldNotFilter 路径分流</b><br/>AdminAuthFilter.shouldNotFilter(request)<br/>依次匹配 ADMIN_ROUTES / DB public 白名单"]',
        'A0["<b>阶段 A · 路径分流</b><br/>shouldNotFilter 决定鉴权/放行/拒绝"]',
    ),
    # A8: 4 行 → 2 行
    (
        'A8{"命中<br/>PUBLIC_WRITE_ROUTES<br/>(POST /auth/login, POST /comments)?"}',
        'A8{"命中 PUBLIC_WRITE_ROUTES?<br/>(POST /auth/login 等)"}',
    ),
    # B7: 2 行 → 1 行（缩短函数名）
    (
        'B7["<b>DeviceService.verifyOnRequest(header X-Device-Id)</b><br/>查 admin_device 表 + status 判定"]',
        'B7["<b>verifyOnRequest(X-Device-Id)</b><br/>查 admin_device 表 + status 判定"]',
    ),
    # C1: 3 行 → 1 行
    (
        'C1["<b>AOP AuditLogAspect</b><br/>execution(* com.blog..controller..*(..))<br/>反射 @PostMapping/@PutMapping/@DeleteMapping"]',
        'C1["<b>AOP AuditLogAspect</b><br/>反射 @PostMapping/PutMapping/DeleteMapping"]',
    ),
    # C5/C6/C7: 1 行但有完整 URL 路径 - 拆短
    (
        'C5["/admin/devices/{id}/approve → APPROVE"]',
        'C5["/admin/devices/.../approve → APPROVE"]',
    ),
    (
        'C6["/admin/devices/{id}/revoke → REJECT"]',
        'C6["/admin/devices/.../revoke → REJECT"]',
    ),
    (
        'C7["/comments/{id}/status → APPROVE"]',
        'C7["/comments/.../status → APPROVE"]',
    ),
    # A4: 3 行 → 2 行
    (
        'A4["<b>必须鉴权</b><br/>return false →<br/>进入 doFilterInternal"]',
        'A4["<b>必须鉴权</b><br/>return false → doFilterInternal"]',
    ),
    # B3: 3 行 → 1 行
    (
        'B3["<b>JwtUtil.parse</b><br/>claims = parse(token)<br/>catch Expired/Signature/Malformed"]',
        'B3["<b>JwtUtil.parse</b><br/>catch Expired/Signature/Malformed"]',
    ),
    # B11: 1 行 OK, 跳过
    # C2: 1 行 OK, 跳过
    # C10: 3 行 → 1 行
    (
        'C10["<b>AuditLogAspect 调</b><br/>auditLogService.record()<br/>@Async 后台写库"]',
        'C10["<b>AuditLogAspect</b><br/>auditLogService.record() @Async"]',
    ),
]


def upgrade_mermaid_config(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    n = 0
    for old, new in [(OLD_FLOWCHART_12, NEW_FLOWCHART_12),
                     (OLD_FLOWCHART_14, NEW_FLOWCHART_14)]:
        if old in text:
            text = text.replace(old, new)
            n += 1
    if n > 0:
        path.write_text(text, encoding="utf-8")
    return n


def upgrade_admin_auth_nodes(path: Path) -> tuple[int, list[str]]:
    text = path.read_text(encoding="utf-8")
    changes = []
    for old, new in ADMIN_AUTH_NODE_FIXES:
        if old in text:
            text = text.replace(old, new)
            changes.append(f"    {old[:80]}")
    n = len(changes)
    if n > 0:
        path.write_text(text, encoding="utf-8")
    return n, changes


def main():
    # 1. 全局 mermaid 配置
    print("=" * 60)
    print("Phase 1: 全局 mermaid 配置升级（8 张图）")
    print("=" * 60)
    for f in sorted(IMG_DIR.glob("2026*.html")):
        n = upgrade_mermaid_config(f)
        print(f"  {f.name}: {n} 处配置升级")

    # 2. admin-auth 节点拆短
    print()
    print("=" * 60)
    print("Phase 2: admin-auth 节点拆短")
    print("=" * 60)
    f = IMG_DIR / "20260702-admin-auth-flow.html"
    n, changes = upgrade_admin_auth_nodes(f)
    print(f"  {f.name}: {n} 处节点拆短")
    for c in changes:
        print(c)


if __name__ == "__main__":
    main()
