<template>
  <nav class="sticky top-0 z-50 backdrop-blur-md" style="background: color-mix(in srgb, var(--color-bg) 85%, transparent); border-bottom: 1px solid var(--color-line);">
    <div class="max-w-5xl mx-auto px-4 md:px-8 h-14 flex items-center justify-between">
      <NuxtLink to="/" class="flex items-center gap-2">
        <!-- 2026-06-12 修复：原 logo 文字和首字母圈都硬编码 "Y" / "Yuan Yi"，
             无论 admin 在「站点设置 → 站点信息」改成什么，前台 nav 永远没反应。
             改成读 /public/settings/blog 拿真实 title；缺省时 fallback 到 "加载中"。
             - title 缺省首字母圈
             - 有 logo URL 时优先显示 logo 图片 -->
        <div class="w-7 h-7 rounded-lg flex items-center justify-center text-white text-sm font-semibold overflow-hidden" style="background: var(--color-primary);">
          <img v-if="blog?.logo" :src="blog.logo" alt="logo" class="w-full h-full object-cover" />
          <span v-else>{{ (blog?.title || '加载中')[0] }}</span>
        </div>
        <span class="font-serif-display text-lg">{{ blog?.title || '加载中' }}</span>
      </NuxtLink>

      <div class="hidden md:flex items-center gap-1">
        <NuxtLink to="/" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">首页</NuxtLink>
        <NuxtLink to="/archives" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">归档</NuxtLink>
        <NuxtLink to="/tags" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">标签</NuxtLink>
        <NuxtLink to="/about" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">关于</NuxtLink>
      </div>

      <div class="flex items-center gap-2">
        <button class="w-9 h-9 rounded-md flex items-center justify-center transition-colors" style="color: var(--color-text-2);" @click="toggleTheme" aria-label="切换主题">
          <svg v-if="!isDark" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>
          <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
        </button>
        <NuxtLink v-if="isApprovedDevice" to="/admin/login" class="hidden md:inline-flex w-9 h-9 rounded-md items-center justify-center transition-colors" style="color: var(--color-text-2);" title="后台管理">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
        </NuxtLink>
      </div>
    </div>
  </nav>
</template>

<script setup lang="ts">
// 2026-06-12 新增：从公开端点拉站点信息，让 logo / title 反映 admin 在后台保存的值。
// useAsyncData 用固定 key 'site-blog'——SiteFooter、app.vue 用同一 key 时 Nuxt 自动 dedupe，
// 整个页面 SSR 只发一次请求。
//
// 2026-06-22 修复（BUG-XXX 顶层 await 双倍阻塞）：
// 之前 NavBar.vue 自己 await useAsyncData('site-blog', ...) 拉同一份数据。
// Nuxt dedupe 的是 promise（只发一次请求），但**两个 await 都得等 promise resolve**——
// 组件 setup 都阻塞。后端 /public/settings/blog 超时 → 整页白屏。
// 修：app.vue 是唯一的"发起方"（顶层 await 一次），NavBar 这里改成 useState 拿 reactive ref。
const { request } = usePublicApi()
const blogRef = useState<any>('site-blog-data', () => ({}))
// 在 script 里用 blogRef.value / template 用 blog（ref 自动 unwrap）
const blog = computed(() => blogRef.value || {})

// 2026-06-12 安全：后台管理入口图标只对「已授权设备」可见——
// 未授权设备（陌生访客 / 未在 admin_device 白名单 approved）连入口都看不到。
// 仅 UI 隐藏；真正的访问控制由 AdminAuthFilter + DeviceService.verifyOnRequest 兜底。
//
// SSR 默认 false：deviceId 存在 localStorage，服务端拿不到，强行 SSR 校验会让
// 所有访客首屏闪一下图标。改为 client mount 后异步校验，默认隐藏 → 有授权再显示。
//
// 关键：useDevice() 必须在 setup 顶层调用（不能放 onMounted 内），
// 否则在 async setup（上面 await useAsyncData）之后 composable 上下文可能丢失。
const { deviceId } = useDevice()
const isApprovedDevice = ref(false)
onMounted(async () => {
  if (!deviceId.value) {
    console.warn('[NavBar] 设备校验跳过：deviceId 为空')
    return
  }
  try {
    const res = await request<any>('/public/device/check', {
      method: 'GET',
      headers: { 'X-Device-Id': deviceId.value }
    })
    isApprovedDevice.value = !!res?.data?.approved
    // console.log('[NavBar] 设备校验:', { deviceId: deviceId.value, approved: isApprovedDevice.value, raw: res })  // auto-removed by auto-bug-scan
  } catch (e: any) {
    console.error('[NavBar] 设备校验失败', { deviceId: deviceId.value, error: e?.message, status: e?.statusCode, data: e?.data })
    isApprovedDevice.value = false
  }
})

// 默认深色：首次访问（localStorage 无值）即进入深色模式
const isDark = ref(true)
const toggleTheme = () => {
  isDark.value = !isDark.value
  if (import.meta.client) {
    document.documentElement.classList.toggle('dark', isDark.value)
    localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
  }
}
onMounted(() => {
  if (import.meta.client) {
    const saved = localStorage.getItem('theme')
    if (saved === 'light') {
      // 用户曾显式选择过浅色
      isDark.value = false
      document.documentElement.classList.remove('dark')
    } else {
      // 默认 / 用户曾选过深色 → 保持深色
      isDark.value = true
      document.documentElement.classList.add('dark')
    }
  }
})
</script>

<style scoped>
.active-link {
  color: var(--color-primary) !important;
  background: var(--color-primary-soft);
  font-weight: 500;
}
</style>
