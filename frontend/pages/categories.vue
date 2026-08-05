<script setup lang="ts">
import { formatDate } from '~/composables/useMarkdownUtils'

const { get } = usePublicApi()

const categories = ref<any[]>([])
const activeCategory = ref<number | null>(null)
const loading = ref(true)

// 分页状态
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const jumpInput = ref('')
const sizeOptions = [5, 10, 20, 50]

const filteredArticles = ref<any[]>([])
const categoryArticlesLoading = ref(false)

const loadAll = async () => {
  try {
    const res = await get<any>('/articles/categories')
    categories.value = res.data || []
  } catch { /* ignore */ }
  finally { loading.value = false }
}

const fetchArticles = async () => {
  if (activeCategory.value == null) {
    filteredArticles.value = []
    total.value = 0
    return
  }
  categoryArticlesLoading.value = true
  try {
    const res = await get<any>('/articles', {
      categoryId: activeCategory.value,
      page: currentPage.value,
      size: pageSize.value
    })
    filteredArticles.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    filteredArticles.value = []
    total.value = 0
  } finally {
    categoryArticlesLoading.value = false
  }
}

// 监听分类切换：重置页码并重新拉取
watch(activeCategory, (newCat) => {
  currentPage.value = 1
  jumpInput.value = ''
  if (newCat != null) {
    fetchArticles()
  } else {
    filteredArticles.value = []
    total.value = 0
  }
})

// 监听页码/单页条数变化
watch([currentPage, pageSize], () => {
  if (activeCategory.value != null) {
    fetchArticles()
  }
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const pageList = computed(() => {
  const tp = totalPages.value
  const cur = currentPage.value
  if (tp <= 7) return Array.from({ length: tp }, (_, i) => i + 1)
  if (cur <= 4) return [1, 2, 3, 4, 5, '…', tp]
  if (cur >= tp - 3) return [1, '…', tp - 4, tp - 3, tp - 2, tp - 1, tp]
  return [1, '…', cur - 1, cur, cur + 1, '…', tp]
})

const goPage = (p: number | string) => {
  if (typeof p !== 'number') return
  if (p < 1 || p > totalPages.value || p === currentPage.value) return
  currentPage.value = p
  if (import.meta.client) {
    nextTick(() => {
      document.querySelector('.category-article-list')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }
}

const onSizeChange = (e: Event) => {
  pageSize.value = Number((e.target as HTMLSelectElement).value)
  currentPage.value = 1
}

const onJump = () => {
  const n = parseInt(jumpInput.value)
  if (!isNaN(n)) {
    goPage(n)
    jumpInput.value = ''
  }
}

const categoryCount = (catId: number) => {
  const c = categories.value.find(x => x.id === catId)
  return Number(c?.articleCount) || 0
}

const categorySize = (count: number) => {
  if (!count) return '14px'
  if (count < 2) return '15px'
  if (count < 4) return '17px'
  if (count < 7) return '19px'
  return '22px'
}

onMounted(loadAll)
</script>

<template>
  <div>
    <header style="margin-bottom: 32px;">
      <h1 class="font-serif" style="font-size: 32px; margin-bottom: 8px;">📁 分类</h1>
      <p style="color: var(--muted); font-size: 14px;">共 {{ categories.length }} 个分类 · 点击查看相关文章</p>
    </header>

    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <template v-else>
      <!-- 分类列表 / 卡片块 -->
      <div class="card" style="padding: 24px; margin-bottom: 24px;">
        <div style="display: flex; flex-wrap: wrap; gap: 12px; justify-content: center; align-items: center; min-height: 80px;">
          <button v-for="c in categories" :key="c.id" @click="activeCategory = activeCategory === c.id ? null : c.id"
            :style="{
              padding: '6px 16px',
              borderRadius: '14px',
              fontSize: categorySize(categoryCount(c.id)),
              fontWeight: '500',
              background: activeCategory === c.id ? 'var(--primary)' : 'var(--bg-soft)',
              color: activeCategory === c.id ? 'white' : 'var(--text-2)',
              border: 'none',
              cursor: 'pointer',
              transition: 'all 0.15s'
            }"
          >
            {{ c.name }} <span style="opacity: 0.6; font-size: 0.8em;">{{ categoryCount(c.id) }}</span>
          </button>
        </div>
      </div>

      <!-- 选中分类的文章列表 -->
      <div v-if="activeCategory" class="category-article-list">
        <h3 class="font-serif" style="font-size: 18px; margin-bottom: 12px;">
          分类「{{ categories.find(c => c.id === activeCategory)?.name }}」下的文章 ({{ total }} 篇)
        </h3>
        <div v-if="categoryArticlesLoading" style="padding: 20px; text-align: center; color: var(--muted);">加载中…</div>
        <div v-else-if="!filteredArticles.length" style="padding: 20px; text-align: center; color: var(--muted);">该分类下还没有文章</div>
        <template v-else>
          <ul style="list-style: none; padding: 0;">
            <li v-for="a in filteredArticles" :key="a.id" style="padding: 10px 0; border-bottom: 1px dashed var(--line-soft);">
              <NuxtLink :to="`/post/${a.slug}`" style="color: var(--text); font-size: 15px;">{{ a.title }}</NuxtLink>
              <span style="margin-left: 12px; color: var(--muted); font-size: 12px;">{{ formatDate(a.publishedAt || a.createdAt) }}</span>
            </li>
          </ul>

          <!-- 分页器组件 -->
          <div v-if="totalPages > 1" class="pagination" style="margin-top: 24px;">
            <div class="pagination-left">
              <select class="pagination-size" :value="pageSize" @change="onSizeChange">
                <option v-for="s in sizeOptions" :key="s" :value="s">{{ s }} 篇/页</option>
              </select>
            </div>
            <div class="pagination-center">
              <button class="page-btn" :disabled="currentPage === 1" @click="goPage(currentPage - 1)" aria-label="上一页">‹</button>
              <template v-for="(p, i) in pageList" :key="i">
                <span v-if="p === '…'" class="page-btn" style="cursor: default;">…</span>
                <button
                  v-else
                  class="page-btn"
                  :class="{ active: p === currentPage }"
                  @click="goPage(p)"
                >{{ p }}</button>
              </template>
              <button class="page-btn" :disabled="currentPage === totalPages" @click="goPage(currentPage + 1)" aria-label="下一页">›</button>
            </div>
            <div class="pagination-right">
              <input v-model="jumpInput" class="pagination-jump" placeholder="页码" @keydown.enter="onJump" />
              <button class="page-btn" @click="onJump">跳转</button>
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
