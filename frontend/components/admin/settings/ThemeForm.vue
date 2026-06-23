<script setup lang="ts">
defineProps<{ theme: { mode: string; primaryColor: string; accentColor: string; fontFamily: string } }>()
defineEmits<{ (e: 'update:theme', v: any): void }>()

const modeOptions = [
  { label: '跟随系统', value: 'auto' },
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' }
]
const fontOptions = [
  { label: '衬线（默认）', value: 'serif' },
  { label: '无衬线', value: 'sans' },
  { label: '等宽', value: 'mono' }
]
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group">
      <label class="form-label">主题模式</label>
      <UiDropdownSelector :model-value="theme.mode" :options="modeOptions" @update:model-value="(v: any) => $emit('update:theme', { ...theme, mode: v })" />
    </div>
    <div class="form-row-2">
      <div class="form-group" style="margin: 0;"><label class="form-label">主色</label><input :value="theme.primaryColor" @input="$emit('update:theme', { ...theme, primaryColor: ($event.target as HTMLInputElement).value })" type="color" class="form-control" style="height: 38px; padding: 2px;" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">强调色</label><input :value="theme.accentColor" @input="$emit('update:theme', { ...theme, accentColor: ($event.target as HTMLInputElement).value })" type="color" class="form-control" style="height: 38px; padding: 2px;" /></div>
    </div>
    <div class="form-group" style="margin-bottom: 0;">
      <label class="form-label">字体</label>
      <UiDropdownSelector :model-value="theme.fontFamily" :options="fontOptions" @update:model-value="(v: any) => $emit('update:theme', { ...theme, fontFamily: v })" />
    </div>
  </div>
</template>
