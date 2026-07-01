<script setup lang="ts">
/**
 * 统一分页组件（2026-07-01）
 * 功能：上一页 / 页码 / 下一页 / 每页条数切换（10/20/50） / 页码跳转
 */
const props = defineProps<{
  page: number
  size: number
  total: number
}>()

const emit = defineEmits<{
  (e: 'update:page', v: number): void
  (e: 'update:size', v: number): void
  (e: 'change'): void
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const jumpInput = ref<string>('')

const sizeOptions = [10, 20, 50]

const prev = () => {
  if (props.page > 1) {
    emit('update:page', props.page - 1)
    emit('change')
  }
}

const next = () => {
  if (props.page < totalPages.value) {
    emit('update:page', props.page + 1)
    emit('change')
  }
}

const goPage = (p: number) => {
  const n = Math.max(1, Math.min(p, totalPages.value))
  if (n !== props.page) {
    emit('update:page', n)
    emit('change')
  }
}

const onSizeChange = (e: Event) => {
  const v = Number((e.target as HTMLSelectElement).value)
  emit('update:size', v)
  emit('update:page', 1)
  emit('change')
}

const onJump = () => {
  const n = parseInt(jumpInput.value)
  if (!isNaN(n)) {
    goPage(n)
    jumpInput.value = ''
  }
}
</script>

<template>
  <div v-if="total > size" class="admin-pagination">
    <div class="pagination-left">
      <span class="pagination-info">共 {{ total }} 条</span>
      <select class="pagination-size" :value="size" @change="onSizeChange">
        <option v-for="s in sizeOptions" :key="s" :value="s">{{ s }} 条/页</option>
      </select>
    </div>
    <div class="pagination-center">
      <button class="page-btn" :disabled="page <= 1" @click="prev">‹</button>
      <button v-for="p in totalPages" :key="p" class="page-btn" :class="{ active: p === page }" @click="goPage(p)">{{ p }}</button>
      <button class="page-btn" :disabled="page >= totalPages" @click="next">›</button>
    </div>
    <div class="pagination-right">
      <span class="pagination-info">{{ page }} / {{ totalPages }}</span>
      <input v-model="jumpInput" class="pagination-jump" placeholder="页码" @keydown.enter="onJump" />
      <button class="page-btn" @click="onJump">跳转</button>
    </div>
  </div>
</template>

<style scoped>
.admin-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
  padding: 10px 0;
}
.pagination-left, .pagination-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pagination-center {
  display: flex;
  align-items: center;
  gap: 2px;
}
.pagination-info {
  font-size: 13px;
  color: var(--muted);
  white-space: nowrap;
}
.pagination-size {
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--card);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
}
.pagination-jump {
  width: 52px;
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--card);
  color: var(--text);
  font-size: 13px;
  text-align: center;
}
.page-btn {
  min-width: 32px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--card);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.page-btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}
.page-btn.active {
  background: var(--primary);
  color: white;
  border-color: var(--primary);
}
.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
