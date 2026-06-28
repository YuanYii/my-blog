/**
 * 2026-06-27 DEV-002：站点偏好设置落地。
 * 读取 /public/settings/preferences（language / timezone / density / codeTheme），
 * 应用到 body class（density / code-theme）+ 全局 useState（供 useI18n / useFormatTime 消费）。
 *
 * - language：仅写入 state，由 useI18n 读取并切换字典
 * - timezone：仅写入 state，由 useFormatTime 读取
 * - density='comfortable'|'compact' → body class density-comfortable|density-compact
 * - codeTheme='github'|'monokai'|'nord' → body class code-theme-{name}
 */
export type SitePreferences = {
  language?: 'zh-CN' | 'en'
  timezone?: string
  density?: 'comfortable' | 'compact'
  codeTheme?: 'github' | 'monokai' | 'nord'
}

const DEFAULT_PREFS: SitePreferences = {
  language: 'zh-CN', timezone: 'Asia/Shanghai', density: 'comfortable', codeTheme: 'github'
}

function applyPreferences(p: SitePreferences) {
  if (typeof document === 'undefined') return
  const body = document.body
  if (!body) return
  body.classList.remove('density-comfortable', 'density-compact')
  body.classList.add(`density-${p.density || 'comfortable'}`)
  // codeTheme：移除所有旧 code-theme-*，加新的
  Array.from(body.classList).forEach((c) => { if (c.startsWith('code-theme-')) body.classList.remove(c) })
  body.classList.add(`code-theme-${p.codeTheme || 'github'}`)
  // language → <html lang>
  document.documentElement.setAttribute('lang', p.language === 'en' ? 'en' : 'zh-CN')
}

export function useSitePreferences() {
  const prefs = useState<SitePreferences>('site-preferences', () => ({ ...DEFAULT_PREFS }))
  const { get } = usePublicApi()
  const { data } = useAsyncData('site-preferences', () => get<any>('/public/settings/preferences'))
  watch(data, (v) => {
    if (v?.data) Object.assign(prefs.value, v.data)
    if (import.meta.client) applyPreferences(prefs.value)
  }, { immediate: true })
  if (import.meta.client) {
    onMounted(() => applyPreferences(prefs.value))
  }
  return { prefs }
}
