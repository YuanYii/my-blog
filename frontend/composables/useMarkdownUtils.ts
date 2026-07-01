/**
 * 共享 markdown / 日期工具函数
 *
 * 2026-06-22 抽出（之前散落在 5 个文件，实现不一致，已制造过"editor 修过
 * 图片语法但 post 页漏改"的历史 bug —— 见 post/[slug].vue:113-117 注释）。
 *
 * 三个函数：
 * - formatDateTime(d)  → 'YYYY-MM-DD HH:mm'  兼容 string / number / null
 * - formatDate(d)      → 'YYYY-MM-DD'         纯日期部分
 * - renderMarkdown(md) → 简易 markdown → HTML（仅 admin 编辑器预览 / 公开文章详情共用）
 *
 * 注意：MarkdownEditor 和 post/[slug] 都做了 DOMPurify 二次净化；本函数
 * 不做 XSS 防护（避免与上层净化重复），上层调用方必须经过 DOMPurify 净化
 * 再 v-html。
 *
 * ===== TODO: 2026-06-22 XSS 隐患（prerender 阶段 ssrSafeHtml 正则过滤不可靠） =====
 * ssr:false 下 Nuxt prerender 公开页时 ssrSafeHtml()（post/[slug].vue）会执行。
 * 当前 ssrSafeHtml 用正则过滤 XSS，已被验证可绕过：
 *   <img src=x onerror=alert(1)>     (黑名单不含 img)
 *   <details open ontoggle=...>      (黑名单不含 details)
 *   <svg><animate onbegin=...>       (on* 属性检测被嵌套标签干扰)
 *   <a href="java\tscript:...">       (tab 字符绕过 protocol 检测)
 * 客户端走 DOMPurify 是安全的；但 prerender 产物会进 nginx 静态文件直接服务给访客。
 *
 * 本轮跳过修复，下一轮决策（候选）：
 *   A. nuxt.config.ts nitro.prerender.ignore 加 /post/** —— 零暴露面，损失文章页 SEO
 *   B. 引入 jsdom + DOMPurify 在 prerender 阶段净化（jsdom ~5MB，不进 client bundle）
 *   C. 改用正经 markdown 库（marked + DOMPurify SSR-safe 模式）
 * 详见 post/[slug].vue ssrSafeHtml 函数上方注释。
 */

/**
 * 统一日期时间格式化为 'YYYY-MM-DD HH:mm'
 *
 * 兼容入参：
 * - null / '' / undefined → ''
 * - number (Unix 毫秒)   → 按本地时区显示
 * - string ISO 8601      → 兼容 'Z' 后缀（UTC）/ '+HH:MM' / '-HH:MM' / 裸 LocalDateTime
 * - string 'YYYY-MM-DD HH:mm:ss' → 兼容（无时区信息按本地时区）
 * - string 'YYYY-MM-DD' → 纯日期
 *
 * BUG-068 修复要点：后端 LocalDateTime 序列化无 'Z' 后缀，直接 new Date() 会按本地
 * 时区解析导致时区漂移；统一加 'Z' 让 Date 内部按 UTC 解析，getXxx() 自动转本地时区。
 *
 * BUG-XXX 修复（这次顺手）：原实现 `d.includes('-', 10)` 对纯日期 '2026-06-13'
 * 也会命中 offset 10 的横杠（'06-13' 中的 '-'），错误地当作带时区的 ISO 解析。
 * 修复：先看是否有 'T' 分隔符，再看末尾是否有 'Z' / '+HH' / '-HH' 偏移标记，
 * 'YYYY-MM-DD' 纯日期不会进 UTC 分支。
 */
