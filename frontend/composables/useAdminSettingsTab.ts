/**
 * 2026-06-23 OPT-005：封装 admin/settings 子页通用的 load/save/saving/message 模板代码。
 * 调用方拥有 `state`（reactive），composable 负责拉取 + 写回 + 状态展示。
 */
export function useAdminSettingsTab<T extends object>(opts: {
  endpoint: string
  state: T
  onSaved?: (data: T) => void
}) {
  const { get, put } = useAdminApi()
  const saving = ref(false)
  const message = ref('')
  let timer: ReturnType<typeof setTimeout> | null = null

  const load = async () => {
    try {
      const res = await get<any>(opts.endpoint)
      if (res.data) Object.assign(opts.state, res.data)
    } catch { /* 默认值 */ }
  }

  const save = async () => {
    saving.value = true
    message.value = ''
    try {
      await put(opts.endpoint, opts.state)
      opts.onSaved?.(opts.state)
      message.value = '已保存 ✓'
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => (message.value = ''), 2000)
    } catch (e: any) {
      message.value = '保存失败：' + (e?.data?.message || e?.message)
    } finally {
      saving.value = false
    }
  }

  onMounted(load)

  return { saving, message, load, save }
}
