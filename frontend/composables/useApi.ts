/**
 * 旧 useApi() 兼容层——已迁移到 usePublicApi / useAdminApi
 * 详见 ./usePublicApi.ts 和 ./useAdminApi.ts
 *
 * 历史：
 * - 2026-06-13 修复：原 useApi.ts 同时 export { usePublicApi, useAdminApi }，
 *   跟 composables/usePublicApi.ts / useAdminApi.ts 冲突，Nuxt auto-import 报
 *   "Duplicated imports" WARN + 偶发 Vite 500（macro 解析失败）。
 *   修复：useApi.ts 只 export useApi 别名，不再 reexport 同名函数。
 *
 * - 2026-06-18 修复：useApi.ts 不再显式 `import { useAdminApi } from './useAdminApi'`，
 *   改靠 Nuxt auto-import 解析。Nuxt 3 的 composables 必须自包含（不互相 import），
 *   互相 import 会破坏 auto-import 的扫描顺序，导致 Vite 编译出的 setup 函数里
 *   部分 composable（比如 usePublicApi）没被注入，运行时 ReferenceError。
 *
 * 调用方：
 * - useAdminMeta.ts（后台顶栏 badge）→ useApi() 应等价 useAdminApi()（带 token）
 */

// 兼容旧调用：useApi() 等价 useAdminApi()（带 Authorization + X-Device-Id）
// 注意：useAdminApi 由 Nuxt auto-import 注入，不要写 import 语句。
export const useApi = () => useAdminApi()
