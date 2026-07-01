<script setup lang="ts">
/**
 * 2026-06-30 OPT-001：主题外观配色方案。
 * - state 新增 scheme 字段（前端本地映射，后端 theme section 不入库）
 * - 加载时按 primaryColor/accentColor 反查匹配 preset，匹配失败填 'custom'
 * - save 时显式剥离 scheme 字段——因为后端 SiteSettingsService.merge() 会
 *   putAll(partial)，任何未知字段都会被静默入库 theme section 污染 DB
 *   （任务文档验证项 #16 描述"请求 body 含 scheme 不报错也不入库"与现状冲突；
 *    本实现选择"前端剥离"以满足"DB 不存 scheme"硬要求，且不动后端）
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

interface SchemePreset { id: string; label: string; primary: string; accent: string }
const COLOR_SCHEMES: SchemePreset[] = [
  { id: 'forest-morning',   label: '森林晨光', primary: '#2f6f5e', accent: '#c97b3f' },
  { id: 'deep-sea-sunset',  label: '深海落日', primary: '#1e3a8a', accent: '#fb7185' },
  { id: 'lavender-field',   label: '薰衣草田', primary: '#6d28d9', accent: '#f9a8d4' },
  { id: 'ink-bamboo-amber', label: '墨竹琥珀', primary: '#1c1917', accent: '#f59e0b' },
  { id: 'cream-forest',     label: '奶油森林', primary: '#f5f5dc', accent: '#2f6f5e' },
  { id: 'terracotta-beige', label: '赤陶米色', primary: '#a0522d', accent: '#f5e6d3' },
  { id: 'dusk-blue-gray',   label: '暮霭蓝灰', primary: '#475569', accent: '#fbbf24' },
  { id: 'sakura-gray',      label: '樱粉青灰', primary: '#db2777', accent: '#94a3b8' },
  { id: 'matcha-chestnut',  label: '抹茶栗色', primary: '#84cc16', accent: '#7c2d12' },
  { id: 'mist-white',       label: '薄雾白',   primary: '#0f172a', accent: '#06b6d4' },
]

// 默认值与 DB siteSettingsService.defaultTheme() 对齐；scheme 默认填"森林晨光"
// (恰好命中 DB 默认 #2f6f5e/#c97b3f，新部署首次进入即显示"森林晨光")
const state = reactive<{
  mode: string; primaryColor: string; accentColor: string; fontFamily: string; scheme: string
}>({
  mode: 'auto',
  primaryColor: '#2f6f5e',
  accentColor: '#c97b3f',
  fontFamily: 'serif',
  scheme: 'forest-morning',
})

const saving = ref(false)
const message = ref('')
const { get, put } = useAdminApi()

const load = async () => {
  try {
    const res = await get<any>('/admin/settings/theme')
    if (res?.data) Object.assign(state, res.data)
  } catch { /* 默认值 */ }
  // 加载完成后按 primaryColor/accentColor 反查匹配 preset
  const matched = COLOR_SCHEMES.find(s => s.primary === state.primaryColor && s.accent === state.accentColor)
  state.scheme = matched ? matched.id : 'custom'
}

const save = async () => {
  saving.value = true
  message.value = ''
  try {
    // 剥离 scheme 字段，避免后端 merge 把 scheme 静默入库（见上方注释）
    const { scheme, ...body } = state
    await put('/admin/settings/theme', body)
    message.value = '已保存 ✓'
    setTimeout(() => (message.value = ''), 2000)
  } catch (e: any) {
    message.value = '保存失败：' + (e?.data?.message || e?.message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <AdminSettingsThemeForm :theme="state" :scheme="state.scheme" @update:theme="Object.assign(state, $event)" @update:scheme="(v: string) => (state.scheme = v)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>