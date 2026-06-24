<script setup lang="ts">
/**
 * 2026-06-23 DEV-001：settings 从单页 9-tab 拆为 9 个子路由。
 * 本文件保留为「站点设置」外壳：page-head + <NuxtPage /> 出口；
 * 访问 /admin/settings 时重定向到 /admin/settings/profile。
 * OPT-009：page-head 标题随当前二级菜单动态变化。
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const route = useRoute()
if (route.path === '/admin/settings' || route.path === '/admin/settings/') {
  await navigateTo('/admin/settings/profile', { replace: true })
}

const subTitleMap: Record<string, { title: string; desc: string }> = {
  '/admin/settings/profile':     { title: '个人资料', desc: '管理你的昵称、头像、简介与联系方式' },
  '/admin/settings/password':    { title: '修改密码', desc: '设置新的登录密码' },
  '/admin/settings/blog':        { title: '站点信息', desc: '配置站点名称、副标题、Logo 与版权' },
  '/admin/settings/techstack':   { title: '技术栈',   desc: '维护博客技术栈展示' },
  '/admin/settings/experience':  { title: '个人经历', desc: '维护个人经历与时间线' },
  '/admin/settings/theme':       { title: '主题外观', desc: '调整主题颜色、字体与代码风格' },
  '/admin/settings/social':      { title: '社交账号', desc: '维护社交账号链接与公开邮箱' },
  '/admin/settings/preferences': { title: '偏好设置', desc: '语言、时区、密度等偏好' },
  '/admin/settings/advanced':    { title: '高级',     desc: '高级功能与维护选项' }
}
const pageHead = computed(() => subTitleMap[route.path] || { title: '个人资料', desc: '管理你的昵称、头像、简介与联系方式' })
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>{{ pageHead.title }}</h1>
        <p>{{ pageHead.desc }}</p>
      </div>
    </div>
    <NuxtPage />
  </div>
</template>
