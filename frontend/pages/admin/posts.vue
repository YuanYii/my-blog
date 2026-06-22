<script setup lang="ts">
// 2026-06-22 抽出 → composables/useMarkdownUtils.ts
import { formatDate } from '~/composables/useMarkdownUtils'

definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const router = useRouter()
const route = useRoute()
const { get, del, put } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()

const articles = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const filterStatus = ref<number | ''>('')
const filterCategory = ref<number | ''>('')
const sortBy = ref<'newest' | 'oldest' | 'views'>('newest')
const loading = ref(false)
const selected = ref<number[]>([])

// 分类（用于下拉）
const categories = ref<any[]>([])
const loadCategories = async () => {
  try {
    const res = await get<any>('/articles/categories/all')
    categories.value = res.data || []
  } catch { /* ignore */ }
}

// 统计
const stats = ref({ total: 0, published: 0, draft: 0, archived: 0, totalViews: 0 })
const loadStats = async () => {
  try {
    const res = await get<any>('/admin/dashboard')
    const k = res.data?.kpi || {}
    stats.value = {
      total: k.totalArticles || 0,
      published: k.publishedArticles || 0,
      draft: k.draftArticles || 0,
      // 2026-06-12 修复：后端 KPI 现在返回 archivedArticles；前端 tab "已归档" 不再 hard-code 0
      archived: k.archivedArticles || 0,
      totalViews: k.totalViewCount || 0
    }
  } catch { /* ignore */ }
}

