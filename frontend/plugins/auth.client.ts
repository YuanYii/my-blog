/**
 * 启动时恢复登录态
 *
 * 2026-06-20 修复（BUG-XXX）：显式 import { useAuth }。
 * plugin 和 middleware 一样不在 Nuxt auto-import 作用域内，
 * 靠隐式 auto-import → ReferenceError: useAuth is not defined，
 * 导致 auth 初始化静默失败、刷新后 isLoggedIn 永远 false。
 */
import { useAuth } from '~/composables/useAuth'

export default defineNuxtPlugin(() => {
  if (import.meta.client) {
    const { init } = useAuth()
    init()
  }
})
