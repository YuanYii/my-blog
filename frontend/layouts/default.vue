<script setup lang="ts">
// 默认前台布局：带 NavBar + Footer
</script>

<template>
  <div class="min-h-screen flex flex-col">
    <NavBar />
    <main class="flex-1 max-w-3xl w-full mx-auto px-4 md:px-8 py-8">
      <slot />
    </main>
    <SiteFooter />
  </div>
</template>
