<script setup lang="ts">
defineProps<{ prefs: { language: string; timezone: string; density: string; codeTheme: string } }>()
defineEmits<{ (e: 'update:prefs', v: any): void }>()
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group">
      <label class="form-label">语言</label>
      <select :value="prefs.language" @change="$emit('update:prefs', { ...prefs, language: ($event.target as HTMLSelectElement).value })" class="form-control"><option value="zh-CN">简体中文</option><option value="en">English</option></select>
    </div>
    <div class="form-group">
      <label class="form-label">时区</label>
      <select :value="prefs.timezone" @change="$emit('update:prefs', { ...prefs, timezone: ($event.target as HTMLSelectElement).value })" class="form-control"><option value="Asia/Shanghai">上海 (UTC+8)</option><option value="UTC">UTC</option><option value="America/New_York">纽约 (UTC-5)</option></select>
    </div>
    <div class="form-group">
      <label class="form-label">列表密度</label>
      <select :value="prefs.density" @change="$emit('update:prefs', { ...prefs, density: ($event.target as HTMLSelectElement).value })" class="form-control"><option value="comfortable">舒适</option><option value="compact">紧凑</option></select>
    </div>
    <div class="form-group" style="margin-bottom: 0;">
      <label class="form-label">代码高亮主题</label>
      <select :value="prefs.codeTheme" @change="$emit('update:prefs', { ...prefs, codeTheme: ($event.target as HTMLSelectElement).value })" class="form-control"><option value="github">GitHub Light</option><option value="monokai">Monokai</option><option value="nord">Nord</option></select>
    </div>
  </div>
</template>
