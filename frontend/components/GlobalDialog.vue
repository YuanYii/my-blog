<script setup lang="ts">
// 全局确认 / 输入对话框（替代浏览器原生 confirm / prompt）
// 2026-06-16 新增：与 toast 配合，所有 admin 页面统一使用。
// 2026-06-28 v5.0.0 DEV-001：新增 Enter 触发确认 + 自动聚焦主按钮
//
// 渲染逻辑：
// - 用 defineModel 接收 v-model:open（父组件控制显示）
// - 内部维护一个 promise 池，关闭时 resolve 给父组件
//
// 两种变体：
// - confirm 模式：纯文本 + 确认/取消按钮
// - prompt 模式：额外 input 输入框（v-model:value 双向绑）
//
// 键盘行为（v5.0.0+）：
// - Esc → 取消（handleCancel）
// - Enter → 确认（handleConfirm），但排除 IME composing（中文/日文输入法回车上屏）+ Shift+Enter
//   文本输入框中 Enter 也走原生行为（让用户能在 prompt 模式直接回车提交）—— 这与全局 Enter 监听
//   不冲突，因为文本框的 @keyup.enter 已经在原位置处理；窗口级 keydown 监听主要服务于「焦点在
//   确认/取消按钮或非输入元素」时的场景（用户 Tab 到主按钮后直接回车）
// - 打开时：confirm 模式自动聚焦「确定」按钮；prompt 模式聚焦 input（input 已有 autofocus）

const props = defineProps<{
  open: boolean
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean           // 主按钮红色（危险操作）
  // prompt 模式才有
  prompt?: boolean
  promptLabel?: string
  promptPlaceholder?: string
  promptDefault?: string
}>()

const emit = defineEmits<{
  'update:open': [boolean]
  confirm: [string?]         // confirm 模式无值，prompt 模式带输入值
  cancel: []
}>()

const inputValue = ref(props.promptDefault || '')
const confirmButtonRef = ref<HTMLButtonElement | null>(null)

watch(() => props.open, async (v) => {
  if (v) {
    inputValue.value = props.promptDefault || ''
    await nextTick()
    // confirm 模式：自动聚焦「确定」按钮（让 Enter 直接命中主按钮）
    // prompt 模式：input 已有 autofocus 标签，让浏览器把焦点给 input
    if (!props.prompt) {
      confirmButtonRef.value?.focus()
    }
  }
})

const handleConfirm = () => {
  if (props.prompt && !inputValue.value.trim()) return  // prompt 模式必须填
  emit('confirm', props.prompt ? inputValue.value : undefined)
  emit('update:open', false)
}

const handleCancel = () => {
  emit('cancel')
  emit('update:open', false)
}

// 2026-06-28 v5.0.0 DEV-001：增加 Enter 触发确认逻辑
// 注意：用 keydown 而非 keyup —— keydown 触发在 input 的 @keyup.enter 之前
// IME composing 状态（中文/日文输入法回车上屏）不触发；Shift+Enter 不触发（保留给将来多行输入）
// 文本输入元素中的 Enter 也不拦截（让原生回车提交表单行为保留，对应需求"不影响表单内回车"）
const onKeydown = (e: KeyboardEvent) => {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.preventDefault()
    handleCancel()
  } else if (e.key === 'Enter' && !e.isComposing && !e.shiftKey) {
    const target = e.target as HTMLElement | null
    // 文本输入/可编辑元素中的回车 → 让浏览器/表单默认行为接管
    // （prompt 模式 input 已有 @keyup.enter=handleConfirm；非 prompt 模式没有 input）
    if (target?.tagName === 'TEXTAREA' || target?.isContentEditable) return
    e.preventDefault()
    handleConfirm()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <ClientOnly>
    <Teleport to="body">
      <Transition name="dialog">
        <div v-if="open" class="dialog-backdrop" @click.self="handleCancel">
          <div class="dialog-card" role="dialog" aria-modal="true" :aria-label="title">
            <div class="dialog-header">
              <h3 class="dialog-title">{{ title }}</h3>
            </div>
            <div class="dialog-body">
              <p class="dialog-message">{{ message }}</p>
              <div v-if="prompt" class="dialog-prompt">
                <label v-if="promptLabel" class="dialog-prompt-label">{{ promptLabel }}</label>
                <input
                  v-model="inputValue"
                  :placeholder="promptPlaceholder"
                  class="form-control dialog-prompt-input"
                  @keyup.enter="handleConfirm"
                  autofocus
                />
              </div>
            </div>
            <div class="dialog-footer">
              <button @click="handleCancel" class="btn btn-ghost">{{ cancelText || '取消' }}</button>
              <button
                ref="confirmButtonRef"
                @click="handleConfirm"
                class="btn"
                :class="danger ? 'btn-danger' : 'btn-primary'"
                :disabled="prompt && !inputValue.trim()"
              >{{ confirmText || '确定' }}</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </ClientOnly>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
  padding: 24px;
}

.dialog-card {
  background: var(--card);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
  max-width: 420px;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.dialog-header {
  padding: 20px 24px 12px;
  border-bottom: 1px solid var(--line-soft);
}

.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
  margin: 0;
}

.dialog-body {
  padding: 16px 24px 20px;
}

.dialog-message {
  color: var(--text-2);
  font-size: 14px;
  line-height: 1.65;
  margin: 0;
  white-space: pre-line;  /* 支持 \n 换行 */
}

.dialog-prompt {
  margin-top: 14px;
}

.dialog-prompt-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  margin-bottom: 6px;
}

.dialog-prompt-input {
  width: 100%;
}

.dialog-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 24px 20px;
  border-top: 1px solid var(--line-soft);
}

/* 2026-06-28 v5.0.0 DEV-001：主按钮聚焦样式（键盘用户能看到当前焦点位置） */
.btn:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}
.btn-danger:focus-visible {
  outline-color: var(--danger);
}

/* 过渡 */
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.15s ease;
}
.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
.dialog-enter-active .dialog-card,
.dialog-leave-active .dialog-card {
  transition: transform 0.2s ease;
}
.dialog-enter-from .dialog-card {
  transform: translateY(20px);
}
.dialog-leave-to .dialog-card {
  transform: translateY(-10px);
}
</style>
