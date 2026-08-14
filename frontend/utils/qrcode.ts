/**
 * 纯 TypeScript 轻量级 QR Code (Model 2) 生成器
 * 零第三方 npm 依赖，支持 Byte 模式与 Reed-Solomon 纠错码计算
 * 适用于纯离线客户端生成文章分享二维码
 */

// Galois Field GF(256) 运算表 (primitive polynomial: x^8 + x^4 + x^3 + x^2 + 1 = 0x11d)
const EXP_TABLE = new Uint8Array(256)
const LOG_TABLE = new Uint8Array(256)

;(() => {
  let val = 1
  for (let i = 0; i < 255; i++) {
    EXP_TABLE[i] = val
    LOG_TABLE[val] = i
    val <<= 1
    if (val & 0x100) val ^= 0x11d
  }
  EXP_TABLE[255] = EXP_TABLE[0]
})()

function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0
  return EXP_TABLE[(LOG_TABLE[a] + LOG_TABLE[b]) % 255]
}

function rsGeneratorPoly(degree: number): Uint8Array {
  let poly = new Uint8Array([1])
  for (let i = 0; i < degree; i++) {
    const next = new Uint8Array(poly.length + 1)
    const factor = EXP_TABLE[i]
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j]
      next[j + 1] ^= gfMul(poly[j], factor)
    }
    poly = next
  }
  return poly
}

function rsCalculateEcc(data: Uint8Array, eccCount: number): Uint8Array {
  const gen = rsGeneratorPoly(eccCount)
  const remainder = new Uint8Array(eccCount)
  for (let i = 0; i < data.length; i++) {
    const factor = data[i] ^ remainder[0]
    for (let j = 0; j < eccCount - 1; j++) {
      remainder[j] = remainder[j + 1] ^ gfMul(gen[j + 1], factor)
    }
    remainder[eccCount - 1] = gfMul(gen[eccCount], factor)
  }
  return remainder
}

// 常用 QR 规范数据容量与块信息表（ECC Level M）
interface VersionInfo {
  version: number
  totalCodewords: number
  dataCodewords: number
  eccCodewords: number
  blocks: { count: number; total: number; data: number }[]
  alignmentPatternPositions: number[]
}

const VERSION_INFO_M: VersionInfo[] = [
  {
    version: 1,
    totalCodewords: 26,
    dataCodewords: 16,
    eccCodewords: 10,
    blocks: [{ count: 1, total: 26, data: 16 }],
    alignmentPatternPositions: []
  },
  {
    version: 2,
    totalCodewords: 44,
    dataCodewords: 28,
    eccCodewords: 16,
    blocks: [{ count: 1, total: 44, data: 28 }],
    alignmentPatternPositions: [6, 18]
  },
  {
    version: 3,
    totalCodewords: 70,
    dataCodewords: 44,
    eccCodewords: 26,
    blocks: [{ count: 1, total: 70, data: 44 }],
    alignmentPatternPositions: [6, 22]
  },
  {
    version: 4,
    totalCodewords: 100,
    dataCodewords: 64,
    eccCodewords: 36,
    blocks: [{ count: 2, total: 50, data: 32 }],
    alignmentPatternPositions: [6, 26]
  },
  {
    version: 5,
    totalCodewords: 134,
    dataCodewords: 86,
    eccCodewords: 48,
    blocks: [{ count: 2, total: 67, data: 43 }],
    alignmentPatternPositions: [6, 30]
  },
  {
    version: 6,
    totalCodewords: 172,
    dataCodewords: 108,
    eccCodewords: 64,
    blocks: [{ count: 4, total: 43, data: 27 }],
    alignmentPatternPositions: [6, 34]
  },
  {
    version: 7,
    totalCodewords: 196,
    dataCodewords: 124,
    eccCodewords: 72,
    blocks: [{ count: 4, total: 49, data: 31 }],
    alignmentPatternPositions: [6, 22, 38]
  },
  {
    version: 8,
    totalCodewords: 242,
    dataCodewords: 154,
    eccCodewords: 88,
    blocks: [{ count: 2, total: 61, data: 39 }, { count: 2, total: 60, data: 38 }],
    alignmentPatternPositions: [6, 24, 42]
  },
  {
    version: 9,
    totalCodewords: 292,
    dataCodewords: 182,
    eccCodewords: 110,
    blocks: [{ count: 3, total: 58, data: 36 }, { count: 2, total: 59, data: 37 }],
    alignmentPatternPositions: [6, 26, 46]
  },
  {
    version: 10,
    totalCodewords: 346,
    dataCodewords: 216,
    eccCodewords: 130,
    blocks: [{ count: 4, total: 69, data: 43 }, { count: 1, total: 70, data: 44 }],
    alignmentPatternPositions: [6, 28, 50]
  }
]

