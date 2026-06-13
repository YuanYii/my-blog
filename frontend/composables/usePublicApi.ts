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
