<script setup lang="ts">
/**
 * C（2026-06-20）：dashboard.vue 拆分后的主文件。
 * 数据获取/状态留在页面层；图表渲染委托 TrafficChart / CategoryChart 子组件；
 * 工具函数从 useDashboardUtils 引入。
 */
// 2026-06-22 抽出：formatDateTime 改走共享 useMarkdownUtils（避免 5 处实现不一致）
import { fillDays, getThemeColors, statusLabel } from '~/composables/useDashboardUtils'
import { formatDateTime } from '~/composables/useMarkdownUtils'

definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get } = useAdminApi()
const { meta, refresh: refreshMeta } = useAdminMeta()
const { user } = useAuth()

const loading = ref(true)
const kpi = ref<any>({})
// 2026-06-21：dashboard 待办"数据备份"项需要展示"距上次成功备份 N 天"
// 后端 DashboardController 新增 lastBackup: { lastBackupAt, lastBackupTag }
const lastBackup = ref<{ lastBackupAt: string | null; lastBackupTag: string | null } | null>(null)
const categoryDist = ref<any[]>([])
const publishTrend = ref<any[]>([])
const visitTrend = ref<any[]>([])
const visitTrend7 = ref<any[]>([])
const topArticles = ref<any[]>([])
// 2026-06-24 DEV-001：流量来源从硬编码改真实统计（后端按 referer 域名分组）
// 后端返回 [{domain, label, count, percentage}]
const trafficSources = ref<Array<{ domain: string; label: string; count: number; percentage: number }>>([])

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
    visitTrend.value = res.data?.visitTrend || []
    visitTrend7.value = res.data?.visitTrend7 || []
    topArticles.value = res.data?.topArticles || []
    lastBackup.value = res.data?.lastBackup || null
    trafficSources.value = res.data?.trafficSources || []
  } catch {
    kpi.value = {}
  } finally {
    loading.value = false
  }
  await refreshMeta()
  // 2026-06-22：站点状态面板（services）改从 /admin/health 真实拉取
  // 之前 services / trafficSources 全硬编码（见 v4.x 服务日志体系设计前的旧实现）
  await loadHealth()
}

// 2026-06-22：services 从 /admin/health 拉数据库大小 / Redis 内存 / SSL 证书到期 / CDN / uptime
// 设计要点：
// - 错误隔离：后端任一子系统失败不影响其他项
// - 轮询：60s 一次，dashboard 长时间打开不显示陈旧数据
const health = ref<any>(null)
const healthLoading = ref(false)
let healthTimer: ReturnType<typeof setInterval> | null = null
const loadHealth = async () => {
  healthLoading.value = true
  try {
    const res = await get<any>('/admin/health')
    health.value = res.data || null
  } catch {
    health.value = null
  } finally {
    healthLoading.value = false
  }
}

