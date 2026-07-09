/**
 * 共享 markdown / 日期工具函数
 *
 * 2026-06-22 抽出；2026-07-06 重写渲染引擎：手写状态机 → marked + 自定义 renderer。
 * 新增：GFM 表格、高亮 ==text==、任务列表、定义列表等。
 *
 * 三个导出函数：
 * - formatDateTime(d)    → 'YYYY-MM-DD HH:mm'  兼容 string / number / null
 * - formatDate(d)        → 'YYYY-MM-DD'         纯日期部分
 * - renderMarkdown(md)   → marked 渲染 HTML（admin 编辑器预览 / 公开文章详情共用）
 *
 * 新增导出：
 * - extractHeadings(md)  → [{ id, text, level }]  供侧边栏 TOC 使用
 *
 * 注意：调用方必须经过 DOMPurify 净化再 v-html（本函数不做 XSS 防护）。
 */

import { Marked } from 'marked'

// ====== 日期格式化 ======

export function formatDateTime(d: string | number | null | undefined): string {
  if (d == null || d === '') return ''
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    return formatYmdHm(date)
  }
  const date = new Date(d.replace(' ', 'T'))
  if (isNaN(date.getTime())) {
    return d.replace('T', ' ').substring(0, 16)
  }
  return formatYmdHm(date)
}

export function formatDate(d: string | number | null | undefined): string {
  if (d == null || d === '') return ''
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    return formatYmd(date)
  }
  return d.substring(0, 10)
}

export function formatMonthDay(d: string | number | null | undefined): string {
  if (d == null || d === '') return ''
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    return formatYmd(date).substring(5)
  }
  return d.length >= 10 ? d.substring(5, 10) : d
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function formatYmd(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function formatYmdHm(date: Date): string {
  return `${formatYmd(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// ====== Markdown 渲染（marked）======

let headingCounter = 0

function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\w\u4e00-\u9fff]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

const marked = new Marked()

marked.use({
  gfm: true,
  breaks: false,
  renderer: {
    heading({ tokens, depth }: { tokens: any[]; depth: number }) {
      const text = tokens.map((t: any) => t.raw || t.text || '').join('')
      const slug = slugify(text)
      headingCounter++
      const id = `h-${headingCounter}-${slug}`
      return `<h${depth} id="${id}">${tokens.map((t: any) => t.raw || t.text || '').join('')}</h${depth}>\n`
    },
  } as any,
})

/**
 * 渲染 markdown → HTML
 *
 * 支持：GFM 表格、围栏代码块、标题、列表、任务列表、引用、
 *       行内 code/bold/italic/strike/link/image、高亮 ==text==
 */
export function renderMarkdown(md: string): string {
  if (!md) return ''
  headingCounter = 0
  // 先处理 ==highlight==（在 marked 解析前，避免被 treated as emphasis）
  const withHighlight = md.replace(/==([^=\n]+)==/g, '<mark>$1</mark>')
  return marked.parse(withHighlight) as string
}

/**
 * 从 markdown 原文提取标题列表（供侧边栏 TOC 使用）
 * 返回 [{ id, text, level }]
 */
export function extractHeadings(md: string): Array<{ id: string; text: string; level: number }> {
  if (!md) return []
  const headings: Array<{ id: string; text: string; level: number }> = []
  let counter = 0

  const lines = md.replace(/\r\n?/g, '\n').split('\n')
  for (const line of lines) {
    const m = /^(#{1,3})\s+(.+)$/.exec(line.trim())
    if (m) {
      counter++
      const level = m[1].length
      const text = m[2].replace(/[*_`~\[\]()!]/g, '').trim()
      const slug = slugify(text)
      const id = `h-${counter}-${slug}`
      headings.push({ id, text, level })
    }
  }
  return headings
}
