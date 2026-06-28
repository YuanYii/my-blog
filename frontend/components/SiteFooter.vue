<script setup lang="ts">
// 2026-06-12 修复：原 footer 把版权、RSS / GitHub / 邮箱链接全部硬编码——
// admin 在「站点设置 → 站点信息 / 社交账号」改完根本不会反映到前台 footer。
// useAsyncData 与 NavBar / app.vue 共享同一组 key，整页 SSR 共三次请求即可。
//
// 2026-06-22 修复（BUG-XXX 顶层 await 双倍阻塞）：
// blog 数据跟 NavBar 一样改 useState 拿（app.vue 是唯一发起方）；
// social 没有跨组件共用，仍走 useAsyncData（Nuxt SSR 自动 dedupe promise）。
//
// 2026-06-28 OPT-001（autopush）：RSS 入口按 advanced.enableRss 显隐
const { get } = usePublicApi()
const { flags: siteFlags } = useSiteFlags()
const [{ data: socialRes }] = await Promise.all([
  useAsyncData('site-social', () => get<any>('/public/settings/social'))
])
const blogRef = useState<any>('site-blog-data', () => ({}))
const blog = computed(() => blogRef.value || {})
const social = computed(() => socialRes.value?.data || {})
const mailHref = computed(() => social.value?.emailPublic ? `mailto:${social.value.emailPublic}` : '')
// RSS 同时受 enableRss 开关 + social.rss URL 是否配置双控：开关关或 URL 空都隐藏
const showRss = computed(() => siteFlags.value.enableRss && !!social.value?.rss)
</script>

<template>
  <footer class="mt-16 py-10" style="border-top: 1px solid var(--color-line);">
    <div class="max-w-5xl mx-auto px-4 md:px-8 text-sm" style="color: var(--color-muted);">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>{{ blog.copyright || '加载中' }} · Powered by Nuxt 3 &amp; Spring Boot</div>
        <div>
          <a v-if="showRss" :href="social.rss" class="hover:text-primary transition-colors">RSS</a>
          <template v-if="showRss && social.github"><span class="mx-2">·</span></template>
          <a v-if="social.github" :href="social.github" target="_blank" rel="noopener" class="hover:text-primary transition-colors">GitHub</a>
          <template v-if="(showRss || social.github) && mailHref"><span class="mx-2">·</span></template>
          <a v-if="mailHref" :href="mailHref" class="hover:text-primary transition-colors">邮箱</a>
        </div>
      </div>
    </div>
  </footer>
</template>
