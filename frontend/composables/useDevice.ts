/**
 * 设备标识 composable
 * - deviceId: 前端生成 UUID 持久化在 localStorage，跨会话/标签页稳定
 * - deviceName: 从 navigator.userAgent 解析得到友好名（"Safari on macOS"）
 *
 * 用法：在 setup 阶段调 `const { deviceId, deviceName } = useDevice()`，
 * 然后随登录请求 / API 请求一起发出（useApi 已自动带 X-Device-Id header）
 *
 * 登录成功后必须调 `syncDeviceId(res.data.deviceId)` 把后端认可的 deviceId
 * 回写到 localStorage（解决 trust-migrate 通道下前后端 deviceId 不一致的问题）
 */
const DEVICE_ID_KEY = 'blog_admin_device_id'

export const useDevice = () => {
  // 设备 ID：首次访问生成 UUID 并持久化
  const deviceId = ref<string>('')
  const deviceName = ref<string>('')

  if (import.meta.client) {
    // 1. 复用已有 device_id（保持设备身份稳定）
    let id = localStorage.getItem(DEVICE_ID_KEY)
    if (!id) {
      id = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : 'dev-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
      localStorage.setItem(DEVICE_ID_KEY, id)
    }
    deviceId.value = id
    // 2. 解析 UA → 友好设备名
    deviceName.value = parseUA(navigator.userAgent)
  }

  /**
   * 同步后端认可的 deviceId 到本地
   * 场景：登录成功后，后端 trust-migrate 通道可能用了 `legacy-{ts}` 作为 deviceId
   *       这与前端 useDevice 生成的 UUID 不一致，会导致"当前设备"标识不显示
   *       这里把后端响应的 deviceId 写回 localStorage，让前后端一致
   */
  const syncDeviceId = (id: string) => {
    if (!id || !import.meta.client) return
    localStorage.setItem(DEVICE_ID_KEY, id)
    deviceId.value = id
  }

  return { deviceId, deviceName, syncDeviceId }
}

/** 解析 User-Agent → 浏览器 on OS 字符串 */
const parseUA = (ua: string): string => {
  if (!ua) return 'Unknown Device'

  // 浏览器
  let browser = 'Browser'
  if (/Edg\//.test(ua)) browser = 'Edge'
  else if (/OPR\//.test(ua) || /Opera/.test(ua)) browser = 'Opera'
  else if (/Chrome\//.test(ua) && !/Chromium/.test(ua)) browser = 'Chrome'
  else if (/Safari\//.test(ua) && /Version\//.test(ua)) browser = 'Safari'
  else if (/Firefox\//.test(ua)) browser = 'Firefox'

  // 操作系统
  let os = 'Unknown OS'
  if (/Windows NT 10/.test(ua)) os = 'Windows 10/11'
  else if (/Windows NT/.test(ua)) os = 'Windows'
  else if (/Mac OS X/.test(ua) && !/iPhone|iPad/.test(ua)) os = 'macOS'
  else if (/iPhone/.test(ua)) os = 'iOS'
  else if (/iPad/.test(ua)) os = 'iPadOS'
  else if (/Android/.test(ua)) os = 'Android'
  else if (/Linux/.test(ua)) os = 'Linux'

  return `${browser} on ${os}`
}
