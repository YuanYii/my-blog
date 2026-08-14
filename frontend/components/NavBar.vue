<template>
  <nav ref="navRef" class="sticky top-0 z-50 backdrop-blur-md" style="background: color-mix(in srgb, var(--color-bg) 85%, transparent); border-bottom: 1px solid var(--color-line);">
    <div class="max-w-5xl mx-auto px-4 md:px-8 h-14 flex items-center justify-between">
      <NuxtLink to="/" class="flex items-center gap-2">
        <div class="w-7 h-7 rounded-lg flex items-center justify-center text-white text-sm font-semibold overflow-hidden" style="background: var(--color-primary);">
          <img v-if="blog?.logo" :src="blog.logo" alt="logo" class="w-full h-full object-cover" />
          <span v-else>{{ (blog?.title || '加载中')[0] }}</span>
        </div>
        <span class="font-serif-display text-lg">{{ blog?.title || '加载中' }}</span>
      </NuxtLink>

      <!-- 桌面端导航菜单（2026-06-27 DEV-002：i18n 化，由 useI18n 驱动） -->
      <div class="hidden md:flex items-center gap-1">
        <NuxtLink to="/" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">{{ t('nav.home') }}</NuxtLink>
        <NuxtLink to="/categories" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">分类</NuxtLink>
        <NuxtLink to="/archives" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">{{ t('nav.archives') }}</NuxtLink>
        <NuxtLink to="/tags" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">{{ t('nav.tags') }}</NuxtLink>
        <NuxtLink to="/about" class="px-3 py-1.5 rounded-md text-sm transition-colors" style="color: var(--color-text-2);" active-class="active-link">{{ t('nav.about') }}</NuxtLink>
      </div>

      <div class="flex items-center gap-2">
        <!-- 汉堡菜单按钮（仅手机端显示） -->
        <button
          class="md:hidden min-w-[44px] min-h-[44px] rounded-md flex items-center justify-center transition-colors active:bg-[var(--primary-soft)]"
          style="color: var(--color-text-2);"
          @click="toggleMenu"
          aria-label="切换菜单"
        >
          <!-- 菜单关闭状态：显示汉堡图标 -->
          <svg v-if="!isMenuOpen" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="pointer-events:none"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
          <!-- 菜单展开状态：显示关闭图标 -->
          <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="pointer-events:none"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>

        <!-- 2026-06-28 OPT-002（autopush）：搜索入口，按 advanced.enableSearch 显隐 -->
        <div v-if="siteFlags.enableSearch" class="relative" ref="searchRef">
          <button
            class="w-9 h-9 rounded-md flex items-center justify-center transition-colors"
            style="color: var(--color-text-2);"
            @click="toggleSearch"
            :aria-expanded="isSearchOpen"
            aria-label="搜索"
            title="搜索"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          </button>
          <Transition name="search-pop">
            <div
              v-if="isSearchOpen"
              class="absolute right-0 top-11 w-72 rounded-lg border shadow-lg p-3"
              style="background: var(--card); border-color: var(--color-line); z-index: 60;"
            >
              <form @submit.prevent="onSearchSubmit" class="flex items-center gap-2">
                <input
                  ref="searchInputRef"
                  v-model="searchKeyword"
                  type="search"
                  class="form-control flex-1"
                  placeholder="搜索文章…（至少 2 字符）"
                  autocomplete="off"
                />
                <button type="submit" class="btn btn-primary btn-sm">搜索</button>
              </form>
            </div>
          </Transition>
        </div>

        <button
          class="w-9 h-9 rounded-md flex items-center justify-center transition-colors"
          style="color: var(--color-text-2);"
          @click="cycleColorMode"
          :aria-label="themeTooltip"
          :title="themeTooltip"
        >
          <!-- 自动模式（跟随系统昼夜）：半月半日/系统图标 -->
          <svg v-if="colorMode === 'auto'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="pointer-events:none">
            <rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/>
          </svg>
          <!-- 强制浅色模式：太阳图标 -->
          <svg v-else-if="colorMode === 'light'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="pointer-events:none">
            <circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
          </svg>
          <!-- 强制深色模式：月亮图标 -->
          <svg v-else width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="pointer-events:none">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
          </svg>
        </button>
        <!-- 后台管理入口：桌面端显示 inline-flex，手机端在下拉菜单里显示 -->
        <NuxtLink v-if="isApprovedDevice" to="/admin/login" class="hidden md:inline-flex w-9 h-9 rounded-md items-center justify-center transition-colors" style="color: var(--color-text-2);" title="后台管理">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
        </NuxtLink>
      </div>
    </div>

    <!-- 手机端下拉菜单 -->
    <Transition name="menu-slide">
      <div
        v-if="isMenuOpen"
        class="md:hidden absolute top-14 left-0 right-0 border-b shadow-lg"
        style="background: var(--card); border-color: var(--color-line); z-index: 49;"
      >
        <div class="max-w-5xl mx-auto px-4 py-2 flex flex-col gap-1">
          <NuxtLink to="/" class="mobile-menu-item" @click="closeMenu">{{ t('nav.home') }}</NuxtLink>
          <NuxtLink to="/categories" class="mobile-menu-item" @click="closeMenu">分类</NuxtLink>
          <NuxtLink to="/archives" class="mobile-menu-item" @click="closeMenu">{{ t('nav.archives') }}</NuxtLink>
          <NuxtLink to="/tags" class="mobile-menu-item" @click="closeMenu">{{ t('nav.tags') }}</NuxtLink>
          <NuxtLink to="/about" class="mobile-menu-item" @click="closeMenu">{{ t('nav.about') }}</NuxtLink>
          <!-- 2026-06-28 OPT-002：手机端下拉菜单也补一个搜索入口 -->
          <NuxtLink v-if="siteFlags.enableSearch" to="/search" class="mobile-menu-item" @click="closeMenu">搜索</NuxtLink>
          <template v-if="isApprovedDevice">
            <div class="my-1 border-t" style="border-color: var(--color-line);"></div>
            <NuxtLink to="/admin/login" class="mobile-menu-item" @click="closeMenu">{{ t('nav.admin') }}</NuxtLink>
          </template>
        </div>
      </div>
    </Transition>
  </nav>
