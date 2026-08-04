# AGENTS.md

> my-blog 项目的 agent 上下文,轻量版（~200 行）。
> 供 OpenCode / Codex / Cursor / Aider / Devin / Gemini CLI 等 agent 启动时自动加载。
> 消费规范：https://agents.md

---

## 1. TL;DR

- **栈**：Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离；**SQLite 3.45**（dev/prod 默认，MySQL 8.0 可选 profile）+ Redis 7.x；JWT 鉴权；API 前缀 `/api/v1`
- **v2.7.0 前端全静态**：`nuxt generate` + nginx:alpine serve `.output/public/`，**省 150-250MB 内存**
- **v4.0.0 三大件**：①日志体系（SLF4J/Logback + traceId + 文件滚动 30 天 + 3GB 上限，dev/prod 分离）②IP 限流封禁（10 次/秒 + 30 分钟封禁，Redis 计数 + DB 持久化 + admin 手动解封 + 应用重启回灌）③加密数据迁移（`sqlite-export.sh` AES-256-CBC + PBKDF2 100k → `sqlite-import.sh` 解密导入）
- **2026-06-18 加密数据迁移**：`sqlite-export.sh` 加密导 db → `.sql.gz.enc`（AES-256-CBC + PBKDF2 100k）→ `sqlite-import.sh` 解密导入；publish-release.sh / deploy-server.sh 通过 `EXPORT_DB` / `IMPORT_DB` / `DEPLOY_MODE` 外置开关集成
- **入口**：先看 §10 关键文件索引 + §8 用户偏好（强制遵守） + §9 安全红线

---

## 2. 项目结构

- `backend/` — Spring Boot 多模块
  - `blog-common` / `blog-auth` / `blog-article` / `blog-comment` / `blog-settings` / `blog-app`
- `frontend/` — Nuxt 3（v2.7.0 全静态）
  - `pages/` 13 个（5 公开 + 8 admin）
  - `layouts/admin.vue` — **admin 布局 + 鉴权兜底**
  - `middleware/admin-auth.ts` — **SSR-safe 鉴权**（v2.7.0 全静态化后 `import.meta.server` 恒为 `false`，SSR 判断已删）
  - `composables/` — `useApi` / `useAuth` / `useAdminApi` / `usePublicApi` / `useDialog` / `useToast` / `useDevice` / `useAdminMeta`
  - `components/` / `plugins/` / `nuxt.config.ts` / `scripts/fetch-routes.js`（build 前拉公开页路由）
- `scripts/` — 部署/验证/迁移/rebuild（2026-06-22 由 `scripts/` 迁移至根目录）
  - `deploy-server.sh` — 服务器端一键部署（**v4.4.0：4 种 DEPLOY_MODE** = `init` / `full` / `docker-create` / `docker-init`，外置开关 `IMPORT_DB=1` 灌数据；**v5.3.1：下载脚本用 `releases/latest/download/`，执行时传版本号**）
  - `publish-release.sh` — 本地打包 + 发布到 GitHub Release（`EXPORT_DB=1` 钩子）
  - `sqlite-export.sh` — **加密导出** dev db（`AES-256-CBC + PBKDF2 100k`，交互式密码两次输入；产出 `.sql.gz.enc`）
  - `sqlite-import.sh` — **解密导入** 到目标 db（密码一次输入；支持本地 + `--remote user@host` 远端模式；错密码不碰目标 db）
  - `rebuild-static.sh` — 每日 cron 重建前端静态文件
  - `verify-sqlite.sh` — 端到点验证脚本（v2.6.0 29 端点；v4.0.0 已扩到 60 端点）
- `docs/` — `requirements/`（含 `https配置文档.md`） / `design/`（`博客系统设计方案.md` + `IP限流封禁方案设计.md` + `服务日志体系设计.md`） / `接口契约审计报告.md` / `changelogs/`（v2.0.0 → v4.2.1）/ `sql/`（v2.6.0 整合后 2 个 schema）/ `deployment/`（`docker/` + `nginx/` 合并）/ `prompts/` + **`项目部署操作手册.md`** + **`项目部署解决方案.md`**
- `README.md` / `AGENTS.md`（本文件）

