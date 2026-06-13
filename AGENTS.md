# AGENTS.md

> my-blog 项目的 agent 上下文，重量版（~500 行）。
> 供 OpenCode / Codex / Cursor / Aider / Devin / Gemini CLI 等 agent 启动时自动加载。
> 消费规范：https://agents.md

---

## 0. TL;DR

- **5 秒速览**：Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离，13 个页面（5 公开 + 8 admin），JWT 鉴权，admin-auth middleware SSR 阶段读不到 localStorage 已修
- **3 关键文件**：`frontend/middleware/admin-auth.ts`（SSR-safe）、`frontend/layouts/admin.vue`（鉴权兜底）、`README.md`（项目门面）
- **3 关键命令**：`docker run` 启 MySQL+Redis → `cd backend && mvn spring-boot:run` → `cd frontend && npm run dev`
- **配色契约**：墨绿 `#2f6f5e` + 焦糖橙 `#c97b3f`（`frontend/assets/css/main.css`）

---

## 1. 项目基本信息

- **项目**：vibeP/my-blog
- **类型**：个人博客 MVP（v2.0.0，2026-06-07）
- **架构**：Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离
- **数据库**：MySQL 8.0 + Redis 7.x
- **鉴权**：JWT (JJWT 0.11.5)
- **API 前缀**：`/api/v1`
- **包名前缀**：`com.blog.*`

---

## 2. 项目结构

- `backend/` — Spring Boot 多模块
  - `blog-common/` — 公共组件（统一响应、异常、分页）
  - `blog-auth/` — 用户、登录、JWT 鉴权
  - `blog-article/` — 文章、分类、标签
  - `blog-comment/` — 评论
  - `blog-settings/` — 设置、上传
  - `blog-app/` — 启动模块（聚合 + 启动类 + 配置）
- `frontend/` — Nuxt 3
  - `pages/` — 13 个页面（5 公开 + 8 admin）
  - `layouts/admin.vue` — **admin 布局 + 鉴权兜底（重要）**
  - `middleware/admin-auth.ts` — **SSR-safe 鉴权（重要）**
  - `composables/useApi.ts` / `useAuth.ts` / `useAdminMeta.ts` — 核心 composables
  - `assets/css/main.css` — 配色契约（墨绿 `#2f6f5e` + 焦糖橙 `#c97b3f`）
  - `components/` / `plugins/` / `nuxt.config.ts`
- `docs/` — 设计/审计/部署/SQL
  - `设计文档/` — 设计方案（`博客系统设计方案.md` / `防重放攻击方案设计.md`）
  - `接口契约审计报告.md` — API 100% 一致
  - `阿里云部署方案.md` — 阿里云部署
  - `docker/` — 生产 docker-compose
  - `nginx/` — 反向代理配置
  - `sql/` — DB 初始化 + 迁移（`blog.sql` / `migrations/`）
- `AGENTS.md` — **本文件**
- `README.md` — 项目门面

---

## 3. Tech Stack 详情

### 3.1 后端
- Java 1.8（**因环境降级**，原始目标 21）
- Spring Boot 2.7.18（**因 Java 1.8 降级**，原始目标 3.3.5）
- MyBatis-Plus 3.4.3.4（**因 MyBatis-Plus 3.5+ 要 JDK 17 降级**）
- Maven 3.6+
- MySQL 8.0（utf8mb4 字符集）
- Redis 7.x
- JJWT 0.11.5
- Hutool 5.8.27 + Lombok 1.18.x
- springdoc-openapi-ui 1.7.0（替代 Knife4j）

### 3.2 前端
- Nuxt 3 ^3.13
- npm（**因无 pnpm 改用**，原始目标 pnpm）
- Tailwind CSS ^3.4
- TypeScript ^5.5
- Pinia（按需引入）

### 3.3 工具
- Python 3.8+ + Playwright（设计稿审计）
- Chromium（headless）

---

## 4. 关键决策

### 4.1 环境降级链
**不要试图升级** Java / Spring Boot / MyBatis-Plus / 包管理工具到"原始目标"版本——开发机环境只有 JDK 1.8，强行升级会破坏所有依赖。详见 README §六.1。

### 4.2 字符编码：双重 UTF-8 修复
**症状**：`GET /api/v1/articles/categories` 返回 `"name":"æŠ€æœ¯"`（API 端乱码，但 mysql shell 看是对的）。

**根因**：`docker exec mysql ... < blog.sql` 默认走 latin1，SQL 里的中文被双重编码写入 utf8mb4。