</template>

<script setup lang="ts">
// 2026-06-12 新增：从公开端点拉站点信息，让 logo / title 反映 admin 在后台保存的值。
const { request } = usePublicApi()
const blogRef = useState<any>('site-blog-data', () => ({}))
const blog = computed(() => blogRef.value || {})

// 2026-06-27 DEV-002：i18n
const { t } = useI18n()

// 2026-06-28 OPT-001/002（autopush）：站点能力开关（enableRss / enableSearch）
const { flags: siteFlags } = useSiteFlags()

// 后台管理入口：已授权设备才显示
const { deviceId } = useDevice()
const isApprovedDevice = ref(false)
onMounted(async () => {
  if (!deviceId.value) return
  try {
    const res = await request<any>('/public/device/check', {
      method: 'GET',
      headers: { 'X-Device-Id': deviceId.value }
    })
    isApprovedDevice.value = !!res?.data?.approved
  } catch {
    isApprovedDevice.value = false
  }
})

// 移动端汉堡菜单
const isMenuOpen = ref(false)
const route = useRoute()
const router = useRouter()

const toggleMenu = () => { isMenuOpen.value = !isMenuOpen.value }
const closeMenu = () => { isMenuOpen.value = false }

// 路由变化时自动关闭菜单
watch(() => route.fullPath, () => { closeMenu() })

// 点击菜单外区域关闭
const navRef = ref<HTMLElement | null>(null)
onMounted(() => {
  document.addEventListener('click', (e) => {
    if (isMenuOpen.value && navRef.value && !navRef.value.contains(e.target as Node)) {
      closeMenu()
    }
  })
})

// 2026-06-28 OPT-002（autopush）：搜索弹层
const isSearchOpen = ref(false)
const searchKeyword = ref('')
const searchRef = ref<HTMLElement | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)

const toggleSearch = () => {
  isSearchOpen.value = !isSearchOpen.value
  if (isSearchOpen.value) {
    // 展开后聚焦输入框（nextTick 等 transition 完成）
    nextTick(() => searchInputRef.value?.focus())
  }
}
const closeSearch = () => { isSearchOpen.value = false }

const onSearchSubmit = () => {
  const q = searchKeyword.value.trim()
  if (!q) return
  if (q.length < 2) { useToast().warning('搜索关键词至少 2 个字符'); return }
  closeSearch()
  router.push({ path: '/search', query: { q } })
}

if (import.meta.client) {
  onMounted(() => {
    // 点击搜索弹层外区域关闭
    document.addEventListener('click', (e) => {
      if (isSearchOpen.value && searchRef.value && !searchRef.value.contains(e.target as Node)) {
        closeSearch()
      }
    })
    // ESC 关闭搜索弹层
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isSearchOpen.value) closeSearch()
    })
  })
}

// 2026-08-14 DEV-002：三态主题与系统昼夜调度
const { colorMode, isDark, cycleColorMode } = useSiteTheme()

const themeTooltip = computed(() => {
  if (colorMode.value === 'auto') {
    return `当前：自动跟随系统昼夜（${isDark.value ? '夜间深色' : '日间浅色'}）· 点击切换浅色`
  }
  if (colorMode.value === 'light') {
    return '当前：浅色模式 · 点击切换深色'
  }
  return '当前：深色模式 · 点击切换自动跟随'
})
</script>

<style scoped>
.active-link {
  color: var(--color-primary) !important;
  background: var(--color-primary-soft);
  font-weight: 500;
}

.mobile-menu-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--color-text-2);
  transition: background 0.15s, color 0.15s;
  text-decoration: none;
}
.mobile-menu-item:hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.mobile-menu-item.router-link-exact-active {
  color: var(--color-primary);
  background: var(--color-primary-soft);
  font-weight: 500;
}

/* 下拉菜单过渡动画 */
.menu-slide-enter-active,
.menu-slide-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.menu-slide-enter-from,
.menu-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 2026-06-28 OPT-002：搜索弹层过渡 */
.search-pop-enter-active,
.search-pop-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}
.search-pop-enter-from,
.search-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
}
</style>
