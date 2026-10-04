# 开发任务报告：T0003 - 站点设置与高级设置双入口导出按钮与文件流下载开发

> **阶段**：S1 需求分析与系统架构设计  
> **工作包**：WP-S1-01 常规研发工作包  
> **负责人**：马前端  
> **日期**：2026-10-02  
> **任务 ID**：T0003  
> **状态**：待审查 (待 周审查)

---

## 变更文件列表

| 序号 | 文件路径 | 变更类型 | 说明 |
|------|---------|----------|------|
| 1 | `frontend/composables/useAdminSettings.ts` | 新增 | 封装 `useAdminSettings` 组合式函数，管理 `exporting` 加载态、调用 `GET /admin/settings/export-md` 获取 Blob 流并自动解析 Content-Disposition 文件名触发浏览器下载与 Toast 提示 |
| 2 | `frontend/components/admin/settings/SettingsMdUploader.vue` | 修改 | 在“下载模版”按钮旁增设“导出当前配置”按钮，集成 loading 禁用态与 `handleExport` 处理函数 |
| 3 | `frontend/pages/admin/settings/blog.vue` | 修改 | 在站点信息表单底部操作条增设“导出配置”快捷入口按钮，集成 loading 禁用态与 `handleExport` 处理函数 |
| 4 | `frontend/tests/settings.spec.ts` | 修改 | 增加对站点信息页“导出配置”按钮与高级页“导出当前配置”按钮的 E2E 渲染断言 |
| 5 | `frontend/scripts/test-export-settings.mjs` | 新增 | 针对文件流下载、RFC 5987 编码文件名解析、错误捕获与 loading 态复原的自动化单元测试凭据 |

---

## 验收标准逐条核验

| 序号 | 验收标准 (Acceptance Criteria) | 映射代码与组件 | 核验结果 | 状态 |
|------|--------------------------------|----------------|----------|------|
| AC-1 | 在 SettingsMdUploader.vue 模版下载旁增设'导出当前配置'按钮 | `SettingsMdUploader.vue`（在 `<button>下载模版</button>` 旁添加 `<button :disabled="exporting" @click="handleExport">{{ exporting ? '导出中…' : '导出当前配置' }}</button>`） | 按钮已增设并对齐样式规范，经查验符合预期 | PASS |
| AC-2 | 在 pages/admin/settings/blog.vue 站点信息页增设'导出配置'快捷入口 | `pages/admin/settings/blog.vue`（在底部操作区增设 `<button :disabled="exporting" @click="handleExport">{{ exporting ? '导出中…' : '导出配置' }}</button>`） | 按钮已增设在站点设置操作栏，经查验符合预期 | PASS |
| AC-3 | 调用 GET /admin/settings/export-md 获取 Blob 并触发浏览器自动保存 site-settings-*.md | `composables/useAdminSettings.ts` 中 `exportSettingsMd`（调用 `fetch` 携带管理员凭证，读取 Blob，解析 Content-Disposition header，自动创建隐藏 `<a>` 触发下载并释放 URL） | 下载流程已全覆盖并支持 RFC 5987 与 quoted 命名 | PASS |
| AC-4 | 导出按钮具备 loading 加载态与错误 Toast 捕获机制 | `useAdminSettings.ts` 中 `exporting` 响应式状态、`try...finally` 安全重置机制，以及 `$toast.success('配置导出成功')` 与 `$toast.error(...)` 捕获拦截 | 按钮动态显示“导出中…”，异常捕获与 Toast 均具备 | PASS |

---

## 自动化测试与命令凭据

### 1. 前端构建与静态生成验证 (`npm run generate`)
- **执行命令**：`npm run generate`
- **执行目录**：`/Users/yuanyi/MyProject/vibeP/my-blog/frontend`
- **命令退出码**：`0`
- **产出凭据**：
  ```
  ✔ Client built in 3077ms
  ✔ Server built in 13ms
  ℹ Initializing prerenderer
  ✔ Generated public .output/public
  ✨ You can now deploy .output/public to any static hosting!
  ```

### 2. 导出组合式函数与下载逻辑单元测试 (`node scripts/test-export-settings.mjs`)
- **执行命令**：`node scripts/test-export-settings.mjs`
- **执行目录**：`/Users/yuanyi/MyProject/vibeP/my-blog/frontend`
- **命令退出码**：`0`
- **测试结果输出**：
  ```
  --- 测试 useAdminSettings 导出逻辑与状态流转 ---
  ✓ Test 1: 正常导出与 Blob 下载及 Content-Disposition 文件名解析通过
  ✓ Test 2: RFC 5987 UTF-8 文件名支持通过
  ✓ Test 3: 异常捕获、错误 Toast 及 loading 态复原通过

  所有 3 项自动化单测全部通过！
  ```

---

## 架构与性能自检

1. **契约边界红线**：
   - 严格恪守前端职责边界，未越界修改后端代码或外部公共配置。
2. **防重复请求与资源泄露控制**：
   - `exporting` 状态防止短时间内的重复并发点击；
   - `URL.createObjectURL(blob)` 触发点击后立即调用 `URL.revokeObjectURL(url)` 和移除隐藏 DOM 节点，避免内存泄漏。
3. **响应式与主题适配**：
   - 导出按钮统一遵循项目 CSS 规范类名 `.btn`，与深色/浅色主题及自适应排版高度兼容。