const load = async () => {
  loading.value = true
  try {
    const params: any = { page: page.value, size: size.value }
    if (filterStatus.value !== '') params.status = filterStatus.value
    if (filterCategory.value) params.categoryId = filterCategory.value
    if (keyword.value) params.keyword = keyword.value
    if (sortBy.value === 'newest') params.sort = 'published_at:desc'  // 2026-06-13 修复（BUG-072）：newest 显式走 published_at
    else if (sortBy.value === 'oldest') params.sort = 'published_at:asc'
    else if (sortBy.value === 'views') params.sort = 'view_count:desc'
    const res = await get<any>('/articles/admin/all', params)
    articles.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    articles.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const handleSearch = () => { page.value = 1; load() }
const handleStatusFilter = () => { page.value = 1; load() }
// 点击统计卡筛选：'' = 全部，1 = 已发布，0 = 草稿。再点已选中的卡 → 取消回全部。
const setFilter = (status: number | '') => {
  filterStatus.value = filterStatus.value === status ? '' : status
  page.value = 1
  load()
}
// 2026-06-13 修复（BUG-048）：三个 handler 补 try/catch，token 过期/设备吊销/网络瞬断
// 时能给用户看到错误提示，而不是静默失败。
// 2026-06-16 改造：confirm → $dialog.confirm，alert → $toast
const handleDelete = async (a: any) => {
  const { confirmed } = await $dialog.confirm({
    title: '删除文章',
    message: `确认删除「${a.title}」？此操作不可撤销。`,
    confirmText: '删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await del(`/articles/${a.id}`)
    $toast.success('已删除')
    selected.value = selected.value.filter(id => id !== a.id)
    load()
    loadStats()
  } catch (e: any) {
    $toast.error('删除失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}
const handleEdit = (a: any) => router.push(`/admin/edit?id=${a.id}`)

const toggleSelect = (id: number) => {
  if (selected.value.includes(id)) selected.value = selected.value.filter(x => x !== id)
  else selected.value = [...selected.value, id]
}
const toggleAll = () => {
  if (selected.value.length === articles.value.length) selected.value = []
  else selected.value = articles.value.map(a => a.id)
}

const handleBulkDelete = async () => {
  const { confirmed } = await $dialog.confirm({
    title: '批量删除文章',
    message: `确认删除选中的 ${selected.value.length} 篇文章？此操作不可撤销。`,
    confirmText: '删除',
    danger: true
  })
  if (!confirmed) return
  // 并行 + 单条 try，避免一个失败导致整个 Promise.all reject
  const results = await Promise.allSettled(selected.value.map(id => del(`/articles/${id}`)))
  const failed = results.filter(r => r.status === 'rejected').length
  if (failed > 0) {
    $toast.warning(`批量删除完成：${selected.value.length - failed} 成功，${failed} 失败`)
  } else {
    $toast.success(`已删除 ${selected.value.length} 篇`)
  }
  selected.value = []
  load()
  loadStats()
}

const handleBulkPublish = async () => {
  const results = await Promise.allSettled(selected.value.map(id => put(`/articles/${id}`, { status: 1 })))
  const failed = results.filter(r => r.status === 'rejected').length
  if (failed > 0) {
    $toast.warning(`批量发布完成：${selected.value.length - failed} 成功，${failed} 失败`)
  } else {
    $toast.success(`已发布 ${selected.value.length} 篇`)
  }
  selected.value = []
  load()
  loadStats()
}

const statusLabel = (s: number) => ({ 0: '草稿', 1: '已发布', 2: '已归档' }[s] || '未知')
const statusBadge = (s: number) => ({ 1: 'badge-success', 0: 'badge-warning', 2: 'badge-muted' }[s] || 'badge-muted')

// 缩略图：取标题前 2 字符
const thumb = (a: any) => {
  if (a.coverUrl) return { emoji: '🖼', noCover: false }
  // 优先用分类首字
  const cat = categories.value.find(c => c.id === a.categoryId)
  if (cat) return { emoji: cat.name[0], noCover: false }
  return { emoji: a.title?.[0] || '?', noCover: false }
}

const categoryName = (id: number) => categories.value.find(c => c.id === id)?.name || '未分类'

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

onMounted(async () => {
  await loadCategories()
  await loadStats()
  // 支持从仪表盘带 ?status=0 跳转（草稿）自动套用筛选
  const q = route.query.status
  if (q !== undefined && q !== '') filterStatus.value = Number(q)
  await load()
})
</script>

<template>
  <div>
    <!-- Page head -->
    <div class="page-head">
      <div>
        <h1>文章管理</h1>
        <p>管理所有已发布、草稿和已归档的文章</p>
      </div>
      <NuxtLink to="/admin/edit" class="btn-new">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
        新建文章
      </NuxtLink>
    </div>

    <!-- Stats（点击「总文章 / 已发布 / 草稿」筛选下方列表） -->
    <div class="stats-row">
      <div class="stat-card clickable" :class="{ active: filterStatus === '' }" @click="setFilter('')">
        <div class="stat-card-label">总文章</div>
        <div class="stat-card-value">{{ stats.total }}</div>
      </div>
      <div class="stat-card clickable" :class="{ active: filterStatus === 1 }" @click="setFilter(1)">
        <div class="stat-card-label">已发布</div>
        <div class="stat-card-value">{{ stats.published }}</div>
        <div class="stat-card-delta">+{{ Math.min(3, stats.published) }} 本月</div>
      </div>
      <div class="stat-card clickable" :class="{ active: filterStatus === 0 }" @click="setFilter(0)">
        <div class="stat-card-label">草稿</div>
        <div class="stat-card-value">{{ stats.draft }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-card-label">总阅读量</div>
        <div class="stat-card-value">{{ Math.round(stats.totalViews / 100) / 10 }}k</div>
        <div class="stat-card-delta">+12%</div>
      </div>
    </div>

    <!-- Bulk bar -->
    <div class="bulk-bar" :class="{ show: selected.length > 0 }">
      <span>已选 <span class="count">{{ selected.length }}</span> 项</span>
      <div class="bulk-actions">
        <button @click="handleBulkPublish" class="btn btn-ghost btn-sm">批量发布</button>
        <button @click="handleBulkDelete" class="btn btn-ghost btn-sm" style="color: var(--danger);">批量删除</button>
        <button @click="selected = []" class="btn btn-ghost btn-sm">取消</button>
      </div>
    </div>

    <!-- Toolbar -->
    <div class="toolbar">
      <div class="toolbar-search">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>
        <input v-model="keyword" @keyup.enter="handleSearch" type="text" placeholder="搜索标题…" />
      </div>
      <select v-model="filterCategory" @change="handleStatusFilter" class="form-control" style="width: auto; padding: 7px 28px 7px 12px; font-size: 12px;">
        <option value="">全部分类</option>
        <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <select v-model="sortBy" @change="handleSearch" class="form-control" style="width: auto; padding: 7px 28px 7px 12px; font-size: 12px;">
        <option value="newest">最新发布</option>
        <option value="oldest">最早发布</option>
        <option value="views">阅读最多</option>
      </select>
    </div>

    <!-- Table -->
    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>
    <div v-else-if="!articles.length" class="card" style="padding: 40px; text-align: center; color: var(--muted);">还没有文章，<NuxtLink to="/admin/edit" style="color: var(--primary);">写第一篇</NuxtLink></div>
    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 40px;">
              <input type="checkbox" :checked="selected.length === articles.length" @change="toggleAll" />
            </th>
            <th style="width: 40%;">标题</th>
            <th>分类</th>
            <th>状态</th>
            <th style="width: 80px;">阅读</th>
            <th>发布时间</th>
            <th style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in articles" :key="a.id">
            <td>
              <input type="checkbox" :checked="selected.includes(a.id)" @change="toggleSelect(a.id)" />
            </td>
            <td>
              <div class="post-cell">
                <div class="post-thumb">{{ thumb(a).emoji }}</div>
                <div class="post-cell-info">
                  <div class="post-cell-title">{{ a.title }}</div>
                  <div class="post-cell-meta">/{{ a.slug }}</div>
                </div>
              </div>
            </td>
            <td><span class="badge" style="background: var(--primary-soft); color: var(--primary);">{{ categoryName(a.categoryId) }}</span></td>
            <td><span class="badge" :class="statusBadge(a.status)">{{ statusLabel(a.status) }}</span></td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ (a.viewCount || 0).toLocaleString() }}</td>
            <td style="font-size: 12px; color: var(--muted); font-family: 'JetBrains Mono', monospace;">{{ formatDate(a.publishedAt || a.createdAt) }}</td>
            <td>
              <div class="row-actions">
                <button @click="handleEdit(a)" class="row-action" title="编辑">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
                </button>
                <a :href="`/post/${a.slug}`" target="_blank" class="row-action" title="查看">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
                </a>
                <button @click="handleDelete(a)" class="row-action danger" title="删除">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div v-if="totalPages > 1" class="admin-pagination">
      <span>共 {{ total }} 条</span>
      <div class="pages">
        <button class="page-btn" :disabled="page === 1" @click="page--; load()">‹</button>
        <button v-for="p in totalPages" :key="p" class="page-btn" :class="{ active: p === page }" @click="page = p; load()">{{ p }}</button>
        <button class="page-btn" :disabled="page === totalPages" @click="page++; load()">›</button>
      </div>
    </div>
  </div>
</template>
