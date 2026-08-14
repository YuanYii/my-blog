<script setup lang="ts">
// 2026-06-28 OPT-002（autopush）：前台搜索结果页。
// 走 /api/v1/public/articles?keyword=...&page=1&size=20
// 后端 ArticleController 已按 advanced.enableSearch 控制 keyword 拒绝（DEV-003）

const route = useRoute()
const { get } = usePublicApi()

const keyword = computed(() => (route.query.q as string)?.trim() || '')
const articles = ref<any[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(Number(route.query.page) || 1)
const size = 20
// 2026-06-30 BUG-001：结果过多提示阈值（size=20，>5 页 ≈ 100 条认为过多）。
// 用户搜通用词（如"的"、"用"、"？"）时后端 articleService.list 会命中全部，
// 没有这条提示用户感知不到"搜索是否生效"——提示缩小关键词范围让 UX 闭环。
const isResultTooMany = computed(() => total.value > size * 5)

async function fetchResults() {
  if (!keyword.value) {
    articles.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await get<any>('/articles', {
      keyword: keyword.value, page: page.value, size
    })
    articles.value = res.data?.records || res.data?.list || []
    total.value = res.data?.total ?? articles.value.length
  } catch (e: any) {
    // 403 通常是 admin 关闭了 enableSearch；显式提示
    articles.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// 路由 query 变化时重新拉（支持点分页）
watch(() => [route.query.q, route.query.page], () => {
  page.value = Number(route.query.page) || 1
  fetchResults()
}, { immediate: true })

// SEO：title 跟 keyword 联动
const headTitle = computed(() => keyword.value ? `搜索：${keyword.value}` : '搜索')
useHead({ title: headTitle })
</script>

<template>
  <div>
    <header style="margin-bottom: 32px;">
      <h1 class="font-serif" style="font-size: 32px; margin-bottom: 8px;">🔍 搜索</h1>
      <p v-if="keyword" style="color: var(--muted); font-size: 14px;">
        关键词：<strong style="color: var(--text);">{{ keyword }}</strong>
        <span v-if="!loading"> · 共 {{ total }} 条结果</span>
        <span v-if="!loading && isResultTooMany" style="color: var(--color-warning, #d97706); margin-left: 8px;">
          · 建议缩小关键词范围以获得更精准结果
        </span>
      </p>
      <p v-else style="color: var(--muted); font-size: 14px;">从顶部导航栏的搜索图标输入关键词</p>
    </header>

    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">搜索中…</div>

    <div v-else-if="!keyword" style="padding: 40px; text-align: center; color: var(--muted);">请输入要搜索的关键词</div>

    <div v-else-if="!articles.length" style="padding: 40px; text-align: center; color: var(--muted);">
      没有找到与「{{ keyword }}」相关的文章
    </div>

    <ul v-else style="list-style: none; padding: 0; display: flex; flex-direction: column; gap: 16px;">
      <li
        v-for="a in articles"
        :key="a.id"
        style="padding: 16px 20px; border: 1px solid var(--line-soft); border-radius: 12px; transition: border-color 0.15s;"
      >
        <NuxtLink
          :to="`/post/${a.slug}`"
          style="display: block; color: var(--text); text-decoration: none; font-size: 17px; font-weight: 500; margin-bottom: 6px;"
        >{{ a.title }}</NuxtLink>
        <p
          v-if="a.summary"
          style="color: var(--text-2); font-size: 14px; line-height: 1.6; margin: 0 0 8px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;"
        >{{ a.summary }}</p>
        <div style="display: flex; gap: 12px; font-size: 12px; color: var(--muted); font-family: 'JetBrains Mono', monospace;">
          <time v-if="a.publishedAt">{{ a.publishedAt.substring(0, 10) }}</time>
          <span v-if="a.categoryName">· {{ a.categoryName }}</span>
          <span>· {{ a.viewCount || 0 }} 👁</span>
        </div>
      </li>
    </ul>
  </div>
</template>
