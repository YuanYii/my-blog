<script setup lang="ts">
/**
 * 数据恢复页（v4.4.0 拆分）
 *
 * 从 backup.vue 拆分出独立的恢复界面，通过下拉框选择已成功备份的数据
 *
 * 设计依据：用户需求——拆分备份/恢复为两个独立界面
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()

// ============= 备份列表（用于下拉选择）============
interface BackupItem {
  id: number
  status: string
  tag?: string
  startedAt?: string
  finishedAt?: string
  durationSec?: number
  dbSize?: number
  uploadsSize?: number
}
const successBackups = ref<BackupItem[]>([])
const loading = ref(false)

// ============= 恢复部分 =============
const selectedRecordId = ref<number | null>(null)
const restoreScope = ref<'DB_ONLY' | 'DB_UPLOADS'>('DB_ONLY')
const restoreDialog = ref(false)
const restoring = ref(false)

// 容错轮询
const restorePollingId = ref<number | null>(null)
const restoreTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const restoreBackoffMs = ref(2000)
const restoreConsecutiveFailures = ref(0)

// 拉取成功的备份列表
const fetchSuccessBackups = async () => {
  loading.value = true
  try {
    const res = await get<any>('/admin/backup/list', { page: 1, size: 100 })
    const allRecords = res?.data?.records || []
    // 只保留 SUCCESS 状态的备份
    successBackups.value = allRecords.filter((item: BackupItem) => item.status === 'SUCCESS')
  } catch (e: any) {
    $toast.error(formatError(e, '加载备份列表失败'))
  } finally {
    loading.value = false
  }
}

// 选中的备份详情
const selectedBackup = computed(() => {
  if (!selectedRecordId.value) return null
  return successBackups.value.find(b => b.id === selectedRecordId.value) || null
})

// 格式化大小
const formatSize = (bytes?: number) => {
  if (!bytes || bytes <= 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

// 格式化时间
const formatDate = (s?: string) => s ? new Date(s).toLocaleString('zh-CN', { hour12: false }) : '-'

// 格式化错误
const formatError = (e: any, fallback = '操作失败'): string => {
  if (!e) return fallback
  if (e?.data?.message) return e.data.message
  if (e?.message) return e.message
  const traceId = e?.response?.headers?.get?.('X-Trace-Id')
  if (traceId) return `${fallback}（请反馈编号 traceId=${traceId}）`
  return fallback
}

// 点击恢复按钮
const handleRestore = () => {
  if (!selectedRecordId.value) {
    $toast.error('请先选择一个备份')
    return
  }
  restoreScope.value = 'DB_ONLY'
  restoreDialog.value = true
}

// 确认恢复
const confirmRestore = async () => {
  restoreDialog.value = false
  restoring.value = true
  restoreConsecutiveFailures.value = 0
  restoreBackoffMs.value = 2000
  try {
    const res = await post<any>('/admin/restore/run', {
      recordId: selectedRecordId.value,
      scope: restoreScope.value
    })
    const id = res?.data?.id
    if (!id) throw new Error('未返回 record id')
    $toast.success('恢复任务已创建, 服务将停止约 1-5 分钟, 浏览器可能短暂断线')
    startRestorePolling(id)
  } catch (e: any) {
    $toast.error(formatError(e, '触发失败'))
  } finally {
    restoring.value = false
  }
}

// 取消 Dialog
const cancelRestore = () => {
  restoreDialog.value = false
}

// 容错轮询
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
      }
    } catch (e: any) {
      restoreConsecutiveFailures.value++
      restoreBackoffMs.value = Math.min(restoreBackoffMs.value * 2, 30000)
      if (restoreConsecutiveFailures.value > 30) {
        stopRestorePolling()
        $toast.warning('轮询超时, 请手动刷新页面查看最终状态')
      }
    }
  }
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

onMounted(() => {
  fetchSuccessBackups()
})
onBeforeUnmount(() => {
  stopRestorePolling()
})
</script>

<template>
  <div>
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <h1>数据恢复</h1>
        <p>从历史备份中恢复数据到当前服务器</p>
      </div>
      <button
        @click="handleRestore"
        :disabled="!selectedRecordId || restoring || restorePollingId !== null"
        class="btn-new"
      >
        <svg v-if="restorePollingId === null" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
        <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="40 60"/></svg>
        {{ restorePollingId !== null ? '恢复中…' : '数据恢复' }}
      </button>
    </div>

    <!-- 提示卡 -->
    <div class="card" style="padding: 16px; margin-bottom: 16px; background: var(--bg-soft); border: 1px solid var(--border);">
      <div style="display: flex; gap: 10px; align-items: flex-start;">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--accent); flex-shrink: 0; margin-top: 2px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        <div style="font-size: 13px; line-height: 1.6; color: var(--text-soft);">
          <strong style="color: var(--text);">数据恢复</strong>: 从下拉框选择一个已成功备份的数据 → 点「数据恢复」 → 服务将停 1-5 分钟 (覆盖式恢复, 期间浏览器可能短暂断线, 自动恢复后会重新连接)。
          <br/>恢复会自动备份当前 db 到 .bak 文件, 失败可手动回退。
        </div>
      </div>
    </div>

    <!-- 选择备份 -->
    <div class="card" style="padding: 20px; margin-bottom: 16px;">
      <div class="form-group">
        <label class="form-label">选择备份 *</label>
        <select
          v-model="selectedRecordId"
          class="form-control"
          :disabled="loading || restoring || restorePollingId !== null"
          style="width: 100%;"
        >
          <option :value="null" disabled>请选择要恢复的备份</option>
          <option v-for="item in successBackups" :key="item.id" :value="item.id">
            #{{ item.id }} · {{ item.tag || '无tag' }} · {{ formatDate(item.startedAt) }} · db: {{ formatSize(item.dbSize) }}{{ item.uploadsSize ? ' + uploads: ' + formatSize(item.uploadsSize) : '' }}
          </option>
        </select>
      </div>

      <!-- 备份详情 -->
      <div v-if="selectedBackup" style="margin-top: 16px; padding: 12px; background: var(--bg-soft); border-radius: 8px; border: 1px solid var(--border);">
        <div style="font-size: 13px; color: var(--text-soft); margin-bottom: 8px;">备份详情:</div>
        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; font-size: 13px;">
          <div>
            <span style="color: var(--muted);">ID:</span>
            <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; margin-left: 4px;">#{{ selectedBackup.id }}</code>
          </div>
          <div>
            <span style="color: var(--muted);">Tag:</span>
            <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; margin-left: 4px;">{{ selectedBackup.tag || '-' }}</code>
          </div>
          <div>
            <span style="color: var(--muted);">触发时间:</span>
            <span style="margin-left: 4px;">{{ formatDate(selectedBackup.startedAt) }}</span>
          </div>
          <div>
            <span style="color: var(--muted);">DB 大小:</span>
            <span style="margin-left: 4px;">{{ formatSize(selectedBackup.dbSize) }}</span>
          </div>
          <div v-if="selectedBackup.uploadsSize">
            <span style="color: var(--muted);">Uploads 大小:</span>
            <span style="margin-left: 4px;">{{ formatSize(selectedBackup.uploadsSize) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 恢复中状态 -->
    <div v-if="restorePollingId !== null" class="card" style="padding: 20px; border: 1px solid var(--accent);">
      <div style="display: flex; align-items: center; gap: 12px;">
        <div class="spin-dot" style="width: 12px; height: 12px;"></div>
        <div>
          <div style="font-size: 14px; font-weight: 500;">恢复任务执行中...</div>
          <div style="font-size: 12px; color: var(--muted); margin-top: 4px;">服务将停止约 1-5 分钟，请耐心等待</div>
        </div>
      </div>
    </div>

    <!-- 恢复 Dialog -->
    <div v-if="restoreDialog" class="modal-backdrop" @click.self="cancelRestore">
      <div class="modal" role="dialog" aria-modal="true" aria-label="数据恢复">
        <div class="modal-header">
          <div class="modal-title">数据恢复</div>
          <button @click="cancelRestore" class="modal-close" aria-label="关闭">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">备份</label>
            <div class="form-control" style="display: flex; align-items: center; gap: 6px; background: var(--bg-soft); cursor: default;">
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">#{{ selectedBackup?.id }} · {{ selectedBackup?.tag }}</code>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">恢复范围 *</label>
            <div style="display: flex; flex-direction: column; gap: 6px;">
              <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 6px 10px; border-radius: 6px;">
                <input type="radio" v-model="restoreScope" value="DB_ONLY" />
                <span style="font-size: 13px;">仅恢复数据库</span>
              </label>
              <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 6px 10px; border-radius: 6px;">
                <input type="radio" v-model="restoreScope" value="DB_UPLOADS" />
                <span style="font-size: 13px;">恢复数据库 + 上传文件 (uploads)</span>
              </label>
            </div>
          </div>
          <div style="color: var(--danger); font-size: 13px; line-height: 1.6; padding: 8px 12px; background: rgba(239, 68, 68, 0.08); border-left: 3px solid var(--danger); border-radius: 4px;">
            ⚠ 恢复期间服务将停止约 1-5 分钟, 浏览器可能短暂掉线。恢复会自动备份当前 db 到 .bak 文件, 失败可手动回退。
          </div>
        </div>
        <div class="modal-footer">
          <button @click="cancelRestore" class="btn btn-ghost btn-sm">取消</button>
          <button @click="confirmRestore" class="btn btn-danger btn-sm">确认恢复</button>
        </div>
      </div>
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
  background: var(--accent);
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}
</style>
