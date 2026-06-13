<script setup lang="ts">
definePageMeta({ layout: false })

const router = useRouter()
const { setSession, isLoggedIn } = useAuth()
const { post } = useAdminApi()
const { deviceId, deviceName, syncDeviceId } = useDevice()

// 2026-06-13 修复（BUG-078）：仅 dev 模式预填默认账密方便本地登录，
// prod 构建会跳过 → 用户必须手输（防占位凭证泄漏到生产）。
// 注：import.meta 必须在 <script setup> 里求值（不能放 Vue 模板里 → Vite 编译错）。
const form = reactive({
  username: import.meta.dev ? 'admin' : '',
  password: import.meta.dev ? '123456' : ''
})
const isDev = import.meta.dev
const loading = ref(false)
const error = ref('')
const pendingDevice = ref<string>('')  // 待授权设备的友好名（PENDING 2001 时用）

onMounted(() => {
  if (isLoggedIn.value) router.replace('/admin/dashboard')
})

const handleSubmit = async () => {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  pendingDevice.value = ''
  try {
    // 带 deviceId + deviceName 一起发，后端走白名单校验
    const res = await post<any>('/auth/login', {
      ...form,
      deviceId: deviceId.value,
      deviceName: deviceName.value
    })
    // 把后端认可的 deviceId 回写到 localStorage（trust-migrate 通道下前后端 deviceId 必须一致）
    if (res.data?.deviceId) {
      syncDeviceId(res.data.deviceId)
    }
    // 2026-06-12 修复：原来只存 uid/username/role，nickname 和 avatar 从登录响应里被丢掉，
    // 后台顶栏因此只能用 username 显示用户名 + 头像永远是首字母圈。
    // 后端 AuthController.login 已经返回了 nickname / avatar，前端一并存下。
    setSession(res.data.token, {
      uid: res.data.uid,
      username: res.data.username,
      role: res.data.role,
      nickname: res.data.nickname,
      avatar: res.data.avatar
    })
    router.push('/admin/dashboard')
  } catch (e: any) {
    const code = e?.data?.code
    const msg = e?.data?.message || '登录失败，请检查账号密码'
    if (code === 2001) {
      // 设备未授权（带状态块 + 设备名 + 首次部署指引）
      pendingDevice.value = deviceName.value
      error.value = ''
    } else if (code === 2002) {
      // 设备被禁止登录
      pendingDevice.value = ''
      error.value = '当前设备已被禁止登录'
    } else {
      error.value = msg
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-logo">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
        <span>Yuan Yi</span>
      </div>
      <h1 class="auth-title">欢迎回来 👋</h1>
      <p class="auth-subtitle">登录后台管理你的博客</p>

      <form @submit.prevent="handleSubmit">
        <div class="form-group">
          <label class="form-label">用户名</label>
          <input v-model="form.username" type="text" class="form-control" placeholder="admin" autocomplete="username" />
        </div>
        <div class="form-group">
          <label class="form-label">密码</label>
          <input v-model="form.password" type="password" class="form-control" placeholder="••••••" autocomplete="current-password" />
        </div>

        <!-- 设备未授权状态块（黄色 + ⏳ + 设备名 + 首次部署指引） -->
        <div v-if="pendingDevice" style="background: var(--accent); color: white; font-size: 13px; padding: 12px 14px; border-radius: 10px; margin-bottom: 12px; line-height: 1.6;">
          <div style="font-weight: 600; margin-bottom: 4px;">⏳ 设备未授权</div>
          <div style="opacity: 0.95;">设备「<strong>{{ pendingDevice }}</strong>」未授权，请联系管理员在「设备管理 → 待授权」中批准后再次登录。</div>
          <div style="opacity: 0.85; margin-top: 6px; font-size: 12px;">💡 首次部署？如尚无已授权设备，请通过 <code style="background: rgba(255,255,255,0.15); padding: 1px 5px; border-radius: 3px;">SSH + MySQL</code> 直接将该设备 status 置为 <code style="background: rgba(255,255,255,0.15); padding: 1px 5px; border-radius: 3px;">approved</code>。</div>
        </div>

        <div v-if="error" style="color: var(--danger); font-size: 13px; margin-bottom: 12px;">{{ error }}</div>

        <button type="submit" class="btn btn-primary" :disabled="loading" style="width: 100%; justify-content: center; padding: 10px;">
          <span v-if="loading">登录中…</span>
          <span v-else>登 录</span>
        </button>
      </form>

      <div v-if="isDev" style="margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line-soft); text-align: center; font-size: 12px; color: var(--muted);">
        <!-- 2026-06-13 修复（BUG-078）：默认账密提示仅 dev 环境展示，prod 构建被 tree-shake 掉。
             占位 admin/123456 见 AGENTS.md §9，生产部署必须改密（参见 docs/阿里云部署方案.md）。 -->
        默认账号 <code style="font-family: 'JetBrains Mono', monospace; color: var(--primary);">admin</code> / 密码 <code style="font-family: 'JetBrains Mono', monospace; color: var(--primary);">123456</code>
      </div>

      <div style="text-align: center; margin-top: 12px;">
        <NuxtLink to="/" style="font-size: 12px; color: var(--muted);">← 返回博客首页</NuxtLink>
      </div>
    </div>
  </div>
</template>
