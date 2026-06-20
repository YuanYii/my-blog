<script setup lang="ts">
defineProps<{ blog: { title: string; subtitle: string; description: string; copyright: string; logo: string } }>()
defineEmits<{ (e: 'update:blog', v: any): void }>()
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group"><label class="form-label">站点标题</label><input :value="blog.title" @input="$emit('update:blog', { ...blog, title: ($event.target as HTMLInputElement).value })" class="form-control" /></div>
    <div class="form-group"><label class="form-label">副标题</label><input :value="blog.subtitle" @input="$emit('update:blog', { ...blog, subtitle: ($event.target as HTMLInputElement).value })" class="form-control" /></div>
    <div class="form-group"><label class="form-label">站点描述（SEO meta）</label><textarea :value="blog.description" @input="$emit('update:blog', { ...blog, description: ($event.target as HTMLTextAreaElement).value })" class="form-control" rows="3"></textarea></div>
    <div class="form-row-2">
      <div class="form-group" style="margin: 0;"><label class="form-label">版权信息</label><input :value="blog.copyright" @input="$emit('update:blog', { ...blog, copyright: ($event.target as HTMLInputElement).value })" class="form-control" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">Logo URL</label><input :value="blog.logo" @input="$emit('update:blog', { ...blog, logo: ($event.target as HTMLInputElement).value })" class="form-control" /></div>
    </div>
  </div>
</template>
