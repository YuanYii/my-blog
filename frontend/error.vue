<script setup lang="ts">
const props = defineProps<{ error: any }>()
const handleHome = () => clearError({ redirect: '/' })
</script>

<template>
  <div class="auth-page" style="background: var(--bg);">
    <div class="auth-card" style="text-align: center;">
      <div class="font-serif" style="font-size: 72px; color: var(--primary); line-height: 1;">{{ props.error?.statusCode || 404 }}</div>
      <h1 style="font-size: 20px; font-weight: 600; margin: 12px 0 6px;">页面走丢了 🧭</h1>
      <p style="color: var(--muted); font-size: 13px; margin-bottom: 20px;">
        {{ props.error?.message || '你访问的页面不存在或已被移动' }}
      </p>
      <button @click="handleHome" class="btn btn-primary" style="width: 100%; justify-content: center; padding: 10px;">
        回到首页
      </button>
    </div>
  </div>
</template>
