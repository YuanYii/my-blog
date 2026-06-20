/**
 * C（2026-06-20）：Markdown 编辑器图片上传逻辑，从 edit.vue 抽出。
 * 覆盖：封面上传、粘贴/拖拽/工具栏选图三条路径。
 */
export function useImageUpload(
  contentMd: Ref<string>,
  textareaRef: Ref<HTMLTextAreaElement | null>,
  uploadFn: (path: string, file: File) => Promise<any>,
  onError: (msg: string) => void
) {
  const mdImageInput = ref<HTMLInputElement | null>(null)
  const uploadingImages = reactive<Record<string, boolean>>({})

  const uploadAndInsertImage = async (file: File, placeholder: string, alt: string) => {
    if (!file.type.startsWith('image/')) {
      onError('只支持图片文件（png/jpg/gif/webp/bmp/ico）')
      contentMd.value = contentMd.value.replace(placeholder, '')
      return
    }
    if (file.size > 5 * 1024 * 1024) {
      onError(`图片「${file.name}」超过 5MB 上限`)
      contentMd.value = contentMd.value.replace(placeholder, '')
      return
    }
    uploadingImages[placeholder] = true
    try {
      const res = await uploadFn('/admin/uploads', file)
      const url = res.data?.url
      if (!url) throw new Error('上传响应缺 url')
      const finalAlt = alt || file.name.replace(/\.[^.]+$/, '')
      contentMd.value = contentMd.value.replace(placeholder, `![${finalAlt}](${url})`)
    } catch (err: any) {
      onError('图片上传失败：' + (err?.data?.message || err?.message))
      contentMd.value = contentMd.value.replace(placeholder, '')
    } finally {
      delete uploadingImages[placeholder]
    }
  }

  const insertImageAtCursor = (file: File) => {
    const ta = textareaRef.value
    const placeholder = `![uploading-${file.name}…]()`
    if (!ta) {
      contentMd.value += (contentMd.value.endsWith('\n') ? '' : '\n') + placeholder + '\n'
      uploadAndInsertImage(file, placeholder, file.name)
      return
    }
    const start = ta.selectionStart
    const end = ta.selectionEnd
    const md = contentMd.value
    const prefix = (start === 0 || md[start - 1] === '\n') ? '' : '\n'
    const suffix = (end === md.length || md[end] === '\n') ? '' : '\n'
    const insertion = prefix + placeholder + suffix
    contentMd.value = md.substring(0, start) + insertion + md.substring(end)
    nextTick(() => {
      const cursorPos = start + insertion.length
      ta.focus()
      ta.setSelectionRange(cursorPos, cursorPos)
    })
    uploadAndInsertImage(file, placeholder, file.name)
  }

  const handleMdImageFileChange = (e: Event) => {
    const input = e.target as HTMLInputElement
    const files = input.files
    if (!files || !files.length) return
    for (const file of Array.from(files)) insertImageAtCursor(file)
    input.value = ''
  }

  const handleEditorPaste = (e: ClipboardEvent) => {
    if (!e.clipboardData) return
    const imageFiles: File[] = []
    for (let i = 0; i < e.clipboardData.items.length; i++) {
      const item = e.clipboardData.items[i]
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const f = item.getAsFile()
        if (f) {
          if (!f.name || f.name === 'image.png') {
            const ext = (f.type.split('/')[1] || 'png').split(';')[0]
            imageFiles.push(new File([f], `pasted-${Date.now()}.${ext}`, { type: f.type }))
          } else {
            imageFiles.push(f)
          }
        }
      }
    }
    if (imageFiles.length === 0) return
    e.preventDefault()
    for (const file of imageFiles) insertImageAtCursor(file)
  }

  const handleEditorDrop = (e: DragEvent) => {
    if (!e.dataTransfer) return
    const files = Array.from(e.dataTransfer.files).filter(f => f.type.startsWith('image/'))
    if (files.length === 0) return
    e.preventDefault()
    for (const file of files) insertImageAtCursor(file)
  }

  const handleEditorDragOver = (e: DragEvent) => {
    if (e.dataTransfer && Array.from(e.dataTransfer.items).some(i => i.kind === 'file' && i.type.startsWith('image/'))) {
      e.preventDefault()
    }
  }

  return {
    mdImageInput,
    uploadingImages,
    insertImageAtCursor,
    handleMdImageFileChange,
    handleEditorPaste,
    handleEditorDrop,
    handleEditorDragOver
  }
}
