<script setup lang="ts">
/**
 * 2026-06-23 OPT-005：admin 侧栏导航整体抽出（一级菜单 + 二级菜单父项 + 子菜单 + 返回前台），
 * 配合 AdminNavIcon 把 layouts/admin.vue 内联 SVG 全部移除，文件行数控制 < 300。
 */
type NavItem = {
  to: string
  label: string
  icon: string
  badge?: () => number
  badgeAccent?: boolean
  badgeLabel?: string
  children?: NavItem[]
}

defineProps<{
  groups: { label: string; items: NavItem[] }[]
  isActive: (to: string) => boolean
  isExactActive: (to: string) => boolean
  isSettingsOpen: boolean
}>()

defineEmits<{ toggleSettings: [] }>()
</script>

<template>
  <nav class="admin-nav">
    <div v-for="group in groups" :key="group.label" class="admin-nav-group">
      <div class="admin-nav-label">{{ group.label }}</div>
      <template v-for="item in group.items" :key="item.to">
        <!-- 含子菜单：父项渲染为可折叠按钮 -->
        <template v-if="item.children && item.children.length">
          <button
            type="button"
            class="admin-nav-item"
            :class="{ active: isActive(item.to) }"
            @click="$emit('toggleSettings')"
            :aria-expanded="isSettingsOpen"
          >
            <AdminNavIcon :name="item.icon" />
            <span style="flex: 1; text-align: left;">{{ item.label }}</span>
            <span :style="{ display: 'inline-flex', transition: 'transform 0.15s', transform: isSettingsOpen ? 'rotate(90deg)' : 'rotate(0deg)' }">
              <AdminNavIcon name="chevron-right" :size="12" />
            </span>
          </button>
          <div v-show="isSettingsOpen" class="admin-nav-subnav">
            <NuxtLink
              v-for="sub in item.children"
              :key="sub.to"
              :to="sub.to"
              class="admin-nav-subitem"
              :class="{ active: isExactActive(sub.to) }"
            >
              <span class="admin-nav-subdot" aria-hidden="true"></span>
              <span style="flex: 1;">{{ sub.label }}</span>
            </NuxtLink>
          </div>
        </template>
        <!-- 普通菜单项 -->
        <NuxtLink
          v-else
          :to="item.to"
          class="admin-nav-item"
          :class="{ active: isActive(item.to) }"
        >
          <AdminNavIcon :name="item.icon" />
          <span style="flex: 1;">{{ item.label }}</span>
          <span
            v-if="item.badge && item.badge()"
            :style="{
              fontFamily: 'JetBrains Mono, monospace',
              fontSize: '10px',
              color: item.badgeAccent ? 'var(--accent)' : 'var(--muted)',
              fontWeight: item.badgeAccent ? '500' : '400'
            }"
          >{{ item.badge() }} {{ item.badgeLabel || '' }}</span>
        </NuxtLink>
      </template>
    </div>

    <!-- 底部：返回前台 -->
    <div class="admin-nav-group" style="margin-top: auto;">
      <NuxtLink to="/" class="admin-nav-item" target="_blank">
        <AdminNavIcon name="home" />
        <span>返回前台</span>
      </NuxtLink>
    </div>
  </nav>
</template>
