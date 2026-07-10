<script setup lang="ts">
/**
 * 审计数据页（2026-07-01 DEV-004）
 *
 * 报表页：分页 + 筛选（target 模块名 + operation 操作类型）+ 操作类型标签配色
 *
 * 表头：序号（倒序）| 操作人 | 模块 | 操作类型 | 详情 | IP | 操作时间
 */
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get } = useAdminApi()
const $toast = useToast()
const { formatTime: formatDateTime } = useFormatTime()

const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

// 筛选：模块 + 操作类型
const targetFilter = ref<string>('all')
const operationFilter = ref<string>('all')

const targetOptions = ref<string[]>([])
const operationOptions = ref<string[]>([])

// 操作类型中英文映射
const operationLabelMap: Record<string, string> = {
  CREATE: '新增', UPDATE: '修改', DELETE: '删除',
  APPROVE: '通过', REJECT: '拒绝', DOWNLOAD: '下载',
  UPGRADE: '升级', ROLLBACK: '回滚'
}
const operationValueMap: Record<string, string> = {
  '新增': 'CREATE', '修改': 'UPDATE', '删除': 'DELETE',
  '通过': 'APPROVE', '拒绝': 'REJECT', '下载': 'DOWNLOAD',
  '升级': 'UPGRADE', '回滚': 'ROLLBACK'
}

const operationColor: Record<string, string> = {
  CREATE:   'var(--accent)',     // 绿
  UPDATE:   'var(--info, #3b82f6)', // 蓝
  DELETE:   'var(--danger, #dc2626)', // 红
  APPROVE:  'var(--accent)',
  REJECT:   'var(--warning, #d97706)',
  DOWNLOAD: 'var(--muted)',
  UPGRADE:  'var(--info, #3b82f6)', // 蓝
  ROLLBACK: 'var(--warning, #d97706)' // 橙
}

const loadFilters = async () => {
  try {
    const [tRes, oRes] = await Promise.all([
      get<any>('/admin/audit-logs/targets'),
      get<any>('/admin/audit-logs/operations')
    ])
    targetOptions.value = tRes.data || []
    operationOptions.value = (oRes.data || []).map((o: string) => operationLabelMap[o] || o)
  } catch (e: any) {
    $toast.error('筛选选项加载失败：' + (e?.data?.message || e?.message))
  }
}

const load = async () => {
  loading.value = true
  try {
    const res = await get<any>('/admin/audit-logs', {
      page: page.value,
      size: size.value,
      target: targetFilter.value,
      operation: operationParam.value
    })
    items.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const applyFilters = () => {
  page.value = 1
  load()
}

// 筛选时将中文操作类型转回英文传给后端
const operationParam = computed(() => {
  if (operationFilter.value === 'all') return 'all'
  return operationValueMap[operationFilter.value] || operationFilter.value
})

const handlePageChange = (p: number) => {
  page.value = p
  load()
}

onMounted(async () => {
  await loadFilters()
  await load()
})

// 序号倒序：maxId - (currentIndex offset) → 但用 list 顺序自带的序号即可
// 因为后端按 id DESC 排序，所以 (total - (page-1)*size - i) 才是"全局序号"
const rowNo = (i: number) => total.value - (page.value - 1) * size.value - i
</script>

<template>
  <div>
    <!-- 筛选栏 -->
    <div class="card" style="padding: 16px 20px; margin-bottom: 16px;">
      <div style="display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-end;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <label style="font-size: 13px; color: var(--muted); white-space: nowrap;">菜单名称</label>
          <UiDropdownSelector
            :model-value="targetFilter"
            :options="[{ label: '全部', value: 'all' }, ...targetOptions.map((t: string) => ({ label: t, value: t }))]"
            placeholder="全部"
            @update:model-value="(v: any) => { targetFilter = v }"
            style="min-width: 200px;"
          />
        </div>
        <div style="display: flex; align-items: center; gap: 8px;">
          <label style="font-size: 13px; color: var(--muted); white-space: nowrap;">操作类型</label>
          <UiDropdownSelector
            :model-value="operationFilter"
            :options="[{ label: '全部', value: 'all' }, ...operationOptions.map((o: string) => ({ label: o, value: o }))]"
            placeholder="全部"
            @update:model-value="(v: any) => { operationFilter = v }"
            style="min-width: 200px;"
          />
        </div>
        <button class="btn btn-primary btn-sm" @click="applyFilters" :disabled="loading">筛选</button>
        <span style="margin-left: auto; font-size: 12px; color: var(--muted);">
          共 {{ total }} 条记录
        </span>
      </div>
    </div>

    <!-- 报表表头 -->
    <div class="table-wrap">
      <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中...</div>
      <div v-else-if="items.length === 0" style="padding: 40px; text-align: center; color: var(--muted);">暂无审计记录</div>
      <table v-else class="table">
        <thead>
          <tr style="background: var(--bg-soft, #f8f8f8); border-bottom: 1px solid var(--border);">
            <th style="text-align: right; padding: 10px 12px; width: 60px;">序号</th>
            <th style="text-align: left; padding: 10px 12px; width: 120px;">操作人</th>
            <th style="text-align: left; padding: 10px 12px; width: 100px;">菜单名称</th>
            <th style="text-align: left; padding: 10px 12px; width: 100px;">操作类型</th>
            <th style="text-align: left; padding: 10px 12px;">详情</th>
            <th style="text-align: left; padding: 10px 12px; width: 130px;">IP</th>
            <th style="text-align: left; padding: 10px 12px; width: 160px;">操作时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(item, i) in items" :key="item.id" style="border-bottom: 1px solid var(--border);">
            <td style="text-align: right; padding: 10px 12px; color: var(--muted);">{{ rowNo(i) }}</td>
            <td style="padding: 10px 12px;">{{ item.operator }}</td>
            <td style="padding: 10px 12px; white-space: nowrap;">{{ item.target }}</td>
            <td style="padding: 10px 12px; white-space: nowrap;">
              <span :style="{
                fontFamily: 'JetBrains Mono, monospace',
                fontSize: '11px',
                padding: '2px 8px',
                borderRadius: '3px',
                background: 'var(--bg-soft)',
                color: operationColor[item.operation] || 'var(--text)',
                border: '1px solid var(--border)'
              }">
                {{ operationLabelMap[item.operation] || item.operation }}
              </span>
            </td>
            <td style="padding: 10px 12px; color: var(--muted); max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="item.detail">{{ item.detail || '—' }}</td>
            <td style="padding: 10px 12px; font-family: 'JetBrains Mono, monospace'; font-size: 12px;">{{ item.ip || '—' }}</td>
            <td style="padding: 10px 12px; color: var(--muted); font-family: 'JetBrains Mono, monospace'; font-size: 12px;">{{ formatDateTime(item.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <AdminPagination
      :page="page"
      :size="size"
      :total="total"
      @update:page="(v: number) => page = v"
      @update:size="(v: number) => size = v"
      @change="load"
    />
  </div>
</template>
