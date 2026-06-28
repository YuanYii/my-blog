// 2026-06-28 v5.0.0 DEV-001：内联 Modal 键盘快捷键 composable
//
// 用途：给 admin 各页的 inline `v-if="showModal"` 模态框加 Esc/Enter 快捷键
//  - Esc → 触发 onCancel（关闭弹窗）
//  - Enter → 触发 onConfirm（提交/保存）—— 但跳过 IME composing + 文本输入元素
//
// 设计动机：
//  - GlobalDialog 已有这套行为（服务 $dialog.confirm / $dialog.prompt）
//  - 但 admin 页的"内联表单 modal"（如 categories 的「新建/编辑」、restore 的「确认恢复」）
//    是直接写在页面里的 `<div v-if="showModal" class="modal-backdrop">`，不走 GlobalDialog
//  - 用 composable 统一处理避免逐页重复 keydown 监听 + IME 兼容代码
//
// 不做的事：
//  - 不管 focus-visible 样式（GlobalDialog 已有，inline modal 继承 .btn 现有 focus 样式）
//  - 不管嵌套栈：当前 admin 页面没有"两个内联 modal 同时打开"的情况（restore 有两个 modal，
//    但 restoreErrorDialog 只在 restoreDialog 关闭后才会打开），如未来出现再补栈管理
//  - 不动原生 `<form @submit>` 的回车行为（需求 4.5：「不影响表单内回车」）
//
// 边界前提：
//  - 调用方在同一组件中只对单一 modal 启用。多个 modal 各自独立 useModalKeyboard 实例是 OK 的
//    （每个实例的 keydown 监听器都注册，但内部 `if (!open.value) return` 守卫确保只有打开的
//    modal 才响应；不会重复关 modal 也不会冲突）
//  - 跨 modal 同时打开的极端场景（如未来要支持）需重构为栈管理或事件代理，目前不需要

interface ModalKeyboardOptions {
  /** 控制弹窗显示的 ref */
  open: Ref<boolean>
  /** Esc 触发的取消处理 */
  onCancel: () => void
  /** Enter 触发的确认处理；不传则只响应 Esc */
  onConfirm?: () => void
  /** 需要自动聚焦的元素 ref（通常是「确定/保存」按钮） */
  confirmButtonRef?: Ref<HTMLElement | null>
}

export function useModalKeyboard(opts: ModalKeyboardOptions) {
  const { open, onCancel, onConfirm, confirmButtonRef } = opts

  const onKeydown = (e: KeyboardEvent) => {
    if (!open.value) return
    if (e.key === 'Escape') {
      e.preventDefault()
      onCancel()
    } else if (onConfirm && e.key === 'Enter' && !e.isComposing && !e.shiftKey) {
      const target = e.target as HTMLElement | null
      // 文本输入/可编辑元素中回车 → 让浏览器/表单默认行为接管
      // （用户在 input/textarea 里敲回车期望换行或 submit 现有 form，不应该被弹窗捕获）
      if (target?.tagName === 'TEXTAREA' || target?.isContentEditable) return
      e.preventDefault()
      onConfirm()
    }
  }

  // 仅在 open 状态时挂载 keydown 监听 —— 弹窗关闭时立刻解绑，
  // 避免页面其他位置（如快速添加输入框）的回车被误捕获
  watch(open, (v) => {
    if (v) {
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  }, { immediate: true })

  // 弹窗打开时自动聚焦「确定」按钮（confirm 按钮拿到焦点 → Enter 直接触发）
  watch(open, async (v) => {
    if (v && confirmButtonRef?.value) {
      await nextTick()
      confirmButtonRef.value.focus()
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown)
  })
}
