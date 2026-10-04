# 代码审查报告

| 字段 | 值 |
|------|-----|
| 任务编号 | T0002 |
| 任务名称 | 博客设置Markdown导出接口与YAML逆向序列化开发 |
| 审查人 | 周审查 |
| 原负责人 | 李开发 |
| 审查日期 | 2026-10-02 15:15 |
| 审查结论 | [PASS] 审查通过，准入集成测试 |

---

## 1. 变更文件与范围核验

- [backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdExporter.java](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdExporter.java)
- [backend/blog-settings/src/main/java/com/blog/settings/controller/SettingsController.java](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/controller/SettingsController.java)
- [backend/blog-settings/src/test/java/com/blog/settings/service/SettingsMdExporterTest.java](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/test/java/com/blog/settings/service/SettingsMdExporterTest.java)

核验结论：修改范围严格限定于任务契约范围内，未出现任何非报备文件或跨模块越界改动。

---

## 2. 核心审查要点核查

### 2.1 代码规范、防御性编程与空值安全
- **强类型保全**：在 `SettingsMdExporter` 中，`assembleProfile`、`assembleBlog`、`assembleTechstack` 与 `assembleExperience` 针对 DB 中的 `null` 字段与缺失属性均做了兜底处理，统一格式化为空字符串 `""`，与 `SettingsMdTemplate` 的白名单定义完全一致。
- **异常容错隔离**：DB 读取与 JSON 反序列化操作均包裹在 `try-catch` 块中，在发生数据异常或查询失败时记录警告日志并降级返回空结构，避免引发端点 500 级崩溃。
- **SnakeYAML 序列化规范**：配置 `FlowStyle.BLOCK`、`PrettyFlow(true)` 与 `Indent(2)`，导出的 frontmatter 结构规整、可读性高。

### 2.2 敏感信息泄露防范
- **硬编码私钥与凭据扫描**：运行 `python3 scripts/check_secrets.py`，未检测到任何硬编码凭据与私钥，扫描判定为 PASS。
- **敏感数据物理隔离**：`User` 实体中涉及的 `passwordHash`、`role`、`status` 等敏感字段在导出逻辑中被物理隔离，未暴露到外部 Markdown 文本。
- **严格白名单过滤**：单测用例 `testStrictWhitelistFiltering` 针对注入非白名单字段（如 `adminPassword`、`unknownKey`）进行拦截断言，经查验符合预期。

### 2.3 接口鉴权与访问控制
- **路由覆盖**：端点路径为 `GET /admin/settings/export-md`，受 `AdminAuthFilter` 中的 `RouteSpec("*", "/admin/")` 保护，未携带有效管理员 Bearer Token 时将被拒绝。
- **操作审计日志**：端点调用显式触发了 `logSettingChange("export-md")`，记录了导出行为的操作人与时间。

### 2.4 逆向序列化与解析回写闭环
- **无损反向解析**：在单测中接入 `SettingsMdImporter.importFromMd` 进行闭环断言，导出的 Markdown 文件能够被 importer 成功解析并应用至 4 个核心区段（`profile`、`blog`·`techstack`、`experience`），数据保持一致。

---

## 3. 运行态测试凭据 (Proof-of-Execution)

```bash
$ mvn test -Dtest=SettingsMdExporterTest -DfailIfNoTests=false -Drevision=7.0.0
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.blog.settings.service.SettingsMdExporterTest
15:12:12.256 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 4 段：file=null-test.md applied=[profile, blog, techstack, experience]
15:12:12.271 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 4 段：file=export-test.md applied=[profile, blog, techstack, experience]
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.188 s - in com.blog.settings.service.SettingsMdExporterTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

4 项测试用例全部通过（`testExportNormalDataAndImportLosslessRoundtrip`, `testExportNullAndEmptyDataHandling`, `testStrictWhitelistFiltering`, `testFilenameAndBytes`）。

---

## 4. 审查结论与流转

- **审查结论**：[PASS] 审查通过。
- **流转动作**：执行 CLI 流转至【测试中】，移交测试工程师（章测试）进行端到端集成测试与接口契约校验。
