/**
 * Admin API 调用封装（自动带 Authorization + X-Device-Id）
 * 适用于：/api/v1/admin/* 鉴权接口
 *
 * 2026-06-08 重构拆分：useApi 拆成 usePublicApi + useAdminApi
 * 删除 useApi 时机：所有 page 迁移完成后
 */
export const useAdminApi = () => {
  const config = useRuntimeConfig()
  const base = config.public.apiBase
  const { token, clear } = useAuth()
  const { deviceId } = useDevice()

  const request = <T = any>(path: string, options: any = {}): Promise<T> => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
    if (token.value) {
      headers['Authorization'] = `Bearer ${token.value}`
    }
    // 设备 ID：每次请求都带，用于后端做白名单校验 + 审计
    if (deviceId.value) {
      headers['X-Device-Id'] = deviceId.value
    }
    return $fetch<T>(`${base}${path}`, {
      ...options,
      headers,
      onResponse({ response }) {
        // 2026-06-18 修复：onResponse 对所有响应无条件执行，早于 ofetch 内部
        // `status >= 400 → onResponseError` 的判断。之前这里不分状态码，只要业务 code
        // !== 200 就 throw，导致真正的 401/403 错误响应也被这里截胡 → onResponseError
        // 里"清空 token + 跳转 /admin/login"永远执行不到（会话失效后页面停留在原地，
        // 只能看见 toast）。只在 HTTP 本身成功（2xx）时才需要识别"业务错误码"，
        // 真正的 HTTP 错误状态交给下面 onResponseError 处理。
        if (!response.ok) return
        // 业务 code 检查（HTTP 200 但 body.code !== 200，例如改密接口的业务校验失败）
        const data: any = response._data
        if (data && typeof data === 'object' && 'code' in data && data.code !== 200) {
          // 2026-06-15 修复：改密端点 /auth/me/password 业务错（code 400）时 HTTP 仍 200，
          // 上一次 throw createError 会被 ofetch 误认为"onResponseError"路径，
          // 触发 401 跳登录清空 token。把 statusCode 设为 200 + code 透传，避免误伤。
          throw createError({
            statusCode: 200,
            statusMessage: 'OK',
            message: data.message || '业务错误',
            data: data
          })
        }
      },
      onResponseError({ response }) {
        if (response.status === 401) {
          const code = response._data?.code
          // 设备白名单相关：被吊销(2002) / 待授权(2001) → 立即清空 + 跳登录
          if (code === 2001 || code === 2002) {
            clear()
            if (import.meta.client && !location.pathname.startsWith('/admin/login')) {
              location.href = '/admin/login'
            }
            return
          }
          // 通用 401：仅在"持有 token 但 token 失效"时才清空 + 跳登录
          if (token.value) {
            clear()
            if (import.meta.client && !location.pathname.startsWith('/admin/login')) {
              location.href = '/admin/login'
            }
          }
        }
        console.error('[API]', response.status, path, response._data)
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

  /**
   * 文件上传（multipart/form-data）
   * 2026-06-12 新增：补 useApi 缺失的 file upload helper
   * 浏览器原生 FormData，$fetch 会自动识别 boundary 不需要手动 set Content-Type
   */
  const upload = async <T = any>(path: string, file: File): Promise<T> => {
    const form = new FormData()
    form.append('file', file)
    const headers: Record<string, string> = {}
    if (token.value) headers['Authorization'] = `Bearer ${token.value}`
    if (deviceId.value) headers['X-Device-Id'] = deviceId.value
    return $fetch<T>(`${base}${path}`, {
      method: 'POST',
      body: form,
      headers,
      onResponse({ response }) {
        // 同上修复：仅 2xx 才识别业务错误码，避免抢在 onResponseError（401 跳登录）前面
        if (!response.ok) return
        const data: any = response._data
        if (data && typeof data === 'object' && 'code' in data && data.code !== 200) {
          throw createError({
            statusCode: response.status,
            message: data.message,
            data: data
          })
        }
      },
      onResponseError({ response }) {
        if (response.status === 401) {
          const code = response._data?.code
          if (code === 2001 || code === 2002) {
            clear()
            if (import.meta.client && !location.pathname.startsWith('/admin/login')) {
              location.href = '/admin/login'
            }
            return
          }
          if (token.value) {
            clear()
            if (import.meta.client && !location.pathname.startsWith('/admin/login')) {
              location.href = '/admin/login'
            }
          }
        }
        console.error('[API]', response.status, path, response._data)
      }
    })
  }

  return { request, get, post, put, del, upload }
}
