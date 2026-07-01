<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive<{ groups: { label: string; items: { name: string; dim: boolean }[] }[] }>({ groups: [] })
const { saving, message, save, load } = useAdminSettingsTab({ endpoint: '/admin/settings/techstack', state })

// 2026-07-01 DEV-006：监听 md 上传成功后的事件，reload 当前 techstack 数据
const bus = useSettingsEventBus()
const unsubscribe = bus.on('settings-updated', (payload) => {
  if (payload.sections.includes('techstack')) load()
})
onBeforeUnmount(unsubscribe)
</script>

<template>
  <div>
    <AdminSettingsTechstackForm :techstack="state" @update:techstack="state.groups = $event.groups" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
