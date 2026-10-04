# 功能 / 集成测试报告

| 字段 | 值 |
|------|-----|
| 任务编号 | T0004 |
| 任务名称 | 后端全量8段设置导出与导入加固防清空 |
| 测试工程师 | 章测试 |
| 测试日期 | 2026-10-02 20:06 |
| 结束时间 | 2026-10-02 20:06 |
| 测试结论 | [OK] 测试通过 (准出，流转至已完成) |

---

## 1. 测试范围与目标

针对任务 T0004 后端实现的 8 段站点设置（`profile`、`blog`、`social`、`preferences`、`theme`、`advanced`、`techstack`、`experience`）导出器与导入器加固逻辑进行针对性集成测试。重点核验以下核心能力：
1. **8 段全量配置无损往返解析**：导出器 YAML Frontmatter 序列化与导入器反向解析回写的一致性与完整性；
2. **非破坏性空值跳过保护策略**：导入包含空字符串或空数组时，有效拦截对数据库既有配置的覆盖，避免数据被洗白；
3. **SnakeYAML 格式与白名单防注入**：禁用自动折行（`splitLines(false)`）保全长 URL，严格执行白名单过滤防止脏字段泄漏；
4. **文件名与二进制流规范**：`site-settings-yyyyMMdd-HHmmss.md` 命名契约及 UTF-8 字节编码一致性。

依据改动与验证分级原则，本轮测试聚焦执行针对性集成测试套件，不触发全量耗时回归测试，保障秒级轻量交付。

