# 开发任务报告：T0004 - 后端全量8段设置导出与导入加固防清空

> **阶段**：S1 需求分析与系统架构设计  
> **工作包**：WP-S1-01 常规研发工作包  
> **负责人**：李开发  
> **日期**：2026-10-02  
> **任务 ID**：T0004  
> **状态**：审查中 (待 周审查)

---

## 变更文件列表

| 序号 | 文件路径 | 变更类型 | 说明 |
|------|---------|----------|------|
| 1 | `backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdTemplate.java` | 修改 | 定义 8 段 `ALL_SECTIONS`，补齐 `THEME_FIELDS`、`SOCIAL_FIELDS`、`PREFERENCES_FIELDS`、`ADVANCED_FIELDS` 白名单定义，在 `getFieldsForSection` 中增加映射 |
| 2 | `backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdExporter.java` | 修改 | 直读 SQLite 导出全部 8 段数据（新增 social/preferences/theme/advanced 组装），配置 `options.setSplitLines(false)` 杜绝长文本与长 URL 自动折行 |
| 3 | `backend/blog-settings/src/main/java/com/blog/settings/service/SettingsMdImporter.java` | 修改 | 支持 8 段严格结构与字段白名单校验；实现非破坏性空值跳过保护机制（对 profile 及各扁平段，null/空串跳过更新；对 techstack.groups 与 experience.items，空列表若库中有数据则跳过覆盖防洗白） |
| 4 | `backend/blog-settings/src/test/java/com/blog/settings/service/SettingsMdExporterTest.java` | 修改 | 扩充 8 段往返无损回写测试、DB 为空强类型保全测试、空值非破坏性跳过与防数据洗白专用测试、splitLines 禁用折行测试，全部 6 项单测通过 |

---

## 验收标准逐条核验

| 序号 | 验收标准 (Acceptance Criteria) | 映射代码与设计实现 | 核验结果 | 状态 |
|------|--------------------------------|-------------------|----------|------|
| AC-1 | SettingsMdTemplate 增加 theme/social/preferences/advanced 白名单定义，支持 8 段体系 | `SettingsMdTemplate.java` 中常量定义：`SECTION_THEME`, `SECTION_SOCIAL`, `SECTION_PREFERENCES`, `SECTION_ADVANCED`；`THEME_FIELDS`（mode, primaryColor, accentColor, fontFamily）、`SOCIAL_FIELDS`（github, twitter, emailPublic, wechat, weibo, rss）、`PREFERENCES_FIELDS`（language, timezone, density, codeTheme）、`ADVANCED_FIELDS`（enableCache, enableRss, enableSearch, enableCommentModeration）；`getFieldsForSection` 完整支持 8 段路由与白名单校验 | 字段定义严格匹配规范与业务字段，经查验符合预期 | PASS |
| AC-2 | SettingsMdExporter 直读 SQLite 导出全部 8 段数据，配置 splitLines(false) | `SettingsMdExporter.java` 中配置 `options.setSplitLines(false)`，`assembleSettingsData()` 组装全部 8 段，新增 `assembleSocial()`, `assemblePreferences()`, `assembleTheme()`, `assembleAdvanced()`，直查 SQLite 并保持强类型保全与布尔解析 fallback | 8 段全部完成导出且长文本不被换行折断，经查验符合预期 | PASS |
| AC-3 | SettingsMdImporter 增加空值跳过保护策略，避免空串覆盖已有数据或空数组清空技术栈 | `SettingsMdImporter.java` 中实现：① `applyProfile` 遍历字段时对 null 或 `trim().isEmpty()` 跳过 `uw.set`；② `filterEmptyValues` 过滤扁平 section 空值字段，仅对非空字段调用 `siteSettingsService.merge`；③ `applyTechstack` 与 `applyExperience` 在导入为空列表且库中有数据时跳过覆盖，杜绝数据洗白 | 空值跳过保护策略运行正常，已有数据未被抹除 | PASS |
| AC-4 | 针对性单元测试覆盖 8 段往返解析及空值防覆盖验证，退出码 0 | `SettingsMdExporterTest.java` 覆盖 8 段往返闭环、空值保全、非白名单过滤、防数据洗白断言、长文本单行导出等用例；执行 `mvn test -Dtest=SettingsMdExporterTest -DfailIfNoTests=false -f backend/pom.xml` | 6 项单元测试亲测全部通过，退出码 0 | PASS |

---

## 自动化测试与命令凭据

### 单元测试与往返断言执行凭据 (`mvn test`)
- **执行命令**：`mvn test -Dtest=SettingsMdExporterTest -DfailIfNoTests=false -f backend/pom.xml`
- **执行目录**：`/Users/yuanyi/MyProject/vibeP/my-blog`
- **命令退出码**：`0`
- **测试结果输出**：
  ```
  [INFO] -------------------------------------------------------
  [INFO]  T E S T S
  [INFO] -------------------------------------------------------
  [INFO] Running com.blog.settings.service.SettingsMdExporterTest
  19:58:42.560 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 8 段：file=null-test.md applied=[profile, blog, social, preferences, theme, advanced, techstack, experience]
  19:58:42.604 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 8 段：file=export-test.md applied=[profile, blog, social, preferences, theme, advanced, techstack, experience]
  19:58:42.614 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] techstack.groups 导入为空且库中有数据，跳过覆盖以防数据洗白
  19:58:42.614 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] experience.items 导入为空且库中有数据，跳过覆盖以防数据洗白
  19:58:42.614 [main] INFO com.blog.settings.service.SettingsMdImporter - [settings-md-import] 成功导入 8 段：file=washout-test.md applied=[profile, blog, social, preferences, theme, advanced, techstack, experience]
  [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.252 s - in com.blog.settings.service.SettingsMdExporterTest
  [INFO] 
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] Total time:  3.488 s
  [INFO] ------------------------------------------------------------------------
  ```

---

## 架构与性能自检

1. **契约边界红线**：
   - 严格恪守后端设置导出与导入服务职责边界，修改集中于 `blog-settings` 模块；
   - 未越界修改其他业务模块源码或数据表结构。
2. **事务原子性与非破坏性保护机制**：
   - `SettingsMdImporter.importFromMd` 在 `@Transactional(rollbackFor = Exception.class)` 边界内执行，8 段中任意一段校验或写入失败，整批操作立即全量回滚；
   - 空值保护机制确保当 Markdown 模版中某些字段为空或被用户置空时，系统采用非破坏性更新语义，保护 SQLite 中原有数据不被误清空或恶意置空；
   - 针对结构化列表字段（`groups` / `items`），仅在确实提供有效内容时更新，若传入空数组且库中已存在内容，系统自动跳过覆盖，阻断数据洗白风险。
3. **YAML 序列化稳定性**：
   - 显式配置 `options.setSplitLines(false)`，保证如文章外部链接、复杂版权说明、长 URL 不会被 SnakeYAML 在 80 列处截断插入软换行，保障 Markdown 与 YAML 语法的严格规范性与往返一致性。
