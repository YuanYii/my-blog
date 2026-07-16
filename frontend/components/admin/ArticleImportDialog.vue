<script setup lang="ts">
/**
 * 文章 ZIP 导入对话框（2026-06-24 DEV-002）
 *
 * 流程：
 *  1. 用户选择 ZIP（≤5MB,前端 size 校验）
 *  2. 调 POST /articles/admin/import 后端立即返回 record id
 *  3. 前端轮询 GET /articles/admin/import/{id} 每 1.5s 直到 status ∈ {SUCCESS, FAILED}
 *  4. 显示 总数 / 成功 / 失败 / 错误详情
 *  5. 历史记录列表展示最近导入（点击对话框打开即拉）
 */
const emit = defineEmits<{ (e: 'close', refreshed?: boolean): void }>()

const router = useRouter()
const { upload, get, request } = useAdminApi()
const $toast = useToast()
const config = useRuntimeConfig()
const { token } = useAuth()
const { deviceId } = useDevice()

const MAX_SIZE = 5 * 1024 * 1024

const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const uploading = ref(false)
const currentId = ref<number | null>(null)
const currentRecord = ref<any>(null)
const polling = ref(false)
// 2026-07-15 OPT-001：导入成功后展示「前往草稿箱」入口
const justImportedDraft = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const history = ref<any[]>([])
const loadHistory = async () => {
  try {
    const res = await get<any>('/articles/admin/import', { limit: 20 })
    history.value = res.data || []
  } catch { history.value = [] }
}

const onPick = () => {
  fileInput.value?.click()
}

const onFileChange = (e: Event) => {
  const t = e.target as HTMLInputElement
  const f = t.files?.[0]
  if (!f) return
  if (!f.name.toLowerCase().endsWith('.zip')) {
    $toast.error('仅支持 .zip 文件')
    t.value = ''
    return
  }
  if (f.size > MAX_SIZE) {
    $toast.error('文件大小不能超过 5MB')
    t.value = ''
    return
  }
  file.value = f
}

