/**
 * 站点设置组合式函数（导出 Markdown 等）
 * 2026-10-02 T0003：双入口导出按钮与文件流下载
 */
export function useAdminSettings() {
  const exporting = ref(false)
  const $toast = useToast()
  const config = useRuntimeConfig()
  const { token } = useAuth()
  const { deviceId } = useDevice()

  /**
   * 调用 GET /admin/settings/export-md 获取 Blob 并触发浏览器自动保存 site-settings-*.md
   */
  const exportSettingsMd = async () => {
    if (exporting.value) return
    exporting.value = true
    try {
      const headers: Record<string, string> = {}
      if (token.value) headers['Authorization'] = `Bearer ${token.value}`
      if (deviceId.value) headers['X-Device-Id'] = deviceId.value

      const base = config.public.apiBase || ''
      const res = await fetch(`${base}/admin/settings/export-md`, {
        method: 'GET',
        headers
      })

      if (!res.ok) {
        let errorMsg = `HTTP ${res.status}`
        try {
          const errData = await res.json()
          if (errData?.message) errorMsg = errData.message
        } catch {
          // ignore json parse error
        }
        throw new Error(errorMsg)
      }

      // 解析 Content-Disposition 响应头中的文件名
      const dispo = res.headers.get('Content-Disposition') || ''
      let filename = 'site-settings.md'
      const utf8Match = dispo.match(/filename\*=UTF-8''([^;]+)/)
      if (utf8Match) {
        filename = decodeURIComponent(utf8Match[1])
      } else {
        const quotedMatch = dispo.match(/filename="?([^";]+)"?/)
        if (quotedMatch) {
          filename = decodeURIComponent(quotedMatch[1])
        }
      }

      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)

      $toast.success('配置导出成功')
    } catch (e: any) {
      $toast.error('导出配置失败: ' + (e?.message || '未知错误'))
      throw e
    } finally {
      exporting.value = false
    }
  }

  return {
    exporting,
    exportSettingsMd
  }
}
