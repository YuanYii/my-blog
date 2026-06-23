<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ mode: 'auto', primaryColor: '#2f6f5e', accentColor: '#c97b3f', fontFamily: 'serif' })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/theme', state })
</script>

<template>
  <div>
    <AdminSettingsThemeForm :theme="state" @update:theme="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
