<script setup lang="ts">
import { Toaster } from 'vue-sonner'

const route = useRoute()
const router = useRouter()
const { user, clear, isLoggedIn, init: initAuth } = useAuth()
const { meta, refresh: refreshMeta } = useAdminMeta()
// 2026-06-16 新增：useDialog 在 SSR 阶段返回 noop（详见 composables/useDialog.ts）
const { state, handleConfirm, handleCancel, confirm, prompt } = useDialog()
const $dialog = { confirm, prompt }

// 3 分组导航
const navGroups = [
  {
    label: '概览',
    items: [
      { to: '/admin/dashboard', label: '仪表盘', icon: 'dashboard' }
    ]
  },
  {
    label: '内容',
    items: [
      { to: '/admin/posts',     label: '文章', icon: 'file',   badge: () => meta.value.articleCount },
      { to: '/admin/comments',  label: '评论', icon: 'message', badge: () => meta.value.pendingComments, badgeAccent: true, badgeLabel: '待审' },
      { to: '/admin/categories', label: '分类', icon: 'folder' },
      { to: '/admin/tags',       label: '标签', icon: 'tag' }
    ]
  },
  {
    label: '系统',
    items: [
      { to: '/admin/devices',   label: '设备授权', icon: 'device' },
      { to: '/admin/settings', label: '设置', icon: 'settings' }
    ]
  }
]

const isActive = (to: string) => route.path === to || route.path.startsWith(to + '/')

// 2026-06-16 改造：confirm → $dialog.confirm（Promise 包装，ESC/点遮罩 = 取消）
const handleLogout = async () => {
  const { confirmed } = await $dialog.confirm({
    title: '退出登录',
    message: '确认退出登录？',
    confirmText: '退出',
    danger: true
  })
  if (!confirmed) return
  clear()
  router.push('/admin/login')
}

// 顶栏标题
const pageTitle = computed(() => {
  const map: Record<string, string> = {
    '/admin/dashboard': '仪表盘',
    '/admin/posts': '文章管理',
    '/admin/edit': '编辑文章',
    '/admin/comments': '评论管理',
    '/admin/categories': '分类管理',
    '/admin/tags': '标签管理',
    '/admin/settings': '站点设置'
  }
  return map[route.path] || '后台'
})

// 挂载时拉一次侧边栏数据
onMounted(() => {
  // client 阶段兜底鉴权：middleware 在 SSR 阶段会跳过（localStorage 不可用），
  // 这里在 hydration 后再确认一次，未登录直接踢回 login
  initAuth()
  if (!isLoggedIn.value) {
    router.replace('/admin/login')
    return
  }
  refreshMeta()
})
</script>

<template>
  <div class="admin-layout">
    <!-- Sidebar -->
    <aside class="admin-sidebar">
      <div class="admin-sidebar-head">
        <NuxtLink to="/" style="font-family: 'DM Serif Display', serif; font-size: 18px; color: var(--primary); display: flex; align-items: center; gap: 8px;">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <!-- 2026-06-13 优化：左上角显示当前登录用户 nickname（之前硬编码 "Yuan Yi" 站名）
               用户在「显示设置 → 个人资料」改 nickname 后，保存走 updateUser 同步 useAuth 立即刷新 -->
          <ClientOnly>
            <span>{{ user?.nickname || user?.username || 'Admin' }}</span>
            <template #fallback>
              <span>加载中</span>
            </template>
          </ClientOnly>
        </NuxtLink>
      </div>

      <nav class="admin-nav">
        <div v-for="group in navGroups" :key="group.label" class="admin-nav-group">
          <div class="admin-nav-label">{{ group.label }}</div>
          <NuxtLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="admin-nav-item"
            :class="{ active: isActive(item.to) }"
          >
            <svg v-if="item.icon === 'dashboard'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="9"/><rect x="14" y="3" width="7" height="5"/><rect x="14" y="12" width="7" height="9"/><rect x="3" y="16" width="7" height="5"/></svg>
            <svg v-else-if="item.icon === 'file'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><line x1="10" y1="9" x2="8" y2="9"/></svg>
            <svg v-else-if="item.icon === 'message'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/></svg>
            <svg v-else-if="item.icon === 'folder'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
            <svg v-else-if="item.icon === 'tag'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>
            <svg v-else-if="item.icon === 'device'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>
            <svg v-else-if="item.icon === 'settings'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
            <span style="flex: 1;">{{ item.label }}</span>
            <!-- 数字 badge -->
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
        </div>

        <!-- 底部：返回前台 -->
        <div class="admin-nav-group" style="margin-top: auto;">
          <NuxtLink to="/" class="admin-nav-item" target="_blank">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
            <span>返回前台</span>
          </NuxtLink>
        </div>
      </nav>
    </aside>

    <!-- Main -->
    <div class="admin-main">
      <header class="admin-header">
        <div style="display: flex; align-items: center; gap: 12px;">
          <h2 style="font-size: 14px; font-weight: 500; color: var(--text);">{{ pageTitle }}</h2>
        </div>
        <div style="display: flex; align-items: center; gap: 4px;">
          <button @click="refreshMeta" class="icon-btn" aria-label="刷新" title="刷新数据">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>
          </button>
          <button class="icon-btn" aria-label="切换主题" title="切换主题">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>
          </button>
          <button class="icon-btn" aria-label="通知" title="通知" style="position: relative;">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/></svg>
            <span v-if="meta.pendingComments" style="position: absolute; top: 6px; right: 6px; width: 7px; height: 7px; border-radius: 50%; background: var(--accent); border: 1.5px solid var(--card);"></span>
          </button>
          <div style="display: flex; align-items: center; gap: 8px; padding: 4px 10px 4px 4px; background: var(--bg-soft); border-radius: 8px; margin-left: 4px;">
            <ClientOnly>
              <!-- 2026-06-12 修复：原来固定显示 username 首字母圈 / username 文字。
                   后台改了 nickname/avatar 之后顶栏不刷新。改成优先 avatar img + 优先 nickname 文字。 -->
              <div style="width: 24px; height: 24px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-size: 12px; overflow: hidden;">
                <img v-if="user?.avatar" :src="user.avatar" alt="avatar" style="width: 100%; height: 100%; object-fit: cover;" />
                <span v-else>{{ (user?.nickname || user?.username)?.[0]?.toUpperCase() || 'A' }}</span>
              </div>
              <span style="font-size: 13px;">{{ user?.nickname || user?.username || 'Admin' }}</span>
              <button @click="handleLogout" style="font-size: 12px; color: var(--muted); margin-left: 4px;" title="退出">↪</button>
              <template #fallback>
                <div style="width: 24px; height: 24px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-size: 12px;">A</div>
                <span style="font-size: 13px;">&nbsp;</span>
              </template>
            </ClientOnly>
          </div>
        </div>
      </header>

      <main class="admin-content">
        <slot />
      </main>
    </div>

    <!-- 2026-06-16 新增：Toast + Dialog 全局容器（用 client-only 避免 SSR mismatch） -->
    <ClientOnly>
      <Toaster position="top-right" :duration="2500" rich-colors close-button />
    </ClientOnly>
    <ClientOnly>
      <GlobalDialog
        :open="state.open"
        :title="state.title"
        :message="state.message"
        :confirm-text="state.confirmText"
        :cancel-text="state.cancelText"
        :danger="state.danger"
        :prompt="state.prompt"
        :prompt-label="state.promptLabel"
        :prompt-placeholder="state.promptPlaceholder"
        :prompt-default="state.promptDefault"
        @confirm="handleConfirm"
        @cancel="handleCancel"
      />
    </ClientOnly>
  </div>
</template>