// 掩码函数 (Mask Pattern 0~7)
type MaskFn = (row: number, col: number) => boolean
const MASK_PATTERNS: MaskFn[] = [
  (r, c) => (r + c) % 2 === 0,
  (r, _) => r % 2 === 0,
  (_, c) => c % 3 === 0,
  (r, c) => (r + c) % 3 === 0,
  (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
  (r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
  (r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
  (r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0
]

// 格式信息编码（ECC Level M = 00，带 BCH 15,5 校验）
const FORMAT_INFO_M: number[] = [
  0x5412, 0x5125, 0x5e7c, 0x5b4b, 0x45f9, 0x40ce, 0x4f97, 0x4aa0
]

/**
 * 将文本编码为 8-bit Byte 模式的 bit 流
 */
function encodeData(text: string, vInfo: VersionInfo): Uint8Array {
  const utf8Bytes = new TextEncoder().encode(text)
  const charCountBits = vInfo.version <= 9 ? 8 : 16
  const totalDataBits = vInfo.dataCodewords * 8

  const bits: number[] = []
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) {
      bits.push((val >> i) & 1)
    }
  }

  // 1. Mode indicator (Byte mode: 0100)
  pushBits(0b0100, 4)
  // 2. Character count indicator
  pushBits(utf8Bytes.length, charCountBits)
  // 3. Data bytes
  for (const b of utf8Bytes) {
    pushBits(b, 8)
  }
  // 4. Terminator (up to 4 zeroes)
  const remaining = totalDataBits - bits.length
  pushBits(0, Math.min(4, Math.max(0, remaining)))
  // 5. Pad to byte boundary
  while (bits.length % 8 !== 0) {
    bits.push(0)
  }
  // 6. Pad bytes (0xEC, 0x11 alternate)
  const padBytes = [0xec, 0x11]
  let padIdx = 0
  while (bits.length < totalDataBits) {
    pushBits(padBytes[padIdx % 2], 8)
    padIdx++
  }

  const bytes = new Uint8Array(vInfo.dataCodewords)
  for (let i = 0; i < vInfo.dataCodewords; i++) {
    let byteVal = 0
    for (let j = 0; j < 8; j++) {
      byteVal = (byteVal << 1) | bits[i * 8 + j]
    }
    bytes[i] = byteVal
  }
  return bytes
}

/**
 * 分块并计算 Reed-Solomon 纠错码
 */
function createCodewords(dataBytes: Uint8Array, vInfo: VersionInfo): Uint8Array {
  const dataBlocks: Uint8Array[] = []
  const eccBlocks: Uint8Array[] = []
  let offset = 0

  for (const b of vInfo.blocks) {
    const eccCount = (b.total - b.data)
    for (let i = 0; i < b.count; i++) {
      const blockData = dataBytes.slice(offset, offset + b.data)
      offset += b.data
      const blockEcc = rsCalculateEcc(blockData, eccCount)
      dataBlocks.push(blockData)
      eccBlocks.push(blockEcc)
    }
  }

  // 交错放置数据码字和纠错码字
  const finalCodewords = new Uint8Array(vInfo.totalCodewords)
  let idx = 0

  let maxDataLen = 0
  for (const d of dataBlocks) {
    if (d.length > maxDataLen) maxDataLen = d.length
  }
  for (let i = 0; i < maxDataLen; i++) {
    for (const d of dataBlocks) {
      if (i < d.length) finalCodewords[idx++] = d[i]
    }
  }

  let maxEccLen = 0
  for (const e of eccBlocks) {
    if (e.length > maxEccLen) maxEccLen = e.length
  }
  for (let i = 0; i < maxEccLen; i++) {
    for (const e of eccBlocks) {
      if (i < e.length) finalCodewords[idx++] = e[i]
    }
  }

  return finalCodewords
}

/**
 * 绘制 QR 矩阵（含寻像图案、定位图案、校正图案、时序图案及掩码）
 */
export function generateQRCodeMatrix(text: string): boolean[][] {
  const utf8Len = new TextEncoder().encode(text).length
  // 选取适合的 Version (Level M)
  let vInfo: VersionInfo | null = null
  for (const info of VERSION_INFO_M) {
    // Byte mode overhead: 4 bits mode + 8/16 bits len
    const overhead = info.version <= 9 ? 2 : 3
    if (utf8Len + overhead <= info.dataCodewords) {
      vInfo = info
      break
    }
  }

  if (!vInfo) {
    // 超过 Version 10 选用最大容量
    vInfo = VERSION_INFO_M[VERSION_INFO_M.length - 1]
  }

  const size = vInfo.version * 4 + 17
  const matrix: (boolean | null)[][] = Array.from({ length: size }, () => Array(size).fill(null))
  const isFunctionPattern: boolean[][] = Array.from({ length: size }, () => Array(size).fill(false))

  const setModule = (r: number, c: number, val: boolean) => {
    matrix[r][c] = val
    isFunctionPattern[r][c] = true
  }

  // 1. 寻像图案 (Finder Patterns) 7x7 at 3 corners
  const drawFinderPattern = (startR: number, startC: number) => {
    for (let r = 0; r < 7; r++) {
      for (let c = 0; c < 7; c++) {
        const isBorder = r === 0 || r === 6 || c === 0 || c === 6
        const isCenter = r >= 2 && r <= 4 && c >= 2 && c <= 4
        setModule(startR + r, startC + c, isBorder || isCenter)
      }
    }
    // 分隔符 (Separators)
    for (let r = -1; r <= 7; r++) {
      for (let c = -1; c <= 7; c++) {
        const tr = startR + r
        const tc = startC + c
        if (tr >= 0 && tr < size && tc >= 0 && tc < size && matrix[tr][tc] === null) {
          setModule(tr, tc, false)
        }
      }
    }
  }
  drawFinderPattern(0, 0)
  drawFinderPattern(0, size - 7)
  drawFinderPattern(size - 7, 0)

  // 2. 校正图案 (Alignment Patterns)
  const pos = vInfo.alignmentPatternPositions
  for (let i = 0; i < pos.length; i++) {
    for (let j = 0; j < pos.length; j++) {
      const ar = pos[i]
      const ac = pos[j]
      if (matrix[ar][ac] !== null) continue
      for (let r = -2; r <= 2; r++) {
        for (let c = -2; c <= 2; c++) {
          const isBorder = Math.abs(r) === 2 || Math.abs(c) === 2
          const isCenter = r === 0 && c === 0
          setModule(ar + r, ac + c, isBorder || isCenter)
        }
      }
    }
  }

  // 3. 时序图案 (Timing Patterns)
  for (let i = 8; i < size - 8; i++) {
    if (matrix[6][i] === null) setModule(6, i, i % 2 === 0)
    if (matrix[i][6] === null) setModule(i, 6, i % 2 === 0)
  }

  // 4. 暗模块 (Dark Module)
  setModule(size - 8, 8, true)

  // 5. 格式信息预留位 (Format info reservation)
  for (let i = 0; i < 9; i++) {
    if (matrix[8][i] === null) isFunctionPattern[8][i] = true
    if (matrix[i][8] === null) isFunctionPattern[i][8] = true
  }
  for (let i = 0; i < 8; i++) {
    isFunctionPattern[8][size - 1 - i] = true
    isFunctionPattern[size - 1 - i][8] = true
  }

  // 6. 数据放置 (Data placement)
  const dataBytes = encodeData(text, vInfo)
  const codewords = createCodewords(dataBytes, vInfo)

  const dataBits: number[] = []
  for (const b of codewords) {
    for (let i = 7; i >= 0; i--) {
      dataBits.push((b >> i) & 1)
    }
  }

  let bitIdx = 0
  let upward = true
  for (let rightCol = size - 1; rightCol > 0; rightCol -= 2) {
    if (rightCol === 6) rightCol = 5 // 跳过垂直时序图案列
    for (let i = 0; i < size; i++) {
      const r = upward ? size - 1 - i : i
      for (let j = 0; j < 2; j++) {
        const c = rightCol - j
        if (!isFunctionPattern[r][c]) {
          const bitVal = bitIdx < dataBits.length ? dataBits[bitIdx++] === 1 : false
          matrix[r][c] = bitVal
        }
      }
    }
    upward = !upward
  }

  // 7. 应用掩码并绘制格式信息（默认 Pattern 0）
  const maskIdx = 0
  const maskFn = MASK_PATTERNS[maskIdx]
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      if (!isFunctionPattern[r][c]) {
        const original = matrix[r][c] === true
        matrix[r][c] = maskFn(r, c) ? !original : original
      }
    }
  }

  // 写入格式信息
  const formatVal = FORMAT_INFO_M[maskIdx]
  const formatBits: boolean[] = []
  for (let i = 14; i >= 0; i--) {
    formatBits.push(((formatVal >> i) & 1) === 1)
  }

  // 格式信息写入到左上角与边界
  for (let i = 0; i < 6; i++) matrix[8][i] = formatBits[i]
  matrix[8][7] = formatBits[6]
  matrix[8][8] = formatBits[7]
  matrix[7][8] = formatBits[8]
  for (let i = 9; i < 15; i++) matrix[14 - i][8] = formatBits[i]

  for (let i = 0; i < 8; i++) matrix[size - 1 - i][8] = formatBits[i]
  for (let i = 8; i < 15; i++) matrix[8][size - 15 + i] = formatBits[i]

  return matrix.map(row => row.map(cell => cell === true))
}

