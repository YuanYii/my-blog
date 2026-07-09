<script setup lang="ts">
/**
 * 系统升级页（20260708-DEV-003）
 *
 * 通过 upgrade-agent 代理执行 deploy-server.sh 升级
 * SSE 流式返回升级日志
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post } = useAdminApi()
const { confirm } = useDialog()
const $toast = useToast()

// ============= 状态 =============
const currentVersion = ref('')
const versions = ref<any[]>([])
const selectedVersion = ref<string | null>(null)
const manualVersion = ref('')
const useManualVersion = ref(false)
const upgradeMode = ref<'full' | 'init'>('full')
const importDb = ref(false)
const confirmText = ref('')
const upgrading = ref(false)
const agentStatus = ref('checking')
const versionsLoading = ref(false)

// ============= SSE 日志 =============
const logs = ref<string[]>([])
const logContainer = ref<HTMLElement | null>(null)
const showLogs = ref(false)

// ============= 升级历史 =============
interface UpgradeRecord {
  id: number
  targetVersion: string
  mode: string
  importDb: boolean
  status: string
  startedAt: string
  finishedAt?: string
  fromVersion?: string
  errorMessage?: string
  operatorName?: string
  ip?: string
}
const records = ref<UpgradeRecord[]>([])
const recordsTotal = ref(0)
const recordsPage = ref(1)
const recordsLoading = ref(false)

// ============= 格式化工具 =============
const formatError = (e: any, fallback = '操作失败'): string => {
  if (!e) return fallback
  if (e?.data?.message) return e.data.message
  if (e?.message) return e.message
  return fallback
}

// ============= 实际选中的版本 =============
const effectiveVersion = computed(() => {
  if (useManualVersion.value) return manualVersion.value.trim()
  return selectedVersion.value
})

// ============= 数据获取 =============
const fetchStatus = async () => {
  try {
    const res: any = await get('/admin/upgrade/status')
    currentVersion.value = res?.data?.currentVersion || 'unknown'
    agentStatus.value = res?.data?.agentStatus?.status || 'unreachable'
  } catch {
    agentStatus.value = 'unreachable'
  }
}

const fetchVersions = async () => {
  versionsLoading.value = true
  try {
    const res: any = await get('/admin/upgrade/versions')
    versions.value = res?.data || []
    if (versions.value.length > 0 && !selectedVersion.value) {
      selectedVersion.value = versions.value[0].tagName
    }
  } catch (e: any) {
    $toast.error(formatError(e, '获取版本列表失败'))
  } finally {
    versionsLoading.value = false
  }
}

const fetchRecords = async () => {
  recordsLoading.value = true
  try {
    const res: any = await get('/admin/upgrade/records', { page: recordsPage.value, size: 20 })
    records.value = res?.data?.records || []
    recordsTotal.value = res?.data?.total || 0
  } catch (e: any) {
    $toast.error(formatError(e, '加载升级历史失败'))
  } finally {
    recordsLoading.value = false
  }
}

const formatDate = (s?: string) => s ? new Date(s).toLocaleString('zh-CN', { hour12: false }) : '-'
const statusType = (s: string) => {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING') return 'accent'
  return 'muted'
}
const statusLabel = (s: string) => ({ PENDING: '排队中', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败' }[s] || s)

// ============= 升级操作 =============
const scrollToBottom = () => {
  if (logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

const handleUpgrade = async () => {
  const version = effectiveVersion.value
  // 统一方案：version 可为空，后端自动获取最新版本

  const needConfirm = importDb.value
  if (needConfirm && confirmText.value !== '确认') {
    $toast.warning('请输入"确认"以启用数据覆盖')
    return
  }

  const versionText = version || '最新版本'
  const { confirmed } = await confirm({
    title: '确认升级',
    message: `确定要升级到 ${versionText} 吗？模式: ${upgradeMode.value === 'full' ? '全量代码升级' : '初始化'}`,
    confirmText: '开始升级',
    danger: true,
  })
  if (!confirmed) return

  upgrading.value = true
  showLogs.value = true
  logs.value = []

  try {
    const body: any = {
      version: version || '',  // 空字符串时后端自动获取最新版本
      mode: upgradeMode.value,
      importDb: importDb.value,
      confirm: confirmText.value,
    }

    const response = await fetch(`${useRuntimeConfig().public.apiBase}/admin/upgrade`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${useAuth().token.value}`,
        'X-Device-Id': useDevice().deviceId.value || '',
      },
      body: JSON.stringify(body),
    })

    if (!response.ok) {
      const err = await response.json()
      $toast.error(err.message || '升级请求失败')
      upgrading.value = false
      return
    }

    const reader = response.body?.getReader()
    const decoder = new TextDecoder()

    if (reader) {
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        let eventName = ''
        for (const line of lines) {
          if (line.startsWith('event: ')) {
            eventName = line.substring(7).trim()
          } else if (line.startsWith('data: ')) {
            const data = line.substring(6)
            if (eventName === 'log') {
              logs.value.push(data)
              nextTick(scrollToBottom)
            } else if (eventName === 'done') {
              try {
                const result = JSON.parse(data)
                if (result.success) {
                  $toast.success('升级成功！')
                } else {
                  $toast.error('升级失败: ' + (result.error || ''))
                }
              } catch {
                $toast.error('升级完成，但解析结果失败')
              }
              upgrading.value = false
              fetchStatus()
            } else if (eventName === 'error') {
              $toast.error(data)
              upgrading.value = false
            }
            eventName = ''
          }
        }
      }
    }
  } catch (e: any) {
    $toast.error('升级请求异常: ' + e.message)
    upgrading.value = false
  }
}

onMounted(() => {
  fetchStatus()
  fetchVersions()
  fetchRecords()
})
</script>

<template>
  <div>
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <h1>系统升级</h1>
        <p>通过 GitHub Release 升级博客系统到指定版本</p>
      </div>
      <div style="display: flex; gap: 8px;">
        <button
          @click="fetchVersions"
          :disabled="versionsLoading"
          class="btn-new"
        >
          <svg v-if="!versionsLoading" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="40 60"/></svg>
          {{ versionsLoading ? '拉取中…' : '拉取版本' }}
        </button>
        <button
          @click="handleUpgrade"
          :disabled="upgrading"
          class="btn-new"
          style="background: var(--primary); color: white;"
        >
          <svg v-if="!upgrading" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/><polyline points="16 16 12 12 8 16"/><line x1="12" y1="12" x2="12" y2="21"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="40 60"/></svg>
          {{ upgrading ? '升级中…' : '开始升级' }}
        </button>
      </div>
    </div>

    <!-- 提示卡 -->
    <div class="card" style="padding: 16px; margin-bottom: 16px; background: var(--bg-soft); border: 1px solid var(--border);">
      <div style="display: flex; gap: 10px; align-items: flex-start;">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--accent); flex-shrink: 0; margin-top: 2px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        <div style="font-size: 13px; line-height: 1.6; color: var(--text-soft);">
          <strong style="color: var(--text);">系统升级</strong>: 从下拉框选择目标版本，或切换到手动输入模式输入版本号，也可不选版本直接升级（自动获取最新版本）→ 点「开始升级」→ upgrade-agent 代理执行 deploy-server.sh，SSE 流式返回日志。
          <br/>升级期间服务会短暂中断（预计 1-3 分钟），升级完成后自动恢复。
        </div>
      </div>
    </div>

    <!-- 当前状态 -->
    <div class="card" style="padding: 20px; margin-bottom: 16px;">
      <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; font-size: 13px;">
        <div>
          <span style="color: var(--muted);">当前版本:</span>
          <span class="version-badge" style="margin-left: 8px;">{{ currentVersion }}</span>
        </div>
        <div>
          <span style="color: var(--muted);">升级代理:</span>
          <span :class="['status-badge', agentStatus === 'idle' ? 'status-success' : agentStatus === 'running' ? 'status-accent' : 'status-danger']" style="margin-left: 8px;">
            <span v-if="agentStatus === 'running'" class="spin-dot"></span>
            {{ agentStatus }}
          </span>
        </div>
      </div>
    </div>

    <!-- 升级配置 -->
    <div class="card" style="padding: 20px; margin-bottom: 16px;">
      <!-- 版本来源切换 -->
      <div class="form-group">
        <label class="form-label">目标版本 *</label>
        <div style="display: flex; gap: 8px; margin-bottom: 8px;">
          <label style="display: flex; align-items: center; gap: 6px; cursor: pointer; font-size: 13px;">
            <input type="radio" v-model="useManualVersion" :value="false" :disabled="upgrading" />
            <span>从列表选择</span>
          </label>
          <label style="display: flex; align-items: center; gap: 6px; cursor: pointer; font-size: 13px;">
            <input type="radio" v-model="useManualVersion" :value="true" :disabled="upgrading" />
            <span>手动输入</span>
          </label>
        </div>

        <!-- 下拉选择 -->
        <UiDropdownSelector
          v-if="!useManualVersion"
          :model-value="selectedVersion"
          :options="[{ label: versions.length ? '请选择目标版本' : '暂无可用版本，请点击「拉取版本」或切换手动输入', value: null }, ...versions.map((v: any) => ({ label: `${v.tagName} — ${v.name || '无描述'}`, value: v.tagName }))]"
          placeholder="请选择目标版本"
          :disabled="upgrading || versionsLoading"
          @update:model-value="(v: any) => selectedVersion = v"
        />

        <!-- 手动输入 -->
        <input
          v-else
          v-model="manualVersion"
          type="text"
          placeholder='输入版本号，如 v5.3.0'
          :disabled="upgrading"
          class="form-control"
        />
      </div>

      <div class="form-group">
        <label class="form-label">升级模式 *</label>
        <div style="display: flex; flex-direction: column; gap: 6px;">
          <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 6px 10px; border-radius: 6px;">
            <input type="radio" v-model="upgradeMode" value="full" :disabled="upgrading" />
            <span style="font-size: 13px;">全量代码升级（推荐）</span>
          </label>
          <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 6px 10px; border-radius: 6px;">
            <input type="radio" v-model="upgradeMode" value="init" :disabled="upgrading" />
            <span style="font-size: 13px;">初始化（仅建库 + 种子数据）</span>
          </label>
        </div>
      </div>

      <div class="form-group">
        <label style="display: flex; align-items: center; gap: 8px; cursor: pointer;">
          <input type="checkbox" v-model="importDb" :disabled="upgrading" />
          <span style="font-size: 13px;">覆盖数据（从 Release 导入加密 db dump）</span>
        </label>
      </div>

      <div v-if="importDb" class="form-group">
        <label class="form-label">确认码 *</label>
        <input
          v-model="confirmText"
          type="text"
          placeholder='请输入"确认"'
          :disabled="upgrading"
          class="form-control"
          style="border-color: var(--accent);"
        />
        <p style="font-size: 12px; color: var(--muted); margin-top: 4px;">importDb=true 时必须输入"确认"二字</p>
      </div>
    </div>

    <!-- 升级中状态 -->
    <div v-if="upgrading" class="card" style="padding: 20px; border: 1px solid var(--accent);">
      <div style="display: flex; align-items: center; gap: 12px;">
        <div class="spin-dot" style="width: 12px; height: 12px;"></div>
        <div>
          <div style="font-size: 14px; font-weight: 500;">升级任务执行中...</div>
          <div style="font-size: 12px; color: var(--muted); margin-top: 4px;">通过 upgrade-agent 代理执行, 预计 1-3 分钟</div>
        </div>
      </div>
    </div>

    <!-- 实时日志 -->
    <div v-if="showLogs" class="card" style="padding: 20px; margin-top: 16px;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
        <h3 style="font-size: 14px; font-weight: 500; margin: 0;">升级日志</h3>
        <span style="font-size: 12px; color: var(--muted);">{{ logs.length }} 行</span>
      </div>
      <pre ref="logContainer" class="log-area">{{ logs.join('\n') }}</pre>
    </div>

    <!-- 升级历史 -->
    <div class="card" style="padding: 20px; margin-top: 16px;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h3 style="font-size: 14px; font-weight: 500; margin: 0;">升级历史</h3>
        <button @click="fetchRecords" class="btn btn-ghost btn-sm" style="font-size: 12px;">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/></svg>
          刷新
        </button>
      </div>

      <div v-if="recordsLoading && records.length === 0" style="padding: 40px; text-align: center; color: var(--muted);">加载中...</div>
      <div v-else-if="!records.length" style="text-align: center; color: var(--muted); padding: 40px;">暂无升级记录</div>

      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 60px;">ID</th>
              <th style="width: 110px;">状态</th>
              <th style="width: 100px;">目标版本</th>
              <th style="width: 100px;">来源版本</th>
              <th style="width: 100px;">模式</th>
              <th style="width: 150px;">开始时间</th>
              <th style="width: 150px;">完成时间</th>
              <th style="width: 100px;">操作人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in records" :key="item.id">
              <td><code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">#{{ item.id }}</code></td>
              <td>
                <span v-if="item.status === 'FAILED'" class="status-badge status-danger">
                  {{ statusLabel(item.status) }}
                </span>
                <span v-else class="status-badge" :class="`status-${statusType(item.status)}`">
                  <span v-if="item.status === 'RUNNING'" class="spin-dot"></span>
                  {{ statusLabel(item.status) }}
                </span>
                <div v-if="item.status === 'FAILED' && item.errorMessage" class="error-reason" :title="item.errorMessage">
                  {{ item.errorMessage.length > 30 ? item.errorMessage.substring(0, 30) + '...' : item.errorMessage }}
                </div>
              </td>
              <td><code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ item.targetVersion }}</code></td>
              <td style="font-size: 12px;">{{ item.fromVersion || '-' }}</td>
              <td style="font-size: 12px;">{{ item.mode === 'full' ? '全量代码升级' : '初始化' }}</td>
              <td style="font-size: 12px;">{{ formatDate(item.startedAt) }}</td>
              <td style="font-size: 12px;">{{ formatDate(item.finishedAt) }}</td>
              <td style="font-size: 12px;">{{ item.operatorName || '-' }}</td>
            </tr>
          </tbody>
        </table>
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
  background: currentColor;
  margin-right: 6px;
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}

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
.error-reason {
  font-size: 11px;
  color: var(--danger);
  margin-top: 4px;
  line-height: 1.3;
  word-break: break-all;
  max-width: 200px;
}

.version-badge {
  background: var(--primary, #2f6f5e);
  color: white;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.log-area {
  background: #1a1a2e;
  color: #e0e0e0;
  padding: 12px;
  border-radius: 6px;
  font-family: 'JetBrains Mono', 'SF Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.6;
  max-height: 400px;
  overflow-y: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
