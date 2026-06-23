<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ github: '', twitter: '', emailPublic: '', wechat: '', weibo: '', rss: '' })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/social', state })
</script>

<template>
  <div>
    <AdminSettingsSocialForm :social="state" @update:social="Object.assign(state, $event)" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
  </div>
</template>
