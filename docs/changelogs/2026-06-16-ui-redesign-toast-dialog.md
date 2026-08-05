# 2026-06-16 v2.4.0 浏览器原生提示框全替换为自定义 UI

## TL;DR

系统里所有浏览器原生 `alert / confirm / prompt` (34 处) 全部替换为：

- **Toast**（vue-sonner@1.3.2）—— 替代 alert 的"成功 / 失败 / 提示"
- **GlobalDialog**（自建组件）+ **`useDialog()` composable**（Promise 包装）—— 替代 confirm 和 prompt

**技术栈选择**：vue-sonner（轻量、维护活）+ 自建 Dialog（替代 confirm/prompt，第三方无现成方案）。

**改动范围**：6 个 admin 页面 + admin layout + default layout + 1 个 plugin + 1 个 composable + 1 个组件 = 11 个文件。

---

## 系统盘点

| 类型 | 数量 | 文件 | 用途 |
|---|---|---|---|
| `alert(...)` | 23 | 6 个 admin 页面 | 错误提示 / 表单校验 |
| `confirm(...)` | 10 | 5 个 admin 页面 + admin layout | 危险操作确认 |
| `prompt(...)` | 1 | `edit.vue` | 工具栏图片 URL 输入 |
| **合计** | **34** | | |

---

## 改造前 vs 改造后

### alert → toast

```ts
// 改造前
alert('保存失败：' + e.message)

// 改造后
const $toast = useToast()
$toast.error('保存失败：' + e.message)
```

`useToast` 是 SSR-safe 的 composable，SSR 阶段返回 no-op（不写 localStorage / 不弹 toast），client 阶段返回真正的 vue-sonner 方法。

### confirm → $dialog.confirm

```ts
// 改造前
if (!confirm('确定删除？')) return

// 改造后
const $dialog = useDialog()
const { confirmed } = await $dialog.confirm({
  title: '删除评论',
  message: '确认删除...',
  confirmText: '删除',
  danger: true  // 主按钮变红
})
if (!confirmed) return
```

### prompt → $dialog.prompt

```ts
// 改造前
const url = prompt('图片 URL')
if (!url) return

// 改造后
const $dialog = useDialog()
const { confirmed, value } = await $dialog.prompt({
  title: '插入网络图片',
  label: '图片 URL',
  placeholder: 'https://...',
  confirmText: '插入'
})
if (!confirmed || !value) return
```

---

## 新增文件

### `composables/useDialog.ts`（核心 composable）
- 内部用 `useState('global-dialog', ...)` 跨页面共享 dialog state
- `confirm(opts)` / `prompt(opts)` 返回 Promise，resolve `{ confirmed, value? }`
- **SSR-safe**：SSR 阶段返回 no-op（不写 useState、不会触发 dialog 渲染）

### `composables/useToast.ts`
- 包装 vue-sonner 的 toast 方法
- SSR 阶段返回 no-op 避免 hydration mismatch

### `components/GlobalDialog.vue`
- 自建组件，confirm + prompt 共用
- 视觉规范：圆角 16px、白底卡片、遮罩 blur 4px、ESC 关闭、点遮罩关闭
- 危险操作主按钮变红（`danger` prop）
- prompt 模式额外渲染输入框（空值时确认按钮 disabled）

### `plugins/toast.client.ts`
- 注册全局 `$toast` 方法（success/error/warning/info/show/dismiss）
- `.client.ts` 后缀：仅 client 端注入

---

## Layout 集成

### `layouts/admin.vue`
- 顶层导入 `useDialog()` 拿到 state + handlers
- template 末尾挂载 `<Toaster>` + `<GlobalDialog>`（都用 ClientOnly 包）

### `layouts/default.vue`
- 同样挂载 Toast + Dialog（前台页面也能用 $dialog 调）

---

## 踩坑记录

### 1. vue-sonner@2.x 强制 nuxt@4，本项目锁 nuxt@3.13
- vue-sonner@latest（2.0.9）peerDependencies 要求 nuxt@4
- **改用 vue-sonner@1.3.2**（兼容 nuxt 3，API 一致，0 强制 peer）
- 1.x 跟 2.x 唯一区别：**没有 `vue-sonner/style.css` 导出**（2.x 才有），但 1.x 的 Toaster 组件会自动注入样式

### 2. hydration mismatch
**症状**：admin layout 套用的页面，client 报 `Hydration completed but contains mismatches`。

**根因**：
- `useState('global-dialog', ...)` 在 SSR 阶段会写一个**空 state ref**（`{ open: false }`）
- client 第一次执行时拿到**不同的 ref**（同一 key 但不同 object 实例） → mismatch

**修法**：
- `useDialog()` 在 SSR 阶段**不调 useState**，直接返回 `ref({ open: false, ... })`（纯局部 ref，不参与 hydration）
- `useToast()` 同理，SSR 阶段返回 no-op 方法
- `<Toaster>` 和 `<GlobalDialog>` 用 ClientOnly 包，确保 SSR 阶段不渲染

### 3. Playwright 登录测试踩坑
**症状**：Playwright 跑登录流程时反复被踢回 login，sessionStorage 为空。

**根因**：Playwright 每次 new context 会生成**新的 deviceId**（localStorage 隔离），新 deviceId 没在 admin_device 表登记 → 后端返回 2001 设备未授权。

**解法**：测试时手动注入已 approved 的 deviceId 到 localStorage：
```js
localStorage.setItem('blog_admin_device_id', 'b0333996-b9e1-41dc-b90c-143e878c2361')
```

---

## 验证

| 测试场景 | 结果 |
|---|---|
| 评论删除：弹 dialog → 确认 → 200 → "已删除" toast → DB 验证记录消失 | ✓ |
| 标签新建：空名提交 → 弹 toast "请填写名称" | ✓ |
| 设备管理：删除按钮 → 弹 danger 风格 dialog | ✓（前一轮已改） |
| admin layout 退出登录：弹 dialog → 确认 → 跳 login | ✓ |
| 退出弹 dialog：点遮罩/ESC = 取消（与浏览器 confirm 行为对齐） | ✓ |
| 编辑器工具栏图片 URL：弹 prompt 输入框 → 取消/确认 | ✓ |
| 整个改造后无 hydration mismatch、无 console error | ✓ |

**截图存档**：
- `docs/audits/2026-06-16-ui-redesign/dialog-comments.png`（删除评论弹窗）
- `docs/audits/2026-06-16-ui-redesign/toast-test.png`（toast 提示）
- `docs/audits/2026-06-16-ui-redesign/full-flow.png`（完整删除流程）

---

## 影响

- **UX 提升**：浏览器原生 alert/confirm 丑、阻塞、不可定制 → 现在 toast 滑入、dialog 带过渡动画
- **可访问性**：GlobalDialog 自带 `role="dialog" aria-modal="true" aria-label="..."`
- **代码一致性**：所有错误提示都走 `$toast.error`，所有危险操作确认都走 `$dialog.confirm({ danger: true })`
- **dev 体验**：toast 带颜色（success 绿 / error 红 / warning 黄），更容易扫到错误

## 部署注意

- 新增依赖 `vue-sonner@1.3.2`（已写入 package.json）
- 无 DB 变更、无 API 变更
- prod build 需要 `npm run build` 重 build
