/**
 * C（2026-06-20）：Dashboard 工具函数，从 dashboard.vue 抽出。
 *
 * 2026-06-22：formatDateTime / formatDate 不再在此处定义，统一走 composables/useMarkdownUtils.ts。
 * （之前 5 处实现不一致已制造过"editor 修过图片语法但 post 页漏改"的历史 bug）
 * 注意：dashboard.vue 之前 import `formatDateTime` 自本文件 —— 改用 auto-import 后
 * 直接拿 useMarkdownUtils 版本，无需在本文件 re-export（re-export 会导致 Nuxt auto-import
 * 报"重复导入"警告，参考上次 prepare 的输出）。
 */

function toLocalDateString(d: Date): string {
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function fillDays(data: any[], days: number, key = 'pv'): number[] {
  const map = new Map<string, number>()
  for (const d of data) {
    const ds = typeof d.date === 'string' ? d.date.substring(0, 10) : toLocalDateString(new Date(d.date))
    map.set(ds, Number(d[key]) || 0)
  }
  const out: number[] = []
  const today = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    out.push(map.get(toLocalDateString(d)) || 0)
  }
  return out
}

export function getThemeColors() {
  if (!import.meta.client) {
    return { primary: '#2f6f5e', accent: '#c97b3f', muted: '#8b8475', text: '#1a1f2e', line: '#e8e4d8', grid: 'rgba(0,0,0,0.04)' }
  }
  const isDark = document.documentElement.classList.contains('dark')
  return {
    primary: isDark ? '#5fb09a' : '#2f6f5e',
    accent:  isDark ? '#d99262' : '#c97b3f',
    text:    isDark ? '#e8e6df' : '#1a1f2e',
    muted:   isDark ? '#6f6c63' : '#8b8475',
    line:    isDark ? '#2a3038' : '#e8e4d8',
    grid:    isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.04)'
  }
}

export const statusLabel = (s: number) => ({ 0: '草稿', 1: '已发布', 2: '已归档' }[s] || '未知')
