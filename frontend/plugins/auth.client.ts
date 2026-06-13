/**
 * 启动时恢复登录态
 */
export default defineNuxtPlugin(() => {
  if (import.meta.client) {
    const { init } = useAuth()
    init()
  }
})
