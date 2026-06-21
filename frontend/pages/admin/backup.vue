<script setup lang="ts">
/**
 * 数据备份 + 数据恢复页（REQ-BACKUP-2026-06-20 + REQ-BACKUP-POLISH-2026-06-21 + REQ-RESTORE-2026-06-20，v4.2.0 / v4.2.1 / v4.3.0）
 *
 * 设计依据：
 *  - docs/设计文档/博客数据备份方案设计.md
 *  - docs/设计文档/博客数据恢复方案设计.md
 *
 * 2026-06-21 v4.2.1 polish 整体重写：
 *  - 表格风格对齐 devices.vue（.table-wrap > table.table，去内联 width/scoped style）
 *  - 新增删除按钮（DELETE /admin/backup/{id}），三种状态不同二次确认：
 *      PENDING/RUNNING → 禁用 + tooltip
 *      SUCCESS → $dialog.prompt 输 DELETE 字样（防误删，会真删 GitHub Release）
 *      FAILED  → $dialog.confirm 普通确认
 *  - 失败任务点击状态/失败阶段 → 弹框展示 errorMessage（不再 inline 显示，行高不再爆）
 *  - Tag 列加复制按钮（navigator.clipboard.writeText）
 *  - 移除资产数列（db/uploads 两列已够，asset_count 留给 detail 接口按需查）
 *  - formatError helper 统一抽 traceId 后缀，业务错（3001/3002/2003...）也带 traceId 排查更顺
 *  - 触发备份按钮改 $dialog.confirm 二次确认（之前是 window.confirm 无样式）
 *
 * 交互流程：
 *  【备份】
 *  1. 用户点「立即备份」 → 二次确认 Dialog → 调 POST /admin/backup/run
 *  2. 后端立即返回 record id → 前端轮询 GET /admin/backup/{id}（每 2s）
 *  3. 状态从 PENDING → RUNNING → SUCCESS / FAILED
 *
 *  【恢复】（v4.3.0）
 *  1. 用户在列表单选一条 SUCCESS 备份 → 点「数据恢复」按钮
 *  2. 二次确认 Dialog → 选 DB_ONLY / DB_UPLOADS → 调 POST /admin/restore/run
 *  3. 后端建 PENDING + 异步跑 blog-restore.sh（systemd-run --scope, 即发即忘）
 *  4. 前端启动容错轮询（退避重试 2s→30s, 容忍后端停服 1-5 分钟）
 *  5. RestoreStartupReconciler 双轨回填（启动 + @Scheduled 5min）状态进 SUCCESS/FAILED/UNKNOWN
 *
 * 失败信息不含密码/secret（后端已脱敏）
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post, del } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()

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
  assetUrls?: string[]
  errorStage?: string
  errorMessage?: string
  operatorName?: string
  traceId?: string
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
// 2026-06-21 v4.2.1 polish: 改用 $dialog.confirm(代替 window.confirm),与全站风格一致
const handleTrigger = async () => {
  if (triggering.value) return
  if (restorePollingId.value !== null) {
    $toast.error('恢复进行中,不能触发备份')
    return
  }
  const { confirmed } = await $dialog.confirm({
    title: '立即备份',
    message: '备份期间不阻塞服务,但会立即占用磁盘和 GitHub 流量。确认开始?',
    confirmText: '开始备份',
    danger: false
  })
  if (!confirmed) return
  triggering.value = true
  try {
    const res = await post<any>('/admin/backup/run', {})
    const id = res?.data?.id
    if (!id) throw new Error('未返回 record id')
    $toast.success(res?.data?.message || '备份任务已创建')
    await fetchList()
    startPolling(id)
  } catch (e: any) {
    $toast.error(formatError(e, '触发失败'))
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
    $toast.error(formatError(e, '加载备份历史失败'))
  } finally {
    loading.value = false
  }
}

// ============= 失败详情弹框（任务 4 方案 A）=============
// 点击失败任务的「状态 / 失败阶段」cell → 弹框展示完整 errorMessage
// 不再 inline 显示（行高不再爆，可读性更好）
const errorDialog = ref(false)
const errorDialogItem = ref<BackupItem | null>(null)
const openErrorDialog = (item: BackupItem) => {
  if (!item.errorMessage && !item.errorStage) return  // 没失败信息不弹
  errorDialogItem.value = item
  errorDialog.value = true
}
const closeErrorDialog = () => {
  errorDialog.value = false
  errorDialogItem.value = null
}

// ============= 删除按钮（任务 2）=============
// 三态分支：
//  PENDING / RUNNING → UI 禁用 + tooltip（后端 3002 兜底）
//  SUCCESS → $dialog.prompt 输 DELETE 字样 → 防误删,会真删 GitHub Release
//  FAILED  → 普通 $dialog.confirm → 只删 db 行
// 2026-06-21 自查 P1-1 修复: deletingId 防并发(防止连点触发两次 DELETE + 两次 GitHub release delete)
const deletingId = ref<number | null>(null)
const handleDelete = async (item: BackupItem) => {
  if (deletingId.value !== null) return  // 防并发:上一次删除未结束直接挡掉
  if (item.status === 'PENDING' || item.status === 'RUNNING') {
    $toast.warning('备份任务进行中,无法删除')
    return
  }
  if (item.status === 'SUCCESS') {
    const { confirmed, value } = await $dialog.prompt({
      title: '删除备份记录',
      message: `将同时删除 GitHub Release tag「${item.tag}」,此操作不可撤销。\n\n请输入 DELETE 字样以确认:`,
      label: '确认字样',
      placeholder: 'DELETE',
      defaultValue: '',
      confirmText: '删除',
      danger: true
    })
    if (!confirmed) return
    if ((value || '').trim() !== 'DELETE') {
      $toast.warning('确认字样不正确,已取消删除')
      return
    }
  } else if (item.status === 'FAILED') {
    const { confirmed } = await $dialog.confirm({
      title: '删除失败记录',
      message: '此操作不可撤销,确认删除这条失败记录?',
      confirmText: '删除',
      danger: true
    })
    if (!confirmed) return
  } else {
    // 未知状态保险一下
    const { confirmed } = await $dialog.confirm({
      title: '删除备份记录',
      message: `状态「${item.status}」非 SUCCESS/FAILED,确认删除?`,
      confirmText: '删除',
      danger: true
    })
    if (!confirmed) return
  }
  deletingId.value = item.id
  try {
    await del(`/admin/backup/${item.id}`)
    $toast.success('已删除')
    // 2026-06-21 v4.2.1 polish: 删除最后一条且非首页 → 自动回退到上一页,避免空白页
    if (list.value.length <= 1 && page.value > 1) {
      page.value = page.value - 1
    }
    await fetchList()
  } catch (e: any) {
    $toast.error(formatError(e, '删除失败'))
  } finally {
    deletingId.value = null
  }
}

// ============= 复制 tag =============
const handleCopyTag = async (tag: string) => {
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(tag)
      $toast.success(`已复制 tag: ${tag}`)
    } else {
      // 兜底:execCommand('copy') 兼容老浏览器/非 https
      const ta = document.createElement('textarea')
      ta.value = tag
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      $toast.success(`已复制 tag: ${tag}`)
    }
  } catch (e: any) {
    $toast.warning('复制失败,请手动选中复制')
  }
}

// 2026-06-21：复制 traceId（失败详情弹框里用,owner 反查 server log）
// 复用 handleCopyTag 的 clipboard 兜底逻辑,只是文案不同
const handleCopyTraceId = async (traceId: string) => {
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(traceId)
      $toast.success(`已复制 traceId: ${traceId}`)
    } else {
      const ta = document.createElement('textarea')
      ta.value = traceId
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      $toast.success(`已复制 traceId: ${traceId}`)
    }
  } catch (e: any) {
    $toast.warning('复制失败,请手动选中复制')
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
    $toast.error(formatError(e, '触发失败'))
  }
}

// 取消 Dialog（点击遮罩或取消按钮）
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

// ============= 工具 =============

/**
 * 2026-06-21 v4.2.1 polish: 统一抽 traceId 后缀
 * 后端 GlobalExceptionHandler / AdminAuthFilter / IpRateLimitFilter 都在 message 末尾拼了
 * "（请反馈编号 traceId=xxx）";优先复用,再 from response header X-Trace-Id 兜底
 */
