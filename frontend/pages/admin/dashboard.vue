<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get } = useAdminApi()
const { meta, refresh: refreshMeta } = useAdminMeta()
const { user } = useAuth()

const loading = ref(true)
const kpi = ref<any>({})
const categoryDist = ref<any[]>([])
const publishTrend = ref<any[]>([])
// v2.5.0 新增：真实访问数据
const visitTrend = ref<any[]>([])      // 近 30 天每日 PV + UV
const visitTrend7 = ref<any[]>([])     // 近 7 天（sparkline 用）
const topArticles = ref<any[]>([])     // 热门文章 TOP 10

// 时间感知
const now = new Date()
const hour = now.getHours()
const greeting = hour < 6 ? '凌晨好' : hour < 11 ? '早上好' : hour < 13 ? '中午好' : hour < 18 ? '下午好' : '晚上好'
const greetingEmoji = hour < 6 ? '🌙' : hour < 11 ? '☀️' : hour < 13 ? '🌤️' : hour < 18 ? '☀️' : '🌙'
const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const dateStr = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 · ${weekdays[now.getDay()]} · ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`

const loadAll = async () => {
  loading.value = true
  try {
    const res = await get<any>('/admin/dashboard')
    kpi.value = res.data?.kpi || {}
    categoryDist.value = res.data?.categoryDistribution || []
    publishTrend.value = res.data?.publishTrend || []
    // v2.5.0
    visitTrend.value = res.data?.visitTrend || []
    visitTrend7.value = res.data?.visitTrend7 || []
    topArticles.value = res.data?.topArticles || []
  } catch {
    kpi.value = {}
  } finally {
    loading.value = false
  }
  await refreshMeta()
}

// 补齐缺失日期的辅助（用于 sparkline —— 缺数据日填 0）
const fillDays = (data: any[], days: number, key = 'pv'): number[] => {
  const map = new Map<string, number>()
  for (const d of data) {
    // d.date 形如 '2026-06-16' 或 Date 对象
    const ds = typeof d.date === 'string' ? d.date.substring(0, 10) : new Date(d.date).toISOString().substring(0, 10)
    map.set(ds, Number(d[key]) || 0)
  }
  const out: number[] = []
  const today = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    const ds = d.toISOString().substring(0, 10)
    out.push(map.get(ds) || 0)
  }
  return out
}

// 待办：从 meta 派生
const todos = computed(() => [
  {
    type: 'urgent',
    title: '待审评论',
    meta: meta.value.pendingComments > 0 ? `需要尽快处理` : '暂无',
    count: meta.value.pendingComments || 0,
    to: '/admin/comments'
  },
  {
    type: 'warning',
    title: '未发布草稿',
    meta: meta.value.draftCount > 0 ? `${meta.value.draftCount} 篇草稿待处理` : '草稿箱已清空',
    count: meta.value.draftCount || 0,
    to: '/admin/posts'
  },
  {
    type: 'success',
    title: '数据备份',
    meta: '下次自动备份：明天 03:00',
    count: '✓',
    to: '/admin/settings'
  }
])

// 最近文章
const recentPosts = ref<any[]>([])
const loadRecent = async () => {
  try {
    const res = await get<any>('/articles/admin/all', { size: 5 })
    recentPosts.value = res.data?.records || []
  } catch { /* ignore */ }
}

// 站点状态（mock）
const services = [
  { name: '服务运行',  status: 'success', value: '正常 · 36 天' },
  { name: '数据库',    status: 'success', value: '128 MB / 1 GB' },
  { name: 'Redis 缓存', status: 'warning', value: '82% 已用' },
  { name: 'CDN',       status: 'success', value: '已启用' },
  { name: 'SSL 证书',  status: 'success', value: '78 天后到期' }
]

// 流量来源（mock）
const trafficSources = [
  { name: 'Google',    pct: 42, color: 'var(--primary)' },
  { name: '直接访问',  pct: 28, color: 'var(--accent)' },
  { name: '百度',      pct: 15, color: '#3b82f6' },
  { name: 'Twitter',   pct: 9,  color: '#8b5cf6' },
  { name: '其他',      pct: 6,  color: 'var(--muted)' }
]

// Chart.js 实例引用
let trendChart: any = null
let categoryChart: any = null
let sparkCharts: any[] = []

// 趋势图 tab
const activeTab = ref('30')

// 主题色
const getThemeColors = () => {
  if (!import.meta.client) return { primary: '#2f6f5e', accent: '#c97b3f', muted: '#8b8475', text: '#1a1f2e', grid: 'rgba(0,0,0,0.04)' }
  const isDark = document.documentElement.classList.contains('dark')
  return {
    primary: isDark ? '#5fb09a' : '#2f6f5e',
    accent:  isDark ? '#d99262' : '#c97b3f',
    text:    isDark ? '#e8e6df' : '#1a1f2e',
    muted:   isDark ? '#6f6c63' : '#8b8475',
    line:    isDark ? '#2a3038' : '#e8e4d8',
    grid:    isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.04)'
  }
}

const renderCharts = () => {
  if (!import.meta.client) return
  const Chart = (window as any).Chart
  if (!Chart) return

  const c = getThemeColors()
  const rand = (min: number, max: number) => Math.random() * (max - min) + min

  // 趋势图（v2.5.0 真实数据：来自 page_view.dailyStats）
  const days = activeTab.value === '7' ? 7 : activeTab.value === '30' ? 30 : activeTab.value === '90' ? 90 : 365
  const source = days <= 7 ? visitTrend7.value : visitTrend.value
  // 缺数据日补 0
  const pvSeries = fillDays(source, days, 'pv')
  const uvSeries = fillDays(source, days, 'uv')
  // X 轴 label：'MM-DD'
  const xLabels: string[] = []
  const today = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    xLabels.push(`${d.getMonth() + 1}/${d.getDate()}`)
  }
  if (trendChart) trendChart.destroy()
  const trendCtx = (document.getElementById('chart-trend') as any)?.getContext('2d')
  if (trendCtx) {
    trendChart = new Chart(trendCtx, {
      type: 'line',
      data: {
        labels: xLabels,
        datasets: [
          {
            label: 'PV',
            data: pvSeries,
            borderColor: c.primary,
            backgroundColor: 'rgba(47, 111, 94, 0.10)',
            borderWidth: 2,
            fill: true,
            tension: 0.35,
            pointRadius: 0,
            pointHoverRadius: 5
          },
          {
            label: 'UV',
            data: uvSeries,
            borderColor: c.accent,
            backgroundColor: 'transparent',
            borderWidth: 1.5,
            borderDash: [4, 3],
            fill: false,
            tension: 0.35,
            pointRadius: 0,
            pointHoverRadius: 5
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'top', align: 'end', labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true, font: { size: 11 }, color: c.muted } },
          tooltip: { backgroundColor: '#1a1f2e', padding: 10, cornerRadius: 8 }
        },
        scales: {
          x: { grid: { display: false }, ticks: { color: c.muted, font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 8 } },
          y: { grid: { color: c.grid, drawBorder: false }, ticks: { color: c.muted, font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 5 }, beginAtZero: true }
        },
        interaction: { mode: 'index', intersect: false }
      }
    })
  }

  // 分类饼图
  if (categoryChart) categoryChart.destroy()
  const catCtx = (document.getElementById('chart-category') as any)?.getContext('2d')
  if (catCtx && categoryDist.value.length) {
    const labels = categoryDist.value.map(c => c.name)
    const data = categoryDist.value.map(c => c.cnt)
    const colors = ['#2f6f5e', '#c97b3f', '#3b82f6', '#8b5cf6', '#94a3b8', '#ec4899', '#10b981']
    categoryChart = new Chart(catCtx, {
      type: 'doughnut',
      data: {
        labels,
        datasets: [{
          data,
          backgroundColor: labels.map((_, i) => colors[i % colors.length]),
          borderWidth: 0,
          spacing: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '70%',
        plugins: {
          legend: { position: 'right', labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true, font: { size: 11 }, color: c.text, padding: 8 } }
        }
      }
    })
  }

  // 4 个 sparkline（v2.5.0：除"评论"用真实数据外，其他尽量用真实值或空数组）
  sparkCharts.forEach(c => c.destroy())
  sparkCharts = []
  const sparkData: Record<string, number[]> = {
    // 今日 PV sparkline：近 7 天每日 PV
    'spark-pv': fillDays(visitTrend7.value, 7, 'pv'),
    // 发布数 sparkline：近 7 天每日发布数
    'spark-posts': fillDays(publishTrend.value, 7, 'cnt'),
    // 评论数 sparkline：原 mock 保留（page_view 不含评论；后续可加 comment_view 单独表）
    'spark-comments': [12, 8, 15, 6, 9, 11, 14, 8, 10, 7, 12, 8, 6, 8].slice(0, 7),
    // "累计阅读"sparkline 用近 7 天 PV 总和
    'spark-words': fillDays(visitTrend7.value, 7, 'pv')
  }
  const sparkColors: Record<string, string> = {
    'spark-pv': c.primary,
    'spark-posts': c.primary,
    'spark-comments': c.accent,
    'spark-words': c.primary
  }
  Object.entries(sparkData).forEach(([id, data]) => {
    const el = document.getElementById(id) as any
    if (!el) return
    const ctx = el.getContext('2d')
    const grad = ctx.createLinearGradient(0, 0, 0, 40)
    grad.addColorStop(0, sparkColors[id] + '40')
    grad.addColorStop(1, sparkColors[id] + '00')
    const ch = new Chart(ctx, {
      type: 'line',
      data: {
        labels: data.map((_, i) => i),
        datasets: [{ data, borderColor: sparkColors[id], backgroundColor: grad, borderWidth: 1.5, fill: true, tension: 0.4, pointRadius: 0 }]
      },
      options: {
        responsive: false,
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
        scales: { x: { display: false }, y: { display: false } }
      }
    })
    sparkCharts.push(ch)
  })
}

const formatDateTime = (s: string) => s ? s.replace('T', ' ').substring(0, 16) : ''
const formatDate = (s: string) => s ? s.substring(0, 10) : ''
const statusLabel = (s: number) => ({ 0: '草稿', 1: '已发布', 2: '已归档' }[s] || '未知')

onMounted(async () => {
  await loadAll()
  await loadRecent()
  // 2026-06-18：从 npm 包按需加载（Vite 自动 code-split，仅 admin 路由打包）
  const { default: Chart } = await import('chart.js/auto')
  ;(window as any).Chart = Chart  // 兼容现有 renderCharts() 用 window.Chart
  renderCharts()
})

watch(activeTab, () => {
  if (trendChart) renderCharts()
})
</script>

<template>
  <div>
    <!-- Welcome -->
    <div class="welcome-bar">
      <div>
        <h1 class="welcome-greeting">
          {{ greeting }}，<ClientOnly><span>{{ user?.nickname || user?.username || 'Admin' }}</span><template #fallback><span>加载中</span></template></ClientOnly> <span class="emoji">{{ greetingEmoji }}</span>
        </h1>
        <div class="welcome-meta">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="4" rx="2"/><path d="M16 2v4M8 2v4M3 10h18"/></svg>
          {{ dateStr }}
        </div>
      </div>
      <div class="welcome-actions">
        <NuxtLink to="/admin/edit" class="btn btn-primary">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
          写新文章
        </NuxtLink>
        <NuxtLink to="/admin/comments" class="btn btn-ghost">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/></svg>
          处理评论
        </NuxtLink>
      </div>
    </div>

    <!-- KPI -->
    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-card-label">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
          今日 PV / UV
        </div>
        <!-- v2.5.0：真实数据（kpi.todayPV / todayUV） -->
        <div class="kpi-card-value">{{ (kpi.todayPV || 0).toLocaleString() }}</div>
        <div class="kpi-card-delta">
          独立访客 <strong>{{ kpi.todayUV || 0 }}</strong>
        </div>
        <canvas class="kpi-card-spark" id="spark-pv"></canvas>
      </div>

      <div class="kpi-card">
        <div class="kpi-card-label">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg>
          总文章
        </div>
        <!-- 2026-06-16 OPT：包 NuxtLink 跳 /admin/posts -->
        <NuxtLink to="/admin/posts" class="kpi-card-link">
          <div class="kpi-card-value">{{ kpi.totalArticles || 0 }}</div>
          <div class="kpi-card-delta">+{{ kpi.publishedArticles || 0 }} 已发布 · 查看 →</div>
        </NuxtLink>
        <canvas class="kpi-card-spark" id="spark-posts"></canvas>
      </div>

      <div class="kpi-card">
        <div class="kpi-card-label">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/></svg>
          待审评论
        </div>
        <!-- 2026-06-16 OPT：包 NuxtLink 跳 /admin/comments -->
        <NuxtLink to="/admin/comments" class="kpi-card-link">
          <div class="kpi-card-value" :style="{ color: meta.pendingComments > 0 ? 'var(--accent)' : 'var(--text)' }">{{ meta.pendingComments }}</div>
          <div class="kpi-card-delta" :style="{ color: meta.pendingComments > 0 ? 'var(--accent)' : 'var(--success)' }">
            {{ meta.pendingComments > 0 ? '需要处理 · 去处理 →' : '已全部处理 · 查看 →' }}
          </div>
        </NuxtLink>
        <canvas class="kpi-card-spark" id="spark-comments"></canvas>
      </div>

      <div class="kpi-card">
        <div class="kpi-card-label">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7V4h16v3M9 20h6M12 4v16"/></svg>
          总字数
        </div>
        <!-- 2026-06-16 OPT：总字数按已发布文章实际字数（strip markdown 后字符数）展示 -->
        <div class="kpi-card-value">{{ (kpi.totalWordCount || 0).toLocaleString() }}</div>
        <div class="kpi-card-delta">已发布文章累计</div>
        <canvas class="kpi-card-spark" id="spark-words"></canvas>
      </div>
    </div>

    <!-- Main grid -->
    <div class="dashboard-grid">
      <!-- Left: charts -->
      <div style="display: flex; flex-direction: column; gap: 16px;">
        <div class="panel">
          <div class="panel-header">
            <h3 class="panel-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
              访问趋势
            </h3>
            <div class="chart-tabs">
              <button v-for="t in ['7', '30', '90', '今年']" :key="t"
                class="chart-tab" :class="{ active: activeTab === t }"
                @click="activeTab = t">{{ t }}{{ t === '今年' ? '' : ' 天' }}</button>
            </div>
          </div>
          <div class="chart-area">
            <canvas id="chart-trend"></canvas>
          </div>
        </div>

        <div class="panel" style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px;">
          <div>
            <div class="panel-header">
              <h3 class="panel-title">分类分布</h3>
              <NuxtLink to="/admin/categories" class="panel-action">管理 →</NuxtLink>
            </div>
            <div class="chart-area" style="height: 200px;">
              <canvas v-if="categoryDist.length" id="chart-category"></canvas>
              <div v-else style="display: flex; align-items: center; justify-content: center; height: 100%; color: var(--muted); font-size: 13px;">暂无数据</div>
            </div>
          </div>
          <div>
            <div class="panel-header">
              <h3 class="panel-title">流量来源</h3>
            </div>
            <div style="display: flex; flex-direction: column; gap: 10px; padding-top: 4px;">
              <div v-for="s in trafficSources" :key="s.name">
                <div style="display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px;">
                  <span style="color: var(--text-2);">{{ s.name }}</span>
                  <span style="font-family: 'JetBrains Mono', monospace; color: var(--text);">{{ s.pct }}%</span>
                </div>
                <div style="height: 6px; background: var(--bg-soft); border-radius: 3px; overflow: hidden;">
                  <div :style="{ height: '100%', width: s.pct + '%', background: s.color, borderRadius: '3px' }"></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Right: todos + status -->
      <div style="display: flex; flex-direction: column; gap: 16px;">
        <div class="panel">
          <div class="panel-header">
            <h3 class="panel-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 11 12 14 22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
              待办清单
            </h3>
            <span class="panel-action">{{ todos.filter(t => typeof t.count === 'number' && t.count > 0).length }} 项</span>
          </div>
          <div class="todo-list">
            <NuxtLink v-for="t in todos" :key="t.title" :to="t.to" class="todo-item" :class="t.type">
              <div class="todo-icon">
                <svg v-if="t.type === 'urgent'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/></svg>
                <svg v-else-if="t.type === 'warning'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 12l2 2 4-4"/><circle cx="12" cy="12" r="10"/></svg>
              </div>
              <div class="todo-info">
                <div class="todo-title">{{ t.title }}</div>
                <div class="todo-meta">{{ t.meta }}</div>
              </div>
              <div class="todo-count">{{ t.count }}</div>
            </NuxtLink>
          </div>
        </div>

        <div class="panel">
          <div class="panel-header">
            <h3 class="panel-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
              站点状态
            </h3>
          </div>
          <div style="display: flex; flex-direction: column; gap: 10px;">
            <div v-for="s in services" :key="s.name" style="display: flex; justify-content: space-between; align-items: center; font-size: 12px;">
              <span style="color: var(--text-2); display: flex; align-items: center; gap: 6px;">
                <span :style="{
                  width: '6px', height: '6px', borderRadius: '50%',
                  background: s.status === 'success' ? 'var(--success)' : s.status === 'warning' ? 'var(--accent)' : 'var(--danger)'
                }"></span>
                {{ s.name }}
              </span>
              <span style="font-family: 'JetBrains Mono', monospace; color: var(--text);">{{ s.value }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Recent posts -->
    <div class="panel" style="margin-top: 4px;">
      <div class="panel-header">
        <h3 class="panel-title">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg>
          最近文章
        </h3>
        <NuxtLink to="/admin/posts" class="panel-action">查看全部 →</NuxtLink>
      </div>
      <div v-if="!recentPosts.length" style="padding: 24px; text-align: center; color: var(--muted); font-size: 13px;">还没有文章</div>
      <table v-else class="recent-table">
        <thead>
          <tr>
            <th>标题</th>
            <th style="width: 90px;">状态</th>
            <th style="width: 90px;">浏览</th>
            <th style="width: 140px;">更新时间</th>
            <th style="width: 60px;"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in recentPosts" :key="a.id">
            <td>
              <div class="recent-title" style="font-weight: 500;">{{ a.title }}</div>
              <div style="font-size: 12px; color: var(--muted); margin-top: 2px;">/{{ a.slug }}</div>
            </td>
            <td><span class="recent-status" :class="a.status === 1 ? 'published' : 'draft'">{{ statusLabel(a.status) }}</span></td>
            <td><span class="recent-meta">{{ (a.viewCount || 0).toLocaleString() }}</span></td>
            <td><span class="recent-meta">{{ formatDateTime(a.updatedAt || a.createdAt) }}</span></td>
            <td><NuxtLink :to="`/admin/edit?id=${a.id}`" style="color: var(--primary); font-size: 12px;">编辑</NuxtLink></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
