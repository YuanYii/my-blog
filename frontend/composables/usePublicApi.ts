/**
 * 公开 API 调用封装（不带 Authorization / X-Device-Id）
 * 适用于：articles / comments / auth/login / health 等公开接口
 *
 * 2026-06-08 重构拆分：useApi 拆成 usePublicApi + useAdminApi
 * 删除 useApi 时机：所有 page 迁移完成后
 */
export const usePublicApi = () => {
  const config = useRuntimeConfig()
  const base = config.public.apiBase

  const request = <T = any>(path: string, options: any = {}): Promise<T> => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
    // 公开 API：故意不带 Authorization / X-Device-Id
    // 2026-06-16 v2.5.0 新增：自动带 X-Visitor-Id（公开页访客标识，用于按天去重 page_view）
    // 2026-06-16 v2.5.0-fix：直接同步读 localStorage，不依赖 useVisitor().visitorId ref 时序
    //   原因：composable 初始化时 ref 写入是异步的（顶层 client 分支），首次发请求时 ref 可能
    //         还是空字符串，导致 X-Visitor-Id 漏发 → 后端 visitor 缺失 → PageViewService 直接跳过。
    //   改法：每次 request 同步读 localStorage，**同时确保 localStorage 一定有 visitorId**（懒初始化）。
    if (import.meta.client) {
      const VISITOR_KEY = 'blog_visitor_id'
      let vid = localStorage.getItem(VISITOR_KEY)
      if (!vid) {
        // 懒初始化：useVisitor 还没跑过（极端情况下，比如 SSR 后首次发请求）
        vid = (typeof crypto !== 'undefined' && crypto.randomUUID)
          ? crypto.randomUUID()
          : 'v-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
        localStorage.setItem(VISITOR_KEY, vid)
      }
      headers['X-Visitor-Id'] = vid
    }
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
  }

  const get = <T = any>(path: string, params?: any) =>
    request<T>(path, { method: 'GET', params })

  const post = <T = any>(path: string, body?: any) =>
    request<T>(path, { method: 'POST', body })

  const put = <T = any>(path: string, body?: any) =>
    request<T>(path, { method: 'PUT', body })

  const del = <T = any>(path: string) =>
    request<T>(path, { method: 'DELETE' })

  return { request, get, post, put, del }
}
