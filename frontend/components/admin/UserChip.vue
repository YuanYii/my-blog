<script setup lang="ts">
/**
 * 2026-06-23 OPT-005：admin 顶栏用户信息 chip（avatar + nickname/username）抽出，
 * 保留 2026-06-12 修复的「优先 avatar img + nickname 文字」行为，SSR fallback 不变。
 */
defineProps<{ user: { username?: string; nickname?: string; avatar?: string } | null }>()
</script>

<template>
  <div style="display: flex; align-items: center; gap: 8px; padding: 4px 10px 4px 4px; background: var(--bg-soft); border-radius: 8px; margin-left: 4px;">
    <ClientOnly>
      <div style="width: 24px; height: 24px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-size: 12px; overflow: hidden;">
        <img v-if="user?.avatar" :src="user.avatar" alt="avatar" style="width: 100%; height: 100%; object-fit: cover;" />
        <span v-else>{{ (user?.nickname || user?.username)?.[0]?.toUpperCase() || 'A' }}</span>
      </div>
      <span style="font-size: 13px;">{{ user?.nickname || user?.username || 'Admin' }}</span>
      <template #fallback>
        <div style="width: 24px; height: 24px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-size: 12px;">A</div>
        <span style="font-size: 13px;">&nbsp;</span>
      </template>
    </ClientOnly>
  </div>
</template>
