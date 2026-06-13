/**
 * 认证状态管理
 * - token 存 localStorage + cookie（双写：cookie 让 SSR 也能读到，admin 接口在 SSR 可带 token）
 * - userInfo 存 sessionStorage（防止刷新闪烁）
 */
const TOKEN_KEY = 'blog_admin_token'
const COOKIE_KEY = 'admin_token'
const USER_KEY = 'blog_admin_user'

export interface AdminUser {
  uid: number
  username: string
  role: string
  // 2026-06-12 新增：nickname / avatar 在登录响应里已经返回，但原 AdminUser 没存，
  // 导致后台顶栏用 username 兜底显示 + 头像位永远是首字母圈，
  // 而且设置页改了 nickname/avatar 之后也没地方刷新登录态，必须重登才能生效。
  nickname?: string
  avatar?: string
}

// 从 cookie 字符串里解析 token
const readTokenFromCookie = (raw: string | undefined | null): string | null => {
  if (!raw) return null
  const m = raw.match(new RegExp(`(?:^|;\\s*)${COOKIE_KEY}=([^;]+)`))
  return m ? decodeURIComponent(m[1]) : null
}

export const useAuth = () => {
  const user = useState<AdminUser | null>('auth-user', () => null)
  const token = useState<string | null>('auth-token', () => null)

  // SSR 阶段：从 request headers 读 cookie 恢复 token
  // ——这样 admin 接口在 SSR 也能带 token，前台首屏直接渲染真数据，没有"先默认后真实"的闪烁
  if (import.meta.server) {
    const headers = useRequestHeaders(['cookie'])
    if (!token.value) {
      token.value = readTokenFromCookie(headers.cookie)
    }
  }

  // 初始化：client 阶段从 localStorage 恢复（cookie 只在 client 写，localStorage 是兜底）
  const init = () => {
    if (import.meta.client) {
      const t = localStorage.getItem(TOKEN_KEY)
      const u = sessionStorage.getItem(USER_KEY)
      if (t && !token.value) token.value = t
      if (u) {
        try { user.value = JSON.parse(u) } catch { /* ignore */ }
      }
    }
  }

  const setSession = (t: string, u: AdminUser) => {
    token.value = t
    user.value = u
    if (import.meta.client) {
      localStorage.setItem(TOKEN_KEY, t)
      sessionStorage.setItem(USER_KEY, JSON.stringify(u))
      // 同步到 cookie（7 天有效；SameSite=Lax 防止 CSRF）
      // 2026-06-12 修复：原 cookie 缺 Secure 标志。生产 HTTPS 部署下，cookie 在 HTTP 降级时
      // 可能被明文传输（攻击者引导用户访问 http://yourname.com/... 窃取 token）。
      // 修复：HTTPS 页面写 cookie 时附加 Secure，HTTP（dev / 本地）不加（否则 dev 浏览器会拒收）。
      const maxAge = 7 * 24 * 60 * 60
      const secureFlag = location.protocol === 'https:' ? '; Secure' : ''
      document.cookie = `${COOKIE_KEY}=${encodeURIComponent(t)}; path=/; max-age=${maxAge}; SameSite=Lax${secureFlag}`
    }
  }

  const clear = () => {
    token.value = null
    user.value = null
    if (import.meta.client) {
      localStorage.removeItem(TOKEN_KEY)
      sessionStorage.removeItem(USER_KEY)
      document.cookie = `${COOKIE_KEY}=; path=/; max-age=0; SameSite=Lax`
    }
  }

  /**
   * 2026-06-12 新增：admin 在「设置 → 个人资料」改了 nickname/avatar 之后，
   * 调用 updateUser({nickname, avatar}) 直接把 useState 的 user 改了 +
   * 同步刷 sessionStorage，下一次组件读 user.nickname/user.avatar 立刻看到新值，
   * 不用退出重登。
   */
  const updateUser = (partial: Partial<AdminUser>) => {
    if (!user.value) return
    user.value = { ...user.value, ...partial }
    if (import.meta.client) {
      sessionStorage.setItem(USER_KEY, JSON.stringify(user.value))
    }
  }

  const isLoggedIn = computed(() => !!token.value && !!user.value)

  return { user, token, isLoggedIn, init, setSession, clear, updateUser }
}