**修复 SQL**（不删表，可逆）：

```sql
UPDATE category SET name = CONVERT(BINARY CONVERT(name USING latin1) USING utf8mb4)
  WHERE HEX(name) REGEXP '^C3A6|^C3A7|^C3A9|^C3A8|^C3A5|^C2|^E2';
```

**后续导入必须**：

```bash
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < blog.sql
```

详见 README §六.3。

### 4.3 admin-auth SSR 修复（BUG-001）
**症状**：直接访问 `/admin/*` 时，已登录用户被踢回 `/admin/login`。

**根因**：`frontend/middleware/admin-auth.ts` 在 SSR 阶段（`import.meta.server`）调用 `localStorage.getItem('token')`，但服务端无 localStorage。

**修复**：

1. `frontend/middleware/admin-auth.ts` 头部加 `if (import.meta.server) return`
2. `frontend/layouts/admin.vue` 加 `onMounted` 兜底，客户端 `initAuth()` 后判断未登录再 `router.replace('/admin/login')`

**不要回退这个修复**——是合理的 SSR-safe 模式。详见 README §六.4。

### 4.4 简化项（首版 MVP 决策）
- Controller 直调 Mapper（无 Service 层）— 业务复杂时再引入
- 密码明文比对（无 BCrypt）— 后续用 `org.springframework.security:crypto`
- 仪表盘直查 DB — 后续定时任务 + Redis 聚合
- 评论列表扁平 — 后续树形结构（`parent_id` 递归）
- 单体单 DB — 数据量大时按业务分库
- ~~无 Redis 缓存 — 首页/文章/分类/标签后续加~~  → **2026-06-08 部分落地**：site_settings service 走 Redis 5min 缓存（但本机 homebrew native redis 抢 6379 端口导致连不上，**降级到直读 DB 正常运行**——见 §12.6）

详见 README §六.2。

---

## 5. 常用命令

### 5.1 一键启动

```bash
# 1. 启动 MySQL + Redis
docker run -d --name blog-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=blog \
  -p 3306:3306 \
  -v blog-mysql-data:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

docker run -d --name blog-redis -p 6379:6379 redis:7-alpine

# 2. 导入数据（**必须带 --default-character-set=utf8mb4**）
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/blog.sql

# 3. 启动后端
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 4. 启动前端
cd frontend && npm install && npm run dev
```

### 5.2 健康检查

```bash
curl http://localhost:8080/api/v1/health
# 期望：{"code":200,"data":{"status":"UP",...}}
```

### 5.3 一键停止

```bash
docker stop blog-mysql blog-redis
# backend/ 和 frontend/ 目录分别 Ctrl+C
```

---

## 6. API 风格

所有接口前缀 `/api/v1`，统一响应格式：

```json
{
  "code": 200,
  "data": { ... },
  "message": "ok"
}
```

错误响应：

```json
{
  "code": 4xx/5xx,
  "message": "..."
}
```

业务码规范：

- 200 — 成功
- 400 — 参数错误
- 401 — 未登录
- 403 — 无权限
- 404 — 资源不存在
- 500 — 服务器错误

详见 `docs/接口契约审计报告.md`（19/19 字段 100% 一致）。

---

## 7. Code Style

### 7.1 后端
- 包名：`com.blog.<module>.<feature>`，如 `com.blog.article.controller`
- 类名：PascalCase（`ArticleController`）
- 方法名：camelCase（`getArticleList`）
- 常量：UPPER_SNAKE_CASE（`MAX_PAGE_SIZE`）
- Lombok：`@Data` / `@Builder` / `@Slf4j`，避免手写 getter/setter
- MyBatis-Plus：Service 用 `IService<T>`，Mapper 用 `BaseMapper<T>`
- 注释：Javadoc 风格，关键类加 `@author` + `@since`

### 7.2 前端
- 文件名：PascalCase（`NavBar.vue`）
- composables：`useXxx.ts`，命名空间 `useXxx`
- script setup：`<script setup lang="ts">`
- 状态：Pinia（按需引入）
- 类型：TypeScript，避免 `any`
- 注释：关键逻辑加 `// NOTE:` / `// TODO:` / `// FIXME:`

### 7.3 数据库
- 表名：snake_case（`article` / `comment` / `tag`）
- 字段：snake_case（`user_id` / `created_at`）
- 主键：`id`（BIGINT AUTO_INCREMENT）
- 时间戳：`created_at` / `updated_at`（DATETIME）
- 逻辑删除：`deleted`（TINYINT，默认 0）

