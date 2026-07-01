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
 */
const { upload } = useAdminApi()
const $toast = useToast()
const router = useRouter()
const bus = useSettingsEventBus()

const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)

const MAX_SIZE = 2 * 1024 * 1024 // 2MB

const handleFileChange = (e: Event) => {
  const target = e.target as HTMLInputElement
  const f = target.files?.[0]
  if (!f) {
    file.value = null
    return
  }
  // 客户端预校验：仅 .md + ≤2MB（与后端一致，避免无效请求）
  if (!f.name.toLowerCase().endsWith('.md')) {
    $toast.error('仅允许 .md 格式')
    target.value = ''  // 清空 input
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
    // 走 useAdminApi.upload() 复用统一鉴权头 + 业务错处理
    const res = await upload<any>('/admin/settings/upload-md', file.value)
    progress.value = 100
    const applied = (res.data?.appliedSections || []) as string[]
    $toast.success(`已应用 ${applied.length} 个段: ${applied.join(', ')}`)
    // 发事件让其他 settings 子页 reload
    bus.publish('settings-updated', { sections: applied })
    // 跳到 blog tab（典型阅读入口）
    router.push('/admin/settings/blog')
  } catch (e: any) {
    progress.value = 0
    $toast.error('解析失败: ' + (e?.data?.message || e?.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

const handleDownloadTemplate = () => {
  // 前端静态文件，由 nginx 直接 serve（不走后端）
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
      <div class="form-label" style="margin: 0 0 4px;">上传 md 文档</div>
      <div style="font-size: 12px; color: var(--muted);">
        下载模版 → 修改 4 段（profile / blog / techstack / experience）→ 上传回应用。
        整体原子提交，任一段校验失败全部回滚。
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
</template>