---

## 3. Tech Stack

| 层 | 选型 | 备注 |
|---|---|---|
| 后端 | Java 1.8 + Spring Boot 2.7.18 + MyBatis-Plus 3.4.3.4 | **环境降级**（原始目标 Java 21 / SB 3.3.5 / MP 3.5+），详见 §4.1 |
| DB | **SQLite 3.45**（dev/prod 默认，v2.6.0 起）+ MySQL 8.0（可选 profile） | sqlite-jdbc 3.45.0.0 + mysql-connector-j（runtime） |
| 缓存 | Redis 7.x（apt 装系统服务 / docker run 都行） | JJWT 0.11.5 + Hutool 5.8.27 + Lombok |
| 前端 | Nuxt 3 ^3.13 + Tailwind ^3.4 + TS ^5.5 + Pinia | **v2.7.0 `ssr: false` + `nuxt generate` 全静态** |
| 工具 | Playwright + Chromium（设计稿审计、端到端） | — |

---

## 4. 关键决策

### 4.1 环境降级链（**不要升级**）
开发机只有 JDK 1.8。**不要试图升级** Java / Spring Boot / MyBatis-Plus / 包管理工具到"原始目标"版本——会破坏所有依赖。

### 4.2 字符编码（MySQL profile 仍需注意）
MySQL 导入必须加 `--default-character-set=utf8mb4`，否则中文双重编码。**SQLite 无此问题**（v2.6.0 起默认）。

### 4.3 admin-auth SSR 修复（BUG-001，**不要回退**）
**症状**：直接访问 `/admin/*`，已登录用户被踢回 `/admin/login`。
**根因**：`admin-auth.ts` 在 SSR 阶段调 `localStorage.getItem('token')`——服务端无 localStorage。
**修复**（v2.0.0）：
1. `admin-auth.ts` 头部加 `if (import.meta.server) return`
2. `layouts/admin.vue` 加 `onMounted` 兜底
**v2.7.0 后续清理**：全静态化后 `import.meta.server` 恒为 `false`，第 1 条已删；兜底保留。

### 4.4 简化项（首版 MVP 决策）
- Controller 直调 Mapper（无 Service 层）——**部分回退**：v2.0+ 已下沉 `DeviceService` / `ApiWhitelistService` / `SiteSettingsService` / `PageViewService`
- 仪表盘直查 DB
- 评论列表扁平（无 parent_id 树形）
- 单体单 DB
- 详细 changelog 见 `docs/changelogs/`（v2.0.0 → v2.7.0）

### 4.5 数据库双 profile（v2.6.0，**不要回退**）
dev/prod 默认 **SQLite**（一文件 0 内存占用）；MySQL 8.0 降级为可选 profile。
- 切回 MySQL：`spring.profiles.active=dev,mysql` 或 `prod,mysql`
- **SQLite 写并发（2026-06-18 起开 WAL）**：prod 用 `journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL`，Hikari `maximum-pool-size` 已由 1 放开到 **8**（WAL 下「多读+单写」可并发，`busy_timeout` 让偶发写竞争等待而非立刻 `SQLITE_BUSY`）。**未开 WAL 时不要把 pool-size 设 >1**
- 业务 SQL 跨方言已统一（38 处）——`PageViewService` / `ArticleController` / `DashboardController` 用 `LocalDate` / `LocalDateTime` 传参替代 MySQL 特有函数（`CURDATE()` / `DATE_SUB` / `NOW()` / `INSERT IGNORE` → 业务层去重）
- MyBatis-Plus 3.4.3.4 `IdType.AUTO` 自动适配 MySQL / SQLite，9 个 `@TableName` 实体各有 1 处 `@TableId(type = IdType.AUTO)`，合计 **9 处注解**（不要把 Service 类注释里出现的 `IdType.AUTO` 字符串误算成第 10 处注解）**不动**

