<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive<{ items: { time: string; title: string; desc: string }[] }>({ items: [] })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/experience', state })
</script>

<template>
  <div>
    <AdminSettingsExperienceForm :experience="state" @update:experience="state.items = $event.items" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
