/**
 * 2026-08-14 DEV-002：站点主题与系统昼夜自适应管理。
 * 支持三态：'auto' (跟随系统地理昼夜) | 'light' (强制浅色) | 'dark' (强制深色)
 * 监听 matchMedia('(prefers-color-scheme: dark)') 的实时 change 事件。
 */
export type ColorMode = 'auto' | 'light' | 'dark'

export type ThemeSettings = {
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

export function useSiteTheme() {
  const themeState = useState<ThemeSettings>('site-theme', () => ({
    mode: 'auto', primaryColor: '#2f6f5e', accentColor: '#c97b3f', fontFamily: 'serif'
  }))

  const colorMode = useState<ColorMode>('site-color-mode', () => 'auto')
  const isDark = useState<boolean>('site-is-dark', () => true)

  const resolveIsDark = (mode: ColorMode): boolean => {
    if (mode === 'dark') return true
    if (mode === 'light') return false
    if (import.meta.client && window.matchMedia) {
      return window.matchMedia('(prefers-color-scheme: dark)').matches
    }
    return true
  }

  const applyTheme = (theme: ThemeSettings, mode: ColorMode) => {
    if (typeof document === 'undefined') return
    const root = document.documentElement
    const body = document.body

    const dark = resolveIsDark(mode)
    isDark.value = dark
    root.classList.toggle('dark', dark)

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

  const setColorMode = (mode: ColorMode) => {
    colorMode.value = mode
    if (import.meta.client) {
      try {
        localStorage.setItem('theme', mode)
      } catch { /* ignore */ }
      applyTheme(themeState.value, mode)
    }
  }

  const cycleColorMode = () => {
    const next: Record<ColorMode, ColorMode> = {
      auto: 'light',
      light: 'dark',
      dark: 'auto'
    }
    setColorMode(next[colorMode.value] || 'auto')
  }

  // SSR + client：拉一次公开端点
  const { get } = usePublicApi()
  const { data } = useAsyncData('site-theme', () => get<any>('/public/settings/theme'))
  watch(data, (v) => {
    if (v?.data) Object.assign(themeState.value, v.data)
    if (import.meta.client) applyTheme(themeState.value, colorMode.value)
  }, { immediate: true })

  if (import.meta.client) {
    onMounted(() => {
      let saved: ColorMode = 'auto'
      try {
        const val = localStorage.getItem('theme')
        if (val === 'light' || val === 'dark' || val === 'auto') {
          saved = val
        }
      } catch { /* ignore */ }
      colorMode.value = saved
      applyTheme(themeState.value, saved)

      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      const onChange = () => {
        if (colorMode.value === 'auto') {
          applyTheme(themeState.value, 'auto')
        }
      }
      if (mq.addEventListener) mq.addEventListener('change', onChange)
      else mq.addListener(onChange)
    })
  }

  return {
    theme: themeState,
    colorMode,
    isDark,
    setColorMode,
    cycleColorMode,
    apply: () => applyTheme(themeState.value, colorMode.value)
  }
}
