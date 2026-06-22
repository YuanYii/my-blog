<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const route = useRoute()
const router = useRouter()
const { get, post, put, upload } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()
const fileInput = ref<HTMLInputElement | null>(null)
const coverUploading = ref(false)

const handleCoverClick = () => fileInput.value?.click()

const handleCoverChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  coverUploading.value = true
  try {
    const res = await upload<any>('/admin/uploads', file)
    if (res.data?.url) form.coverUrl = res.data.url
  } catch (err: any) {
    $toast.error('上传失败：' + (err?.data?.message || err?.message))
  } finally {
    coverUploading.value = false
    input.value = ''
  }
}

const isEdit = computed(() => !!route.query.id)
const loading = ref(false)
const saving = ref(false)
const saveStatus = ref('idle')

const form = reactive({
  id: null as number | null,
  title: '',
  slug: '',
  summary: '',
  contentMd: '',
  coverUrl: '',
  status: 0,
  categoryId: null as number | null,
  publishedAt: null as string | null
})

const categories = ref<any[]>([])
const allTags = ref<any[]>([])
const selectedTags = ref<number[]>([])
const viewCount = ref(0)
const createdAt = ref('')
// 2026-06-22 修复（BUG-XXX 自动保存误触发）：加载已有文章时刻；
// watch 内 < 2000ms 内的触发全部忽略（来自 Object.assign 的字段级变更，不是用户输入）。
const loadedAt = ref<number>(0)

const loadMeta = async () => {
  try {
    const [c, t] = await Promise.all([
      get<any>('/articles/categories/all'),
      get<any>('/articles/tags')
    ])
    categories.value = c.data || []
    allTags.value = t.data || []
  } catch { /* ignore */ }
}

const loadArticle = async () => {
  if (!isEdit.value) return
  loading.value = true
  try {
    const res = await get<any>(`/articles/id/${route.query.id}`)
    const a = res.data
    Object.assign(form, {
      id: a.id, title: a.title, slug: a.slug,
      summary: a.summary || '', contentMd: a.contentMd || '',
      coverUrl: a.coverUrl || '', status: a.status,
      categoryId: a.categoryId, publishedAt: a.publishedAt || null
    })
    selectedTags.value = Array.isArray(a.tagIds) ? [...a.tagIds] : []
    viewCount.value = a.viewCount || 0
    createdAt.value = a.createdAt || ''
    // 2026-06-22 修复（BUG-XXX 自动保存误触发）：
    // 上面的 Object.assign 会逐字段触发 watch，3 秒后自动 save(false) 把原文存一遍——
    // 用户没编辑就写了一次库。
    // 解决：标记加载完成时刻；watch 内 < 2000ms 一律跳过（人工编辑最慢也得几百毫秒）。
    loadedAt.value = Date.now()
  } catch (e: any) {
    $toast.error('加载失败：' + (e?.data?.message || e?.message))
  } finally {
    loading.value = false
  }
}

const autoSlug = () => {
  if (!isEdit.value && !form.slug) {
    form.slug = form.title.toLowerCase()
      .replace(/[^a-z0-9一-龥]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60)
  }
}

const save = async (publishNow = false) => {
  if (!form.title) { $toast.warning('请填写标题'); return }
  if (!form.slug)  { $toast.warning('请填写 slug'); return }
  if (!form.categoryId) { $toast.warning('请选择分类'); return }
  saving.value = true
  saveStatus.value = 'saving'
  try {
    const finalStatus = publishNow ? 1 : form.status
    const payload = { ...form, status: finalStatus, tagIds: selectedTags.value, publishedAt: form.publishedAt }
    if (isEdit.value) {
      await put(`/articles/${form.id}`, payload)
    } else {
      const res = await post<any>('/articles', payload)
      form.id = res.data.id
      router.replace(`/admin/edit?id=${form.id}`)
    }
    saveStatus.value = 'saved'
    setTimeout(() => saveStatus.value = 'idle', 2000)
  } catch (e: any) {
    $toast.error('保存失败：' + (e?.data?.message || e?.message))
    saveStatus.value = 'idle'
  } finally {
    saving.value = false
  }
}

const handlePreview = () => {
  if (!form.slug) { $toast.warning('请先保存以生成 slug'); return }
  window.open(`/post/${form.slug}`, '_blank')
}

let saveTimer: any = null
watch([() => form.title, () => form.contentMd, () => form.summary], () => {
  if (!isEdit.value || !form.id) return
  // 2026-06-22 修复（BUG-XXX 自动保存误触发）：加载完成后 2 秒内的变更视为
  // Object.assign 字段级联动，不是用户输入；用户手动改一次最慢也要几百 ms。
  if (loadedAt.value && Date.now() - loadedAt.value < 2000) return
  saveStatus.value = 'saving'
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => save(false), 3000)
})

const removeTag = (id: number) => {
  selectedTags.value = selectedTags.value.filter(t => t !== id)
}

const publishedAt = computed(() => {
  const d = form.publishedAt ? new Date(form.publishedAt) : new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})

onMounted(async () => {
  await loadMeta()
  await loadArticle()
})
</script>

