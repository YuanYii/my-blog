/**
 * 站点设置更新事件总线（2026-07-01 DEV-006）
 *
 * 用途：高级 tab 上传 md 文档成功后，4 个 settings 子页（profile / blog / techstack / experience）
 * 收到事件后调用自己的 load() 拉取最新数据，无需手动刷页。
 *
 * 设计要点：
 *  - 简单 observer 模式，不引 mitt 依赖（与项目"不引小依赖"原则一致）
 *  - 模块级单例，所有调用方共享同一个订阅列表
 *  - 事件类型固定为 'settings-updated'，payload 为 { sections: string[] }（已应用的段名列表）
 *
 * 用法：
 *  - 发布：useSettingsEventBus().publish('settings-updated', { sections: ['profile', 'blog', ...] })
 *  - 订阅：const bus = useSettingsEventBus(); bus.on('settings-updated', (payload) => load())
 *  - 取消：onUnmounted(() => bus.off('settings-updated', handler))
 */

export type SettingsEventType = 'settings-updated'
export type SettingsEventPayload = { sections: string[] }

type Handler = (payload: SettingsEventPayload) => void

// 模块级单例（避免每次调用重新创建订阅列表）
const subscribers: Map<SettingsEventType, Set<Handler>> = new Map()

export const useSettingsEventBus = () => {
  const on = (event: SettingsEventType, handler: Handler) => {
    if (!subscribers.has(event)) subscribers.set(event, new Set())
    subscribers.get(event)!.add(handler)
    // 返回 unsubscribe 函数，便于调用方在 onUnmounted 里清理
    return () => off(event, handler)
  }

  const off = (event: SettingsEventType, handler: Handler) => {
    subscribers.get(event)?.delete(handler)
  }

  const publish = (event: SettingsEventType, payload: SettingsEventPayload) => {
    subscribers.get(event)?.forEach((h) => {
      try {
        h(payload)
      } catch (e) {
        // 订阅方错误不影响发布方
        // eslint-disable-next-line no-console
        console.error('[settings-event-bus] handler error', e)
      }
    })
  }

  return { on, off, publish }
}
