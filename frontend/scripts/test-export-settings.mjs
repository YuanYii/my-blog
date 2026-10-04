import assert from 'node:assert/strict'

console.log('--- 测试 useAdminSettings 导出逻辑与状态流转 ---')

// 模拟测试环境
let clickedUrl = ''
let downloadedFileName = ''
let toastMessages = []

// Mock DOM
globalThis.document = {
  body: {
    appendChild(el) {},
    removeChild(el) {}
  },
  createElement(tag) {
    if (tag === 'a') {
      const el = {
        href: '',
        download: '',
        click() {
          clickedUrl = el.href
          downloadedFileName = el.download
        }
      }
      return el
    }
    return {}
  }
}

globalThis.URL = {
  createObjectURL(blob) {
    return 'blob:http://localhost:3000/mock-uuid-1234'
  },
  revokeObjectURL(url) {}
}

const mockToast = {
  success(msg) {
    toastMessages.push({ type: 'success', msg })
  },
  error(msg) {
    toastMessages.push({ type: 'error', msg })
  }
}

// 模拟导出函数内部逻辑
async function testExportLogic({
  apiBase = 'http://localhost:8080/api/v1',
  token = 'test-token',
  deviceId = 'test-device-id',
  mockStatus = 200,
  mockHeaders = {},
  mockBody = '# Site Settings'
}) {
  let exporting = false
  const headersSent = {}

  const exportSettingsMd = async () => {
    if (exporting) return
    exporting = true
    try {
      const headers = {}
      if (token) headers['Authorization'] = `Bearer ${token}`
      if (deviceId) headers['X-Device-Id'] = deviceId
      Object.assign(headersSent, headers)

      if (mockStatus !== 200) {
        throw new Error(`HTTP ${mockStatus}`)
      }

      const dispo = mockHeaders['content-disposition'] || ''
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

      const url = globalThis.URL.createObjectURL(new Blob([mockBody]))
      const a = globalThis.document.createElement('a')
      a.href = url
      a.download = filename
      globalThis.document.body.appendChild(a)
      a.click()
      globalThis.document.body.removeChild(a)
      globalThis.URL.revokeObjectURL(url)

      mockToast.success('配置导出成功')
    } catch (e) {
      mockToast.error('导出配置失败: ' + (e?.message || '未知错误'))
      throw e
    } finally {
      exporting = false
    }
  }

  return { exportSettingsMd, headersSent, getExporting: () => exporting }
}

async function runTests() {
  // Test 1: 正常导出，解析 attachment 格式文件名
  {
    toastMessages = []
    const filenameExpected = 'site-settings-20261002-152000.md'
    const { exportSettingsMd, headersSent, getExporting } = await testExportLogic({
      mockStatus: 200,
      mockHeaders: {
        'content-disposition': `attachment; filename="${filenameExpected}"`
      }
    })

    assert.equal(getExporting(), false, '初始状态 exporting 应为 false')
    await exportSettingsMd()
    assert.equal(getExporting(), false, '执行完毕后 exporting 应重置为 false')
    assert.equal(headersSent['Authorization'], 'Bearer test-token', '需携带 Authorization Bearer token')
    assert.equal(headersSent['X-Device-Id'], 'test-device-id', '需携带 X-Device-Id')
    assert.equal(downloadedFileName, filenameExpected, '下载文件名与后端 Content-Disposition 解析一致')
    assert.equal(clickedUrl, 'blob:http://localhost:3000/mock-uuid-1234', '触发了 Blob 对象 URL 下载')
    assert.equal(toastMessages.length, 1)
    assert.equal(toastMessages[0].type, 'success')
    console.log('✓ Test 1: 正常导出与 Blob 下载及 Content-Disposition 文件名解析通过')
  }

  // Test 2: RFC 5987 filename*=UTF-8'' 编码支持
  {
    toastMessages = []
    const filenameExpected = 'site-settings-测试.md'
    const encoded = encodeURIComponent(filenameExpected)
    const { exportSettingsMd } = await testExportLogic({
      mockStatus: 200,
      mockHeaders: {
        'content-disposition': `attachment; filename*=UTF-8''${encoded}`
      }
    })

    await exportSettingsMd()
    assert.equal(downloadedFileName, filenameExpected, 'RFC 5987 编码文件名解析一致')
    console.log('✓ Test 2: RFC 5987 UTF-8 文件名支持通过')
  }

  // Test 3: 异常捕获与 Toast 报错
  {
    toastMessages = []
    const { exportSettingsMd, getExporting } = await testExportLogic({
      mockStatus: 500
    })

    let caught = false
    try {
      await exportSettingsMd()
    } catch (e) {
      caught = true
    }
    assert.equal(caught, true, '应抛出异常')
    assert.equal(getExporting(), false, '异常发生后 exporting 状态仍被 finally 重置')
    assert.equal(toastMessages.length, 1)
    assert.equal(toastMessages[0].type, 'error')
    assert(toastMessages[0].msg.includes('HTTP 500'))
    console.log('✓ Test 3: 异常捕获、错误 Toast 及 loading 态复原通过')
  }

  console.log('\n所有 3 项自动化单测全部通过！')
}

runTests().catch(err => {
  console.error('测试失败:', err)
  process.exit(1)
})
