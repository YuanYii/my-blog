<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive<{ groups: { label: string; items: { name: string; dim: boolean }[] }[] }>({ groups: [] })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/techstack', state })
</script>

<template>
  <div>
    <AdminSettingsTechstackForm :techstack="state" @update:techstack="state.groups = $event.groups" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