const startImport = async () => {
  if (!file.value) {
    $toast.warning('请先选择 ZIP 文件')
    return
  }
  justImportedDraft.value = false
  uploading.value = true
  try {
    const res = await upload<any>('/articles/admin/import', file.value)
    currentId.value = res.data?.id
    if (currentId.value) startPolling(currentId.value)
  } catch (e: any) {
    $toast.error('上传失败：' + (e?.data?.message || e?.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

const startPolling = (id: number) => {
  polling.value = true
  const fetchOnce = async () => {
    try {
      const res = await get<any>(`/articles/admin/import/${id}`)
      currentRecord.value = res.data
      if (res.data?.status === 'SUCCESS' || res.data?.status === 'FAILED') {
        stopPolling()
        loadHistory()
        if (res.data?.status === 'SUCCESS') {
          const succ = res.data?.successCount ?? 0
          const fail = res.data?.failCount ?? 0
          if (fail === 0) {
            // 2026-07-15 OPT-001：导入文章默认进草稿箱，提示用户去发布
            $toast.success(`导入完成,共 ${succ} 篇（已放入草稿箱，待发布）`)
            justImportedDraft.value = true
          }
          else $toast.warning(`导入完成: ${succ} 成功 / ${fail} 失败`)
        } else {
          $toast.error('导入失败: ' + (res.data?.errorMessage || '未知错误'))
        }
      }
    } catch (e: any) {
      // 网络瞬断不停止,等下一轮
      console.warn('[import] 轮询失败', e)
    }
  }
  fetchOnce()
  pollTimer = setInterval(fetchOnce, 1500)
}

const stopPolling = () => {
  polling.value = false
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const downloadTemplate = async () => {
  // 直接走原生 fetch 拿到 blob, useAdminApi 的 $fetch 会尝试解 JSON 不适合下载
  const headers: Record<string, string> = {}
  if (token.value) headers['Authorization'] = `Bearer ${token.value}`
  if (deviceId.value) headers['X-Device-Id'] = deviceId.value
  try {
    const resp = await fetch(`${config.public.apiBase}/articles/admin/import/template`, { headers })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'import-template.zip'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e: any) {
    $toast.error('模板下载失败: ' + (e?.message || '未知错误'))
  }
}

const close = () => {
  stopPolling()
  emit('close', currentRecord.value?.status === 'SUCCESS' && (currentRecord.value?.successCount ?? 0) > 0)
}

const formatDate = (s?: string) => {
  if (!s) return ''
  try {
    const d = new Date(s)
    return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  } catch { return s }
}

const statusLabel = (s?: string) => {
  switch (s) {
    case 'PENDING': return '等待中'
    case 'RUNNING': return '导入中'
    case 'SUCCESS': return '已完成'
    case 'FAILED':  return '失败'
    default: return s || ''
  }
}

const statusColor = (s?: string) => {
  if (s === 'SUCCESS') return 'var(--success)'
  if (s === 'FAILED') return 'var(--danger)'
  if (s === 'RUNNING' || s === 'PENDING') return 'var(--accent)'
  return 'var(--text-2)'
}

onMounted(loadHistory)
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="import-mask" @click.self="close">
    <div class="import-dialog">
      <div class="import-head">
        <h3>文章导入</h3>
        <button class="close-btn" @click="close" aria-label="关闭">×</button>
      </div>

      <div class="import-body">
        <p class="import-tip">
          上传 ZIP 包,后台异步解析 Markdown 文件并创建文章。
          ZIP 包仅支持 <code>.md</code> 和 <code>.png</code>,大小 ≤ 5MB。
          <a href="javascript:void(0)" @click="downloadTemplate">下载模板</a>
        </p>

        <div class="import-pick" @click="onPick">
          <input ref="fileInput" type="file" accept=".zip" style="display:none" @change="onFileChange" />
          <div v-if="!file" class="pick-empty">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/></svg>
            <div>点击选择 ZIP 文件</div>
          </div>
          <div v-else class="pick-file">
            <div><strong>{{ file.name }}</strong></div>
            <div style="color: var(--muted); font-size: 12px;">{{ (file.size / 1024).toFixed(1) }} KB</div>
          </div>
        </div>

        <div class="import-actions">
          <button class="btn" :disabled="!file || uploading || polling" @click="startImport">
            {{ uploading ? '上传中…' : (polling ? '导入中…' : '开始导入') }}
          </button>
        </div>

        <!-- 当前任务进度 -->
        <div v-if="currentRecord" class="import-progress">
          <!-- 2026-07-15 OPT-001：导入进草稿箱后，提供「前往草稿箱」入口 -->
          <div v-if="justImportedDraft" class="draft-link">
            <NuxtLink to="/admin/posts?status=0">前往草稿箱发布 →</NuxtLink>
          </div>
          <div class="row">
            <span>状态</span>
            <span :style="{ color: statusColor(currentRecord.status) }">{{ statusLabel(currentRecord.status) }}</span>
          </div>
          <div class="row">
            <span>总数 / 成功 / 失败</span>
            <span>{{ currentRecord.totalCount || 0 }} / {{ currentRecord.successCount || 0 }} / {{ currentRecord.failCount || 0 }}</span>
          </div>
          <div v-if="currentRecord.errorMessage" class="error-box">
            <div style="font-weight: 600; margin-bottom: 4px;">详情</div>
            <pre>{{ currentRecord.errorMessage }}</pre>
          </div>
        </div>

        <!-- 历史 -->
        <div class="import-history">
          <div class="history-head">最近导入</div>
          <div v-if="!history.length" class="history-empty">还没有导入记录</div>
          <div v-else class="history-list">
            <div v-for="r in history" :key="r.id" class="history-row">
              <div class="history-name">
                <span style="font-weight: 500;">{{ r.fileName }}</span>
                <span style="font-size: 11px; color: var(--muted);">{{ formatDate(r.startedAt) }}</span>
              </div>
              <div class="history-meta">
                <span :style="{ color: statusColor(r.status) }">{{ statusLabel(r.status) }}</span>
                <span>{{ r.successCount || 0 }}/{{ r.totalCount || 0 }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.import-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.6);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
}
.import-dialog {
  background: var(--bg, #fff);
  color: var(--text);
  border-radius: 8px;
  width: min(560px, 92vw);
  max-height: 86vh;
  display: flex; flex-direction: column;
  box-shadow: 0 10px 30px rgba(0,0,0,0.25);
}
.import-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border, #eee);
}
.import-head h3 { margin: 0; font-size: 16px; }
.close-btn {
  background: transparent; border: none; cursor: pointer;
  font-size: 22px; color: var(--text-2, #888);
}
.import-body {
  padding: 14px 18px;
  overflow-y: auto;
  display: flex; flex-direction: column; gap: 14px;
}
.import-tip {
  margin: 0; font-size: 12px; color: var(--text-2, #666);
  line-height: 1.5;
}
.import-tip code {
  background: var(--bg-2, #f3f4f6); padding: 1px 5px; border-radius: 3px;
}
.import-tip a { color: var(--primary); margin-left: 4px; }
.import-pick {
  border: 1px dashed var(--border, #ddd);
  border-radius: 8px;
  padding: 24px;
  cursor: pointer;
  text-align: center;
  transition: border-color 0.15s ease;
}
.import-pick:hover { border-color: var(--primary); }
.pick-empty {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  color: var(--muted);
}
.pick-file { display: flex; flex-direction: column; gap: 4px; }
.import-actions { display: flex; justify-content: flex-end; }
.btn {
  background: var(--primary); color: white;
  border: none; padding: 8px 16px; border-radius: 6px;
  cursor: pointer; font-size: 13px;
}
.btn:disabled { opacity: 0.6; cursor: not-allowed; }

.import-progress {
  background: var(--bg-2, #f9fafb);
  border-radius: 6px; padding: 10px 12px;
  font-size: 12px;
  display: flex; flex-direction: column; gap: 6px;
}
.import-progress .row {
  display: flex; justify-content: space-between;
}
.draft-link {
  margin-top: 8px;
  font-size: 12px;
}
.draft-link a {
  color: var(--primary);
  font-weight: 500;
  text-decoration: none;
}
.draft-link a:hover { text-decoration: underline; }
.error-box {
  background: var(--danger-soft, #fef2f2);
  border-radius: 4px; padding: 6px 8px;
  margin-top: 4px;
}
.error-box pre {
  margin: 0; font-size: 11px; max-height: 120px; overflow-y: auto;
  white-space: pre-wrap; word-break: break-all;
}

.import-history {
  border-top: 1px solid var(--border, #eee);
  padding-top: 10px;
}
.history-head {
  font-size: 12px; color: var(--text-2); margin-bottom: 6px;
}
.history-empty {
  font-size: 12px; color: var(--muted); padding: 10px 0;
}
.history-list {
  max-height: 180px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 4px;
}
.history-row {
  display: flex; justify-content: space-between;
  padding: 6px 8px; border-radius: 4px;
  font-size: 12px;
  background: var(--bg-2, #f9fafb);
}
.history-name { display: flex; flex-direction: column; gap: 2px; }
.history-meta {
  display: flex; gap: 10px;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 768px) {
  .import-dialog { width: 96vw; max-height: 92vh; }
  .import-pick { padding: 16px; }
}
</style>