const formatError = (e: any, fallback = '操作失败'): string => {
  if (!e) return fallback
  if (e?.data?.message) return e.data.message
  if (e?.message) return e.message
  // 兜底:某些 401/429 走 filter 直写响应,body message 为空但响应头 X-Trace-Id 还在
  // 拼成 "操作失败（请反馈编号 traceId=xxx）" 让用户能复制编号定位日志
  const traceId = e?.response?.headers?.get?.('X-Trace-Id')
  if (traceId) return `${fallback}（请反馈编号 traceId=${traceId}）`
  return fallback
}

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
// 失败阶段翻译:errorStage 是英文枚举(SCRIPT/UPLOAD/PACK/DUMP),前端显示中文
const errorStageLabel = (stage?: string) => {
  if (!stage) return ''
  return {
    SCRIPT: '脚本执行',
    UPLOAD: 'GitHub 上传',
    PACK: '上传打包',
    DUMP: '数据库导出'
  }[stage] || stage
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
          class="btn-new"
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
          <br/><strong style="color: var(--text);">删除记录</strong>: FAILED 直接确认;SUCCESS 需输入 <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; padding: 1px 5px; background: var(--bg); border-radius: 3px;">DELETE</code> 字样(同时删 GitHub Release tag);PENDING/RUNNING 不能删。
          <br/><strong style="color: var(--text);">同时只能运行 1 个备份或恢复任务</strong>, 避免资源争用。
        </div>
      </div>
    </div>

    <!-- 历史列表 -->
    <div v-if="loading && list.length === 0" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <div v-else-if="!list.length" class="card" style="text-align: center; color: var(--muted); padding: 40px;">
      暂无备份记录
    </div>

    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 50px;">选择</th>
            <th style="width: 60px;">ID</th>
            <th style="width: 110px;">状态</th>
            <th>Tag</th>
            <th style="width: 150px;">触发时间</th>
            <th style="width: 80px;">耗时</th>
            <th style="width: 100px;">db 大小</th>
            <th style="width: 100px;">uploads</th>
            <th style="width: 110px;">操作人</th>
            <th style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
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
              <!--
                状态列只显示「成功 / 失败」字样(PENDING/RUNNING 保持中文标签)。
                失败时可点"失败"字样 → 弹框看完整 errorStage + errorMessage(详见下方 errorDialog)。
                2026-06-21 v4.2.1 polish 修正:之前这里显示 errorStageLabel("脚本执行/GitHub 上传"等),
                跟"状态"语义错位——状态列说状态,失败阶段留给详情弹框。
              -->
              <span
                v-if="item.status === 'FAILED' && (item.errorStage || item.errorMessage)"
                class="status-badge status-danger clickable"
                @click="openErrorDialog(item)"
                :title="'点击查看失败详情'"
              >
                {{ statusLabel(item.status) }}
              </span>
              <span v-else class="status-badge" :class="`status-${statusType(item.status)}`">
                <span v-if="item.status === 'RUNNING'" class="spin-dot"></span>
                {{ statusLabel(item.status) }}
              </span>
            </td>
            <td>
              <!--
                任务 4 方案 A: 失败任务"点击状态 cell"打开弹框 → 这里 Tag 列不再重复放可点 badge。
                失败任务 tag 通常为 null(脚本没产出 .result.json 时 tag 没生成),显示 - 即可。
              -->
              <div v-if="item.tag" style="display: flex; align-items: center; gap: 6px;">
                <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ item.tag }}</code>
                <button
                  @click="handleCopyTag(item.tag)"
                  class="row-action"
                  title="复制 tag"
                  aria-label="复制 tag"
                  style="width: 22px; height: 22px;"
                >
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                </button>
              </div>
              <span v-else style="color: var(--muted);">-</span>
            </td>
            <td style="font-size: 12px;">{{ formatDate(item.startedAt) }}</td>
            <td>{{ formatDuration(item.durationSec) }}</td>
            <td>{{ formatSize(item.dbSize) }}</td>
            <td>{{ formatSize(item.uploadsSize) }}</td>
            <td style="font-size: 12px;">{{ item.operatorName || '-' }}</td>
            <td>
              <div class="row-actions">
                <button
                  @click="handleDelete(item)"
                  class="row-action danger"
                  :disabled="item.status === 'PENDING' || item.status === 'RUNNING'"
                  :title="(item.status === 'PENDING' || item.status === 'RUNNING') ? '备份任务进行中,无法删除' : (item.status === 'SUCCESS' ? '删除(同时删 GitHub Release tag)' : '删除记录')"
                  :style="(item.status === 'PENDING' || item.status === 'RUNNING') ? { opacity: 0.4, cursor: 'not-allowed' } : null"
                  aria-label="删除"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页(对齐 admin 设计系统 .admin-pagination) -->
    <div v-if="total > size" class="admin-pagination">
      <button
        @click="page = Math.max(1, page - 1); fetchList()"
        :disabled="page === 1"
        class="btn btn-sm"
      >上一页</button>
      <div class="pages">
        <span style="padding: 6px 12px; color: var(--muted); font-size: 13px;">{{ page }} / {{ Math.ceil(total / size) }}</span>
      </div>
      <button
        @click="page = page + 1; fetchList()"
        :disabled="page * size >= total"
        class="btn btn-sm"
      >下一页</button>
    </div>

    <!-- 失败详情弹框(任务 4 方案 A) -->
    <div v-if="errorDialog" class="modal-backdrop" @click.self="closeErrorDialog">
      <div class="modal" role="dialog" aria-modal="true" aria-label="备份失败详情">
        <div class="modal-header">
          <div class="modal-title">备份失败详情</div>
          <button @click="closeErrorDialog" class="modal-close" aria-label="关闭">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">备份记录</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default;">
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">
                #{{ errorDialogItem?.id }} · {{ errorDialogItem?.status }}
              </code>
            </div>
          </div>
          <!--
            2026-06-21：失败详情里展示 traceId,owner 拿这个编号反查 server log
            /opt/myblog/logs/blog.log.* 定位完整请求链路
            没拿到 traceId(理论上 @Async 后 MDC 透传没接,这是从 record 字段读的)时也兼容,显示 -
          -->
          <div class="form-group" v-if="errorDialogItem?.traceId">
            <label class="form-label">追踪编号（反馈给 owner 查日志用）</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default; display: flex; align-items: center; gap: 8px;">
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px; flex: 1; word-break: break-all;">{{ errorDialogItem.traceId }}</code>
              <button @click="handleCopyTraceId(errorDialogItem.traceId)" class="row-action" title="复制 traceId" aria-label="复制 traceId" style="width: 24px; height: 24px;">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
              </button>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">失败阶段</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default;">
              <span class="status-badge status-danger">{{ errorStageLabel(errorDialogItem?.errorStage) || '-' }}</span>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">错误信息</label>
            <div class="form-control" style="background: var(--bg-soft); cursor: default; font-family: 'JetBrains Mono', monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 320px; overflow-y: auto; line-height: 1.6;">
              {{ errorDialogItem?.errorMessage || '(空)' }}
            </div>
          </div>
          <div v-if="errorDialogItem?.tag" style="color: var(--text-soft); font-size: 12px; padding: 8px 12px; background: var(--bg-soft); border-left: 3px solid var(--accent); border-radius: 4px;">
            Tag: <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ errorDialogItem.tag }}</code>
          </div>
        </div>
        <div class="modal-footer">
          <button @click="closeErrorDialog" class="btn btn-ghost btn-sm">关闭</button>
        </div>
      </div>
    </div>

    <!-- 恢复 Dialog (v4.3.0 新增) -->
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
              <code style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ selectedItem?.tag }}</code>
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
/* 2026-06-21 v4.2.1 polish: scoped style 只保留页面独有交互(轮询行高亮/旋转/失败 badge 可点击)
   其他都走 main.css 全局设计系统(.table-wrap / .table / .row-actions / .admin-pagination / .btn-new / .modal-*) */
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
/* 失败 badge 可点击 → 加 cursor + hover 反馈 */
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
