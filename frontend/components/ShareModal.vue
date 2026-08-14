<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { renderQRCodeToCanvas } from '~/utils/qrcode'
import { resolveShareUrl, isLocalHost } from '~/utils/shareUrl'

const props = defineProps<{
  show: boolean
  title: string
  slug?: string
  url?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const $toast = useToast()
const { get } = usePublicApi()
const canvasRef = ref<HTMLCanvasElement | null>(null)
const copied = ref(false)
const serverLanIp = ref<string>('')

// 计算最终分享 URL 与环境标志
const shareInfo = computed(() => {
  if (props.slug) {
    return resolveShareUrl(props.slug, { lanIp: serverLanIp.value })
  }
  if (props.url) {
    const isLan = import.meta.client && typeof window !== 'undefined'
      ? isLocalHost(window.location.hostname)
      : false
    return { url: props.url, isLan }
  }
  return { url: '', isLan: false }
})

const effectiveUrl = computed(() => shareInfo.value.url)
const isLanMode = computed(() => shareInfo.value.isLan)

const renderQR = () => {
  if (!canvasRef.value || !effectiveUrl.value) return
  renderQRCodeToCanvas(canvasRef.value, effectiveUrl.value, {
    size: 200,
    margin: 2,
    colorDark: '#111827',
    colorLight: '#ffffff'
  })
}

watch(
  () => [props.show, effectiveUrl.value],
  async ([show]) => {
    if (show) {
      copied.value = false
      await nextTick()
      renderQR()
    }
  },
  { immediate: true }
)

const handleCopy = async () => {
  if (!effectiveUrl.value) return
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(effectiveUrl.value)
    } else {
      const input = document.createElement('input')
      input.value = effectiveUrl.value
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    copied.value = true
    $toast.success('文章链接已复制到剪贴板')
    setTimeout(() => {
      copied.value = false
    }, 2500)
  } catch (err) {
    $toast.error('复制失败，请手动复制浏览器地址栏')
  }
}

const handleDownload = () => {
  if (!canvasRef.value) return
  try {
    const dataUrl = canvasRef.value.toDataURL('image/png')
    const a = document.createElement('a')
    a.href = dataUrl
    a.download = `${props.title ? props.title.slice(0, 20) : '文章'}-二维码.png`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    $toast.success('二维码已保存到本地')
  } catch (err) {
    $toast.error('保存二维码失败')
  }
}

// ESC 键关闭弹窗
const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && props.show) {
    emit('close')
  }
}

onMounted(async () => {
  if (import.meta.client) {
    window.addEventListener('keydown', onKeydown)
    // 获取服务器局域网 IP（用于本地调试扫码）
    try {
      const res = await get<any>('/public/server-info')
      if (res?.data?.lanIp) {
        serverLanIp.value = res.data.lanIp
      }
    } catch {
      // 静默失败，保持默认 URL
    }
  }
})

onUnmounted(() => {
  if (import.meta.client) {
    window.removeEventListener('keydown', onKeydown)
  }
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="show" class="share-modal-backdrop" @click.self="$emit('close')">
        <div class="share-modal-card" role="dialog" aria-modal="true" aria-labelledby="share-modal-title">
          <!-- 头部 -->
          <div class="share-modal-header">
            <div class="flex items-center gap-2">
              <span class="share-modal-icon">📱</span>
              <h3 id="share-modal-title" class="share-modal-title">手机扫码阅读 / 分享</h3>
            </div>
            <button class="share-modal-close" @click="$emit('close')" aria-label="关闭">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>

          <!-- 文章标题预览 -->
          <p class="share-modal-article-title" :title="title">
            {{ title }}
          </p>

          <!-- 二维码展示区 -->
          <div class="share-modal-qr-container">
            <div class="share-modal-qr-box">
              <canvas ref="canvasRef" class="share-modal-canvas"></canvas>
            </div>
            <p class="share-modal-hint">
              <span v-if="isLanMode" class="share-lan-badge">局域网调试模式</span>
              微信或手机浏览器「扫一扫」<br />
              <span v-if="isLanMode" class="share-lan-tip">（手机与电脑连同一 Wi-Fi 即可直接打开）</span>
              <span v-else>随时随地畅快阅读</span>
            </p>
          </div>

          <!-- 链接输入展示与操作 -->
          <div class="share-modal-url-box">
            <input
              type="text"
              readonly
              :value="effectiveUrl"
              class="share-modal-url-input"
              @focus="($event.target as HTMLInputElement).select()"
            />
          </div>

          <!-- 底部操作按钮 -->
          <div class="share-modal-actions">
            <button class="btn btn-primary share-action-btn" @click="handleCopy">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
                <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
              </svg>
              {{ copied ? '已复制 ✓' : '复制链接' }}
            </button>
            <button class="btn btn-secondary share-action-btn" @click="handleDownload">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                <polyline points="7 10 12 15 17 10"/>
                <line x1="12" x2="12" y1="15" y2="3"/>
              </svg>
              保存二维码
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.share-modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 16px;
}

.share-modal-card {
  width: 100%;
  max-width: 380px;
  background: var(--card, #ffffff);
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 16px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.2), 0 10px 10px -5px rgba(0, 0, 0, 0.1);
  padding: 24px;
  color: var(--text-1, #111827);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.share-modal-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.share-modal-icon {
  font-size: 18px;
}

.share-modal-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1, #111827);
  margin: 0;
}

.share-modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  color: var(--text-2, #6b7280);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
}

.share-modal-close:hover {
  background: var(--bg-soft, #f3f4f6);
  color: var(--text-1, #111827);
}

.share-modal-article-title {
  font-size: 14px;
  color: var(--text-2, #6b7280);
  margin: 0 0 16px 0;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0 4px;
}

.share-modal-qr-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 16px;
}

.share-modal-qr-box {
  padding: 12px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
}

.share-modal-canvas {
  display: block;
  border-radius: 4px;
}

.share-modal-hint {
  font-size: 12px;
  color: var(--text-2, #6b7280);
  margin-top: 10px;
  line-height: 1.5;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

.share-lan-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: #0284c7;
  background: #e0f2fe;
  padding: 2px 8px;
  border-radius: 9999px;
  margin-bottom: 2px;
}

.share-lan-tip {
  font-size: 11px;
  color: var(--primary, #2f6f5e);
}

.share-modal-url-box {
  width: 100%;
  margin-bottom: 16px;
}

.share-modal-url-input {
  width: 100%;
  font-size: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--bg-soft, #f3f4f6);
  border: 1px solid var(--line, #e5e7eb);
  color: var(--text-2, #6b7280);
  outline: none;
  font-family: monospace;
}

.share-modal-actions {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.share-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

/* 动效 */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

.modal-fade-enter-from .share-modal-card,
.modal-fade-leave-to .share-modal-card {
  transform: scale(0.95);
}
</style>
