<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, put, upload } = useAdminApi()
// 2026-06-12 新增：拿到 updateUser，profile 保存成功后立即把顶栏的 nickname/avatar 同步刷新
const { updateUser } = useAuth()
const avatarUploading = ref(false)
const avatarInput = ref<HTMLInputElement | null>(null)

const handleAvatarClick = () => avatarInput.value?.click()

const handleAvatarChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  avatarUploading.value = true
  try {
    const res = await upload<any>('/admin/uploads', file)
    if (res.data?.url) {
      profile.avatar = res.data.url
    }
  } catch (err: any) {
    alert('上传失败：' + (err?.data?.message || err?.message))
  } finally {
    avatarUploading.value = false
    input.value = ''
  }
}

const activeTab = ref('profile')
const saving = ref(false)
const message = ref('')

const tabs = [
  { id: 'profile',     label: '个人资料', icon: '👤' },
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
// 2026-06-13 新增：技术栈 / 个人经历（维护 about 页面）
const techstack = reactive<{ groups: { label: string; items: { name: string; dim: boolean }[] }[] }>({ groups: [] })
const experience = reactive<{ items: { time: string; title: string; desc: string }[] }>({ items: [] })
const advanced = reactive({ enableCache: true, enableRss: true, enableSearch: true, commentModeration: true })

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
  } catch { /* 默认 */ }
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
      // 2026-06-12 修复：保存 profile 时同步把 useAuth 里的 user.nickname/avatar 也更新，
      // 这样后台顶栏立即看到新值，不用退出重登。
      if (activeTab.value === 'profile') {
        updateUser({ nickname: profile.nickname, avatar: profile.avatar })
      }
      message.value = '已保存 ✓'
    }
    setTimeout(() => message.value = '', 2000)
  } catch (e: any) {
    message.value = '保存失败：' + (e?.data?.message || e?.message)
  } finally {
    saving.value = false
  }
}

// ===== 技术栈编辑器操作 =====
const addTechGroup = () => techstack.groups.push({ label: '', items: [] })
const removeTechGroup = (gi: number) => techstack.groups.splice(gi, 1)
const addTechItem = (gi: number) => techstack.groups[gi].items.push({ name: '', dim: false })
const removeTechItem = (gi: number, ii: number) => techstack.groups[gi].items.splice(ii, 1)

