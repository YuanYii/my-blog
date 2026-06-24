<script setup lang="ts">
import { Toaster } from 'vue-sonner'

const route = useRoute()
const router = useRouter()
const { user, clear, isLoggedIn, init: initAuth } = useAuth()
const { meta, refresh: refreshMeta } = useAdminMeta()
// 2026-06-16 新增：useDialog 在 SSR 阶段返回 noop（详见 composables/useDialog.ts）
const { state, handleConfirm, handleCancel, confirm, prompt } = useDialog()
const $dialog = { confirm, prompt }

// 移动端抽屉状态
const drawerOpen = ref(false)

const toggleDrawer = () => {
  if (!import.meta.client) return
  drawerOpen.value = !drawerOpen.value
  document.body.style.overflow = drawerOpen.value ? 'hidden' : ''
}

const closeDrawer = () => {
  if (!import.meta.client) return
  drawerOpen.value = false
  document.body.style.overflow = ''
}

// 路由变化时自动关闭抽屉
watch(() => route.path, () => {
  closeDrawer()
})

// 2026-06-23 DEV-001：nav 升级为支持 children?: NavItem[]（站点设置改为可折叠二级菜单）
type NavItem = {
  to: string
  label: string
  icon: string
  badge?: () => number
  badgeAccent?: boolean
  badgeLabel?: string
  children?: NavItem[]
}

const navGroups: { label: string; items: NavItem[] }[] = [
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
      { to: '/admin/backup',    label: '数据备份', icon: 'backup' },
      { to: '/admin/restore',   label: '数据恢复', icon: 'restore' },
      {
        to: '/admin/settings', label: '站点设置', icon: 'settings',
        children: [
          { to: '/admin/settings/profile',     label: '个人资料', icon: 'sub' },
          { to: '/admin/settings/password',    label: '修改密码', icon: 'sub' },
          { to: '/admin/settings/blog',        label: '站点信息', icon: 'sub' },
          { to: '/admin/settings/techstack',   label: '技术栈',   icon: 'sub' },
          { to: '/admin/settings/experience',  label: '个人经历', icon: 'sub' },
          { to: '/admin/settings/theme',       label: '主题外观', icon: 'sub' },
          { to: '/admin/settings/social',      label: '社交账号', icon: 'sub' },
          { to: '/admin/settings/preferences', label: '偏好设置', icon: 'sub' },
          { to: '/admin/settings/advanced',    label: '高级',     icon: 'sub' }
        ]
      }
    ]
  }
]

const isActive = (to: string) => route.path === to || route.path.startsWith(to + '/')
// 二级菜单精确匹配（子项命中以"等于"为准，避免父路径误判）
const isExactActive = (to: string) => route.path === to

// 二级菜单展开状态（localStorage 持久化；路径命中 /admin/settings/* 自动展开）
const isSettingsOpen = ref(false)
const SETTINGS_OPEN_KEY = 'admin.settings.expanded'
const toggleSettings = () => {
  isSettingsOpen.value = !isSettingsOpen.value
  if (import.meta.client) localStorage.setItem(SETTINGS_OPEN_KEY, isSettingsOpen.value ? '1' : '0')
}
watch(() => route.path, (p) => {
  if (p.startsWith('/admin/settings')) isSettingsOpen.value = true
}, { immediate: true })

// 2026-06-16 改造：confirm → $dialog.confirm（Promise 包装，ESC/点遮罩 = 取消）
const handleLogout = async () => {
  const { confirmed } = await $dialog.confirm({
    title: '退出登录', message: '确认退出登录？', confirmText: '退出', danger: true
  })
  if (!confirmed) return
  clear()
  router.push('/admin/login')
}

// 顶栏标题
// OPT-009：进入站点设置后，顶栏标题始终显示当前二级菜单名称（不再显示"站点设置"）
const pageTitle = computed(() => {
  const map: Record<string, string> = {
    '/admin/dashboard': '仪表盘',
    '/admin/posts': '文章管理',
    '/admin/edit': '编辑文章',
    '/admin/comments': '评论管理',
    '/admin/categories': '分类管理',
    '/admin/tags': '标签管理',
    '/admin/devices': '设备授权',
    '/admin/backup': '数据备份',
    '/admin/restore': '数据恢复',
    '/admin/settings':             '个人资料',
    '/admin/settings/':            '个人资料',
    '/admin/settings/profile':     '个人资料',
    '/admin/settings/password':    '修改密码',
    '/admin/settings/blog':        '站点信息',
    '/admin/settings/techstack':   '技术栈',
    '/admin/settings/experience':  '个人经历',
    '/admin/settings/theme':       '主题外观',
    '/admin/settings/social':      '社交账号',
    '/admin/settings/preferences': '偏好设置',
    '/admin/settings/advanced':    '高级'
  }
  return map[route.path] || '后台'
})

