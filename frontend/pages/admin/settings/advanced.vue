<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ enableCache: true, enableRss: true, enableSearch: true, enableCommentModeration: true })
const { saving, message, save } = useAdminSettingsTab({ endpoint: '/admin/settings/advanced', state })

// 隐藏功能：连续点击"启用缓存"5次打开模拟报错弹窗
const clickCount = ref(0)
const showLogSimulator = ref(false)
let clickTimer: ReturnType<typeof setTimeout> | null = null

const handleCacheLabelClick = () => {
  clickCount.value++
  if (clickTimer) clearTimeout(clickTimer)
  clickTimer = setTimeout(() => { clickCount.value = 0 }, 3000)
  if (clickCount.value >= 5) {
    clickCount.value = 0
    showLogSimulator.value = true
  }
}
</script>

<template>
  <div>
    <AdminSettingsAdvancedForm :advanced="state" @update:advanced="Object.assign(state, $event)" @cache-label-click="handleCacheLabelClick" />
    <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" />
    <!-- 2026-07-01 DEV-006：高级 tab 底部「上传 md 文档」区块 -->
    <AdminSettingsMdUploader />
    <!-- 2026-07-30 DEV-001：模拟服务报错弹窗 -->
    <AdminSettingsLogSimulatorDialog v-model:visible="showLogSimulator" />
  </div>
</template>