### 4.6 前端全静态化（v2.7.0，**不要回退**）
`nuxt generate` 产出 `.output/public/`，nginx:alpine 直接 serve。
- 公开页 SEO 预渲染：`nitro.prerender.routes`（由 `scripts/fetch-routes.js` 拉后端所有公开页 slug 生成 `.routes.json`）+ `crawlLinks: true` + `failOnError: false`
- admin 路由不预渲染（`ignore: '/admin/**'` + `'/api/**'`）
- 新文章延迟：每日凌晨 3 点 cron `scripts/rebuild-static.sh` rebuild（构建 ~60s，吃 200-300MB 临时内存）
- `import.meta.server` 永远是 `false`（全静态化后），不要回退 `useAuth.ts` 的 SSR cookie 读取代码（v2.7.0 已删）
- 镜像：node 20-alpine build → nginx:alpine runtime（~50MB vs v2.6 之前的 ~200MB）

### 4.7 业务层去重：page_view（v2.5.0+，**不要改回 INSERT IGNORE**）
- `PageViewService` 用 `JdbcTemplate.update` + `selectCount` 做"先查后插"
- 不要用 MyBatis-Plus `BaseMapper.insert` ——SQLite 下 `getGeneratedKeys()` 失败会返回 1 但数据未落库（**假象**）
- `PageViewMapper` 没有 `@Insert` 注解，走通用 `JdbcTemplate.update` 路径

### 4.8 日志体系（REQ-LOG-2026-06-18，**已实现**）
- **门面**：SLF4J + Logback（Spring Boot 默认，无新依赖）；`@Slf4j` Lombok 注解
- **配置**：`backend/blog-app/src/main/resources/logback-spring.xml`（dev 落 `/tmp/blog-dev-logs/`，prod 落 `/opt/myblog/logs/`，按天滚动 100MB/30 天，主 2GB + warn 1GB = 3GB 上限，异步 `neverBlock`）。日志级别/路径全在此文件，**dev/prod yml 的 `logging.*` 已清空**避免冲突
- **文件名**：`blog.log` / `blog-YYYY-MM-DD.N.log`（全量）+ `blog-warn.log` / `blog-warn-YYYY-MM-DD.N.log`（WARN+），与 `deploy-server.sh` 的 logrotate glob 对齐
- **traceId**：每个请求由 `TraceIdFilter`（`com.blog.common.web`，`@Order(Ordered.HIGHEST_PRECEDENCE)`）注入 MDC，长度 32 字符（UUID 去横线），响应头 `X-Trace-Id` 回写；`IpRateLimitFilter` 已让位下调到 `HIGHEST_PRECEDENCE+1`
- **操作人传递**：`AdminAuthFilter` 放行时把 uid/deviceId 写入 request attribute（`com.blog.common.web.AuthContext`），admin 业务日志据此打"操作人"
- **必须覆盖的 4 个 P0 安全类**：`AdminAuthFilter`、`AuthController.login`、`DeviceService`、`JwtUtil` —— 任一拒绝点必须打 WARN，含 IP / path / 拒绝原因
- **禁打日志的字段**：明文 password、`passwordHash`、`token` 全文、JWT secret（合规硬线）
- **降噪规则**：`PageViewFilter` / `PageViewService` / `view_count` 自增 等高频路径**禁止** INFO 级（会爆磁盘）
- **prod console**：FR-6.5 强制 prod profile **必须关闭 CONSOLE appender**（避免 systemd 重定向的 app.log 与 Logback 文件双写，绕过 3GB 预算）
- **systemd 重定向文件**：`/opt/myblog/logs/app.log` + `app-error.log` 由 logrotate 单独管（按天切，保留 7 天）
- **完整需求**：见 `docs/design/服务日志体系设计.md`（原计划落 `docs/requirements/REQ-LOG-2026-06-18.md`，该路径未建文件，2026-06-18 复核确认统一收口到设计文档）

---

