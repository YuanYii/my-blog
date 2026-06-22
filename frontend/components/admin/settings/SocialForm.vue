<script setup lang="ts">
defineProps<{ social: { github: string; twitter: string; emailPublic: string; wechat: string; weibo: string; rss: string } }>()
defineEmits<{ (e: 'update:social', v: any): void }>()
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div class="social-row">
      <div class="form-group" style="margin: 0;"><label class="form-label">GitHub</label><input :value="social.github" @input="$emit('update:social', { ...social, github: ($event.target as HTMLInputElement).value })" class="form-control" placeholder="https://github.com/xxx" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">Twitter / X</label><input :value="social.twitter" @input="$emit('update:social', { ...social, twitter: ($event.target as HTMLInputElement).value })" class="form-control" placeholder="https://x.com/xxx" /></div>
    </div>
    <div class="social-row">
      <div class="form-group" style="margin: 0;"><label class="form-label">公开邮箱</label><input :value="social.emailPublic" @input="$emit('update:social', { ...social, emailPublic: ($event.target as HTMLInputElement).value })" type="email" class="form-control" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">微信号</label><input :value="social.wechat" @input="$emit('update:social', { ...social, wechat: ($event.target as HTMLInputElement).value })" class="form-control" /></div>
    </div>
    <div class="social-row">
      <div class="form-group" style="margin: 0;"><label class="form-label">微博</label><input :value="social.weibo" @input="$emit('update:social', { ...social, weibo: ($event.target as HTMLInputElement).value })" class="form-control" /></div>
      <div class="form-group" style="margin: 0;"><label class="form-label">RSS 订阅</label><input :value="social.rss" @input="$emit('update:social', { ...social, rss: ($event.target as HTMLInputElement).value })" class="form-control" placeholder="/rss.xml" /></div>
    </div>
  </div>
</template>
