<script setup lang="ts">
// 2026-06-22 抽出 → composables/useMarkdownUtils.ts（统一 formatDate）
import { formatDate } from '~/composables/useMarkdownUtils'

const { get } = usePublicApi()

const tags = ref<any[]>([])
const activeTag = ref<number | null>(null)
const loading = ref(true)

// 2026-06-13 修复（BUG-049）：去掉 articles 全表拉取，tag 计数改用 t.articleCount 字段。
// 2026-06-22 修复（BUG-XXX "点击 tag 看到的是空列表"）：
// 之前 filteredArticles 永远返回 []，因为产品决策"标签云是导航入口"关闭了，
// 但模板 line 55 又写了"点击查看相关文章"——文案与实现矛盾。
// 改：activeTag 变化时主动调 /articles?tagId=<id> 拉该 tag 下文章（后端 ArticleController
// line 31 早就支持 tagId 参数，详见 ArticleService.list:67-69 的实现）。
const filteredArticles = ref<any[]>([])
const tagArticlesLoading = ref(false)

const loadAll = async () => {
  try {
    const t = await get<any>('/articles/tags')
    tags.value = t.data || []
  } catch { /* ignore */ }
  finally { loading.value = false }
}

// 2026-06-22 新增：activeTag 变化 → 拉该 tag 下文章（取消选择时清空）
watch(activeTag, async (newTag, oldTag) => {
  if (newTag === oldTag) return
  if (newTag == null) {
    filteredArticles.value = []
    return
  }
  tagArticlesLoading.value = true
  try {
    const res = await get<any>('/articles', { tagId: newTag, size: 50 })
    filteredArticles.value = res.data?.records || []
  } catch {
    filteredArticles.value = []
  } finally {
    tagArticlesLoading.value = false
  }
})

// 每个 tag 的文章数（直接用后端返回的 articleCount）
const tagCount = (tagId: number) => {
  const t = tags.value.find(x => x.id === tagId)
  return Number(t?.articleCount) || 0
}

// 字号按文章数对数缩放
// 2026-06-13 修复（BUG-079）：公开页 tags.vue 与 admin/tags.vue 字号阈值不一致
// （公开 2/5/10 阶，admin 2/4/7 阶）—— 同一标签在两处字号不同非常诡异。
// 统一到 admin 阈值（更密的阶数，视觉对比更明显）。
const tagSize = (count: number) => {
  if (!count) return '13px'
  if (count < 2) return '14px'
  if (count < 4) return '16px'
  if (count < 7) return '19px'
  return '22px'
}

onMounted(loadAll)
</script>

<template>
  <div>
    <header style="margin-bottom: 32px;">
      <h1 class="font-serif" style="font-size: 32px; margin-bottom: 8px;">🏷️ 标签</h1>
      <p style="color: var(--muted); font-size: 14px;">共 {{ tags.length }} 个标签 · 点击查看相关文章</p>
    </header>

    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <template v-else>
      <!-- 标签云 -->
      <div class="card" style="padding: 24px; margin-bottom: 24px;">
        <div style="display: flex; flex-wrap: wrap; gap: 12px; justify-content: center; align-items: center; min-height: 80px;">
          <button v-for="t in tags" :key="t.id" @click="activeTag = activeTag === t.id ? null : t.id"
            :style="{
              padding: '6px 14px',
              borderRadius: '14px',
              fontSize: tagSize(tagCount(t.id)),
              fontWeight: '500',
              background: activeTag === t.id ? 'var(--primary)' : 'var(--bg-soft)',
              color: activeTag === t.id ? 'white' : 'var(--text-2)',
              border: 'none',
              cursor: 'pointer',
              transition: 'all 0.15s'
            }"
          >
            {{ t.name }} <span style="opacity: 0.6; font-size: 0.8em;">{{ tagCount(t.id) }}</span>
          </button>
        </div>
      </div>

      <!-- 选中 tag 的文章 -->
      <div v-if="activeTag">
        <h3 class="font-serif" style="font-size: 18px; margin-bottom: 12px;">
          标签「{{ tags.find(t => t.id === activeTag)?.name }}」下的文章
        </h3>
        <div v-if="tagArticlesLoading" style="padding: 20px; text-align: center; color: var(--muted);">加载中…</div>
        <div v-else-if="!filteredArticles.length" style="padding: 20px; text-align: center; color: var(--muted);">该标签下还没有文章</div>
        <ul v-else style="list-style: none; padding: 0;">
          <li v-for="a in filteredArticles" :key="a.id" style="padding: 10px 0; border-bottom: 1px dashed var(--line-soft);">
            <NuxtLink :to="`/post/${a.slug}`" style="color: var(--text); font-size: 15px;">{{ a.title }}</NuxtLink>
            <span style="margin-left: 12px; color: var(--muted); font-size: 12px;">{{ formatDate(a.publishedAt || a.createdAt) }}</span>
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>