### 4.9 安全红线：禁止打印/提交的敏感字段（代码审查硬线）
- **日志禁打**：明文 password、passwordHash、token 全文、secret、key、private、apiKey、credential、JWT secret
- **禁止提交**：.env（含真实密钥）、*.pem、*.key、*.jks、keystore、credentials.json
- **代码审查检查点**：在 log.info/warn/error 和 System.out.println 的参数中搜索上述字段名
- **与 §4.8 的关系**：§4.8 日志体系包含禁打字段清单（基础设施层），本节的检查维度供代码质量审查员（Stage 1.5）和日常 code review 使用


## 5. 常用命令

```bash
# ============ Dev 默认（v2.6.0 起：SQLite）============
docker run -d --name blog-redis -p 6379:6379 redis:7-alpine
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm run dev
curl http://localhost:8080/api/v1/health
bash scripts/verify-sqlite.sh

# ============ Prod（v2.6.0/v2.7.0：无 docker）============
sudo DEPLOY_MODE=full bash /opt/myblog/scripts/deploy-server.sh v5.3.10
0 3 * * * bash /opt/myblog/scripts/rebuild-static.sh
sqlite3 /opt/myblog/blog.db ".backup /opt/myblog/backups/blog-$(date +%Y%m%d-%H%M%S).db"

# ============ Dev → Prod 加密数据迁移(2026-06-18 起)============
bash scripts/sqlite-export.sh --exclude page_view -o /tmp/migration.sql.gz.enc
ssh myblog@<ecs-ip> "sudo bash /opt/myblog/scripts/sqlite-import.sh /opt/myblog/db/blog.db /tmp/migration.sql.gz.enc"
EXPORT_DB=1 ./scripts/publish-release.sh
IMPORT_DB=1 sudo DEPLOY_MODE=full ./scripts/deploy-server.sh v5.3.10
```

---

## 6. API 风格

