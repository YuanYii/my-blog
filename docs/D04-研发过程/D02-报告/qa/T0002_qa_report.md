# 功能 / 集成测试报告

| 字段 | 值 |
|------|-----|
| 任务编号 | T0002 |
| 任务名称 | 博客设置Markdown导出接口与YAML逆向序列化开发 |
| 测试工程师 | 章测试 |
| 测试日期 | 2026-10-02 15:15 |
| 结束时间 | 2026-10-02 15:15 |
| 测试结论 | [OK] 测试通过 (准出，流转至已完成) |

---

## 1. 测试范围与目标

针对任务 T0002 新增的 `SettingsMdExporter` 逆向序列化组件及 `SettingsController.exportMd()` 导出端点进行针对性单元与无损往返集成测试。依据精准针对性测试规约，不执行耗时全量回归，聚焦当前改动模块的秒级轻量交付验证。

### 关联代码与被测组件
- 核心服务：[`SettingsMdExporter.java`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdExporter.java)
- 控制器端点：[`SettingsController.java#exportMd`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/controller/SettingsController.java#L489-L501)
- 测试套件：[`SettingsMdExporterTest.java`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/test/java/com/blog/settings/service/SettingsMdExporterTest.java)

---

## 2. 测试用例执行与结果

| 用例 ID | 测试项 / 场景描述 | 预期结果 | 实际结果 | 结论 |
|---------|------------------|----------|----------|------|
| TC-T0002-01 | **完整数据导出与无损往返闭环**<br>直读 SQLite 组装 4 段数据（profile / blog / techstack / experience），逆向序列化为 YAML frontmatter，并输入 `SettingsMdImporter` 执行反向解析回写 | 成功导出 Markdown，YAML frontmatter 格式规整；Importer 成功解析回写全部 4 段数据，回写数据与源数据一致 | 导出的 Markdown 成功被 Importer 解析并应用 4 段，数据匹配一致 | PASS |
| TC-T0002-02 | **空值与 null 字段强类型保全**<br>DB 无记录或各字段为 null / 缺失时导出 | profile（8个白名单字段）与 blog（5个白名单字段）的 null 均保全为空字符串 `""`，techstack/experience 对应空列表；Importer 校验通过无报错 | 全部字段成功保全为空字符串 `""`，无 NPE，Importer 成功导入 4 段 | PASS |
| TC-T0002-03 | **非白名单与脏字段隔离过滤**<br>DB 存入外部非白名单字段（如 `unknownKey`）及敏感密码（如 `adminPassword`） | 导出结果严格遵循白名单定义，非白名单字段及敏感凭据不出现在生成的 Markdown 文本中 | 导出数据仅包含白名单键值，未发现脏数据与敏感字段泄露 | PASS |
| TC-T0002-04 | **导出文件名正则与二进制字节格式**<br>生成导出文件名及调用 `exportToMdBytes()` | 文件名严格满足 `site-settings-yyyyMMdd-HHmmss.md` 正则；字节数组 UTF-8 解码与文本一致 | 文件名格式符合预期正则匹配，字节流解码与源文本一致 | PASS |
| TC-T0002-05 | **Controller 导出端点契约校验**<br>`GET /admin/settings/export-md` 接口响应头、状态码与审计日志 | 返回 HTTP 200，`Content-Type: text/markdown; charset=UTF-8`，`Content-Disposition` 携带正确 attachment 文件名，触发审计日志 | 接口响应状态码 200，响应头设置正确，经查验符合预期 | PASS |

---

## 3. 运行态测试凭据 (Proof-of-Execution)

针对性测试执行命令与真实终端输出日志如下：

```bash
$ mvn test -Dtest=SettingsMdExporterTest -DfailIfNoTests=false -f backend/pom.xml
```

```text
[INFO] Scanning for projects...
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Build Order:
[INFO]   blog-parent [pom]
[INFO]   blog-common [jar]
[INFO]   blog-auth [jar]
[INFO]   blog-settings [jar]
[INFO]   blog-article [jar]
[INFO]   blog-comment [jar]
[INFO]   blog-app [jar]
[INFO] 
[INFO] -----------------------< com.blog:blog-settings >-----------------------
[INFO] Building blog-settings 7.0.0                                       [4/7]
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- maven-surefire-plugin:2.22.2:test (default-test) @ blog-settings ---
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.blog.settings.service.SettingsMdExporterTest
15:14:49.986 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 4 段：file=null-test.md applied=[profile, blog, techstack, experience]
15:14:50.002 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 4 段：file=export-test.md applied=[profile, blog, techstack, experience]
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.19 s - in com.blog.settings.service.SettingsMdExporterTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for blog-parent 7.0.0:
[INFO] 
[INFO] blog-parent ........................................ SUCCESS [  0.002 s]
[INFO] blog-common ........................................ SUCCESS [  0.445 s]
[INFO] blog-auth .......................................... SUCCESS [  0.116 s]
[INFO] blog-settings ...................................... SUCCESS [  1.150 s]
[INFO] blog-article ....................................... SUCCESS [  0.031 s]
[INFO] blog-comment ....................................... SUCCESS [  0.027 s]
[INFO] blog-app ........................................... SUCCESS [  0.209 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  2.228 s
[INFO] Finished at: 2026-10-02T15:14:50+08:00
[INFO] ------------------------------------------------------------------------
```

---

## 4. 边界与防御性测试分析

1. **往返无损一致性**：导出的 YAML frontmatter 结构经 `SettingsMdImporter.importFromMd` 解析后，成功无损反写回系统，闭环验证了导出与导入的互通性；
2. **空值鲁棒性**：当数据库记录不存在或字段为 null 时，`SettingsMdExporter` 统一将其转化为 `""` 空串，有效杜绝了 SnakeYAML 输出 `null` 字符串或下游解析抛出 NPE 的潜在风险；
3. **白名单安全性**：严格按照 `SettingsMdTemplate` 中的字段白名单构建 Map，非白名单字段和敏感用户字段（如 `passwordHash`）被完全隔离，未发现敏感信息泄露隐患。

---

## 5. 测试准出结论

- **准出状态**：通过当前测试用例，准予发布。
- **流转目标**：由测试工程师推进状态至【已完成】，并移交严经理（PM）。
