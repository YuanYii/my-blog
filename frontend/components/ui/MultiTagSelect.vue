<script setup lang="ts">
interface Option { label: string; value: number }

const props = defineProps<{
  modelValue: number[]
  options: Option[]
  placeholder?: string
  disabled?: boolean
  max?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: number[]): void
}>()

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const highlightIdx = ref(-1)

const selectedLabels = computed(() => {
  return props.modelValue.map(v => {
    const found = props.options.find(o => o.value === v)
    return found ? found.label : String(v)
  })
})

const availableOptions = computed(() => {
  return props.options.filter(o => !props.modelValue.includes(o.value))
})

const toggle = () => {
  if (props.disabled) return
  isOpen.value = !isOpen.value
  highlightIdx.value = -1
}

const add = (opt: Option) => {
  if (props.max && props.modelValue.length >= props.max) return
  if (!props.modelValue.includes(opt.value)) {
    emit('update:modelValue', [...props.modelValue, opt.value])
  }
}

const remove = (val: number) => {
  emit('update:modelValue', props.modelValue.filter(v => v !== val))
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
  <div class="mts-wrap" :class="{ disabled }">
    <div class="mts-trigger" ref="triggerRef" @click="toggle">
      <div class="mts-chips">
        <span v-if="!modelValue.length" class="mts-placeholder">{{ placeholder || '请选择' }}</span>
        <span v-for="(label, i) in selectedLabels" :key="modelValue[i]" class="mts-chip">
          {{ label }}
          <button class="mts-chip-remove" @click.stop="remove(modelValue[i])">×</button>
        </span>
      </div>
      <svg class="mts-arrow" :class="{ open: isOpen }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>
    </div>
    <Teleport to="body">
      <div v-if="isOpen" class="mts-panel"
        :style="{
          top: triggerRef ? triggerRef.getBoundingClientRect().bottom + 4 + 'px' : '0',
          left: triggerRef ? triggerRef.getBoundingClientRect().left + 'px' : '0',
          width: triggerRef ? triggerRef.getBoundingClientRect().width + 'px' : 'auto'
        }">
        <div v-if="!availableOptions.length" class="mts-empty">无更多选项</div>
        <div v-for="(opt, i) in availableOptions" :key="opt.value"
          class="mts-option" :class="{ highlight: i === highlightIdx }"
          @click="add(opt)" @mouseenter="highlightIdx = i">
          {{ opt.label }}
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.mts-wrap { position: relative; width: 100%; outline: none; }
.mts-wrap.disabled { opacity: 0.5; pointer-events: none; }
.mts-trigger {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px; gap: 4px;
  background: var(--bg); color: var(--text);
  border: 1px solid var(--line); border-radius: 8px;
  font-size: 13px; cursor: pointer; transition: all 0.15s;
  min-height: 36px; flex-wrap: wrap;
}
.mts-wrap:focus-within .mts-trigger { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.mts-chips { display: flex; flex-wrap: wrap; gap: 4px; flex: 1; min-width: 0; }
.mts-placeholder { color: var(--muted); padding: 2px 6px; }
.mts-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; background: var(--primary-soft); color: var(--primary);
  border-radius: 12px; font-size: 12px;
}
.mts-chip-remove {
  background: transparent; border: none; cursor: pointer;
  color: var(--primary); padding: 0; line-height: 1; font-size: 14px;
}
.mts-chip-remove:hover { color: var(--danger); }
.mts-arrow { flex-shrink: 0; color: var(--muted); transition: transform 0.15s; margin-left: 4px; }
.mts-arrow.open { transform: rotate(180deg); }
</style>

<style>
.mts-panel {
  position: fixed; z-index: 9999;
  background: var(--card); border: 1px solid var(--line);
  border-radius: 8px; padding: 4px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
  max-height: 240px; overflow-y: auto;
}
.mts-option {
  padding: 8px 12px; border-radius: 6px;
  font-size: 14px; color: var(--text);
  cursor: pointer; transition: background 0.1s;
  white-space: nowrap;
}
.mts-option:hover, .mts-option.highlight { background: var(--bg-soft); }
.mts-empty { padding: 8px 12px; font-size: 13px; color: var(--muted); text-align: center; }
</style>
