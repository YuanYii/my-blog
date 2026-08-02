<script setup lang="ts">
// 2026-06-22 抽出 → composables/useMarkdownUtils.ts
import { formatDateTime } from '~/composables/useMarkdownUtils'

definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const router = useRouter()
const route = useRoute()
const { get, del, put, hardDeleteArticle, restoreArticle, batchDeleteArticles } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()
// 2026-06-24 BUG-003：删除文章后侧栏数量不刷新——dashboard.vue 在 loadAll 末尾调了
// refreshMeta(),但 posts.vue 删除操作完毕后没调,导致侧栏「文章/草稿」陈旧
const { refresh: refreshMeta } = useAdminMeta()

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

// 2026-07-01 BUG-002：3 tab 切换（全部 / 未删除 / 已删除）
type DeletedFilter = 'all' | '0' | '1'
const filterDeleted = ref<DeletedFilter>('0')

// 分类（用于下拉）
const categories = ref<any[]>([])
const loadCategories = async () => {
  try {
    const res = await get<any>('/articles/categories/all')
    categories.value = res.data || []
  } catch { /* ignore */ }
}

// 统计
const stats = ref({ total: 0, published: 0, draft: 0, archived: 0, pinned: 0, totalViews: 0 })
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
      pinned: k.pinnedArticles || 0,
      totalViews: k.totalViewCount || 0
    }
  } catch { /* ignore */ }
}

