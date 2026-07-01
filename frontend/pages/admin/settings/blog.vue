<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ title: '', subtitle: '', description: '', copyright: '', logo: '' })
const { saving, message, save, load } = useAdminSettingsTab({ endpoint: '/admin/settings/blog', state })

// 2026-07-01 DEV-006：监听 md 上传成功后的事件，reload 当前 blog 数据
const bus = useSettingsEventBus()
const unsubscribe = bus.on('settings-updated', (payload) => {
  if (payload.sections.includes('blog')) load()
})
onBeforeUnmount(unsubscribe)
</script>

<template>
  <div>
    <AdminSettingsBlogForm :blog="state" @update:blog="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
