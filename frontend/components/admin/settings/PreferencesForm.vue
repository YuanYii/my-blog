<script setup lang="ts">
defineProps<{ prefs: { language: string; timezone: string; density: string; codeTheme: string } }>()
defineEmits<{ (e: 'update:prefs', v: any): void }>()

const langOptions = [
  { label: '简体中文', value: 'zh-CN' },
  { label: 'English', value: 'en' }
]
const tzOptions = [
  { label: '上海 (UTC+8)', value: 'Asia/Shanghai' },
  { label: 'UTC', value: 'UTC' },
  { label: '纽约 (UTC-5)', value: 'America/New_York' }
]
const densityOptions = [
  { label: '舒适', value: 'comfortable' },
  { label: '紧凑', value: 'compact' }
]
const codeThemeOptions = [
  { label: 'GitHub Light', value: 'github' },
  { label: 'Monokai', value: 'monokai' },
  { label: 'Nord', value: 'nord' }
]
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group">
      <label class="form-label">语言</label>
      <UiDropdownSelector :model-value="prefs.language" :options="langOptions" @update:model-value="(v: any) => $emit('update:prefs', { ...prefs, language: v })" />
    </div>
    <div class="form-group">
      <label class="form-label">时区</label>
      <UiDropdownSelector :model-value="prefs.timezone" :options="tzOptions" @update:model-value="(v: any) => $emit('update:prefs', { ...prefs, timezone: v })" />
    </div>
    <div class="form-group">
      <label class="form-label">列表密度</label>
      <UiDropdownSelector :model-value="prefs.density" :options="densityOptions" @update:model-value="(v: any) => $emit('update:prefs', { ...prefs, density: v })" />
    </div>
    <div class="form-group" style="margin-bottom: 0;">
      <label class="form-label">代码高亮主题</label>
      <UiDropdownSelector :model-value="prefs.codeTheme" :options="codeThemeOptions" @update:model-value="(v: any) => $emit('update:prefs', { ...prefs, codeTheme: v })" />
    </div>
  </div>
</template>
