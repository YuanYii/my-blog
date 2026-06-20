<script setup lang="ts">
const props = defineProps<{
  profile: { nickname: string; email: string; bio: string; location: string; avatar: string }
  upload: (path: string, file: File) => Promise<any>
}>()
const emit = defineEmits<{ (e: 'update:profile', v: typeof props.profile): void }>()

const $toast = useToast()
const avatarUploading = ref(false)
const avatarInput = ref<HTMLInputElement | null>(null)

const form = computed({
  get: () => props.profile,
  set: (v) => emit('update:profile', v)
})

const handleAvatarClick = () => avatarInput.value?.click()
const handleAvatarChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  avatarUploading.value = true
  try {
    const res = await props.upload('/admin/uploads', file)
    if (res.data?.url) emit('update:profile', { ...props.profile, avatar: res.data.url })
  } catch (err: any) {
    $toast.error('上传失败：' + (err?.data?.message || err?.message))
  } finally {
    avatarUploading.value = false
    input.value = ''
  }
}
</script>

<template>
  <div class="card" style="padding: 24px;">
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
        <input :value="profile.nickname" @input="emit('update:profile', { ...profile, nickname: ($event.target as HTMLInputElement).value })" class="form-control" />
      </div>
      <div class="form-group" style="margin: 0;">
        <label class="form-label">邮箱（不公开）</label>
        <input :value="profile.email" @input="emit('update:profile', { ...profile, email: ($event.target as HTMLInputElement).value })" type="email" class="form-control" />
      </div>
    </div>
    <div class="form-group">
      <label class="form-label">个人简介</label>
      <textarea :value="profile.bio" @input="emit('update:profile', { ...profile, bio: ($event.target as HTMLTextAreaElement).value })" class="form-control" rows="3" placeholder="一句话介绍你"></textarea>
    </div>
    <div class="form-row-2">
      <div class="form-group" style="margin: 0;">
        <label class="form-label">所在地</label>
        <input :value="profile.location" @input="emit('update:profile', { ...profile, location: ($event.target as HTMLInputElement).value })" class="form-control" />
      </div>
      <div class="form-group" style="margin: 0;">
        <label class="form-label">头像 URL</label>
        <input :value="profile.avatar" @input="emit('update:profile', { ...profile, avatar: ($event.target as HTMLInputElement).value })" class="form-control" />
      </div>
    </div>
  </div>
</template>
