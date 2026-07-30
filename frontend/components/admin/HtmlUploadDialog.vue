<script setup lang="ts">
/**
 * HTML 文件上传对话框
 *
 * 功能：
 *  1. 用户选择 .html 文件（≤2MB）
 *  2. 可选输入标题（默认取文件名）
 *  3. 上传后创建文章（slug: html + MD5前16位）
 *  4. 成功后刷新文章列表
 */
const emit = defineEmits<{ (e: 'close', refreshed?: boolean): void }>()

const { upload } = useAdminApi()
const $toast = useToast()

const MAX_SIZE = 2 * 1024 * 1024 // 2MB

const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const title = ref('')
const uploading = ref(false)
const success = ref(false)
const createdSlug = ref('')

const onPick = () => {
  fileInput.value?.click()
}

const onFileChange = (e: Event) => {
  const t = e.target as HTMLInputElement
  const f = t.files?.[0]
  if (!f) return
  if (!f.name.toLowerCase().endsWith('.html') && !f.name.toLowerCase().endsWith('.htm')) {
    $toast.error('仅支持 .html 或 .htm 文件')
    t.value = ''
    return
  }
  if (f.size > MAX_SIZE) {
    $toast.error('文件大小不能超过 2MB')
    t.value = ''
    return
  }
  file.value = f
  // 自动填充标题（去掉 .html 后缀）
  if (!title.value) {
    title.value = f.name.replace(/\.(html?|HTML?)$/, '')
  }
}

const handleUpload = async () => {
  if (!file.value) {
    $toast.warning('请先选择 HTML 文件')
    return
  }
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file.value)
    if (title.value.trim()) {
      formData.append('title', title.value.trim())
    }
    const res = await upload<any>('/articles/admin/import-html', file.value)
    success.value = true
    createdSlug.value = res.data?.slug || ''
    $toast.success('HTML 页面创建成功（已放入草稿箱）')
  } catch (e: any) {
    $toast.error('上传失败：' + (e?.data?.message || e?.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

const close = () => {
  emit('close', success.value)
}
</script>

<template>
  <div class="upload-mask" @click.self="close">
    <div class="upload-dialog">
      <div class="upload-head">
        <h3>上传 HTML 页面</h3>
        <button class="close-btn" @click="close" aria-label="关闭">×</button>
      </div>

      <div class="upload-body">
        <p class="upload-tip">
          上传 HTML 文件，创建为文章（草稿状态）。
          上传后可在文章管理中编辑和发布。
        </p>

        <!-- 成功状态 -->
        <div v-if="success" class="success-box">
          <div class="success-icon">✓</div>
          <div class="success-title">上传成功</div>
          <div class="success-slug">slug: {{ createdSlug }}</div>
          <div class="success-actions">
            <NuxtLink :to="`/post/${createdSlug}`" target="_blank" class="btn btn-outline">
              预览页面
            </NuxtLink>
            <button class="btn btn-primary" @click="close">完成</button>
          </div>
        </div>

        <!-- 上传表单 -->
        <template v-else>
          <div class="upload-pick" @click="onPick">
            <input ref="fileInput" type="file" accept=".html,.htm" style="display:none" @change="onFileChange" />
            <div v-if="!file" class="pick-empty">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/></svg>
              <div>点击选择 HTML 文件</div>
              <div style="font-size: 12px; color: var(--muted);">支持 .html / .htm，最大 2MB</div>
            </div>
            <div v-else class="pick-file">
              <div><strong>{{ file.name }}</strong></div>
              <div style="color: var(--muted); font-size: 12px;">{{ (file.size / 1024).toFixed(1) }} KB</div>
            </div>
          </div>

          <div class="form-group">
            <label>页面标题（可选）</label>
            <input v-model="title" class="form-input" placeholder="默认使用文件名" />
          </div>

          <div class="upload-actions">
            <button class="btn" @click="close">取消</button>
            <button class="btn btn-primary" :disabled="!file || uploading" @click="handleUpload">
              {{ uploading ? '上传中…' : '上传' }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upload-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.6);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
}
.upload-dialog {
  background: var(--bg, #fff);
  color: var(--text);
  border-radius: 8px;
  width: min(480px, 92vw);
  max-height: 86vh;
  display: flex; flex-direction: column;
  box-shadow: 0 10px 30px rgba(0,0,0,0.25);
}
.upload-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border, #eee);
}
.upload-head h3 { margin: 0; font-size: 16px; }
.close-btn {
  background: transparent; border: none; cursor: pointer;
  font-size: 22px; color: var(--text-2, #888);
}
.upload-body {
  padding: 18px;
  display: flex; flex-direction: column; gap: 16px;
}
.upload-tip {
  margin: 0; font-size: 12px; color: var(--text-2, #666);
  line-height: 1.5;
}
.upload-pick {
  border: 1px dashed var(--border, #ddd);
  border-radius: 8px;
  padding: 24px;
  cursor: pointer;
  text-align: center;
  transition: border-color 0.15s ease;
}
.upload-pick:hover { border-color: var(--primary); }
.pick-empty {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  color: var(--muted);
}
.pick-file { display: flex; flex-direction: column; gap: 4px; }
.form-group {
  display: flex; flex-direction: column; gap: 6px;
}
.form-group label {
  font-size: 13px; font-weight: 500; color: var(--text-2);
}
.form-input {
  padding: 8px 12px;
  border: 1px solid var(--border, #ddd);
  border-radius: 6px;
  font-size: 14px;
  background: var(--bg, #fff);
  color: var(--text);
}
.form-input:focus {
  outline: none;
  border-color: var(--primary);
}
.upload-actions {
  display: flex; justify-content: flex-end; gap: 8px;
}
.btn {
  background: var(--bg-2, #f3f4f6);
  color: var(--text);
  border: 1px solid var(--border, #ddd);
  padding: 8px 16px; border-radius: 6px;
  cursor: pointer; font-size: 13px;
}
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-primary {
  background: var(--primary); color: white; border-color: var(--primary);
}
.btn-outline {
  background: transparent; color: var(--primary); border-color: var(--primary);
}
.btn-outline:hover {
  background: var(--primary-soft);
}
.success-box {
  text-align: center; padding: 20px 0;
}
.success-icon {
  width: 48px; height: 48px;
  background: var(--success, #22c55e);
  color: white;
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 24px; font-weight: bold;
  margin-bottom: 12px;
}
.success-title {
  font-size: 16px; font-weight: 600; margin-bottom: 8px;
}
.success-slug {
  font-size: 12px; color: var(--muted); margin-bottom: 16px;
  font-family: monospace;
}
.success-actions {
  display: flex; justify-content: center; gap: 12px;
}
</style>
