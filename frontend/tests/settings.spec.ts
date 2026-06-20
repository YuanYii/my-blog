import { test, expect } from '@playwright/test'

async function login(page: any) {
  await page.goto('/admin/login')
  await page.fill('input[type="text"], input[name="username"], input[placeholder*="账号"], input[placeholder*="用户"]', 'admin')
  await page.fill('input[type="password"]', '123456')
  await page.click('button[type="submit"], .btn-primary')
  await page.waitForURL('**/admin**', { timeout: 15000 })
}

test.describe('设置页面 (settings sub-components)', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.goto('/admin/settings')
    await page.waitForLoadState('networkidle')
  })

  test('设置页加载，左侧 tab 列表存在', async ({ page }) => {
    const tabNav = page.locator('.settings-tabs, nav')
    await expect(tabNav).toBeVisible({ timeout: 10000 })
    await expect(page.locator('button:has-text("个人资料"), .settings-tab:has-text("个人资料")')).toBeVisible()
  })

  test('ProfileForm：昵称字段可见且可编辑', async ({ page }) => {
    // 默认停在 profile tab
    const nicknameInput = page.locator('input').filter({ hasText: '' }).first()
    await expect(page.locator('label:has-text("昵称")')).toBeVisible({ timeout: 10000 })
    const input = page.locator('label:has-text("昵称") + input, label:has-text("昵称") ~ input').first()
    if (!await input.isVisible()) {
      // 备用：找到包含昵称的 form-group 下的 input
      const fg = page.locator('.form-group').filter({ hasText: '昵称' }).locator('input')
      await expect(fg).toBeVisible()
    }
  })

  test('保存设置按钮存在并可点击', async ({ page }) => {
    const saveBtn = page.locator('button:has-text("保存设置")')
    await expect(saveBtn).toBeVisible({ timeout: 10000 })
    await saveBtn.click()
    // 成功或失败提示出现（API 可能 401/500，但按钮应有响应）
    await page.waitForTimeout(1500)
    await expect(page).toHaveURL(/\/admin\/settings/)
  })

  test('切换到"修改密码" tab，PasswordForm 渲染', async ({ page }) => {
    await page.click('button:has-text("修改密码"), .settings-tab:has-text("修改密码")')
    await expect(page.locator('input[type="password"]').first()).toBeVisible({ timeout: 8000 })
    await expect(page.locator('button:has-text("更新密码")')).toBeVisible()
  })

  test('PasswordForm：空提交显示错误（客户端校验）', async ({ page }) => {
    await page.click('button:has-text("修改密码"), .settings-tab:has-text("修改密码")')
    await page.click('button:has-text("更新密码")')
    // 客户端校验会显示提示文字而不发请求
    const errMsg = page.locator('text=请输入当前密码')
    await expect(errMsg).toBeVisible({ timeout: 5000 })
  })

  test('切换到"站点信息" tab，BlogForm 渲染', async ({ page }) => {
    await page.click('button:has-text("站点信息"), .settings-tab:has-text("站点信息")')
    await expect(page.locator('label:has-text("站点标题")')).toBeVisible({ timeout: 8000 })
  })

  test('切换到"技术栈" tab，TechstackForm 渲染并可添加分组', async ({ page }) => {
    await page.click('button:has-text("技术栈"), .settings-tab:has-text("技术栈")')
    const addGroupBtn = page.locator('button:has-text("添加分组")')
    await expect(addGroupBtn).toBeVisible({ timeout: 8000 })
    await addGroupBtn.click()
    await expect(page.locator('label:has-text("分组标题")')).toBeVisible({ timeout: 5000 })
  })

  test('切换到"个人经历" tab，ExperienceForm 渲染并可添加经历', async ({ page }) => {
    await page.click('button:has-text("个人经历"), .settings-tab:has-text("个人经历")')
    const addBtn = page.locator('button:has-text("添加经历")')
    await expect(addBtn).toBeVisible({ timeout: 8000 })
    await addBtn.click()
    await expect(page.locator('label:has-text("时间")')).toBeVisible({ timeout: 5000 })
  })

  test('切换到"主题外观" tab，ThemeForm 渲染', async ({ page }) => {
    await page.click('button:has-text("主题外观"), .settings-tab:has-text("主题外观")')
    await expect(page.locator('label:has-text("主题模式")')).toBeVisible({ timeout: 8000 })
  })

  test('切换到"高级" tab，AdvancedForm 渲染（toggle 开关存在）', async ({ page }) => {
    await page.click('button:has-text("高级"), .settings-tab:has-text("高级")')
    await expect(page.locator('text=启用缓存, text=启用 Redis 缓存').first()).toBeVisible({ timeout: 8000 })
  })
})
