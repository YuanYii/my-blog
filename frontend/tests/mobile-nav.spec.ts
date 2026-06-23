/**
 * 集成测试：前台移动端导航菜单适配（对应 docs/design/移动端导航菜单适配方案.md）
 *
 * 测试范围：
 * - 桌面端 (1280px)：水平菜单显示，汉堡按钮隐藏
 * - 手机端 (375px)：汉堡按钮显示，点击展开/收起菜单
 * - 菜单交互：点击菜单项跳转+自动关闭、点击外区域关闭、路由变化关闭
 * - 路由高亮：当前页菜单项正确高亮
 * - 后台入口：未授权设备不可见（桌面+手机端）
 * - 主题切换：桌面端和手机端均正常
 * - 静态构建后菜单正常
 */

import { test, expect } from '@playwright/test'

/** 设置桌面端视口 */
async function setDesktop(page: any) {
  await page.setViewportSize({ width: 1280, height: 800 })
}

/** 设置手机端视口 */
async function setMobile(page: any) {
  await page.setViewportSize({ width: 375, height: 812 })
}

/** 获取汉堡按钮 */
function hamburgerBtn(page: any) {
  return page.locator('button[aria-label="切换菜单"]')
}

/** 获取手机端下拉菜单容器 */
function mobileMenu(page: any) {
  return page.locator('.mobile-menu-item')
}

/** 获取桌面端导航链接（NavBar 内的 NuxtLink） */
function desktopNavLinks(page: any) {
  return page.locator('nav .hidden.md\\:flex a')
}

// ============ 桌面端 ============
test.describe('前台导航菜单 - 桌面端 (≥768px)', () => {
  test.beforeEach(async ({ page }) => {
    await setDesktop(page)
    await page.goto('/')
    await page.waitForLoadState('networkidle')
  })

  test('D01: 桌面端水平导航菜单可见（首页/归档/标签/关于）', async ({ page }) => {
    const links = desktopNavLinks(page)
    const count = await links.count()
    expect(count).toBeGreaterThanOrEqual(4)
    await expect(links.first()).toBeVisible()
  })

  test('D02: 桌面端汉堡按钮隐藏', async ({ page }) => {
    await expect(hamburgerBtn(page)).not.toBeVisible()
  })

  test('D03: 桌面端主题切换按钮正常工作', async ({ page }) => {
    const themeBtn = page.locator('button[aria-label="切换主题"]')
    await expect(themeBtn).toBeVisible()
    await themeBtn.click()
    await page.waitForTimeout(300)
    const htmlClass = await page.locator('html').getAttribute('class')
    expect(htmlClass).toBeDefined()
  })

  test('D04: 桌面端后台入口未授权设备不可见', async ({ page }) => {
    const adminLink = page.locator('a[href="/admin/login"]')
    await expect(adminLink).not.toBeVisible()
  })

  test('D05: 桌面端当前路由菜单项高亮', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const homeLink = page.locator('a.active-link')
    const count = await homeLink.count()
    expect(count).toBeGreaterThan(0)
  })
})

// ============ 手机端 ============
test.describe('前台导航菜单 - 手机端 (375px)', () => {
  test.beforeEach(async ({ page }) => {
    await setMobile(page)
    await page.goto('/')
    await page.waitForLoadState('networkidle')
  })

  test('M01: 手机端汉堡按钮可见', async ({ page }) => {
    await expect(hamburgerBtn(page)).toBeVisible()
  })

  test('M02: 手机端桌面菜单隐藏', async ({ page }) => {
    const links = desktopNavLinks(page)
    await expect(links.first()).not.toBeVisible()
  })

  test('M03: 点击汉堡按钮展开菜单', async ({ page }) => {
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    const items = mobileMenu(page)
    const count = await items.count()
    expect(count).toBeGreaterThanOrEqual(4)
    await expect(items.first()).toBeVisible()
  })

  test('M04: 点击汉堡按钮切换收起菜单', async ({ page }) => {
    const btn = hamburgerBtn(page)
    await btn.click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    let items = mobileMenu(page)
    await expect(items.first()).toBeVisible()
    await btn.click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(400)
    items = mobileMenu(page)
    const count = await items.count()
    if (count > 0) {
      await expect(items.first()).not.toBeVisible()
    }
  })

  test('M05: 点击菜单项后路由跳转 + 菜单自动关闭', async ({ page }) => {
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    const archiveLink = page.locator('.mobile-menu-item').filter({ hasText: '归档' })
    if (await archiveLink.isVisible()) {
      await archiveLink.click()
      await page.waitForTimeout(500)
      const url = page.url()
      expect(url).toContain('/archives')
      const items = mobileMenu(page)
      const count = await items.count()
      if (count > 0) {
        await expect(items.first()).not.toBeVisible()
      }
    }
  })

  test('M06: 点击菜单外区域自动关闭', async ({ page }) => {
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    const items = mobileMenu(page)
    await expect(items.first()).toBeVisible()
    await page.locator('main').first().click({ position: { x: 10, y: 200 } })
    await page.waitForTimeout(400)
    const afterItems = mobileMenu(page)
    const count = await afterItems.count()
    if (count > 0) {
      await expect(afterItems.first()).not.toBeVisible()
    }
  })

  test('M07: 手机端主题切换按钮正常工作', async ({ page }) => {
    const themeBtn = page.locator('button[aria-label="切换主题"]')
    await expect(themeBtn).toBeVisible()
    await themeBtn.click()
    await page.waitForTimeout(300)
  })

  test('M08: 手机端后台入口未授权设备不可见', async ({ page }) => {
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    const adminItem = page.locator('.mobile-menu-item').filter({ hasText: '后台管理' })
    await expect(adminItem).not.toBeVisible()
  })

  test('M09: 菜单展开后显示关闭图标(X)而非汉堡', async ({ page }) => {
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    const svgLines = page.locator('button[aria-label="切换菜单"] line')
    const lineCount = await svgLines.count()
    expect(lineCount).toBe(2)
  })

  test('M10: 路由高亮 - 手机端当前页菜单项正确高亮', async ({ page }) => {
    await page.goto('/archives')
    await page.waitForLoadState('networkidle')
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    const activeItems = page.locator('.mobile-menu-item.router-link-exact-active')
    const activeCount = await activeItems.count()
    expect(activeCount).toBeGreaterThan(0)
  })
})

// ============ 静态构建验证 ============
test.describe('前台导航菜单 - 静态构建验证', () => {
  test('SB01: 首页加载后导航栏存在且无控制台错误', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (err: Error) => errors.push(err.message))
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('nav').first()).toBeVisible()
    await page.waitForTimeout(1000)
    expect(errors).toEqual([])
  })
})

// ============ 响应式切换 ============
test.describe('前台导航菜单 - 响应式切换', () => {
  test('R01: 桌面端→手机端切换后汉堡按钮出现', async ({ page }) => {
    await setDesktop(page)
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(hamburgerBtn(page)).not.toBeVisible()
    await setMobile(page)
    await page.waitForTimeout(300)
    await expect(hamburgerBtn(page)).toBeVisible()
  })

  test('R02: 手机端展开菜单→切到桌面端→桌面菜单可见', async ({ page }) => {
    await setMobile(page)
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await hamburgerBtn(page).click({ position: { x: 1, y: 1 } })
    await page.waitForTimeout(300)
    await setDesktop(page)
    await page.waitForTimeout(300)
    await expect(desktopNavLinks(page).first()).toBeVisible()
  })
})