// services 数组由 health 派生（不直接存硬编码）
const services = computed(() => {
  const h = health.value
  if (!h) return []
  const out: Array<{ name: string; status: 'success' | 'warning' | 'danger'; value: string }> = []

  // 0. 版本号
  if (h.version && h.version !== 'unknown') {
    out.push({ name: '版本', status: 'success', value: `v${h.version}` })
  }

  // 1. 服务运行（uptime → "X 天 Y 小时 Z 分钟"）
  if (h.uptimeSec != null && h.uptimeSec >= 0) {
    const d = Math.floor(h.uptimeSec / 86400)
    const h1 = Math.floor((h.uptimeSec % 86400) / 3600)
    const m = Math.floor((h.uptimeSec % 3600) / 60)
    const parts = []
    if (d > 0) parts.push(`${d} 天`)
    if (h1 > 0) parts.push(`${h1} 小时`)
    if (m > 0 || parts.length === 0) parts.push(`${m} 分钟`)
    out.push({ name: '服务运行', status: 'success', value: `正常 · ${parts.join(' ')}` })
  } else {
    out.push({ name: '服务运行', status: 'success', value: '正常' })
  }

  // 2. 数据库
  if (h.database && !h.database.error) {
    const pct = h.database.pct || 0
    const status = pct >= 90 ? 'danger' : pct >= 70 ? 'warning' : 'success'
    out.push({ name: '数据库', status, value: `${h.database.sizeHuman} / ${h.database.maxHuman} (${pct}%)` })
  } else {
    out.push({ name: '数据库', status: 'danger', value: '查询失败' })
  }

  // 3. Redis
  if (h.redis?.connected) {
    const pct = h.redis.pct || 0
    const status = pct >= 90 ? 'danger' : pct >= 70 ? 'warning' : 'success'
    const maxText = h.redis.maxMemoryBytes > 0 ? ` / ${h.redis.maxMemoryHuman}` : ''
    out.push({ name: 'Redis 缓存', status, value: `${h.redis.usedMemoryHuman}${maxText} (${pct}%)` })
  } else if (h.redis) {
    out.push({ name: 'Redis 缓存', status: 'danger', value: '未连接' })
  } else {
    out.push({ name: 'Redis 缓存', status: 'danger', value: '查询失败' })
  }

  // 4. CDN
  out.push({
    name: 'CDN',
    status: h.cdn?.configured ? 'success' : 'warning',
    value: h.cdn?.configured ? '已启用' : '未配置'
  })

  // 5. SSL 证书
  if (h.ssl?.configured && h.ssl.daysLeft != null) {
    const dl = h.ssl.daysLeft
    let status: 'success' | 'warning' | 'danger' = 'success'
    let value = `${dl} 天后到期`
    if (dl < 0)       { status = 'danger'; value = `已过期 ${-dl} 天` }
    else if (dl < 30) { status = 'warning' }
    out.push({ name: 'SSL 证书', status, value })
  } else if (h.ssl?.configured) {
    out.push({ name: 'SSL 证书', status: 'warning', value: '证书不可读' })
  } else {
    out.push({ name: 'SSL 证书', status: 'warning', value: '未配置' })
  }

  return out
})

// 2026-06-21：dashboard 待办"数据备份"项计算"距上次成功备份 N 天"
// lastBackupAt 为 null → "未备份过";否则按 Date.now() - lastBackupAt 算天数
// ≥7 天标 urgent(深色高亮),<7 天标 success
const daysSinceLastBackup = computed(() => {
  if (!lastBackup.value?.lastBackupAt) return null
  const t = new Date(lastBackup.value.lastBackupAt).getTime()
  if (isNaN(t)) return null
  return Math.floor((Date.now() - t) / 86400000)
})

const lastBackupDateText = computed(() => {
  if (!lastBackup.value?.lastBackupAt) return null
  const d = new Date(lastBackup.value.lastBackupAt)
  if (isNaN(d.getTime())) return null
  // YYYY-MM-DD,本地时区
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
})

const todos = computed(() => [
  {
    type: 'urgent',
    title: '待审评论',
    meta: meta.value.pendingComments > 0 ? `需要尽快处理` : '暂无',
    count: meta.value.pendingComments || 0,
    to: '/admin/comments?status=0'
  },
  {
    type: 'warning',
    title: '未发布草稿',
    meta: meta.value.draftCount > 0 ? `${meta.value.draftCount} 篇草稿待处理` : '草稿箱已清空',
    count: meta.value.draftCount || 0,
    to: '/admin/posts?status=0'
  },
  {
    type: (daysSinceLastBackup.value === null || daysSinceLastBackup.value >= 7) ? 'urgent' : 'success',
    title: '数据备份',
    meta: lastBackup.value?.lastBackupAt
      ? `上次备份：${lastBackupDateText.value}${daysSinceLastBackup.value! >= 7 ? `（已 ${daysSinceLastBackup.value} 天）` : ''}`
      : '未备份过',
    count: (daysSinceLastBackup.value === null || daysSinceLastBackup.value >= 7) ? '!' : '✓',
    to: '/admin/backup'
  }
])

const recentPosts = ref<any[]>([])
const loadRecent = async () => {
  try {
    const res = await get<any>('/articles/admin/all', { size: 5 })
    recentPosts.value = res.data?.records || []
  } catch { /* ignore */ }
}

// 2026-06-24 DEV-001：trafficSources 从硬编码改后端真实统计
// - 后端 DashboardController + PageViewService.topReferrers 按 page_view.referer 列分组
// - 返回 [{domain, label, count, percentage}], 上面 loadAll() 填充 trafficSources ref
// - 颜色按位置循环（最多 5 项,顺序对齐 backend Top N）
// 调色板：primary / accent / 蓝 / 紫 / 灰
const trafficColors = ['var(--primary)', 'var(--accent)', '#3b82f6', '#8b5cf6', 'var(--muted)']
const decoratedTrafficSources = computed(() =>
  trafficSources.value.map((s, i) => ({
    name: s.label,
    pct: s.percentage,
    color: trafficColors[i % trafficColors.length]
  }))
)

