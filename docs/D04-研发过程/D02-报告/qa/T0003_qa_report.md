# 功能 / 集成测试报告

| 字段 | 值 |
|------|-----|
| 任务编号 | T0003 |
| 任务名称 | 站点设置与高级设置双入口导出按钮与文件流下载开发 |
| 测试工程师 | 章测试 |
| 测试日期 | 2026-10-02 15:26 |
| 结束时间 | 2026-10-02 15:26 |
| 测试结论 | [OK] 测试通过 (准出，流转至已完成) |

---

## 1. 测试范围与目标

针对任务 T0003 前端新增的 `useAdminSettings.ts` 导出逻辑、Blob 文件流下载封装、双入口组件交互（`SettingsMdUploader.vue` 与 `pages/admin/settings/blog.vue`）以及与后端 T0002 `GET /admin/settings/export-md` 接口的联调契约进行精准针对性验证与准出。根据精准针对性测试规约，不执行全量耗时回归测试，保持秒级轻量交付。

### 关联代码与被测组件
- 组合式函数：[`frontend/composables/useAdminSettings.ts`](file:///Users/yuanyi/MyProject/vibeP/my-blog/frontend/composables/useAdminSettings.ts)
- 高级设置导出入口：[`frontend/components/admin/settings/SettingsMdUploader.vue`](file:///Users/yuanyi/MyProject/vibeP/my-blog/frontend/components/admin/settings/SettingsMdUploader.vue)
- 站点设置导出入口：[`frontend/pages/admin/settings/blog.vue`](file:///Users/yuanyi/MyProject/vibeP/my-blog/frontend/pages/admin/settings/blog.vue)
- 后端联调契约端点：[`SettingsController.java#exportMd`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/controller/SettingsController.java#L489-L501)
- 针对性测试套件：[`scripts/test-export-settings.mjs`](file:///Users/yuanyi/MyProject/vibeP/my-blog/scripts/test-export-settings.mjs)

---

## 2. 测试用例执行与结果

| 用例 ID | 测试项 / 场景描述 | 预期结果 | 实际结果 | 结论 |
|---------|------------------|----------|----------|------|
| TC-T0003-01 | **标准导出与 Blob 文件流下载**<br>后端返回 HTTP 200，`Content-Disposition: attachment; filename="site-settings-20261002-152000.md"`，触发下载流程 | 成功创建 Blob 对象 URL 并挂载隐藏 `<a>` 标签触发下载；下载文件名正确解析为预期文件名；URL 及时释放；Toast 提示成功；`exporting` 正确复位 | 成功解析 Content-Disposition 文件名，触发 Blob 下载，状态与提示符合预期 | PASS |
| TC-T0003-02 | **RFC 5987 UTF-8 文件名解析**<br>后端返回含中文编码的 `filename*=UTF-8''...` 头部 | 正确使用 `decodeURIComponent` 解码并还原中文文件名，未发生乱码或回退异常 | 正确解析为中文文件名，与源编码字符串一致 | PASS |
| TC-T0003-03 | **服务端异常与 loading 状态机防御**<br>后端接口返回 HTTP 500 或网络异常 | 捕获异常并弹出错误 Toast 提示；`exporting` 在 `finally` 块中重置为 `false`，按钮解除禁用态，杜绝永久置灰 | 成功捕获异常并弹出 Toast，loading 状态复原，经查验符合预期 | PASS |
| TC-T0003-04 | **前后端联调契约校验 (T0002 <-> T0003)**<br>核验前端 `fetch` 路径、鉴权头部与后端 `SettingsController.exportMd()` 响应结构 | 接口路径 `${base}/admin/settings/export-md`，请求携带 `Authorization` Bearer 令牌及 `X-Device-Id`；后端返回 `text/markdown; charset=UTF-8` 及 attachment 响应头，契约完全对齐 | 前后端契约字段、方法与状态码一致，未发现明显契约冲突 | PASS |
| TC-T0003-05 | **双入口 UI 渲染与防抖配置**<br>核验站点信息页与高级设置模版上传区双按钮状态绑定 | 两处按钮均绑定 `:disabled="exporting"`，点击期间文字显示为“导出中…”，阻止重复并发点击 | 按钮均已正确绑定 loading 态与禁用防抖属性，经查验符合预期 | PASS |

---

## 3. 运行态测试凭据 (Proof-of-Execution)

针对性测试执行命令与真实终端输出日志如下：

```bash
$ node scripts/test-export-settings.mjs
```

```text
--- 测试 useAdminSettings 导出逻辑与状态流转 ---
✓ Test 1: 正常导出与 Blob 下载及 Content-Disposition 文件名解析通过
✓ Test 2: RFC 5987 UTF-8 文件名支持通过
✓ Test 3: 异常捕获、错误 Toast 及 loading 态复原通过

所有 3 项自动化单测全部通过！
```

---

## 4. 边界与防御性测试分析

1. **防抖与并发控制**：在 `useAdminSettings` 内层设置 `if (exporting.value) return` 防护，外层 UI 按钮绑定 `:disabled="exporting"`，在请求飞行期间提供双层防重点击阻断。
2. **内存与 DOM 资源回收**：临时创建的 `<a>` 标签在触发 `click()` 后被立即从 `document.body` 中移除，并随即调用 `URL.revokeObjectURL(url)` 释放 Blob 对象 URL，未产生多余 DOM 残留或浏览器对象内存驻留。
3. **状态机安全兜底**：使用 `try ... finally` 确保无论成功、网络中断或服务端 500 错误，`exporting` 状态必定复位为 `false`，保障用户可重试。
4. **前后端接口对齐**：验证了 T0002 后端 `SettingsController.java` 中的 `@GetMapping("/export-md")` 与 T0003 前端组合式函数的调用协议、鉴权凭据与流响应解析逻辑，联调契约闭环。

---

## 5. 准出结论

- **测试定性**：[OK] 测试通过。
- **流转结论**：满足所有验收标准与防错门控，准出并流转至【已完成】，指派给项目经理（严经理）进行最终验收。
