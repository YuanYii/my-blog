#!/usr/bin/env node
/**
 * Build-time 脚本：从后端 API 拉取所有文章 slug + 公开页路由，生成 routes.json
 * nuxt.config.ts 在 build 时读这个文件作为 prerender routes
 *
 * 用法：
 *   node scripts/fetch-routes.js
 *   # 或带环境变量：
 *   API_BASE=https://yourname.com/api/v1 node scripts/fetch-routes.js
 *
 * 输出：
 *   frontend/.routes.json  （被 nuxt.config.ts 读取）
 *
 * 失败处理：
 *   - 后端不通：写空数组（只预渲染入口公开页，post 走 SPA fallback）
 *   - 单页 404：跳过该 slug 不阻塞
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUTPUT = resolve(__dirname, '..', '.routes.json')

const API_BASE = process.env.API_BASE
  || process.env.NUXT_PUBLIC_API_BASE
  || 'http://localhost:8080/api/v1'

// 固定入口公开页
// 2026-06-30 BUG-001：补 /search 路由（与 docs/design/博客系统设计方案.md §5.9 / §11 文档化一致）
const STATIC_ROUTES = ['/', '/about', '/archives', '/tags', '/search']

async function fetchJson(url, timeout = 10000) {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), timeout)
  try {
    const res = await fetch(url, { signal: ctrl.signal })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return await res.json()
  } finally {
    clearTimeout(t)
  }
}

async function fetchAllArticleSlugs() {
  const slugs = []
  let page = 1
  const size = 100
  let total = Infinity

  while (slugs.length < total) {
    const data = await fetchJson(
      `${API_BASE}/articles?page=${page}&size=${size}`
    )
    if (!data || data.code !== 200) {
      console.warn(`[fetch-routes] /articles page=${page} 返回非 200，停止`)
      break
    }
    const records = data.data?.records || []
    total = data.data?.total || 0
    for (const r of records) {
      if (r.slug && r.status === 1) {
        slugs.push(`/post/${r.slug}`)
      }
    }
    if (records.length < size) break
    page++
    if (page > 100) break // 安全上限：100 页 = 10000 篇
  }
  return slugs
}

async function fetchAllTagSlugs() {
  try {
    const data = await fetchJson(`${API_BASE}/articles/tags`)
    if (data?.code !== 200) return []
    const tags = data.data || []
    return tags
      .filter(t => t.slug)
      .map(t => `/tags/${t.slug}`)
  } catch (e) {
    console.warn(`[fetch-routes] 拉取 tags 失败：${e.message}`)
    return []
  }
}

async function fetchAllCategorySlugs() {
  try {
    const data = await fetchJson(`${API_BASE}/articles/categories`)
    if (data?.code !== 200) return []
    const cats = data.data || []
    return cats
      .filter(c => c.slug)
      .map(c => `/categories/${c.slug}`)
  } catch (e) {
    console.warn(`[fetch-routes] 拉取 categories 失败：${e.message}`)
    return []
  }
}

async function main() {
  const start = Date.now()
  const routes = [...STATIC_ROUTES]

  // 并行拉三类数据，失败不阻塞
  const [postSlugs, tagSlugs, catSlugs] = await Promise.all([
    fetchAllArticleSlugs().catch(e => {
      console.warn(`[fetch-routes] 拉取 articles 失败：${e.message}（post 页面将走 SPA fallback）`)
      return []
    }),
    fetchAllTagSlugs(),
    fetchAllCategorySlugs(),
  ])

  routes.push(...postSlugs, ...tagSlugs, ...catSlugs)

  mkdirSync(dirname(OUTPUT), { recursive: true })
  writeFileSync(OUTPUT, JSON.stringify(routes, null, 2))

  const elapsed = Date.now() - start
  console.log(`[fetch-routes] 写入 ${routes.length} 条路由到 ${OUTPUT}（${elapsed}ms）`)
  console.log(`  入口公开页: ${STATIC_ROUTES.length}`)
  console.log(`  文章页: ${postSlugs.length}`)
  console.log(`  标签页: ${tagSlugs.length}`)
  console.log(`  分类页: ${catSlugs.length}`)
}

main().catch(e => {
  console.error(`[fetch-routes] 致命错误：${e.message}`)
  // 写空文件（不阻塞构建）
  writeFileSync(OUTPUT, JSON.stringify(STATIC_ROUTES, null, 2))
  process.exit(0)  // 不让构建失败
})
