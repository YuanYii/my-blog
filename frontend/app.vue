<script setup lang="ts">
const config = useRuntimeConfig()

// 2026-06-12 新增：从站点设置拉 blog.title / blog.description 注入 <head>。
// 之前浏览器 tab 标题、SEO description 都硬编码在 nuxt.config.ts，admin 在后台改 blog.title 完全不生效。
// useAsyncData key 与 NavBar / SiteFooter 一致 → Nuxt SSR 自动 dedupe，整页只发一次。
const { get } = usePublicApi()
const { data: blogRes } = await useAsyncData('site-blog', () => get<any>('/public/settings/blog'))
const blog = computed(() => blogRes.value?.data || {})

// 让 <title> 跟 blog.title 联动；description 用 computed 覆盖 nuxt.config.ts 里的静态默认值
const siteTitle = computed(() => blog.value?.title || '加载中')
const siteDesc = computed(() => blog.value?.description || '后端工程师的博客 - 技术、读书、生活')

useHead({
  title: siteTitle,
  meta: [
    { name: 'description', content: siteDesc }
  ],
  // 防止首屏闪白：在客户端 JS 接管之前同步设置 dark class。
  // 默认深色，仅当用户曾显式选过 light 时才走浅色。
  // 这里必须用 useHead 注入到 <head>，且不能走异步/动态逻辑，
  // 否则会先渲染浅色再切到深色（FOUC）。
  script: [
    {
      tagPosition: 'head',
      innerHTML: `(function(){try{var t=localStorage.getItem('theme');if(t==='light'){document.documentElement.classList.remove('dark')}else{document.documentElement.classList.add('dark')}}catch(e){document.documentElement.classList.add('dark')}})();`
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
})

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
