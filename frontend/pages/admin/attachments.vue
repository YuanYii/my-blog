<script setup lang="ts">
// 2026-07-01 DEV-003：附件管理后台
// tab 切换：未删除 / 已删除 / 全部
// 单条操作：硬删除（二次确认）+ 恢复 + 查看文章
// 2026-07-01 OPT-002/003：BLOG-002 待落地时同步隐藏恢复按钮 + 加 7 列布局（文件名|大小|关联文章|上传时间|删除时间|状态|操作）
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, restoreAttachment, hardDeleteAttachment } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()
const { formatFileSize } = useFileSize()
const { formatTime: formatDateTime } = useFormatTime()

type TabKey = '0' | '1' | 'all'
const activeTab = ref<TabKey>('0')

const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const res = await get<any>('/admin/attachments', {
      page: page.value,
      size: size.value,
      deleted: activeTab.value
    })
    items.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const handleTabChange = (tab: TabKey) => {
  activeTab.value = tab
  page.value = 1
  load()
}

// "已删除" tab 数量（badge 显示）
const softDeletedCount = ref(0)
const loadSoftDeletedCount = async () => {
  try {
    const res = await get<any>('/admin/attachments', { page: 1, size: 1, deleted: '1' })
    softDeletedCount.value = res.data?.total || 0
  } catch { softDeletedCount.value = 0 }
}

