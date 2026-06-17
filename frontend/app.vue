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
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