---

## 8. 用户偏好（**强制遵守**）

### 8.1 需求跟踪文档 / 报告：先看后写
- 用户要求生成"需求/任务跟踪文档"（含 BUG/OPT/DEV 项）时，agent **先在对话里展示完整内容**，等用户确认后再写入文件
- 不要主动直接落盘
- 跨项目/会话/场景适用

### 8.2 提示词优化：不落盘
- 用户要求"优化提示词"时，agent **只在对话里输出优化结果**
- 不要主动落盘到任何文件（无论是否提示"输出为MD文档" / "保存到 X 路径"等）
- 跨项目/跨场景适用

### 8.3 过程文件：放项目目录
- agent 主动写的过程文件（draft / 临时分析 / 报告草稿 / 截图 / 比对资料 / 任何非系统管理的产物）一律写到**对应的项目目录下**（项目根下的 docs/、scripts/、.audit/、tmp/、screenshots/ 等子目录），或只输出在对话里
- **不要写到用户家目录 `/Users/yuanyi/` 下任何位置**（包括但不限于 `~/.mavis/...`、`~/Desktop/`、`~/Downloads/`、`~/Documents/`，家目录根直接散落的 png/txt/md/csv 等都算违规）
- agent context 给了 `YOUR WORKSPACE DIRECTORY` 时直接写到那里；**没给或不确定时，先在对话里跟用户确认项目目录位置再写**——避免再次发生"前任 session 漏写到 home 根"的事
- mavis 系统自动管理的 handoff / scratchpad / session log / memory append 仍由系统机制驱动，不算"过程文件"
- 跨项目/跨会话/跨场景适用

---

## 9. 安全红线

- **不要在 commit / 文档 / 日志中暴露任何 secrets**（数据库密码、JWT secret、API key、用户密码 hash）
- **不要把 secrets 写到 process env / docker env**（生产用 secret manager）
- **不要在 controller / service / model 里写 SQL 字符串拼接**（用 MyBatis-Plus Wrapper 或 `@Select` 注解）
- **不要在前端 store / localStorage 里存明文密码**（只用 token）
- **不要在 commit message 里包含敏感信息**（token、密码、邮箱）
- 默认 admin 账号是 `admin / 123456`（开发环境占位），**生产环境必须改 + BCrypt 加密**（详见 §4.4）

### 9.1 应用层防护策略（2026-06-07 决策：不引入应用层加密）

**决策**：本项目**不引入**应用层加密（前端预 hash / 端到端 / 国密），只靠**传输层 HTTPS + 设备白名单**做防护。

- **传输层**：HTTPS + HSTS 已配（`nginx-https.conf` listen 443 + `Strict-Transport-Security: max-age=31536000`）—— 链路加密
- **应用层**：靠**设备白名单**做防护 —— `admin_device` 表 3 状态（pending/approved/revoked）+ token 带 `deviceId` claim + `AdminAuthFilter` 每请求校验
  - 账号密码即使泄漏，**未授权设备**无法登录
  - 已签发 token 在**未授权设备**上**无法使用**（deviceId claim 不匹配）
- **不引入前端预 hash / 国密 / 端到端**的原因：单台 ECS 2C2G 个人博客场景，攻击者拿到 SSH 等于全盘被拿，应用层加密的收益边际很小

**未来 agent 不要再提"加 L3 前端 hash / 端到端加密吧"**——这个决策是有意识的安全模型选择，**L1 + L2 + 设备白名单**对个人博客场景已足够。详见 `docs/阿里云部署方案.md`。

**用户原话**（2026-06-07）："A. 不改了，我有设备授权，即使别人拿到账号密码也无法登录"。

---

## 10. Test 约定

### 10.1 后端
- 当前**无单测**（首版 MVP 简化）
- API 测试通过 Swagger UI 手动验证
- 后续接入：JUnit 5 + Mockito + Testcontainers（MySQL/Redis）

### 10.2 前端
- 当前**无单测**
- 后续接入：Vitest（unit）+ Playwright（E2E）

### 10.3 端到端
- 当前**无端到端测试报告**
- API 契约审计见 `docs/接口契约审计报告.md`

---

## 11. 常见任务

