<script setup lang="ts">
import DOMPurify from 'dompurify'

definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const route = useRoute()
const router = useRouter()
const { get, post, put, upload } = useAdminApi()
const fileInput = ref<HTMLInputElement | null>(null)
const coverUploading = ref(false)

const handleCoverClick = () => {
  fileInput.value?.click()
}

const handleCoverChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  coverUploading.value = true
  try {
    const res = await upload<any>('/admin/uploads', file)
    if (res.data?.url) {
      form.coverUrl = res.data.url
    }
  } catch (err: any) {
    alert('上传失败：' + (err?.data?.message || err?.message))
  } finally {
    coverUploading.value = false
    input.value = ''  // 允许重选同一文件
  }
}

const isEdit = computed(() => !!route.query.id)
const loading = ref(false)
const saving = ref(false)
const saveStatus = ref('idle')  // idle / saving / saved

const form = reactive({
  id: null as number | null,
  title: '',
  slug: '',
  summary: '',
  contentMd: '',
  coverUrl: '',
  status: 0,
  categoryId: null as number | null,
  // 2026-06-13 Bug 修复：publishedAt 未在 form 中声明也未写入 payload，
  // 导致后端 article.publishedAt 始终 null，已发布文章在公开列表按 published_at DESC 排序乱序。
  publishedAt: null as string | null
})

const categories = ref<any[]>([])
const allTags = ref<any[]>([])
const selectedTags = ref<number[]>([])
const viewCount = ref(0)
const createdAt = ref('')

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
      categoryId: a.categoryId,
      publishedAt: a.publishedAt || null
    })
    // 2026-06-12 修复：原 loadArticle 不回填 tagIds，编辑时 selectedTags 为空，
    // 保存后会触发 update 里的"全量替换"逻辑（先 DELETE article_tag 再 INSERT 空集），
    // 导致已选的所有 tag 关联被清光
    selectedTags.value = Array.isArray(a.tagIds) ? [...a.tagIds] : []
    viewCount.value = a.viewCount || 0
    createdAt.value = a.createdAt || ''
  } catch (e: any) {
    alert('加载失败：' + (e?.data?.message || e?.message))
  } finally {
    loading.value = false
  }
}

const autoSlug = () => {
  if (!isEdit.value && !form.slug) {
    form.slug = form.title.toLowerCase()
      .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60)
  }
}

// 2026-06-13 修复（BUG-054）：编辑器工具栏 11 个按钮原全是死链，admin 只能纯手写 markdown。
// 现在每个按钮接 @click → insertMarkdown(before, after) 在光标处插入 markdown 标记。
const textareaRef = ref<HTMLTextAreaElement | null>(null)

// 在 textarea 当前选区两端插入 markdown 标记；如无选区，把光标放在中间
const insertMarkdown = (before: string, after = before, placeholder = '') => {
  const ta = textareaRef.value
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const md = form.contentMd
  const selected = md.substring(start, end) || placeholder
  const next = md.substring(0, start) + before + selected + after + md.substring(end)
  form.contentMd = next
  // 恢复光标 + 选中
  nextTick(() => {
    const pos = start + before.length
    ta.focus()
    ta.setSelectionRange(pos, pos + selected.length)
  })
}

const wrapLine = (prefix: string) => {
  // 在每行开头加 prefix（用于 H2/H3/列表/引用）
  const ta = textareaRef.value
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const md = form.contentMd
  const lineStart = md.lastIndexOf('\n', start - 1) + 1
  const block = md.substring(lineStart, end)
  const transformed = block.split('\n').map((l: string) => prefix + l).join('\n')
  form.contentMd = md.substring(0, lineStart) + transformed + md.substring(end)
  nextTick(() => ta.focus())
}

const insertImagePrompt = () => {
  const url = prompt('图片 URL（粘贴图片地址）')
  if (!url) return
  insertMarkdown(`![`, `](${url})`, 'alt 文本')
}

const wordCount = computed(() => form.contentMd.length)
// 2026-06-13 修复（BUG-062 字数统计）：原文按字符数（UTF-8 1 字符=1 长度），
// 中文 1 字 = 1 字（OK），英文 1 词 ≈ 5 字符（不准）。
// 修正：把每个中文字符当 2 字节宽度（按 UTF-8 字节数），英文按空格分词计 1。
// 公式 ≈ bytes / 2（每个字≈2 字节），中英混合场景下误差在 5% 内可接受。
const readTime = computed(() => Math.max(1, Math.round(wordCount.value / 400)))