export function formatDateTime(d: string | number | null | undefined): string {
  if (d == null || d === '') return ''
  // number: Unix 毫秒
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    return formatYmdHm(date)
  }
  // string: 先尝试作为 ISO 解析
  // 关键：先判断是不是带时区/时间部分，再决定要不要补 'Z'
  const hasTime = d.includes('T') || d.includes(' ')
  const hasTzMarker = d.endsWith('Z') || /[+-]\d{2}:?\d{2}$/.test(d)
  const utc = hasTime && !hasTzMarker ? d + 'Z' : d
  const date = new Date(utc)
  if (isNaN(date.getTime())) {
    // 兜底：纯字符串截前 16 字符
    return d.replace('T', ' ').substring(0, 16)
  }
  return formatYmdHm(date)
}

/**
 * 统一日期格式化为 'YYYY-MM-DD'
 * 兼容 string / number / null；其它按 d.substring(0, 10) 兜底
 */
export function formatDate(d: string | number | null | undefined): string {
  if (d == null || d === '') return ''
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    return formatYmd(date)
  }
  // string: ISO 字符串取前 10 字符；'YYYY-MM-DD HH:mm:ss' 也兼容
  return d.substring(0, 10)
}

/** 'MM-DD'（archives 页用，不带年份） */
export function formatMonthDay(d: string | number | null | undefined): string {
  if (d == null || d === '') return ''
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    return formatYmd(date).substring(5)
  }
  // string: ISO / 'YYYY-MM-DD HH:mm:ss' 都能 substring(5, 10) 拿到 MM-DD
  // 但若 string 长度 < 10，substring 兜底返回原串
  return d.length >= 10 ? d.substring(5, 10) : d
}

// ====== 内部 helper ======

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function formatYmd(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function formatYmdHm(date: Date): string {
  return `${formatYmd(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// ====== Markdown 渲染 ======

/**
 * 简易 markdown → HTML 渲染（逐行状态机版）
 *
 * 2026-06-22 重写：原"逐正则 + 段落拼接"版本有 #7 #8 两类 bug——
 *   #7 多段列表被错误合并为一个 ul（贪婪匹配）
 *   #8 空段→空 p 标签、连续 blockquote 错位、内联 code 不处理
 * 改成逐行扫描 + 块累积：
 *   - 扫描每行，识别块类型（heading/list/blockquote/code-fence/paragraph）
 *   - 同类型连续行累积成一个块；遇到空行 / 类型切换 → emit 累积块
 *   - 内联处理（bold/italic/code/link/image）在 emit 时一次性作用于块内文本
 *
 * 不做 XSS 防护（DOMPurify 净化由调用方负责）。
 * 调用方：
 *   - pages/post/[slug].vue：safeMarkdown(md) = DOMPurify.sanitize(renderMarkdown(md))
 *   - components/admin/MarkdownEditor.vue：同上
 *
 * 支持语法：
 *   - 围栏代码块 ```lang\n...\n```
 *   - 标题 # / ## / ###
 *   - 无序列表 - / * / +（混用视为同一列表）
 *   - 有序列表 1. / 2. ...
 *   - 引用 >（连续行累积，允许中间空行）
 *   - 段落（连续非空行用 <br> 连接）
 *   - 行内 code / **bold** / *italic* / ~~strike~~ / [text](url) / ![alt](url)
 *
 * 不支持（按 MVP 范围）：
 *   - 嵌套列表、表格、删除线以外的强调、HTML 标签直写、引用嵌套列表
 *   - 这些走 marked / remark 时再扩
 */
export function renderMarkdown(md: string): string {
  if (!md) return ''
  // 2026-06-29 修复：去除 Windows 换行符 \r，避免标题正则匹配失败
  const lines = md.replace(/\r\n?/g, '\n').split('\n')
  const out: string[] = []
  // 块累积缓冲区
  let buf: string[] = []
  let blockType: '' | 'p' | 'ul' | 'ol' | 'quote' = ''

  const flush = () => {
    if (buf.length === 0) return
    const inner = renderInline(buf.join(blockType === 'p' ? '<br>' : '\n'))
    if (blockType === 'p')       out.push(`<p>${inner}</p>`)
    else if (blockType === 'ul') out.push(`<ul>${buf.map(l => `<li>${renderInline(l)}</li>`).join('')}</ul>`)
    else if (blockType === 'ol') out.push(`<ol>${buf.map(l => `<li>${renderInline(l)}</li>`).join('')}</ol>`)
    else if (blockType === 'quote') out.push(`<blockquote>${buf.map(l => renderInline(l)).join('<br>')}</blockquote>`)
    buf = []
    blockType = ''
  }

  let i = 0
  while (i < lines.length) {
    const line = lines[i]

    // ---- 围栏代码块：成对处理，不进 buf ----
    const fenceMatch = /^```(\w*)\s*$/.exec(line)
    if (fenceMatch) {
      flush()
      const lang = fenceMatch[1] || ''
      const codeLines: string[] = []
      i++
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        codeLines.push(lines[i])
        i++
      }
      i++  // 跳过收尾 ```
      const codeContent = escapeHtml(codeLines.join('\n'))
      out.push(`<pre><code class="lang-${lang}">${codeContent}</code></pre>`)
      continue
    }

    // ---- 空行：段落分隔 ----
    if (line.trim() === '') {
      flush()
      i++
      continue
    }

    // ---- 标题：单行块 ----
    const headingMatch = /^(#{1,3})\s+(.+)$/.exec(line)
    if (headingMatch) {
      flush()
      const level = headingMatch[1].length
      out.push(`<h${level}>${renderInline(headingMatch[2])}</h${level}>`)
      i++
      continue
    }

    // ---- 引用：以 > 开头 ----
    // 允许 blockquote 中间夹空行不打断（连续 > 行算同一段）
    if (/^>\s?/.test(line)) {
      if (blockType !== 'quote') flush()
      blockType = 'quote'
      buf.push(line.replace(/^>\s?/, ''))
      i++
      continue
    }

    // ---- 无序列表：以 - / * / + 加空格开头 ----
    if (/^[-*+]\s+/.test(line)) {
      if (blockType !== 'ul') flush()
      blockType = 'ul'
      buf.push(line.replace(/^[-*+]\s+/, ''))
      i++
      continue
    }

    // ---- 有序列表：数字 + . + 空格 ----
    if (/^\d+\.\s+/.test(line)) {
      if (blockType !== 'ol') flush()
      blockType = 'ol'
      buf.push(line.replace(/^\d+\.\s+/, ''))
      i++
      continue
    }

    // ---- 其他：段落 ----
    if (blockType !== 'p') flush()
    blockType = 'p'
    buf.push(line)
    i++
  }
  flush()

  return out.join('\n')
}

