<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const state = reactive({ title: '', subtitle: '', description: '', copyright: '', logo: '' })
const { saving, message, save, load } = useAdminSettingsTab({ endpoint: '/admin/settings/blog', state })
const { exporting, exportSettingsMd } = useAdminSettings()

const showImportModal = ref(false)

const handleExport = async () => {
  try {
    await exportSettingsMd()
  } catch {
    // 错误由 composable 统一拦截并 toast
  }
}

const handleImportSuccess = () => {
  showImportModal.value = false
  load()
}

// 2026-07-01 DEV-006 / 2026-10-02 DEV-007：监听 md 上传成功后的事件，reload 当前 blog 数据并关闭弹窗
const bus = useSettingsEventBus()
const unsubscribe = bus.on('settings-updated', (payload) => {
  if (payload.sections.includes('blog')) {
    load()
    showImportModal.value = false
  }
})
onBeforeUnmount(unsubscribe)
</script>

<template>
  <div>
    <AdminSettingsBlogForm :blog="state" @update:blog="Object.assign(state, $event)" />
    <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; flex-wrap: wrap;">
      <AdminSettingsSaveBar :saving="saving" :message="message" @save="save" style="margin-top: 0;" />
      <div style="display: flex; align-items: center; gap: 8px;">
        <button
          type="button"
          class="btn"
          @click="showImportModal = true"
        >
          导入配置
        </button>
        <button
          type="button"
          class="btn"
          :disabled="exporting"
          @click="handleExport"
        >
          {{ exporting ? '导出中…' : '导出配置' }}
        </button>
      </div>
    </div>

    <!-- 导入配置操作浮层 / 模态框 -->
    <Teleport to="body">
      <div
        v-if="showImportModal"
        class="import-modal-overlay"
        @click.self="showImportModal = false"
      >
        <div class="import-modal-content">
          <div class="import-modal-header">
            <h3 style="margin: 0; font-size: 16px; font-weight: 600;">导入站点配置 (Markdown)</h3>
            <button
              type="button"
              class="import-modal-close"
              @click="showImportModal = false"
              title="关闭"
            >
              &times;
            </button>
          </div>
          <div class="import-modal-body">
            <AdminSettingsMdUploader :in-modal="true" @success="handleImportSuccess" />
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.import-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(2px);
}

.import-modal-content {
  background: var(--bg);
  border: 1px solid var(--color-line);
  border-radius: 12px;
  padding: 24px;
  width: 620px;
  max-width: 92vw;
  max-height: 85vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.import-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-line);
}

.import-modal-close {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
  color: var(--muted);
  padding: 2px 6px;
  border-radius: 4px;
  transition: color 0.2s, background-color 0.2s;
}

.import-modal-close:hover {
  color: var(--text);
  background-color: var(--bg-soft);
}

.import-modal-body {
  /* 模态框正文 */
}
</style>
