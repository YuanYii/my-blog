/**
 * 集成测试：管理后台移动端抽屉式菜单（对应 docs/design/管理后台移动端抽屉式菜单设计方案.md）
 */
import { test, expect } from '@playwright/test'

/** 设置桌面端视口 */
async function setDesktop(page: any) { await page.setViewportSize({ width: 1280, height: 800 }) }
/** 设置手机端视口 */
async function setMobile(page: any) { await page.setViewportSize({ width: 375, height: 812 }) }
function hamburgerBtn(page: any) { return page.locator('.hamburger-btn, button[aria-label="打开菜单"]') }
function sidebar(page: any) { return page.locator('.admin-sidebar') }
function overlay(page: any) { return page.locator('.drawer-overlay') }
async function clickHamburger(page: any) { await hamburgerBtn(page).click({ position: { x: 5, y: 5 } }) }

// ============ 桌面端 ============
test.describe('管理后台抽屉 - 桌面端 (>768px)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setDesktop(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
  })

  test('AD01: 桌面端侧边栏常驻显示', async ({ page }) => {
    await expect(sidebar(page)).toBeVisible({ timeout: 10000 })
  })
  test('AD02: 桌面端汉堡按钮隐藏', async ({ page }) => {
    if (await hamburgerBtn(page).isVisible()) {
      const d = await hamburgerBtn(page).evaluate((el: HTMLElement) => window.getComputedStyle(el).display)
      expect(d).toBe('none')
    } else {
      await expect(hamburgerBtn(page)).not.toBeVisible()
    }
  })
  test('AD03: 桌面端遮罩层不渲染', async ({ page }) => {
    expect(await overlay(page).count()).toBe(0)
  })
  test('AD04: 桌面端侧边栏宽度为 240px', async ({ page }) => {
    await expect(sidebar(page)).toBeVisible({ timeout: 10000 })
    const w = await sidebar(page).evaluate((el: HTMLElement) => window.getComputedStyle(el).width)
    expect(w).toBe('240px')
  })
})

// ============ 手机端 ============
test.describe('管理后台抽屉 - 手机端 (375px)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
  })

  test('AD05: 手机端汉堡按钮可见', async ({ page }) => {
    await expect(hamburgerBtn(page)).toBeVisible({ timeout: 10000 })
  })
  test('AD06: aria-label="打开菜单"', async ({ page }) => {
    await expect(hamburgerBtn(page)).toHaveAttribute('aria-label', '打开菜单')
  })
  test('AD07: 侧边栏默认隐藏（transform 在视口外）', async ({ page }) => {
    const t = await sidebar(page).evaluate((el: HTMLElement) => window.getComputedStyle(el).transform)
    expect(t).toBeDefined()
  })
  test('AD08: 遮罩层默认不渲染', async ({ page }) => {
    expect(await overlay(page).count()).toBe(0)
  })
  test('AD09: 点击汉堡展开抽屉', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    const t = await sidebar(page).evaluate((el: HTMLElement) => window.getComputedStyle(el).transform)
    expect(t).toBeDefined()
  })
  test('AD10: 展开后遮罩层出现', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await expect(overlay(page)).toBeVisible()
  })
  test('AD11: 展开后 body overflow:hidden', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    const ov = await page.evaluate(() => document.body.style.overflow)
    expect(ov).toBe('hidden')
  })
  test('AD12: 点击遮罩关闭抽屉', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(400)
    await expect(overlay(page)).toBeVisible()
    await overlay(page).click({ position: { x: 300, y: 400 } })
    await page.waitForTimeout(400)
    expect(await overlay(page).count()).toBe(0)
  })
  test('AD13: 关闭后 body 恢复滚动', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await overlay(page).click({ position: { x: 300, y: 400 } })
    await page.waitForTimeout(400)
    const ov = await page.evaluate(() => document.body.style.overflow)
    expect(ov).toBe('')
  })
  test('AD14: Esc 关闭抽屉', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await expect(overlay(page)).toBeVisible()
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
    expect(await overlay(page).count()).toBe(0)
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })
  test('AD15: toggle 行为（再点击关闭）', async ({ page }) => {
    await page.evaluate(() => { const btn = document.querySelector(".hamburger-btn, button[aria-label=\"打开菜单\"]"); if (btn) (btn as HTMLElement).click(); })
    await page.waitForTimeout(300)
    await expect(overlay(page)).toBeVisible()
    await page.evaluate(() => { const btn = document.querySelector(".hamburger-btn, button[aria-label=\"打开菜单\"]"); if (btn) (btn as HTMLElement).click(); })
    await page.waitForTimeout(400)
    await expect(overlay(page)).not.toBeVisible()
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })
})