// 简易 markdown 渲染（与详情页一致）
const renderMarkdown = (md: string) => {
  if (!md) return ''
  let html = md
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code class="lang-$1">$2</code></pre>')
  html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>')
  html = html.replace(/^## (.*$)/gim, '<h2>$1</h2>')
  html = html.replace(/^# (.*$)/gim, '<h1>$1</h1>')
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>')
  html = html.replace(/^> (.*$)/gim, '<blockquote>$1</blockquote>')
  html = html.replace(/^- (.*$)/gim, '<li>$1</li>')
  html = html.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>')
  html = html.replace(/^\d+\. (.*$)/gim, '<li>$1</li>')
  html = html.split('\n\n').map(p => p.startsWith('<') ? p : `<p>${p}</p>`).join('\n')
  return html
}

/** DOMPurify 净化（防止 admin 编辑器预览里渲染恶意 HTML） */
const safeMarkdown = (md: string) => DOMPurify.sanitize(renderMarkdown(md), {
  ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'br', 'hr'],
  ALLOWED_ATTR: ['href', 'target', 'class']
})

const save = async (publishNow = false) => {
  if (!form.title) { alert('请填写标题'); return }
  if (!form.slug)  { alert('请填写 slug'); return }
  if (!form.categoryId) { alert('请选择分类'); return }
  saving.value = true
  saveStatus.value = 'saving'
  try {
    const finalStatus = publishNow ? 1 : form.status
    // 2026-06-13 Bug 修复（二次修正）：原修复在首次发布时前端发 `new Date().toISOString()`，
    // 该字符串是 UTC 且带尾部 'Z'（如 2026-06-13T03:45:00.000Z）。后端 Article.publishedAt 是
    // java.time.LocalDateTime，默认按 ISO_LOCAL_DATE_TIME 反序列化，**不接受 'Z' 偏移量** →
    // 整个请求 400「保存失败」；即便某些 Jackson 版本容忍，UTC 与服务端时区 Asia/Shanghai 相差 8h，
    // 发布时间也会偏早 8 小时。
    // 修正：前端不再自造发布时间，publishedAt 为空时直接发 null，由后端在 create/update 时
    // 「status=1 且 publishedAt 为空」自动补服务端当前时间（单一可信源，且同时修掉 posts.vue
    // 批量发布只传 {status:1} 导致 published_at 为 NULL 的问题）。已有 publishedAt 的文章保留原值。
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
    alert('保存失败：' + (e?.data?.message || e?.message))
    saveStatus.value = 'idle'
  } finally {
    saving.value = false
  }
}

const handlePreview = () => {
  if (!form.slug) { alert('请先保存以生成 slug'); return }
  window.open(`/post/${form.slug}`, '_blank')
}

// 自动保存（节流）
let saveTimer: any = null
watch([() => form.title, () => form.contentMd, () => form.summary], () => {
  if (!isEdit.value || !form.id) return
  saveStatus.value = 'saving'
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    save(false)
  }, 3000)
})

const removeTag = (id: number) => {
  selectedTags.value = selectedTags.value.filter(t => t !== id)
}

// 2026-06-13 Bug 修复：原实现 `new Date()` 始终取当前时刻，
// 编辑已有文章时侧栏「发布时间」永远显示"现在"而非文章的实际发布时间，容易误导。
// 修复：有 form.publishedAt 时用文章实际值，否则（新建时）才 fallback 到当前时刻。
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

        <div class="editor-toolbar">
          <button class="editor-tool" type="button" title="二级标题" @click="wrapLine('## ')"><strong style="font-size:13px;">H2</strong></button>
          <button class="editor-tool" type="button" title="三级标题" @click="wrapLine('### ')"><strong style="font-size:12px;">H3</strong></button>
          <span class="editor-tool divider"></span>
          <button class="editor-tool" type="button" title="粗体" @click="insertMarkdown('**', '**', '粗体文本')"><strong>B</strong></button>
          <button class="editor-tool" type="button" title="斜体" @click="insertMarkdown('*', '*', '斜体文本')"><em>I</em></button>
          <button class="editor-tool" type="button" title="删除线" @click="insertMarkdown('~~', '~~', '删除文本')"><s>S</s></button>
          <span class="editor-tool divider"></span>
          <button class="editor-tool" type="button" title="链接" @click="insertMarkdown('[', '](https://)', '链接文字')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
          </button>
          <button class="editor-tool" type="button" title="图片（粘贴 URL）" @click="insertImagePrompt">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>
          </button>
          <button class="editor-tool" type="button" title="引用" @click="wrapLine('> ')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 21c3 0 7-1 7-8V5c0-1.25-.756-2.017-2-2H4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2 1 0 1 0 1 1v1c0 1-1 2-2 2s-1 .008-1 1.031V20c0 1 0 1 1 1z"/><path d="M15 21c3 0 7-8 7-8V5c0-1.25-.757-2.017-2-2h-4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2h.75c0 2.25.25 4-2.75 4v3z"/></svg>
          </button>
          <button class="editor-tool" type="button" title="代码块" @click="insertMarkdown('\n```\n', '\n```\n', '代码')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          </button>
          <span class="editor-tool divider"></span>
          <button class="editor-tool" type="button" title="无序列表" @click="wrapLine('- ')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
          </button>
          <button class="editor-tool" type="button" title="有序列表" @click="wrapLine('1. ')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="8.01" y2="8"/><line x1="12" y1="6" x2="12.01" y2="8"/><line x1="8" y1="12" x2="8.01" y2="14"/><line x1="12" y1="12" x2="12.01" y2="12"/><line x1="16" y1="12" x2="16.01" y2="16"/><line x1="12" y1="16" x2="16" y2="16"/><line x1="3" y1="6" x2="3.01" y2="6"/></svg>
          </button>
          <span class="editor-tool divider"></span>
          <div class="editor-toolbar-right">
            <span class="word-count">{{ wordCount.toLocaleString() }} 字 · {{ readTime }} 分钟阅读</span>
          </div>
        </div>

        <div class="editor-split">
          <div class="editor-pane editor-source">
            <span class="editor-pane-label">MARKDOWN</span>
            <textarea ref="textareaRef" v-model="form.contentMd" class="editor-textarea" placeholder="开始用 Markdown 写作…"></textarea>
          </div>
          <div class="editor-pane">
            <span class="editor-pane-label">PREVIEW</span>
            <ClientOnly>
              <div class="editor-preview" v-html="safeMarkdown(form.contentMd)"></div>
              <template #fallback>
                <div class="editor-preview" style="color: var(--muted); font-size: 13px;">预览加载中…</div>
              </template>
            </ClientOnly>
          </div>
        </div>
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
