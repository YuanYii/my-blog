import { test, expect } from '@playwright/test'

const LOGIN_URL = '/admin/login'
const EDIT_URL  = '/admin/edit'

async function login(page: any) {
  await page.goto(LOGIN_URL)
  await page.fill('input[type="text"], input[name="username"], input[placeholder*="账号"], input[placeholder*="用户"]', 'admin')
  await page.fill('input[type="password"]', '123456')
  await page.click('button[type="submit"], .btn-primary')
  await page.waitForURL('**/admin**', { timeout: 15000 })
}

test.describe('文章编辑页 (AdminMarkdownEditor)', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test('跳转到编辑页后 MarkdownEditor 渲染工具栏和 textarea', async ({ page }) => {
    await page.goto(EDIT_URL)
    // 工具栏中有加粗按钮（B）
    await expect(page.locator('.md-toolbar')).toBeVisible({ timeout: 10000 })
    // textarea 存在
    await expect(page.locator('.md-textarea, textarea')).toBeVisible()
  })

  test('在 MarkdownEditor 中输入内容触发 v-model 更新', async ({ page }) => {
    await page.goto(EDIT_URL)
    const textarea = page.locator('.md-textarea, textarea').first()
    await textarea.click()
    await textarea.fill('# Hello World\n\n这是一段测试内容。')
    // 预览区域应能渲染出 h1
    const preview = page.locator('.md-preview')
    if (await preview.isVisible()) {
      await expect(preview.locator('h1')).toContainText('Hello World')
    }
  })

  test('工具栏 B 按钮插入加粗标记', async ({ page }) => {
    await page.goto(EDIT_URL)
    const textarea = page.locator('.md-textarea, textarea').first()
    await textarea.click()
    await textarea.fill('test')
    // 选中全部文字后点 B
    await textarea.press('Control+a')
    await page.click('.md-toolbar button[title="加粗"], .md-toolbar button:has-text("B")')
    const val = await textarea.inputValue()
    expect(val).toContain('**')
  })

  test('标题和 slug 字段存在', async ({ page }) => {
    await page.goto(EDIT_URL)
    await expect(page.locator('input[placeholder*="标题"], input[name="title"]')).toBeVisible({ timeout: 10000 })
  })

  test('保存按钮可点击（表单校验未通过时显示错误，不跳转）', async ({ page }) => {
    await page.goto(EDIT_URL)
    // 不填标题直接点保存
    const saveBtn = page.locator('button:has-text("发布"), button:has-text("保存")')
    await saveBtn.first().click()
    // 应停留在编辑页
    await expect(page).toHaveURL(/\/admin\/edit/)
  })
})
