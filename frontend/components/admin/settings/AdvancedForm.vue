<script setup lang="ts">
defineProps<{ advanced: { enableCache: boolean; enableRss: boolean; enableSearch: boolean; enableCommentModeration: boolean } }>()
defineEmits<{ (e: 'update:advanced', v: any): void }>()
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="form-group" style="display:flex; align-items:center; justify-content:space-between; margin-bottom:16px;">
      <div><div class="form-label" style="margin:0;">启用缓存</div><div style="font-size:12px; color:var(--muted);">开启服务端页面缓存，降低数据库压力</div></div>
      <label class="toggle"><input type="checkbox" :checked="advanced.enableCache" @change="$emit('update:advanced', { ...advanced, enableCache: ($event.target as HTMLInputElement).checked })" /><span></span></label>
    </div>
    <div class="form-group" style="display:flex; align-items:center; justify-content:space-between; margin-bottom:16px;">
      <div><div class="form-label" style="margin:0;">RSS 订阅</div><div style="font-size:12px; color:var(--muted);">开启后在 /rss.xml 自动生成 RSS Feed</div></div>
      <label class="toggle"><input type="checkbox" :checked="advanced.enableRss" @change="$emit('update:advanced', { ...advanced, enableRss: ($event.target as HTMLInputElement).checked })" /><span></span></label>
    </div>
    <div class="form-group" style="display:flex; align-items:center; justify-content:space-between; margin-bottom:16px;">
      <div><div class="form-label" style="margin:0;">全文搜索</div><div style="font-size:12px; color:var(--muted);">开启后读者可在博客内搜索文章内容</div></div>
      <label class="toggle"><input type="checkbox" :checked="advanced.enableSearch" @change="$emit('update:advanced', { ...advanced, enableSearch: ($event.target as HTMLInputElement).checked })" /><span></span></label>
    </div>
    <div class="form-group" style="display:flex; align-items:center; justify-content:space-between; margin-bottom:0;">
      <div><div class="form-label" style="margin:0;">评论审核</div><div style="font-size:12px; color:var(--muted);">开启后评论需要审核通过才会公开展示</div></div>
      <label class="toggle"><input type="checkbox" :checked="advanced.enableCommentModeration" @change="$emit('update:advanced', { ...advanced, enableCommentModeration: ($event.target as HTMLInputElement).checked })" /><span></span></label>
    </div>
  </div>
</template>
