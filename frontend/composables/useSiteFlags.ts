/**
 * 2026-06-28 OPT-001/002（autopush）：站点能力开关显隐。
 *
 * 读取 /public/site-flags（enableRss / enableSearch），由前台 about / SiteFooter
 * 按 enableRss 控制 RSS 链接显隐，NavBar 按 enableSearch 控制搜索入口显隐。
 *
 * 设计：
 * - 后端 /site-flags 走 api_whitelist `/public/` 公开白名单，无需鉴权
 * - 30s 内存缓存由后端 AdvancedSettingsAccessor 承担，前端只拉一次
 * - SSR + client：useAsyncData key 全局唯一，hydration 后不会重复请求
 * - 失败/缺字段：默认 true（保守，UI 显示所有入口；admin 主动关闭才隐藏）
 *
 * 与 useSiteTheme / useSitePreferences 模式一致——app.vue 顶层调用一次，
 * 子组件通过 useState('site-flags') 读 reactive ref。
 */
export type SiteFlags = {
  enableRss: boolean
  enableSearch: boolean
}

const DEFAULT_FLAGS: SiteFlags = {
  enableRss: true,
  enableSearch: true
}

export function useSiteFlags() {
  const flags = useState<SiteFlags>('site-flags', () => ({ ...DEFAULT_FLAGS }))

  const { get } = usePublicApi()
  const { data } = useAsyncData('site-flags', () => get<any>('/public/site-flags'))
  watch(data, (v) => {
    const incoming = v?.data
    if (!incoming) return
    // 显式 false 才关闭，缺字段保留默认 true（与后端 AdvancedSettingsAccessor 兜底一致）
    flags.value.enableRss = incoming.enableRss !== false
    flags.value.enableSearch = incoming.enableSearch !== false
  }, { immediate: true })

  return { flags }
}
