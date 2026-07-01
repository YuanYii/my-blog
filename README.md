# 个人博客系统

> **v5.0.0** — Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离的个人博客 MVP。
> 当前状态：v2.6.0 dev/prod 默认 SQLite（一文件 0 内存占用，MySQL 降级为可选 profile） + v2.7.0 前端全静态化（`nuxt generate` + nginx serve，省 150-250MB 内存）+ 公开页 SEO 预渲染 + **v4.0.0 日志体系（SLF4J/Logback + traceId + 文件滚动 30 天）+ IP 限流封禁（Redis + DB 持久化）+ 动态 favicon** + **v4.2.0 数据备份（加密上传 GitHub Release）+ v4.2.1 备份 polish（删除记录 / traceId 全链路 / UI 对齐）**。

---

## 一、项目介绍

### 1.1 是什么

一个前后端分离的个人博客系统，包含**公开前台**（文章浏览/归档/标签/关于）和**管理后台**（仪表盘/文章管理/评论审核/分类标签/个人设置/设备管理/IP 封禁）。**面向单机 1C2G 低配服务器优化**——SQLite 替代 MySQL、前端静态化替代 SSR Node，生产部署无 docker。

### 1.2 核心功能

| 模块 | 功能 |
|------|------|
| **前台公开** | 首页（Hero 个人介绍 + 文章列表）/ 文章详情 / 归档 / 标签云 / 关于页（**SEO 预渲染**） |
| **管理后台** | 仪表盘（KPI 聚合 + 30 天趋势）/ 文章增删改 / 评论审核 / 分类&标签管理 / **9 子路由站点设置（profile/password/blog/techstack/experience/theme/social/preferences/advanced，侧栏二级菜单 + 按需加载）** / 设备白名单管理 / **数据备份（加密上传 GitHub Release）+ 数据恢复** |
| **系统** | JWT 鉴权 / 设备白名单（X-Device-Id 绑定 token）/ API 路由白名单（DB 驱动，最长前缀匹配）/ 文件上传（本地存储 + 扩展名 + magic bytes 双重校验）/ 站点设置 8 section（blog/social/preferences/theme/advanced/techstack/experience + admin profile）/ Swagger API 文档 / 全静态前端 / **SLF4J+Logback 日志体系（traceId 串联全链路，文件滚动 30 天，3GB 容量上限）** / **IP 限流（10 次/秒 + 30 分钟封禁，Redis 热路径 + DB 持久化 + admin 手动解封）** |

### 1.3 版本记录

