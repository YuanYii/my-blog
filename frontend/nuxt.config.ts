// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2024-08-01',
  devtools: { enabled: true },

  modules: ['@nuxtjs/tailwindcss'],

  css: ['~/assets/css/main.css'],

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

// 2026-06-12 备注：nuxt 3.21 + routeRules proxy 触发 dev SSR 子进程 socket 异常
// （ENOENT .nuxt-tmp/nuxt-vite-node-*.sock）
// ——生产用 nginx 静态 serve /uploads/ 即可；dev 暂时让前端直接请求后端 8080 端口的文件
// （profile.avatar 改为绝对 URL 后端域名）
// 后续如需 dev 代理，研究清楚 routeRules 触发 SSR 子进程 socket 异常根因再启用