// sparkline 图表（仍在此层管理，轻量）
let sparkCharts: any[] = []

const trafficChartRef = ref<any>(null)
const categoryChartRef = ref<any>(null)

const renderSparklines = () => {
  if (!import.meta.client) return
  const Chart = (window as any).Chart
  if (!Chart) return
  const c = getThemeColors()
  sparkCharts.forEach(ch => ch.destroy())
  sparkCharts = []
  const sparkData: Record<string, number[]> = {
    'spark-pv':       fillDays(visitTrend7.value, 7, 'pv'),
    'spark-posts':    fillDays(publishTrend.value, 7, 'cnt'),
    'spark-comments': [12, 8, 15, 6, 9, 11, 14, 8, 10, 7, 12, 8, 6, 8].slice(0, 7),
    'spark-words':    fillDays(visitTrend7.value, 7, 'pv')
  }
  const sparkColors: Record<string, string> = {
    'spark-pv': c.primary, 'spark-posts': c.primary,
    'spark-comments': c.accent, 'spark-words': c.primary
  }
  Object.entries(sparkData).forEach(([id, data]) => {
    const el = document.getElementById(id) as any
    if (!el) return
    const ctx = el.getContext('2d')
    const grad = ctx.createLinearGradient(0, 0, 0, 40)
    grad.addColorStop(0, sparkColors[id] + '40')
    grad.addColorStop(1, sparkColors[id] + '00')
    sparkCharts.push(new Chart(ctx, {
      type: 'line',
      data: { labels: data.map((_: any, i: number) => i), datasets: [{ data, borderColor: sparkColors[id], backgroundColor: grad, borderWidth: 1.5, fill: true, tension: 0.4, pointRadius: 0 }] },
      options: { responsive: false, plugins: { legend: { display: false }, tooltip: { enabled: false } }, scales: { x: { display: false }, y: { display: false } } }
    }))
  })
}

onMounted(async () => {
  await loadAll()
  await loadRecent()
  const { default: Chart } = await import('chart.js/auto')
  ;(window as any).Chart = Chart
  trafficChartRef.value?.renderTrend()
  categoryChartRef.value?.renderCategory()
  renderSparklines()
  // 2026-06-22：站点状态面板每 60s 轮询一次 /admin/health
  // 后端只读本地文件 / Redis INFO，无外部依赖，60s 频率完全安全
  if (healthTimer) clearInterval(healthTimer)
  healthTimer = setInterval(loadHealth, 60_000)
})

