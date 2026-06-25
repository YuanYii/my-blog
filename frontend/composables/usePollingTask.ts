/**
 * 跨组件轮询任务池（composable）
 *
 * 设计目的：
 *  - 解决「组件卸载时 setTimeout 闭包持有的组件 ref 已被释放，UI 状态丢失」+ 「切换页面后轮询真的停了」两个 race。
 *  - 解决「localStorage 在组件 catch 分支被激进清掉，导致下次 onMounted 无法恢复」race。
 *
 * 核心不变量：
 *  1. localStorage 是 state-of-truth（单一权威源）
 *  2. 定时器放模块作用域，不挂在任何组件实例上 → 组件卸载不丢
 *  3. 组件只注册 UI 订阅，不直接读写 localStorage / 定时器
 *  4. start(id) 幂等（同一 id 不重启；不同 id 才重启）
 *  5. 终态 SUCCESS / FAILED / UNKNOWN 自动 stop + 清 localStorage
 *
 * 用法：
 *   const task = usePollingTask('backup')
 *
 *   task.subscribe((state) => {  // 订阅状态变化，每次 tick 都回调
 *     if (state.status === 'SUCCESS') $toast.success(...)
 *   })
 *
 *   await task.start(recordId, async () => {  // 启动轮询
 *     const res = await get(`/admin/backup/${id}`)
 *     return res?.data  // { status, ... }
 *   }, { terminalStatuses: ['SUCCESS', 'FAILED'], initialInterval: 2000 })
 *
 *   task.restore()  // onMounted 调用，从 localStorage 自动恢复
 *
 *   task.stop()     // 主动停止（一般用不到，组件卸载会自动解绑订阅）
 *
 * 2026-06-22 v4.3.0 polish：新增
 */
import { ref, onScopeDispose, type Ref } from 'vue'

export interface PollingTaskState {
  /** 当前轮询的 record id, null = 未轮询 */
  id: number | null
  /** 当前状态字符串, null = 未轮询 */
  status: string | null
  /** 任意附加数据（备份: tag/dbSize...; 恢复: sourceTag/scope...） */
  data: any | null
}

export interface PollingTaskOptions {
  /** 终态集合 — 这些状态触发后自动 stop + 清 localStorage */
  terminalStatuses: string[]
  /** 起始轮询间隔 (ms), 失败指数退避 (上限 30s) */
  initialInterval?: number
  /** 退避上限 (ms) */
  maxInterval?: number
  /** 连续失败多少次放弃轮询 (清 localStorage + 解绑) */
  maxFailures?: number
  /** localStorage key, 默认 `${key}PollingId` */
  storageKey?: string
}

type Fetcher = () => Promise<PollingTaskState | null>
type Listener = (state: PollingTaskState) => void

interface PoolEntry {
  state: Ref<PollingTaskState>
  listeners: Set<Listener>
  fetcher: Fetcher | null
  options: PollingTaskOptions
  timer: ReturnType<typeof setTimeout> | null
  failures: number
  interval: number
  /** 已通知过的终态（防多个订阅者重复弹 Toast） */
  lastTerminalNotified: string | null
}

// 模块作用域单例池 — 整个 SPA 共享
const pool = new Map<string, PoolEntry>()

const getEntry = (key: string, storageKey: string): PoolEntry => {
  let entry = pool.get(key)
  if (!entry) {
    entry = {
      state: ref<PollingTaskState>({ id: null, status: null, data: null }),
      listeners: new Set(),
      fetcher: null,
      options: { terminalStatuses: [], storageKey },
      timer: null,
      failures: 0,
      interval: 2000,
      lastTerminalNotified: null
    }
    // 启动时从 localStorage 还原 id（不是 status, status 要重新拉）
    if (import.meta.client) {
      const saved = localStorage.getItem(storageKey)
      if (saved) {
        const id = parseInt(saved, 10)
        if (!isNaN(id)) {
          entry.state.value.id = id
        }
      }
    }
    pool.set(key, entry)
  }
  return entry
}

const clearTimer = (entry: PoolEntry) => {
  if (entry.timer) {
    clearTimeout(entry.timer)
    entry.timer = null
  }
}

const notify = (entry: PoolEntry, state: PollingTaskState) => {
  entry.state.value = state
  for (const fn of entry.listeners) {
    try {
      fn(state)
    } catch (e) {
      // 单个订阅者抛错不影响其他订阅者
      console.error('[usePollingTask] listener error:', e)
    }
  }
}

/**
 * 获取/创建一个轮询任务
 * @param key 任务 key（如 'backup' / 'restore'），决定 localStorage 命名空间
 */
