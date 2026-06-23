<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ language: 'zh-CN', timezone: 'Asia/Shanghai', density: 'comfortable', codeTheme: 'github' })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/preferences', state })
</script>

<template>
  <div>
    <AdminSettingsPreferencesForm :prefs="state" @update:prefs="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