const load = async () => {
  loading.value = true
  try {
    const params: any = { page: page.value, size: size.value, deleted: filterDeleted.value }
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
// 2026-07-01 BUG-002：3 tab 切换
const handleDeletedTab = (tab: DeletedFilter) => {
  filterDeleted.value = tab
  page.value = 1
  selected.value = []  // 切换 tab 清掉选中
  load()
}
// 点击统计卡筛选：'' = 全部，1 = 已发布，0 = 草稿。再点已选中的卡 → 取消回全部。
const setFilter = (status: number | '') => {
  filterStatus.value = filterStatus.value === status ? '' : status
  page.value = 1
  load()
}

// 2026-07-01 BUG-002：删除模式二段化
// - 未删除 tab（deleted=0）：行操作「删除」→ 软删（DELETE /articles/{id}）
// - 已删除 tab（deleted=1）：行操作「恢复」+「硬删除」两个按钮
const handleSoftDelete = async (a: any) => {
  const { confirmed } = await $dialog.confirm({
    title: '删除文章',
    message: `确认删除「${a.title}」？文章会移入「已删除」列表，可后续恢复或硬删除。`,
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
    refreshMeta()  // 2026-06-24 BUG-003：刷新侧栏「文章/草稿」数量
  } catch (e: any) {
    $toast.error('删除失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}

const handleRestore = async (a: any) => {
  const { confirmed } = await $dialog.confirm({
    title: '恢复文章',
    message: `确认恢复「${a.title}」？恢复后将回到「未删除」列表。`,
    confirmText: '恢复'
  })
  if (!confirmed) return
  try {
    await restoreArticle(a.id)
    $toast.success('文章已恢复')
    selected.value = selected.value.filter(id => id !== a.id)
    load()
    loadStats()
    refreshMeta()
  } catch (e: any) {
    $toast.error('恢复失败：' + (e?.data?.message || e?.message))
  }
}

// 2026-07-01 BUG-002：硬删除二次确认（输入 DELETE 字样）
// 复用 backup.vue 「输入 DELETE」模式：要求用户输入字面 DELETE 串才能点确认
const handleHardDelete = async (a: any) => {
  const { confirmed, value } = await $dialog.prompt({
    title: '硬删除文章（不可恢复）',
    message: `硬删除「${a.title}」会清除记录，附件也会一并硬删除。请输入 DELETE 字样以确认操作。`,
    label: '请输入 DELETE 以确认',
    placeholder: 'DELETE',
    confirmText: '硬删除',
    danger: true
  })
  if (!confirmed) return
  if (value !== 'DELETE') {
    $toast.error('确认字样错误，已取消')
    return
  }
  try {
    await hardDeleteArticle(a.id)
    $toast.success('文章已硬删除')
    selected.value = selected.value.filter(id => id !== a.id)
    load()
    loadStats()
    refreshMeta()
  } catch (e: any) {
    $toast.error('硬删除失败：' + (e?.data?.message || e?.message))
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

// 2026-07-01 BUG-002：批量操作根据当前 tab 走不同的 API
const handleBulkDelete = async () => {
  if (filterDeleted.value === '1') {
    // 已删除 tab → 批量硬删除带二次确认
    const { confirmed, value } = await $dialog.prompt({
      title: '批量硬删除（不可恢复）',
      message: `硬删除选中的 ${selected.value.length} 篇文章？此操作不可撤销。请输入 DELETE 字样以确认。`,
      label: '请输入 DELETE 以确认',
      placeholder: 'DELETE',
      confirmText: '硬删除',
      danger: true
    })
    if (!confirmed || value !== 'DELETE') return
    let failed = 0
    for (const id of selected.value) {
      try { await hardDeleteArticle(id) } catch { failed++ }
    }
    if (failed > 0) {
      $toast.warning(`批量硬删除完成：${selected.value.length - failed} 成功，${failed} 失败`)
    } else {
      $toast.success(`已硬删除 ${selected.value.length} 篇`)
    }
  } else {
    // 未删除 / 全部 tab → 软删
    const { confirmed } = await $dialog.confirm({
      title: '批量删除文章',
      message: `确认删除选中的 ${selected.value.length} 篇文章？删除后可在「已删除」tab 恢复或硬删除。`,
      confirmText: '删除',
      danger: true
    })
    if (!confirmed) return
    const res = await batchDeleteArticles(selected.value)
    const data = res.data || { success: 0, skipped: 0 }
    if (data.skipped > 0) {
      $toast.warning(`批量删除完成：${data.success} 成功，${data.skipped} 跳过`)
    } else {
      $toast.success(`已删除 ${data.success} 篇`)
    }
  }
  selected.value = []
  load()
  loadStats()
  refreshMeta()
}

const handleBulkRestore = async () => {
  const { confirmed } = await $dialog.confirm({
    title: '批量恢复文章',
    message: `确认恢复选中的 ${selected.value.length} 篇文章？`,
    confirmText: '恢复'
  })
  if (!confirmed) return
  let failed = 0
  for (const id of selected.value) {
    try { await restoreArticle(id) } catch { failed++ }
  }
  if (failed > 0) {
    $toast.warning(`批量恢复完成：${selected.value.length - failed} 成功，${failed} 失败`)
  } else {
    $toast.success(`已恢复 ${selected.value.length} 篇`)
  }
  selected.value = []
  load()
  loadStats()
  refreshMeta()
}

const handleBulkPublish = async () => {
  let failed = 0
  for (const id of selected.value) {
    try { await put(`/articles/${id}`, { status: 1 }) } catch { failed++ }
  }
  if (failed > 0) {
    $toast.warning(`批量发布完成：${selected.value.length - failed} 成功，${failed} 失败`)
  } else {
    $toast.success(`已发布 ${selected.value.length} 篇`)
  }
  selected.value = []
  load()
  loadStats()
  refreshMeta()  // 2026-06-24 BUG-003：批量发布改了 draftCount,刷新侧栏
}

const statusLabel = (s: number) => ({ 0: '草稿', 1: '已发布', 2: '已归档' }[s] || '未知')
const statusBadge = (s: number) => ({ 1: 'badge-success', 0: 'badge-warning', 2: 'badge-muted' }[s] || 'badge-muted')
const pinLabel = (p: number) => p === 1 ? '置顶' : ''

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

// 2026-06-24 DEV-002：文章 ZIP 导入对话框开关
const showImport = ref(false)
const onImportClose = (refreshed?: boolean) => {
  showImport.value = false
  if (refreshed) { load(); loadStats() }
}

// HTML 上传对话框开关
const showHtmlUpload = ref(false)
const onHtmlUploadClose = (refreshed?: boolean) => {
  showHtmlUpload.value = false
  if (refreshed) { load(); loadStats() }
}

onMounted(async () => {
  await loadCategories()
  await loadStats()
  // 支持从仪表盘带 ?status=0 跳转（草稿）自动套用筛选
  const q = route.query.status
  if (q !== undefined && q !== '') filterStatus.value = Number(q)
  // 支持从 dashboard.vue "已删除文章" KPI 跳转 ?deleted=1
  const delQ = route.query.deleted
  if (delQ === '1') filterDeleted.value = '1'
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
      <div style="display: flex; gap: 8px;">
        <button @click="showImport = true" class="btn-new" style="background: var(--muted-soft, var(--accent)); color: var(--text);">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/></svg>
          导入
        </button>
        <button @click="showHtmlUpload = true" class="btn-new" style="background: var(--primary-soft, var(--accent)); color: var(--primary, var(--text));">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg>
          上传HTML
        </button>
        <NuxtLink to="/admin/edit" class="btn-new">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
          新建文章
        </NuxtLink>
      </div>
    </div>

    <!-- 2026-06-24 DEV-002：文章 ZIP 导入对话框 -->
    <AdminArticleImportDialog v-if="showImport" @close="onImportClose" />

    <!-- HTML 上传对话框 -->
    <AdminHtmlUploadDialog v-if="showHtmlUpload" @close="onHtmlUploadClose" />


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
        <div class="stat-card-label">置顶</div>
        <div class="stat-card-value">{{ stats.pinned }}</div>
        <div class="stat-card-delta">同时仅 1 篇</div>
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
        <!-- 2026-07-01 BUG-002：批量操作按 tab 切换 -->
        <button v-if="filterDeleted !== '1'" @click="handleBulkPublish" class="btn btn-ghost btn-sm">批量发布</button>
        <button v-if="filterDeleted !== '1'" @click="handleBulkDelete" class="btn btn-ghost btn-sm" style="color: var(--danger);">批量删除</button>
        <button v-if="filterDeleted === '1'" @click="handleBulkRestore" class="btn btn-ghost btn-sm">批量恢复</button>
        <button v-if="filterDeleted === '1'" @click="handleBulkDelete" class="btn btn-ghost btn-sm" style="color: var(--danger);">批量硬删除</button>
        <button @click="selected = []" class="btn btn-ghost btn-sm">取消</button>
      </div>
    </div>

    <!-- Toolbar -->
    <div class="toolbar">
      <div class="toolbar-search">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>
        <input v-model="keyword" @keyup.enter="handleSearch" type="text" placeholder="搜索标题…" />
      </div>
      <div class="toolbar-dropdown">
        <UiDropdownSelector :model-value="filterCategory" :options="[{ label: '全部分类', value: '' }, ...categories.map((c: any) => ({ label: c.name, value: c.id }))]" placeholder="全部分类" @update:model-value="(v: any) => { filterCategory = v; handleStatusFilter() }" />
      </div>
      <div class="toolbar-dropdown">
        <UiDropdownSelector :model-value="sortBy" :options="[{ label: '最新发布', value: 'newest' }, { label: '最早发布', value: 'oldest' }, { label: '阅读最多', value: 'views' }]" @update:model-value="(v: any) => { sortBy = v; handleSearch() }" />
      </div>
    </div>

    <!-- 2026-07-01 BUG-002：3 tab 切换（全部 / 未删除 / 已删除） -->
    <div class="posts-tabs">
      <button :class="{ active: filterDeleted === 'all' }" @click="handleDeletedTab('all')">全部</button>
      <button :class="{ active: filterDeleted === '0' }" @click="handleDeletedTab('0')">未删除</button>
      <button :class="{ active: filterDeleted === '1' }" @click="handleDeletedTab('1')">
        已删除
        <span v-if="filterDeleted === '1'" class="tab-hint">回收站</span>
      </button>
    </div>

    <!-- Table -->
    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>
    <div v-else-if="!articles.length" class="card" style="padding: 40px; text-align: center; color: var(--muted);">
      <span v-if="filterDeleted === '1'">回收站是空的</span>
      <span v-else>还没有文章，<NuxtLink to="/admin/edit" style="color: var(--primary);">写第一篇</NuxtLink></span>
    </div>
    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 40px;">
              <input type="checkbox" :checked="selected.length === articles.length" @change="toggleAll" />
            </th>
            <th style="width: 35%;">标题</th>
            <th>分类</th>
            <th>状态</th>
            <th style="width: 120px;">阅读</th>
            <!-- 2026-07-01 OPT-001：新增「附件」列,按行展示 attachmentCount -->
            <th style="width: 70px;">附件</th>
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
                <div class="post-cell-info post-cell-clickable" @click="handleEdit(a)">
                  <div class="post-cell-title">
                    <span v-if="a.isPinned === 1" class="featured-badge" style="font-size: 10px; padding: 1px 5px; margin-right: 4px; vertical-align: middle;">
                      <svg width="8" height="8" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.4 7.4H22l-6.2 4.5 2.4 7.4-6.2-4.5-6.2 4.5 2.4-7.4L2 9.4h7.6L12 2z"/></svg>
                      置顶
                    </span>
                    {{ a.title }}
                  </div>
                  <div class="post-cell-meta">/{{ a.slug }}</div>
                </div>
              </div>
            </td>
            <td><span class="badge" style="background: var(--primary-soft); color: var(--primary);">{{ categoryName(a.categoryId) }}</span></td>
            <td><span class="badge" :class="statusBadge(a.status)">{{ statusLabel(a.status) }}</span></td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px; white-space: nowrap;">
              {{ (a.viewCount || 0).toLocaleString() }}<span v-if="(a.viewCount3d || 0) > 0" style="color: var(--success, #16a34a); font-weight: 600;">（+{{ a.viewCount3d }}）</span>
            </td>
            <!-- 2026-07-01 OPT-001：附件数单元格, 0 时显示 "-" -->
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">
              <span v-if="(a.attachmentCount || 0) > 0">📎 {{ a.attachmentCount }}</span>
              <span v-else style="color: var(--muted);">-</span>
            </td>
            <td style="font-size: 12px; color: var(--muted); font-family: 'JetBrains Mono', monospace;">{{ formatDateTime(a.publishedAt) }}</td>
            <td>
              <div class="row-actions">
                <button @click="handleEdit(a)" class="row-action" title="编辑">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
                </button>
                <!-- 2026-07-01 BUG-002：行操作按 tab 切换 -->
                <!-- 未删除 / 全部 tab：编辑 + 查看 + 删除（软删） -->
                <template v-if="filterDeleted !== '1'">
                  <!-- 2026-07-19 修复：草稿/归档文章加 preview=1 走管理员预览端点（绕过 status=1 过滤） -->
                  <a :href="`/post/${a.slug}?preview=1`" target="_blank" class="row-action" title="查看">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
                  </a>
                  <button @click="handleSoftDelete(a)" class="row-action danger" title="删除">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                  </button>
                </template>
                <!-- 已删除 tab：恢复 + 硬删除 -->
                <template v-else>
                  <button @click="handleRestore(a)" class="row-action" title="恢复">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
                  </button>
                  <button @click="handleHardDelete(a)" class="row-action danger" title="硬删除">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6"/></svg>
                  </button>
                </template>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <AdminPagination
      :page="page"
      :size="size"
      :total="total"
      @update:page="(v: number) => page = v"
      @update:size="(v: number) => size = v"
      @change="load"
    />
  </div>
</template>

<style scoped>
/* 2026-07-01 BUG-002：3 tab 切换样式（与 attachments.vue:115 风格保持一致） */
.posts-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.posts-tabs button {
  background: transparent;
  border: none;
  padding: 10px 16px;
  font-size: 13px;
  color: var(--muted);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s ease;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.posts-tabs button:hover {
  color: var(--text);
}
.posts-tabs button.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}
.tab-hint {
  font-size: 11px;
  color: var(--muted);
  margin-left: 4px;
}
</style>
