<script setup lang="ts">
/**
 * 数据备份 + 数据恢复页（REQ-BACKUP-2026-06-20 + REQ-RESTORE-2026-06-20，v4.3.0）
 *
 * 设计依据：
 *  - docs/设计文档/博客数据备份方案设计.md
 *  - docs/设计文档/博客数据恢复方案设计.md
 *
 * 交互流程：
 *  【备份】
 *  1. 用户点「立即备份」 → 二次确认 → 调 POST /admin/backup/run
 *  2. 后端立即返回 record id → 前端轮询 GET /admin/backup/{id}（每 2s）
 *  3. 状态从 PENDING → RUNNING → SUCCESS / FAILED
 *
 *  【恢复】（v4.3.0 新增）
 *  1. 用户在列表单选一条 SUCCESS 备份 → 点「数据恢复」按钮
 *  2. 二次确认 Dialog → 选 DB_ONLY / DB_UPLOADS → 调 POST /admin/restore/run
 *  3. 后端建 PENDING + 异步跑 blog-restore.sh（systemd-run --scope, 即发即忘）
 *  4. 前端启动容错轮询（退避重试 2s→30s, 容忍后端停服 1-5 分钟）
 *  5. RestoreStartupReconciler 双轨回填（启动 + @Scheduled 5min）状态进 SUCCESS/FAILED/UNKNOWN
 *
 * 失败信息不含密码/secret（后端已脱敏）
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post } = useAdminApi()
const $toast = useToast()

// ============= 备份部分 =============
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

// 当前正在轮询的备份 record
const pollingId = ref<number | null>(null)
const pollingTimer = ref<ReturnType<typeof setInterval> | null>(null)

// 触发备份
const triggering = ref(false)
const handleTrigger = async () => {
  if (triggering.value) return
  if (restorePollingId.value !== null) {
    $toast.error('恢复进行中,不能触发备份')
    return
  }
  // 二次确认
  const ok = window.confirm('确认立即备份?\n\n备份期间不阻塞服务,但会立即占用磁盘和 GitHub 流量。')
  if (!ok) return
  triggering.value = true
  try {
    const res = await post<any>('/admin/backup/run', {})
    const id = res?.data?.id
    if (!id) throw new Error('未返回 record id')
    $toast.success(res?.data?.message || '备份任务已创建')
    await fetchList()
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

// ============= 恢复部分（v4.3.0 新增）=============
const selectedId = ref<number | null>(null)
const restoreDialog = ref(false)
const restoreScope = ref<'DB_ONLY' | 'DB_UPLOADS'>('DB_ONLY')

// 容错轮询 (v4 关键: 容忍后端停服 1-5 分钟)
const restorePollingId = ref<number | null>(null)
const restoreTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const restoreBackoffMs = ref(2000)
const restoreConsecutiveFailures = ref(0)

const selectedItem = computed(() => list.value.find(x => x.id === selectedId.value) || null)

// 「数据恢复」按钮
const handleRestore = () => {
  if (!selectedId.value) {
    $toast.error('请先选择一条备份')
    return
  }
  if (!selectedItem.value || selectedItem.value.status !== 'SUCCESS') {
    $toast.error('只能恢复 SUCCESS 状态的备份')
    return
  }
  restoreScope.value = 'DB_ONLY'  // 默认仅 db
  restoreDialog.value = true
}

// 确认恢复
const confirmRestore = async () => {
  restoreDialog.value = false
  restoreConsecutiveFailures.value = 0
  restoreBackoffMs.value = 2000
  try {
    const res = await post<any>('/admin/restore/run', {
      recordId: selectedId.value,
      scope: restoreScope.value
    })
    const id = res?.data?.id
    if (!id) throw new Error('未返回 record id')
    $toast.success('恢复任务已创建, 服务将停止约 1-5 分钟, 浏览器可能短暂断线')
    startRestorePolling(id)
  } catch (e: any) {
    $toast.error(e?.data?.message || e?.message || '触发失败')
  }
}

// 取消 Dialog
const cancelRestore = () => {
  restoreDialog.value = false
}

// 容错轮询（v4 关键: 不复用 startPolling, 接口路径不同）
const startRestorePolling = (id: number) => {
  stopRestorePolling()
  restorePollingId.value = id
  const tick = async () => {
    try {
      const res = await get<any>(`/admin/restore/${id}`)
      const item = res?.data
      if (!item) return
      restoreConsecutiveFailures.value = 0
      restoreBackoffMs.value = 2000
      if (item.status === 'SUCCESS' || item.status === 'FAILED' || item.status === 'UNKNOWN') {
        stopRestorePolling()
        if (item.status === 'SUCCESS') {
          $toast.success(`恢复成功: ${item.sourceTag || ''}`)
        } else if (item.status === 'UNKNOWN') {
          $toast.warning(`恢复状态未知, 请检查服务端日志: ${item.errorStage || ''}`)
        } else {
          $toast.error(`恢复失败: ${item.errorStage || '未知阶段'}`)
        }
        // 不刷新 backup list (restore 不影响 backup list)
      }
    } catch (e: any) {
      // 后端停服期间 connection refused, 退避重试
      restoreConsecutiveFailures.value++
      restoreBackoffMs.value = Math.min(restoreBackoffMs.value * 2, 30000)
      // v4.3.2: 30 次失败 = backoff 序列 [2,4,8,16,30,30...,30] 大约 5-15 分钟, 放弃轮询
      // 设计文档 §7.5 写 "5 分钟", 60 次实际 ~28 分钟 (注释误导, 与"5 分钟"不一致)
      if (restoreConsecutiveFailures.value > 30) {
        // 连续失败 > 5-15 分钟 → 放弃轮询, 让用户手动刷新
        stopRestorePolling()
        $toast.warning('轮询超时, 请手动刷新页面查看最终状态')
      }
    }
  }
  // v4.3.2: 不要直接 tick(), 让 schedule 自然调度 (第一次 2s 后)
  // 之前: 先 tick() 再 schedule() → 0s 发一次 + 2s 再发一次 (重复请求, 无害但语义不对)
  // 用 setTimeout 自调度, 不用 setInterval (退避需要动态间隔)
  const schedule = () => {
    restoreTimer.value = setTimeout(async () => {
      await tick()
      if (restorePollingId.value !== null) schedule()
    }, restoreBackoffMs.value)
  }
  schedule()
}
const stopRestorePolling = () => {
  if (restoreTimer.value) {
    clearTimeout(restoreTimer.value)
    restoreTimer.value = null
  }
  restorePollingId.value = null
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
  if (s === 'FAILED' || s === 'UNKNOWN') return 'danger'
  if (s === 'RUNNING') return 'accent'
  return 'muted'
}
const statusLabel = (s: string) => {
  return {
    PENDING: '排队中',
    RUNNING: '执行中',
    SUCCESS: '成功',
    FAILED: '失败',
    UNKNOWN: '状态未知'
  }[s] || s
}

onMounted(() => {
  fetchList()
})
onBeforeUnmount(() => {
  stopPolling()
  stopRestorePolling()
})
</script>

<template>
  <div>
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <h1>数据备份</h1>
        <p>将生产数据库和上传文件加密后备份到 GitHub 备份仓库, 也可从这里恢复到任意一次备份</p>
      </div>
      <div style="display: flex; align-items: center; gap: 12px;">
        <button
          @click="handleTrigger"
          :disabled="triggering || pollingId !== null || restorePollingId !== null"
          class="btn btn-primary"
          style="display: flex; align-items: center; gap: 6px;"
        >
          <svg v-if="pollingId === null && restorePollingId === null" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="40 60"/></svg>
          {{ triggering ? '触发中…' : (pollingId !== null ? '备份进行中…' : '立即备份') }}
        </button>
        <button
          @click="handleRestore"
          :disabled="!selectedId || pollingId !== null || restorePollingId !== null"
          class="btn"
        >
          {{ restorePollingId !== null ? '恢复中…' : '数据恢复' }}
        </button>
      </div>
    </div>

    <!-- 提示卡 -->
    <div class="card" style="padding: 16px; margin-bottom: 16px; background: var(--bg-soft); border: 1px solid var(--border);">
      <div style="display: flex; gap: 10px; align-items: flex-start;">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--accent); flex-shrink: 0; margin-top: 2px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        <div style="font-size: 13px; line-height: 1.6; color: var(--text-soft);">
          备份期间不阻塞服务, SQLite 走 WAL online backup, uploads 走流式 tar 压缩后立即 AES-256-CBC 加密。
          <br/>加密文件会上传到独立的 GitHub 备份仓库(<code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; padding: 1px 5px; background: var(--bg); border-radius: 3px;">GITHUB_BACKUP_REPO</code>), 密码从服务器 <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; padding: 1px 5px; background: var(--bg); border-radius: 3px;">/etc/myblog/myblog.env</code> 读取。
          <br/><strong style="color: var(--text);">数据恢复</strong>: 在下方列表单选一条 SUCCESS 备份 → 点「数据恢复」 → 服务将停 1-5 分钟 (覆盖式恢复, 期间浏览器可能短暂断线, 自动恢复后会重新连接)。
          <br/><strong style="color: var(--text);">同时只能运行 1 个备份或恢复任务</strong>, 避免资源争用。
        </div>
      </div>
    </div>

    <!-- 历史列表 -->
    <div class="card" style="padding: 0; overflow: hidden;">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width: 50px;">选择</th>
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
            <td colspan="10" style="text-align: center; padding: 32px; color: var(--muted);">加载中…</td>
          </tr>
          <tr v-else-if="list.length === 0">
            <td colspan="10" style="text-align: center; padding: 32px; color: var(--muted);">暂无备份记录</td>
          </tr>
          <tr v-for="item in list" :key="item.id"
              :class="{ 'row-polling': pollingId === item.id, 'row-selected': selectedId === item.id }">
            <td>
              <input
                type="radio"
                :value="item.id"
                v-model="selectedId"
                :disabled="item.status !== 'SUCCESS'"
                :title="item.status !== 'SUCCESS' ? '只能恢复 SUCCESS 状态的备份' : ''"
              />
            </td>
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

    <!-- 恢复 Dialog (v4.3.0 新增) -->
    <ClientOnly>
      <Teleport to="body">
        <Transition name="dialog">
          <div v-if="restoreDialog" class="dialog-backdrop" @click.self="cancelRestore">
            <div class="dialog-card" role="dialog" aria-modal="true" aria-label="数据恢复">
              <div class="dialog-header">
                <h3 class="dialog-title">数据恢复</h3>
              </div>
              <div class="dialog-body">
                <p style="color: var(--text-2); font-size: 14px; line-height: 1.65; margin: 0 0 14px;">
                  从备份 <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; background: var(--bg-soft); padding: 1px 6px; border-radius: 3px;">{{ selectedItem?.tag }}</code> 恢复:
                </p>
                <div style="display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px;">
                  <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 8px 12px; border-radius: 8px; background: var(--bg-soft);">
                    <input type="radio" v-model="restoreScope" value="DB_ONLY" />
                    <span>仅恢复数据库</span>
                  </label>
                  <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 8px 12px; border-radius: 8px; background: var(--bg-soft);">
                    <input type="radio" v-model="restoreScope" value="DB_UPLOADS" />
                    <span>恢复数据库 + 上传文件 (uploads)</span>
                  </label>
                </div>
                <p style="color: var(--danger); font-size: 13px; line-height: 1.6; margin: 0; padding: 8px 12px; background: rgba(239, 68, 68, 0.08); border-left: 3px solid var(--danger); border-radius: 4px;">
                  ⚠ 恢复期间服务将停止约 1-5 分钟, 浏览器可能短暂掉线。恢复会自动备份当前 db 到 .bak 文件, 失败可手动回退。
                </p>
              </div>
              <div class="dialog-footer">
                <button @click="cancelRestore" class="btn btn-ghost">取消</button>
                <button @click="confirmRestore" class="btn btn-danger">确认恢复</button>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>
    </ClientOnly>
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
.row-selected {
  background: var(--bg-soft);
  box-shadow: inset 3px 0 0 var(--accent);
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

/* 恢复 Dialog 样式 */
.dialog-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
  padding: 24px;
}
.dialog-card {
  background: var(--card);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
  max-width: 500px;
  width: 100%;
  display: flex;
  flex-direction: column;
}
.dialog-header {
  padding: 20px 24px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
  margin: 0;
}
.dialog-body {
  padding: 16px 24px 20px;
}
.dialog-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 24px 20px;
  border-top: 1px solid var(--line-soft);
}
.dialog-enter-active, .dialog-leave-active { transition: opacity 0.15s ease; }
.dialog-enter-from, .dialog-leave-to { opacity: 0; }
.dialog-enter-active .dialog-card, .dialog-leave-active .dialog-card {
  transition: transform 0.2s ease;
}
.dialog-enter-from .dialog-card { transform: translateY(20px); }
.dialog-leave-to .dialog-card { transform: translateY(-10px); }
</style>