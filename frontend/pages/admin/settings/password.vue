<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { put } = useAdminApi()
const passwordFormRef = ref<{ onSuccess: () => void; onError: (msg: string) => void } | null>(null)

const handlePasswordChange = async (payload: { oldPassword: string; newPassword: string; confirmPassword: string }) => {
  try {
    await put('/auth/me/password', payload)
    passwordFormRef.value?.onSuccess()
  } catch (e: any) {
    passwordFormRef.value?.onError(e?.data?.message || e?.message)
  }
}
</script>

<template>
  <div>
    <AdminSettingsPasswordForm
      ref="passwordFormRef"
      @change="handlePasswordChange"
    />
  </div>
</template>
