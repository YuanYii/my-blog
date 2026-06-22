<script setup lang="ts">
const emit = defineEmits<{ (e: 'change', payload: { oldPassword: string; newPassword: string; confirmPassword: string }): void }>()

const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const saving = ref(false)
const message = ref('')
const success = ref(false)

const changePassword = async () => {
  message.value = ''
  success.value = false
  if (!pwd.oldPassword) { message.value = '请输入当前密码'; return }
  if (!pwd.newPassword)  { message.value = '请输入新密码'; return }
  if (pwd.newPassword.length < 8) { message.value = '新密码至少 8 位'; return }
  if (pwd.newPassword !== pwd.confirmPassword) { message.value = '新密码两次输入不一致'; return }
  if (pwd.newPassword === pwd.oldPassword) { message.value = '新密码不能与当前密码相同'; return }
  saving.value = true
  emit('change', { ...pwd })
}

const onSuccess = () => {
  success.value = true
  message.value = '密码已更新 ✓ 当前会话保持，无需重新登录'
  pwd.oldPassword = ''
  pwd.newPassword = ''
  pwd.confirmPassword = ''
  saving.value = false
}

const onError = (msg: string) => {
  message.value = '改密失败：' + msg
  saving.value = false
}

defineExpose({ onSuccess, onError })
</script>

<template>
  <div class="card" style="padding: 24px;">
    <div class="sidebar-card-title" style="margin-bottom: 6px;">修改密码</div>
    <p style="color: var(--muted); font-size: 13px; margin-bottom: 18px;">改密后当前会话保持有效，无需重新登录。建议新密码至少 8 位，包含字母与数字。</p>
    <div class="form-group" style="margin-bottom: 14px;">
      <label class="form-label">当前密码</label>
      <input v-model="pwd.oldPassword" type="password" class="form-control" placeholder="请输入当前密码" autocomplete="current-password" />
    </div>
    <div class="form-group" style="margin-bottom: 14px;">
      <label class="form-label">新密码</label>
      <input v-model="pwd.newPassword" type="password" class="form-control" placeholder="至少 8 位" autocomplete="new-password" />
    </div>
    <div class="form-group" style="margin-bottom: 18px;">
      <label class="form-label">确认新密码</label>
      <input v-model="pwd.confirmPassword" type="password" class="form-control" placeholder="再次输入新密码" autocomplete="new-password" @keyup.enter="changePassword" />
    </div>
    <div v-if="message" :style="{ padding: '8px 12px', borderRadius: '8px', fontSize: '13px', marginBottom: '12px', background: success ? 'rgba(22,163,74,0.1)' : 'rgba(194,65,12,0.1)', color: success ? 'var(--success)' : 'var(--danger)' }">{{ message }}</div>
    <button @click="changePassword" :disabled="saving" class="btn btn-primary">{{ saving ? '更新中…' : '更新密码' }}</button>
  </div>
</template>
