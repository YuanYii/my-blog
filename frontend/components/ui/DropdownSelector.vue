<script setup lang="ts">
interface Option { label: string; value: string | number }

const props = defineProps<{
  modelValue: string | number | null
  options: Option[]
  placeholder?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number | null): void
}>()

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const listRef = ref<HTMLElement | null>(null)
const highlightIdx = ref(-1)

const selectedLabel = computed(() => {
  const found = props.options.find(o => o.value === props.modelValue)
  return found ? found.label : ''
})

const toggle = () => {
  if (props.disabled) return
  isOpen.value = !isOpen.value
  if (isOpen.value) highlightIdx.value = props.options.findIndex(o => o.value === props.modelValue)
}

const select = (opt: Option) => {
  emit('update:modelValue', opt.value)
  isOpen.value = false
  highlightIdx.value = -1
}

const onKeydown = (e: KeyboardEvent) => {
  if (props.disabled) return
  if (!isOpen.value && (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === ' ')) {
    e.preventDefault()
    isOpen.value = true
    highlightIdx.value = props.options.findIndex(o => o.value === props.modelValue)
    return
  }
  if (!isOpen.value) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlightIdx.value = Math.min(highlightIdx.value + 1, props.options.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightIdx.value = Math.max(highlightIdx.value - 1, 0)
  } else if (e.key === 'Enter' && highlightIdx.value >= 0) {
    e.preventDefault()
    select(props.options[highlightIdx.value])
  } else if (e.key === 'Escape') {
    isOpen.value = false
  }
}

const onClickOutside = (e: MouseEvent) => {
  if (triggerRef.value && !triggerRef.value.contains(e.target as Node)) {
    isOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onClickOutside))
onBeforeUnmount(() => document.removeEventListener('click', onClickOutside))
</script>

<template>
  <div class="ds-wrap" :class="{ disabled }" ref="triggerRef" @keydown="onKeydown" tabindex="0">
    <div class="ds-trigger" @click="toggle">
      <span class="ds-value" :class="{ placeholder: !selectedLabel }">{{ selectedLabel || placeholder || '请选择' }}</span>
      <svg class="ds-arrow" :class="{ open: isOpen }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>
    </div>
    <Teleport to="body">
      <div v-if="isOpen" class="ds-panel" ref="listRef"
        :style="{
          top: triggerRef ? triggerRef.getBoundingClientRect().bottom + 4 + 'px' : '0',
          left: triggerRef ? triggerRef.getBoundingClientRect().left + 'px' : '0',
          width: triggerRef ? triggerRef.getBoundingClientRect().width + 'px' : 'auto'
        }">
        <div v-for="(opt, i) in options" :key="String(opt.value)"
          class="ds-option" :class="{ active: opt.value === modelValue, highlight: i === highlightIdx }"
          @click="select(opt)" @mouseenter="highlightIdx = i">
          {{ opt.label }}
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.ds-wrap {
  position: relative;
  width: 100%;
  outline: none;
}
.ds-wrap.disabled { opacity: 0.5; pointer-events: none; }
.ds-trigger {
  display: flex; align-items: center; justify-content: space-between;
  padding: 9px 12px;
  background: var(--card); color: var(--text);
  border: 1px solid var(--line); border-radius: 8px;
  font-size: 14px; cursor: pointer; transition: all 0.15s;
}
.ds-wrap:focus .ds-trigger { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.ds-value { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ds-value.placeholder { color: var(--muted); }
.ds-arrow { flex-shrink: 0; color: var(--muted); transition: transform 0.15s; margin-left: 6px; }
.ds-arrow.open { transform: rotate(180deg); }
</style>

<style>
.ds-panel {
  position: fixed; z-index: 9999;
  background: var(--card); border: 1px solid var(--line);
  border-radius: 8px; padding: 4px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
  max-height: 240px; overflow-y: auto;
}
.ds-option {
  padding: 8px 12px; border-radius: 6px;
  font-size: 14px; color: var(--text);
  cursor: pointer; transition: background 0.1s;
  white-space: nowrap;
}
.ds-option:hover, .ds-option.highlight { background: var(--bg-soft); }
.ds-option.active { background: var(--primary-soft); color: var(--primary); font-weight: 500; }
</style>
