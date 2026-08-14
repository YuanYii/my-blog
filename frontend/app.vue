<script setup lang="ts">
const config = useRuntimeConfig()

// 2026-06-12 新增：从站点设置拉 blog.title / blog.description 注入 <head>。
// 之前浏览器 tab 标题、SEO description 都硬编码在 nuxt.config.ts，admin 在后台改 blog.title 完全不生效。
// useAsyncData key 与 NavBar / SiteFooter 一致 → Nuxt SSR 自动 dedupe，整页只发一次。
//
// 2026-06-22 修复（BUG-XXX 顶层 await 双倍阻塞）：
// 之前 NavBar.vue 也自己 await useAsyncData('site-blog', ...) 拉同一份数据。
// Nuxt dedupe 的是 promise（只发一次请求），但**两个 await 都得等 promise resolve**——
// 组件 setup 都阻塞。后端 /public/settings/blog 超时 → 整页白屏。
// 修：app.vue 顶层 await 一次（仍然在 root setup 里，Nuxt 会注入到 SSR payload）；
// 其它组件用 useState 拿 reactive ref，不再二次 await。
const { get } = usePublicApi()
const { data: blogRes } = await useAsyncData('site-blog', () => get<any>('/public/settings/blog'))
// 全局共享 reactive 引用 —— NavBar / SiteFooter 通过 useState 拿同一个 ref
const blogShared = useState<any>('site-blog-data', () => blogRes.value?.data || {})
watch(blogRes, (v) => { blogShared.value = v?.data || {} }, { immediate: true })
const blog = blogShared

// 2026-06-27 DEV-001：站点主题（theme）落地——读取后注入 CSS 变量与 dark class / font class
useSiteTheme()
// 2026-06-27 DEV-002：站点偏好（language / timezone / density / codeTheme）落地
useSitePreferences()
// 2026-06-28 OPT-001/002（autopush）：站点能力开关显隐（enableRss / enableSearch）
useSiteFlags()

// 让 <title> 跟 blog.title 联动；description 用 computed 覆盖 nuxt.config.ts 里的静态默认值
const siteTitle = computed(() => blog.value?.title || '加载中')
const siteDesc = computed(() => blog.value?.description || '后端工程师的博客 - 技术、读书、生活')

useHead({
  title: siteTitle,
  meta: [
    { name: 'description', content: siteDesc }
  ],
  // 防止首屏闪白/闪黑（FOUC）：在客户端 JS 接管之前同步设置 dark class。
  // 支持 'auto'（跟随系统 matchMedia）、'light'、'dark'。
  // 注入到 <head> 同步执行，保证初次渲染即为准确模式。
  script: [
    {
      tagPosition: 'head',
      innerHTML: `(function(){try{var t=localStorage.getItem('theme')||'auto';var isDark=t==='dark'||(t==='auto'&&window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches);if(isDark){document.documentElement.classList.add('dark')}else{document.documentElement.classList.remove('dark')}}catch(e){document.documentElement.classList.add('dark')}})();`
    }
  ]
})

// ============= 2026-06-18 新增：动态 favicon 跟 NavBar 红方块 C 保持一致 =============
//
// 行为跟 NavBar.vue:11-12 完全一致：
//   - 后端 site_settings.blog.logo 有值时优先用 logo 图片
//   - 没有 logo 时用 Canvas 画「圆角方块 + 主题色 + title 首字母」
//
// 跟 NavBar 行为完全镜像：admin 改站名 / 换 logo，favicon 自动跟着变（不需要 rebuild）。
//
// CORS 兜底：blog.logo 通常是外链图床（OSS/七牛），Canvas drawImage 跨域图会污染画布
// → catch 后 fallback 首字母方案，保证 favicon 一定能渲染。
//
// client only：SSR 阶段 document/navigator 不存在；favicon 是浏览器行为，SSR 注入没意义。
onMounted(async () => {
  // 2026-06-22 修复（BUG-XXX favicon 全链 try-catch）：
  // 之前各分支（loadImageWithCors / imageToDataUrl / renderLetterIcon）内部各自 try-catch，
  // 但外层 onMounted 没有 try-catch——getComputedStyle(documentElement) 失败、
  // canvas.toDataURL() 在极旧浏览器抛异常等情况下，整个 onMounted 静默失败。
  // 加外层 try-catch：失败仅 console.warn，不影响页面其他功能。
  try {
    await renderFavicon()
  } catch (e) {
    console.warn('[favicon] 渲染失败，使用浏览器默认 favicon:', e)
  }
})

