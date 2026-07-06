<script setup lang="ts">
import { Toaster } from 'vue-sonner'
const { state, handleConfirm, handleCancel } = useDialog()
</script>

<template>
  <div class="min-h-screen flex flex-col">
    <NavBar />
    <main class="flex-1 w-full mx-auto px-4 md:px-8 py-8">
      <slot />
    </main>
    <SiteFooter />

    <ClientOnly>
      <Toaster position="top-right" :duration="2500" rich-colors close-button />
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
