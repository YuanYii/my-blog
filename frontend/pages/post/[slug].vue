<script setup lang="ts">
import DOMPurify from 'dompurify'
import { formatDate as formatDateShared, formatDateTime as formatDateTimeShared, renderMarkdown, extractHeadings } from '~/composables/useMarkdownUtils'

definePageMeta({ layout: 'post' })

const route = useRoute()
const { get, post, downloadAttachment } = usePublicApi()
// 2026-07-15 BUG-001：草稿预览——preview=1 时改走管理员鉴权端点（绕过 status=1 过滤）
const { get: adminGet } = useAdminApi()
const $toast = useToast()
const slug = route.params.slug as string
const preview = computed(() => route.query.preview === '1')

// 2026-07-15 BUG-003：用 ref + onMounted 替代 useAsyncData，避免 SPA 路由初始化时 route.query 未就绪
const article = ref<any>(null)
const articleError = ref<any>(null)
const articleStatus = ref('idle')
const refreshArticle = async () => {
  article.value = null
  articleError.value = null
  articleStatus.value = 'pending'
  try {
    const isPreview = route.query.preview === '1' || (import.meta.client && new URLSearchParams(window.location.search).get('preview') === '1')
    const res = isPreview
      ? await adminGet<any>(`/articles/admin/preview/${slug}`)
      : await get<any>(`/articles/${slug}`)
    article.value = res.data || null
    articleStatus.value = 'success'
  } catch (e: any) {
    articleError.value = e
    articleStatus.value = 'error'
  }
}
await refreshArticle()
const { data: metaRes } = await useAsyncData(
  `post-meta-${slug}`,
  async (): Promise<{ categories: any[]; tags: any[] }> => {
    const results = await Promise.all([
      get<any>('/articles/categories'),
      get<any>('/articles/tags')
    ])
    return { categories: results[0]?.data || [], tags: results[1]?.data || [] }
  }
)

const categories = computed(() => metaRes.value?.categories || [])
const tags = computed(() => metaRes.value?.tags || [])
const comments = ref<any[]>([])
const related = ref<any[]>([])
const newComment = reactive({ nickname: '', email: '', content: '' })
const submitting = ref(false)
const submitted = ref(false)
// 2026-07-15 BUG-003：用 useAsyncData 的 status 判断加载态，
// 避免请求失败时 articleRes.value 非 null 导致 loading 既非 true 也非 false → 静默空白。
const loading = computed(() => articleStatus.value === 'pending')
const articleErrorMsg = computed(() => {
  const e = articleError.value as any
  if (!e) return ''
  return e?.data?.message || e?.message || '请检查网络连接或重新登录'
})
const retryPreview = () => { articleError.value = null; refreshArticle() }

const getCategoryName = (id: number) => categories.value.find(c => c.id === id)?.name || ''
const getTagName = (id: number) => tags.value.find(t => t.id === id)?.name || ''

const loadComments = async () => {
  if (!article.value?.id) return
  try {
    const res = await get<any>('/comments', { articleId: article.value.id, status: 1 })
    comments.value = res.data?.records || []
  } catch { /* ignore */ }
}
const loadRelated = async () => {
  if (!article.value?.categoryId) return
  try {
    const res = await get<any>('/articles', { categoryId: article.value.categoryId, size: 4 })
    related.value = (res.data?.records || []).filter((a: any) => a.id !== article.value.id).slice(0, 3)
  } catch { /* ignore */ }
}

const handleSubmitComment = async () => {
  if (!newComment.nickname || !newComment.content) return
  submitting.value = true
  try {
    await post('/comments', {
      articleId: article.value.id,
      nickname: newComment.nickname,
      email: newComment.email,
      content: newComment.content
    })
    submitted.value = true
    newComment.nickname = ''
    newComment.email = ''
    newComment.content = ''
    setTimeout(() => submitted.value = false, 3000)
  } catch { /* ignore */ }
  finally { submitting.value = false }
}

const formatDate = (d: string | number | null | undefined) => formatDateShared(d)
const formatDateTime = (d: string | number | null | undefined) => formatDateTimeShared(d)

// ============ 附件下载 ============
const { formatFileSize } = useFileSize()
const downloading = ref(false)
const downloadLock = ref<number | null>(null)

const handleDownload = async () => {
  if (!article.value?.id || !article.value?.attachment) return
  if (downloading.value) return
  downloading.value = true
  if (downloadLock.value) clearTimeout(downloadLock.value)
  try {
    await downloadAttachment(article.value.id)
  } catch (err: any) {
    $toast.error(err?.message || '下载失败')
  } finally {
    downloadLock.value = window.setTimeout(() => {
      downloading.value = false
      downloadLock.value = null
    }, 30000)
  }
}

