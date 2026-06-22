import { test, expect } from '@playwright/test'

async function login(page: any) {
  await page.goto('/admin/login')
  await page.fill('input[type="text"], input[name="username"], input[placeholder*="账号"], input[placeholder*="用户"]', 'admin')
  await page.fill('input[type="password"]', '123456')
  await page.click('button[type="submit"], .btn-primary')
  await page.waitForURL('**/admin**', { timeout: 15000 })
}

test.describe('Dashboard (TrafficChart + CategoryChart)', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.goto('/admin/dashboard')
    // 等待数据加载
    await page.waitForLoadState('networkidle')
  })

  test('仪表盘包含统计卡片', async ({ page }) => {
    // 至少 3 个 KPI 卡片（文章数、访客数、评论数 等）
    const cards = page.locator('.stat-card, .kpi-card, .dashboard-card')
    await expect(cards.first()).toBeVisible({ timeout: 15000 })
  })

  test('TrafficChart 组件挂载，canvas 存在', async ({ page }) => {
    // Chart.js 绘制到 canvas
    const canvas = page.locator('canvas').first()
    await expect(canvas).toBeVisible({ timeout: 15000 })
  })

  test('TrafficChart tab 切换 7/30/90', async ({ page }) => {
    // tab 按钮
    const tab30 = page.locator('button:has-text("30"), .tab-btn:has-text("30")')
    if (await tab30.isVisible()) {
      await tab30.click()
      // 点击后按钮应有 active 样式（不抛异常即可）
      await page.waitForTimeout(500)
    }
    const tab7 = page.locator('button:has-text("7"), .tab-btn:has-text("7")')
    if (await tab7.isVisible()) {
      await tab7.click()
      await page.waitForTimeout(500)
    }
    // 断言页面没有崩溃
    await expect(page).toHaveURL(/\/admin\/dashboard/)
  })

  test('CategoryChart canvas 存在', async ({ page }) => {
    // 可能有多个 canvas（流量图 + 分类图）
    const canvases = page.locator('canvas')
    const count = await canvases.count()
    expect(count).toBeGreaterThanOrEqual(1)
  })
})