### 11.1 新增 admin 页面
1. 在 `frontend/pages/admin/<name>.vue` 建文件
2. 在 `frontend/layouts/admin.vue` 的 `navGroups` 数组加导航项
3. （如有）后端在对应 module 加 `Controller` / `Mapper` / `Entity` + DTO
4. 在 `docs/接口契约审计报告.md` 同步更新（如有新增端点）

### 11.2 新增 API
1. 后端：在对应 module 加 `Controller` 方法（统一返回 `Result<T>`）
2. 前端：在 `composables/useApi.ts` 加请求方法
3. 更新 `docs/接口契约审计报告.md`（19 字段对齐）
4. Swagger UI 手动验证

### 11.3 修 bug
1. **复现 + 写最小测试 case**（手动 / 自动化）
2. **根因分析**（不要瞎改）
3. **修复 + 加 changelog** 到 `docs/`
4. **回归测试**（手动 + 文档更新）

### 11.4 改首页模板（hero / bento / section）（2026-06-08 教训）
**禁区**：
- **hero 区是"博主个人介绍"视觉单元**（Y avatar + "嗨，我是 {{nickname}}" + role + bio）—— **不要在 hero 顶部加第二个 H1/title**（哪怕是站点信息），会出现两个 32px H1 撞车
- 站点信息（blog.title / subtitle）展示位置：① footer copyright ② 顶栏 brand（不在 hero）

**改完模板自检**：
- 用 Playwright 截全图
- Playwright `document.querySelectorAll('h1, h2')` 列元素 + fontSize —— 同一个视觉单元内不应有 2 个 H1 同字号
- 视觉对比：截图 + hero-text 区域 innerText 顺序检查

---

## 12. 踩坑记录

### 12.1 双重 UTF-8 编码
见 §4.2。

### 12.2 admin-auth SSR
见 §4.3。

### 12.3 截图写到 home 根目录
**症状**：前任 session 写截图时没改 workdir，直接落到 `~/` 散落了 11 个 png。
**修复**：清掉 + 写新流程——所有截图统一放"对应项目目录"（见 §8.3）。
**预防**：见 §8.3 过程文件落盘位置。

### 12.4 admin-dashboard 趋势图 mock
**症状**：design 有 PV + UV 双线，actual 只有 PV。
**原因**：当前无访问日志表，趋势图是 mock。
**后续**：建 `page_view` 表 + 定时任务聚合昨日数据。

### 12.6 mvn spring-boot:run 用 jar 不是 target/classes（2026-06-08）
**症状**：新加的 controller / service 编译 + install 后，swagger / 端点还是看不到，但同模块老类能注册。
**根因**：`mvn spring-boot:run` 的 classpath 是 `target/classes` + 依赖 jar，**模块依赖走 jar（`~/.m2/repository/com/blog/blog-xxx-0.1.0.jar`）**。只跑 `mvn compile` 不更新 jar → 新加的类**没进 jar** → 启动时类找不到。
**修复**：每次改完模块 → `mvn -pl blog-xxx -am install -DskipTests` 把 class 打进 jar。
**教训**：dev 时改完一个模块 → install 一次（`mvn install`），不要只 `compile`。

### 12.7 本机 native redis 抢 docker 6379（2026-06-08）
**症状**：Spring Boot 连 localhost:6379 报 `Unable to connect to localhost:6379`，但 `docker exec blog-redis redis-cli` 能 PONG。
**根因**：本机 homebrew 装的 redis-server（`/opt/homebrew/opt/redis/bin/redis-server 127.0.0.1:6379`，PID 929）+ 启用了密码（NOAUTH），跟 docker 容器 redis 抢同一个端口。
**影响**：SiteSettingsService Redis 缓存写入失败 → 降级到直读 DB（业务正常，只是不走缓存）。
**修复选项**（需用户确认）：
- 杀 native redis：`brew services stop redis` 或 `kill 929`
- 改 docker 容器端口到 6380 + application-dev.yml 改 host:port
- 配 application-dev.yml 加 native redis 的 password
**当前状态**：未处理，业务降级运行。

### 12.8 docker exec pipe 中文双重编码（2026-06-08 发现）
**症状**：`docker exec -i blog-mysql mysql ... < file.sql` 写的中文变 `????`（包括 api_whitelist description 之前所有 SQL seed 都受影响）。
**根因**：shell pipe + docker exec 把 UTF-8 数据按 latin1 处理。
**修复**：用 Python `pymysql` 直连写（不走 docker exec pipe），或写英文 placeholder + Java 启动时 init 中文。
**教训**：写含中文 SQL 数据 → **别用 docker exec pipe** → 用 pymysql / mysql 命令行 `-e` 配合 `--default-character-set=utf8mb4` + 二次确认。

