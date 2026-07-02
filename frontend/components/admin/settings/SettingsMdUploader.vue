<script setup lang="ts">
/**
 * 上传 md 文档组件（2026-07-01 DEV-006）
 *
 * 用法：
 *   <AdminSettingsMdUploader /> 放在高级 tab 页底（advanced.vue 集成）
 *
 * 行为：
 *   - 「下载模版」走 nginx 直接 serve /templates/site-settings-template.md（不走后端）
 *   - 「选择文件」仅接受 .md
 *   - 「上传并应用」→ POST /admin/settings/upload-md（multipart）
 *   - 成功：toast「已应用 N 个段: ...」+ 发 settings-updated 事件 + 跳到 /admin/settings/blog
 *   - 失败：toast「解析失败: {message}」原文展示
 *
 * 隐藏功能：连续点击「博客信息导入」标题 5 次弹出 SQL 执行窗口
 */
const { upload, post } = useAdminApi()
const $toast = useToast()
const router = useRouter()
const bus = useSettingsEventBus()

const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)

// 隐藏功能：连续点击 5 次打开 SQL 执行窗口
const clickCount = ref(0)
const showSqlModal = ref(false)
const sqlInput = ref('')
const sqlResult = ref<any>(null)
const sqlExecuting = ref(false)
let clickTimer: ReturnType<typeof setTimeout> | null = null

const handleLabelClick = () => {
  clickCount.value++
  if (clickTimer) clearTimeout(clickTimer)
  clickTimer = setTimeout(() => { clickCount.value = 0 }, 3000)
  if (clickCount.value >= 5) {
    clickCount.value = 0
    showSqlModal.value = true
  }
}

const executeSql = async () => {
  if (!sqlInput.value.trim()) {
    $toast.warning('请输入 SQL')
    return
  }
  sqlExecuting.value = true
  sqlResult.value = null
  try {
    const res = await post<any>('/admin/settings/exec-sql', { sql: sqlInput.value.trim() })
    sqlResult.value = res.data
    $toast.success('执行成功')
  } catch (e: any) {
    sqlResult.value = { error: e?.data?.message || e?.message || '执行失败' }
    $toast.error('执行失败: ' + (e?.data?.message || e?.message || '未知错误'))
  } finally {
    sqlExecuting.value = false
  }
}

const MAX_SIZE = 2 * 1024 * 1024 // 2MB

const handleFileChange = (e: Event) => {
  const target = e.target as HTMLInputElement
  const f = target.files?.[0]
  if (!f) {
    file.value = null
    return
  }
  if (!f.name.toLowerCase().endsWith('.md')) {
    $toast.error('仅允许 .md 格式')
    target.value = ''
    file.value = null
    return
  }
  if (f.size > MAX_SIZE) {
    $toast.error('文件超过 2MB 上限')
    target.value = ''
    file.value = null
    return
  }
  file.value = f
}