- **v5.0.0 增量**（2026-06-30）— **主题外观配色方案推荐组合**（20260630-OPT-001）：(1) `frontend/components/admin/settings/ThemeForm.vue` 删除 `modeOptions` 数组 + "主题模式" form-group（原 line 5-9 + 18-21），mode 字段的写入路径（NavBar toggleTheme 切深浅）全部保留；新增 `COLOR_SCHEMES` 数组（10 个推荐组合：森林晨光 / 深海落日 / 薰衣草田 / 墨竹琥珀 / 奶油森林 / 赤陶米色 / 暮霭蓝灰 / 樱粉青灰 / 抹茶栗色 / 薄雾白）+ `schemeOptions`（10 preset + 1 `custom`）+ 新增 form-group "配色方案" + `UiDropdownSelector`；(2) 双向同步 watch：scheme 变（且非 `custom`）→ 查 preset map 写 primary/accent；primary/accent 变（且不匹配当前 preset）→ scheme 跳 `'custom'`；**手动优先语义**：用户同时调主色+强调色恰好等于某 preset 仍显示"自定义"，不自动回跳；color picker **永久显示**作为"自定义/微调"入口；(3) `frontend/pages/admin/settings/theme.vue` `state` 增 `scheme: string` 字段（默认 `'forest-morning'` 命中 DB 默认值 `#2f6f5e + #c97b3f`，加载时按 `primaryColor/accentColor` 反查匹配 preset，匹配失败填 `'custom'`），自实现 `load/save`（不依赖 `useAdminSettingsTab` 因其 save 写死 `put(opts.endpoint, opts.state)` 无法剥离字段）；save 时显式 `const { scheme, ...body } = state` 剥离 scheme 字段（**前端剥离路径**：因 `SiteSettingsService.merge()` 第 195 行 `current.putAll(partial)` 会静默入库未知字段，前端剥离保证 PUT body 永远不含 scheme，DB theme section 4 字段干净）；(4) **未动**：`backend/blog-settings/.../SettingsController.java`（mode/颜色/字体白名单原样）、`SiteSettingsService.java`（`merge` 累加行为不修）、`composables/useSiteTheme.ts`、`components/NavBar.vue`、`layouts/admin.vue` 的 `toggleTheme`、`state.mode` 字段（保留给 NavBar 切换语义用）、字体下拉框、DB schema、`SiteSettingsService.defaultTheme()`（`#2f6f5e + #c97b3f` 默认仍命中"森林晨光"）；(5) stage 3 代码质量审查 0 严重/1 警告（COLOR_SCHEMES 重复定义，不阻塞）/ 3 建议（watch 同步缺单测 / setTimeout 未清理 / 手动优先可加注释）；stage 4 静态审计 22 项验证清单 8 通过 + 2 事实修正标注（NavBar 不调 PUT theme 是 pre-existing 行为 + 后端 merge 入库未知字段已通过前端剥离规避）+ 12 项 🔍 待 dev 环境 UI 确认；stage 5 集成测试 3 项 API 通过（GET theme / PUT 不含 scheme → DB 不含 scheme / 反查匹配森林晨光）+ 12 项 UI 交互 ⏭ 跳过（myblog-sim 容器为 nginx:alpine 静态站，无 dev runtime）+ DB 测试副作用已清理
- **v5.0.0 增量**（2026-06-30）— **博客首页搜索按钮无法使用修复**（20260630-BUG-001）：(1) **`backend/blog-article/.../ArticleService.java`** 第 65-67 行加 keyword 长度下限校验 `keyword.trim().length() < 2` 抛 BusinessException(400, "关键词至少 2 个字符")；第 77-80 行**搜索范围限定 title + summary**（**关键决策**：不搜 content_md，因为通用词如 "AI"/"Redis" 在所有文章正文里都出现，content_md 全表 LIKE 命中率 100%，反让用户感知"搜索 = 全列表"——这正是 BUG 原始症状的反面），MyBatis-Plus `and(orLambda)` 模式等价 SQL：`AND (title LIKE ? OR summary LIKE ?)`；(2) **`frontend/pages/search.vue`** 第 14-18 行新增 `isResultTooMany = computed(() => total.value > size * 5)`（threshold 100 条），第 56-58 行 header 段加"建议缩小关键词范围"提示（v-if="isResultTooMany"，CSS 用 `var(--color-warning, #d97706)` 兜底色）；(3) **`scripts/deploy-server.sh`** 第 889 行 nginx `try_files` 顺序从 `$uri $uri/ /200.html` 改为 `$uri $uri/index.html /200.html`——修复后 nginx 先试 `/search/index.html`（直接命中），跳过 `$uri/` 触发的 301 redirect（之前 `/search` 直接访问 → 301 → `http://localhost/search/` → 端口丢失 → 连接失败），同时修复 `/about /archives /tags` 所有"目录形"路由；(4) **`frontend/scripts/fetch-routes.js`** 第 30 行 `STATIC_ROUTES` 数组补 `/search`（与 `docs/design/博客系统设计方案.md` §5.9/§11 一致性，Nuxt prerender 默认兜底仍会生成静态 HTML，但与文档化口径对齐）；(5) **未动**：后端 `ArticleController.java`（按任务卡"不动清单"，enableSearch=false 403 校验原样保留在 ArticleController 第 47-50 行）、`NavBar.vue` / `useSiteFlags.ts` / `usePublicApi.ts`（代码逻辑已验证正确）、`useSiteTheme.ts` / DB schema / 已发表文章数据；(6) **实测验证**（myblog-sim 容器，新 jar 已部署 + nginx 已 reload）：`?keyword=S` → 400、`?keyword=Spring` → 3 条（Spring Boot 系列）、`?keyword=AI` → 3 条（AI 系列，**关键正面验证：修复前若搜 content_md 会命中全部 6 篇**）、`?keyword=Redis` → 1 条、`?keyword=Zip Slip` → 1 条、无 keyword → 6 条全部（向后兼容）、`/search?q=xxx` → 200 OK（不再是 301）、`/about /archives /tags /post/<slug> /200.html` → 全部 200 OK；(7) stage 3 代码质量审查 0 严重/1 警告（fetch-routes.js runbook 注释）/4 建议（OPT-001 3 建议保留 + 新增 SUG-004 service 层校验下沉）；stage 4 静态审计 30 项验证清单（OPT-001 22 + BUG-001 30 减去重复） 30 项 ✅ 通过 + **0 事实修正**（修复方案与任务描述 100% 一致）+ 8 项 🔍 UI 需 dev 环境；stage 5 集成测试 13 项 ✅ 通过（OPT-001 3 + BUG-001 10：API 5 + nginx 5）+ 12 项 UI ⏭ 跳过（myblog-sim 容器为 nginx:alpine 静态站无 dev runtime）；stage 6 0 卡片合并（异常项均为 OPT-001 的 2 处事实修正非代码缺陷，不属本轮 BUG-001 引入）
- **v5.0.0 增量**（2026-06-28）— **全站确认/取消按钮键盘快捷键支持**（20260628-DEV-001）：(1) `frontend/components/GlobalDialog.vue` 在 `onKeydown` 监听里增加 Enter 分支（守卫：`!e.isComposing && !e.shiftKey` + 排除 TEXTAREA/contenteditable），按 Enter 触发 `handleConfirm()`；Esc 已有逻辑加 `e.preventDefault()` 避免被其他监听器吞；watch + nextTick + ref 在 dialog 打开时自动聚焦「确定」按钮（confirm 模式），prompt 模式焦点在 input（已有 autofocus）；scoped 补 `.btn:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }` + `.btn-danger:focus-visible { outline-color: var(--danger); }` 焦点环可见；(2) 新增 `frontend/composables/useModalKeyboard.ts` 统一封装内联 modal 键盘逻辑（window 级 keydown 监听，**仅在 `open.value === true` 时挂载**，关闭立即解绑；onCancel 必传、onConfirm 可选；confirmButtonRef 自动聚焦），适用场景：admin 各页 `<div v-if="showModal" class="modal-backdrop">` 内联模态框；(3) 4 个 admin 页面接入 composable：`pages/admin/categories.vue`（showModal 新建/编辑）、`pages/admin/tags.vue`（showModal 新建标签）、`pages/admin/restore.vue`（restoreDialog 确认恢复 + restoreErrorDialog 只挂 Esc）、`pages/admin/backup.vue`（errorDialog 备份失败详情只挂 Esc）；(4) 新增 `frontend/tests/dialog-keyboard.spec.ts` 10 个 e2e case（Playwright + Chromium 149）：GlobalDialog confirm/prompt + 4 个内联 modal 的 Esc/Enter + IME composing 排除 + 连续 Enter 防重 + 焦点环，全部通过；为支持 e2e 临时新增 `frontend/_e2e-server.mjs`（static + /api proxy）替代 nuxt dev 跑测试（避免 HMR + 1.3GB 内存压垮） + pre-approve `e2e-keyboard-test-device` 设备（admin_device 表 status=approved）解决 login 2001；(5) **未动**：`pages/admin/devices.vue / comments.vue / posts.vue / edit.vue / settings/*.vue` 的删除/退出均走 `$dialog.confirm` → 通过 GlobalDialog 改动已自动覆盖；`PasswordForm.vue` `tags.vue` 快速添加 `posts.vue` 搜索框已有 `@keyup.enter`，无需重复处理；**未影响** 纯表单提交场景（`<form @submit>` 内回车仍走原生行为，符合需求 4.5「不影响表单内回车」）
- **v4.3.0+ 增量**（2026-06-28）— **前台 RSS / 搜索入口显隐**（autopush-OPT-001/002）：(1) **20260627-OPT-001 RSS 链接显隐**：新增 `GET /api/v1/public/site-flags` 端点（`PublicSettingsController` 拆 `@RequestMapping("/public/settings")` → `/public` + 方法路径补回，新增 `siteFlags()` 方法返 `enableRss/enableSearch` 白名单子集；不暴露 `enableCache/enableCommentModeration` 敏感开关），新增 `frontend/composables/useSiteFlags.ts` composable（`useAsyncData('site-flags')` + `useState` 共享 + 默认 true 兜底）；`frontend/app.vue` 顶层调用 `useSiteFlags()`；`frontend/pages/about.vue` 两处 RSS 链接（社交图标 + contact-card）加 `v-if="siteFlags.enableRss"`；`frontend/components/SiteFooter.vue` 引入 `showRss = enableRss && social.rss` 双控 computed，RSS 链接 + `·` 分隔符同步隐藏；(2) **20260627-OPT-002 NavBar 搜索入口**：在 `frontend/components/NavBar.vue` 主题切换按钮旁加搜索 icon + 简易弹层（input + 搜索按钮），用 `<form @submit.prevent="onSearchSubmit">` 实现回车提交 → `router.push({ path: '/search', query: { q } })`；弹层支持 click outside 关闭 + ESC 关闭（keydown 监听）；`v-if="siteFlags.enableSearch"` 包裹整个 icon 入口（关闭时不渲染）；手机端下拉菜单里也加一个直跳 `/search` 的入口；新增 `frontend/pages/search.vue` 搜索结果页（走 `/api/v1/public/articles?keyword=&page=&size=`，articles 列表 + 摘要 + 分类 + 浏览数 + 分页；watch route.query 变化自动重拉；SEO title 跟 keyword 联动）；前端 prerender 路由从 7 → 8（含新增 `/search`）
- **v4.3.0+ 增量**（2026-06-27）— **站点设置三大菜单功能落地**（DEV-001/002/003）：(1) **20260627-DEV-001 主题外观**：新增 `frontend/composables/useSiteTheme.ts`，从 `/api/v1/public/settings/theme` 拉取 mode/primaryColor/accentColor/fontFamily，applyTheme 把 mode 映射为 `dark` class（兼容 localStorage 本机覆盖与 auto 模式 prefers-color-scheme 监听）、把 primaryColor/accentColor 注入 `--primary / --color-primary / --primary-soft / --accent / --color-accent` CSS 变量、把 fontFamily 设为 `body.font-{serif|sans|mono}` class；`frontend/assets/css/main.css` 补齐 `--color-bg / --color-text / --color-text-2 / --color-line / --color-primary / --color-primary-soft / --color-accent` 七个别名（NavBar.vue 一直在用但此前未定义）+ 新增 `body.font-sans/mono/serif` 字体类；`backend/blog-settings/src/main/java/com/blog/settings/service/SiteSettingsService.java` 的 `PUBLIC_SECTIONS` 追加 `SECTION_THEME, SECTION_PREFERENCES`；`frontend/app.vue` 顶层调用 `useSiteTheme()`；(2) **20260627-DEV-002 偏好设置**：新增 `frontend/composables/useSitePreferences.ts`（拉 `/public/settings/preferences`，把 density/codeTheme 应用为 `body.density-{comfortable|compact}` + `body.code-theme-{github|monokai|nord}` class，language 写入 `<html lang>`）+ `useI18n.ts`（基于 `useState('site-preferences')` 切换字典，未命中回退 zh-CN→key）+ `useFormatTime.ts`（用 `Intl.DateTimeFormat(locale, { timeZone })`，不依赖 dayjs）+ `frontend/i18n/zh-CN.json` / `en.json`（12 个高频 key：nav.* / common.* / footer.*）；`frontend/components/NavBar.vue` 导航文案改用 `t('nav.home|archives|tags|about|admin')`；main.css 新增 `body.density-comfortable | .density-compact`（收紧 nav/td/card padding）+ `body.code-theme-{github|monokai|nord}`（覆盖 `--code-bg / --code-text`）；`backend/blog-settings/src/main/java/com/blog/settings/controller/SettingsController.java` 的 `updatePreferences` 补 density / codeTheme 白名单校验 + timezone `ZoneId.of()` 校验；(3) **20260627-DEV-003 高级开关**：新增 `backend/blog-settings/src/main/java/com/blog/settings/service/AdvancedSettingsAccessor.java`（30s 内存缓存 + 优先级语义读取 `enableCache/Rss/Search/CommentModeration`，过期 lazy refresh，回推 `siteSettingsService.setCacheEnabled(bool)`）+ `RssController.java`（新增 `/api/v1/rss` 与 `/api/v1/rss.xml` 双路由，开关关闭返 404，开启时拉首页前 20 篇渲染 RSS 2.0 XML，pubDate RFC 822 + Asia/Shanghai）；`SiteSettingsService` 新增 `setCacheEnabled(boolean)` + `get()` cacheEnabled=false 时所有 section 穿透 DB（advanced 段例外，防自环），`defaultAdvanced` 字段名统一改 `enableCommentModeration` 与前端 AdvancedForm 对齐；`ArticleController.list` 注入 AdvancedSettingsAccessor，对带 keyword 的请求按 searchEnabled 拒绝（业务码 403 "站点搜索已禁用"），不带 keyword 走原路径；`CommentService.create` 在 INSERT 前读 `commentModerationEnabled()`，决定 status=0/1，返回消息随之 "评论已发布" / "评论已提交，待审核"；`backend/blog-comment/pom.xml` + `backend/blog-article/pom.xml` 新增 `blog-settings` 依赖（运行时注入）；(4) **stage5 集成测试期发现并修复 1 处 bug**：`AdvancedSettingsAccessor.commentModerationEnabled()` 早期 OR 合并 `enableCommentModeration` 与 legacy `commentModeration` 时，会让 user 显式关闭新 key 被 legacy=true 抵消；改为「新 key 显式存在则取新 key，否则回退 legacy，再缺则默认 true」优先级语义；INT-011 验证修复后 status=1 + "评论已发布" 通过；(5) **流水线集成测试结果**：14 项验证 → 11 ✅ 通过（含 RSS 双路由 / enableSearch 403 / 评论审核双向切换 / preferences 三字段校验）+ 3 ⏭ 跳过（FOUC 视觉、body class 视觉、cache DB 穿透 SQL 计数——均因前端 dev 未启动或需 DEBUG 日志），0 ❌；(6) **autopush 范围外项 → 2 个 OPT 卡片**：`autopush-20260627-OPT-001` 前台 about/footer RSS 链接显隐（待建 `/public/site-flags` + `useSiteFlags` composable）+ `autopush-20260627-OPT-002` 前台 NavBar 搜索入口显隐（待先补搜索 UI 入口）
- **v4.3.0+ 增量**（2026-06-24）— **数据恢复性能优化 + 恢复历史孤儿重建 + 仪表盘流量来源真实统计 + 文章 ZIP 导入 + Docker 恢复链路 + 侧栏数量自刷新 + 备份/恢复状态可见性**：(1) PBKDF2 迭代次数从 100k 降至 10（用户使用强密码，安全性可接受，解密速度提升约 10,000 倍）+ sqlite-import.sh 导入前注入 `PRAGMA synchronous=OFF` + `PRAGMA journal_mode=OFF` 加速 SQL 写入（导入后恢复 WAL + NORMAL），涉及文件：`scripts/sqlite-export.sh` / `scripts/sqlite-import.sh` / `scripts/blog-backup.sh`；(2) **20260624-BUG-001 恢复后历史记录孤儿重建**：`RestoreService.triggerRestore` 在创建 PENDING record 后写 `.restore-meta.{id}.json` 到 `RESTORE_RESULT_DIR`（result_dir 不被 trap 清理），`RestoreStartupReconciler.reconcileOrphanResults` 扫描 result_dir 下孤儿 `.result.json`（DB 中不存在的 ID）→ 合并 meta + result 反向 INSERT 重建 restore_record，并按 source tag 把仍 RUNNING 的 backup_record 修正为 SUCCESS（新增 `BackupRecordMapper.fixRunningToSuccessByTag` / `selectByTag` + `RestoreRecordMapper.existsById`），`@Scheduled` initialDelay 由 5min 调至 60s 加快恢复后用户可见性；(3) **20260624-DEV-001 仪表盘流量来源真实统计**：新增 `PageViewService.topReferrers(days, limit)` 按 `page_view.referer` 域名分组 + 友好名映射（Google/百度/Bing/Twitter/GitHub 等 13 类）+ percentage 浮点精度 2 位，`DashboardController.dashboard` 新增 `trafficSources` 字段（30 天 Top 5），`frontend/pages/admin/dashboard.vue` 删除硬编码 trafficSources 数组接入 API 数据 + 空状态"暂无访问数据"；(4) **20260624-DEV-002 文章 ZIP 批量导入**：新增 `/articles/admin/import`（POST 提交） / `GET /articles/admin/import/{id}`（轮询）/ `GET /articles/admin/import`（历史，最多 50 条）/ `GET /articles/admin/import/template`（模板下载）4 个 admin 接口；新建 `ArticleImportService`（单线程池 articleImportExecutor 串行执行 + 5MB ZIP 上限 + 5MB 解压总大小防压缩炸弹 + Zip Slip 防护 + 仅 .md/.png 白名单 + 单篇导入 try/catch 累积错误） + `MarkdownImporter`（自实现轻量 YAML front matter 解析器 + 图片相对路径提取/替换 + 文件名→title/slug 转换 + YYYY-MM-DD- 日期前缀剥离）+ `ImportRecord`/`ImportRecordMapper` + `import_record` 表（双 schema 同步）+ `import-template.zip` 模板资源；前端 `pages/admin/posts.vue` 新增「导入」按钮 + `components/admin/ArticleImportDialog.vue`（文件选择/校验/上传/1.5s 轮询/历史/模板下载，深色模式 + 移动端适配）；(5) **20260624-BUG-002 Docker 模式恢复链路**：数据恢复机制已修改，原恢复脚本步骤 4 + 11 Docker 分支已废弃；(6) **20260624-BUG-003 侧栏数量自刷新**：`posts.vue` (handleDelete/handleBulkDelete/handleBulkPublish) + `comments.vue` (handleAction/handleDelete) 五处成功回调追加 `useAdminMeta().refresh()` 调用,**不改 `useAdminMeta.ts`**(refresh 早已存在,仅业务页漏调);(7) **20260624-BUG-004 恢复失败可见性优化**：`RestoreStartupReconciler.@Scheduled` fixedDelay 从 5min 降至 60s + reconcileOnStartup 追加 `Thread.sleep(5_000) + reconcile()` 二次触发,覆盖"恢复脚本写 result.json 在 systemctl/supervisorctl start myblog 之后"的时序窗口,失败可见性从 ~6min 缩到 ~5s;(8) **20260624-BUG-005 BackupStartupReconciler**：新建 `BackupStartupReconciler` 镜像 RestoreStartupReconciler 模式 + 新增 `BackupRecordMapper.findAllRunning`,JVM 启动 + 5s 二次 + 60s scheduled 扫 RUNNING 备份记录,读 `BACKUP_STAGE_DIR 父目录/.blog-backup-result.{id}.json` 反序列化 `BackupService.ResultJson` 复用 SUCCESS 路径字段映射(tag/db_size/uploads_size/asset_count/asset_urls/manifest_json),超阈值无 result.json → FAILED/ORPHAN,解析异常 → FAILED/RESULT_PARSE_FAILED 兜底,覆盖"JVM 在 waitFor 之后/updateById 之前被强杀"的极窄窗口卡 RUNNING;(9) **20260624-OPT-002 restore.vue 五态 badge 色值**：`restoreStatusType` 显式补 PENDING → muted + `<style scoped>` 追加 `.status-badge` + `.status-success/danger/accent/muted` 五态色值(与 backup.vue 一致,backup.vue 的 scoped 不再"穿透"到 restore.vue),`.spin-dot` 背景由 `var(--accent)` 改为 `currentColor` 跟随文字色；(10) **20260624-OPT-003 导入对话框深色主题修复**：根因为 `ArticleImportDialog.vue` 引用了未在全局 CSS 变量系统中定义的 `--bg-2` / `--border` / `--danger-soft` 三个变量，浅色和深色模式都走 fallback 到浅色硬编码值，导致深色下历史行/进度区/错误框/虚线边框/代码标签全是浅色刺眼。`frontend/assets/css/main.css` 的 `:root` 补 `--bg-2: #f3f4f6; --border: #e8e4d8; --danger-soft: #fef2f2;`，`.dark` 补 `--bg-2: #1c2129; --border: #2a3038; --danger-soft: rgba(248, 113, 113, 0.1);` + `.import-mask` 遮罩透明度 0.4→0.6 提升深色下对比度；(11) **20260624-OPT-004 文章管理标题点击 + 筛选器布局**：`pages/admin/posts.vue` 给 `.post-cell-info` 加 `@click="handleEdit(a)"` + `.post-cell-clickable` 类，点击标题/slug 区域跳编辑（复用既有 handleEdit 方法）；两个 `<UiDropdownSelector>` 各包一层 `<div class="toolbar-dropdown">` 容器（方案 A，局部修复不影响其他页面）；`main.css` 新增 `.post-cell-clickable { cursor: pointer }` + hover 标题变 `--primary` 色 + `.post-cell-title` 加 `transition: color 0.15s` + `.toolbar-dropdown { width: 160px; flex-shrink: 0 }` + `:deep(.ds-wrap) { width: 100% }`，移动端媒体查询追加 `.toolbar-dropdown { width: 100% }`；(12) **20260624-DEV-003 备份同步按钮**：解决项目初始化场景下 `backup_record` 表为空、无法选择历史备份恢复的问题。`BackupService` 新增 `syncFromGithub(request)` 方法（调 `gh release list --repo ... -L 3 --json tagName,publishedAt,assets` 5s 超时拉取最近 3 条 release → 逐条 `selectByTag` 不存在才 INSERT 天然幂等 → 字段映射 status=SUCCESS / startedAt=finishedAt=`parseGithubTimestamp(publishedAt)` / assetCount / assetUrls JSON / operatorId/Name / traceId → manifest.json 走 curl + Authorization header process substitution 5s 超时拉取，解析 `db.size_bytes` / `uploads.size_bytes` 填 dbSize/uploadsSize，失败兜底 0 + WARN 含 traceId）+ `isShellSafe(tagName)` 防御性校验（与 deleteGitHubRelease 安全策略对齐）；`BackupController` 新增 `POST /admin/backup/sync` 端点；前端 `pages/admin/backup.vue` 顶部 page-head 在「立即备份」前并列新增「备份同步」ghost 按钮（circular refresh icon + spin loading）+ `handleSync` 函数（toast「已同步 N 条备份」+ 立即 fetchList 刷新列表）
- **v4.3.0+ 增量**（2026-06-23）— **管理后台拆分与移动端 polish**：(1) 站点设置从单页 8 tab 拆为 **9 个独立子路由** + admin 侧栏二级菜单（`/admin/settings/{profile,password,blog,techstack,experience,theme,social,preferences,advanced}`，路径命中自动展开 + `localStorage('admin.settings.expanded')` 持久化，按需 GET 替代原 8 并发 `loadAll`）；(2) **dashboard 移动端 polish**（welcome-bar ≤768px 上下堆叠 + 按钮居中、`.dashboard-grid > * { min-width: 0 }` 解决 KPI/图表/待办/站点状态/流量来源溢出、最近文章表格 col class 列宽优化 + 浏览量挪到 slug 副标题、顶栏退出按钮统一为 SVG + `.icon-btn`）；(3) 全站下拉框统一为 `DropdownSelector` 组件（11 处 `<select>` 替换 + `MultiTagSelect` chip 多选 + Teleport body 定位 + 键盘导航）；(4) 5 个 admin 表格加 `.table-wrap { overflow-x: auto }` 横向滚动；(5) 日志归档按月份子目录（`logback-spring.xml` 改 `${LOG_DIR}/archive/%d{yyyy-MM}/blog-...log` + `deploy-server.sh` 同步 logrotate glob）；(6) **admin 模块重构（OPT-005）**：抽 `composables/useAdminSettingsTab.ts`（封装 admin/settings 子页通用 load/save/saving/message 模板）+ `<AdminSettingsSaveBar>`（保存按钮 + message 行），7 个非 password settings 子页 13 行整页（-72%）；抽 `<AdminNavIcon>`（15 种 SVG icon 映射）+ `<AdminSidebarNav>`（一/二级菜单整体）+ `<AdminUserChip>`（顶栏胶囊）三组件，`layouts/admin.vue` 365 → 245 行（< 300 阈值），9 个 Form 组件 props/emits/data 零改动；(7) **移动端优化**（OPT-006~010，2026-06-24 重做版）：文章编辑页 topbar 左侧抽 `.editor-topbar-left` + `.editor-back-btn` 类（返回按钮路由 `/admin/posts`）；仪表盘"分类分布 + 流量来源"内联 grid 抽为 `.dashboard-split-panel`（桌面 + 移动端等效 50/50 并排），删除旧 `dashboard-grid .panel canvas { max-width: 120px }` 错误规则（曾误压访问趋势折线图），`.chart-area canvas { width: 100% !important }` 强制铺满；最近文章移动端表格列宽严格 `40% / 15% / 30% / 15%`（取代旧像素值），浏览数挪到 slug 副标题区；`layouts/admin.vue` 的 `pageTitle` map 把 `/admin/settings` 与 `/admin/settings/` 默认映射到"个人资料"，9 个子路径仅显示子菜单名（"站点设置"四字不再出现）+ `pages/admin/settings.vue` 同步 `pageHead` 计算属性动态化；首页禁止横向滚动根因修复为 `.hero { overflow: hidden }`（裁掉 `.hero::before` 装饰球的 100px 右溢出），并把 `html, body { overflow-x: hidden; max-width: 100%; }` 作双兜底
- **v4.2.1**（2026-06-21）— **备份 polish**：删除备份记录（`DELETE /admin/backup/{id}`，SUCCESS 删 GitHub Release + db 行，FAILED 仅删 db，PENDING/RUNNING 拒绝 3002）+ traceId 全链路（`TraceIdUtil` 统一工具类，AdminAuthFilter / IpRateLimitFilter / GlobalExceptionHandler 三处拒绝响应均拼 traceId）+ 前端 `formatError` 自动展示 traceId + 操作人显示 username（JWT subject 透传，零 IO）
- **v4.2.0**（2026-06-20）— **数据备份**：admin 后台一键加密备份（`POST /admin/backup/run`，异步执行，`blog-backup.sh` 产物 AES-256-CBC 加密上传 GitHub Release）+ 备份历史列表 + 单条详情轮询 + `BackupRecord` 表 + `BACKUP_CONFLICT(3001)` 互斥
- **v4.1.0**（2026-06-19）— 重构优化落地（A1/B1/B2/D 重构项）
- **v4.0.0**（2026-06-18）— **日志体系**（SLF4J/Logback + traceId + 文件滚动 30 天 + 3GB 上限，dev/prod 分离，prod 关 CONSOLE 防 systemd 双写绕过预算）+ **IP 限流封禁**（10 次/秒 + 30 分钟封禁，Redis 计数 + DB 持久化 + admin 后台手动解封 + 应用重启回灌）+ 动态 favicon + 加密数据迁移链路（`sqlite-export.sh` AES-256-CBC + PBKDF2 100k → `sqlite-import.sh` 解密导入，publish-release / deploy-server 通过 `EXPORT_DB` / `IMPORT_DB` 外置开关集成）
- **v2.7.0**（2026-06-17）— 前端改全静态（`nuxt generate` + nginx serve），1C2G 省 150-250MB 内存 + 公开页 SEO 预渲染 + 每日凌晨 3 点 cron `rebuild-static.sh` rebuild
- **v2.6.0**（2026-06-17）— dev/prod 默认改 SQLite（一文件 0 内存），MySQL 降级可选 profile（`spring.profiles.active=*,mysql`）+ 业务 SQL 跨方言统一（38 处）+ 端到端 29 端点验证脚本
- **v2.5.0**（2026-06-16）— 需求五件套（评论自删/自吊销、Toast/Dialog、字数统计、page_view 业务层去重、page_view 统计聚合）
- **v2.2.0**（2026-06-12）— 设备白名单（X-Device-Id 绑定 token）+ API 路由白名单（DB 驱动 + 最长前缀匹配）+ 防重放攻击设计 + 写端点匿名访问漏洞修复
- **v2.1.0**（2026-06-08）— site_settings DB 持久化 + 公开读端点 + 前台首页实时同步后台改动
- **v2.0.0**（2026-06-07）— 8 admin 页 + 5 公开页 + JWT Filter + 13 对页面设计稿审计