async function renderFavicon() {
  const title = blog.value?.title || '加载中'
  const logoUrl = blog.value?.logo
  let dataUrl: string | null = null

  // ---- 分支 1: 有 logo URL → 试着用图片（CORS 失败就跳分支 2）----
  if (logoUrl) {
    try {
      const img = await loadImageWithCors(logoUrl)
      dataUrl = await imageToDataUrl(img, 64)
    } catch (e) {
      console.warn('[favicon] logo 加载失败（CORS 或 404），fallback 首字母:', logoUrl, e)
    }
  }

  // ---- 分支 2: 无 logo / logo 加载失败 → Canvas 画首字母方块 ----
  if (!dataUrl) {
    dataUrl = renderLetterIcon(title, 64)
  }

  // ---- 替换 <link rel="icon"> ----
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!link) {
    link = document.createElement('link')
    link.rel = 'icon'
    link.type = 'image/png'
    document.head.appendChild(link)
  }
  link.href = dataUrl
}

/** 加载图片（处理 CORS：图床若带 Access-Control-Allow-Origin 就能直接画） */
function loadImageWithCors(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'  // 触发 CORS 请求；图床没返 ACAO 头就会 fail
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('image load failed (likely CORS)'))
    img.src = url
  })
}

/** 把 <img> 画到 64x64 canvas，return dataURL */
function imageToDataUrl(img: HTMLImageElement, size: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = size
    const ctx = canvas.getContext('2d')
    if (!ctx) return reject(new Error('canvas 2d context unavailable'))
    // 居中裁剪成正方形（避免图床返回长方形导致 favicon 变形）
    const w = img.naturalWidth
    const h = img.naturalHeight
    const side = Math.min(w, h)
    const sx = (w - side) / 2
    const sy = (h - side) / 2
    ctx.drawImage(img, sx, sy, side, side, 0, 0, size, size)
    try {
      resolve(canvas.toDataURL('image/png'))
    } catch (e) {
      reject(e)
    }
  })
}

/**
 * 跟 NavBar.vue:10-12 视觉完全一致：
 *   - 圆角方块（rounded-lg ≈ 8px，64px 画布上 ≈ 12）
 *   - 背景色 = --color-primary CSS 变量（主题色，跟着 light/dark 变）
 *   - 文字白色加粗 + 首字母
 */
function renderLetterIcon(title: string, size: number): string {
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')
  if (!ctx) return ''  // 极端兜底（老浏览器），favicon 留空 → 浏览器用默认

  const color = getComputedStyle(document.documentElement)
                  .getPropertyValue('--color-primary').trim() || '#e85d4a'
  const letter = (title[0] || '?').toUpperCase()

  // 圆角矩形（Safari 16.4+ 支持 roundRect；不支持的话降级到 fillRect）
  ctx.fillStyle = color
  if (typeof (ctx as any).roundRect === 'function') {
    ctx.beginPath()
    ;(ctx as any).roundRect(0, 0, size, size, size * 0.18)
    ctx.fill()
  } else {
    ctx.fillRect(0, 0, size, size)
  }

  // 首字母
  ctx.fillStyle = '#ffffff'
  ctx.font = `bold ${Math.round(size * 0.55)}px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(letter, size / 2, size / 2 + size * 0.04)  // 视觉居中微调
  return canvas.toDataURL('image/png')
}
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