const handleHardDelete = async (item: any) => {
  const { confirmed } = await $dialog.confirm({
    title: '硬删除附件',
    message: `确认硬删除「${item.fileName}」？文件 + 记录会一并清除，不可恢复。`,
    confirmText: '硬删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await hardDeleteAttachment(item.id)
    $toast.success('附件已硬删除')
    load()
    loadSoftDeletedCount()
  } catch (e: any) {
    $toast.error('硬删除失败：' + (e?.data?.message || e?.message))
  }
}

const handleRestore = async (item: any) => {
  try {
    await restoreAttachment(item.id)
    $toast.success('附件已恢复')
    load()
    loadSoftDeletedCount()
  } catch (e: any) {
    $toast.error('恢复失败：' + (e?.data?.message || e?.message))
  }
}

/**
 * 查看文章：调 detailById 拿 slug，跳公开页。
 * 失败兜底：弹 toast 提示（slug 缺失时——比如文章已硬删但有孤儿 attachment 记录——这种应不存在）。
 */
const handleViewArticle = async (item: any) => {
  try {
    const res = await get<any>(`/articles/id/${item.articleId}`)
    const slug = res.data?.slug
    if (slug) {
      window.open(`/post/${slug}`, '_blank')
    } else {
      $toast.error('文章不存在或已被删除')
    }
  } catch (e: any) {
    $toast.error('查看失败：' + (e?.data?.message || e?.message))
  }
}

onMounted(async () => {
  await Promise.all([load(), loadSoftDeletedCount()])
})
</script>

<template>
  <div class="attachments-page">
    <div class="page-topbar">
      <h1>附件管理</h1>
      <div style="font-size: 13px; color: var(--muted);">仅 zip · 最大 5MB · 一文一附件</div>
    </div>

    <!-- Tabs -->
    <div class="tabs">
      <button :class="{ active: activeTab === '0' }" @click="handleTabChange('0')">未删除</button>
      <button :class="{ active: activeTab === '1' }" @click="handleTabChange('1')">
        已删除
        <span v-if="softDeletedCount > 0" class="badge-count">{{ softDeletedCount }}</span>
      </button>
      <button :class="{ active: activeTab === 'all' }" @click="handleTabChange('all')">全部</button>
    </div>

    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <div v-else-if="!items.length" class="card" style="padding: 60px; text-align: center; color: var(--muted);">
      暂无附件
    </div>

    <!-- 2026-07-01 OPT-003：表头 7 列（文件名|大小|关联文章|上传时间|删除时间|状态|操作） -->
    <div v-else class="card" style="padding: 0; overflow: hidden;">
      <table class="attach-table">
        <thead>
          <tr>
            <th style="width: 30%;">文件名</th>
            <th style="width: 10%;">大小</th>
            <th style="width: 16%;">关联文章</th>
            <th style="width: 14%;">上传时间</th>
            <th style="width: 14%;">删除时间</th>
            <th style="width: 6%;">状态</th>
            <th style="width: 10%;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>
              <div style="display: flex; align-items: center; gap: 8px;">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 17.93 8.8l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
                <span style="word-break: break-all; font-size: 13px;">{{ item.fileName }}</span>
              </div>
            </td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ formatFileSize(item.fileSize) }}</td>
            <!-- 2026-07-01 OPT-003：关联文章列（来自后端 LEFT JOIN article 的 articleTitle/articleDeleted） -->
            <td style="font-size: 12px;">
              <span v-if="item.articleId">
                <span
                  v-if="item.articleDeleted === 1"
                  style="color: var(--muted); text-decoration: line-through;"
                  :title="item.articleTitle"
                >已删除文章</span>
                <a
                  v-else
                  href="#"
                  @click.prevent="handleViewArticle(item)"
                  style="color: var(--primary); text-decoration: none;"
                  :title="item.articleTitle"
                >{{ item.articleTitle || '查看' }}</a>
              </span>
              <span v-else style="color: var(--muted);">-</span>
            </td>
            <td style="font-size: 12px;">{{ formatDateTime(item.createdAt || item.updatedAt) }}</td>
            <!-- 2026-07-01 OPT-003：删除时间列（未删除 tab 显示 -；已删除/全部 tab 显示 updatedAt） -->
            <td style="font-size: 12px;">
              <span v-if="item.deleted === 1" style="color: var(--muted);">{{ formatDateTime(item.updatedAt) }}</span>
              <span v-else style="color: var(--muted);">-</span>
            </td>
            <td>
              <span v-if="item.deleted === 1" class="status-tag status-soft-del">软删除</span>
              <span v-else class="status-tag status-active">正常</span>
            </td>
            <td>
              <div style="display: flex; gap: 6px; flex-wrap: wrap;">
                <!-- 2026-07-01 OPT-002：文章被硬删/回收时,「查看文章」按钮隐藏(公开页 410,跳过去无意义) -->
                <button v-if="item.articleId && item.articleDeleted !== 1" @click="handleViewArticle(item)" class="btn btn-ghost btn-xs">查看文章</button>
                <!-- 2026-07-01 OPT-002：文章被硬删/回收时,「恢复」按钮隐藏(恢复后文章页还是不展示,软删附件形同虚设) -->
                <button v-if="item.deleted === 1 && item.articleDeleted !== 1" @click="handleRestore(item)" class="btn btn-ghost btn-xs">恢复</button>
                <!-- 硬删除始终保留 -->
                <button @click="handleHardDelete(item)" class="btn btn-ghost btn-xs" style="color: var(--danger);">硬删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <div v-if="total > size" style="display: flex; justify-content: center; gap: 8px; margin-top: 16px;">
      <button class="btn btn-ghost btn-sm" :disabled="page === 1" @click="page--; load()">上一页</button>
      <span style="font-size: 13px; padding: 6px 12px; color: var(--muted);">第 {{ page }} 页 / 共 {{ Math.ceil(total / size) }} 页</span>
      <button class="btn btn-ghost btn-sm" :disabled="page * size >= total" @click="page++; load()">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.page-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-topbar h1 {
  font-size: 22px;
  font-weight: 600;
}
.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.tabs button {
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
.tabs button:hover {
  color: var(--text);
}
.tabs button.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}
.badge-count {
  background: var(--danger);
  color: white;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  min-width: 18px;
  text-align: center;
}
.attach-table {
  width: 100%;
  border-collapse: collapse;
}
.attach-table th {
  background: var(--bg-soft);
  font-weight: 500;
  font-size: 12px;
  text-align: left;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  color: var(--muted);
}
.attach-table td {
  padding: 14px 16px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 13px;
}
.attach-table tr:hover td {
  background: var(--bg-soft);
}
.status-tag {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
}
.status-active { background: var(--primary-soft); color: var(--primary); }
.status-soft-del { background: rgba(217, 119, 6, 0.12); color: #d97706; }
.btn-xs {
  font-size: 12px;
  padding: 4px 10px;
}
</style>