// ============ TOC 侧边栏 ============
const tocHeadings = computed(() => {
  if (!article.value?.contentMd) return []
  return extractHeadings(article.value.contentMd)
})
const activeHeading = ref('')
let observer: IntersectionObserver | null = null

const scrollToHeading = (id: string) => {
  const el = document.getElementById(id)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

const ssrSafeHtml = (md: string): string => {
  if (!md) return ''
  // 检测是否是 HTML 内容（以 < 开头）
  const isHtml = md.trimStart().startsWith('<')
  if (isHtml) {
    // HTML 内容：仅过滤危险属性，保留所有标签
    let html = md
    html = html.replace(/(href|src)=(["'])\s*(javascript|data|vbscript):/gi, '$1=$2#')
    html = html.replace(/\s+on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    return html
  }
  // Markdown 内容：转换并过滤
  let html = renderMarkdown(md)
  html = html.replace(/(href|src)=(["'])\s*(javascript|data|vbscript):/gi, '$1=$2#')
  html = html.replace(/<(script|iframe|object|embed|style|link|meta)\b[^>]*>[\s\S]*?<\/\1>/gi, '')
  html = html.replace(/<(script|iframe|object|embed|style|link|meta)\b[^>]*\/?>/gi, '')
  html = html.replace(/\s+on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  return html
}

const safeMarkdown = (md: string): string => {
  if (import.meta.client) {
    // 检测是否是 HTML 内容
    const isHtml = md.trimStart().startsWith('<')
    if (isHtml) {
      // HTML 内容：使用更宽松的 DOMPurify 配置
      return DOMPurify.sanitize(md, {
        ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'br', 'hr', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'mark', 'input', 'div', 'span', 'section', 'article', 'header', 'footer', 'nav', 'aside', 'main', 'figure', 'figcaption', 'video', 'audio', 'source', 'canvas', 'svg', 'style', 'script'],
        ALLOWED_ATTR: ['href', 'target', 'class', 'src', 'alt', 'loading', 'id', 'checked', 'disabled', 'type', 'style', 'width', 'height', 'controls', 'autoplay', 'loop', 'muted', 'poster', 'preload', 'playsinline', 'data-*'],
        ALLOW_DATA_ATTR: true
      })
    }
    return DOMPurify.sanitize(renderMarkdown(md), {
      ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'br', 'hr', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'mark', 'input'],
      ALLOWED_ATTR: ['href', 'target', 'class', 'src', 'alt', 'loading', 'id', 'checked', 'disabled', 'type'],
      ALLOW_DATA_ATTR: false
    })
  }
  return ssrSafeHtml(md)
}

const proseHtml = computed(() => {
  if (!article.value?.contentMd) return ''
  return safeMarkdown(article.value.contentMd)
})

// 检测是否是 HTML 文件路径（以 /uploads/html/ 开头）
const isHtmlFile = computed(() => {
  if (!article.value?.contentMd) return false
  return article.value.contentMd.startsWith('/uploads/html/')
})

// 检测是否是内联 HTML 内容（以 < 开头）
const isHtmlContent = computed(() => {
  if (!article.value?.contentMd) return false
  return article.value.contentMd.trimStart().startsWith('<')
})

// HTML 文件 URL
const htmlFileUrl = computed(() => {
  if (!isHtmlFile.value || !article.value?.contentMd) return ''
  const config = useRuntimeConfig()
  return `${config.public.apiBase.replace('/api/v1', '')}${article.value.contentMd}`
})

// iframe 引用和高度调整
const htmlIframeRef = ref<HTMLIFrameElement | null>(null)

const adjustIframeHeight = () => {
  const iframe = htmlIframeRef.value
  if (!iframe || !iframe.contentDocument) return
  
  try {
    // 获取 iframe 内容的实际高度
    const body = iframe.contentDocument.body
    const html = iframe.contentDocument.documentElement
    const height = Math.max(
      body.scrollHeight,
      body.offsetHeight,
      html.clientHeight,
      html.scrollHeight,
      html.offsetHeight
    )
    
    // 设置 iframe 高度（加一些 padding 避免裁剪）
    iframe.style.height = `${height + 20}px`
  } catch (e) {
    // 跨域时无法访问 contentDocument，设置一个默认高度
    iframe.style.height = '800px'
    console.warn('无法调整 iframe 高度（可能跨域）:', e)
  }
}

onMounted(async () => {
  // 渐进增强：隐藏 SEO 静态内容（后端返回的 HTML 中的 #seo-content）
  const seoContent = document.getElementById('seo-content')
  if (seoContent) {
    seoContent.style.display = 'none'
  }

  if (article.value) {
    await Promise.all([loadComments(), loadRelated()])
  }
  // Scroll spy：IntersectionObserver 监听 h2/h3
  nextTick(() => {
    const prose = document.querySelector('.prose')
    if (!prose || !tocHeadings.value.length) return

    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            activeHeading.value = entry.target.id
          }
        }
      },
      { rootMargin: '-80px 0px -60% 0px', threshold: 0 }
    )
    for (const h of tocHeadings.value) {
      const el = document.getElementById(h.id)
      if (el) observer.observe(el)
    }
  })
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <div>
    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <div v-else-if="articleError" style="padding: 40px; text-align: center;">
      <div style="color: var(--danger); font-size: 15px; margin-bottom: 12px;">{{ preview ? '草稿预览加载失败：' : '文章加载失败：' }}{{ articleErrorMsg }}</div>
      <button @click="retryPreview" class="btn btn-primary btn-sm">重试</button>
    </div>

    <div v-else-if="article">
      <!-- 2026-07-15 BUG-001：草稿预览标记（仅管理员可进入此页） -->
      <div v-if="preview" class="preview-banner">
        🔍 草稿预览模式（仅管理员可见，访客看不到此文章）
      </div>
      <div class="post-layout">
      <!-- 主内容区 -->
      <article class="post-main">
        <header style="margin-bottom: 32px; padding-bottom: 24px; border-bottom: 1px solid var(--line);">
          <div style="display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--muted); margin-bottom: 12px;">
            <span v-if="article.categoryId" class="badge badge-primary">{{ getCategoryName(article.categoryId) }}</span>
            <span>·</span>
            <span>{{ formatDateTime(article.publishedAt || article.createdAt) }}</span>
            <span>·</span>
            <span>{{ article.viewCount || 0 }} 次阅读</span>
          </div>
          <h1 class="font-serif" style="font-size: 32px; line-height: 1.3; margin-bottom: 12px;">{{ article.title }}</h1>
          <p v-if="article.summary" style="color: var(--text-2); font-size: 16px; line-height: 1.6;">{{ article.summary }}</p>
        </header>

        <!-- HTML 文件：使用 iframe 加载原始文件（保留所有交互功能） -->
        <div v-if="isHtmlFile" class="html-iframe-container">
          <iframe ref="htmlIframeRef" :src="htmlFileUrl" class="html-iframe" frameborder="0" allowfullscreen @load="adjustIframeHeight"></iframe>
        </div>
        <!-- 内联 HTML 内容：使用 v-html 渲染 -->
        <div v-else-if="isHtmlContent" class="html-content" v-html="proseHtml"></div>
        <!-- Markdown 内容：使用 prose 容器 -->
        <div v-else class="prose" v-html="proseHtml"></div>

        <section v-if="article.attachment" style="margin: 32px 0; padding: 16px 20px; background: var(--bg-soft); border: 1px solid var(--line); border-radius: 8px;">
          <div style="display: flex; align-items: center; gap: 12px;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 17.93 8.8l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
            <div style="flex: 1; min-width: 0;">
              <div style="font-size: 14px; font-weight: 500; word-break: break-all;">{{ article.attachment.fileName }}</div>
              <div style="font-size: 12px; color: var(--muted);">{{ formatFileSize(article.attachment.fileSize) }}</div>
            </div>
            <span v-if="article.attachment.deleted === 1" style="font-size: 12px; color: var(--muted);">原附件已被作者删除</span>
            <button v-else @click="handleDownload" :disabled="downloading" class="btn btn-primary btn-sm">
              {{ downloading ? '下载中…' : '下载附件' }}
            </button>
          </div>
        </section>

        <div v-if="article.tagIds?.length" style="margin: 32px 0; padding-top: 24px; border-top: 1px solid var(--line-soft); display: flex; gap: 6px; flex-wrap: wrap;">
          <span style="font-size: 12px; color: var(--muted); margin-right: 4px;">标签：</span>
          <span v-for="tid in article.tagIds" :key="tid" class="badge">{{ getTagName(tid) }}</span>
        </div>

        <section style="margin-top: 48px; padding-top: 24px; border-top: 1px solid var(--line);">
          <h3 class="font-serif" style="font-size: 20px; margin-bottom: 20px;">💬 评论 ({{ comments.length }})</h3>

          <div v-if="submitted" style="padding: 12px; background: var(--primary-soft); color: var(--primary); border-radius: 8px; margin-bottom: 16px; font-size: 13px;">
            评论已提交，待审核后显示 ✓
          </div>

          <form @submit.prevent="handleSubmitComment" class="card" style="padding: 16px; margin-bottom: 24px;">
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 8px;">
              <input v-model="newComment.nickname" class="form-control" placeholder="昵称 *" required />
              <input v-model="newComment.email" type="email" class="form-control" placeholder="邮箱（选填，不公开）" />
            </div>
            <textarea v-model="newComment.content" class="form-control" rows="3" placeholder="说点什么…" required style="margin-bottom: 8px; resize: vertical;"></textarea>
            <div style="text-align: right;">
              <button type="submit" :disabled="submitting" class="btn btn-primary btn-sm">
                {{ submitting ? '提交中…' : '发表评论' }}
              </button>
            </div>
          </form>

          <div v-if="!comments.length" style="padding: 24px; text-align: center; color: var(--muted); font-size: 14px;">
            还没有评论，来抢沙发？
          </div>
          <div v-else style="display: flex; flex-direction: column; gap: 14px;">
            <div v-for="c in comments" :key="c.id" style="display: flex; gap: 12px;">
              <div style="width: 36px; height: 36px; border-radius: 50%; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; font-size: 14px; flex-shrink: 0;">
                {{ c.nickname?.[0] || '?' }}
              </div>
              <div style="flex: 1; min-width: 0;">
                <div style="display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px;">
                  <strong style="font-size: 14px;">{{ c.nickname }}</strong>
                  <span style="font-size: 12px; color: var(--muted);">{{ formatDateTime(c.createdAt) }}</span>
                </div>
                <div style="color: var(--text-2); font-size: 14px; line-height: 1.6; white-space: pre-wrap;">{{ c.content }}</div>
              </div>
            </div>
          </div>
        </section>

        <section v-if="related.length" style="margin-top: 48px; padding-top: 24px; border-top: 1px solid var(--line);">
          <h3 class="font-serif" style="font-size: 20px; margin-bottom: 16px;">📖 相关推荐</h3>
          <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px;">
            <NuxtLink v-for="a in related" :key="a.id" :to="`/post/${a.slug}`" class="card" style="padding: 14px; text-decoration: none; color: inherit;">
              <div style="font-size: 12px; color: var(--muted); margin-bottom: 4px;">{{ formatDate(a.publishedAt) }}</div>
              <div style="font-weight: 500; line-height: 1.4;">{{ a.title }}</div>
            </NuxtLink>
          </div>
        </section>
      </article>

      <!-- 侧边栏 TOC -->
      <aside v-if="tocHeadings.length > 1" class="post-toc">
        <div class="post-toc-inner">
          <div class="post-toc-title">目录</div>
          <nav class="post-toc-nav">
            <a
              v-for="h in tocHeadings"
              :key="h.id"
              :href="`#${h.id}`"
              class="post-toc-link"
              :class="[
                activeHeading === h.id ? 'active' : '',
                h.level === 3 ? 'indent' : ''
              ]"
              @click.prevent="scrollToHeading(h.id)"
            >{{ h.text }}</a>
          </nav>
        </div>
      </aside>
      </div>
    </div>

    <div v-else style="padding: 40px; text-align: center; color: var(--muted);">文章不存在或已被删除</div>
  </div>
</template>

<style scoped>
.post-layout {
  display: flex;
  gap: 32px;
  max-width: 1400px;
  margin: 0 auto;
}
.post-main {
  flex: 1;
  min-width: 0;
}

/* HTML 内容全屏展示 */
.html-content {
  width: 100%;
  margin: 0;
}

/* HTML 文件 iframe 容器 */
.html-iframe-container {
  width: 100%;
  margin: 0;
}

.html-iframe {
  width: 100%;
  min-height: 1200px;
  height: auto;
  border: none;
  border-radius: 8px;
  background: #0e0f13;
  overflow: visible;
}

/* 2026-07-15 BUG-001：草稿预览标记条 */
.preview-banner {
  margin-bottom: 16px;
  padding: 10px 16px;
  background: var(--primary-soft);
  color: var(--primary);
  border: 1px solid var(--primary);
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
}

/* TOC 侧边栏 */
.post-toc {
  width: 220px;
  flex-shrink: 0;
}
.post-toc-inner {
  position: fixed;
  top: 80px;
  right: calc(50vw - 700px);
  width: 220px;
  max-height: calc(100dvh - 100px);
  overflow-y: auto;
}

@media (max-width: 1440px) {
  .post-toc-inner { right: 16px; }
}
.post-toc-title {
  font-size: 12px;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 12px;
  font-weight: 500;
}
.post-toc-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-left: 1px solid var(--line-soft);
}
.post-toc-link {
  display: block;
  padding: 4px 12px;
  font-size: 13px;
  color: var(--text-2);
  text-decoration: none;
  transition: all 0.15s;
  border-left: 2px solid transparent;
  margin-left: -1px;
  line-height: 1.5;
}
.post-toc-link:hover {
  color: var(--text);
}
.post-toc-link.active {
  color: var(--primary);
  border-left-color: var(--primary);
  font-weight: 500;
}
.post-toc-link.indent {
  padding-left: 24px;
  font-size: 12px;
}

@media (max-width: 1100px) {
  .post-toc, .post-toc-inner { display: none; }
  .post-layout { max-width: 100%; }
}

/* 正文样式 */
.prose :deep(h1) { font-family: 'DM Serif Display', serif; font-size: 28px; margin: 24px 0 12px; }
.prose :deep(h2) { font-family: 'DM Serif Display', serif; font-size: 22px; margin: 20px 0 10px; padding-top: 8px; }
.prose :deep(h3) { font-size: 18px; font-weight: 600; margin: 16px 0 8px; }
.prose :deep(p) { margin: 12px 0; line-height: 1.8; color: var(--text); }
.prose :deep(pre) {
  background: var(--code-bg);
  color: var(--code-text);
  padding: 14px 16px;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  margin: 16px 0;
}
.prose :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.9em;
  background: var(--code-bg);
  padding: 2px 6px;
  border-radius: 4px;
}
.prose :deep(pre code) { background: transparent; padding: 0; }
.prose :deep(blockquote) {
  border-left: 3px solid var(--primary);
  padding: 4px 16px;
  margin: 16px 0;
  color: var(--text-2);
  background: var(--bg-soft);
  border-radius: 0 6px 6px 0;
}
.prose :deep(ul) { margin: 12px 0; padding-left: 24px; }
.prose :deep(li) { margin: 4px 0; line-height: 1.7; }
.prose :deep(a) { color: var(--primary); text-decoration: underline; text-underline-offset: 2px; }
.prose :deep(mark) {
  background: #fef08a;
  color: inherit;
  padding: 1px 4px;
  border-radius: 3px;
}
.dark .prose :deep(mark) {
  background: #854d0e;
  color: #fef08a;
}
.prose :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 16px 0;
  font-size: 14px;
}
.prose :deep(th),
.prose :deep(td) {
  padding: 10px 14px;
  text-align: left;
  border: 1px solid var(--line);
}
.prose :deep(th) {
  background: var(--bg-soft);
  font-weight: 600;
  font-size: 13px;
  color: var(--text-2);
}
.prose :deep(tr:hover) {
  background: var(--bg-soft);
}
.prose :deep(del) {
  color: var(--muted);
  text-decoration: line-through;
}
.prose :deep(input[type="checkbox"]) {
  margin-right: 6px;
  accent-color: var(--primary);
}

@media (max-width: 768px) {
  .post-layout { gap: 0; }
  .post-main { padding: 0 4px; }
  .prose :deep(h1) { font-size: 24px; margin: 20px 0 10px; }
  .prose :deep(h2) { font-size: 20px; margin: 18px 0 8px; padding-top: 6px; }
  .prose :deep(h3) { font-size: 17px; margin: 14px 0 6px; }
  .prose :deep(p) { font-size: 16px; line-height: 1.85; margin: 10px 0; }
  .prose :deep(pre) { font-size: 13px; padding: 12px; margin: 12px 0; }
  .prose :deep(code) { font-size: 0.9em; }
  .prose :deep(table) { font-size: 13px; display: block; overflow-x: auto; -webkit-overflow-scrolling: touch; }
  .prose :deep(th), .prose :deep(td) { padding: 8px 10px; }
  .prose :deep(blockquote) { margin: 12px 0; padding: 8px 14px; }
  .prose :deep(ul), .prose :deep(ol) { padding-left: 22px; }
  .prose :deep(li) { font-size: 16px; line-height: 1.75; margin: 3px 0; }
  .prose :deep(img) { border-radius: 8px; margin: 14px 0; }
}
</style>
