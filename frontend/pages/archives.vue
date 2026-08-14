<script setup lang="ts">
// 2026-06-22 抽出 → composables/useMarkdownUtils.ts（formatMonthDay: 'MM-DD'）
import { formatMonthDay as formatDate } from '~/composables/useMarkdownUtils'

const { get } = usePublicApi()
const articles = ref<any[]>([])
const loading = ref(true)

// 按年分组
const grouped = computed(() => {
  const map: Record<string, any[]> = {}
  for (const a of articles.value) {
    const year = (a.publishedAt || '').substring(0, 4) || '未知'
    if (!map[year]) map[year] = []
    map[year].push(a)
  }
  return Object.entries(map).sort(([a], [b]) => b.localeCompare(a))
})

onMounted(async () => {
  try {
    const res = await get<any>('/articles/archives')
    articles.value = res.data || []
  } catch { /* ignore */ }
  finally { loading.value = false }
})
</script>

<template>
  <div>
    <header style="margin-bottom: 32px;">
      <h1 class="font-serif" style="font-size: 32px; margin-bottom: 8px;">📚 归档</h1>
      <p style="color: var(--muted); font-size: 14px;">所有文章按发布时间倒序，共 {{ articles.length }} 篇</p>
    </header>

    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <div v-else-if="!articles.length" style="padding: 40px; text-align: center; color: var(--muted);">还没有文章</div>

    <div v-else style="display: flex; flex-direction: column; gap: 32px;">
      <section v-for="[year, items] in grouped" :key="year">
        <h2 class="font-serif" style="font-size: 24px; color: var(--primary); margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid var(--line);">
          {{ year }}
          <span style="font-size: 13px; color: var(--muted); font-family: 'Outfit'; margin-left: 8px;">{{ items.length }} 篇</span>
        </h2>
        <ul style="list-style: none; padding: 0;">
          <li v-for="a in items" :key="a.id" style="display: flex; align-items: baseline; gap: 12px; padding: 8px 0; border-bottom: 1px dashed var(--line-soft);">
            <span class="font-mono" style="color: var(--muted); font-size: 13px; min-width: 50px;">{{ formatDate(a.publishedAt) }}</span>
            <NuxtLink :to="`/post/${a.slug}`" style="flex: 1; color: var(--text); text-decoration: none; font-size: 15px;">{{ a.title }}</NuxtLink>
            <span class="font-mono" style="color: var(--muted); font-size: 12px;">{{ a.viewCount || 0 }} 👁</span>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>
