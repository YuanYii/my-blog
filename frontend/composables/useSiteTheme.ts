/**
 * 2026-06-27 DEV-001：站点主题落地。
 * 读取 /public/settings/theme（mode / primaryColor / accentColor / fontFamily），
 * 同步注入到 :root CSS 变量与 dark class、body font-* class。
 *
 * - mode=auto：监听 prefers-color-scheme
 * - mode=light/dark：直接固定
 * - primaryColor / accentColor 注入 --primary / --accent / --color-primary / --color-accent
 * - fontFamily=serif/sans/mono → body class font-{family}
 * - SSR 阶段 noop（document/window 不存在，且静态站 SSR 走预渲染，由客户端补齐）
 *
 * 调用方：app.vue 根 setup 一次即可（useState 单例 + onMounted 应用）。
 */
type ThemeSettings = {
  mode?: 'auto' | 'light' | 'dark'
  primaryColor?: string
  accentColor?: string
  fontFamily?: 'serif' | 'sans' | 'mono'
}

function hexToRgb(hex: string): [number, number, number] | null {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(hex || '')
  if (!m) return null
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
}

function applyTheme(theme: ThemeSettings) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  const body = document.body

  // mode：与现有 localStorage('theme') 兼容。本机覆盖优先（用户在 NavBar 切换 sun/moon 时写 localStorage）
  const localOverride = (() => {
    try { return localStorage.getItem('theme') } catch { return null }
  })()
  let mode: 'light' | 'dark'
  if (localOverride === 'light' || localOverride === 'dark') {
    mode = localOverride
  } else if (theme.mode === 'light' || theme.mode === 'dark') {
    mode = theme.mode
  } else {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    mode = mq.matches ? 'dark' : 'light'
  }
  root.classList.toggle('dark', mode === 'dark')

  // primaryColor / accentColor → CSS 变量
  if (theme.primaryColor && /^#[0-9a-fA-F]{6}$/.test(theme.primaryColor)) {
    root.style.setProperty('--primary', theme.primaryColor)
    root.style.setProperty('--color-primary', theme.primaryColor)
    const rgb = hexToRgb(theme.primaryColor)
    if (rgb) {
      root.style.setProperty('--primary-soft', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.12)`)
      root.style.setProperty('--color-primary-soft', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.12)`)
    }
  }
  if (theme.accentColor && /^#[0-9a-fA-F]{6}$/.test(theme.accentColor)) {
    root.style.setProperty('--accent', theme.accentColor)
    root.style.setProperty('--color-accent', theme.accentColor)
  }

  // fontFamily → body class
  if (body) {
    body.classList.remove('font-serif', 'font-sans', 'font-mono')
    const ff = theme.fontFamily || 'serif'
    body.classList.add(`font-${ff}`)
  }
}

export function useSiteTheme() {
  const themeState = useState<ThemeSettings>('site-theme', () => ({
    mode: 'auto', primaryColor: '#2f6f5e', accentColor: '#c97b3f', fontFamily: 'serif'
  }))

  // SSR + client：拉一次公开端点（dedupe 走 useAsyncData key）
  const { get } = usePublicApi()
  const { data } = useAsyncData('site-theme', () => get<any>('/public/settings/theme'))
  watch(data, (v) => {
    if (v?.data) Object.assign(themeState.value, v.data)
    if (import.meta.client) applyTheme(themeState.value)
  }, { immediate: true })

  // auto 模式跟随系统 prefers-color-scheme
  if (import.meta.client) {
    onMounted(() => {
      applyTheme(themeState.value)
      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      const onChange = () => {
        const localOverride = (() => { try { return localStorage.getItem('theme') } catch { return null } })()
        if (localOverride) return
        if (themeState.value.mode === 'auto') applyTheme(themeState.value)
      }
      if (mq.addEventListener) mq.addEventListener('change', onChange)
      else mq.addListener(onChange)
    })
  }

  return { theme: themeState, apply: () => applyTheme(themeState.value) }
}
