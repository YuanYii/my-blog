// 全局 toast 提示（包装 vue-sonner 客户端方法）
// 2026-06-16 新增：避免在 SSR 阶段直接用 useNuxtApp().$toast 导致 hydration mismatch。
// SSR 阶段返回 noop（什么都不做），client 阶段返回真正的 toast 方法。
//
// 用法：
//   const $toast = useToast()
//   $toast.success('保存成功')
//   $toast.error('失败：' + e.message)
export const useToast = () => {
  if (import.meta.server) {
    return {
      success: (_msg: string) => {},
      error: (_msg: string) => {},
      warning: (_msg: string) => {},
      info: (_msg: string) => {},
      show: (_msg: string) => {},
      dismiss: () => {}
    }
  }
  const { $toast } = useNuxtApp()
  return $toast as {
    success: (msg: string) => void
    error: (msg: string) => void
    warning: (msg: string) => void
    info: (msg: string) => void
    show: (msg: string) => void
    dismiss: () => void
  }
}
