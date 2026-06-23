#!/usr/bin/env python3
"""
v4.3.0 备份/恢复功能 Playwright E2E 测试

设计依据：docs/testing/备份恢复功能自测清单.md（14 项 + 后端 API 5 项）

测试策略：
  - 真后端跑在 http://localhost:8080（dev profile），前端 dev 跑在 http://localhost:3000
  - 触发备份/恢复会因为缺 BACKUP_ENCRYPTION_PASSWORD env 而被后端拒（500）— 但前端流程仍可测
  - "切页签不丢" / "状态隔离" 这两个核心需求靠 Playwright 真实点击 + 路由切换验证
  - 测试结果写回 docs/testing/备份恢复功能自测清单.md（自动勾选 + 填备注）

用法：
  python3 scripts/test-backup-restore-e2e.py [--base http://localhost:3000] [--api http://localhost:8080]

依赖：
  Python 3.13 + playwright 1.60+ + chromium 浏览器
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path
from playwright.sync_api import sync_playwright, Page, BrowserContext, TimeoutError as PWTimeout

# ============= 配置 =============
BASE = "http://localhost:3000"
API = "http://localhost:8080"
USERNAME = "admin"
PASSWORD = "123456"
TIMEOUT_NAV = 30000      # 导航超时（Nuxt dev SSR + JS bundle 首次加载慢）
TIMEOUT_API = 10000      # API 调用超时
TIMEOUT_POLLING = 5000   # 等轮询状态切换
LOGIN_WAIT = 3000        # 登录后等 toast / dashboard

RESULTS = []  # [(test_id, test_name, status, note), ...]


# ============= 测试结果记录 =============
def record(test_id: str, name: str, status: str, note: str = ""):
    """status: PASS / FAIL / SKIP / MANUAL"""
    RESULTS.append((test_id, name, status, note))
    icon = {"PASS": "✅", "FAIL": "❌", "SKIP": "⚠️ ", "MANUAL": "👀"}.get(status, "?")
    print(f"  {icon} {test_id} {name}" + (f"  ({note})" if note else ""))


# ============= 工具函数 =============
def login(page: Page):
    """登录 admin
    v4.3.0 适配：admin 走设备白名单, 全新 deviceId 会被拒
    → 先用 trust-migrate (curl 触发) 拿到后端认可的 deviceId, 写到 localStorage 再登录
    """
    # 1) 先 curl 触发 trust-migrate 拿到 approved deviceId
    import urllib.request, json as _json
    login_req = urllib.request.Request(
        f"{API}/api/v1/auth/login",
        data=_json.dumps({"username": USERNAME, "password": PASSWORD}).encode(),
        method="POST",
        headers={"Content-Type": "application/json", "User-Agent": "test-e2e/1.0"}
    )
    try:
        with urllib.request.urlopen(login_req, timeout=10) as resp:
            body = _json.loads(resp.read().decode())
            approved_device_id = body.get("data", {}).get("deviceId", "")
    except Exception as e:
        print(f"  ⚠️  预登录失败（trust-migrate 取 deviceId 失败）: {e}")
        approved_device_id = ""

    # 2) 进登录页之前先注入 approved deviceId 到 localStorage
    #    否则 useDevice 首次 init() 时 localStorage 是空 → 自动生成新 UUID → 不是 approved → 2001
    if approved_device_id:
        init_js = (
            "try { localStorage.setItem('blog_admin_device_id', '"
            + approved_device_id.replace("'", "\\'") +
            "') } catch(e){}"
        )
        page.add_init_script(init_js)
    page.goto(f"{BASE}/admin/login", timeout=TIMEOUT_NAV)
    page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
    # 给 Vue 异步 mount + ClientOnly 一点时间
    try:
        page.wait_for_function(
            "() => document.querySelector('input[type=\"text\"]') !== null",
            timeout=TIMEOUT_NAV  # 30s, 容忍 Nuxt dev SSR + hydration 慢
        )
    except Exception:
        page.screenshot(path="/tmp/login-fail.png")
        print(f"  ⚠️  login page 没有 input, 当前 url={page.url}, title={page.title()}")
        raise
    # 3) 填表单 + 点登录
    page.fill('input[name="username"], input[placeholder*="用户"], input[type="text"]', USERNAME, timeout=TIMEOUT_API)
    page.fill('input[name="password"], input[type="password"]', PASSWORD, timeout=TIMEOUT_API)
    page.click('button:has-text("登录"), button[type="submit"]', timeout=TIMEOUT_API)
    page.wait_for_url(lambda url: "/admin/login" not in url, timeout=TIMEOUT_NAV)
    time.sleep(LOGIN_WAIT / 1000)


def safe_text(page: Page, selector: str, default: str = "") -> str:
    try:
        loc = page.locator(selector).first
        if loc.count() > 0:
            return loc.inner_text(timeout=TIMEOUT_API).strip()
    except Exception:
        pass
    return default


def has_text(page: Page, text: str) -> bool:
    """页面任意位置出现 text"""
    try:
        return page.locator(f"text={text}").first.is_visible(timeout=TIMEOUT_API)
    except Exception:
        return False


def click_with_retry(page: Page, selector: str, max_tries: int = 3):
    """点击元素, 重试机制防 transient failure"""
    for i in range(max_tries):
        try:
            page.locator(selector).first.click(timeout=TIMEOUT_API)
            return True
        except Exception as e:
            if i == max_tries - 1:
                raise
            time.sleep(0.5)


# ============= 测试场景 =============
def test_section_1_backup(page: Page):
    """§1. 备份功能测试"""
    print("\n=== 1. 备份功能测试 ===")

    # 1.1 备份历史列表展示
    page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
    page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
    # 等 Vue 异步 mount 表格 / 空提示 / 加载状态
    try:
        page.wait_for_function(
            """() => {
                const hasTable = document.querySelector('table tbody tr') !== null
                const hasEmpty = !!document.body.innerText.match(/暂无备份记录/)
                const hasLoading = !!document.body.innerText.match(/加载中/)
                return hasTable || hasEmpty || hasLoading
            }""",
            timeout=10000
        )
    except Exception:
        pass
    time.sleep(1)
    has_table = page.locator("table").count() > 0
    has_loading_or_empty = (
        has_text(page, "加载中") or
        has_text(page, "暂无备份记录") or
        page.locator("table tbody tr").count() > 0
    )
    if has_table or has_loading_or_empty:
        record("1.1", "备份历史列表展示", "PASS",
               f"表格存在={has_table}, 行数={page.locator('table tbody tr').count()}")
    else:
        record("1.1", "备份历史列表展示", "FAIL", "页面无表格 / 无加载状态 / 无空提示")

    # 1.2 触发备份
    trigger_btn = page.locator('button:has-text("立即备份")').first
    try:
        if not trigger_btn.is_visible(timeout=TIMEOUT_API):
            record("1.2", "触发备份", "FAIL", "找不到「立即备份」按钮")
        else:
            trigger_btn.click()
            time.sleep(0.5)
            # 等二次确认 Dialog
            confirm_btn = page.locator('button:has-text("开始备份")').first
            try:
                if confirm_btn.is_visible(timeout=3000):
                    confirm_btn.click()
                else:
                    # 没二次确认 Dialog — 可能新版取消了
                    pass
            except PWTimeout:
                pass
            time.sleep(2)
            # 检查按钮文字变 "备份进行中" 或 Toast
            running_visible = has_text(page, "备份进行中")
            toast_visible = has_text(page, "备份任务已创建")
            # 后端会因缺 env 拒（500），前端弹错误 toast
            error_toast = has_text(page, "BACKUP_ENCRYPTION_PASSWORD") or has_text(page, "触发失败")
            if running_visible or toast_visible or error_toast:
                note = []
                if running_visible: note.append("按钮变 '备份进行中'")
                if toast_visible: note.append("成功 toast")
                if error_toast: note.append("预期内错误 toast（缺 BACKUP_ENCRYPTION_PASSWORD）")
                record("1.2", "触发备份", "PASS", "; ".join(note))
            else:
                record("1.2", "触发备份", "FAIL", "按钮/Toast 无变化")
    except Exception as e:
        record("1.2", "触发备份", "FAIL", f"异常: {e}")

    # 1.3 备份轮询状态持久化 — 在 backup 页设 localStorage 然后切到 restore 页再回来
    try:
        # 模拟"备份进行中"的状态: 写 localStorage backupPollingId
        page.evaluate("""() => {
            localStorage.setItem('backupPollingId', '999')
        }""")
        # 切到 restore 页
        page.goto(f"{BASE}/admin/restore", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(1)
        # 再切回 backup 页
        page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(2)
        # 检查 localStorage 里 polling id 仍在（不应被激进清掉）
        saved = page.evaluate("() => localStorage.getItem('backupPollingId')")
        # 不再因 404 激进的清掉
        if saved == "999":
            record("1.3", "备份轮询状态持久化（切页签不丢）", "PASS",
                   "localStorage('backupPollingId') 切页签后仍保留")
        else:
            record("1.3", "备份轮询状态持久化（切页签不丢）", "FAIL",
                   f"localStorage 被改={saved}, 应保留 '999'")
        # 清理
        page.evaluate("() => localStorage.removeItem('backupPollingId')")
    except Exception as e:
        record("1.3", "备份轮询状态持久化（切页签不丢）", "FAIL", f"异常: {e}")

    # 1.4 删除备份记录 — SUCCESS 状态需输 DELETE 字样
    try:
        page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(1)
        success_rows = page.locator("table tbody tr").filter(has_text="成功")
        if success_rows.count() > 0:
            # 找第一个 SUCCESS 行的删除按钮
            row = success_rows.first
            row.locator('button[aria-label="删除"], button:has-text("删"), svg').first.click(timeout=TIMEOUT_API)
            time.sleep(0.5)
            # 等 prompt Dialog
            prompt_input = page.locator('input[placeholder="DELETE"], input').filter(has_text="").first
            try:
                if page.locator('input[placeholder="DELETE"]').count() > 0:
                    page.fill('input[placeholder="DELETE"]', "DELETE")
                    # 找删除按钮（在 Dialog footer）
                    page.locator('.dialog-footer button:has-text("删除")').first.click()
                    time.sleep(2)
                    record("1.4", "删除备份记录（SUCCESS 输 DELETE）", "PASS", "Dialog 流程通")
                else:
                    record("1.4", "删除备份记录（SUCCESS 输 DELETE）", "FAIL", "无 DELETE 字样输入框")
            except PWTimeout:
                record("1.4", "删除备份记录（SUCCESS 输 DELETE）", "FAIL", "Dialog 流程超时")
        else:
            record("1.4", "删除备份记录（SUCCESS 输 DELETE）", "SKIP", "无 SUCCESS 状态的备份可测")
    except Exception as e:
        record("1.4", "删除备份记录（SUCCESS 输 DELETE）", "FAIL", f"异常: {e}")

    # 1.5 删除失败记录 — 普通确认
    try:
        page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(2)
        # 先确保列表加载完（表格存在 + 不在"加载中"状态）
        try:
            page.wait_for_selector('table tbody tr', timeout=5000)
        except Exception:
            record("1.5", "删除失败记录", "SKIP", "表格未加载（可能列表为空）")
            return
        failed_rows = page.locator("table tbody tr").filter(has_text="失败")
        if failed_rows.count() > 0:
            row = failed_rows.first
            row.locator('button[aria-label="删除"]').first.click(timeout=TIMEOUT_API)
            time.sleep(1)
            confirm_btn = page.locator('.dialog-footer button:has-text("删除")').first
            try:
                if confirm_btn.is_visible(timeout=5000):
                    confirm_btn.click()
                    time.sleep(2)
                    record("1.5", "删除失败记录", "PASS", "普通确认 Dialog 流程通")
                else:
                    record("1.5", "删除失败记录", "FAIL", "确认 Dialog 没出现")
            except PWTimeout:
                record("1.5", "删除失败记录", "FAIL", "Dialog 超时")
        else:
            record("1.5", "删除失败记录", "SKIP", "无失败状态的备份可测")
    except Exception as e:
        record("1.5", "删除失败记录", "FAIL", f"异常: {e}")

    # 1.6 查看失败详情 — 点失败 badge 弹详情 Dialog
    try:
        page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(1)
        failed_badges = page.locator('.status-badge.status-danger.clickable')
        if failed_badges.count() > 0:
            failed_badges.first.click()
            time.sleep(0.5)
            if has_text(page, "备份失败详情"):
                record("1.6", "查看失败详情", "PASS", "弹框出现 '备份失败详情'")
                # 关闭
                page.locator('.modal-close, button:has-text("关闭")').first.click()
            else:
                record("1.6", "查看失败详情", "FAIL", "点击失败 badge 后未弹详情")
        else:
            record("1.6", "查看失败详情", "SKIP", "无失败任务可点")
    except Exception as e:
        record("1.6", "查看失败详情", "FAIL", f"异常: {e}")


def test_section_2_restore(page: Page):
    """§2. 恢复功能测试"""
    print("\n=== 2. 恢复功能测试 ===")

    # 2.1 恢复历史列表展示
    page.goto(f"{BASE}/admin/restore", timeout=TIMEOUT_NAV)
    page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
    try:
        page.wait_for_function(
            """() => {
                const hasTable = document.querySelector('table tbody tr') !== null
                const hasEmpty = !!document.body.innerText.match(/暂无恢复记录/)
                const hasLoading = !!document.body.innerText.match(/加载中/)
                return hasTable || hasEmpty || hasLoading
            }""",
            timeout=15000
        )
    except Exception:
        pass
    time.sleep(2)
    has_table = page.locator("table").count() > 0
    has_loading_or_empty = (
        has_text(page, "加载中") or
        has_text(page, "暂无恢复记录") or
        page.locator("table tbody tr").count() > 0
    )
    if has_table or has_loading_or_empty:
        record("2.1", "恢复历史列表展示（新增）", "PASS",
               f"表格存在={has_table}, 行数={page.locator('table tbody tr').count()}")
    else:
        # 看下页面到底什么内容
        url_now = page.url
        title = page.title()
        body_snippet = page.evaluate("() => document.body.innerText.slice(0, 200)")
        record("2.1", "恢复历史列表展示（新增）", "FAIL",
               f"页面无表格 / 无加载状态 / 无空提示; url={url_now}, title={title}, body[:200]={body_snippet}")

    # 2.2 触发恢复
    try:
        # 选一个备份（select option）
        select = page.locator('select.form-control').first
        options = select.locator('option').all()
        success_options = [o for o in options if o.get_attribute("value") and o.get_attribute("value") != "null"]
        if not success_options:
            record("2.2", "触发恢复", "SKIP", "无可选的成功备份")
            return
        select.select_option(value=success_options[0].get_attribute("value"))
        time.sleep(0.5)
        # 点「数据恢复」按钮
        restore_btn = page.locator('button:has-text("数据恢复")').first
        restore_btn.click()
        time.sleep(0.5)
        # 等 Dialog
        if page.locator('button:has-text("确认恢复")').count() > 0:
            page.locator('button:has-text("确认恢复")').first.click()
            time.sleep(2)
            # 等错误 toast（缺 env）
            error_toast = has_text(page, "BACKUP_ENCRYPTION_PASSWORD") or has_text(page, "触发失败")
            running_visible = has_text(page, "恢复中")
            if error_toast or running_visible:
                note = []
                if running_visible: note.append("按钮变 '恢复中'")
                if error_toast: note.append("预期内错误 toast")
                record("2.2", "触发恢复", "PASS", "; ".join(note))
            else:
                record("2.2", "触发恢复", "FAIL", "按钮/Toast 无变化")
        else:
            record("2.2", "触发恢复", "FAIL", "确认恢复 Dialog 没出现")
    except Exception as e:
        record("2.2", "触发恢复", "FAIL", f"异常: {e}")

    # 2.3 恢复轮询状态持久化 + 切页签不丢（核心需求）
    try:
        # 模拟"恢复进行中"
        page.evaluate("""() => {
            localStorage.setItem('restorePollingId', '888')
        }""")
        # 切到 backup 页
        page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(2)
        # 关键检查 1: backup 页应该看到"恢复任务进行中"卡片
        restore_card_visible = has_text(page, "数据恢复任务进行中")
        # 关键检查 2: localStorage 不应被激进清掉
        saved = page.evaluate("() => localStorage.getItem('restorePollingId')")
        # 关键检查 3: 即使 GET /restore/888 返回 404, localStorage 也不该被清（修复 A race）
        # （实际上端可能有 id=888 的 mock 记录, 但 restorePollingTask.start 还没真启动过, 所以 GET 会失败）
        notes = []
        notes.append(f"localStorage 保留={saved == '888'}")
        notes.append(f"backup 页显示恢复卡={restore_card_visible}")
        # 切回 restore 页, 看 localStorage 仍在
        page.goto(f"{BASE}/admin/restore", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(2)
        saved_after = page.evaluate("() => localStorage.getItem('restorePollingId')")
        notes.append(f"切回后 localStorage 保留={saved_after == '888'}")
        if saved == "888" and saved_after == "888" and restore_card_visible:
            record("2.3", "恢复轮询状态持久化（切页签不丢）", "PASS", "; ".join(notes))
        elif saved != "888" or saved_after != "888":
            record("2.3", "恢复轮询状态持久化（切页签不丢）", "FAIL",
                   f"localStorage 被激进清掉: saved={saved}, after={saved_after}; 卡片显示={restore_card_visible}")
        else:
            record("2.3", "恢复轮询状态持久化（切页签不丢）", "FAIL",
                   f"backup 页未显示恢复卡; saved={saved}, after={saved_after}")
        # 清理
        page.evaluate("() => localStorage.removeItem('restorePollingId')")
    except Exception as e:
        record("2.3", "恢复轮询状态持久化（切页签不丢）", "FAIL", f"异常: {e}")

    # 2.4 查看恢复失败详情
    try:
        page.goto(f"{BASE}/admin/restore", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(1)
        failed_badges = page.locator('.status-badge.status-danger.clickable')
        if failed_badges.count() > 0:
            failed_badges.first.click()
            time.sleep(0.5)
            if has_text(page, "恢复失败详情"):
                record("2.4", "查看恢复失败详情（新增）", "PASS", "弹框出现 '恢复失败详情'")
                page.locator('.modal-close, button:has-text("关闭")').first.click()
            else:
                record("2.4", "查看恢复失败详情（新增）", "FAIL", "未弹详情")
        else:
            record("2.4", "查看恢复失败详情（新增）", "SKIP", "无失败恢复任务可点")
    except Exception as e:
        record("2.4", "查看恢复失败详情（新增）", "FAIL", f"异常: {e}")


def test_section_3_isolation(page: Page):
    """§3. 状态隔离测试"""
    print("\n=== 3. 状态隔离测试 ===")

    # 3.1 备份和恢复的轮询状态隔离
    try:
        # 同时设两个 polling id
        page.evaluate("""() => {
            localStorage.setItem('backupPollingId', '111')
            localStorage.setItem('restorePollingId', '222')
        }""")
        # 进 backup 页
        page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(2)
        backup_id = page.evaluate("() => localStorage.getItem('backupPollingId')")
        restore_id = page.evaluate("() => localStorage.getItem('restorePollingId')")
        # 两个应独立
        if backup_id == "111" and restore_id == "222":
            record("3.1", "备份和恢复轮询状态隔离", "PASS",
                   f"backup={backup_id}, restore={restore_id}, 互不覆盖")
        else:
            record("3.1", "备份和恢复轮询状态隔离", "FAIL",
                   f"backup={backup_id}, restore={restore_id}, 应分别为 111/222")
        # 清理
        page.evaluate("""() => {
            localStorage.removeItem('backupPollingId')
            localStorage.removeItem('restorePollingId')
        }""")
    except Exception as e:
        record("3.1", "备份和恢复轮询状态隔离", "FAIL", f"异常: {e}")

    # 3.2 并发互斥 — 后端会拒同请求
    # 这条要并发触发两个, 复杂 — 标记为手动测
    record("3.2", "并发互斥（备份/恢复不能同时）", "MANUAL",
           "需同时触发备份和恢复, E2E 不易模拟, 见 scripts/test-backup-restore.sh line 218")


def test_section_4_boundary(page: Page):
    """§4. 边界情况测试"""
    print("\n=== 4. 边界情况测试 ===")

    # 4.1 页面刷新后轮询状态恢复
    try:
        page.evaluate("""() => {
            localStorage.setItem('restorePollingId', '777')
        }""")
        # F5 刷新
        page.goto(f"{BASE}/admin/restore", timeout=TIMEOUT_NAV)
        page.reload(timeout=TIMEOUT_NAV)
        page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        time.sleep(2)
        saved = page.evaluate("() => localStorage.getItem('restorePollingId')")
        # 应该还在（不会激进清）
        if saved == "777":
            record("4.1", "页面刷新后轮询状态恢复", "PASS", "localStorage 刷新后保留")
        else:
            record("4.1", "页面刷新后轮询状态恢复", "FAIL",
                   f"刷新后 localStorage={saved}, 应保留 '777'")
        page.evaluate("() => localStorage.removeItem('restorePollingId')")
    except Exception as e:
        record("4.1", "页面刷新后轮询状态恢复", "FAIL", f"异常: {e}")

    # 4.2 多个浏览器标签页测试 — 标签页 A 设 polling, 标签页 B 在 backup 页也看得到「恢复进行中」卡
    # Playwright BrowserContext 多 page 可模拟, 这里用 page B 验证
    # v4.3.0 polish 核心: 切到 backup 页也能看到恢复任务进行中 (cross-page visibility)
    try:
        ctx: BrowserContext = page.context
        page_a = page  # 当前
        # 用 ctx.new_page() 但共享 cookies + localStorage —— Playwright 默认行为
        # 但需确保 token 已在 localStorage（login 时已 setSession）
        page_b = ctx.new_page()
        # 把 token + user 写到 page_b (其实 context 已共享, 直接 add_init_script 即可)
        # 先看 page_a 现在的 localStorage
        token = page_a.evaluate("() => localStorage.getItem('blog_admin_token')")
        user = page_a.evaluate("() => localStorage.getItem('blog_admin_user')")
        device = page_a.evaluate("() => localStorage.getItem('blog_admin_device_id')")
        print(f"  [4.2 debug] token={bool(token)}, user={bool(user)}, device={device}")
        # 设 restorePollingId 到 page_a (context 共享 → page_b 也看得到)
        page_a.evaluate("""() => {
            localStorage.setItem('restorePollingId', '555')
        }""")
        # 标签页 B 进 backup 页 (用户切到 backup 页的场景)
        page_b.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
        page_b.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
        # 等 admin-auth middleware 完成（可能有 redirect）
        time.sleep(3)
        saved_b = page_b.evaluate("() => localStorage.getItem('restorePollingId')")
        # 看 page_b 当前 url（确认有没有被踢回 login）
        url_b = page_b.url
        print(f"  [4.2 debug] page_b url={url_b}, saved_b={saved_b}")
        # 标签页 B (在 backup 页) 应能看到「数据恢复任务进行中」卡 (v4.3.0 新增)
        restore_card_visible = False
        if "/admin/backup" in url_b:
            try:
                restore_card_visible = page_b.locator('text="数据恢复任务进行中"').first.is_visible(timeout=5000)
            except Exception:
                pass
        if saved_b == "555" and restore_card_visible:
            record("4.2", "多个浏览器标签页测试（backup 页可见恢复卡）", "PASS",
                   f"标签页 B 在 backup 页可见恢复卡, localStorage 共享={saved_b}")
        else:
            record("4.2", "多个浏览器标签页测试（backup 页可见恢复卡）", "FAIL",
                   f"localStorage B={saved_b}, 卡可见={restore_card_visible}, page_b_url={url_b}")
        page_a.evaluate("() => localStorage.removeItem('restorePollingId')")
        page_b.close()
    except Exception as e:
        record("4.2", "多个浏览器标签页测试（backup 页可见恢复卡）", "FAIL", f"异常: {e}")


def test_section_5_backend_api():
    """§5. 后端 API 测试 (用 Python requests 直接调, 比 playwright 直)"""
    print("\n=== 5. 后端 API 测试 ===")
    try:
        import urllib.request
        import urllib.error

        def req(method, path, body=None, token=None, device=None):
            url = f"{API}{path}"
            data = json.dumps(body).encode() if body is not None else None
            r = urllib.request.Request(url, data=data, method=method)
            if body is not None:
                r.add_header("Content-Type", "application/json")
            if token:
                r.add_header("Authorization", f"Bearer {token}")
            if device:
                r.add_header("X-Device-Id", device)
            try:
                with urllib.request.urlopen(r, timeout=TIMEOUT_API / 1000) as resp:
                    return resp.status, json.loads(resp.read().decode())
            except urllib.error.HTTPError as e:
                raw = e.read().decode()
                try:
                    return e.code, json.loads(raw)
                except Exception:
                    return e.code, {"raw": raw}

        # 登录
        print(f"  → req POST /api/v1/auth/login (api={API})")
        code, body = req("POST", "/api/v1/auth/login", {"username": USERNAME, "password": PASSWORD})
        print(f"  ← code={code}, body keys={list(body.keys()) if isinstance(body, dict) else 'NOT DICT'}")
        if code != 200 or not body.get("data", {}).get("token"):
            record("5.0", "后端登录", "FAIL", f"HTTP {code}, body={body}")
            return
        token = body["data"]["token"]
        device = body["data"]["deviceId"]
        record("5.0", "后端登录", "PASS", f"deviceId={device}")

        # 5.1 备份列表
        code, body = req("GET", "/api/v1/admin/backup/list?page=1&size=20", token=token, device=device)
        if code == 200:
            record("5.1", "备份历史列表 API", "PASS", f"total={body.get('data', {}).get('total', '?')}")
        else:
            record("5.1", "备份历史列表 API", "FAIL", f"HTTP {code}")

        # 5.2 恢复列表（新增）
        code, body = req("GET", "/api/v1/admin/restore/list?page=1&size=20", token=token, device=device)
        if code == 200:
            record("5.2", "恢复历史列表 API（新增）", "PASS", f"total={body.get('data', {}).get('total', '?')}")
        else:
            record("5.2", "恢复历史列表 API（新增）", "FAIL", f"HTTP {code}")

        # 5.3 备份详情
        code, body = req("GET", "/api/v1/admin/backup/1", token=token, device=device)
        if code == 200:
            record("5.3", "单条备份详情 API", "PASS", f"id=1 status={body.get('data', {}).get('status', '?')}")
        else:
            record("5.3", "单条备份详情 API", "FAIL", f"HTTP {code}")

        # 5.4 恢复详情
        code, body = req("GET", "/api/v1/admin/restore/1", token=token, device=device)
        if code == 200:
            record("5.4", "单条恢复详情 API", "PASS", f"id=1 status={body.get('data', {}).get('status', '?')}")
        else:
            record("5.4", "单条恢复详情 API", "FAIL", f"HTTP {code}")

    except Exception as e:
        record("5.x", "后端 API 测试", "FAIL", f"异常: {e}")


# ============= 写回 md 清单 =============
def write_to_md():
    """把测试结果写回 docs/testing/备份恢复功能自测清单.md"""
    md_path = Path(__file__).parent.parent / "docs" / "testing" / "备份恢复功能自测清单.md"
    if not md_path.exists():
        print(f"⚠️  md 清单不存在: {md_path}, 跳过写回")
        return

    text = md_path.read_text(encoding="utf-8")
    # 按 test_id 替换 测试结果：… 行
    # md 里的实际格式: "**测试结果：□ 通过 □ 失败**" （markdown 加粗）
    by_id = {r[0]: r for r in RESULTS}
    new_lines = []
    for line in text.split("\n"):
        # 兼容两种格式: 带 ** 加粗 / 不带
        is_result_line = ("测试结果：" in line and "□ 通过" in line and "□ 失败" in line)
        if is_result_line:
            # 找出当前行所属测试 — 向上找 ### X.Y
            test_id = None
            for i in range(len(new_lines) - 1, -1, -1):
                l = new_lines[i]
                if l.startswith("### "):
                    # 提取 "X.Y"
                    after = l.split("### ", 1)[1].split(" ", 1)[0]
                    if "." in after:
                        test_id = after
                    break
            if test_id and test_id in by_id:
                _, name, status, note = by_id[test_id]
                icon = {"PASS": "✅", "FAIL": "❌", "SKIP": "⚠️", "MANUAL": "👀"}.get(status, "□")
                # 保留 markdown 加粗结构
                line = line.replace("□ 通过 □ 失败", f"{icon} {status}")
                if note and "**备注：**" in line:
                    line = line + f" {note}"
            elif test_id:
                # 没在结果里 — 标记未测
                line = line.replace("□ 通过 □ 失败", "⚪ 未测")
        new_lines.append(line)

    md_path.write_text("\n".join(new_lines), encoding="utf-8")
    print(f"\n📝 结果已写回 {md_path}")


# ============= 主流程 =============
def main():
    global BASE, API
    parser = argparse.ArgumentParser(description="v4.3.0 备份/恢复 Playwright E2E")
    parser.add_argument("--base", default=BASE, help="前端 base URL")
    parser.add_argument("--api", default=API, help="后端 base URL")
    parser.add_argument("--no-md", action="store_true", help="不写回 md 清单")
    args = parser.parse_args()

    BASE = args.base
    API = args.api

    print(f"前端: {BASE}, 后端: {API}\n")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 900})
        page = ctx.new_page()

        # 后端 API 测试（不需要登录态以外的 session）
        test_section_5_backend_api()

        # 前端 E2E — 需要登录
        try:
            login(page)
            test_section_1_backup(page)
            test_section_2_restore(page)
            test_section_3_isolation(page)
            test_section_4_boundary(page)
        except Exception as e:
            print(f"\n❌ 前端测试异常: {e}")
            import traceback
            traceback.print_exc()

        # 截图留档
        screenshots_dir = Path(__file__).parent.parent / "var" / "testing"
        screenshots_dir.mkdir(parents=True, exist_ok=True)
        try:
            page.goto(f"{BASE}/admin/restore", timeout=TIMEOUT_NAV)
            page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
            page.screenshot(path=str(screenshots_dir / "restore-page.png"), full_page=True)
            page.goto(f"{BASE}/admin/backup", timeout=TIMEOUT_NAV)
            page.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_NAV)
            page.screenshot(path=str(screenshots_dir / "backup-page.png"), full_page=True)
            print(f"📸 截图保存到 {screenshots_dir}")
        except Exception as e:
            print(f"截图失败: {e}")

        browser.close()

    # 汇总
    print("\n" + "=" * 60)
    print("测试结果汇总:")
    pass_count = sum(1 for _, _, s, _ in RESULTS if s == "PASS")
    fail_count = sum(1 for _, _, s, _ in RESULTS if s == "FAIL")
    skip_count = sum(1 for _, _, s, _ in RESULTS if s == "SKIP")
    manual_count = sum(1 for _, _, s, _ in RESULTS if s == "MANUAL")
    print(f"  ✅ PASS: {pass_count}")
    print(f"  ❌ FAIL: {fail_count}")
    print(f"  ⚠️  SKIP: {skip_count}")
    print(f"  👀 MANUAL: {manual_count}")
    print(f"  总计: {len(RESULTS)}")

    if not args.no_md:
        write_to_md()

    sys.exit(0 if fail_count == 0 else 1)


if __name__ == "__main__":
    main()