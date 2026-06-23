<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ title: '', subtitle: '', description: '', copyright: '', logo: '' })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/blog', state })
</script>

<template>
  <div>
    <AdminSettingsBlogForm :blog="state" @update:blog="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
