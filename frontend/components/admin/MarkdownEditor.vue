<script setup lang="ts">
/**
 * C（2026-06-20）：Markdown 编辑器组件，从 edit.vue 抽出。
 * 包含工具栏 + textarea（支持图片粘贴/拖拽）+ 实时预览双栏。
 */
import DOMPurify from 'dompurify'
// 2026-06-22 抽出 renderMarkdown → composables/useMarkdownUtils.ts
// 之前 MarkdownEditor 与 post/[slug].vue 各有一份独立实现，已制造过
// "editor 修过图片语法但 post 页漏改" 的历史 bug（2026-06-16 BUG-078）。
import { renderMarkdown } from '~/composables/useMarkdownUtils'

const props = defineProps<{
  modelValue: string
  upload: (path: string, file: File) => Promise<any>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

const $toast = useToast()
const $dialog = useDialog()

const content = computed({
  get: () => props.modelValue,
  set: (v: string) => emit('update:modelValue', v)
})

const textareaRef = ref<HTMLTextAreaElement | null>(null)

const { mdImageInput, uploadingImages, handleMdImageFileChange, handleEditorPaste, handleEditorDrop, handleEditorDragOver, insertImageAtCursor } =
  useImageUpload(
    content,
    textareaRef,
    props.upload,
    (msg) => $toast.error(msg)
  )

const wordCount = computed(() => props.modelValue.length)
const readTime = computed(() => Math.max(1, Math.round(wordCount.value / 400)))

const insertMarkdown = (before: string, after = before, placeholder = '') => {
  const ta = textareaRef.value
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const md = content.value
  const selected = md.substring(start, end) || placeholder
  content.value = md.substring(0, start) + before + selected + after + md.substring(end)
  nextTick(() => {
    const pos = start + before.length
    ta.focus()
    ta.setSelectionRange(pos, pos + selected.length)
  })
}

const wrapLine = (prefix: string) => {
  const ta = textareaRef.value
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const md = content.value
  const lineStart = md.lastIndexOf('\n', start - 1) + 1
  const block = md.substring(lineStart, end)
  const transformed = block.split('\n').map((l: string) => prefix + l).join('\n')
  content.value = md.substring(0, lineStart) + transformed + md.substring(end)
  nextTick(() => ta.focus())
}

const insertImagePrompt = async () => {
  const { confirmed: useLocal } = await $dialog.confirm({
    title: '插入图片',
    message: '点「本地」从本地上传图片（粘贴板 / 拖拽也可）；\n点「取消」输入一个网络图片 URL。',
    confirmText: '本地',
    cancelText: '输入 URL'
  })
  if (useLocal) {
    mdImageInput.value?.click()
  } else {
    const { confirmed, value } = await $dialog.prompt({
      title: '插入网络图片',
      label: '图片 URL',
      placeholder: 'https://...',
      confirmText: '插入'
    })
    if (!confirmed || !value) return
    insertMarkdown(`![`, `](${value})`, 'alt 文本')
  }
}

const safeMarkdown = (md: string) => DOMPurify.sanitize(renderMarkdown(md), {
  ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'strong', 'em', 'a', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'br', 'hr', 'img'],
  ALLOWED_ATTR: ['href', 'target', 'class', 'src', 'alt', 'loading']
})
</script>

<template>
  <div>
    <div class="editor-toolbar">
      <input ref="mdImageInput" type="file" accept="image/*" multiple style="display: none;" @change="handleMdImageFileChange" />
      <button class="editor-tool" type="button" title="二级标题" @click="wrapLine('## ')"><strong style="font-size:13px;">H2</strong></button>
      <button class="editor-tool" type="button" title="三级标题" @click="wrapLine('### ')"><strong style="font-size:12px;">H3</strong></button>
      <span class="editor-tool divider"></span>
      <button class="editor-tool" type="button" title="粗体" @click="insertMarkdown('**', '**', '粗体文本')"><strong>B</strong></button>
      <button class="editor-tool" type="button" title="斜体" @click="insertMarkdown('*', '*', '斜体文本')"><em>I</em></button>
      <button class="editor-tool" type="button" title="删除线" @click="insertMarkdown('~~', '~~', '删除文本')"><s>S</s></button>
      <span class="editor-tool divider"></span>
      <button class="editor-tool" type="button" title="链接" @click="insertMarkdown('[', '](https://)', '链接文字')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
      </button>
      <button class="editor-tool" type="button" title="图片（本地上传 / 也可粘贴图片到编辑器 / 拖拽图片）" @click="insertImagePrompt">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>
      </button>
      <button class="editor-tool" type="button" title="引用" @click="wrapLine('> ')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 21c3 0 7-1 7-8V5c0-1.25-.756-2.017-2-2H4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2 1 0 1 0 1 1v1c0 1-1 2-2 2s-1 .008-1 1.031V20c0 1 0 1 1 1z"/><path d="M15 21c3 0 7-8 7-8V5c0-1.25-.757-2.017-2-2h-4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2h.75c0 2.25.25 4-2.75 4v3z"/></svg>
      </button>
      <button class="editor-tool" type="button" title="代码块" @click="insertMarkdown('\n```\n', '\n```\n', '代码')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
      </button>
      <span class="editor-tool divider"></span>
      <button class="editor-tool" type="button" title="无序列表" @click="wrapLine('- ')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      </button>
      <button class="editor-tool" type="button" title="有序列表" @click="wrapLine('1. ')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="8.01" y2="8"/><line x1="12" y1="6" x2="12.01" y2="8"/></svg>
      </button>
      <span class="editor-tool divider"></span>
      <div class="editor-toolbar-right">
        <span v-if="Object.keys(uploadingImages).length" class="upload-progress" style="font-size: 11px; color: var(--accent, #c97b3f); margin-right: 8px;">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="vertical-align: -1px; animation: spin 1s linear infinite;"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
          {{ Object.keys(uploadingImages).length }} 张图片上传中…
        </span>
        <span class="word-count">{{ wordCount.toLocaleString() }} 字 · {{ readTime }} 分钟阅读</span>
      </div>
    </div>

    <div class="editor-split">
      <div class="editor-pane editor-source">
        <span class="editor-pane-label">MARKDOWN</span>
        <textarea
          ref="textareaRef"
          :value="modelValue"
          @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
          class="editor-textarea"
          placeholder="开始用 Markdown 写作…（可直接 Ctrl/Cmd+V 粘贴图片 / 拖拽图片到此处）"
          @paste="handleEditorPaste"
          @drop="handleEditorDrop"
          @dragover="handleEditorDragOver"
        ></textarea>
      </div>
      <div class="editor-pane">
        <span class="editor-pane-label">PREVIEW</span>
        <ClientOnly>
          <div class="editor-preview" v-html="safeMarkdown(modelValue)"></div>
          <template #fallback>
            <div class="editor-preview" style="color: var(--muted); font-size: 13px;">预览加载中…</div>
          </template>
        </ClientOnly>
      </div>
    </div>
  </div>
</template>