const handleUpload = async () => {
  if (!file.value) {
    $toast.warning('请先选择文件')
    return
  }
  uploading.value = true
  progress.value = 10
  try {
    const res = await upload<any>('/admin/settings/upload-md', file.value)
    progress.value = 100
    const applied = (res.data?.appliedSections || []) as string[]
    $toast.success(`已应用 ${applied.length} 个段: ${applied.join(', ')}`)
    bus.publish('settings-updated', { sections: applied })
    router.push('/admin/settings/blog')
  } catch (e: any) {
    progress.value = 0
    $toast.error('解析失败: ' + (e?.data?.message || e?.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

const handleDownloadTemplate = () => {
  const a = document.createElement('a')
  a.href = '/templates/site-settings-template.md'
  a.download = 'site-settings-template.md'
  a.click()
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}
</script>

<template>
  <div class="card" style="padding: 24px; margin-top: 16px;">
    <div style="margin-bottom: 16px;">
      <div class="form-label" style="margin: 0 0 4px; cursor: default; user-select: none; padding: 4px 0; display: inline-block;" @click="handleLabelClick">博客信息导入</div>
      <div style="font-size: 12px; color: var(--muted);">
        下载模版 → 修改以下数据段 → 上传回应用，整体原子提交。
      </div>
      <div style="font-size: 12px; color: var(--muted); margin-top: 6px; line-height: 1.8;">
        <strong>支持维护的数据：</strong><br/>
        • <strong>个人资料</strong>：昵称、邮箱、头像、个人标语、我的介绍、引用语、底部欢迎语、所在地<br/>
        • <strong>站点信息</strong>：站点标题、副标题、描述、版权、Logo<br/>
        • <strong>技术栈</strong>：技能分组 + 技能列表<br/>
        • <strong>个人经历</strong>：时间 + 职位 + 描述
      </div>
    </div>

    <div style="display: flex; flex-wrap: wrap; gap: 12px; align-items: center;">
      <button type="button" class="btn" @click="handleDownloadTemplate">
        下载模版
      </button>

      <label class="btn" style="cursor: pointer; display: inline-flex; align-items: center; gap: 4px;">
        <input
          type="file"
          accept=".md"
          style="display: none;"
          @change="handleFileChange"
        />
        选择文件
      </label>

      <span v-if="file" style="font-size: 13px; color: var(--muted); flex: 1; min-width: 200px;">
        {{ file.name }}（{{ formatSize(file.size) }}）
      </span>
      <span v-else style="font-size: 13px; color: var(--muted); flex: 1; min-width: 200px;">
        未选择文件
      </span>

      <button
        type="button"
        class="btn btn-primary"
        @click="handleUpload"
        :disabled="!file || uploading"
      >
        {{ uploading ? '上传中…' : '上传并应用' }}
      </button>
    </div>

    <!-- 进度条 -->
    <div v-if="uploading" style="margin-top: 12px; height: 4px; background: var(--bg-soft); border-radius: 2px; overflow: hidden;">
      <div :style="{
        height: '100%',
        background: 'var(--primary)',
        width: progress + '%',
        transition: 'width 0.3s'
      }"></div>
    </div>
  </div>

  <!-- 隐藏 SQL 执行窗口 -->
  <Teleport to="body">
    <div v-if="showSqlModal" style="position: fixed; inset: 0; z-index: 9999; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.5);" @click.self="showSqlModal = false">
      <div style="background: var(--bg); border: 1px solid var(--color-line); border-radius: 12px; padding: 24px; width: 600px; max-width: 90vw; max-height: 80vh; overflow-y: auto; box-shadow: 0 20px 60px rgba(0,0,0,0.3);">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
          <h3 style="margin: 0; font-size: 16px;">SQL 执行</h3>
          <button @click="showSqlModal = false" style="background: none; border: none; cursor: pointer; font-size: 18px; color: var(--muted);">&times;</button>
        </div>
        <div style="font-size: 12px; color: var(--muted); margin-bottom: 12px;">
          仅支持 SELECT / INSERT / UPDATE / DELETE，禁止 DROP / ALTER / CREATE
        </div>
        <textarea
          v-model="sqlInput"
          placeholder="输入 SQL 语句..."
          style="width: 100%; min-height: 120px; padding: 12px; border: 1px solid var(--color-line); border-radius: 8px; font-family: 'JetBrains Mono', monospace; font-size: 13px; resize: vertical; background: var(--bg-soft); color: var(--text);"
        ></textarea>
        <div style="display: flex; gap: 8px; margin-top: 12px; justify-content: flex-end;">
          <button class="btn" @click="showSqlModal = false">取消</button>
          <button class="btn btn-primary" @click="executeSql" :disabled="sqlExecuting">
            {{ sqlExecuting ? '执行中…' : '执行' }}
          </button>
        </div>
        <div v-if="sqlResult" style="margin-top: 16px; border: 1px solid var(--color-line); border-radius: 8px; font-size: 12px; font-family: 'JetBrains Mono', monospace; background: var(--bg-soft);">
          <div v-if="sqlResult.error" style="padding: 12px; color: var(--danger);">{{ sqlResult.error }}</div>
          <div v-else-if="sqlResult.rows">
            <div style="padding: 12px 12px 8px; color: var(--muted);">返回 {{ sqlResult.count }} 行</div>
            <div style="max-height: 300px; overflow-y: auto;">
              <table style="width: 100%; border-collapse: collapse;">
                <thead style="position: sticky; top: 0; z-index: 1;">
                  <tr style="background: var(--bg-soft);">
                    <th v-for="(_, key) in sqlResult.rows[0] || {}" :key="key" style="text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-line); font-weight: 600; white-space: nowrap;">{{ key }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, i) in sqlResult.rows" :key="i">
                    <td v-for="(val, key) in row" :key="key" style="padding: 6px 8px; border-bottom: 1px solid var(--color-line); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ val }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div v-else-if="sqlResult.affected != null" style="color: var(--success);">
            影响 {{ sqlResult.affected }} 行
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