<template>
  <div class="editor-page">
    <!-- Topbar -->
    <div class="editor-topbar">
      <h1>{{ isEdit ? '编辑文章' : '新建文章' }}</h1>
      <div class="editor-topbar-actions">
        <span class="save-status" :class="{ saving: saveStatus === 'saving' }">
          <span class="dot"></span>
          <span v-if="saveStatus === 'saving'">正在保存…</span>
          <span v-else-if="saveStatus === 'saved'">已自动保存 · 刚刚</span>
          <span v-else-if="isEdit">已加载</span>
          <span v-else>未保存</span>
        </span>
        <button v-if="form.id" @click="handlePreview" class="btn btn-ghost btn-sm">预览</button>
        <button @click="save(false)" :disabled="saving" class="btn btn-ghost btn-sm">保存草稿</button>
        <button @click="save(true)" :disabled="saving" class="btn btn-primary btn-sm">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7"/></svg>
          {{ form.status === 1 ? '更新' : '发布' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <div v-else class="editor-grid">
      <!-- Left: Editor -->
      <div class="editor-main">
        <input v-model="form.title" @input="autoSlug" type="text" class="title-input" placeholder="文章标题…" />

        <div class="slug-row">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
          <span class="slug-prefix">yuanyi.com/p/</span>
          <input v-model="form.slug" type="text" class="slug-input" />
          <span class="slug-edit-btn">编辑</span>
        </div>

        <!-- MarkdownEditor 组件：工具栏 + textarea + 预览 -->
        <AdminMarkdownEditor v-model="form.contentMd" :upload="upload" />
      </div>

      <!-- Right: Sidebar -->
      <aside>
        <div class="sidebar-card">
          <div class="sidebar-card-title">状态</div>
          <div class="status-btn-group">
            <button class="status-btn" :class="{ active: form.status === 1 }" @click="form.status = 1">
              <svg v-if="form.status === 1" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>
              已发布
            </button>
            <button class="status-btn" :class="{ active: form.status === 0 }" @click="form.status = 0">草稿</button>
            <button class="status-btn" :class="{ active: form.status === 2 }" @click="form.status = 2">归档</button>
          </div>
          <div class="form-group" style="margin: 0;">
            <label class="form-label" style="font-size: 11px;">发布时间</label>
            <input type="text" class="form-control" :value="publishedAt" readonly style="font-size: 12px; padding: 6px 10px;" />
          </div>
        </div>

        <div class="sidebar-card">
          <div class="sidebar-card-title">分类</div>
          <select v-model="form.categoryId" class="form-control" style="font-size: 13px; padding: 7px 10px;">
            <option :value="null">未分类</option>
            <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>

        <div class="sidebar-card">
          <div class="sidebar-card-title">标签</div>
          <div class="tag-input-wrap">
            <span v-for="tid in selectedTags" :key="tid" class="tag-chip">
              {{ allTags.find(t => t.id === tid)?.name }}
              <button @click="removeTag(tid)">×</button>
            </span>
            <select v-model="selectedTags" multiple class="tag-input" style="flex: 1; min-width: 100%;">
              <option v-for="t in allTags" :key="t.id" :value="t.id">{{ t.name }}</option>
            </select>
          </div>
          <div class="form-meta" style="margin-top: 6px;">
            <span>{{ selectedTags.length }} / 10 个</span>
          </div>
        </div>

        <div class="sidebar-card">
          <div class="sidebar-card-title">封面图</div>
          <input ref="fileInput" type="file" accept="image/*" style="display: none;" @change="handleCoverChange" />
          <div v-if="form.coverUrl" class="cover-preview" style="position: relative; margin-bottom: 8px;">
            <img :src="form.coverUrl" alt="cover" style="width: 100%; height: 100px; object-fit: cover; border-radius: 6px;" />
            <button @click="form.coverUrl = ''" type="button" style="position: absolute; top: 4px; right: 4px; background: rgba(0,0,0,0.6); color: white; border: none; border-radius: 4px; padding: 2px 8px; font-size: 12px; cursor: pointer;">✕</button>
          </div>
          <div class="cover-upload" @click="handleCoverClick" :style="{ opacity: coverUploading ? 0.6 : 1, cursor: 'pointer' }">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>
            <span>{{ coverUploading ? '上传中…' : (form.coverUrl ? '更换封面' : '点击上传封面图') }}</span>
            <span style="font-size: 10px;">建议 16:9 · 最大 5MB</span>
          </div>
          <input v-model="form.coverUrl" class="form-control" placeholder="或粘贴图片 URL" style="margin-top: 8px; font-size: 12px;" />
        </div>

        <div class="sidebar-card">
          <div class="sidebar-card-title">摘要</div>
          <textarea v-model="form.summary" class="form-control" rows="3" style="font-size: 12px; line-height: 1.5; resize: vertical;"></textarea>
          <div class="form-meta" style="margin-top: 6px;">
            <span>{{ form.summary.length }} / 150 字</span>
            <span :style="{ color: form.summary.length > 150 ? 'var(--danger)' : 'var(--success)' }">
              {{ form.summary.length > 150 ? '⚠ 超出' : '✓ 长度合适' }}
            </span>
          </div>
        </div>

        <div v-if="isEdit" class="sidebar-card">
          <div class="sidebar-card-title">数据</div>
          <div class="meta-row">
            <span class="meta-label">阅读数</span>
            <span style="font-family: 'JetBrains Mono', monospace;">{{ viewCount.toLocaleString() }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">创建时间</span>
            <span style="font-size: 11px;">{{ createdAt }}</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>
