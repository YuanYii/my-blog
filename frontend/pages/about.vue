<script setup lang="ts">
const { get } = usePublicApi()
// 2026-06-12 修复：about 页面属于公开前台，但原代码访问的是 /admin/settings/*，
// 走 usePublicApi 不带 Authorization → 永远 401 → bio/social 始终是默认占位。
// 改走真正的公开端点：
//   /public/profile          → nickname/avatar/bio/location（脱敏，不含 email）
//   /public/settings/social  → github/twitter/emailPublic/rss
// 2026-06-13：技术栈 / 经历改为后台可维护，走公开端点读取
//   /public/settings/techstack → { groups: [ { label, items: [ { name, dim } ] } ] }
//   /public/settings/experience → { items: [ { time, title, desc } ] }
// 2026-06-28 OPT-001（autopush）：RSS 入口按 advanced.enableRss 显隐
const { flags: siteFlags } = useSiteFlags()
const [profileRes, socialRes, techRes, expRes] = await Promise.all([
  useAsyncData('about-profile', () => get<any>('/public/profile')),
  useAsyncData('about-social', () => get<any>('/public/settings/social')),
  useAsyncData('about-techstack', () => get<any>('/public/settings/techstack')),
  useAsyncData('about-experience', () => get<any>('/public/settings/experience'))
])
const profile = computed(() => profileRes.data.value?.data || {})
const social = computed(() => socialRes.data.value?.data || {})

// 后端无数据时的兜底（与后端默认 seed 一致，避免空白）
const fallbackSkills = [
  {
    label: '工作中常用',
    items: [
      { name: 'Java / Spring Boot' },
      { name: 'MySQL / PostgreSQL' },
      { name: 'Redis / Kafka' },
      { name: 'Docker / Kubernetes' },
      { name: 'Linux / Nginx' },
      { name: 'Git / CI/CD' }
    ]
  },
  {
    label: '会用但不够熟',
    items: [
      { name: 'Vue / Nuxt' },
      { name: 'TypeScript' },
      { name: 'Go / Rust（学习中）' },
      { name: 'Elasticsearch' },
      { name: 'React', dim: true },
      { name: 'Swift / iOS 开发', dim: true }
    ]
  }
]

const fallbackExperiences = [
  {
    time: '2022 — 现在',
    title: '某互联网公司 · 高级后端工程师',
    desc: '负责核心交易链路，从单体应用到逐步服务化。带过 3 人小组。'
  },
  {
    time: '2018 — 2022',
    title: '某 SaaS 公司 · 后端工程师',
    desc: '从 0 到 1 参与了多租户 SaaS 平台的搭建，深入理解了权限、计费、数据隔离。'
  },
  {
    time: '2016 — 2018',
    title: '某电商公司 · Java 开发',
    desc: '写了两年的 CRUD，第一次体会到「能跑起来」和「能扛住流量」之间有巨大的鸿沟。'
  }
]

// 2026-06-13 修复：区分「后台已配置（含显式清空 []）」与「后端无该段（null/未初始化）」。
//   - 返回的是数组（即使为空）→ 尊重后台配置；空数组时由模板隐藏整段，避免显示他人占位数据
//   - 返回 null/undefined（段不存在）→ 才用兜底，保证全新部署不至于空白
const skills = computed(() => {
  const groups = techRes.data.value?.data?.groups
  return Array.isArray(groups) ? groups : fallbackSkills
})
const experiences = computed(() => {
  const items = expRes.data.value?.data?.items
  return Array.isArray(items) ? items : fallbackExperiences
})
</script>

