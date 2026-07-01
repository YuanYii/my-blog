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

// ============= 恢复历史列表 =============
interface RestoreItem {
  id: number
  status: string
  sourceRecordId?: number
  sourceTag?: string
  scope?: string
  startedAt?: string
  finishedAt?: string
  durationSec?: number
  errorStage?: string
  errorMessage?: string
  verifyDiff?: string
  operatorName?: string
}
const restoreList = ref<RestoreItem[]>([])
const restoreTotal = ref(0)
const restorePage = ref(1)
const restoreSize = ref(20)
const restoreLoading = ref(false)

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

// 容错轮询（v4.3.0 polish：用模块级 usePollingTask 替换组件级 ref + setTimeout，
// 解决"组件卸载时定时器闭包持有的 ref 已被释放"和"catch 块激进清 localStorage"两个 race）
// 关键不变量：localStorage 是 state-of-truth, 定时器在模块作用域, 组件只订阅
const restorePollingTask = usePollingTask('restore')

// 模板里需要判断"是否正在轮询"——给个 computed 镜像
const restorePollingId = computed<number | null>(() => restorePollingTask.state.value.id)

const restoreFetcher = async () => {
  const id = restorePollingTask.state.value.id
  if (id == null) return null
  try {
    const res = await get<any>(`/admin/restore/${id}`)
    return res?.data || null
  } catch (e: any) {
    if (e?.data?.code === 404) {
      return { status: 'UNKNOWN' } as any
    }
    throw e
  }
}

const restorePollingOptions = {
  terminalStatuses: ['SUCCESS', 'FAILED', 'UNKNOWN'],
  initialInterval: 2000,
  maxInterval: 30000,
  maxFailures: 60
}

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

