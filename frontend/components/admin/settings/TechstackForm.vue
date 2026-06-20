<script setup lang="ts">
const props = defineProps<{ techstack: { groups: { label: string; items: { name: string; dim: boolean }[] }[] } }>()
const emit = defineEmits<{ (e: 'update:techstack', v: any): void }>()

const addGroup = () => emit('update:techstack', { groups: [...props.techstack.groups, { label: '', items: [] }] })
const removeGroup = (gi: number) => emit('update:techstack', { groups: props.techstack.groups.filter((_, i) => i !== gi) })
const addItem = (gi: number) => {
  const groups = props.techstack.groups.map((g, i) => i === gi ? { ...g, items: [...g.items, { name: '', dim: false }] } : g)
  emit('update:techstack', { groups })
}
const removeItem = (gi: number, ii: number) => {
  const groups = props.techstack.groups.map((g, i) => i === gi ? { ...g, items: g.items.filter((_, j) => j !== ii) } : g)
  emit('update:techstack', { groups })
}
const updateGroup = (gi: number, label: string) => {
  const groups = props.techstack.groups.map((g, i) => i === gi ? { ...g, label } : g)
  emit('update:techstack', { groups })
}
const updateItem = (gi: number, ii: number, field: 'name' | 'dim', value: any) => {
  const groups = props.techstack.groups.map((g, i) => i === gi ? {
    ...g, items: g.items.map((item, j) => j === ii ? { ...item, [field]: value } : item)
  } : g)
  emit('update:techstack', { groups })
}
</script>
<template>
  <div class="card" style="padding: 24px;">
    <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
      <div style="font-size:13px; color:var(--muted);">维护「关于我」页面的技术栈分组，可按熟练度勾选「弱化显示」</div>
      <button @click="addGroup" class="btn btn-ghost btn-sm" type="button">+ 添加分组</button>
    </div>
    <div v-if="!techstack.groups.length" style="padding:32px 0; text-align:center; color:var(--muted); font-size:13px;">还没有分组，点击右上角「添加分组」开始维护。</div>
    <div v-for="(group, gi) in techstack.groups" :key="gi" style="border:1px solid var(--line-soft); border-radius:8px; padding:16px; margin-top:14px;">
      <div class="form-row-2" style="align-items:end;">
        <div class="form-group" style="margin:0;"><label class="form-label">分组标题</label><input :value="group.label" @input="updateGroup(gi, ($event.target as HTMLInputElement).value)" class="form-control" placeholder="如：工作中常用" /></div>
        <div style="display:flex; justify-content:flex-end;"><button @click="removeGroup(gi)" class="btn btn-ghost btn-sm" type="button" style="color:var(--danger);">删除分组</button></div>
      </div>
      <div style="margin-top:12px; display:flex; flex-direction:column; gap:8px;">
        <div v-for="(item, ii) in group.items" :key="ii" style="display:flex; align-items:center; gap:10px;">
          <input :value="item.name" @input="updateItem(gi, ii, 'name', ($event.target as HTMLInputElement).value)" class="form-control" style="flex:1;" placeholder="技术名，如：Java / Spring Boot" />
          <label style="display:flex; align-items:center; gap:6px; font-size:12px; color:var(--text-2); white-space:nowrap; cursor:pointer;"><input type="checkbox" :checked="item.dim" @change="updateItem(gi, ii, 'dim', ($event.target as HTMLInputElement).checked)" /> 弱化显示</label>
          <button @click="removeItem(gi, ii)" class="btn btn-ghost btn-sm" type="button" style="color:var(--danger);">移除</button>
        </div>
      </div>
      <button @click="addItem(gi)" class="btn btn-ghost btn-sm" type="button" style="margin-top:10px;">+ 添加技术</button>
    </div>
  </div>
</template>