---

## 13. 调试技巧

### 13.1 后端
- 日志：`backend/blog-app/src/main/resources/application-dev.yml` 配 `logging.level.com.blog=DEBUG`
- 端口冲突：`lsof -i :8080` 查占用
- DB 连不上：检查 `docker ps` 状态 + `application-dev.yml` 的 url/username/password
- Swagger：[http://localhost:8080/api/v1/swagger-ui.html](http://localhost:8080/api/v1/swagger-ui.html)

### 13.2 前端
- HMR：`npm run dev` 自动热更新
- Vue Devtools 浮窗（12ms / 13ms / ⚙）— dev 模式产物
- API 连不上：检查 `nuxt.config.ts` 的 `runtimeConfig.public.apiBase` + 后端 CORS
- admin 跳 login：检查 BUG-001 修复（§4.3）

### 13.3 截图工具
- 慢/超时：检查 frontend / backend 两个服务都跑着
- 鉴权失败：检查 `frontend/composables/useAuth.ts` + `frontend/middleware/admin-auth.ts`
- 截图：用 Playwright MCP，viewport 默认 1440x900

---

## 14. 关键文件索引（agent 必读）

| 路径 | 用途 | 优先级 |
|---|---|---|
| `README.md` | 项目门面 | 🔴 必读 |
| `docs/设计文档/博客系统设计方案.md` | 完整设计 v0.3 | 🔴 必读 |
| `docs/接口契约审计报告.md` | API 契约审计 | 🟠 重要 |
| `docs/阿里云部署方案.md` | 部署方案 | 🟠 重要 |
| `frontend/middleware/admin-auth.ts` | SSR-safe 鉴权（**不要回退 BUG-001 修复**） | 🔴 必读 |
| `frontend/layouts/admin.vue` | admin 布局 + 鉴权兜底 | 🔴 必读 |
| `frontend/composables/useAuth.ts` | 鉴权 composable | 🟠 重要 |
| `backend/blog-settings/.../entity/SiteSettings.java` | 站点设置 DB 实体（v2.1） | 🟠 重要 |
| `backend/blog-settings/.../service/SiteSettingsService.java` | settings 业务 + Redis 5min 缓存 | 🟠 重要 |
| `backend/blog-settings/.../controller/SettingsController.java` | admin 读写（5 tab + profile） | 🟠 重要 |
| `backend/blog-settings/.../controller/PublicSettingsController.java` | 公开读（v2.1） | 🟠 重要 |
| `backend/blog-app/src/main/resources/application-dev.yml` | dev 配置 | 🟡 知道 |
| `docs/sql/blog.sql` | DB 初始化脚本 | 🟡 知道 |
| `docs/sql/migration-site-settings.sql` | site_settings 迁移（v2.1） | 🟡 知道 |
| `AGENTS.md` | **本文件** | 🔴 必读 |

---

## 15. 维护记录

- **2026-06-08 v2.1.0** — site_settings DB 持久化 + 公开读端点 + Redis 缓存（降级运行）
  - **新表** `site_settings`（5 section：blog/social/preferences/theme/advanced）
  - **新模块** `com.blog.settings.{entity,mapper,service,controller.PublicSettingsController}`
  - **新接口** `GET /api/v1/public/settings/{section}`（公开读，匿名也能看）
  - **改写** `SettingsController` 5 个 tab（blog/social/preferences/theme/advanced）走 SiteSettingsService
  - **前端** `pages/index.vue` 加 `home-blog` useAsyncData + hero 顶部站点信息 + footer copyright
  - **缓存** Redis 5min TTL（降级运行：native redis 抢 6379 + 密码冲突，**详见 §12.7**）
  - **踩坑** §12.6 mvn install 缺 / §12.7 native redis 抢端口 / §12.8 docker exec pipe 中文
  - 触发：用户报告"改个人资料/站点信息不同步到前台首页" → 排查 → 根因（内存 Map + 前台没拉 blog）

- **2026-06-07 v2.0.0** — 项目级 agent 上下文初建（重量版）
  - 覆盖：项目结构 / Tech stack / 关键决策 / 用户偏好 / 安全红线 / Test 约定 / 常见任务 / 踩坑 / 调试技巧 / 关键文件索引
  - 触发：v2.0.0 全面文档更新 + 设计稿审计完成
