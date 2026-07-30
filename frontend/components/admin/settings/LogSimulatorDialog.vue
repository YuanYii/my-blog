<script setup lang="ts">
/**
 * 模拟服务报错弹窗组件（2026-07-30 DEV-001）
 *
 * 隐藏功能：在高级设置页面连续点击"启用缓存"5次弹出
 * 用于模拟服务端日志输出，便于测试日志采集系统
 */
const { post } = useAdminApi()
const $toast = useToast()

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

// 表单字段
const timestamp = ref('')
const level = ref('ERROR')
const thread = ref('')
const traceId = ref('')
const message = ref('')
const count = ref(1)
const executing = ref(false)

// 生成随机线程名
const generateThread = () => {
  const num = Math.floor(Math.random() * 20) + 1
  return `http-nio-8080-exec-${num}`
}

// 生成 UUID 格式的 traceId
const generateTraceId = () => {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// 初始化默认值
const initDefaults = () => {
  const now = new Date()
  const offset = now.getTimezoneOffset()
  const local = new Date(now.getTime() - offset * 60 * 1000)
  timestamp.value = local.toISOString().slice(0, 16)
  thread.value = generateThread()
  traceId.value = generateTraceId()
  message.value = ''
  count.value = 1
}

// 重置表单
const handleReset = () => {
  initDefaults()
  message.value = ''
}

// 提交表单
const handleSubmit = async () => {
  if (!message.value.trim()) {
    $toast.warning('请输入错误信息')
    return
  }

  executing.value = true
  try {
    await post('/admin/settings/simulate-log', {
      timestamp: timestamp.value,
      level: level.value,
      thread: thread.value,
      traceId: traceId.value,
      message: message.value.trim(),
      count: count.value
    })
    $toast.success(`已打印 ${count.value} 条模拟日志`)
    emit('update:visible', false)
  } catch (e: any) {
    $toast.error('执行失败: ' + (e?.data?.message || e?.message || '未知错误'))
  } finally {
    executing.value = false
  }
}

// 监听 visible 变化，初始化默认值
watch(() => props.visible, (val) => {
  if (val) initDefaults()
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-overlay" @click.self="emit('update:visible', false)">
      <div class="modal-content">
        <div class="modal-header">
          <h3>模拟服务报错</h3>
          <button class="modal-close" @click="emit('update:visible', false)">&times;</button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label class="form-label">报错时间</label>
            <input
              v-model="timestamp"
              type="datetime-local"
              class="form-input"
            />
          </div>
          <div class="form-row">
            <label class="form-label">日志级别</label>
            <select v-model="level" class="form-input">
              <option value="INFO">INFO</option>
              <option value="WARN">WARN</option>
              <option value="ERROR">ERROR</option>
            </select>
          </div>
          <div class="form-row">
            <label class="form-label">报错线程</label>
            <div class="input-group">
              <input v-model="thread" type="text" class="form-input" placeholder="http-nio-8080-exec-1" />
              <button class="btn btn-sm" @click="thread = generateThread()" title="随机生成">🎲</button>
            </div>
          </div>
          <div class="form-row">
            <label class="form-label">traceId</label>
            <div class="input-group">
              <input v-model="traceId" type="text" class="form-input" placeholder="UUID 格式" />
              <button class="btn btn-sm" @click="traceId = generateTraceId()" title="随机生成">🎲</button>
            </div>
          </div>
          <div class="form-row">
            <label class="form-label">错误信息</label>
            <textarea
              v-model="message"
              class="form-input"
              rows="3"
              placeholder="输入模拟的错误信息..."
            ></textarea>
          </div>
          <div class="form-row">
            <label class="form-label">打印次数</label>
            <input
              v-model.number="count"
              type="number"
              class="form-input"
              min="1"
              max="20"
              placeholder="1-20"
            />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="handleReset">重置</button>
          <button class="btn btn-primary" @click="handleSubmit" :disabled="executing">
            {{ executing ? '执行中...' : '确认' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
}

.modal-content {
  background: var(--bg);
  border: 1px solid var(--color-line);
  border-radius: 12px;
  padding: 24px;
  width: 500px;
  max-width: 90vw;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.modal-close {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 20px;
  color: var(--muted);
  padding: 4px;
}

.modal-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
}

.form-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--color-line);
  border-radius: 8px;
  font-size: 14px;
  background: var(--bg-soft);
  color: var(--text);
  transition: border-color 0.2s;
}

.form-input:focus {
  outline: none;
  border-color: var(--primary);
}

textarea.form-input {
  resize: vertical;
  min-height: 80px;
}

.input-group {
  display: flex;
  gap: 8px;
}

.input-group .form-input {
  flex: 1;
}

.btn-sm {
  padding: 8px 12px;
  font-size: 14px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--color-line);
}
</style>