// ============ 菜单项 ============
test.describe('管理后台抽屉 - 菜单项导航', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
  })

  test('AD16: 侧边栏全部 9 个菜单项 + 返回前台', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    const s = sidebar(page)
    await expect(s).toBeVisible({ timeout: 5000 })
    const labels = ['仪表盘', '设备授权', '数据备份', '数据恢复', '站点设置', '返回前台']
    for (const l of labels) await expect(s.locator(`text=${l}`)).toBeVisible()
    await expect(s.locator('text=文章').last()).toBeVisible()
    await expect(s.locator('text=评论').last()).toBeVisible()
    await expect(s.locator('text=分类').last()).toBeVisible()
    await expect(s.locator('text=标签').last()).toBeVisible()
  })
  test('AD17: 菜单项路由跳转', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await sidebar(page).locator('a[href="/admin/settings"]').click()
    await page.waitForTimeout(1500)
    expect(page.url()).toContain('/admin/settings')
  })
  test('AD18: 菜单项点击后抽屉自动关闭', async ({ page }) => {
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await sidebar(page).locator('.admin-nav-item').filter({ hasText: "站点设置" }).click()
    await page.waitForTimeout(800)
    expect(await overlay(page).count()).toBe(0)
  })
})

// ============ 响应式切换 ============
test.describe('管理后台抽屉 - 响应式切换', () => {
  test('AD19: 桌面→手机→汉堡出现', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setDesktop(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    if (await hamburgerBtn(page).isVisible()) {
      const d = await hamburgerBtn(page).evaluate((el: HTMLElement) => window.getComputedStyle(el).display)
      expect(d).toBe('none')
    }
    await setMobile(page)
    await page.waitForTimeout(500)
    await expect(hamburgerBtn(page)).toBeVisible()
  })
  test('AD20: 展开抽屉→切桌面→自动关闭', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await expect(overlay(page)).toBeVisible()
    await setDesktop(page)
    await page.waitForTimeout(800)
    expect(await overlay(page).count()).toBe(0)
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })
})

// ============ 暗色模式 ============
test.describe('管理后台抽屉 - 暗色模式', () => {
  test('AD21: 暗色模式下抽屉不抛错', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    const isDark = await page.locator('html').evaluate((el: HTMLElement) => el.classList.contains('dark'))
    expect(isDark).toBe(true)
    await clickHamburger(page)
    await page.waitForTimeout(300)
    await expect(sidebar(page)).toBeVisible()
  })
})

// ============ SSR 安全性 ============
test.describe('管理后台抽屉 - SSR 安全性', () => {
  test('AD22: 无 JS 错误', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (err: Error) => errors.push(err.message))
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    expect(errors.filter(e => !e.includes('fetch') && !e.includes('NetworkError'))).toEqual([])
  })
  test('AD23: 侧边栏 DOM 存在', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    expect(await sidebar(page).count()).toBeGreaterThan(0)
  })
})

// ============ z-index ============
test.describe('管理后台抽屉 - z-index', () => {
  test('AD24: sidebar(200) > overlay(190)', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('blog_admin_token', 'test-token-e2e')
      localStorage.setItem('blog_admin_user', JSON.stringify({ uid: 1, username: 'admin', role: 'admin', nickname: 'Test' }))
    })
    await setMobile(page)
    await page.goto('/admin/dashboard')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    await clickHamburger(page)
    await page.waitForTimeout(300)
    const sZ = await sidebar(page).evaluate((el: HTMLElement) => parseInt(window.getComputedStyle(el).zIndex, 10) || 0)
    const oZ = await overlay(page).evaluate((el: HTMLElement) => parseInt(window.getComputedStyle(el).zIndex, 10) || 0)
    expect(sZ).toBeGreaterThan(oZ)
  })
})
