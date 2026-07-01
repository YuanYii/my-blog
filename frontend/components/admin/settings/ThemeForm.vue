<script setup lang="ts">
/**
 * 2026-06-30 OPT-001：主题外观配色方案。
 * - 删除原"主题模式"下拉框（mode 仅由 NavBar 切深浅控制）
 * - 新增"配色方案"下拉框（10 个推荐组合 + "自定义"）
 * - 双向同步：scheme 变 → 查 preset 写 primary/accent；primary/accent 变（且不匹配当前 preset）→ scheme 跳 'custom'
 * - "手动优先"：用户同时调主色+强调色恰好等于某个 preset 仍显示"自定义"（不自动回跳）
 */
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

const props = defineProps<{
  theme: { mode: string; primaryColor: string; accentColor: string; fontFamily: string }
  scheme: string
}>()
const emit = defineEmits<{
  (e: 'update:theme', v: any): void
  (e: 'update:scheme', v: string): void
}>()

const schemeOptions = [
  ...COLOR_SCHEMES.map(s => ({ label: s.label, value: s.id })),
  { label: '自定义', value: 'custom' },
]

const fontOptions = [
  { label: '衬线（默认）', value: 'serif' },
  { label: '无衬线', value: 'sans' },
  { label: '等宽', value: 'mono' },
]

const findPreset = (id: string) => COLOR_SCHEMES.find(s => s.id === id)

// primaryColor / accentColor 变化 → 若不等于当前 preset → scheme = 'custom'
// 已是 'custom' 不动（避免覆盖用户的"自定义"意图）
watch(
  () => [props.theme.primaryColor, props.theme.accentColor],
  ([pc, ac]) => {
    if (props.scheme === 'custom') return
    const preset = findPreset(props.scheme)
    if (!preset || preset.primary !== pc || preset.accent !== ac) {
      emit('update:scheme', 'custom')
    }
  }
)

// scheme 变化 → 查 preset map → emit primary/accent
// 切到 'custom' 不重置 picker（保留当前 HEX 值）
watch(
  () => props.scheme,
  (newScheme) => {
    if (newScheme === 'custom') return
    const preset = findPreset(newScheme)
    if (!preset) return
    if (preset.primary !== props.theme.primaryColor || preset.accent !== props.theme.accentColor) {
      emit('update:theme', { ...props.theme, primaryColor: preset.primary, accentColor: preset.accent })
    }
  }
)
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group">
      <label class="form-label">配色方案</label>
      <UiDropdownSelector :model-value="scheme" :options="schemeOptions" @update:model-value="(v: any) => $emit('update:scheme', v)" />
    </div>
    <div class="form-row-2">
      <div class="form-group" style="margin: 0;"><label class="form-label">主色</label><input :value="theme.primaryColor" @input="$emit('update:theme', { ...theme, primaryColor: ($event.target as HTMLInputElement).value })" type="color" class="form-control" style="height: 38px; padding: 2px;" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">强调色</label><input :value="theme.accentColor" @input="$emit('update:theme', { ...theme, accentColor: ($event.target as HTMLInputElement).value })" type="color" class="form-control" style="height: 38px; padding: 2px;" /></div>
    </div>
    <div class="form-group" style="margin-bottom: 0;">
      <label class="form-label">字体</label>
      <UiDropdownSelector :model-value="theme.fontFamily" :options="fontOptions" @update:model-value="(v: any) => $emit('update:theme', { ...theme, fontFamily: v })" />
    </div>
  </div>
</template>