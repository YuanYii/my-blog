<script setup lang="ts">
const { get } = usePublicApi()

// 拉取首页所需数据
const [health, categoriesRes, tagsRes, profileRes, socialRes, blogRes] = await Promise.all([
  useAsyncData('health', () => get<any>('/health')),
  useAsyncData('home-categories', () => get<any>('/articles/categories')),
  useAsyncData('home-tags', () => get<any>('/articles/tags')),
  // 2026-06-12 修复：原 `/admin/settings/profile` 走 usePublicApi（不带 token）→
  // 所有访客（含未登录的 admin 自己）首屏永远拿 401 → hero bio/avatar/location 永远显示默认占位文本，
  // admin 在后台改完保存其实根本没生效。改用新建的公开端点 /public/profile（只返回 nickname/avatar/bio/location）。
  useAsyncData('home-profile', () => get<any>('/public/profile')),
  // 社交账号 / 站点信息：调公开端点（不需要 token）——所有用户都能拿到
  useAsyncData('home-social', () => get<any>('/public/settings/social')),
  useAsyncData('home-blog', () => get<any>('/public/settings/blog'))
])

// 列表分页状态
const currentPage = ref(1)
const pageSize = 4

// 文章列表：随 currentPage 变化自动重新拉取
const { data: articlesRes } = await useAsyncData(
  'home-articles',
  () => get<any>('/articles', { page: currentPage.value, size: pageSize }),
  { watch: [currentPage] }
)

const articles = computed(() => articlesRes.value?.data?.records || [])
const total = computed(() => articlesRes.value?.data?.total || 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const categories = computed(() => categoriesRes.data.value?.data || [])
const tags = computed(() => tagsRes.data.value?.data || [])
const profile = computed(() => profileRes.data.value?.data || {})
const social = computed(() => socialRes.data.value?.data || {})
const blog = computed(() => blogRes.data.value?.data || {})

// 分类名查找
const categoryName = (id: number) => categories.value.find((c: any) => c.id === id)?.name || '未分类'
const categorySlug = (id: number) => categories.value.find((c: any) => c.id === id)?.slug || ''

// 统计
const stats = computed(() => ({
  articles: total.value,
  categories: categories.value.length,
  tags: tags.value.length,
  // 总字数：按当前页字数 / 当前页数量 * 总数 估算
  words: Math.round((articles.value.reduce((s: number, a: any) =>
    s + (a.contentMd?.length || 0), 0) / Math.max(articles.value.length, 1)) * total.value)
}))

// 标签按字母顺序，取前 12 个
const popularTags = computed(() => {
  return tags.value.slice(0, 12)
})

// 头像首字母（兜底显示）
const avatarLetter = computed(() => profile.value?.nickname?.[0] || profile.value?.username?.[0] || 'Y')
// 社交用户名（@xxx 形式）
const githubHandle = computed(() => social.value?.github ? '@' + (social.value.github.split('/').filter(Boolean).pop() || '') : '')
const twitterHandle = computed(() => social.value?.twitter ? '@' + (social.value.twitter.split('/').filter(Boolean).pop() || '') : '')

const formatDate = (s: string) => s ? s.substring(0, 10) : ''
const formatViews = (n: number) => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)

