<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, put, upload } = useAdminApi()
const { updateUser } = useAuth()

const activeTab = ref('profile')
const saving = ref(false)
const message = ref('')

const passwordFormRef = ref<{ onSuccess: () => void; onError: (msg: string) => void } | null>(null)

const tabs = [
  { id: 'profile',     label: '个人资料', icon: '👤' },
  { id: 'password',    label: '修改密码', icon: '🔒' },
  { id: 'blog',        label: '站点信息', icon: '✍️' },
  { id: 'techstack',   label: '技术栈',   icon: '🧰' },
  { id: 'experience',  label: '个人经历', icon: '📜' },
  { id: 'theme',       label: '主题外观', icon: '🎨' },
  { id: 'social',      label: '社交账号', icon: '🌐' },
  { id: 'preferences', label: '偏好设置', icon: '⚙️' },
  { id: 'advanced',    label: '高级',     icon: '🔧' }
]

const profile = reactive({ nickname: '', email: '', bio: '', location: '', avatar: '' })
const blog = reactive({ title: '', subtitle: '', description: '', copyright: '', logo: '' })
const theme = reactive({ mode: 'auto', primaryColor: '#2f6f5e', accentColor: '#c97b3f', fontFamily: 'serif' })
const social = reactive({ github: '', twitter: '', emailPublic: '', wechat: '', weibo: '', rss: '' })
const prefs = reactive({ language: 'zh-CN', timezone: 'Asia/Shanghai', density: 'comfortable', codeTheme: 'github' })
const techstack = reactive<{ groups: { label: string; items: { name: string; dim: boolean }[] }[] }>({ groups: [] })
const experience = reactive<{ items: { time: string; title: string; desc: string }[] }>({ items: [] })
const advanced = reactive({ enableCache: true, enableRss: true, enableSearch: true, enableCommentModeration: true })

const loadAll = async () => {
  try {
    const [p, b, t, s, pr, a, ts, ex] = await Promise.all([
      get<any>('/admin/settings/profile'),
      get<any>('/admin/settings/blog'),
      get<any>('/admin/settings/theme'),
      get<any>('/admin/settings/social'),
      get<any>('/admin/settings/preferences'),
      get<any>('/admin/settings/advanced'),
      get<any>('/admin/settings/techstack'),
      get<any>('/admin/settings/experience')
    ])
    if (p.data) Object.assign(profile, p.data)
    if (b.data) Object.assign(blog, b.data)
    if (t.data) Object.assign(theme, t.data)
    if (s.data) Object.assign(social, s.data)
    if (pr.data) Object.assign(prefs, pr.data)
    if (a.data) Object.assign(advanced, a.data)
    techstack.groups = Array.isArray(ts.data?.groups) ? ts.data.groups : []
    experience.items = Array.isArray(ex.data?.items) ? ex.data.items : []
  } catch { /* 默认值 */ }
}

const save = async () => {
  saving.value = true
  message.value = ''
  try {
    const map: Record<string, { endpoint: string; data: any }> = {
      profile:     { endpoint: '/admin/settings/profile',     data: profile },
      blog:        { endpoint: '/admin/settings/blog',        data: blog },
      theme:       { endpoint: '/admin/settings/theme',       data: theme },
      social:      { endpoint: '/admin/settings/social',      data: social },
      preferences: { endpoint: '/admin/settings/preferences', data: prefs },
      advanced:    { endpoint: '/admin/settings/advanced',    data: advanced },
      techstack:   { endpoint: '/admin/settings/techstack',   data: { groups: techstack.groups } },
      experience:  { endpoint: '/admin/settings/experience',  data: { items: experience.items } }
    }
    const target = map[activeTab.value]
    if (target) {
      await put(target.endpoint, target.data)
      if (activeTab.value === 'profile') updateUser({ nickname: profile.nickname, avatar: profile.avatar })
      message.value = '已保存 ✓'
    }
    setTimeout(() => (message.value = ''), 2000)
  } catch (e: any) {
    message.value = '保存失败：' + (e?.data?.message || e?.message)
  } finally {
    saving.value = false
  }
}

const handlePasswordChange = async (payload: { oldPassword: string; newPassword: string; confirmPassword: string }) => {
  try {
    await put('/auth/me/password', payload)
    passwordFormRef.value?.onSuccess()
  } catch (e: any) {
    passwordFormRef.value?.onError(e?.data?.message || e?.message)
  }
}

const uploadFn = (path: string, file: File) => upload<any>(path, file)

onMounted(loadAll)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>站点设置</h1>
        <p>管理你的个人资料、站点信息和主题外观</p>
      </div>
    </div>

    <div class="settings-layout">
      <nav class="settings-tabs">
        <button v-for="t in tabs" :key="t.id" @click="activeTab = t.id"
          class="settings-tab" :class="{ active: activeTab === t.id }">
          <span style="font-size: 14px;">{{ t.icon }}</span>
          {{ t.label }}
        </button>
      </nav>

      <div>
        <AdminSettingsProfileForm
          v-if="activeTab === 'profile'"
          :profile="profile"
          :upload="uploadFn"
          @update:profile="Object.assign(profile, $event)"
        />
        <AdminSettingsPasswordForm
          v-else-if="activeTab === 'password'"
          ref="passwordFormRef"
          @change="handlePasswordChange"
        />
        <AdminSettingsBlogForm
          v-else-if="activeTab === 'blog'"
          :blog="blog"
          @update:blog="Object.assign(blog, $event)"
        />
        <AdminSettingsTechstackForm
          v-else-if="activeTab === 'techstack'"
          :techstack="techstack"
          @update:techstack="techstack.groups = $event.groups"
        />
        <AdminSettingsExperienceForm
          v-else-if="activeTab === 'experience'"
          :experience="experience"
          @update:experience="experience.items = $event.items"
        />
        <AdminSettingsThemeForm
          v-else-if="activeTab === 'theme'"
          :theme="theme"
          @update:theme="Object.assign(theme, $event)"
        />
        <AdminSettingsSocialForm
          v-else-if="activeTab === 'social'"
          :social="social"
          @update:social="Object.assign(social, $event)"
        />
        <AdminSettingsPreferencesForm
          v-else-if="activeTab === 'preferences'"
          :prefs="prefs"
          @update:prefs="Object.assign(prefs, $event)"
        />
        <AdminSettingsAdvancedForm
          v-else
          :advanced="advanced"
          @update:advanced="Object.assign(advanced, $event)"
        />

        <div v-if="activeTab !== 'password'" style="display: flex; align-items: center; gap: 12px; margin-top: 16px;">
          <button @click="save" :disabled="saving" class="btn btn-primary">
            {{ saving ? '保存中…' : '保存设置' }}
          </button>
          <span v-if="message" :style="{ color: message.includes('失败') ? 'var(--danger)' : 'var(--success)', fontSize: '13px' }">{{ message }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