// 拉取恢复历史列表
const fetchRestoreList = async () => {
  restoreLoading.value = true
  try {
    const res = await get<any>('/admin/restore/list', { page: restorePage.value, size: restoreSize.value })
    restoreList.value = res?.data?.records || []
    restoreTotal.value = res?.data?.total || 0
  } catch (e: any) {
    $toast.error(formatError(e, '加载恢复历史失败'))
  } finally {
    restoreLoading.value = false
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

// ============= 恢复历史状态工具 =============
// 2026-06-24 OPT-002：五态色值标准化
//   SUCCESS → success(绿) / FAILED + UNKNOWN → danger(红) / RUNNING → accent(橙)
//   PENDING / 未匹配 → muted(灰)
const restoreStatusType = (s: string) => {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED' || s === 'UNKNOWN') return 'danger'
  if (s === 'RUNNING') return 'accent'
  if (s === 'PENDING') return 'muted'
  return 'muted'
}
const restoreStatusLabel = (s: string) => {
  return {
    PENDING: '排队中',
    RUNNING: '执行中',
    SUCCESS: '成功',
    FAILED: '失败',
    UNKNOWN: '状态未知'
  }[s] || s
}
const restoreScopeLabel = (scope?: string) => {
  return {
    'DB_ONLY': '仅数据库',
    'DB_UPLOADS': '数据库+上传文件'
  }[scope || ''] || scope || '-'
}
const restoreErrorStageLabel = (stage?: string) => {
  if (!stage) return ''
  return {
    PRECHECK: '预检',
    DOWNLOAD: '下载',
    SHA256: '校验',
    DECRYPT: '解密',
    IMPORT: '导入',
    UPLOADS: '恢复上传文件',
    START: '启动脚本',
    HEALTH: '健康检查',
    VERIFY: '数据校验',
    ORPHAN: '孤儿记录处理'
  }[stage] || stage
}

// ============= 恢复历史失败详情弹框 =============
const restoreErrorDialog = ref(false)
const restoreErrorDialogItem = ref<RestoreItem | null>(null)
const openRestoreErrorDialog = (item: RestoreItem) => {
  if (!item.errorMessage && !item.errorStage) return
  restoreErrorDialogItem.value = item
  restoreErrorDialog.value = true
}
const closeRestoreErrorDialog = () => {
  restoreErrorDialog.value = false
  restoreErrorDialogItem.value = null
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
  try {
    const res = await post<any>('/admin/restore/run', {
      recordId: selectedRecordId.value,
      scope: restoreScope.value
    })
    const id = res?.data?.id
    if (!id) throw new Error('未返回 record id')
    $toast.success('恢复任务已创建, 预计 10-60 秒, 过程不停服')
    await restorePollingTask.start(id, restoreFetcher, restorePollingOptions)
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

// 2026-06-28 v5.0.0 DEV-001：恢复 modal 键盘快捷键（Esc 取消 / Enter 确认恢复）
const restoreConfirmButtonRef = ref<HTMLButtonElement | null>(null)
const restoreErrorConfirmButtonRef = ref<HTMLButtonElement | null>(null)
useModalKeyboard({
  open: restoreDialog,
  onCancel: cancelRestore,
  onConfirm: confirmRestore,
  confirmButtonRef: restoreConfirmButtonRef
})
// 失败详情弹框（仅关闭按钮，无 confirm 配对）—— 只挂 Esc，不挂 Enter
useModalKeyboard({
  open: restoreErrorDialog,
  onCancel: closeRestoreErrorDialog,
  confirmButtonRef: restoreErrorConfirmButtonRef
})

// 订阅恢复任务状态 — 终态时弹 Toast + 刷新历史列表
// 必须在 setup() 顶层调用（不在 onMounted 内）, onScopeDispose 才能正确触发
// usePollingTask 内部已用 lastTerminalNotified 防多订阅者重复弹 Toast
restorePollingTask.subscribe((state) => {
  if (!state.id || !state.status) return
  // 终态弹 Toast + 刷新历史
  if (state.status === 'SUCCESS' || state.status === 'FAILED' || state.status === 'UNKNOWN') {
    const data = state.data as any
    if (state.status === 'SUCCESS') {
      $toast.success(`恢复成功: ${data?.sourceTag || ''}`)
    } else if (state.status === 'UNKNOWN') {
      $toast.warning(`恢复状态未知, 请检查服务端日志: ${data?.errorStage || ''}`)
    } else {
      $toast.error(`恢复失败: ${data?.errorStage || '未知阶段'}`)
    }
    fetchRestoreList()  // 刷新恢复历史列表
    fetchSuccessBackups()  // 刷新备份列表（db 可能被覆盖）
  }
})

onMounted(() => {
  fetchSuccessBackups()
  fetchRestoreList()
  // 从 localStorage 恢复轮询 — 后端可能还在停服, restore() 内部会自然失败重试，不清 localStorage
  restorePollingTask.restore(restoreFetcher, restorePollingOptions)
})

onBeforeUnmount(() => {
  // 不停轮询 — usePollingTask 是模块单例, 定时器在模块作用域继续跑
  // 组件卸载只解绑 subscribe 回调（onScopeDispose 自动处理）
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
          <strong style="color: var(--text);">数据恢复</strong>: 从下拉框选择一个已成功备份的数据 → 点「数据恢复」 → v5.0 同进程不停服, 预计 10-60 秒内完成, 期间浏览器无需断线。
          <br/>恢复会自动备份当前 db 到 .bak 文件, 失败可手动回退。
        </div>
      </div>
    </div>

    <!-- 选择备份 -->
    <div class="card" style="padding: 20px; margin-bottom: 16px;">
      <div class="form-group">
        <label class="form-label">选择备份 *</label>
        <UiDropdownSelector
          :model-value="selectedRecordId"
          :options="[{ label: '请选择要恢复的备份', value: null }, ...successBackups.map((item: BackupItem) => ({ label: `#${item.id} · ${item.tag || '无tag'} · ${formatDate(item.startedAt)} · db: ${formatSize(item.dbSize)}${item.uploadsSize ? ' + uploads: ' + formatSize(item.uploadsSize) : ''}`, value: item.id }))]"
          placeholder="请选择要恢复的备份"
          :disabled="loading || restoring || restorePollingId !== null"
          @update:model-value="(v: any) => selectedRecordId = v"
        />
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
          <div style="font-size: 12px; color: var(--muted); margin-top: 4px;">同进程内执行, 预计 10-60 秒</div>
        </div>
      </div>
    </div>

    <!-- 恢复历史记录 -->
    <div v-if="restoreLoading && restoreList.length === 0" style="padding: 40px; text-align: center; color: var(--muted);">加载中...</div>

    <div v-else-if="!restoreList.length" class="card" style="text-align: center; color: var(--muted); padding: 40px; margin-top: 16px;">
      暂无恢复记录
    </div>

    <div v-else class="table-wrap" style="margin-top: 16px;">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 60px;">ID</th>
            <th style="width: 110px;">状态</th>
            <th style="width: 120px;">源备份</th>
            <th style="width: 100px;">恢复范围</th>
            <th style="width: 150px;">开始时间</th>
            <th style="width: 80px;">耗时</th>
            <th style="width: 110px;">操作人</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in restoreList" :key="item.id">
            <td><code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">#{{ item.id }}</code></td>
            <td>
              <span
                v-if="item.status === 'FAILED' && (item.errorStage || item.errorMessage)"
                class="status-badge status-danger clickable"
                @click="openRestoreErrorDialog(item)"
                :title="'点击查看失败详情'"
              >
                {{ restoreStatusLabel(item.status) }}
              </span>
              <span v-else class="status-badge" :class="`status-${restoreStatusType(item.status)}`">
                <span v-if="item.status === 'RUNNING'" class="spin-dot"></span>
                {{ restoreStatusLabel(item.status) }}
              </span>
            </td>
            <td>
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ item.sourceTag || '-' }}</code>
            </td>
            <td style="font-size: 13px;">{{ restoreScopeLabel(item.scope) }}</td>
            <td style="font-size: 12px;">{{ formatDate(item.startedAt) }}</td>
            <td>{{ item.durationSec != null ? (item.durationSec < 60 ? item.durationSec + 's' : Math.floor(item.durationSec / 60) + 'm ' + (item.durationSec % 60) + 's') : '-' }}</td>
            <td style="font-size: 12px;">{{ item.operatorName || '-' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <AdminPagination
      :page="restorePage"
      :size="restoreSize"
      :total="restoreTotal"
      @update:page="(v: number) => restorePage = v"
      @update:size="(v: number) => restoreSize = v"
      @change="fetchRestoreList"
    />

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
            ⚠ v5.0 同进程恢复, 预计 10-60 秒内完成, 期间服务不中断。SQLite Online Backup API 原地替换 db, 替换瞬间业务请求可能短暂 SQLITE_BUSY 后自动重试。
          </div>
        </div>
        <div class="modal-footer">
          <button @click="cancelRestore" class="btn btn-ghost btn-sm">取消</button>
          <button @click="confirmRestore" ref="restoreConfirmButtonRef" class="btn btn-danger btn-sm">确认恢复</button>
        </div>
      </div>
    </div>

    <!-- 恢复历史失败详情弹框 -->
    <div v-if="restoreErrorDialog" class="modal-backdrop" @click.self="closeRestoreErrorDialog">
      <div class="modal" role="dialog" aria-modal="true" aria-label="恢复失败详情">
        <div class="modal-header">
          <div class="modal-title">恢复失败详情</div>
          <button @click="closeRestoreErrorDialog" class="modal-close" aria-label="关闭">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">恢复记录</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default;">
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">
                #{{ restoreErrorDialogItem?.id }} · {{ restoreErrorDialogItem?.status }}
              </code>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">源备份</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default;">
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">
                {{ restoreErrorDialogItem?.sourceTag || '-' }}
              </code>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">失败阶段</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default;">
              <span class="status-badge status-danger">{{ restoreErrorStageLabel(restoreErrorDialogItem?.errorStage) || '-' }}</span>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">错误信息</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default; font-family: 'JetBrains Mono', monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 320px; overflow-y: auto; line-height: 1.6;">
              {{ restoreErrorDialogItem?.errorMessage || '(空)' }}
            </div>
          </div>
          <div v-if="restoreErrorDialogItem?.verifyDiff" class="form-group">
            <label class="form-label">数据校验差异</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default; font-family: 'JetBrains Mono', monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 320px; overflow-y: auto; line-height: 1.6;">
              {{ restoreErrorDialogItem.verifyDiff }}
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button @click="closeRestoreErrorDialog" ref="restoreErrorConfirmButtonRef" class="btn btn-ghost btn-sm">关闭</button>
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
  background: currentColor;  /* 跟随 badge 文字色,使 badge 切换状态时圆点同步换色 */
  margin-right: 6px;
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}

/* 2026-06-24 OPT-002：恢复历史状态 badge 五态色值
   - backup.vue 的同名 style scoped 只在 backup.vue 生效,restore.vue 无样式 → 用户看到的 badge 是纯文字无底色
   - 这里照搬 backup.vue:620-639 的色值,保持两页面视觉一致 */
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}
.status-success { background: rgba(34, 197, 94, 0.12);  color: var(--success); }
.status-danger  { background: rgba(239, 68, 68, 0.12);  color: var(--danger); }
.status-accent  { background: rgba(201, 123, 63, 0.12); color: var(--accent); }
.status-muted   { background: var(--bg);                color: var(--muted); }
.status-badge.clickable {
  cursor: pointer;
  transition: filter 0.15s;
}
.status-badge.clickable:hover {
  filter: brightness(0.95);
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
