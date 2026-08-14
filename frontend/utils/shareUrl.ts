/**
 * 2026-08-14 DEV-003：智能文章分享 URL 解析器
 * 支持：
 * 1. 本地环境 (localhost/127.0.0.1) -> 优先替换为局域网 IP (192.168.x.x / 10.x.x.x)，便于手机同 Wi-Fi 扫码直连
 * 2. 生产环境三级降级：
 *    - Tier 1: 生产 HTTPS + 域名 (如 https://blog.coreyai.cn/post/xxx)
 *    - Tier 2: 生产 HTTP + 域名 (如 http://blog.coreyai.cn/post/xxx)
 *    - Tier 3: 生产 HTTP + 公网 IP (如 http://47.100.x.x:8080/post/xxx)
 */

export interface ResolveShareUrlOptions {
  lanIp?: string
}

export function isLocalHost(hostname: string): boolean {
  if (!hostname) return true
  const h = hostname.toLowerCase()
  return h === 'localhost' || h === '127.0.0.1' || h === '0.0.0.0' || h === '::1'
}

export function isIpAddress(hostname: string): boolean {
  if (!hostname) return false
  return /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(hostname)
}

export function resolveShareUrl(slug: string, options: ResolveShareUrlOptions = {}): { url: string; isLan: boolean } {
  const postPath = `/post/${encodeURIComponent(slug)}`

  if (!import.meta.client || typeof window === 'undefined') {
    return { url: postPath, isLan: false }
  }

  const { protocol, hostname, port } = window.location
  const portSuffix = port && port !== '80' && port !== '443' ? `:${port}` : ''

  // 1. 本地环境处理 (localhost / 127.0.0.1)
  if (isLocalHost(hostname)) {
    const lan = options.lanIp && !isLocalHost(options.lanIp) ? options.lanIp : ''
    if (lan) {
      return {
        url: `http://${lan}${portSuffix}${postPath}`,
        isLan: true
      }
    }
    return {
      url: `${protocol}//${hostname}${portSuffix}${postPath}`,
      isLan: true
    }
  }

  // 2. 生产环境三级降级
  // Tier 1: 生产 HTTPS + 域名 (首选)
  if (protocol === 'https:' && !isIpAddress(hostname)) {
    return {
      url: `https://${hostname}${portSuffix}${postPath}`,
      isLan: false
    }
  }

  // Tier 2: 生产 HTTP + 域名 (降级一)
  if (protocol === 'http:' && !isIpAddress(hostname)) {
    return {
      url: `http://${hostname}${portSuffix}${postPath}`,
      isLan: false
    }
  }

  // Tier 3: 生产 HTTP + 公网 IP (降级二)
  return {
    url: `${protocol}//${hostname}${portSuffix}${postPath}`,
    isLan: false
  }
}
