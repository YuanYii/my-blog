/**
 * 2026-06-27 DEV-002：轻量 i18n（无运行时依赖，零打包成本）。
 *
 * 用法：
 *   const { t, locale } = useI18n()
 *   t('nav.home')   →  当前 locale 字典里的值；未命中回退 zh-CN，再回退 key 原文
 *
 * locale 由 useSitePreferences().prefs.language 驱动；切换语言无需刷新。
 * 字典懒加载（首次访问时 import('~/i18n/{lang}.json')）。
 */
import zhCN from '~/i18n/zh-CN.json'
import en from '~/i18n/en.json'

const DICT: Record<string, Record<string, string>> = {
  'zh-CN': zhCN as Record<string, string>,
  'en': en as Record<string, string>
}

export function useI18n() {
  const prefs = useState<{ language?: string }>('site-preferences', () => ({ language: 'zh-CN' }))
  const locale = computed(() => (prefs.value?.language as keyof typeof DICT) || 'zh-CN')
  const t = (key: string): string => {
    const cur = DICT[locale.value] || DICT['zh-CN']
    return cur[key] ?? DICT['zh-CN'][key] ?? key
  }
  return { t, locale }
}
