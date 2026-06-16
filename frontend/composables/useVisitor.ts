/**
 * 访客标识 composable
 * - visitorId: 前端生成 UUID 持久化在 localStorage，跨 session 稳定
 * - 后端 PageViewFilter 拿这个 ID 做按天去重（page_view.UNIQUE(visitor, path, created_at)）
 *
 * 与 useDevice 的区别：
 * - deviceId：admin 鉴权用，绑定到登录账号（登录后 syncDeviceId 改写）
 * - visitorId：公开页访客用，**不绑定账号**——任何访客（包括未登录的爬虫）都有
 *
 * 用法：useApi 已经自动带 X-Visitor-Id header（修改 usePublicApi 也加一下）
 */
const VISITOR_ID_KEY = 'blog_visitor_id'

export const useVisitor = () => {
  const visitorId = ref<string>('')

  if (import.meta.client) {
    let id = localStorage.getItem(VISITOR_ID_KEY)
    if (!id) {
      id = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : 'v-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
      localStorage.setItem(VISITOR_ID_KEY, id)
    }
    visitorId.value = id
  }

  return { visitorId }
}
