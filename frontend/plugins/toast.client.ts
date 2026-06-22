// 全局 toast 提示（vue-sonner 客户端包装）
// 2026-06-16 新增：替换所有 alert(...) 为 $toast.xxx(...) 调用。
// 设计参考：https://github.com/xiaoluobing/vue-sonner
//
// SSR 兼容性：
// - vue-sonner 必须 client-only（DOM API 依赖），所以用 .client.ts 后缀
// - toast.success / error / warning / info 在任何地方调都没问题（vue-sonner 自带 queue）
//
// 样式说明：
// - vue-sonner@1.3.2 没有 `style.css` 导出（2.x 才有），样式由 Toaster 组件自动注入到 head
// - 不需要手动 import 样式
//
// 全局配色：
// - 容器在右上角 (top-right)，z-index 高于 modal
// - 主题色用 vue-sonner 内置 rich-colors（success/error/warning/info 自动配色）
import { toast } from 'vue-sonner'

export default defineNuxtPlugin((nuxtApp) => {
  if (import.meta.server) return

  // 注册全局可用方法（vue-sonner 的 toast() 已经是全局，但为了 TS 友好重新导出）
  return {
    provide: {
      toast: {
        success: (msg: string) => toast.success(msg),
        error: (msg: string) => toast.error(msg),
        warning: (msg: string) => toast.warning(msg),
        info: (msg: string) => toast.info(msg),
        // 通用（自动类型）
        show: (msg: string) => toast(msg),
        dismiss: () => toast.dismiss()
      }
    }
  }
})
