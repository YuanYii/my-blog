<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ enableCache: true, enableRss: true, enableSearch: true, enableCommentModeration: true })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/advanced', state })
</script>

<template>
  <div>
    <AdminSettingsAdvancedForm :advanced="state" @update:advanced="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
