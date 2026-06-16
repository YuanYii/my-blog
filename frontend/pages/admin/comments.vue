<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

// 2026-06-16 修复（BUG-076）：之前只解构 { get, put }，handleDelete 调 `del()` 报
// ReferenceError: del is not defined → catch 弹"删除失败：del is not defined"。
// 物理删除后端 DELETE /comments/{id} 早就实现了，缺的是前端 del 解构。
const { get, put, del } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()
const filter = ref<number>(0)
const comments = ref<any[]>([])
const articles = ref<any[]>([])
const loading = ref(false)
const total = ref(0)

const statusMap: Record<number, { label: string; apiValue: number; cls: string }> = {
  0: { label: '待审核', apiValue: 0, cls: 'pending' },
  1: { label: '已通过', apiValue: 1, cls: 'approved' },
  2: { label: '已拒绝', apiValue: 2, cls: 'rejected' }
}

// 2026-06-13 Bug 修复：原 size: 1000 超出后端校验上限（max=100），
// 后端返回 400 → catch 吞掉 → articles 为空 → 评论列表全部显示"未知文章"。
// 修复：改用 /articles/admin/all（admin 端点，同样限 100），获取最近 100 篇文章标题。
// 若文章总数超过 100 篇且评论关联的是第 101+ 篇，标题仍显示"未知文章"——
// 属于已知限制（MVP 阶段够用），后续可改为按需按 articleId 单独查询。
const loadArticles = async () => {
  try {
    const res = await get<any>('/articles/admin/all', { size: 100, sort: 'updated_at:desc' })
    articles.value = res.data?.records || []
  } catch { /* ignore */ }
}

const articleTitle = (id: number) => articles.value.find(a => a.id === id)?.title || '未知文章'

const load = async () => {
  loading.value = true
  try {
    const res = await get<any>('/comments/admin', { status: statusMap[filter.value].apiValue, size: 100 })
    comments.value = res.data?.records || []
    // 2026-06-13 修复（BUG-065）：之前 total = comments.length 只统计当前页（最大 100），
    // 列表底部 "共 X 条" 实际只展示 100。后端 PageResult 已带 total 字段。
    total.value = res.data?.total || 0
  } catch {
    comments.value = []
  } finally {
    loading.value = false
  }
}

const handleAction = async (c: any, action: 'approve' | 'reject') => {
  const status = action === 'approve' ? 1 : 2
  try {
    await put(`/comments/${c.id}/status`, { status })
    load()
  } catch (e: any) {
    $toast.error('操作失败：' + (e?.data?.message || e?.message))
  }
}

// 2026-06-13 修复（BUG-074）：admin/comments 之前无物理删除入口，
// 后端 DELETE /comments/{id} 已实现但前端未暴露；垃圾评论/广告只能"拒绝"留在库里。
// 2026-06-16 改造：confirm → $dialog.confirm；alert → $toast
const handleDelete = async (c: any) => {
  const { confirmed } = await $dialog.confirm({
    title: '删除评论',
    message: `确认删除 ${c.nickname} 的这条评论？此操作不可撤销。`,
    confirmText: '删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await del(`/comments/${c.id}`)
    $toast.success('已删除')
    load()
  } catch (e: any) {
    $toast.error('删除失败：' + (e?.data?.message || e?.message))
  }
}

const formatDateTime = (s: string) => s ? s.replace('T', ' ').substring(0, 16) : ''

onMounted(async () => {
  await loadArticles()
  await load()
})
</script>

<template>
  <div>
    <!-- Page head -->
    <div class="page-head">
      <div>
        <h1>评论管理</h1>
        <p>审核访客评论，维护评论区秩序</p>
      </div>
    </div>

    <!-- Tabs -->
    <!-- 2026-06-12 修复：v-for 遍历 Record<number, ...> 时 Vue 模板把 key 当字符串，
         而 filter 是 ref<number>(0)——`filter = key` 类型不兼容、`filter === key` 永远 false。
         用 Number(key) 显式转一下，TS 通过、运行时也正确。 -->
    <div class="filter-tabs" style="margin-bottom: 16px;">
      <button v-for="(meta, key) in statusMap" :key="key" @click="filter = Number(key); load()"
        class="filter-tab" :class="{ active: filter === Number(key) }">
        {{ meta.label }}
      </button>
    </div>

    <!-- 列表 -->
    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>
    <div v-else-if="!comments.length" class="card" style="padding: 40px; text-align: center; color: var(--muted);">
      {{ filter === 0 ? '没有待审评论' : filter === 1 ? '还没有通过的评论' : '还没有被拒绝的评论' }}
    </div>

    <div v-else style="display: flex; flex-direction: column; gap: 10px;">
      <div v-for="c in comments" :key="c.id" class="comment-card" :class="statusMap[c.status]?.cls">
        <div class="comment-head">
          <div class="comment-avatar">{{ c.nickname?.[0] || '?' }}</div>
          <div class="comment-info">
            <div class="comment-author">{{ c.nickname }}</div>
            <div class="comment-meta">{{ c.email || '匿名' }} · {{ formatDateTime(c.createdAt) }}</div>
          </div>
          <div class="comment-actions">
            <span v-if="c.status === 1" class="badge badge-success">已通过</span>
            <span v-else-if="c.status === 2" class="badge" style="background: rgba(194,65,12,0.1); color: var(--danger);">已拒绝</span>
            <span v-else class="badge" style="background: var(--bg-soft); color: var(--accent);">待审核</span>
            <button v-if="c.status === 0" @click="handleAction(c, 'approve')" class="btn btn-sm" style="background: var(--success); color: white;">通过</button>
            <button v-if="c.status === 0" @click="handleAction(c, 'reject')" class="btn btn-sm" style="background: var(--bg-soft); color: var(--danger);">拒绝</button>
            <button @click="handleDelete(c)" class="btn btn-sm" style="background: var(--bg-soft); color: var(--muted);" title="物理删除">删除</button>
          </div>
        </div>
        <div class="comment-content">{{ c.content }}</div>
        <div class="comment-article-ref">
          来自：<a :href="`/post/${articles.find(a => a.id === c.articleId)?.slug || ''}`" target="_blank">{{ articleTitle(c.articleId) }}</a>
        </div>
      </div>
    </div>
  </div>
</template>