// 切页：边界判断 + 平滑滚到列表
// 2026-06-12 修复：模板里 `@click="goPage(p)"` 的 p 来自 pageList，类型是 number | '…'。
// 之前 goPage 形参只接 number → TS 报错。改成接受联合，运行时遇到 '…' 直接 return。
const goPage = (p: number | string) => {
  if (typeof p !== 'number') return
  if (p < 1 || p > totalPages.value || p === currentPage.value) return
  currentPage.value = p
  if (import.meta.client) {
    nextTick(() => {
      document.querySelector('.post-list')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }
}

// 要渲染的页码（含省略号）
const pageList = computed(() => {
  const tp = totalPages.value
  const cur = currentPage.value
  if (tp <= 7) return Array.from({ length: tp }, (_, i) => i + 1)
  if (cur <= 4) return [1, 2, 3, 4, 5, '…', tp]
  if (cur >= tp - 3) return [1, '…', tp - 4, tp - 3, tp - 2, tp - 1, tp]
  return [1, '…', cur - 1, cur, cur + 1, '…', tp]
})
</script>

<template>
  <div>
    <!-- Hero -->
    <section class="hero">
      <div class="hero-inner">
        <div class="avatar">
          <img v-if="profile.avatar" :src="profile.avatar" alt="avatar" style="width:100%;height:100%;object-fit:cover;border-radius:inherit;" />
          <span v-else>{{ avatarLetter }}</span>
        </div>
        <div class="hero-text">
          <h1>嗨，这里是 {{ blog.title || '加载中' }}</h1>
          <div class="hero-role">{{ blog.subtitle || '后端工程师的日常' }}</div>
          <p class="hero-desc">{{ blog.description || '记录技术、读书、生活' }}</p>
          <div class="hero-meta">
            <span v-if="profile.location">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 10c0 7-8 12-8 12s-8-5-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/></svg>
              {{ profile.location }}
            </span>
            <span v-if="social.emailPublic || profile.email">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="14" x="2" y="5" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
              {{ social.emailPublic || profile.email }}
            </span>
            <span v-if="githubHandle">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .5C5.4.5 0 5.9 0 12.5c0 5.3 3.4 9.8 8.2 11.4.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1.1-.8.1-.8.1-.8 1.2.1 1.9 1.3 1.9 1.3 1.1 1.9 2.9 1.4 3.6 1 .1-.8.4-1.4.8-1.7-2.7-.3-5.5-1.3-5.5-6 0-1.3.5-2.4 1.2-3.2-.1-.3-.5-1.5.1-3.2 0 0 1-.3 3.3 1.2 1-.3 2-.4 3-.4s2 .1 3 .4c2.3-1.6 3.3-1.2 3.3-1.2.7 1.7.3 2.9.1 3.2.8.8 1.2 1.9 1.2 3.2 0 4.6-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.3v3.4c0 .3.2.7.8.6 4.8-1.6 8.2-6.1 8.2-11.4C24 5.9 18.6.5 12 .5z"/></svg>
              {{ githubHandle }}
            </span>
          </div>
          <div class="social-links">
            <a v-if="social.github" :href="social.github" class="social-link" aria-label="GitHub" target="_blank" rel="noopener"><svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .5C5.4.5 0 5.9 0 12.5c0 5.3 3.4 9.8 8.2 11.4.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1.1-.8.1-.8.1-.8 1.2.1 1.9 1.3 1.9 1.3 1.1 1.9 2.9 1.4 3.6 1 .1-.8.4-1.4.8-1.7-2.7-.3-5.5-1.3-5.5-6 0-1.3.5-2.4 1.2-3.2-.1-.3-.5-1.5.1-3.2 0 0 1-.3 3.3 1.2 1-.3 2-.4 3-.4s2 .1 3 .4c2.3-1.6 3.3-1.2 3.3-1.2.7 1.7.3 2.9.1 3.2.8.8 1.2 1.9 1.2 3.2 0 4.6-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.3v3.4c0 .3.2.7.8.6 4.8-1.6 8.2-6.1 8.2-11.4C24 5.9 18.6.5 12 .5z"/></svg></a>
            <a v-if="social.twitter" :href="social.twitter" class="social-link" aria-label="Twitter" target="_blank" rel="noopener"><svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z"/></svg></a>
            <a v-if="social.emailPublic || profile.email" :href="`mailto:${social.emailPublic || profile.email}`" class="social-link" aria-label="邮箱"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg></a>
            <a v-if="social.rss" :href="social.rss" class="social-link" aria-label="RSS" target="_blank"><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M6.18 15.64a2.18 2.18 0 0 1 2.18 2.18C8.36 19 7.38 20 6.18 20 5 20 4 19 4 17.82a2.18 2.18 0 0 1 2.18-2.18M4 4.44A15.56 15.56 0 0 1 19.56 20h-2.83A12.73 12.73 0 0 0 4 7.27V4.44m0 5.66a9.9 9.9 0 0 1 9.9 9.9h-2.83A7.07 7.07 0 0 0 4 12.93V10.1z"/></svg></a>
          </div>
        </div>
      </div>
    </section>

    <!-- Bento stats -->
    <section class="section" style="padding-top: 32px; padding-bottom: 32px;">
      <div class="bento">
        <div class="bento-item">
          <div class="bento-label">文章</div>
          <div class="bento-value">{{ stats.articles }}</div>
          <div class="bento-delta">+3 本月</div>
        </div>
        <div class="bento-item">
          <div class="bento-label">分类</div>
          <div class="bento-value">{{ stats.categories }}</div>
          <div class="bento-delta">技术 / 生活</div>
        </div>
        <div class="bento-item">
          <div class="bento-label">标签</div>
          <div class="bento-value">{{ stats.tags }}</div>
          <div class="bento-delta">+2 本月</div>
        </div>
        <div class="bento-item">
          <div class="bento-label">总字数</div>
          <div class="bento-value">{{ Math.round(stats.words / 1000) }}k</div>
          <div class="bento-delta">持续更新</div>
        </div>
      </div>
    </section>

    <!-- 最新文章 -->
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">
          <span class="section-title-mark"></span>
          最新文章
        </h2>
        <NuxtLink to="/archives" class="section-action">
          查看全部
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l7 7-7 7"/></svg>
        </NuxtLink>
      </div>

      <div v-if="!articles.length" class="post-list">
        <div class="post-card" style="text-align: center; color: var(--muted);">还没有文章</div>
      </div>
      <div v-else class="post-list">
        <NuxtLink v-for="(a, i) in articles" :key="a.id" :to="`/post/${a.slug}`" style="display: block;">
          <article class="post-card" :class="{ featured: i === 0 }">
            <div class="post-card-head">
              <span class="post-card-cat">{{ categoryName(a.categoryId) }}</span>
              <span class="post-card-date">{{ formatDate(a.publishedAt || a.createdAt) }}</span>
            </div>
            <h3 class="post-card-title">
              {{ a.title }}
              <span v-if="i === 0" class="featured-badge">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.4 7.4H22l-6.2 4.5 2.4 7.4-6.2-4.5-6.2 4.5 2.4-7.4L2 9.4h7.6L12 2z"/></svg>
                最新
              </span>
            </h3>
            <p class="post-card-excerpt">{{ a.summary }}</p>
            <div class="post-card-foot">
              <div class="post-stats">
                <span class="post-stat">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
                  {{ formatViews(a.viewCount || 0) }}
                </span>
              </div>
            </div>
          </article>
        </NuxtLink>
      </div>

      <div v-if="totalPages > 1" class="pagination">
        <button class="page-btn" :disabled="currentPage === 1" @click="goPage(currentPage - 1)" aria-label="上一页">‹</button>
        <template v-for="(p, i) in pageList" :key="i">
          <span v-if="p === '…'" class="page-btn" style="cursor: default;">…</span>
          <button
            v-else
            class="page-btn"
            :class="{ active: p === currentPage }"
            @click="goPage(p)"
          >{{ p }}</button>
        </template>
        <button class="page-btn" :disabled="currentPage === totalPages" @click="goPage(currentPage + 1)" aria-label="下一页">›</button>
      </div>
    </section>

    <!-- 热门标签 -->
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">
          <span class="section-title-mark"></span>
          热门标签
        </h2>
        <NuxtLink to="/tags" class="section-action">查看全部 →</NuxtLink>
      </div>
      <div v-if="!popularTags.length" style="color: var(--muted); font-size: 14px;">还没有标签</div>
      <div v-else class="tag-cloud">
        <span v-for="t in popularTags" :key="t.id" class="tag-pill">
          {{ t.name }} <span class="count">{{ t.articleCount || 0 }}</span>
        </span>
      </div>
    </section>

    <!-- 站点 footer（2026-06-08：从 /public/settings/blog 拉 copyright） -->
    <footer v-if="blog.copyright" class="site-footer" style="text-align: center; padding: 32px 0; color: var(--muted); font-size: 13px; border-top: 1px solid var(--border); margin-top: 24px;">
      {{ blog.copyright }}
    </footer>
  </div>
</template>