// =================== 内联渲染 + HTML 转义 ===================

/**
 * 行内元素渲染：code / bold / italic / strike / image / link
 * 注意：图片必须在链接前匹配（图片也是 []() 形式）
 */
function renderInline(text: string): string {
  if (!text) return ''
  // 先用占位符替换 image + link，避开后续的 []() 处理被干扰
  const placeholders: string[] = []
  const placeholder = (html: string) => {
    const idx = placeholders.length
    placeholders.push(html)
    return `\x00PH${idx}\x00`
  }
  // image: ![alt](url) — alt 允许空，url 不允许空白，可选 "title"
  let s = text.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)/g, (_m, alt, url) =>
    placeholder(`<img src="${url}" alt="${escapeHtml(alt)}" loading="lazy" />`)
  )
  // link: [text](url)
  s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_m, t, url) =>
    placeholder(`<a href="${url}" target="_blank">${escapeHtml(t)}</a>`)
  )
  // 行内 code: `code` — 用占位符防止内部 ** 被外层处理
  s = s.replace(/`([^`]+)`/g, (_m, code) => placeholder(`<code>${escapeHtml(code)}</code>`))
  // bold: **text**
  s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
  // italic: *text*
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  // strike: ~~text~~
  s = s.replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
  // 还原占位符
  s = s.replace(/\x00PH(\d+)\x00/g, (_m, idx) => placeholders[Number(idx)] || '')
  return s
}

/** HTML 特殊字符转义（用在 code 块 / code inline / alt 文本里防 XSS） */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
