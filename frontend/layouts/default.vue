<script setup lang="ts">
// 默认前台布局：带 NavBar + Footer
// 2026-06-16 新增：挂载 GlobalDialog 容器，让前台页面也能用 $dialog.confirm/prompt
import { Toaster } from 'vue-sonner'
const { state, handleConfirm, handleCancel } = useDialog()
</script>

<template>
  <div class="min-h-screen flex flex-col">
    <NavBar />
    <main class="flex-1 max-w-3xl w-full mx-auto px-4 md:px-8 py-8">
      <slot />
    </main>
    <SiteFooter />

    <!-- Toast 容器：右上角，z-index 高于 modal -->
    <ClientOnly>
      <Toaster position="top-right" :duration="2500" rich-colors close-button />

      <!-- Dialog 容器（confirm + prompt 共用） -->
      <GlobalDialog
        :open="state.open"
        :title="state.title"
        :message="state.message"
        :confirm-text="state.confirmText"
        :cancel-text="state.cancelText"
        :danger="state.danger"
        :prompt="state.prompt"
        :prompt-label="state.promptLabel"
        :prompt-placeholder="state.promptPlaceholder"
        :prompt-default="state.promptDefault"
        @confirm="handleConfirm"
        @cancel="handleCancel"
      />
    </ClientOnly>
  </div>
</template>
