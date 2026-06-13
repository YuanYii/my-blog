/**
 * 后台全局状态：侧边栏 badge 用的文章数/待审评论数
 * - 在 admin layout 挂载时拉一次
 * - 各页通过 useAdminMeta() 共享
 */
export interface AdminMeta {
  articleCount: number
  pendingComments: number
  draftCount: number
}

export const useAdminMeta = () => {
  const meta = useState<AdminMeta>('admin-meta', () => ({
    articleCount: 0,
    pendingComments: 0,
    draftCount: 0
  }))
  const { get } = useApi()

  const refresh = async () => {
    if (!import.meta.client) return
    try {
      // 一次拉两个：dashboard（含 KPI）和待审评论列表
      const [dash, pending] = await Promise.all([
        get<any>('/admin/dashboard'),
        get<any>('/comments/admin', { status: 0 })
      ])
      const kpi = dash.data?.kpi || {}
      // 2026-06-12 修复：pendingComments 原来用 `records.length` 取页内条数，
      // 默认 size=20，超过 20 条待审时侧边栏永远显示 20（实际可能是 50/100）。
      // PageResult.total 才是 DB 真实总数；优先用 total，缺失时再 fallback 到 records.length。
      // dashboard.kpi.pendingComments 也是 DB 真实总数（COUNT(*)），可以二级 fallback。
      const pendingTotal = pending.data?.total
        ?? pending.data?.records?.length
        ?? kpi.pendingComments
        ?? 0
      meta.value = {
        articleCount: kpi.totalArticles || 0,
        draftCount: kpi.draftArticles || 0,
        pendingComments: pendingTotal
      }
    } catch { /* 静默 */ }
  }

  return { meta, refresh }
}
