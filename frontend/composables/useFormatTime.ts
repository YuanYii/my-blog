/**
 * 2026-06-27 DEV-002：时间格式化（带 timezone）。
 *
 * 用法：
 *   const { formatTime } = useFormatTime()
 *   formatTime('2026-06-27 12:00:00')                   →  按 prefs.timezone 渲染（默认 Asia/Shanghai）
 *   formatTime('2026-06-27T04:00:00Z', { dateOnly: true })  →  仅日期
 *
 * 实现走原生 Intl.DateTimeFormat，避免引入 dayjs/luxon。
 */
import type { SitePreferences } from './useSitePreferences'

export function useFormatTime() {
  const prefs = useState<SitePreferences>('site-preferences', () => ({ timezone: 'Asia/Shanghai', language: 'zh-CN' }))

  function formatTime(input: string | number | Date | null | undefined, opts?: { dateOnly?: boolean }): string {
    if (input == null || input === '') return ''
    const d = input instanceof Date ? input : new Date(input)
    if (isNaN(d.getTime())) return String(input)
    const tz = prefs.value?.timezone || 'Asia/Shanghai'
    const locale = prefs.value?.language === 'en' ? 'en-US' : 'zh-CN'
    try {
      const o: Intl.DateTimeFormatOptions = opts?.dateOnly
        ? { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: tz }
        : { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: tz }
      return new Intl.DateTimeFormat(locale, o).format(d)
    } catch {
      return d.toISOString()
    }
  }

  return { formatTime }
}