onBeforeUnmount(() => {
  if (healthTimer) {
    clearInterval(healthTimer)
    healthTimer = null
  }
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
        <div class="kpi-card-label"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg> 今日 PV / UV</div>
        <div class="kpi-card-value">{{ (kpi.todayPV || 0).toLocaleString() }}</div>
        <div class="kpi-card-delta">独立访客 <strong>{{ kpi.todayUV || 0 }}</strong></div>
        <canvas class="kpi-card-spark" id="spark-pv"></canvas>
      </div>
      <div class="kpi-card">
        <div class="kpi-card-label"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg> 总文章</div>
        <NuxtLink to="/admin/posts" class="kpi-card-link">
          <div class="kpi-card-value">{{ kpi.totalArticles || 0 }}</div>
          <div class="kpi-card-delta">+{{ kpi.publishedArticles || 0 }} 已发布 · 查看 →</div>
        </NuxtLink>
        <canvas class="kpi-card-spark" id="spark-posts"></canvas>
      </div>
      <div class="kpi-card">
        <div class="kpi-card-label"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/></svg> 待审评论</div>
        <NuxtLink to="/admin/comments?status=0" class="kpi-card-link">
          <div class="kpi-card-value" :style="{ color: meta.pendingComments > 0 ? 'var(--accent)' : 'var(--text)' }">{{ meta.pendingComments }}</div>
          <div class="kpi-card-delta" :style="{ color: meta.pendingComments > 0 ? 'var(--accent)' : 'var(--success)' }">{{ meta.pendingComments > 0 ? '需要处理 · 去处理 →' : '已全部处理 · 查看 →' }}</div>
        </NuxtLink>
        <canvas class="kpi-card-spark" id="spark-comments"></canvas>
      </div>
      <div class="kpi-card">
        <div class="kpi-card-label"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7V4h16v3M9 20h6M12 4v16"/></svg> 总字数</div>
        <div class="kpi-card-value">{{ (kpi.totalWordCount || 0).toLocaleString() }}</div>
        <div class="kpi-card-delta">已发布文章累计</div>
        <canvas class="kpi-card-spark" id="spark-words"></canvas>
      </div>
    </div>

    <!-- Main grid -->
    <div class="dashboard-grid">
      <div style="display: flex; flex-direction: column; gap: 16px;">
        <!-- 访问趋势图 -->
        <AdminTrafficChart ref="trafficChartRef" :visit-trend="visitTrend" :visit-trend7="visitTrend7" />

        <div class="panel dashboard-split-panel">
          <!-- 分类分布饼图 -->
          <AdminCategoryChart ref="categoryChartRef" :category-dist="categoryDist" />
          <div class="dashboard-traffic">
            <div class="panel-header"><h3 class="panel-title">流量来源</h3></div>
            <div v-if="!decoratedTrafficSources.length" style="padding: 24px 8px; text-align: center; color: var(--muted); font-size: 12px;">
              暂无访问数据
            </div>
            <div v-else class="traffic-list">
              <div v-for="s in decoratedTrafficSources" :key="s.name" class="traffic-row">
                <div class="traffic-row-head">
                  <span class="traffic-name">{{ s.name }}</span>
                  <span class="traffic-pct">{{ s.pct }}%</span>
                </div>
                <div class="traffic-bar">
                  <div class="traffic-bar-fill" :style="{ width: s.pct + '%', background: s.color }"></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div style="display: flex; flex-direction: column; gap: 16px;">
        <div class="panel">
          <div class="panel-header">
            <h3 class="panel-title"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 11 12 14 22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg> 待办清单</h3>
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
          <div class="panel-header"><h3 class="panel-title"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> 站点状态</h3></div>
          <div style="display: flex; flex-direction: column; gap: 10px;">
            <div v-for="s in services" :key="s.name" style="display: flex; justify-content: space-between; align-items: center; font-size: 12px;">
              <span style="color: var(--text-2); display: flex; align-items: center; gap: 6px;">
                <span :style="{ width: '6px', height: '6px', borderRadius: '50%', background: s.status === 'success' ? 'var(--success)' : s.status === 'warning' ? 'var(--accent)' : 'var(--danger)' }"></span>
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
        <h3 class="panel-title"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg> 最近文章</h3>
        <NuxtLink to="/admin/posts" class="panel-action">查看全部 →</NuxtLink>
      </div>
      <div v-if="!recentPosts.length" style="padding: 24px; text-align: center; color: var(--muted); font-size: 13px;">还没有文章</div>
      <table v-else class="recent-table">
        <thead><tr><th class="col-title">标题</th><th class="col-status" style="width: 90px;">状态</th><th class="col-views" style="width: 90px;">浏览</th><th class="col-time" style="width: 140px;">更新时间</th><th class="col-action" style="width: 60px;"></th></tr></thead>
        <tbody>
          <tr v-for="a in recentPosts" :key="a.id">
            <td class="col-title">
              <div class="recent-title" style="font-weight: 500;">{{ a.title }}</div>
              <div style="font-size: 12px; color: var(--muted); margin-top: 2px;">
                /{{ a.slug }}<span class="recent-views-inline">{{ (a.viewCount || 0).toLocaleString() }} 次浏览</span>
              </div>
            </td>
            <td class="col-status"><span class="recent-status" :class="a.status === 1 ? 'published' : 'draft'">{{ statusLabel(a.status) }}</span></td>
            <td class="col-views"><span class="recent-meta">{{ (a.viewCount || 0).toLocaleString() }}</span></td>
            <td class="col-time"><span class="recent-meta">{{ formatDateTime(a.updatedAt || a.createdAt) }}</span></td>
            <td class="col-action"><NuxtLink :to="`/admin/edit?id=${a.id}`" style="color: var(--primary); font-size: 12px;">编辑</NuxtLink></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
