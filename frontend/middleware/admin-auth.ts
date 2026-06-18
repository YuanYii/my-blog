/**
 * 后台页面统一鉴权中间件
 * 未登录跳 /admin/login
 *
 * 2026-06-17 v2.7.0：全静态化后只走 client 阶段，移除 import.meta.server 判断
 * 兜底：layouts/admin.vue 的 onMounted 也会再检查一次
 */
export default defineNuxtRouteMiddleware((to) => {
  // 登录页本身不需要鉴权
  if (to.path === '/admin/login') return

  const { isLoggedIn, init } = useAuth()
  init()

  if (!isLoggedIn.value) {
    return navigateTo('/admin/login')
  }
})
