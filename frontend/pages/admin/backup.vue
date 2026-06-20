<script setup lang="ts">
/**
 * 数据备份页（REQ-BACKUP-2026-06-20，v4.2.0）
 *
 * 设计依据：docs/设计文档/博客数据备份方案设计.md
 *
 * 交互流程：
 *  1. 用户点「立即备份」 → 二次确认 → 调 POST /admin/backup/run
 *  2. 后端立即返回 record id → 前端轮询 GET /admin/backup/{id}（每 2s）
 *  3. 状态从 PENDING → RUNNING → SUCCESS / FAILED
 *  4. 列表显示历史记录（成功/失败/耗时/大小/资产 URL）
 *
 * 注意：
 *  - 备份期间不阻塞后端（SQLite 走 .backup WAL，uploads 走 tar 流式压缩）
 *  - 失败信息不含密码/secret(后端已脱敏)
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post } = useAdminApi()
const $toast = useToast()

// 列表
interface BackupItem {
  id: number
  status: string
  tag?: string
  startedAt?: string
  finishedAt?: string
  durationSec?: number
  dbSize?: number
  uploadsSize?: number
  assetCount?: number
  assetUrls?: string[]
  errorStage?: string
  errorMessage?: string
  operatorName?: string
}
const list = ref<BackupItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)

// 当前正在轮询的 record
const pollingId = ref<number | null>(null)
const pollingTimer = ref<ReturnType<typeof setInterval> | null>(null)

// 触发备份
const triggering = ref(false)
const handleTrigger = async () => {
  if (triggering.value) return
  triggering.value = true
  try {
    const res = await post<any>('/admin/backup/run', {})
    const id = res?.data?.id
    if (!id) throw new Error('未返回 record id')
    $toast.success(res?.data?.message || '备份任务已创建')
    // 立即把这条 PENDING 插到列表顶
    await fetchList()
    // 开始轮询
    startPolling(id)
  } catch (e: any) {
    const msg = e?.data?.message || e?.message || '触发失败'
    $toast.error(msg)
  } finally {
    triggering.value = false
  }
}

// 轮询单条
const startPolling = (id: number) => {
  stopPolling()
  pollingId.value = id
  const tick = async () => {
    try {
      const res = await get<any>(`/admin/backup/${id}`)
      const item = res?.data
      if (!item) return
      // 同步到列表对应行
      const idx = list.value.findIndex(x => x.id === id)
      if (idx >= 0) list.value[idx] = item
      if (item.status === 'SUCCESS' || item.status === 'FAILED') {
        stopPolling()
        if (item.status === 'SUCCESS') {
          $toast.success(`备份完成：${item.tag || ''}`)
        } else {
          $toast.error(`备份失败：${item.errorStage || '未知阶段'}`)
        }
      }
    } catch (e) {
      // 网络错误不停,继续轮询
    }
  }
  // 立即拉一次,再 setInterval
  tick()
  pollingTimer.value = setInterval(tick, 2000)
}

const stopPolling = () => {
  if (pollingTimer.value) {
    clearInterval(pollingTimer.value)
    pollingTimer.value = null
  }
  pollingId.value = null
}

// 拉列表
const fetchList = async () => {
  loading.value = true
  try {
    const res = await get<any>('/admin/backup/list', { page: page.value, size })
    list.value = res?.data?.records || []
    total.value = res?.data?.total || 0
  } catch (e: any) {
    $toast.error('加载备份历史失败：' + (e?.data?.message || e?.message))
  } finally {
    loading.value = false
  }
}

// 工具
const formatSize = (bytes?: number) => {
  if (!bytes || bytes <= 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}
const formatDuration = (sec?: number) => {
  if (sec == null) return '-'
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}m ${s}s`
}
const formatDate = (s?: string) => s ? new Date(s).toLocaleString('zh-CN', { hour12: false }) : '-'
const statusType = (s: string) => {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING') return 'accent'
  return 'muted'
}
const statusLabel = (s: string) => {
  return { PENDING: '排队中', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败' }[s] || s
}

onMounted(() => {
  fetchList()
})
onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <div>
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <h1>数据备份</h1>
        <p>将生产数据库和上传文件加密后备份到 GitHub 备份仓库</p>
      </div>
      <div style="display: flex; align-items: center; gap: 12px;">
        <button
          @click="handleTrigger"
          :disabled="triggering || pollingId !== null"
          class="btn btn-primary"
          style="display: flex; align-items: center; gap: 6px;"
        >
          <svg v-if="pollingId === null" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="40 60"/></svg>
          {{ triggering ? '触发中…' : (pollingId !== null ? '备份进行中…' : '立即备份') }}
        </button>
      </div>
    </div>

    <!-- 提示卡 -->
    <div class="card" style="padding: 16px; margin-bottom: 16px; background: var(--bg-soft); border: 1px solid var(--border);">
      <div style="display: flex; gap: 10px; align-items: flex-start;">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--accent); flex-shrink: 0; margin-top: 2px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        <div style="font-size: 13px; line-height: 1.6; color: var(--text-soft);">
          备份期间不阻塞服务，SQLite 走 WAL online backup，uploads 走流式 tar 压缩后立即 AES-256-CBC 加密。
          <br/>加密文件会上传到独立的 GitHub 备份仓库（<code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; padding: 1px 5px; background: var(--bg); border-radius: 3px;">GITHUB_BACKUP_REPO</code>），密码从服务器 <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; padding: 1px 5px; background: var(--bg); border-radius: 3px;">/etc/myblog/myblog.env</code> 读取。
          <br/><strong style="color: var(--text);">同时只能运行 1 个备份任务</strong>，避免 SQLite 写锁竞争。失败信息已脱敏，不会泄露密码或 token。
        </div>
      </div>
    </div>

    <!-- 历史列表 -->
    <div class="card" style="padding: 0; overflow: hidden;">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width: 60px;">ID</th>
            <th style="width: 110px;">状态</th>
            <th>Tag / 失败阶段</th>
            <th style="width: 150px;">触发时间</th>
            <th style="width: 90px;">耗时</th>
            <th style="width: 120px;">db 大小</th>
            <th style="width: 120px;">uploads 大小</th>
            <th style="width: 90px;">资产数</th>
            <th>操作人</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading && list.length === 0">
            <td colspan="9" style="text-align: center; padding: 32px; color: var(--muted);">加载中…</td>
          </tr>
          <tr v-else-if="list.length === 0">
            <td colspan="9" style="text-align: center; padding: 32px; color: var(--muted);">暂无备份记录</td>
          </tr>
          <tr v-for="item in list" :key="item.id" :class="{ 'row-polling': pollingId === item.id }">
            <td><code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">#{{ item.id }}</code></td>
            <td>
              <span class="status-badge" :class="`status-${statusType(item.status)}`">
                <span v-if="item.status === 'RUNNING'" class="spin-dot"></span>
                {{ statusLabel(item.status) }}
              </span>
            </td>
            <td>
              <code v-if="item.tag" style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ item.tag }}</code>
              <span v-else-if="item.errorStage" :style="{ color: 'var(--danger)', fontSize: '12px' }">[{{ item.errorStage }}]</span>
              <span v-else style="color: var(--muted);">-</span>
              <div v-if="item.errorMessage" :style="{ color: 'var(--muted)', fontSize: '11px', marginTop: '4px', fontFamily: 'JetBrains Mono, monospace', whiteSpace: 'pre-wrap', maxWidth: '400px', overflow: 'hidden', textOverflow: 'ellipsis' }" :title="item.errorMessage">{{ item.errorMessage }}</div>
            </td>
            <td style="font-size: 12px;">{{ formatDate(item.startedAt) }}</td>
            <td>{{ formatDuration(item.durationSec) }}</td>
            <td>{{ formatSize(item.dbSize) }}</td>
            <td>{{ formatSize(item.uploadsSize) }}</td>
            <td>{{ item.assetCount ?? '-' }}</td>
            <td style="font-size: 12px;">{{ item.operatorName || '-' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <div v-if="total > size" style="display: flex; justify-content: center; gap: 8px; margin-top: 16px;">
      <button @click="page = Math.max(1, page - 1); fetchList()" :disabled="page === 1" class="btn">上一页</button>
      <span style="padding: 6px 12px; color: var(--muted); font-size: 13px;">{{ page }} / {{ Math.ceil(total / size) }}</span>
      <button @click="page = page + 1; fetchList()" :disabled="page * size >= total" class="btn">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.spin-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  margin-right: 6px;
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}
.row-polling {
  background: var(--bg-soft);
}
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}
.status-success { background: rgba(34, 197, 94, 0.12); color: var(--success); }
.status-danger  { background: rgba(239, 68, 68, 0.12); color: var(--danger); }
.status-accent  { background: rgba(201, 123, 63, 0.12); color: var(--accent); }
.status-muted   { background: var(--bg); color: var(--muted); }
</style>
