<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, put, upload } = useAdminApi()
const { updateUser } = useAuth()

const profile = reactive({ nickname: '', email: '', bio: '', location: '', avatar: '' })
const saving = ref(false)
const message = ref('')

const load = async () => {
  try {
    const res = await get<any>('/admin/settings/profile')
    if (res.data) Object.assign(profile, res.data)
  } catch { /* 默认值 */ }
}

const save = async () => {
  saving.value = true
  message.value = ''
  try {
    await put('/admin/settings/profile', profile)
    updateUser({ nickname: profile.nickname, avatar: profile.avatar })
    message.value = '已保存 ✓'
    setTimeout(() => (message.value = ''), 2000)
  } catch (e: any) {
    message.value = '保存失败：' + (e?.data?.message || e?.message)
  } finally {
    saving.value = false
  }
}

const uploadFn = (path: string, file: File) => upload<any>(path, file)

onMounted(load)

// 2026-07-01 DEV-006：监听 md 上传成功后的事件，reload 当前 profile 数据
const bus = useSettingsEventBus()
const unsubscribe = bus.on('settings-updated', (payload) => {
  if (payload.sections.includes('profile')) load()
})
onBeforeUnmount(unsubscribe)
</script>

<template>
  <div>
    <AdminSettingsProfileForm
      :profile="profile"
      :upload="uploadFn"
      @update:profile="Object.assign(profile, $event)"
    />
    <div style="display: flex; align-items: center; gap: 12px; margin-top: 16px;">
      <button @click="save" :disabled="saving" class="btn btn-primary">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
      <span v-if="message" :style="{ color: message.includes('失败') ? 'var(--danger)' : 'var(--success)', fontSize: '13px' }">{{ message }}</span>
    </div>
  </div>
</template>
