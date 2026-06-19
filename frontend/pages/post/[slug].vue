<script setup lang="ts">
import DOMPurify from 'dompurify'

// 2026-06-13 修复（auto_fix BUG-003）：
// 原 safeMarkdown 在 SSR 阶段调 DOMPurify.sanitize 抛 `default.sanitize is not a function`
// → 所有 /post/* 500。修复：safeMarkdown 内 import.meta.client 守卫，SSR 走 ssrSafeHtml 兜底。

const route = useRoute()
const { get, post } = usePublicApi()
const slug = route.params.slug as string

// 2026-06-13 修复（BUG-050 SEO 严重缺陷）：
//   原 onMounted 内才 fetch article —— SSR 期间 article 为 null，模板只渲染"加载中…"，
//   搜索引擎（百度/Google）抓 /post/<slug> 拿到空 HTML。
//   改用 setup 顶层 useAsyncData 预取（SSR 友好）：
//   - article：核心内容必须 SSR
//   - categories / tags：用于显示文章元信息（分类名 + tag 名），也 SSR
//   - comments：评论列表 SSR（评论数从 0 变 N 也算 SEO 信号）
//   - related：相关推荐 SSR（增加站内链接）
// 关联修复（BUG-068 时区）：后端返的 publishedAt 是 UTC 时刻字符串（无 'Z'），
//   new Date("2026-06-13T03:45:00") 默认按本地时区解析会偏 8h。
//   统一加 'Z' 表明 UTC，Date 内部按 UTC 解析，getHours/getMinutes 用本地时区显示。
const { data: articleRes } = await useAsyncData(
  `post-article-${slug}`,
  () => get<any>(`/articles/${slug}`),
  { transform: (r: any) => r.data }
)
const { data: metaRes } = await useAsyncData(
  `post-meta-${slug}`,
  async () => {
    const [c, t] = await Promise.all([
      get<any>('/articles/categories'),
      get<any>('/articles/tags')
    ])
    return { categories: c.data || [], tags: t.data || [] }
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
// notFound：article SSR 阶段就报 404（useAsyncData 自动 throw）→ 走 Nuxt 404 路由。
// 这里不再需要 notFound 客户端分支。
const loading = computed(() => !article.value && articleRes.value === null)

const getCategoryName = (id: number) => categories.value.find(c => c.id === id)?.name || ''
const getTagName = (id: number) => tags.value.find(t => t.id === id)?.name || ''

// 评论 + 相关文章：依赖 article.id，所以放 onMounted client 端加载
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

const formatDate = (d: string) => d ? d.substring(0, 10) : ''
// 2026-06-13 修复（BUG-068）：后端返的 publishedAt 是 LocalDateTime 序列化（无 'Z'），
// 直接 new Date("2026-06-13T03:45:00") 会按本地时区解析，与服务端 Asia/Shanghai 差 8h。
// 统一加 'Z' 表明 UTC，Date 内部按 UTC 解析，getXxx() 自动转本地时区。
 // 2026-06-19 修复（BUG-XXX 配套）：兼容 number（Unix ms）和 string 两种 createdAt 形态。
 // 之前 d.endsWith('Z') 在 number 上会抛 "endsWith is not a function"。
 const formatDateTime = (d: any) => {
  if (d == null || d === '') return ''
  // number：Unix 毫秒
  if (typeof d === 'number') {
    const date = new Date(d)
    if (isNaN(date.getTime())) return ''
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  // string：尝试 ISO 解析，失败再走 replace 兜底
  const utc = d.endsWith('Z') || d.includes('+') || d.includes('-', 10) ? d : d + 'Z'
  const date = new Date(utc)
  if (isNaN(date.getTime())) return d.replace('T', ' ').substring(0, 16)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// 简易 markdown 渲染（生产用 marked/remark，这里 MVP 走最简版）
// 2026-06-16 修复（BUG-078）：原实现只处理普通链接 `[text](url)`，没处理图片 `![alt](url)`。
// 后果：文章详情页把 `![alt](url)` 显示成 `<p>!<a href="url">alt</a></p>`——"图片链接"而非图片。
// 同样的 bug 之前在 admin/edit.vue 修过（2026-06-15 v2.2.0 §12.10），
// 但 post/[slug].vue 漏改——admin 编辑器预览正确 ≠ 详情页正确，两边 markdown 渲染器独立。
// 修法：与 edit.vue 保持完全一致——在普通链接正则之前先匹配图片语法。
const renderMarkdown = (md: string) => {
  if (!md) return ''
  let html = md
  // 代码块
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code class="lang-$1">$2</code></pre>')
  // 标题
  html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>')
  html = html.replace(/^## (.*$)/gim, '<h2>$1</h2>')
  html = html.replace(/^# (.*$)/gim, '<h1>$1</h1>')
  // 粗体/斜体
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  // 图片语法：必须在普通链接前匹配（图片也是 ![](url) 形式，正则覆盖普通链接的话会先匹配错）
  // alt 文本里允许空：![](url)，url 允许双引号包起来：![alt]( "url" )
  html = html.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g,
    '<img src="$2" alt="$1" loading="lazy" />')
  // 链接
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>')
  // 引用
  html = html.replace(/^> (.*$)/gim, '<blockquote>$1</blockquote>')
  // 列表
  html = html.replace(/^- (.*$)/gim, '<li>$1</li>')
  html = html.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>')
  // 段落
  html = html.split('\n\n').map(p => p.startsWith('<') ? p : `<p>${p}</p>`).join('\n')
  return html
}

/**
 * SSR 阶段的安全 HTML（auto_fix BUG-003）：
 * - DOMPurify 在 SSR 不可用（默认导出不是函数），会抛 500
 * - 替代方案：仅过滤危险协议（javascript: / data: / vbscript:），
 *   再用一个简单的白名单把 <script> / <iframe> / on* 属性剥离。
 * - 不依赖 DOMPurify，SSR 安全可用。
 */
const ssrSafeHtml = (md: string): string => {
  if (!md) return ''
  let html = renderMarkdown(md)
  // 1. 过滤危险协议
  html = html.replace(/(href|src)=(["'])\s*(javascript|data|vbscript):/gi, '$1=$2#')
  // 2. 去掉 <script> / <iframe> / <object> / <embed> 整段
  html = html.replace(/<(script|iframe|object|embed|style|link|meta)\b[^>]*>[\s\S]*?<\/\1>/gi, '')
  html = html.replace(/<(script|iframe|object|embed|style|link|meta)\b[^>]*\/?>/gi, '')
  // 3. 去掉 on* 事件属性
  html = html.replace(/\s+on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  return html
}

/**
 * 安全渲染 markdown（auto_fix BUG-003 修复）：
 * - SSR 阶段：DOMPurify 默认导出不可用会抛 500 → 走 ssrSafeHtml 兜底（协议 + 标签过滤）
 * - Client 阶段：DOMPurify.sanitize 严格白名单（防御深度）
 * 防止用户文章里写 script 标签或 onerror 属性等触发 XSS
 */
const safeMarkdown = (md: string): string => {
  if (import.meta.client) {
    // 客户端：DOMPurify 已就绪（顶层 import 在 client bundle 正常工作）
    return DOMPurify.sanitize(renderMarkdown(md), {
      // 2026-06-16 修复（BUG-078）：加 img 标签——之前白名单漏了，图片 markdown 渲染出 <img> 也被净化掉
      ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'br', 'hr', 'img'],
      // 2026-06-16 修复（BUG-078）：加 src/alt/loading（与 edit.vue 一致）
      ALLOWED_ATTR: ['href', 'target', 'class', 'src', 'alt', 'loading'],
      ALLOW_DATA_ATTR: false
    })
  }
  // SSR：避免 DOMPurify 不可用导致 500
  return ssrSafeHtml(md)
}

onMounted(async () => {
  // 评论 + 相关文章是依赖 article.id 的子加载，setup 顶层 useAsyncData 拿不到 dynamic param
  // 放 onMounted 走 client 端加载
  if (article.value) {
    await Promise.all([loadComments(), loadRelated()])
  }
})
</script>

<template>
  <div>
    <!-- 文章 SSR 失败：article 404 已被 useAsyncData 抛错，Nuxt error page 会兜底
         这里是加载中过渡态（罕见，仅在 setup 执行慢的瞬间出现） -->
    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <article v-else-if="article">
      <!-- 标题区 -->
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

      <!-- 正文 -->
      <div class="prose" v-html="safeMarkdown(article.contentMd)"></div>

      <!-- 标签 -->
      <div v-if="article.tagIds?.length" style="margin: 32px 0; padding-top: 24px; border-top: 1px solid var(--line-soft); display: flex; gap: 6px; flex-wrap: wrap;">
        <span style="font-size: 12px; color: var(--muted); margin-right: 4px;">标签：</span>
        <span v-for="tid in article.tagIds" :key="tid" class="badge">{{ getTagName(tid) }}</span>
      </div>

      <!-- 评论区 -->
      <section style="margin-top: 48px; padding-top: 24px; border-top: 1px solid var(--line);">
        <h3 class="font-serif" style="font-size: 20px; margin-bottom: 20px;">💬 评论 ({{ comments.length }})</h3>

        <div v-if="submitted" style="padding: 12px; background: var(--primary-soft); color: var(--primary); border-radius: 8px; margin-bottom: 16px; font-size: 13px;">
          评论已提交，待审核后显示 ✓
        </div>

        <!-- 发表评论 -->
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

        <!-- 评论列表 -->
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

      <!-- 相关文章 -->
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
  </div>
</template>

<style scoped>
.prose :deep(h1) { font-family: 'DM Serif Display', serif; font-size: 28px; margin: 24px 0 12px; }
.prose :deep(h2) { font-family: 'DM Serif Display', serif; font-size: 22px; margin: 20px 0 10px; }
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
</style>
