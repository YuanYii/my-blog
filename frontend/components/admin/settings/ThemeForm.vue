<script setup lang="ts">
defineProps<{ theme: { mode: string; primaryColor: string; accentColor: string; fontFamily: string } }>()
defineEmits<{ (e: 'update:theme', v: any): void }>()
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group">
      <label class="form-label">主题模式</label>
      <select :value="theme.mode" @change="$emit('update:theme', { ...theme, mode: ($event.target as HTMLSelectElement).value })" class="form-control">
        <option value="auto">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option>
      </select>
    </div>
    <div class="form-row-2">
      <div class="form-group" style="margin: 0;"><label class="form-label">主色</label><input :value="theme.primaryColor" @input="$emit('update:theme', { ...theme, primaryColor: ($event.target as HTMLInputElement).value })" type="color" class="form-control" style="height: 38px; padding: 2px;" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">强调色</label><input :value="theme.accentColor" @input="$emit('update:theme', { ...theme, accentColor: ($event.target as HTMLInputElement).value })" type="color" class="form-control" style="height: 38px; padding: 2px;" /></div>
    </div>
    <div class="form-group" style="margin-bottom: 0;">
      <label class="form-label">字体</label>
      <select :value="theme.fontFamily" @change="$emit('update:theme', { ...theme, fontFamily: ($event.target as HTMLSelectElement).value })" class="form-control">
        <option value="serif">衬线（默认）</option><option value="sans">无衬线</option><option value="mono">等宽</option>
      </select>
    </div>
  </div>
</template>