所有接口前缀 `/api/v1`，统一响应：
```json
{ "code": 200, "data": { ... }, "message": "ok" }
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

详见 `docs/接口契约审计报告.md`。

---

## 7. Code Style（极简）

- **包名** `com.blog.<module>.<feature>`；**类** PascalCase；**方法** camelCase；**常量** UPPER_SNAKE
- **Lombok** `@Data` / `@Builder` / `@Slf4j` 避免手写 getter/setter
- **前端** 文件 PascalCase（`NavBar.vue`）；composables `useXxx.ts`；`<script setup lang="ts">`；TS，避免 `any`
- **数据库** 表/字段 snake_case；主键 `id` BIGINT/MySQL / INTEGER SQLite；时间 `created_at` / `updated_at` DATETIME；逻辑删除 `deleted` TINYINT
- **profile 命名** `application-{env}.yml` + 可选 `application-{db}.yml` 片段（dev/prod/mysql 自由组合）

---

## 8. 用户偏好（**强制遵守**）

| 偏好 | 规则 |
|---|---|
| **8.1 需求跟踪文档 / 报告：先看后写** | 涉及 BUG/OPT/DEV 项时，**先在对话里展示完整内容**，等用户确认后再落盘 |
| **8.2 提示词优化：不落盘** | "优化提示词"任务**只在对话里输出**——不写文件（无论是否提示"输出为 MD"） |
| **8.3 过程文件：放项目目录** | 主动写的 draft / 临时分析 / 截图 / 比对资料一律写到**对应项目目录下**（docs/、scripts/、.workbuddy/、var/tmp/、screenshots/ 等），或只输出在对话里。**不写到 `~/`、`~/Desktop/`、`~/.mavis/` 等家目录**。mavis 系统自管文件（scratchpad / memory / session log）不受此约束 |
| **8.4 git commit 默认不自动** | 默认不自动 git commit —— 改完代码停留在工作区，等用户显式说"提交"才执行；触发词必须是用户原话 |
| **8.5 本地 docker 服务报错 → 查 `myblog-sim` 容器日志** | 用户说"本地 docker 服务报错"（含 traceId / 5xx / 接口异常等）时，**直接进 `myblog-sim` 容器查日志**：`docker logs myblog-sim 2>&1 \| grep <traceId>` + 容器内 `/opt/myblog/logs/blog.log` 配套；不要先查 dev 本地文件日志（`/tmp/blog-dev-logs/`）或 host 上其它路径——`myblog-sim` 才是本地 docker 部署的运行实例。仅本项目适用 |
| **8.6 打包需显式触发** | 处理完问题后**不要自动打包**，等用户显式说"打包"才执行 `publish-release.sh`；触发词必须是用户原话 |
| **8.7 先说方案再动手** | 遇到问题时，**先在对话里给出解决方案**（含根因分析 + 修复步骤），等用户确认后再执行代码修改/操作 |

跨项目 / 跨会话 / 跨场景适用。

---

## 9. 安全红线

- **不要在 commit / 文档 / 日志中暴露 secrets**（DB 密码、JWT secret、API key、密码 hash）
- **不要 SQL 字符串拼接**（用 MyBatis-Plus Wrapper 或 `@Select` 注解；page_view 走 `JdbcTemplate.update` 跨方言兼容）
- **不要在前端 store / localStorage 存明文密码**（只用 token）
- 默认 admin 账号 `admin / 123456`（dev 占位）——**生产环境必须改 + BCrypt 加密**

### 9.1 应用层防护策略（2026-06-07 决策：不引入应用层加密）

**决策**：不引入应用层加密（前端预 hash / 端到端 / 国密），只靠**传输层 HTTPS + 设备白名单**。

- **传输层**：HTTPS + HSTS（`nginx-https.conf` listen 443 + `Strict-Transport-Security: max-age=31536000`）
- **应用层**：`admin_device` 表 3 状态（pending/approved/revoked） + token 带 `deviceId` claim + `AdminAuthFilter` 每请求校验
  - 账号密码即使泄漏，**未授权设备**无法登录
  - 已签发 token 在**未授权设备**上**无法使用**（deviceId claim 不匹配）

**未来 agent 不要再提"加 L3 前端 hash / 端到端加密吧"**——这是有意识的安全模型选择，**L1 + L2 + 设备白名单**对个人博客场景已足够。详见 `docs/项目部署解决方案.md` 附录 ADR + `docs/项目部署操作手册.md` §2.5 §11。

**用户原话**（2026-06-07）："A. 不改了，我有设备授权，即使别人拿到账号密码也无法登录"。

---

## 10. 关键文件索引

| 路径 | 用途 | 优先级 |
|---|---|---|
| `README.md` | 项目门面（v4.0.0 同步刷新） | 🔴 必读 |
| `docs/design/博客系统设计方案.md` | 完整设计 v0.3（含 v2.6.0/v2.7.0/v4.0.0 变更记录） | 🔴 必读 |
| `docs/项目部署操作手册.md` | **部署操作手册**（v4.0.0+ 一步步怎么操作） | 🟠 重要 |
| `docs/项目部署解决方案.md` | **部署解决方案**（设计决策 / 成本预算 / ADR） | 🟠 重要 |
| `frontend/middleware/admin-auth.ts` | SSR-safe 鉴权（**不要回退 BUG-001 修复**） | 🔴 必读 |
| `frontend/layouts/admin.vue` | admin 布局 + 鉴权兜底 + 全局 Toast/Dialog 容器 | 🔴 必读 |
| `frontend/nuxt.config.ts` | v2.7.0 全静态 prerender 配置（routes / crawlLinks / failOnError / ignore） | 🔴 必读 |
| `backend/blog-app/src/main/resources/application-{dev,prod,mysql}.yml` | v2.6.0 拆 4 profile 矩阵（dev / dev,mysql / prod / prod,mysql） | 🔴 必读 |
| `scripts/rebuild-static.sh` | 每日 cron 重建前端静态文件 | 🔴 必读 |
| `scripts/verify-sqlite.sh` | 端到端 29 端点验证脚本 | 🟠 重要 |
| `scripts/sqlite-export.sh` | dev 加密导出 db（**只支持加密**，无明文兜底） | 🟠 重要 |
| `scripts/sqlite-import.sh` | prod 解密导入 db（**只支持 .enc**，错密码不碰目标 db） | 🟠 重要 |
| `scripts/blog-backup.sh` | 备份脚本（db+uploads 加密打包 → 推 GitHub Release） | 🟠 重要 |
| `scripts/migrate-logs.sh` | 历史日志迁移（v4.0.0~v4.3.0 旧日志归档到 archive/YYYY-MM/） | 🟡 可选 |
| `scripts/sudoers-myblog-restore.example` | sudoers 白名单（5 条精确命令，**无通配符**） | 🔴 必读 |
| `docs/design/博客数据恢复方案设计.md` | 恢复功能设计稿（v5 设计稿，5 轮迭代） | 🟠 重要 |
| `scripts/publish-release.sh` | 本地打包 + 发布到 GitHub Release（`EXPORT_DB=1` 钩子） | 🟠 重要 |
| `scripts/deploy-server.sh` | 服务器端一键部署（v4.4.0：4 种 DEPLOY_MODE = `init` / `full` / `docker-create` / `docker-init`，外置开关 `IMPORT_DB=1` 灌数据；v5.3.1：下载用 `releases/latest/download/`，执行时传版本号） | 🟠 重要 |
| `scripts/upgrade-agent.py` | Python 升级代理（v5.3.0，监听 127.0.0.1:28081，SSE 流式日志） | 🟠 重要 |
| `scripts/upgrade-agent.service` | upgrade-agent systemd 服务文件 | 🟡 可选 |
| `backend/blog-app/.../upgrade/UpgradeController.java` | 升级控制器（5 端点：升级/状态/版本/历史/回滚） | 🟠 重要 |
| `docs/design/系统升级方案设计.md` | 系统升级方案设计文档 | 🟠 重要 |
| `docs/生产升级问题记录.md` | 生产环境升级问题记录（9 个问题及解决方案） | 🟠 重要 |
| `docs/changelogs/` | 版本变更记录（v2.0.0 → v2.7.0） | 🟠 重要 |
| `docs/接口契约审计报告.md` | API 100% 一致 | 🟠 重要 |
| `AGENTS.md` | **本文件** | 🔴 必读 |

---

## 11. 备注

- 之前版本的"常见任务 / 踩坑记录 / 调试技巧 / 维护记录"已删除（内容散落 `docs/changelogs/` + 各文件注释里）
- agent 启动建议顺序：1) 读本文件 → 2) **`docs/changelogs/` 最新两版**（v4.0.0 + 上一版，理解当前架构） → 3) 关键文件索引中的 🔴 必读项 → 4) 接到任务时再按需 Read
- v4.0.0 / v2.7.0 / v2.6.0 是**架构大变更**（日志体系 + IP 限流封禁 + 加密数据迁移 + 全静态化 + SQLite 改造），接到新任务前务必先读这几个 changelog

---

## 12. 版本号管理规范（2026-06-18 v4.0.0 起强制）

### 12.1 权威源
- **Maven `<revision>` 是项目唯一权威版本号**（`backend/pom.xml` line 32）
- 当前 `<revision>` = **5.0.0**
- Git tag / 部署脚本 / 文档里的所有版本号必须与 `<revision>` **同步**（按 §12.3 工作流）

### 12.2 版本号引用分类（决定改 vs 不改）

**A 类 — 必须随 `<revision>` 同步改的"活跃引用"**：

| 文件 | 位置 | 说明 |
|---|---|---|
| `backend/pom.xml` | line 32 `<revision>` | 🔴 唯一入口，改这个其他全跟着同步 |
| `scripts/deploy-server.sh` | 头部注释（`DEPLOY_MODE` 取值说明 + 版本号引用）/ Usage 段 / rollback 提示 | 注释里的 `vX.Y.Z` + 错误提示（具体行号随脚本迭代变化，以 `grep -n vX.Y.Z scripts/deploy-server.sh` 实际查找为准）|
| `scripts/publish-release.sh` | line 279（README 模板里的 deploy 例子） | GitHub Release README 解锁用的初始内容 |

**B 类 — 绝对不改的"历史引用"**（破坏它就破坏历史追溯）：
- 历史 changelog（`docs/changelogs/*.md` 所有文件）
- 历史设计文档（`docs/design/博客系统设计方案.md` / `docs/项目部署解决方案.md` §十六实施记录）
- AGENTS.md / README.md 里的"历史描述"段（§3 Tech Stack / §4 关键决策 / §10 关键文件索引里所有 `v2.x` 引用）
- 历史升级指南（`scripts/upgrade-guide.md` 里所有 `v2.x` 引用）
- 代码注释里的"vX.Y.Z 加的"标注（`frontend/middleware/admin-auth.ts` / `frontend/composables/*.ts` / `frontend/nuxt.config.ts` / `backend/**/application*.yml`）

**C 类 — 每次版本变更新建**：
- `docs/changelogs/YYYY-MM-DD-vX.Y.Z-{slug}.md`（命名按既有风格：`v2.6.0-sqlite-migration` / `v2.7.0-nuxt-static`）

### 12.3 改版本号的工作流

1. 改 `backend/pom.xml` `<revision>` ← 唯一入口
2. 全仓 grep `v<旧版本号>` 找 A 类引用 → 同步改成 `v<新版本号>`
3. B 类历史引用一律不动（grep 时排除 `docs/changelogs/` `docs/requirements/` `docs/项目部署解决方案.md` §十六实施记录段）
4. 新建 `docs/changelogs/YYYY-MM-DD-vX.Y.Z-{slug}.md` 写变更摘要（背景 / 新增 / 改动 / 升级回滚说明）
5. AGENTS.md §3 / §4 / §10 索引如有相关条目 → 仅在"agent 启动建议顺序"那行加新版本号
6. 测试：`bash scripts/verify-sqlite.sh` 全过 + 部署脚本能跑

### 12.4 禁止

- ❌ 在 B 类位置把 v2.7.0 改成 v4.0.0（破坏历史追溯）
- ❌ 用 Maven `${revision}` 以外的来源定义版本号（避免双系统漂移）
- ❌ 改 `<revision>` 不改 deploy-server.sh 注释（注释误导运维）
- ❌ 跳过新建 changelog（破坏 §12.2 C 类规则）

### 12.5 兼容原则

- 历史 changelog 文件名 / 内容 / commit 全部**只读**，即便后续修正也要新建 changelog 引用旧 changelog
- "上次是什么版本"问题永远以 `<revision>` 为准，B 类历史引用只回答"当时是什么时候"

<!-- autoclaw:hermes-evolution-guidance -->
## Hermes-Evolution

**Current evolution intensity for this workspace/agent: off (0%).**

The desktop app sends deterministic evolution-check messages (starting with `[SYSTEM: Post-turn evolution check`) after qualifying turns.
When you receive such a message, follow the `hermes-evolution` skill instructions to evaluate and potentially propose an evolution.
Apply the rules defined in the skill according to the **off (0%)** intensity level.
This value is workspace-local. If asked about the current agent evolution intensity, report this value instead of the global gateway skill env.

Core principle: **never write to target files without user approval** — always use the draft/approve workflow.
User preference statements are not approval to directly edit MEMORY.md, AGENTS.md, TOOLS.md, USER.md, or managed SKILL.md files.
Use the evolution proposal card instead of editing target files directly; only apply changes after the user confirms the proposal.

### Evolution Echo
When you apply knowledge from a previously evolved rule (AGENTS.md, MEMORY.md, TOOLS.md, or a managed SKILL.md),
briefly mention it in your response: "（基于之前的经验：<one-line rule summary>）".
Keep it to one short line at most. Do not echo on every turn — only when an evolved rule directly influenced your approach.
<!-- /autoclaw:hermes-evolution-guidance -->