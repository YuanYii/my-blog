// 全局对话框 composable（替代 confirm / prompt）
// 2026-06-16 新增：与 GlobalDialog 组件配合，所有 admin 页面用 await 调用。
//
// SSR-safe：SSR 阶段返回无 op（confirm/prompt 都不会被 SSR 触发，返回 confirmed:false）
// 避免 useState 在 SSR 阶段写入"假"状态导致 hydration mismatch。
//
// 用法：
//   const $dialog = useDialog()
//   const { confirmed, value } = await $dialog.confirm({ title, message, danger: true })
//   if (!confirmed) return
//
//   const { confirmed, value } = await $dialog.prompt({ title, label, placeholder })
//   if (!confirmed) return;  // value 是用户输入的字符串
//
// 内部用 ref + resolve/reject 模式，跨组件共享同一个 GlobalDialog 实例。

interface ConfirmOptions {
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

interface PromptOptions {
  title: string
  message?: string          // prompt 模式 message 可选
  label?: string            // 输入框上方标签
  placeholder?: string
  defaultValue?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

interface DialogResult {
  confirmed: boolean
  value?: string            // prompt 模式才有
}

export const useDialog = () => {
  // SSR 阶段：返回 noop，confirm/prompt 永远返回 confirmed=false
  // dialog 是纯 client 端 UI 状态，SSR 不会有任何使用
  if (import.meta.server) {
    // 2026-06-21 v4.2.1 polish 修复: 显式标注返回类型 DialogResult
    // 之前直接返回 { confirmed: false } 让 TS union 推导把 prompt 也缩成 { confirmed: boolean },
    // 调用方 `const { value } = await $dialog.prompt(...)` 报 TS2339 "value 不存在"
    // 一次性把所有页面的 dialog prompt TS 错误连带修掉(MarkdownEditor.vue 等历史错)
    const noopResult: DialogResult = { confirmed: false }
    return {
      state: ref({ open: false, title: '', message: '' }),
      handleConfirm: () => {},
      handleCancel: () => {},
      confirm: async (): Promise<DialogResult> => noopResult,
      prompt: async (): Promise<DialogResult> => noopResult
    }
  }
  // 单例状态：所有页面共享一个 dialog 状态
  const state = useState<{
    open: boolean
    title: string
    message: string
    confirmText?: string
    cancelText?: string
    danger?: boolean
    prompt?: boolean
    promptLabel?: string
    promptPlaceholder?: string
    promptDefault?: string
    resolver: ((r: DialogResult) => void) | null
  }>('global-dialog', () => ({
    open: false,
    title: '',
    message: '',
    resolver: null
  }))

  const open = (opts: Omit<typeof state.value, 'open' | 'resolver'>) => {
    return new Promise<DialogResult>((resolve) => {
      // 2026-07-01 BUG-003 修复：opts 不传的字段显式兜底默认值,
      // 避免之前的 dialog（如 MarkdownEditor "输入 URL"）污染全局 state,
      // 导致后续不传 cancelText 的 $dialog.confirm() 一直显示旧值。
      // 之前写法 `...state.value, ...opts` 会保留上次的 cancelText/danger/prompt 等状态。
      state.value = {
        ...state.value,
        ...opts,
        confirmText: opts.confirmText ?? '',
        cancelText: opts.cancelText ?? '',
        danger: opts.danger ?? false,
        prompt: opts.prompt ?? false,
        promptLabel: opts.promptLabel ?? '',
        promptPlaceholder: opts.promptPlaceholder ?? '',
        promptDefault: opts.promptDefault ?? '',
        open: true,
        resolver: resolve
      }
    })
  }

  const handleConfirm = (value?: string) => {
    if (state.value.resolver) {
      state.value.resolver({ confirmed: true, value })
    }
    state.value = { ...state.value, open: false, resolver: null }
  }

  const handleCancel = () => {
    if (state.value.resolver) {
      state.value.resolver({ confirmed: false })
    }
    state.value = { ...state.value, open: false, resolver: null }
  }

  return {
    state: readonly(state),
    handleConfirm,
    handleCancel,
    confirm: (opts: ConfirmOptions) => open({ ...opts, prompt: false }),
    prompt: (opts: PromptOptions) => open({
      title: opts.title,
      message: opts.message || '',
      promptLabel: opts.label,
      promptPlaceholder: opts.placeholder,
      promptDefault: opts.defaultValue,
      confirmText: opts.confirmText,
      cancelText: opts.cancelText,
      danger: opts.danger,
      prompt: true
    })
  }
}
