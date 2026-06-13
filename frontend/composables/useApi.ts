/**
 * 旧 useApi() 兼容层——已迁移到 usePublicApi / useAdminApi
 * 详见 ./usePublicApi.ts 和 ./useAdminApi.ts
 *
 * 2026-06-13 修复：原 useApi.ts 同时 export { usePublicApi, useAdminApi }，
 * 跟 composables/usePublicApi.ts / useAdminApi.ts 冲突，Nuxt auto-import 报
 *   "Duplicated imports" WARN + 偶发 Vite 500（macro 解析失败）。
 * 修复：useApi.ts 只 export useApi 别名，不再 reexport 同名函数。
 */
import { useAdminApi } from './useAdminApi'

// 兼容旧调用：useApi() 等价 useAdminApi()（带 Authorization + X-Device-Id）
export const useApi = () => useAdminApi()
