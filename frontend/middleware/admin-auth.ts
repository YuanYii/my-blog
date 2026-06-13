/**
 * 后台页面统一鉴权中间件
 * 未登录跳 /admin/login
 *
 * 注意：SSR 阶段不鉴权（localStorage 在 server 不可用），
 * 靠 client 阶段 init() 后再判断 + layouts/admin.vue 的 onMounted 兜底。
 */
export default defineNuxtRouteMiddleware((to) => {
  // 登录页本身不需要鉴权
  if (to.path === '/admin/login') return

  // SSR 阶段：localStorage 不可用，跳过鉴权；client 阶段会再跑一次此 middleware
  if (import.meta.server) return

  const { isLoggedIn, init } = useAuth()
  init()

  if (!isLoggedIn.value) {
    return navigateTo('/admin/login')
  }
})
