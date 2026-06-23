<script setup lang="ts">
/**
 * 2026-06-23 DEV-001：settings 从单页 9-tab 拆为 9 个子路由。
 * 本文件保留为「站点设置」外壳：page-head + <NuxtPage /> 出口；
 * 访问 /admin/settings 时重定向到 /admin/settings/profile。
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const route = useRoute()
if (route.path === '/admin/settings' || route.path === '/admin/settings/') {
  await navigateTo('/admin/settings/profile', { replace: true })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>站点设置</h1>
        <p>管理你的个人资料、站点信息和主题外观</p>
      </div>
    </div>
    <NuxtPage />
  </div>
</template>
