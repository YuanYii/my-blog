/**
 * 文件大小格式化（2026-07-01 DEV-002）
 *
 * 不引依赖（format-bytes / pretty-bytes 等 npm 包太重），
 * 自己实现 B / KB / MB 三档够用——文章附件场景最大 5MB。
 */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null || isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

export const useFileSize = () => {
  return { formatFileSize }
}