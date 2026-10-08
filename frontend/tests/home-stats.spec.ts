import { test, expect } from '@playwright/test';

test.describe('首页 Bento 统计卡片与总字数展示', () => {
  test('总字数正常展示且不为 0k', async ({ page }) => {
    // 监听关键接口请求
    const statsPromise = page.waitForResponse(response =>
      response.url().includes('/api/v1/articles/stats') && response.status() === 200
    );

    await page.goto('http://localhost:28000/', { waitUntil: 'networkidle', timeout: 30000 });

    const statsRes = await statsPromise;
    const statsJson = await statsRes.json();
    console.log('GET /api/v1/articles/stats Response:', statsJson);

    expect(statsJson.code).toBe(200);
    expect(statsJson.data.totalWordCount).toBeGreaterThan(0);

    // 验证 Bento 卡片
    await page.waitForSelector('.bento', { timeout: 10000 });
    const bentoItems = await page.$$eval('.bento-item', items =>
      items.map(it => ({
        label: it.querySelector('.bento-label')?.textContent?.trim(),
        value: it.querySelector('.bento-value')?.textContent?.trim(),
        delta: it.querySelector('.bento-delta')?.textContent?.trim(),
      }))
    );

    console.log('Bento Cards Rendered on Homepage:', bentoItems);

    const wordsCard = bentoItems.find(it => it.label === '总字数');
    expect(wordsCard).toBeDefined();
    expect(wordsCard?.value).not.toBe('0k');
    expect(wordsCard?.value).not.toBe('0');
    // 容器内 35739 字，格式化后应为 35.7k
    expect(wordsCard?.value).toBe('35.7k');
  });
});
