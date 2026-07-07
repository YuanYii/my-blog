<script setup lang="ts">
import DOMPurify from 'dompurify'
import { formatDate as formatDateShared, formatDateTime as formatDateTimeShared, renderMarkdown, extractHeadings } from '~/composables/useMarkdownUtils'

definePageMeta({ layout: 'post' })

const route = useRoute()
const { get, post, downloadAttachment } = usePublicApi()
const $toast = useToast()
const slug = route.params.slug as string

const { data: articleRes } = await useAsyncData(
  `post-article-${slug}`,
  () => get<any>(`/articles/${slug}`),
  { transform: (r: any) => r.data }
)
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

const article = computed(() => articleRes.value || null)
const categories = computed(() => metaRes.value?.categories || [])
const tags = computed(() => metaRes.value?.tags || [])
const comments = ref<any[]>([])
const related = ref<any[]>([])
const newComment = reactive({ nickname: '', email: '', content: '' })
const submitting = ref(false)
const submitted = ref(false)
const loading = computed(() => !article.value && articleRes.value === null)

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
  let html = renderMarkdown(md)
  html = html.replace(/(href|src)=(["'])\s*(javascript|data|vbscript):/gi, '$1=$2#')
  html = html.replace(/<(script|iframe|object|embed|style|link|meta)\b[^>]*>[\s\S]*?<\/\1>/gi, '')
  html = html.replace(/<(script|iframe|object|embed|style|link|meta)\b[^>]*\/?>/gi, '')
  html = html.replace(/\s+on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  return html
}

const safeMarkdown = (md: string): string => {
  if (import.meta.client) {
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

    <div v-else-if="article" class="post-layout">
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

        <div class="prose" v-html="proseHtml"></div>

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
</style>
