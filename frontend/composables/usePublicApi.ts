/**
 * 公开 API 调用封装（不带 Authorization / X-Device-Id）
 * 适用于：articles / comments / auth/login / health 等公开接口
 *
 * 2026-06-08 重构拆分：useApi 拆成 usePublicApi + useAdminApi
 * 删除 useApi 时机：所有 page 迁移完成后
 *
 * 2026-06-22 修复（BUG-XXX 访客 ID 并发竞态）：
 * 之前每次 request() 都同步读 localStorage + 懒初始化 vid。
 * 首页 6-7 个 useAsyncData 并发时，可能多个 request 都看到 localStorage 为空，
 * 各自分别生成 UUID 并互相覆盖 → 后端按天去重 page_view 失效。
 * 解决：用 module 级 lazy promise，第一次访问时初始化一次，后续复用。
 */
const VISITOR_KEY = 'blog_visitor_id'
let visitorIdPromise: Promise<string> | null = null

function ensureVisitorId(): Promise<string> {
  if (!import.meta.client) return Promise.resolve('')
  if (visitorIdPromise) return visitorIdPromise
  visitorIdPromise = new Promise<string>((resolve) => {
    try {
      const existing = localStorage.getItem(VISITOR_KEY)
      if (existing) {
        resolve(existing)
        return
      }
      const vid = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : 'v-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
      localStorage.setItem(VISITOR_KEY, vid)
      resolve(vid)
    } catch {
      // localStorage 不可用（隐私模式 / 第三方 cookie 禁用）—— 退化到内存随机 id
      resolve('mem-' + Math.random().toString(36).slice(2) + Date.now().toString(36))
    }
  })
  return visitorIdPromise
}

export const usePublicApi = () => {
  const config = useRuntimeConfig()
  const base = config.public.apiBase

  const request = <T = any>(path: string, options: any = {}): Promise<T> => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
    // 公开 API：故意不带 Authorization / X-Device-Id
    // 自动带 X-Visitor-Id（公开页访客标识，用于按天去重 page_view）。
    // module 级 promise 缓存 — 并发 6-7 个 useAsyncData 也只生成一次。
    const vidPromise = ensureVisitorId()
    return vidPromise.then((vid) => {
      if (vid) headers['X-Visitor-Id'] = vid
      return $fetch<T>(`${base}${path}`, {
        ...options,
        headers,
        onResponse({ response }) {
          // 业务 code 检查：后端业务错误用 HTTP 200 + body.code=非200 表达
          const data: any = response._data
          if (data && typeof data === 'object' && 'code' in data && data.code !== 200) {
            throw createError({
              statusCode: response.status,
              message: data.message,
              data: data
            })
          }
        }
      })
    })
  }

  const get = <T = any>(path: string, params?: any) =>
    request<T>(path, { method: 'GET', params })

  const post = <T = any>(path: string, body?: any) =>
    request<T>(path, { method: 'POST', body })

  const put = <T = any>(path: string, body?: any) =>
    request<T>(path, { method: 'PUT', body })

  const del = <T = any>(path: string) =>
    request<T>(path, { method: 'DELETE' })

  // ============ 2026-07-01 DEV-002：公开附件下载 ============

  /**
   * 浏览器原生 GET 下载（不走 ofetch，绕过 JSON 解析）。
   * 后端走 StreamUtils.copy 流式响应，浏览器收到 Content-Disposition 后触发下载。
   * 用 `<a ref="dlRef" @click.prevent="onDownload">` + ref 锁 + 30s setTimeout 防多点击（按 DEV-002 设计）。
   */
  const downloadAttachment = async (articleId: number): Promise<void> => {
    if (!import.meta.client) return
    const vid = await ensureVisitorId()
    const headers: Record<string, string> = {}
    if (vid) headers['X-Visitor-Id'] = vid
    const res = await fetch(`${base}/articles/${articleId}/attachment`, {
      method: 'GET',
      headers
    })
    if (!res.ok) {
      // 410 Gone（软删） / 404（不存在） / 500
      let msg = `下载失败 (${res.status})`
      try {
        const data = await res.json()
        if (data?.message) msg = data.message
      } catch { /* ignore */ }
      throw new Error(msg)
    }
    // 提取文件名（解析 Content-Disposition: attachment; filename="..."; filename*=UTF-8''...）
    const dispo = res.headers.get('Content-Disposition') || ''
    let fileName = 'attachment.zip'
    const utf8Match = dispo.match(/filename\*=UTF-8''([^;]+)/)
    if (utf8Match) {
      fileName = decodeURIComponent(utf8Match[1])
    } else {
      const quotedMatch = dispo.match(/filename="?([^";]+)"?/)
      if (quotedMatch) fileName = decodeURIComponent(quotedMatch[1])
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  return { request, get, post, put, del, downloadAttachment }
}
