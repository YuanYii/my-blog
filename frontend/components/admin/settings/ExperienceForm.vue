<script setup lang="ts">
const props = defineProps<{ experience: { items: { time: string; title: string; desc: string }[] } }>()
const emit = defineEmits<{ (e: 'update:experience', v: any): void }>()

const add = () => emit('update:experience', { items: [...props.experience.items, { time: '', title: '', desc: '' }] })
const remove = (i: number) => emit('update:experience', { items: props.experience.items.filter((_, j) => j !== i) })
const move = (i: number, dir: -1 | 1) => {
  const j = i + dir
  if (j < 0 || j >= props.experience.items.length) return
  const arr = [...props.experience.items]
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
  emit('update:experience', { items: arr })
}
const update = (i: number, field: 'time' | 'title' | 'desc', value: string) => {
  const items = props.experience.items.map((item, j) => j === i ? { ...item, [field]: value } : item)
  emit('update:experience', { items })
}
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
      <div style="font-size:13px; color:var(--muted);">维护「关于我」页面的经历时间线，按从新到旧排列</div>
      <button @click="add" class="btn btn-ghost btn-sm" type="button">+ 添加经历</button>
    </div>
    <div v-if="!experience.items.length" style="padding:32px 0; text-align:center; color:var(--muted); font-size:13px;">还没有经历，点击右上角「添加经历」开始维护。</div>
    <div v-for="(item, i) in experience.items" :key="i" style="border:1px solid var(--line-soft); border-radius:8px; padding:16px; margin-top:14px;">
      <div class="form-row-2">
        <div class="form-group" style="margin:0;"><label class="form-label">时间</label><input :value="item.time" @input="update(i, 'time', ($event.target as HTMLInputElement).value)" class="form-control" placeholder="如：2022 — 现在" /></div>
        <div class="form-group" style="margin:0;"><label class="form-label">标题</label><input :value="item.title" @input="update(i, 'title', ($event.target as HTMLInputElement).value)" class="form-control" placeholder="如：某公司 · 高级后端工程师" /></div>
      </div>
      <div class="form-group" style="margin-top:14px; margin-bottom:0;"><label class="form-label">描述</label><textarea :value="item.desc" @input="update(i, 'desc', ($event.target as HTMLTextAreaElement).value)" class="form-control" rows="2" placeholder="一句话描述这段经历"></textarea></div>
      <div style="display:flex; gap:8px; margin-top:12px;">
        <button @click="move(i, -1)" :disabled="i === 0" class="btn btn-ghost btn-sm" type="button">↑ 上移</button>
        <button @click="move(i, 1)" :disabled="i === experience.items.length - 1" class="btn btn-ghost btn-sm" type="button">↓ 下移</button>
        <button @click="remove(i)" class="btn btn-ghost btn-sm" type="button" style="color:var(--danger); margin-left:auto;">删除</button>
      </div>
    </div>
  </div>
</template>