---

## 二、项目结构

### 2.1 整体目录

```
my-blog/
├── backend/                           # Spring Boot 多模块后端
├── frontend/                          # Nuxt 3 前端（v2.7.0 全静态）
├── docs/                              # 设计文档与审计报告
│   ├── scripts/                       # 部署 / 验证 / 迁移 / rebuild 脚本（2026-06-18 由 scripts/ 迁移至此）
│   │   ├── deploy-server.sh           # 服务器端一键部署（DEPLOY_MODE=full|code|data）
│   │   ├── publish-release.sh         # 本地打包 + 发布到 GitHub Release（EXPORT_DB=1 加密导出数据）
│   │   ├── sqlite-export.sh           # 加密导出 dev db（AES-256-CBC + PBKDF2 10，产出 .sql.gz.enc）
│   │   ├── sqlite-import.sh           # 解密导入到目标 db（错密码不碰目标 db）
│   │   ├── rebuild-static.sh          # 每日 cron 重建前端静态文件
│   │   ├── verify-sqlite.sh           # 端到点验证脚本（v4.0.0 起 60+ 端点）
│   │   ├── blog-backup.sh             # 数据备份脚本（加密 + 上传 GitHub Release）
│   │   ├── deploy-server.env.example  # deploy env 模板
│   │   ├── deploy.env                 # deploy env（不入库）
│   │   └── sudoers-myblog-restore.example  # 恢复 sudoers 白名单
│   ├── sql/                           # 数据库脚本（v2.6.0 整合后 2 个 schema；2026-06-22 复核：schema-sqlite 15 张 / schema-mysql 13 张，MySQL 缺 restore_record/about_section 是已知遗留，详见 README §3.5）
│   │   ├── schema-mysql.sql           # MySQL 完整 schema + seed data（13 张表，含 backup_record + ip_ban；缺 restore_record + about_section）
│   │   └── schema-sqlite.sql          # SQLite 完整 schema + seed data（dev/prod 默认，15 张表）
│   ├── docker/                        # 历史 docker-compose（v2.5 之前用，v2.6+ 不再推荐）
│   ├── nginx/                         # Nginx 反向代理配置（nginx.conf / nginx-https.conf）
│   ├── 设计文档/                      # 设计方案
│   │   ├── 博客系统设计方案.md
│   │   ├── IP限流封禁方案设计.md       # v4.0.0 IP 限流封禁方案
│   │   ├── 服务日志体系设计.md         # v4.0.0 日志体系需求 + 设计
│   │   ├── 服务日志体系设计_可行性分析.md
│   │   ├── 博客数据备份方案设计.md     # v4.2.0 数据备份方案
│   │   ├── 博客数据恢复方案设计.md     # v5.0 同进程数据恢复方案
│   │   └── 重构优化方案_2026-06-20.md  # v4.1.0 重构优化
│   ├── 数据备份与恢复操作手册.md      # v5.0 数据备份 + 数据恢复操作手册（合并版）
│   ├── 项目部署操作手册.md            # v4.0.0+ 部署操作手册
│   ├── changelogs/                    # 版本变更记录（v2.0.0 → v4.2.1）
│   ├── 接口契约审计报告.md            # API 契约 100% 一致性审计（v4.0.0 快照：13 Controller / 60 端点）
│   └── 项目部署解决方案.md            # 生产部署方案（VPS 1C2G，无 docker）
├── AGENTS.md                          # 项目级 agent 上下文（v4.0.0 同步）
└── README.md                          # 本文件
```

