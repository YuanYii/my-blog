// Minimal static file server with /api proxy
// 2026-06-28 v5.0.0 DEV-001 e2e test server
//   - 静态文件：serve .output/public
//   - /api/* 代理到 http://localhost:8080
//   - 端口 3000
//
// 为什么需要：npx serve 不支持 proxy；nuxt dev 太重（HMR / 1.3GB 内存 / 107% CPU）
// 用于 Playwright e2e 测试，替代 nuxt dev 跑 stage 5 集成测试

import { createReadStream, statSync, existsSync, readFileSync } from 'node:fs'
import { resolve, extname, join } from 'node:path'
import http from 'node:http'

const ROOT = resolve(process.cwd(), '.output/public')
const PORT = 3000
const BACKEND = 'http://localhost:8080'

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript; charset=utf-8',
  '.mjs':  'application/javascript; charset=utf-8',
  '.css':  'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png':  'image/png',
  '.jpg':  'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg':  'image/svg+xml',
  '.ico':  'image/x-icon',
  '.woff': 'font/woff',
  '.woff2':'font/woff2',
  '.ttf':  'font/ttf',
  '.txt':  'text/plain; charset=utf-8'
}

const server = http.createServer(async (req, res) => {
  // /api/* → 代理到后端
  if (req.url?.startsWith('/api/')) {
    const targetUrl = BACKEND + req.url
    const proxyReq = http.request(targetUrl, {
      method: req.method,
      headers: { ...req.headers, host: new URL(BACKEND).host }
    }, (proxyRes) => {
      res.writeHead(proxyRes.statusCode || 502, proxyRes.headers)
      proxyRes.pipe(res)
    })
    proxyReq.on('error', (e) => {
      console.error('[proxy] error:', e.message)
      res.writeHead(502, { 'Content-Type': 'text/plain' })
      res.end('Bad gateway: ' + e.message)
    })
    req.pipe(proxyReq)
    return
  }

  // 静态文件
  let path = req.url?.split('?')[0] || '/'
  if (path === '/') path = '/index.html'

  // 路径遍历保护
  const fullPath = resolve(join(ROOT, path))
  if (!fullPath.startsWith(ROOT)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' })
    res.end('Forbidden')
    return
  }

  if (!existsSync(fullPath)) {
    // SPA 兜底：/admin/* 找不到时返回 /index.html（admin 走 SPA 客户端渲染）
    if (path.startsWith('/admin/') || path.startsWith('/search')) {
      const idxPath = join(ROOT, 'index.html')
      if (existsSync(idxPath)) {
        res.writeHead(200, { 'Content-Type': MIME['.html'] })
        createReadStream(idxPath).pipe(res)
        return
      }
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' })
    res.end('Not found: ' + path)
    return
  }

  const stat = statSync(fullPath)
  if (stat.isDirectory()) {
    const idxPath = join(fullPath, 'index.html')
    if (existsSync(idxPath)) {
      res.writeHead(200, { 'Content-Type': MIME['.html'] })
      createReadStream(idxPath).pipe(res)
      return
    }
    res.writeHead(403, { 'Content-Type': 'text/plain' })
    res.end('Directory listing disabled')
    return
  }

  const ext = extname(fullPath).toLowerCase()
  res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' })
  createReadStream(fullPath).pipe(res)
})

server.listen(PORT, () => {
  console.log(`[e2e-server] static + proxy on :${PORT}`)
  console.log(`[e2e-server] static root: ${ROOT}`)
  console.log(`[e2e-server] /api/* proxy → ${BACKEND}`)
})