// 主题切换：复用前台 NavBar 同一套机制（.dark class + localStorage('theme')）
// 默认深色：首次访问（localStorage 无值）即进入深色模式
const isDark = ref(true)
const toggleTheme = () => {
  isDark.value = !isDark.value
  if (import.meta.client) {
    document.documentElement.classList.toggle('dark', isDark.value)
    localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
  }
}

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
  // 与全局主题状态同步（app.vue 已在首屏注入 .dark，这里只对齐 isDark 标记）
  const saved = localStorage.getItem('theme')
  isDark.value = saved !== 'light'

  // DEV-001：恢复二级菜单展开状态（路径命中 settings 子路径时已自动展开，这里仅恢复人工折叠/展开偏好）
  if (!route.path.startsWith('/admin/settings')) {
    const expanded = localStorage.getItem(SETTINGS_OPEN_KEY)
    if (expanded === '1') isSettingsOpen.value = true
  }

  // 移动端抽屉：Esc 关闭 + resize 重置
  if (import.meta.client) {
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && drawerOpen.value) closeDrawer()
    })
    window.addEventListener('resize', () => {
      if (window.innerWidth > 768 && drawerOpen.value) closeDrawer()
    })
  }

  onBeforeUnmount(() => {
    if (import.meta.client) document.body.style.overflow = ''
  })
})
</script>

<template>
  <div class="admin-layout">
    <!-- Sidebar -->
    <aside class="admin-sidebar" :class="{ open: drawerOpen }">
      <div class="admin-sidebar-head">
        <NuxtLink to="/" style="font-family: 'DM Serif Display', serif; font-size: 18px; color: var(--primary); display: flex; align-items: center; gap: 8px;">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <ClientOnly>
            <span>{{ user?.nickname || user?.username || 'Admin' }}</span>
            <template #fallback><span>加载中</span></template>
          </ClientOnly>
        </NuxtLink>
      </div>
      <AdminSidebarNav
        :groups="navGroups"
        :is-active="isActive"
        :is-exact-active="isExactActive"
        :is-settings-open="isSettingsOpen"
        @toggle-settings="toggleSettings"
      />
    </aside>

    <!-- 移动端抽屉遮罩 -->
    <div v-if="drawerOpen" class="drawer-overlay" @click="closeDrawer" />

    <!-- Main -->
    <div class="admin-main">
      <header class="admin-header">
        <button class="hamburger-btn" @click="toggleDrawer" aria-label="打开菜单">
          <AdminNavIcon name="menu" :size="20" />
        </button>
        <div style="display: flex; align-items: center; gap: 12px;">
          <h2 style="font-size: 14px; font-weight: 500; color: var(--text);">{{ pageTitle }}</h2>
        </div>
        <div style="display: flex; align-items: center; gap: 4px;">
          <ClientOnly>
            <button @click="toggleTheme" class="icon-btn" aria-label="切换主题" :title="isDark ? '切换到亮色' : '切换到暗色'">
              <AdminNavIcon :name="isDark ? 'moon' : 'sun'" :size="18" />
            </button>
          </ClientOnly>
          <AdminUserChip :user="user" />
          <ClientOnly>
            <button @click="handleLogout" class="icon-btn" aria-label="退出登录" title="退出登录">
              <AdminNavIcon name="logout" :size="18" />
            </button>
          </ClientOnly>
        </div>
      </header>
      <main class="admin-content"><slot /></main>
    </div>

    <!-- 2026-06-16 新增：Toast + Dialog 全局容器（用 client-only 避免 SSR mismatch） -->
    <ClientOnly>
      <Toaster position="top-right" :duration="2500" rich-colors close-button />
    </ClientOnly>
    <ClientOnly>
      <GlobalDialog
        :open="state.open" :title="state.title" :message="state.message"
        :confirm-text="state.confirmText" :cancel-text="state.cancelText" :danger="state.danger"
        :prompt="state.prompt" :prompt-label="state.promptLabel" :prompt-placeholder="state.promptPlaceholder" :prompt-default="state.promptDefault"
        @confirm="handleConfirm" @cancel="handleCancel"
      />
    </ClientOnly>
  </div>
</template>