<template>
  <div>
    <!-- Hero -->
    <header class="about-hero">
      <div class="about-hero-inner">
        <!-- 2026-06-16 修复：about 页头像硬编码 'Y' 跟 index.vue 一样走 profile.avatar 优先 + 首字母兜底 -->
        <div class="avatar">
          <img v-if="profile.avatar" :src="profile.avatar" alt="avatar" style="width:100%;height:100%;object-fit:cover;border-radius:inherit;" />
          <span v-else>{{ profile.nickname?.[0] || 'Y' }}</span>
        </div>
        <div class="about-hero-text">
          <h1>关于我</h1>
          <p class="hero-desc">{{ profile.bio || '后端工程师，在上海工作。日常写 Java / Spring Boot，偶尔折腾前端。喜欢把学到的东西写下来——这个博客就是写给自己的笔记。' }}</p>
          <div class="social-links">
            <a :href="social.github || '#'" class="social-link" aria-label="GitHub" target="_blank">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .5C5.4.5 0 5.9 0 12.5c0 5.3 3.4 9.8 8.2 11.4.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1.1-.8.1-.8.1-.8 1.2.1 1.9 1.3 1.9 1.3 1.1 1.9 2.9 1.4 3.6 1 .1-.8.4-1.4.8-1.7-2.7-.3-5.5-1.3-5.5-6 0-1.3.5-2.4 1.2-3.2-.1-.3-.5-1.5.1-3.2 0 0 1-.3 3.3 1.2 1-.3 2-.4 3-.4s2 .1 3 .4c2.3-1.6 3.3-1.2 3.3-1.2.7 1.7.3 2.9.1 3.2.8.8 1.2 1.9 1.2 3.2 0 4.6-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.3v3.4c0 .3.2.7.8.6 4.8-1.6 8.2-6.1 8.2-11.4C24 5.9 18.6.5 12 .5z"/></svg>
            </a>
            <a :href="social.twitter || '#'" class="social-link" aria-label="Twitter" target="_blank">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z"/></svg>
            </a>
            <a :href="`mailto:${social.emailPublic || profile.email || 'hello@example.com'}`" class="social-link" aria-label="邮箱">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
            </a>
            <a v-if="siteFlags.enableRss" :href="social.rss || '/rss.xml'" class="social-link" aria-label="RSS">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M6.18 15.64a2.18 2.18 0 0 1 2.18 2.18C8.36 19 7.38 20 6.18 20 5 20 4 19 4 17.82a2.18 2.18 0 0 1 2.18-2.18M4 4.44A15.56 15.56 0 0 1 19.56 20h-2.83A12.73 12.73 0 0 0 4 7.27V4.44m0 5.66a9.9 9.9 0 0 1 9.9 9.9h-2.83A7.07 7.07 0 0 0 4 12.93V10.1z"/></svg>
            </a>
          </div>
        </div>
      </div>
    </header>

    <!-- 我是谁 -->
    <section class="about-section">
      <h2 class="about-section-title">我是谁</h2>
      <div class="about-prose">
        <p>工作 8 年了，主要做后端，写 Java。架构上更偏好<strong>简单直接</strong>的方案，对微服务、中台这些词比较警惕——大多数时候，单体 + 好的模块化比强行拆服务更舒服。</p>
        <p>技术之外，我喜欢读书、跑步、偶尔下厨。写博客的初衷是<strong>对抗遗忘</strong>——今天踩的坑，不写下来，过三个月还会再踩一次。慢慢地，这个博客也成了我思考问题的地方。</p>
        <div class="quote">
          如果你不能简单地解释它，说明你还没真正理解它。
          <div class="quote-author">— Richard Feynman</div>
        </div>
        <p>欢迎在文章下面留言，告诉我哪里写错了、哪里有不同看法。读者反馈是博客最珍贵的部分。</p>
      </div>
    </section>

    <!-- 技术栈 -->
    <section v-if="skills.length" class="about-section">
      <h2 class="about-section-title">技术栈</h2>
      <div v-for="(group, gi) in skills" :key="gi" class="skill-group">
        <div class="skill-group-label">{{ group.label }}</div>
        <div class="skill-grid">
          <div v-for="(s, si) in group.items" :key="si" class="skill-item" :class="{ dim: s.dim }">
            <span class="skill-dot"></span>{{ s.name }}
          </div>
        </div>
      </div>
    </section>

    <!-- 经历 -->
    <section v-if="experiences.length" class="about-section">
      <h2 class="about-section-title">经历</h2>
      <ol class="timeline">
        <li v-for="(e, i) in experiences" :key="i">
          <time>{{ e.time }}</time>
          <span class="timeline-title">{{ e.title }}</span>
          <span class="timeline-desc">{{ e.desc }}</span>
        </li>
      </ol>
    </section>

    <!-- 联系我 -->
    <section class="about-section">
      <h2 class="about-section-title">联系我</h2>
      <p style="color: var(--text-2); font-size: 14px; margin-bottom: 20px;">工作相关可以发邮件，技术交流欢迎在文章下面留言，闲谈不回复哦。</p>
      <div class="contact-grid">
        <a :href="`mailto:${social.emailPublic || profile.email || 'hello@example.com'}`" class="contact-card">
          <div class="contact-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
          </div>
          <div class="contact-info">
            <div class="contact-label">Email</div>
            <div class="contact-value">{{ social.emailPublic || profile.email || 'hello@example.com' }}</div>
          </div>
        </a>
        <a :href="social.github || '#'" target="_blank" class="contact-card">
          <div class="contact-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .5C5.4.5 0 5.9 0 12.5c0 5.3 3.4 9.8 8.2 11.4.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1.1-.8.1-.8.1-.8 1.2.1 1.9 1.3 1.9 1.3 1.1 1.9 2.9 1.4 3.6 1 .1-.8.4-1.4.8-1.7-2.7-.3-5.5-1.3-5.5-6 0-1.3.5-2.4 1.2-3.2-.1-.3-.5-1.5.1-3.2 0 0 1-.3 3.3 1.2 1-.3 2-.4 3-.4s2 .1 3 .4c2.3-1.6 3.3-1.2 3.3-1.2.7 1.7.3 2.9.1 3.2.8.8 1.2 1.9 1.2 3.2 0 4.6-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.3v3.4c0 .3.2.7.8.6 4.8-1.6 8.2-6.1 8.2-11.4C24 5.9 18.6.5 12 .5z"/></svg>
          </div>
          <div class="contact-info">
            <div class="contact-label">GitHub</div>
            <div class="contact-value">@{{ social.github?.split('/').pop() || 'yuanyi' }}</div>
          </div>
        </a>
        <a :href="social.twitter || '#'" target="_blank" class="contact-card">
          <div class="contact-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z"/></svg>
          </div>
          <div class="contact-info">
            <div class="contact-label">Twitter / X</div>
            <div class="contact-value">@{{ social.twitter?.split('/').pop() || 'yuanyi' }}</div>
          </div>
        </a>
        <a v-if="siteFlags.enableRss" :href="social.rss || '/rss.xml'" class="contact-card">
          <div class="contact-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M6.18 15.64a2.18 2.18 0 0 1 2.18 2.18C8.36 19 7.38 20 6.18 20 5 20 4 19 4 17.82a2.18 2.18 0 0 1 2.18-2.18M4 4.44A15.56 15.56 0 0 1 19.56 20h-2.83A12.73 12.73 0 0 0 4 7.27V4.44m0 5.66a9.9 9.9 0 0 1 9.9 9.9h-2.83A7.07 7.07 0 0 0 4 12.93V10.1z"/></svg>
          </div>
          <div class="contact-info">
            <div class="contact-label">RSS</div>
            <div class="contact-value">{{ social.rss || '/rss.xml' }}</div>
          </div>
        </a>
      </div>
    </section>
  </div>
</template>