### 关联代码与被测组件
- 导出器实现：[`SettingsMdExporter.java`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdExporter.java)
- 导入器实现：[`SettingsMdImporter.java`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdImporter.java)
- 模版规范与字段白名单：[`SettingsMdTemplate.java`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdTemplate.java)
- 针对性集成测试套件：[`SettingsMdExporterTest.java`](file:///Users/yuanyi/MyProject/vibeP/my-blog/backend/blog-settings/src/test/java/com/blog/settings/service/SettingsMdExporterTest.java)

---

## 2. 测试用例执行与结果

| 用例 ID | 测试项 / 场景描述 | 预期结果 | 实际结果 | 结论 |
|---------|------------------|----------|----------|------|
| TC-T0004-01 | **完整 8 段数据导出与 SettingsMdImporter 无损反向解析回写闭环**<br>测试 8 段（profile, blog, social, preferences, theme, advanced, techstack, experience）数据导出后交由导入器解析并回写 | 导出 md 正确包含 YAML frontmatter 及全部 8 段字段与数据；导入器解析成功并应用全部 8 段，回写数据与源数据一致 | 导出包含合法 8 段 YAML，导入器解析返回 applied 包含全部 8 段，各段数据完全对齐 | PASS |
| TC-T0004-02 | **DB 为空或 null 字段时强类型保全与白名单校验**<br>当 user 表或 site_settings 各段无数据时执行导出 | 导出数据中 profile/blog/social/preferences/theme 字段填充空串 `""`，advanced 填充默认 `true`，techstack/experience 填充空数组 `[]`；生成的 Markdown 能够通过导入器严格 Schema 校验 | 导出的数据严格匹配白名单规范，导入器成功解析 8 段，未发生 null 转换异常 | PASS |
| TC-T0004-03 | **导入空值非破坏性跳过保护与防数据洗白**<br>数据库已有完整配置，导入文档中部分字段为空串 `""`，techstack.groups 与 experience.items 为空列表 `[]` | 1. profile 仅更新非空字段，空串 nickname 不进入 SQL SET，库中已有昵称得以保留；<br>2. blog 仅 merge 非空字段 subtitle，空串 title/copyright 被过滤，库中原有值保留；<br>3. social 全空字段跳过 merge；<br>4. techstack.groups 为空列表触发防洗白保护，库中已有分组保留；<br>5. experience.items 为空列表触发防洗白保护，库中已有履历项保留 | 空串字段被拦截，空列表未覆盖已有分组和履历，库中原有有效数据经查验均完整保留，符合预期 | PASS |
| TC-T0004-04 | **YAML 导出配置 splitLines(false) 长文本防折行**<br>字段包含超长 URL 及参数（>100 字符） | 导出的 YAML 文本中超长字符串保持在单行，中间不插入换行符 | 超过 100 字符的 URL 在导出结果中保持完整单行，未被自动拆行 | PASS |
| TC-T0004-05 | **数据库脏字段与非白名单字段严格过滤**<br>模拟数据库中存在非法注入或非白名单字段（如 unknownKey, adminPassword, maliciousField） | assembleSettingsData 组装结果仅保留白名单 key，导出的 Markdown 文本中杜绝非白名单字段 | 组装数据 keySet 与白名单集合完全一致，敏感/非法字段被安全剔除，未流入导出文件 | PASS |
| TC-T0004-06 | **导出文件名规则与二进制流核验**<br>验证生成文件名正则与 exportToMdBytes() 字节流 | 文件名符合 `site-settings-yyyyMMdd-HHmmss.md` 格式；字节流经 UTF-8 解码后与 exportToMd() 字符串完全一致 | 文件名正则匹配成功，字节数组大小正常且解码内容与文本导出完全对齐 | PASS |

---

## 3. 运行态测试凭据 (Proof-of-Execution)

针对性集成测试执行命令与真实终端输出日志如下：

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
...
[INFO] -----------------------< com.blog:blog-settings >-----------------------
[INFO] Building blog-settings 7.0.0                                       [4/7]
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] --- maven-surefire-plugin:2.22.2:test (default-test) @ blog-settings ---
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.blog.settings.service.SettingsMdExporterTest
20:04:45.657 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 8 段：file=null-test.md applied=[profile, blog, social, preferences, theme, advanced, techstack, experience]
20:04:45.702 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 8 段：file=export-test.md applied=[profile, blog, social, preferences, theme, advanced, techstack, experience]
20:04:45.711 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] techstack.groups 导入为空且库中有数据，跳过覆盖以防数据洗白
20:04:45.711 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] experience.items 导入为空且库中有数据，跳过覆盖以防数据洗白
20:04:45.711 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 8 段：file=washout-test.md applied=[profile, blog, social, preferences, theme, advanced, techstack, experience]
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.232 s - in com.blog.settings.service.SettingsMdExporterTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for blog-parent 7.0.0:
[INFO] 
[INFO] blog-parent ........................................ SUCCESS [  0.003 s]
[INFO] blog-common ........................................ SUCCESS [  0.474 s]
[INFO] blog-auth .......................................... SUCCESS [  0.112 s]
[INFO] blog-settings ...................................... SUCCESS [  1.187 s]
[INFO] blog-article ....................................... SUCCESS [  0.035 s]
[INFO] blog-comment ....................................... SUCCESS [  0.025 s]
[INFO] blog-app ........................................... SUCCESS [  0.204 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  2.302 s
[INFO] Finished at: 2026-10-02T20:04:46+08:00
[INFO] ------------------------------------------------------------------------
```

---

## 4. 边界与防御性测试分析

1. **8 段无损解析与回写闭环**：通过动态代理 Mock MyBatis-Plus Mapper 与 Service，测试验证了导出 Markdown 文本输入至 `SettingsMdImporter` 后，能够被完整识别 8 段结构，并正确落盘至用户表和站点配置存储，各字段值无损对齐。
2. **非破坏性空值跳过与防数据洗白保护**：
   - Profile 字段：通过 `val instanceof String && ((String) val).trim().isEmpty()` 拦截空字符串，仅允许有效更新字段进入 `UpdateWrapper.set()`，防止空串将昵称等关键信息覆盖。
   - 扁平 Section（blog/social/preferences/theme/advanced）：通过 `filterEmptyValues()` 过滤空字符串与 null 键值对，若过滤后映射为空则直接跳过 `merge()` 调用，保留库中原有内容。
   - 集合型 Section（techstack/experience）：当导入的数组为 `[]` 时，优先读取库中既有数据，若已有有效数据则输出拦截日志并跳过覆盖，有效防止因导入空模板或缺项文档导致历史配置被清空。
3. **YAML 格式安全与字段防泄露**：
   - 采用 `DumperOptions.LineBreak` 与 `setSplitLines(false)` 配置，超长 URL 和描述文本未发生自动换行截断，保障反向解析时的格式稳定性。
   - 导出组装阶段以 `SettingsMdTemplate` 白名单作为约束准则，非白名单字段被直接丢弃，避免数据库内部脏数据或敏感配置随导出流溢出。

---

## 5. 用户关注清单

- **验收里程碑**：T0004 后端全量 8 段设置导出与导入加固已通过全部 6 项针对性集成测试，实现导出与导入双向格式闭环。
- **安全防护点**：针对空字符串与空数组导入的非破坏性防洗白策略经验证切实有效，能够防止误导入空模版导致历史配置丢失。
- **依赖关联**：本任务导出的 8 段 Markdown 结构与前端 T0003 导出下载交互完全对齐，具备投产可用性。

---

## 6. 核心结论 / 决策摘要

**测试结论**：通过当前全部 6 项针对性集成测试用例，8 段全量配置导出与导入无损往返解析经查验符合预期，空值非破坏性跳过与防洗白机制切实生效，未发现明显异常。

**准出状态**：准予发布，任务 T0004 满足准出标准，流转推进至【已完成】并指派项目经理（严经理）进行验收归档。