export const usePollingTask = (key: string) => {
  const storageKey = `${key}PollingId`
  const entry = getEntry(key, storageKey)

  /** 启动（或复用）轮询 */
  const start = async (
    id: number,
    fetcher: Fetcher,
    options: PollingTaskOptions
  ): Promise<void> => {
    // 幂等: 同一 id 已经在跑就不重启
    if (entry.state.value.id === id && entry.timer) {
      // 只更新 options/fetcher（防止回调变了）
      entry.fetcher = fetcher
      entry.options = { ...options, storageKey }
      return
    }

    // 切到新任务前先清掉旧的
    clearTimer(entry)
    entry.fetcher = fetcher
    entry.options = { ...options, storageKey }
    entry.failures = 0
    entry.interval = options.initialInterval ?? 2000
    entry.lastTerminalNotified = null  // 新任务重置"已通知"标记

    // 立即把 id 落 localStorage, 状态先标 null, 等第一次 tick 填
    if (import.meta.client) {
      localStorage.setItem(storageKey, id.toString())
    }
    notify(entry, { id, status: entry.state.value.status, data: null })

    // 首次立即 tick
    await tick(entry)
    schedule(entry)
  }

  /** 从 localStorage 自动恢复轮询（onMounted 调用） */
  const restore = async (fetcher: Fetcher, options: PollingTaskOptions): Promise<void> => {
    if (!import.meta.client) return
    if (entry.state.value.id === null) return  // localStorage 空
    if (entry.timer) return  // 已经在跑
    entry.fetcher = fetcher
    entry.options = { ...options, storageKey }
    entry.failures = 0
    entry.interval = options.initialInterval ?? 2000
    entry.lastTerminalNotified = null  // 从 localStorage 恢复时也重置（start() 已处理过）
    await tick(entry)
    schedule(entry)
  }

  /** 主动停止轮询 + 清 localStorage */
  const stop = (): void => {
    clearTimer(entry)
    entry.failures = 0
    if (import.meta.client) {
      localStorage.removeItem(storageKey)
    }
    notify(entry, { id: null, status: null, data: null })
  }

  /** 订阅状态变化, 返回解绑函数
 *
 * 注意：必须在 setup() 顶层调用（不要在 onMounted 等回调内），onScopeDispose 才能正确触发。
 * 不在 effect scope 内调用时也能用（手动管理返回的解绑函数即可）。
 */
  const subscribe = (fn: Listener): (() => void) => {
    entry.listeners.add(fn)
    // 立即用当前状态通知一次, 方便 UI 立刻渲染
    if (entry.state.value.id !== null) {
      try { fn(entry.state.value) } catch (e) { /* ignore */ }
    }
    // 在组件 scope 内调用时，组件卸载自动解绑（避免内存泄漏）
    try {
      onScopeDispose(() => { entry.listeners.delete(fn) })
    } catch {
      // 不在 effect scope 内调用 — 返回的手动解绑函数兜底
    }
    return () => { entry.listeners.delete(fn) }
  }

  /** 响应式只读状态 — 用于模板里直接渲染 */
  const state = entry.state as Readonly<Ref<PollingTaskState>>

  /** 当前是否在轮询中 */
  const isPolling = (): boolean => entry.timer !== null

  return {
    state,
    start,
    restore,
    stop,
    subscribe,
    isPolling
  }
}

// ============= 内部: 调度逻辑 =============

const tick = async (entry: PoolEntry): Promise<void> => {
  if (!entry.fetcher || entry.state.value.id === null) return
  try {
    const result = await entry.fetcher()
    entry.failures = 0
    entry.interval = entry.options.initialInterval ?? 2000
    if (!result) {
      // 记录不存在（可能被 wipe），停止轮询 + 清 localStorage
      stopEntry(entry)
      return
    }
    const newState: PollingTaskState = {
      id: entry.state.value.id,
      status: result.status,
      data: result
    }

    // 终态检测：第一次进终态时正常 notify（订阅者拿终态弹 Toast），标记已通知
    // 第二次（多个订阅者都看到）就不再 notify（防 Toast 重复弹）
    const isTerminal = result.status && entry.options.terminalStatuses.includes(result.status)
    if (isTerminal && entry.lastTerminalNotified === result.status) {
      // 已知终态 — 不通知, 但仍 stop + 清 localStorage
      stopEntry(entry)
      return
    }

    notify(entry, newState)

    // 终态 → 标记已通知 + stop + 清 localStorage
    if (isTerminal) {
      entry.lastTerminalNotified = result.status
      stopEntry(entry)
    }
  } catch (e: any) {
    // 不再激进清 localStorage — 让 restore() 在下次 onMounted 重试
    entry.failures++
    const maxInterval = entry.options.maxInterval ?? 30000
    entry.interval = Math.min(entry.interval * 2, maxInterval)

    const maxFailures = entry.options.maxFailures ?? 60
    if (entry.failures > maxFailures) {
      console.warn(
        `[usePollingTask:${storageKeyHint(entry)}] 连续失败 ${entry.failures} 次, 放弃轮询 (id=${entry.state.value.id})`
      )
      stopEntry(entry)
    }
  }
}

const schedule = (entry: PoolEntry) => {
  if (entry.state.value.id === null) return
  entry.timer = setTimeout(async () => {
    await tick(entry)
    if (entry.state.value.id !== null) schedule(entry)
  }, entry.interval)
}

const stopEntry = (entry: PoolEntry) => {
  clearTimer(entry)
  entry.failures = 0
  entry.lastTerminalNotified = null  // 下次 start 时重置
  if (import.meta.client && entry.options.storageKey) {
    localStorage.removeItem(entry.options.storageKey)
  }
  notify(entry, { id: null, status: null, data: null })
}

const storageKeyHint = (entry: PoolEntry): string => {
  return entry.options.storageKey || 'unknown'
}