export interface QRCodeRenderOptions {
  size?: number
  margin?: number
  colorDark?: string
  colorLight?: string
}

/**
 * 将二维码高清晰绘制在 Canvas 上（支持 Retain/Retina 高分屏抗锯齿）
 */
export function renderQRCodeToCanvas(
  canvas: HTMLCanvasElement,
  text: string,
  options: QRCodeRenderOptions = {}
): void {
  const matrix = generateQRCodeMatrix(text)
  const moduleCount = matrix.length
  const size = options.size || 240
  const margin = options.margin !== undefined ? options.margin : 2
  const totalModules = moduleCount + margin * 2
  const cellSize = Math.floor(size / totalModules)
  const actualSize = cellSize * totalModules

  // 针对高分辨率屏幕缩放
  const dpr = typeof window !== 'undefined' ? (window.devicePixelRatio || 1) : 1
  canvas.width = actualSize * dpr
  canvas.height = actualSize * dpr
  canvas.style.width = `${actualSize}px`
  canvas.style.height = `${actualSize}px`

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.scale(dpr, dpr)
  ctx.fillStyle = options.colorLight || '#ffffff'
  ctx.fillRect(0, 0, actualSize, actualSize)

  ctx.fillStyle = options.colorDark || '#111827'
  for (let r = 0; r < moduleCount; r++) {
    for (let c = 0; c < moduleCount; c++) {
      if (matrix[r][c]) {
        ctx.fillRect((c + margin) * cellSize, (r + margin) * cellSize, cellSize, cellSize)
      }
    }
  }
}
