// https://nuxt.com/docs/api/configuration/nuxt-config
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

// build-time：拉取所有公开页路由（post / tag / category）
// 由 scripts/fetch-routes.js 在 build 前生成 .routes.json
// 失败兜底：只预渲染入口公开页
function loadPrerenderRoutes() {
  const fallback = ['/', '/about', '/archives', '/tags']
  try {
    const routesFile = resolve(process.cwd(), '.routes.json')
    if (!existsSync(routesFile)) {
      console.warn('[nuxt.config] .routes.json 不存在，使用 fallback 路由')
      return fallback
    }
    const routes = JSON.parse(readFileSync(routesFile, 'utf-8'))
    console.log(`[nuxt.config] 加载 ${routes.length} 条 prerender 路由`)
    return routes
  } catch (e) {
    console.warn(`[nuxt.config] 加载 .routes.json 失败: ${e.message}，使用 fallback`)
    return fallback
  }
}

export default defineNuxtConfig({
  compatibilityDate: '2024-08-01',
  devtools: { enabled: false },
  // 2026-06-17 关掉 SSR，改纯 SPA：开发期冷启动 / HMR 体验大幅提升
  // 2026-06-17 v2.7.0 进一步改全静态（nuxt generate）：服务端只跑 Spring Boot jar + nginx serve 静态文件
  // 公开页 build 时预渲染成 HTML，admin 走 SPA 客户端渲染
  // 1C2G 收益：前端不再跑 node 服务，省 150-250MB 内存
  ssr: false,
  modules: ['@nuxtjs/tailwindcss'],
  css: ['~/assets/css/main.css'],
  // ---------- 全静态预渲染配置 ----------
  // build 时把公开页生成静态 HTML，admin 排除（登录后才看、且因用户而异）
  nitro: {
    prerender: {
      // 入口 + 后端拉到的所有 post / tag / category slug
      routes: loadPrerenderRoutes(),
      // 兜底：自动从首页爬 <a> 链接（虽然 ssr:false 下首页是空壳，主要靠显式 routes）
      crawlLinks: true,
      // 某个 slug 404 不阻塞整次构建（防止删了文章但首页链接还在）
      failOnError: false,
      // 排除 admin（不预渲染）+ API（防止尝试预渲染）
      ignore: [
        '/admin',
        '/admin/**',
        '/api/**',
      ],
    },
  },
  app: {
    head: {
      title: 'Yuan Yi · 个人博客',
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        { name: 'description', content: '后端工程师的博客 - 技术、读书、生活' }
      ],
      link: [
        { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' },
        { rel: 'stylesheet', href: 'https://fonts.googleapis.com/css2?family=DM+Serif+Display&family=Outfit:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&family=Noto+Serif+SC:wght@500;700&display=swap' }
      ],
      script: [
        { src: 'https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js', defer: true }
      ]
    }
  },
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080/api/v1'
    }
  }
})
