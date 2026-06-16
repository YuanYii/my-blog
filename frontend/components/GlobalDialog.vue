<script setup lang="ts">
// 全局确认 / 输入对话框（替代浏览器原生 confirm / prompt）
// 2026-06-16 新增：与 toast 配合，所有 admin 页面统一使用。
//
// 渲染逻辑：
// - 用 defineModel 接收 v-model:open（父组件控制显示）
// - 内部维护一个 promise 池，关闭时 resolve 给父组件
//
// 两种变体：
// - confirm 模式：纯文本 + 确认/取消按钮
// - prompt 模式：额外 input 输入框（v-model:value 双向绑）

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

watch(() => props.open, (v) => {
  if (v) inputValue.value = props.promptDefault || ''
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

// ESC 关闭
const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && props.open) handleCancel()
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