### 2.2 后端项目结构

```
backend/
├── pom.xml                            # 父 POM（packaging=pom，6 个 module）
│
├── blog-common/                       # 公共组件（所有模块共享）
│   └── src/main/java/com/blog/common/
│       ├── Result.java               # 统一响应格式 {code, data, message}
│       ├── ResultCode.java           # 业务码枚举（200/400/401/403/404/500 + 1xxx/2xxx/3xxx）
│       ├── BusinessException.java    # 业务异常
│       ├── GlobalExceptionHandler.java # 全局异常处理（含 traceId 回写）
│       ├── PageRequest.java          # 分页请求参数
│       ├── PageResult.java           # 分页响应结构
│       ├── TrustedProxyUtil.java     # 反代 IP 解析（X-Forwarded-For）
│       └── web/
│           ├── AuthContext.java       # 请求级操作人上下文（uid/deviceId/username）
│           ├── TraceIdFilter.java     # traceId 注入 Filter（@Order HIGHEST_PRECEDENCE）
│           └── TraceIdUtil.java       # traceId 工具（withTraceId 统一拼后缀）
│
├── blog-auth/                         # 用户认证 & JWT
│   └── src/main/java/com/blog/auth/
│       ├── controller/
│       │   ├── AuthController.java        # 登录 / 个人信息 / 改密
│       │   ├── DeviceController.java      # admin 设备管理（列表/审批/吊销/物理删除）
│       │   ├── PublicDeviceController.java # 公开设备状态查询 /public/device/check
│       │   └── IpBanController.java       # IP 封禁查看与手动解封（v4.0.0）
│       ├── entity/
│       │   ├── User.java              # 用户实体（BCrypt 密码）
│       │   ├── AdminDevice.java      # 设备白名单（pending/approved/revoked）
│       │   ├── ApiWhitelist.java     # API 路由白名单（path_prefix/type）
│       │   └── IpBan.java             # IP 封禁记录（v4.0.0，ip/ip_key/expires_at/unbanned）
│       ├── mapper/
│       │   ├── UserMapper.java       # MyBatis-Plus BaseMapper
│       │   ├── AdminDeviceMapper.java
│       │   ├── ApiWhitelistMapper.java
│       │   └── IpBanMapper.java
│       ├── service/
│       │   ├── DeviceService.java     # 设备注册/审批/校验逻辑
│       │   ├── ApiWhitelistService.java # 白名单最长前缀匹配 + 缓存刷新
│       │   └── IpBanService.java      # IP 封禁查询/解封（v4.0.0）
│       └── util/
│           └── JwtUtil.java # JWT 签发/解析（启动时校验 secret 长度）
│
├── blog-article/                       # 文章 & 分类 & 标签 & 访问统计
│   └── src/main/java/com/blog/article/
│       ├── controller/
│       │   └── ArticleController.java # 公开列表/详情 + admin CRUD
│       ├── entity/
│       │   ├── Article.java           # 文章（逻辑删除 deleted=1）
│       │   ├── Category.java          # 分类
│       │   ├── Tag.java               # 标签
│       │   └── PageView.java          # 访问统计（v2.5.0+，visitDate 字段）
│       ├── mapper/
│       │   ├── ArticleMapper.java
│       │   ├── CategoryMapper.java
│       │   ├── TagMapper.java
│       │   └── PageViewMapper.java    # 走 JdbcTemplate.update 跨方言兼容
│       ├── service/
│       │   ├── ArticleService.java    # 文章业务逻辑
│       │   └── PageViewService.java   # 业务层去重（X-Visitor-Id + UK 双重去重）
│       ├── security/
│       │   └── PageViewFilter.java    # 公开页自动写 page_view
│       └── config/
│           └── AsyncConfig.java       # 异步线程池配置
│
├── blog-comment/                       # 评论
│   └── src/main/java/com/blog/comment/
│       ├── controller/
│       │   └── CommentController.java # 公开列表 + 审核 + admin 列表
│       ├── entity/
│       │   └── Comment.java
│       └── service/
│           └── CommentService.java    # 评论业务逻辑
│
├── blog-settings/                      # 站点设置 & 文件上传 & 数据备份/恢复
│   └── src/main/java/com/blog/settings/
│       ├── controller/
│       │   ├── SettingsController.java           # admin 读写（8 tab）+ 文件上传
│       │   ├── UploadController.java             # /admin/uploads 单文件上传（前端实际使用）
│       │   ├── PublicSettingsController.java    # 公开读端点 /public/settings/{section}（匿名可看）
│       │   ├── PublicProfileController.java     # 公开个人资料 /public/profile（前台关于页用）
│       │   ├── BackupController.java            # 数据备份（v4.2.0：触发/列表/详情 + v4.2.1：删除）
│       │   └── RestoreController.java           # 数据恢复（v4.3.0：触发/列表/详情）
│       ├── entity/
│       │   ├── SiteSettings.java                 # 站点设置（8 section）
│       │   ├── BackupRecord.java                 # 备份记录（v4.2.0）
│       │   └── RestoreRecord.java                # 恢复记录（v4.3.0）
│       ├── dto/
│       │   ├── BackupResponse.java               # 备份响应 DTO
│       │   └── RestoreResponse.java              # 恢复响应 DTO
│       ├── mapper/
│       │   ├── SiteSettingsMapper.java
│       │   ├── BackupRecordMapper.java           # v4.2.0
│       │   └── RestoreRecordMapper.java          # v4.3.0
│       ├── service/
│       │   ├── SiteSettingsService.java          # 业务逻辑 + Redis 5min 缓存
│       │   ├── BackupService.java                # 备份触发/异步执行/删除/GitHub Release 管理
│       │   └── RestoreService.java               # 恢复触发/异步执行/状态回填
│       └── config/
│           └── RestoreStartupReconciler.java     # 启动 + @Scheduled 双轨回填恢复状态
│
└── blog-app/ # 启动模块（唯一可执行的 Spring Boot）
    └── src/main/
        ├── java/com/blog/
        │   ├── BlogApplication.java # 启动类
        │   ├── common/                            # ⚠️ 2026-06-22 复核确认实际路径是 com.blog.common（不是 README 早期误写的 com.blog.security）
        │   │   ├── {Result, ResultCode, BusinessException, GlobalExceptionHandler, PageRequest, PageResult, TrustedProxyUtil}.java
        │   │   └── security/                     # Filter 链
        │   │       ├── AdminAuthFilter.java      # JWT 每请求鉴权 + 设备白名单校验（HIGHEST_PRECEDENCE+2）
        │   │       └── IpRateLimitFilter.java    # IP 限流封禁（v4.0.0，Redis + DB，HIGHEST_PRECEDENCE+1）
        │   ├── config/
        │   │   ├── CorsConfig.java               # CORS 跨域（读 yml 环境变量）
        │   │   ├── MybatisPlusConfig.java
        │   │   ├── OpenApiConfig.java            # Swagger
        │   │   └── StaticResourceConfig.java     # 静态资源映射
        │   └── controller/
        │       ├── HelloController.java
        │       ├── DashboardController.java       # 仪表盘聚合（KPI + 30 天趋势 + 热门）
        │       └── ApiWhitelistController.java
        └── resources/
            ├── application-dev.yml      # dev 配置（v2.6.0 默认 SQLite）
            ├── application-prod.yml    # prod 配置（v2.6.0 默认 SQLite，1C2G 调参）
            ├── application-mysql.yml   # 可选 profile 片段（切回 MySQL 用）
            └── logback-spring.xml # 日志配置
```

