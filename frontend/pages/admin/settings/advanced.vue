<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ enableCache: true, enableRss: true, enableSearch: true, enableCommentModeration: true })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/advanced', state })
</script>

<template>
  <div>
    <AdminSettingsAdvancedForm :advanced="state" @update:advanced="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
    <!-- 2026-07-01 DEV-006：高级 tab 底部「上传 md 文档」区块 -->
    <AdminSettingsMdUploader />
  </div>
</template>
