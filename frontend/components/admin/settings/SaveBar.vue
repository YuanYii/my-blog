<script setup lang="ts">
defineProps<{ saving: boolean; message: string }>()
defineEmits<{ save: [] }>()
</script>

<template>
  <div style="display: flex; align-items: center; gap: 12px; margin-top: 16px;">
    <button @click="$emit('save')" :disabled="saving" class="btn btn-primary">
      {{ saving ? '保存中…' : '保存设置' }}
    </button>
    <span v-if="message" :style="{ color: message.includes('失败') ? 'var(--danger)' : 'var(--success)', fontSize: '13px' }">{{ message }}</span>
  </div>
</template>