### 2.3 前端项目结构（v2.7.0 全静态化）

```
frontend/
├── package.json / nuxt.config.ts / tsconfig.json / tailwind.config.js
├── .routes.json                        # build 时由 scripts/fetch-routes.js 生成（预渲染路由清单）
├── app.vue                             # 根组件
├── error.vue                           # 错误页（404/500）
├── assets/css/main.css                  # 全局样式（墨绿 #2f6f5e + 焦糖橙 #c97b3f）
│
├── layouts/
│   ├── default.vue                     # 公开页布局（NavBar + SiteFooter）
│   └── admin.vue                       # admin 布局（含鉴权兜底 onMounted）
│
├── middleware/
│   └── admin-auth.ts                   # SSR-safe 鉴权中间件
│                                          （BUG-001 修复：SSR 阶段跳过 localStorage；v2.7.0 已删 SSR 判断，全静态化后 import.meta.server 恒为 false）
│
├── composables/ # 组合式函数
│   ├── useApi.ts                      # $fetch 封装（统一拦截/错误处理）
│   ├── useAuth.ts                     # 鉴权状态（login/logout/me，v2.7.0 已删 SSR cookie 读取）
│   ├── useAdminApi.ts                 # admin API 封装（自动带 Authorization + X-Device-Id）
│   ├── usePublicApi.ts                # 公开 API 封装（自动加 X-Visitor-Id）
│   ├── useDialog.ts / useToast.ts     # 替代浏览器原生 alert/confirm/prompt
│   ├── useDevice.ts                   # 设备指纹
│   ├── useAdminMeta.ts                # Admin meta 标签
│   ├── useDashboardUtils.ts           # 仪表盘工具函数
│   ├── useImageUpload.ts              # 图片上传逻辑
│   ├── useAdminSettingsTab.ts         # v4.3.0+ admin/settings 子页通用 load/save/saving/message 封装（OPT-005）
│   ├── useSiteFlags.ts                # 2026-06-28 OPT-001/002：站点能力开关显隐（enableRss/enableSearch）
│   └── useVisitor.ts                  # 访客 ID 管理
│
├── components/                        # 公开组件（NavBar / SiteFooter / GlobalDialog / ...）+ v4.3.0+ admin/（NavIcon/SidebarNav/UserChip + settings/SaveBar）+ ui/（DropdownSelector/MultiTagSelect）
├── plugins/                           # Nuxt 插件（auth.client.ts 等）
│
├── pages/ # 25 个页面（6 公开 + 10 admin 顶层 + 9 settings 子路由）
│   ├── index.vue                      # 首页（Hero + 文章列表）
│   ├── post/[slug].vue                # 文章详情（v2.7.0 build 时预渲染）
│   ├── archives.vue                   # 归档
│   ├── tags.vue                       # 标签云
│   ├── about.vue                      # 关于页
│   ├── search.vue                     # 2026-06-28 OPT-002：搜索结果页（关键词 + 分页 + 摘要）
│   └── admin/
│       ├── login.vue                  # 登录页
│       ├── dashboard.vue              # 仪表盘
│       ├── posts.vue                  # 文章管理
│       ├── edit.vue                   # 文章编辑
│       ├── comments.vue               # 评论管理
│       ├── categories.vue             # 分类管理
│       ├── tags.vue                   # 标签管理
│       ├── devices.vue                # 设备管理
│       ├── backup.vue                 # 数据备份（v4.2.0 + v4.2.1 polish）
│       ├── restore.vue                # 数据恢复（v4.3.0）
│       ├── settings.vue               # 站点设置外壳（v4.3.0+：page-head + <NuxtPage /> 出口，/admin/settings → /admin/settings/profile 重定向）
│       └── settings/                  # 站点设置 9 子路由（v4.3.0+，每页独立 onMounted GET + save + message）
│           ├── profile.vue            # 个人资料（保存后 useAuth().updateUser 同步顶栏 nickname/avatar）
│           ├── password.vue           # 修改密码（ref + onSuccess/onError handler 模式）
│           ├── blog.vue               # 站点信息
│           ├── techstack.vue          # 技术栈
│           ├── experience.vue         # 个人经历
│           ├── theme.vue              # 主题外观
│           ├── social.vue             # 社交账号
│           ├── preferences.vue        # 偏好设置
│           └── advanced.vue           # 高级
│
├── scripts/
│   └── fetch-routes.js                # build 前拉后端所有公开页 slug，生成 .routes.json
│
└── Dockerfile                         # v2.7.0：node build → nginx alpine serve 静态
```

---

## 三、技术方案

### 3.1 技术栈总览

| 层 | 选型 | 版本 |
|----|------|------|
| **后端语言** | Java | 1.8（开发机环境限制） |
| **后端框架** | Spring Boot | 2.7.18 |
| **ORM** | MyBatis-Plus | 3.4.3.4 |
| **数据库（默认）** | SQLite | 3.45.0.0（dev/prod 默认，v2.6.0 起） |
| **数据库（可选）** | MySQL | 8.0（`spring.profiles.active=*,mysql` 切回） |
| **缓存** | Redis | 7.x（apt 装系统服务） |
| **鉴权** | JJWT | 0.11.5 |
| **工具库** | Hutool + Lombok | 5.8.27 / 1.18.x |
| **API 文档** | springdoc-openapi-ui | 1.7.0 |
| **前端框架** | Nuxt 3 | ^3.13（`ssr: false`，v2.7.0 全静态 generate） |
| **包管理** | npm | 11.x |
| **样式** | Tailwind CSS | ^3.4 |
| **类型** | TypeScript | ^5.5 |
| **构建** | Maven | 3.6.0 |
| **部署** | nginx:alpine（前端静态服务） + openjdk-8-jdk（后端） + redis-server + sqlite3 | 无 docker（v2.6.0 起） |

### 3.2 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                       用户浏览器                         │
│         http://localhost:3000 (dev)                       │
│         https://yourname.com (prod, HTTPS)               │
└────────────────────────┬────────────────────────────────┘
                         │ HTTP / HTTPS
                         ▼