// ===== 个人经历编辑器操作 =====
const addExperience = () => experience.items.push({ time: '', title: '', desc: '' })
const removeExperience = (i: number) => experience.items.splice(i, 1)
const moveExperience = (i: number, dir: -1 | 1) => {
  const j = i + dir
  if (j < 0 || j >= experience.items.length) return
  const arr = experience.items
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

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
      <!-- 左侧 Tab -->
      <nav class="settings-tabs">
        <button v-for="t in tabs" :key="t.id" @click="activeTab = t.id"
          class="settings-tab" :class="{ active: activeTab === t.id }">
          <span style="font-size: 14px;">{{ t.icon }}</span>
          {{ t.label }}
        </button>
      </nav>

      <!-- 右侧表单 -->
      <div>
        <!-- 个人资料 -->
        <div v-if="activeTab === 'profile'" class="card" style="padding: 24px;">
          <div class="form-row-avatar" style="margin-bottom: 20px;">
            <input ref="avatarInput" type="file" accept="image/*" style="display: none;" @change="handleAvatarChange" />
            <div class="avatar-upload" @click="handleAvatarClick" :style="{ cursor: 'pointer', opacity: avatarUploading ? 0.6 : 1 }">
              <img v-if="profile.avatar" :src="profile.avatar" alt="avatar" style="width:100%;height:100%;object-fit:cover;border-radius:inherit;" />
              <span v-else>{{ profile.nickname?.[0] || 'Y' }}</span>
              <div class="avatar-upload-overlay">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
              </div>
            </div>
            <div>
              <div style="font-size: 13px; font-weight: 500; margin-bottom: 4px;">头像</div>
              <div style="font-size: 12px; color: var(--muted);">{{ avatarUploading ? '上传中…' : '点击上传，或填写 URL 字段' }}</div>
            </div>
          </div>

          <div class="form-row-2">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">昵称</label>
              <input v-model="profile.nickname" class="form-control" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">邮箱（不公开）</label>
              <input v-model="profile.email" type="email" class="form-control" />
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">个人简介</label>
            <textarea v-model="profile.bio" class="form-control" rows="3" placeholder="一句话介绍你"></textarea>
          </div>
          <div class="form-row-2">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">所在地</label>
              <input v-model="profile.location" class="form-control" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">头像 URL</label>
              <input v-model="profile.avatar" class="form-control" />
            </div>
          </div>
        </div>

        <!-- 站点信息 -->
        <div v-else-if="activeTab === 'blog'" class="card" style="padding: 24px;">
          <div class="form-group">
            <label class="form-label">站点标题</label>
            <input v-model="blog.title" class="form-control" />
          </div>
          <div class="form-group">
            <label class="form-label">副标题</label>
            <input v-model="blog.subtitle" class="form-control" />
          </div>
          <div class="form-group">
            <label class="form-label">站点描述（SEO meta）</label>
            <textarea v-model="blog.description" class="form-control" rows="3"></textarea>
          </div>
          <div class="form-row-2">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">版权信息</label>
              <input v-model="blog.copyright" class="form-control" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">Logo URL</label>
              <input v-model="blog.logo" class="form-control" />
            </div>
          </div>
        </div>

        <!-- 技术栈 -->
        <div v-else-if="activeTab === 'techstack'" class="card" style="padding: 24px;">
          <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
            <div style="font-size:13px; color:var(--muted);">维护「关于我」页面的技术栈分组，可按熟练度勾选「弱化显示」</div>
            <button @click="addTechGroup" class="btn btn-ghost btn-sm" type="button">+ 添加分组</button>
          </div>

          <div v-if="!techstack.groups.length" style="padding:32px 0; text-align:center; color:var(--muted); font-size:13px;">
            还没有分组，点击右上角「添加分组」开始维护。
          </div>

          <div v-for="(group, gi) in techstack.groups" :key="gi"
            style="border:1px solid var(--line-soft); border-radius:8px; padding:16px; margin-top:14px;">
            <div class="form-row-2" style="align-items:end;">
              <div class="form-group" style="margin:0;">
                <label class="form-label">分组标题</label>
                <input v-model="group.label" class="form-control" placeholder="如：工作中常用" />
              </div>
              <div style="display:flex; justify-content:flex-end;">
                <button @click="removeTechGroup(gi)" class="btn btn-ghost btn-sm" type="button"
                  style="color:var(--danger);">删除分组</button>
              </div>
            </div>

            <div style="margin-top:12px; display:flex; flex-direction:column; gap:8px;">
              <div v-for="(item, ii) in group.items" :key="ii"
                style="display:flex; align-items:center; gap:10px;">
                <input v-model="item.name" class="form-control" style="flex:1;" placeholder="技术名，如：Java / Spring Boot" />
                <label style="display:flex; align-items:center; gap:6px; font-size:12px; color:var(--text-2); white-space:nowrap; cursor:pointer;">
                  <input type="checkbox" v-model="item.dim" /> 弱化显示
                </label>
                <button @click="removeTechItem(gi, ii)" class="btn btn-ghost btn-sm" type="button"
                  style="color:var(--danger);">移除</button>
              </div>
            </div>
            <button @click="addTechItem(gi)" class="btn btn-ghost btn-sm" type="button" style="margin-top:10px;">+ 添加技术</button>
          </div>
        </div>

        <!-- 个人经历 -->
        <div v-else-if="activeTab === 'experience'" class="card" style="padding: 24px;">
          <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
            <div style="font-size:13px; color:var(--muted);">维护「关于我」页面的经历时间线，按从新到旧排列</div>
            <button @click="addExperience" class="btn btn-ghost btn-sm" type="button">+ 添加经历</button>
          </div>

          <div v-if="!experience.items.length" style="padding:32px 0; text-align:center; color:var(--muted); font-size:13px;">
            还没有经历，点击右上角「添加经历」开始维护。
          </div>

          <div v-for="(item, i) in experience.items" :key="i"
            style="border:1px solid var(--line-soft); border-radius:8px; padding:16px; margin-top:14px;">
            <div class="form-row-2">
              <div class="form-group" style="margin:0;">
                <label class="form-label">时间</label>
                <input v-model="item.time" class="form-control" placeholder="如：2022 — 现在" />
              </div>
              <div class="form-group" style="margin:0;">
                <label class="form-label">标题</label>
                <input v-model="item.title" class="form-control" placeholder="如：某公司 · 高级后端工程师" />
              </div>
            </div>
            <div class="form-group" style="margin-top:14px; margin-bottom:0;">
              <label class="form-label">描述</label>
              <textarea v-model="item.desc" class="form-control" rows="2" placeholder="一句话描述这段经历"></textarea>
            </div>
            <div style="display:flex; gap:8px; margin-top:12px;">
              <button @click="moveExperience(i, -1)" :disabled="i === 0" class="btn btn-ghost btn-sm" type="button">↑ 上移</button>
              <button @click="moveExperience(i, 1)" :disabled="i === experience.items.length - 1" class="btn btn-ghost btn-sm" type="button">↓ 下移</button>
              <button @click="removeExperience(i)" class="btn btn-ghost btn-sm" type="button" style="color:var(--danger); margin-left:auto;">删除</button>
            </div>
          </div>
        </div>

        <!-- 主题外观 -->
        <div v-else-if="activeTab === 'theme'" class="card" style="padding: 24px;">
          <div class="form-group">
            <label class="form-label">主题模式</label>
            <select v-model="theme.mode" class="form-control">
              <option value="auto">跟随系统</option>
              <option value="light">浅色</option>
              <option value="dark">深色</option>
            </select>
          </div>
          <div class="form-row-2">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">主色</label>
              <input v-model="theme.primaryColor" type="color" class="form-control" style="height: 38px; padding: 2px;" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">强调色</label>
              <input v-model="theme.accentColor" type="color" class="form-control" style="height: 38px; padding: 2px;" />
            </div>
          </div>
          <div class="form-group" style="margin-bottom: 0;">
            <label class="form-label">字体</label>
            <select v-model="theme.fontFamily" class="form-control">
              <option value="serif">衬线（默认）</option>
              <option value="sans">无衬线</option>
              <option value="mono">等宽</option>
            </select>
          </div>
        </div>

        <!-- 社交账号 -->
        <div v-else-if="activeTab === 'social'" class="card" style="padding: 24px;">
          <div class="social-row">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">GitHub</label>
              <input v-model="social.github" class="form-control" placeholder="https://github.com/xxx" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">Twitter / X</label>
              <input v-model="social.twitter" class="form-control" placeholder="https://x.com/xxx" />
            </div>
          </div>
          <div class="social-row">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">公开邮箱</label>
              <input v-model="social.emailPublic" type="email" class="form-control" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">微信号</label>
              <input v-model="social.wechat" class="form-control" />
            </div>
          </div>
          <div class="social-row">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">微博</label>
              <input v-model="social.weibo" class="form-control" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">RSS 订阅</label>
              <input v-model="social.rss" class="form-control" placeholder="/rss.xml" />
            </div>
          </div>
        </div>

        <!-- 偏好设置 -->
        <div v-else-if="activeTab === 'preferences'" class="card" style="padding: 24px;">
          <div class="form-group">
            <label class="form-label">语言</label>
            <select v-model="prefs.language" class="form-control">
              <option value="zh-CN">简体中文</option>
              <option value="en">English</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">时区</label>
            <select v-model="prefs.timezone" class="form-control">
              <option value="Asia/Shanghai">上海 (UTC+8)</option>
              <option value="UTC">UTC</option>
              <option value="America/New_York">纽约 (UTC-5)</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">列表密度</label>
            <select v-model="prefs.density" class="form-control">
              <option value="comfortable">舒适</option>
              <option value="compact">紧凑</option>
            </select>
          </div>
          <div class="form-group" style="margin-bottom: 0;">
            <label class="form-label">代码高亮主题</label>
            <select v-model="prefs.codeTheme" class="form-control">
              <option value="github">GitHub Light</option>
              <option value="monokai">Monokai</option>
              <option value="nord">Nord</option>
            </select>
          </div>
        </div>

        <!-- 高级 -->
        <div v-else class="card" style="padding: 24px;">
          <div style="display: flex; flex-direction: column; gap: 14px;">
            <label style="display: flex; align-items: center; gap: 10px; cursor: pointer; font-size: 14px;">
              <input type="checkbox" v-model="advanced.enableCache" />
              <span>启用 Redis 缓存</span>
            </label>
            <label style="display: flex; align-items: center; gap: 10px; cursor: pointer; font-size: 14px;">
              <input type="checkbox" v-model="advanced.enableRss" />
              <span>启用 RSS 订阅</span>
            </label>
            <label style="display: flex; align-items: center; gap: 10px; cursor: pointer; font-size: 14px;">
              <input type="checkbox" v-model="advanced.enableSearch" />
              <span>启用全文搜索</span>
            </label>
            <label style="display: flex; align-items: center; gap: 10px; cursor: pointer; font-size: 14px;">
              <input type="checkbox" v-model="advanced.commentModeration" />
              <span>评论需审核</span>
            </label>
          </div>
          <div style="margin-top: 20px; padding-top: 16px; border-top: 1px dashed var(--line-soft); font-size: 12px; color: var(--muted);">
            ⚠ 修改后需要重启服务才能生效
          </div>
        </div>

        <!-- 操作条 -->
        <div style="display: flex; align-items: center; gap: 12px; margin-top: 16px;">
          <button @click="save" :disabled="saving" class="btn btn-primary">
            {{ saving ? '保存中…' : '保存设置' }}
          </button>
          <span v-if="message" :style="{ color: message.includes('失败') ? 'var(--danger)' : 'var(--success)', fontSize: '13px' }">{{ message }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
