<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, put, del } = useAdminApi()
const { deviceId: myDeviceId } = useDevice()

const devices = ref<any[]>([])
const loading = ref(false)
const message = ref('')

const load = async () => {
  loading.value = true
  try {
    const res = await get<any>('/admin/devices')
    devices.value = res.data || []
  } catch (e: any) {
    message.value = '加载失败：' + (e?.data?.message || e?.message)
  } finally {
    loading.value = false
  }
}

const approve = async (id: number) => {
  if (!confirm('批准后该设备将可以登录管理后台，确定吗？')) return
  try {
    await put(`/admin/devices/${id}/approve`, { approvedBy: myDeviceId.value })
    message.value = '已批准 ✓'
    await load()
  } catch (e: any) {
    message.value = '批准失败：' + (e?.data?.message || e?.message)
  }
  setTimeout(() => message.value = '', 2000)
}

const revoke = async (id: number, name: string) => {
  if (!confirm(`确定吊销「${name}」吗？该设备将无法再登录。`)) return
  try {
    await put(`/admin/devices/${id}/revoke`)
    message.value = '已吊销 ✓'
    await load()
  } catch (e: any) {
    // 2003 DEVICE_SELF_REVOKE_FORBIDDEN：后端兜底防自吊销
    // 前端 UI 已经隐藏自己设备的吊销按钮，这是双层防护的兜底
    const code = e?.data?.code
    if (code === 2003) {
      message.value = '不能吊销当前登录设备（请在另一台已授权设备上操作）'
    } else {
      message.value = '吊销失败：' + (e?.data?.message || e?.message)
    }
  }
  setTimeout(() => message.value = '', 2000)
}

// 2026-06-13 新增：物理删除设备记录（用户要求"支持删除当前记录"）
// - 跟 revoke 区别：revoke 软删（改 status=revoked，留底），delete 真抹掉
// - 允许删自己当前设备（业务放开；防误操作靠 confirm 二次确认）
// - 自删后下次请求会 401 → useAdminApi onResponseError 跳 login 页
const remove = async (d: any) => {
  const isSelf = d.deviceId === myDeviceId.value
  const tip = isSelf
    ? `你正在删除「${d.deviceName}」(当前设备)\n\n删除后下次操作会强制重新登录，确认吗？`
    : `确定删除「${d.deviceName}」的设备记录？此操作不可撤销（与吊销不同，记录会被彻底抹掉）。`
  if (!confirm(tip)) return
  try {
    await del(`/admin/devices/${d.id}`)
    message.value = isSelf ? '已删除当前设备，请重新登录' : '已删除 ✓'
    await load()
  } catch (e: any) {
    message.value = '删除失败：' + (e?.data?.message || e?.message)
  }
  setTimeout(() => message.value = '', 2000)
}

const statusLabel = (s: string) => {
  if (s === 'approved') return { text: '已授权', color: 'var(--success)', bg: 'rgba(22, 163, 74, 0.1)' }
  if (s === 'pending')  return { text: '待授权', color: 'var(--accent)',  bg: 'rgba(202, 138, 4, 0.1)' }
  if (s === 'revoked')  return { text: '已吊销', color: 'var(--danger)',  bg: 'rgba(194, 65, 12, 0.1)' }
  return { text: s, color: 'var(--muted)', bg: 'var(--bg-soft)' }
}

const formatTime = (s: string) => s ? s.replace('T', ' ').substring(0, 19) : '—'

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>设备管理</h1>
        <p>管理可以登录管理后台的设备。只在已授权的设备上登录，未授权的设备需要你在已授权设备上手动批准。</p>
      </div>
    </div>

    <div v-if="message" :style="{
      padding: '10px 14px',
      borderRadius: '10px',
      marginBottom: '14px',
      fontSize: '13px',
      background: message.includes('失败') ? 'rgba(194, 65, 12, 0.1)' : 'rgba(22, 163, 74, 0.1)',
      color: message.includes('失败') ? 'var(--danger)' : 'var(--success)'
    }">{{ message }}</div>

    <div v-if="loading" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>

    <div v-else-if="!devices.length" class="card" style="text-align: center; color: var(--muted); padding: 40px;">
      暂无设备记录
    </div>

    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>设备</th>
            <th>状态</th>
            <th>IP</th>
            <th>最后活跃</th>
            <th>授权时间</th>
            <th>授权人</th>
            <th style="text-align: right;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in devices" :key="d.id">
            <td>
              <div style="font-weight: 500;">{{ d.deviceName || 'Unknown' }}</div>
              <div style="font-size: 11px; color: var(--muted); font-family: 'JetBrains Mono', monospace; margin-top: 2px;">
                {{ d.deviceId?.substring(0, 13) }}…
                <span v-if="d.deviceId === myDeviceId" style="color: var(--primary); font-weight: 500; margin-left: 4px;">← 当前</span>
              </div>
            </td>
            <td>
              <span class="badge" :style="{ background: statusLabel(d.status).bg, color: statusLabel(d.status).color }">
                {{ statusLabel(d.status).text }}
              </span>
            </td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px;">{{ d.ip || '—' }}</td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px; color: var(--muted);">{{ formatTime(d.lastSeenAt) }}</td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px; color: var(--muted);">{{ formatTime(d.approvedAt) }}</td>
            <td style="font-family: 'JetBrains Mono', monospace; font-size: 12px; color: var(--muted);">
              <!-- 2026-06-13 修复（BUG-067）：后端返完整 deviceId（36 字符 UUID）原 UI 没展示。
                   截前 8 字符加省略号，hover 提示 title 完整。空值友好显示 "—" -->
              <span :title="d.approvedBy || '—'">{{ d.approvedBy?.substring(0, 8) || '—' }}…</span>
            </td>
            <td>
              <div class="row-actions">
                <button
                  v-if="d.status !== 'approved'"
                  @click="approve(d.id)"
                  class="row-action"
                  title="批准该设备"
                  aria-label="批准"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                </button>
                <button
                  v-if="d.status !== 'revoked' && d.deviceId !== myDeviceId"
                  @click="revoke(d.id, d.deviceName)"
                  class="row-action danger"
                  title="吊销该设备"
                  aria-label="吊销"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
                <button
                  @click="remove(d)"
                  class="row-action danger"
                  title="物理删除记录（不可恢复）"
                  aria-label="删除"
                >
                  <!-- 垃圾桶 icon —— 跟"吊销"的 × 区分：吊销是禁止符，删除是物理移除 -->
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div style="margin-top: 16px; padding: 14px 16px; background: var(--bg-soft); border-radius: 10px; font-size: 12px; color: var(--muted); line-height: 1.7;">
      <strong style="color: var(--text-2);">工作流程：</strong><br>
      ① 访客/新设备用正确密码登录 → 后端建一条「待授权」记录，返回提示，不发 token<br>
      ② 你在已授权设备的「设备管理」页看到待授权设备 → 点「批准」<br>
      ③ 对方重新登录即可<br>
      ④ 发现异常设备立即点「吊销」——该设备会被强制退出且无法再登录（软删，记录仍在 status=revoked）<br>
      ⑤ 想彻底抹掉记录（含当前自己）→ 点「🗑」图标——物理删除，不可恢复
    </div>
  </div>
</template>