┌─────────────────────────────────────────────────────────┐
│                     Nginx                                │
│   - 80 → 443 强制跳转                                   │
│   - HTTPS (Let's Encrypt 证书)                          │
│   - 静态文件 /uploads/ 直接 serve                       │
│   - /api/* 反代到 backend:8080                          │
│   - nginx 层限流（登录 5req/min，API 20req/s）          │
│   - **应用层 IP 限流封禁（v4.0.0，10 次/秒 + 30 分钟封禁）**
│   - 前端静态目录 /var/www/blog/（v2.7.0 配套）         │
└────────────────────────┬────────────────────────────────┘
          ┌──────────────┴──────────────┐
          ▼                             ▼
┌──────────────────────┐   ┌─────────────────────────────┐
│ nginx:alpine (3000)  │   │   Spring Boot (8080)         │
│ 静态文件 serve       │   │   后端 REST API              │
│ .output/public/      │   │   (v2.7.0 后只剩后端 JVM)    │
│ v2.7.0 预渲染 HTML   │   │   - TraceIdFilter (HIGHEST)  │
└──────────────────────┘   │   - IpRateLimitFilter (+1)   │
                          │   - AdminAuthFilter          │
                          └──────────────┬──────────────┘
                                ┌──────────┴──────────┐
                                ▼                     ▼
                         ┌────────────┐         ┌──────────────┐
                         │  SQLite    │         │    Redis     │
                         │  blog.db   │         │  7.x         │
                         │ (v2.6 默认)│         │  :6379       │
                         │ 14 张表  │         │ (login限流/IP)│
                         └────────────┘         └──────────────┘
                                                       │
                                              ┌────────┴────────┐
                                              ▼                 ▼
                                       ┌──────────────┐  ┌─────────────┐
                                       │ /opt/myblog/  │  │ Logback 日志│
                                       │   logs/       │  │ 滚动 30天  │
                                       │ (3GB 上限)    │  │ traceId 串联│
                                       └──────────────┘  └─────────────┘
```

**v2.7.0 关键变化**：前端进程从 "node + Nitro server" 改成 "nginx:alpine 静态服务"，**省 150-250MB 内存**（1C2G 服务器上 JVM heap 可从 256MB 提到 384MB）。

**v4.0.0 关键变化**：
- **Filter 链**：TraceIdFilter（`@Order(HIGHEST_PRECEDENCE)`）→ IpRateLimitFilter（`HIGHEST_PRECEDENCE + 1`）→ AdminAuthFilter → 业务 Controller，确保任何被拦截的请求都带 traceId 日志。
- **日志落盘**：所有请求带 traceId 进 Logback 文件，30 天滚动 3GB 上限，prod 关 CONSOLE 防 systemd 双写绕过预算。
- **IP 封禁**：触发后写 Redis 标记 + DB 持久化 + 30 分钟自动解封，admin 可手动 `PUT /admin/ip-bans/{id}/unban` 提前解封。

**v4.2.0 关键变化**：
- **数据备份**：`BackupService` 异步调用 `blog-backup.sh`（`ProcessBuilder` + `systemd-run --scope`），产物加密上传 GitHub Release；`BackupRecord` 表记录状态；`trimOldRecords` 自动保留最近 30 条 SUCCESS。
- **traceId 全链路**：`TraceIdUtil.withTraceId()` 统一工具类，AdminAuthFilter / IpRateLimitFilter / GlobalExceptionHandler 三处拒绝响应均拼 traceId 后缀，前端 `formatError` 自动展示。

### 3.3 后端模块依赖关系

```
blog-app（启动类，唯一可执行 jar）
  ├── blog-common（公共组件，所有模块共享）
  ├── blog-auth（用户 + JWT Filter）
  ├── blog-article（文章/分类/标签/访问统计，依赖 User 显示作者）
  ├── blog-comment（评论，依赖 User）
  └── blog-settings（设置，依赖 common + auth）
```

### 3.4 API 风格

所有接口前缀 `/api/v1`，统一响应格式：

```json
// 成功
{ "code": 200, "data": { ... }, "message": "ok" }

// 错误
{ "code": 401, "message": "未登录" }
```

业务码规范：

| code | 含义 | code | 含义 |
|---|---|---|---|
| 200 | 成功 | 401 | 未登录 |
| 400 | 参数错误 | 403 | 无权限 |
| 404 | 资源不存在 | 500 | 服务器错误 |
| 100x | 文章/分类/标签/评论/用户 not found | 1006 | 凭证错误 |
| 2001 | 设备未授权 | 2002 | 设备已吊销 |
| 2003 | 自删/自吊销禁止 | 1007 | Token 无效 |
| 3001 | 已有 RUNNING 备份任务 | 3002 | 备份进行中，无法删除 |

详见 `docs/接口契约审计报告.md`。

### 3.5 数据库设计（schema-sqlite 实际 15 张 / schema-mysql 实际 13 张）

> **2026-06-22 复核确认**：dev/prod 默认 SQLite 实际 **15 张表**（含 `restore_record`），MySQL schema 实际 **13 张表**（含 `backup_record`，**缺 `restore_record` + `about_section`**）。MySQL schema 缺这两张是**已知遗留**，下次切回 MySQL profile 前需要补 DDL（参考 schema-sqlite.sql 的同段 SQL）。下表按"代码实际涉及"的 15 张列，标注哪些 MySQL schema 暂未建。

| 表名 | 说明 | 关键字段 | schema-sqlite | schema-mysql |
|------|------|----------|---------------|--------------|
| `user` | 管理员账号 | username / password_hash (BCrypt) / nickname / email / avatar / bio / location / role | ✅ | ✅ |
| `article` | 文章 | title / slug / summary / content_md / cover_url / status / view_count / category_id / deleted | ✅ | ✅ |
| `category` | 分类 | name / slug / description / sort / visible | ✅ | ✅ |
| `tag` | 标签 | name / slug | ✅ | ✅ |
| `article_tag` | 文章-标签关联 | article_id / tag_id | ✅ | ✅ |
| `comment` | 评论 | content / article_id / parent_id / nickname / email / website / ip / user_agent / status (0待审/1通过/2屏蔽) | ✅ | ✅ |
| `article_view_log` | 历史表（v2.5.0 之前的访问日志，0 数据保留 schema 兼容） | article_id / view_date / view_count | ✅ | ✅ |
| `site_settings` | 站点设置（按 section 存整段 JSON，v2.1 新增，v2.3 扩到 8 section） | section (blog/social/preferences/theme/advanced + techstack/experience) / data (JSON) | ✅ | ✅ |
| `admin_device` | 设备白名单（v2.2 新增） | device_id / status (pending/approved/revoked) | ✅ | ✅ |
| `api_whitelist` | API 路由白名单（AdminAuthFilter 用，v2.2 新增） | path_prefix / type (public/admin) / enabled / description | ✅ | ✅ |
| `page_view` | 访问统计（v2.5.0 新增，v2.6.0 业务层去重） | visitor / url / visit_date / ua / ip | ✅ | ✅ |
| `ip_ban` | IP 封禁记录（v4.0.0 新增，限流超阈值时落库 + 手动解封） | ip / expires_at / unbanned / created_at | ✅ | ✅ |
| `backup_record` | 备份记录（v4.2.0 新增，异步备份状态 + GitHub Release tag） | status / tag / started_at / finished_at / error_stage / operator_name | ✅ | ✅ |
| `restore_record` | 恢复记录（v4.3.0 新增，异步恢复状态） | status / source_tag / scope / started_at / finished_at / error_stage | ✅ | **❌ 待补** |
| `about_section` | 关于页 section（v2.3 设计意图，DDL 见 `docs/design/博客系统设计方案.md` §5.2.4） | section_key / data / sort / enabled | **❌ 设计意图未落地**（复用 site_settings.techstack/experience） | **❌ 设计意图未落地** |

**SQL 文件**（v2.6.0 整合后，2 个 schema 替代 8 个散文件）：
- `docs/sql/schema-sqlite.sql` — **dev/prod 默认**，15 张表 + seed data（含 `restore_record`）
- `docs/sql/schema-mysql.sql` — MySQL 可选 profile，13 张表 + seed data（**缺 `restore_record` + `about_section`**）

> **schema-mysql 待补清单（TODO，下次切回 MySQL profile 前修复）**：
> - 补 `restore_record` 表（参考 `schema-sqlite.sql` 第 12 段，需要把 `INTEGER PRIMARY KEY AUTOINCREMENT` 改为 `BIGINT AUTO_INCREMENT`，并加 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` 引擎声明；MySQL 8.0 不支持 SQLite 的 partial unique index `WHERE status = 'RUNNING'`，需改用 `status` 字段建单列 unique + 应用层兜底，参考 `schema-mysql.sql` 里 backup_record 的写法）
> - `about_section` 表按当前事实不需要补（设计意图未落地，前台 `/about` 走 `site_settings.techstack`/`site_settings.experience`）

### 3.6 配色契约

- **墨绿**：`#2f6f5e` — 主色调
- **焦糖橙**：`#c97b3f` — 强调色
- 定义在 `frontend/assets/css/main.css`

---

## 四、开发环境部署

### 4.1 前置条件

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 1.8+ | 环境降级（详见 `AGENTS.md` §4.1） |
| Maven | 3.6+ | 后端构建 |
| Node.js | 18+ | 推荐 22+ |
| npm | 11.x | 前端包管理 |
| sqlite3 | ≥ 3.30 | **v2.6.0 起需要**（命令行工具，可选；sqlite-jdbc 自带驱动） |
| Redis | 7.x | 本机服务或 docker run |
| Docker | 最新 | **可选**：仅在需要 MySQL profile 或 Redis 容器化时使用 |

### 4.2 启动步骤（v2.6.0 起：SQLite 默认）

#### 第一步：启动 Redis（SQLite 是文件型 DB，不需要单独启动）

```bash
# 方式 A：docker run（推荐）
docker run -d --name blog-redis -p 6379:6379 redis:7-alpine

# 方式 B：apt 装本机服务（Linux）
sudo apt install redis-server
sudo systemctl start redis-server
```

#### 第二步：初始化 SQLite db（仅首次需要）

```bash
# SQLite 默认在首次启动时自动建库（schema 由 MyBatis-Plus 控制）
# 如需手动初始化（参考 schema 中的 seed data）：
sqlite3 backend/blog.db < docs/sql/schema-sqlite.sql

# 如果仍要 MySQL 模式：
docker run -d --name blog-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=blog \
  -p 3306:3306 \
  -v blog-mysql-data:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

# 切回 MySQL profile 启动后，手动导入（**必须带 --default-character-set=utf8mb4**）
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/schema-mysql.sql
```

#### 第三步：启动后端（SQLite 默认 / MySQL 加 `,mysql`）

```bash
cd backend

# 默认（SQLite）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 切回 MySQL
mvn spring-boot:run -Dspring-boot.run.profiles=dev,mysql
```

健康检查：

```bash
curl http://localhost:8080/api/v1/health
# 期望：{"code":200,"data":{"status":"UP",...}}

# 测试登录（默认账号 admin / 123456，**生产环境必须改**）
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

Swagger 文档：[http://localhost:8080/api/v1/swagger-ui.html](http://localhost:8080/api/v1/swagger-ui.html)

#### 第四步：启动前端（dev 模式，HMR）

```bash
cd frontend
npm install   # 首次约 30 秒
npm run dev
```

打开 [http://localhost:3000](http://localhost:3000)。

> **生产构建**：`npm run build` 跑 `nuxt generate`，产物在 `.output/public/`（详见 §5）。

### 4.3 一键停止

```bash
docker stop blog-redis blog-mysql   # 启了 docker 的才需要
# backend/ 和 frontend/ 目录分别 Ctrl+C
```

### 4.4 开发注意事项

1. **改完模块必须 install**：每次修改 `blog-auth` / `blog-article` 等模块后，必须运行 `mvn -pl blog-xxx -am install -DskipTests`，否则 `mvn spring-boot:run` 不会加载新类（classpath 走 jar 而非 target/classes）
2. **MySQL 中文 SQL 导入**：用 `--default-character-set=utf8mb4`，不要用 docker exec pipe 写含中文的 SQL 数据（会双重编码，**SQLite 无此问题**）
3. **SQLite WAL 模式**：v4.0.0 起开 WAL（`journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL`），Hikari `maximum-pool-size` 已放开到 **8**（WAL 下「多读+单写」可并发，`busy_timeout` 让偶发写竞争等待而非立刻 SQLITE_BUSY）
4. **admin SSR 鉴权**：`frontend/middleware/admin-auth.ts` 已修复 SSR 阶段跳过 localStorage，直接访问 `/admin/*` 不会误踢已登录用户（v2.7.0 全静态化后 `import.meta.server` 恒为 `false`，SSR 判断代码已删）
5. **端到端验证**：`scripts/verify-sqlite.sh` 跑 60+ 个端点（含 page_view 业务层去重验证、备份/恢复端点），全过后才算 dev 完成

---

## 五、生产环境部署

> 详细方案见 [`docs/项目部署解决方案.md`](docs/项目部署解决方案.md) + [`docs/项目部署操作手册.md`](docs/项目部署操作手册.md)。**v2.6.0 起部署架构简化**：1C2G 无 docker + SQLite + 全静态前端。

### 5.1 部署架构

**阿里云香港轻量 ECS 单机部署**（1C2G，~¥30/月，免备案）：

```
用户浏览器 (HTTPS)
      ↓
Nginx (:80 → 443)         ← apt 装 nginx（系统服务）
  ├── /api/* → Spring Boot :8080
  ├── / 静态文件 → /var/www/blog/   ← v2.7.0 全静态
  └── /uploads/* → 本地磁盘 /data/uploads/
           ↓
   ┌───────────────────────────────────┐
   │  ECS 单机（无 docker）              │
   │  - openjdk-8-jdk                   │
   │  - redis-server (apt + systemd)    │
   │  - sqlite3 (apt)                   │
   │  - nginx (apt + systemd)           │
   │  - blog-app.jar (systemd)          │
   │  - 静态文件 /var/www/blog/         │
   └───────────────────────────────────┘
```

**v2.6.0/v2.7.0 关键变化**（对比 v2.5.0 的 Docker Compose 5 容器方案）：
- 数据库：MySQL 8.0 → **SQLite**（一文件 0 内存）
- 缓存：redis:7-alpine 容器 → **redis-server 系统服务**
- 前端：Nuxt SSR node 容器 → **nginx:alpine 静态服务**（v2.7.0）
- 编排：docker-compose → **systemd**（更轻量、1C2G 友好）

### 5.2 前置资源

| 资源       | 规格                           | 成本     |
|----------|------------------------------|--------|
| VPS 洛杉矶 | 1C2G / 30G SSD / 3M 带宽       | Vultr 1C2G 约 $5/月 ≈ ¥35/月 ≈ ¥420/年（2026-06-22 复核：README 早期误写 ¥70/年、设计文档误写 ¥30/月，本表以 Vultr 官网当前公开价为准） |
| 域名       | `coreyai.cn`                 | ¥55/年  |
| SSL 证书   | Let's Encrypt (certbot 自动续期) | ¥0     |

> v2.7.0 释放前端 150-250MB 内存后，JVM heap 可从 256MB 提到 384MB（详见 `scripts/deploy-server.sh`）。

### 5.3 部署流程（v2.6.0 起）

```bash
# ============ Day 1：本地构建 ============
git clone <your-repo>
cd my-blog

# 后端
cd backend && mvn clean package -DskipTests
# 产物：backend/blog-app/target/blog-app.jar
cd ..

# 前端
cd frontend && npm install && npm run build
# 产物：frontend/.output/public/（静态文件）
cd ..

# ============ Day 2：上传到 ECS ============
# 创建 myblog 系统用户（脚本里会自动）
# 上传 jar
scp backend/blog-app/target/blog-app.jar myblog@<ecs-ip>:/tmp/

# 上传 schema
scp docs/sql/schema-sqlite.sql myblog@<ecs-ip>:/tmp/

# 上传前端产物
scp -r frontend/.output/public myblog@<ecs-ip>:/tmp/

# 上传 deploy 脚本
scp scripts/deploy-server.sh myblog@<ecs-ip>:/tmp/

# ============ Day 3：ECS 上一键部署 ============
ssh myblog@<ecs-ip>
sudo mv /tmp/blog-app.jar /opt/myblog/
sudo mv /tmp/schema-sqlite.sql /opt/myblog/
sudo mv /tmp/public /opt/myblog/frontend-static
sudo mv /tmp/deploy-server.sh /opt/myblog/scripts/
sudo chmod +x /opt/myblog/scripts/deploy-server.sh
sudo bash /opt/myblog/scripts/deploy-server.sh
```

`deploy-server.sh` 自动完成：
1. 权限检查（需 root）
2. 系统依赖检查（java / redis-server / sqlite3）
3. 创建 `myblog` 系统用户
4. 建 `/opt/myblog/{logs,backups,uploads}` 目录结构
5. 检查 `blog-app.jar` 是否在 `/opt/myblog/`
6. **首次部署**：用 `schema-sqlite.sql` 初始化 `/opt/myblog/blog.db`
7. 启动 redis（systemd）
8. 创建 systemd service（安全加固：NoNewPrivileges / PrivateTmp / ProtectSystem=strict）
9. 重启服务 + 30 秒健康检查轮询
10. 输出运维命令清单

> 支持 `DEPLOY_MODE=full|code|data` + `IMPORT_DB=1` 钩子（详见 `AGENTS.md` §5）。

### 5.4 每日 cron 重建静态（v2.7.0 配套）

新文章发布后最迟 24h 内可见，配套每日凌晨 3 点 cron：

```bash
# 上传 rebuild 脚本
scp scripts/rebuild-static.sh myblog@<ecs-ip>:/opt/myblog/scripts/

# 配置 cron（myblog 用户视角）
ssh myblog@<ecs-ip>
crontab -e
# 加一行：
0 3 * * * bash /opt/myblog/scripts/rebuild-static.sh
```

`rebuild-static.sh` 工作流：
1. 备份当前 `.output/public/` → `.output/.history/public-{timestamp}/`（保留 3 份）
2. 拉最新代码（`git pull --ff-only`，失败不阻塞）
3. 跑 `nuxt generate`（30-90s，吃 200-300MB）
4. 校验 `.output/public/index.html` 存在
5. 同步到 nginx 服务目录 `/var/www/blog/`（**先同步到临时目录再原子 rename**）
6. `nginx -s reload`（保险）
7. 健康检查 `/healthz`
8. 写日志 `/var/log/myblog-rebuild.log`

### 5.5 端到端验证

```bash
# 在本地（dev 环境）跑端到端验证
bash scripts/verify-sqlite.sh

# 期望输出："✅ 全部 XX 个端点通过"
# 包含：
#   - 公开端点（健康、文章、分类、标签、归档、评论、设置、设备检查）
#   - admin 端点（鉴权 + 仪表盘 + 各管理模块）
#   - 写操作端点（更新 profile、page_view 写入/去重）
#   - 数据备份端点（触发/列表/详情/删除）
#   - 数据恢复端点（触发/列表/详情）
#   - 业务层去重验证（X-Visitor-Id 同访客连刷 5 次 → DB +1 行）
```

### 5.6 生产环境配置

所有敏感信息通过环境变量注入，不在仓库中明文保存：

```bash
# 复制模板（2026-06-22: 改用 scripts/deploy-server.env.example,早期 .env.prod.example 已删）
cp scripts/deploy-server.env.example scripts/deploy-server.env
# 编辑真实值后 ssh 上传到服务器 /etc/myblog/myblog.env:
#   scp scripts/deploy-server.env myblog@<ecs-ip>:/tmp/myblog.env
#   ssh myblog@<ecs-ip> 'sudo mv /tmp/myblog.env /etc/myblog/myblog.env && sudo chmod 600 /etc/myblog/myblog.env'
```

关键生产改造（`docs/项目部署解决方案.md` §七）：

| 改造项 | 说明 |
|--------|------|
| BCrypt 密码加密 | 改用 Hutool BCrypt，明文比对 → `BCrypt.checkpw()` |
| JWT secret 强随机 | 32+ 位随机，启动时 `JwtUtil.validateSecret()` 校验长度 |
| CORS 收紧 | 不再允许 `*`，只允许你的域名 |
| Swagger 生产关闭 | `blog.swagger.enabled=false` |
| 文件上传路径 | 改用环境变量 `UPLOAD_DIR`（容器内 `/data/uploads/`） |
| 前端 apiBase | 通过 `process.env.NUXT_PUBLIC_API_BASE` 注入 |
| **JVM 调参（v2.6.0）** | `JAVA_OPTS=-Xms128m -Xmx384m -XX:+UseG1GC`（v2.7.0 释放前端内存后从 256MB 提到 384MB） |
| **Tomcat 调参（v2.6.0）** | `max-threads=50`（1C2G 从 100 调小，省 ~25MB 栈空间） |

### 5.7 上线前 CheckList

- [ ] **安全**：BCrypt 密码 / JWT secret 强随机 / Swagger 关闭 / CORS 收紧 / SSH 密钥登录
- [ ] **配置**：`application-prod.yml` / `scripts/deploy-server.sh` / `nginx.conf` 就绪
- [ ] **数据**：`schema-sqlite.sql` 自动导入 / 默认 admin 密码修改 / 删除测试数据
- [ ] **HTTPS**：证书部署 / 80 → 443 强制跳转
- [ ] **静态化**：`nuxt generate` 产物已上传 `/var/www/blog/` / `rebuild-static.sh` cron 已配
- [ ] **备份**：`sqlite3 blog.db ".backup backups/blog-YYYYMMDD.db"` 自动备份 + `/data/uploads/` 同步 OSS 冷存
- [ ] **监控**：systemd 容器状态监控 + `/api/v1/health` 健康检查 + `/healthz` 静态服务健康检查

---

## 六、快速参考

### 6.1 常用命令

```bash
# ============ 开发启动（v2.6.0 起：SQLite 默认）============
docker run -d --name blog-redis -p 6379:6379 redis:7-alpine
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm run dev

# 切回 MySQL 模式（可选）
docker run -d --name blog-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=blog \
  -p 3306:3306 -v blog-mysql-data:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/schema-mysql.sql
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,mysql

# 健康检查
curl http://localhost:8080/api/v1/health

# 一键停止
docker stop blog-redis blog-mysql

# ============ 端到端验证 ============
bash scripts/verify-sqlite.sh

# ============ Dev → Prod 加密数据迁移（v4.0.0 起）============
# 1. dev 导出加密 dump（交互式输两次密码，产出 .sql.gz.enc）
bash scripts/sqlite-export.sh --exclude page_view -o /tmp/migration.sql.gz.enc

# 2. 上传到 ECS
scp /tmp/migration.sql.gz.enc myblog@<ecs-ip>:/tmp/

# 3. 生产端解密 + 导入（交互式输一次密码；错密码不碰目标 db）
ssh myblog@<ecs-ip> "sudo bash /opt/myblog/scripts/sqlite-import.sh /opt/myblog/db/blog.db /tmp/migration.sql.gz.enc"

# ============ 生产部署（v2.6.0 起，v4.0.0+ DEPLOY_MODE）============
# 上传 jar + schema + 脚本到 ECS
scp backend/blog-app/target/blog-app.jar myblog@<ecs-ip>:/tmp/
scp docs/sql/schema-sqlite.sql myblog@<ecs-ip>:/tmp/
scp scripts/deploy-server.sh myblog@<ecs-ip>:/tmp/

# ECS 一键部署（v4.0.0 起标准方式）
sudo DEPLOY_MODE=full bash /opt/myblog/scripts/deploy-server.sh
# DEPLOY_MODE=full|code|data + IMPORT_DB=1 钩子

# 每日 cron rebuild（v2.7.0 配套）
0 3 * * * bash /opt/myblog/scripts/rebuild-static.sh

# ============ 数据备份（v4.2.0 起）============
# 触发加密备份（admin 后台 UI 或 SSH）
# UI：admin → 系统 → 数据备份 → 立即备份
# SSH：sudo -E bash /opt/myblog/scripts/blog-backup.sh

# ============ 数据库热备份（SQLite）============
sqlite3 /opt/myblog/blog.db ".backup /opt/myblog/backups/blog-$(date +%Y%m%d-%H%M%S).db"
```

### 6.2 目录约定

| 约定 | 值 |
|------|---|
| 后端包名 | `com.blog.*` |
| 数据库 | SQLite: `backend/blog.db`（dev）/ `/opt/myblog/blog.db`（prod）；MySQL: `blog` |
| API 前缀 | `/api/v1` |
| 文件上传 | `/data/uploads/yyyy/mm/<uuid>.<ext>`（dev: `var/uploads/`） |
| Swagger | `http://localhost:8080/api/v1/swagger-ui.html` |
| 静态产物 | `frontend/.output/public/`（dev build） → `/var/www/blog/`（prod） |
| systemd service | `/etc/systemd/system/myblog.service` |

### 6.3 参考文档

| 文档 | 说明                                         |
|------|--------------------------------------------|
| [`docs/design/博客系统设计方案.md`](docs/design/博客系统设计方案.md) | 完整需求与架构设计（v4.2.1 增量更新：v3.1 之前的快照保留作为历史，v4.0.0~v4.3.0 走 §11 v4.x 增量变更记录段 + 各子设计文档）       |
| [`docs/项目部署解决方案.md`](docs/项目部署解决方案.md) | 部署解决方案（VPS 1C2G，无 docker，v4.0.0 配套） |
| [`docs/项目部署操作手册.md`](docs/项目部署操作手册.md) | 部署操作手册（v4.0.0+ 一步步操作）              |
| [`docs/接口契约审计报告.md`](docs/接口契约审计报告.md) | API 契约 100% 一致性审计（v4.0.0 快照：13 Controller / 60 端点，v4.2.0+ 新增备份/恢复端点待补） |
| [`docs/数据备份与恢复操作手册.md`](docs/数据备份与恢复操作手册.md) | 数据备份 + 数据恢复操作手册（v5.0 合并版,含同进程恢复 / 备份同步 / 应急流程） |
| [`docs/design/博客数据备份方案设计.md`](docs/design/博客数据备份方案设计.md) | 数据备份方案设计（v4.2.0 已实现）                |
| [`docs/design/博客数据恢复方案设计.md`](docs/design/博客数据恢复方案设计.md) | 数据恢复方案设计（v5.0 同进程不停服已实现）       |
| [`docs/design/IP限流封禁方案设计.md`](docs/design/IP限流封禁方案设计.md) | IP 限流封禁方案（v4.0.0 已实现）                |
| [`docs/design/服务日志体系设计.md`](docs/design/服务日志体系设计.md) | 服务日志体系设计（v4.0.0 已实现）               |
| [`docs/design/重构优化方案_2026-06-20.md`](docs/design/重构优化方案_2026-06-20.md) | 重构优化方案（v4.1.0 已实现）                   |
| [`docs/changelogs/`](docs/changelogs/) | 版本变更记录（v2.0.0 → v4.2.1，每个版本独立 md）          |
| [`AGENTS.md`](AGENTS.md) | 项目级 agent 上下文（v4.2.0 同步更新）                 |

---

## 七、暂未实现（后续规划）

### 7.1 已完成

- ✅ **数据导入/导出**（v2.6.0 落地 MySQL → SQLite 工具；v4.0.0 替换为 `sqlite-export.sh` / `sqlite-import.sh` 加密链路，AES-256-CBC + PBKDF2 100k）
- ✅ **自动化单元测试**（v2.6.0 `verify-sqlite.sh` 端到点验证脚本，v4.0.0 起 60+ 端点）
- ✅ **Flyway / Liquibase 数据库迁移**（v2.6.0 整合到 2 个 schema 文件，等价于"单文件 migration"）
- ✅ **SQLite WAL 模式 + 并发优化**（v4.0.0：WAL + busy_timeout + pool-size 放开到 8）
- ✅ **数据备份**（v4.2.0：admin 后台一键加密备份 → GitHub Release + v4.2.1 polish：删除记录 / traceId 全链路）
- ✅ **数据恢复**（v4.3.0：admin 后台选择备份 → 异步恢复 + 容错轮询 + 启动回填）

### 7.2 backlog（按优先级）

| 优先级 | 项 | 说明 |
|---|---|---|
| 🟡 中 | page_view 长期数据清理 | v2.6.0 起无分区（SQLite 不支持），按月清理脚本待加 |
| 🟡 中 | article.word_count 写时计算 | 写文章时计算并入库，避免 dashboard `computeTotalWordCount` 全表扫描 |
| 🟡 中 | 评论树形结构 | 按 `parent_id` 递归（当前扁平列表） |
| 🟡 中 | Redis 缓存层扩展 | 当前仅 `site_settings` 接入 Redis（5min TTL），文章详情/列表/分类/标签缓存待补 |
| 🟢 低 | 图片懒加载 + WebP 转换 | 公开页图片优化 |
| 🟢 低 | GitHub Actions CI/CD | 自动化测试 + 镜像推送（v2.6.0/v2.7.0 部署脚本已就绪，CI 配套待加） |
| 🟢 低 | rebuild 事件触发 | 从每日 cron 改为写文章时触发（30-60s 延迟） |
| 🟢 低 | CDN 加速 | 把 `/var/www/blog/` 同步到阿里云 OSS / CDN，国内访问加速 |
| 🟢 低 | 告警脚本 | build 失败发邮件/微信 |
| 🟢 低 | 访客 cookie 强制下发 | PageViewFilter `Set-Cookie: blog_visitor_id` 给无 X-Visitor-Id 的访客（彻底解决 curl/反爬漏统计） |
| 🟢 低 | 预渲染缓存 + 增量构建 | nuxt generate 改成只构建新增/修改的文章 |
| 🟢 低 | 设计稿 P0/P1 修复 | 暂无文档归档，后续补 `docs/设计稿符合性报告.md` |
