<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive<{ items: { time: string; title: string; desc: string }[] }>({ items: [] })
const { saving, message, save, load } = useAdminSettingsTab({ endpoint: '/admin/settings/experience', state })

// 2026-07-01 DEV-006：监听 md 上传成功后的事件，reload 当前 experience 数据
const bus = useSettingsEventBus()
const unsubscribe = bus.on('settings-updated', (payload) => {
  if (payload.sections.includes('experience')) load()
})
onBeforeUnmount(unsubscribe)
</script>

<template>
  <div>
    <AdminSettingsExperienceForm :experience="state" @update:experience="state.items = $event.items" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
