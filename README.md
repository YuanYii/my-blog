# 个人博客系统

> **v2.7.0** — Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离的个人博客 MVP。
> 当前状态：v2.6.0 dev/prod 默认 SQLite（一文件 0 内存占用，MySQL 降级为可选 profile） + v2.7.0 前端全静态化（`nuxt generate` + nginx serve，省 150-250MB 内存）+ 公开页 SEO 预渲染 + 完整部署/迁移/验证脚本就绪。

---

## 一、项目介绍

### 1.1 是什么

一个前后端分离的个人博客系统，包含**公开前台**（文章浏览/归档/标签/关于）和**管理后台**（仪表盘/文章管理/评论审核/分类标签/个人设置/设备管理）。**面向单机 1C2G 低配服务器优化**——SQLite 替代 MySQL、前端静态化替代 SSR Node，生产部署无 docker。

### 1.2 核心功能

| 模块 | 功能 |
|------|------|
| **前台公开** | 首页（Hero 个人介绍 + 文章列表）/ 文章详情 / 归档 / 标签云 / 关于页（**SEO 预渲染**） |
| **管理后台** | 仪表盘（KPI 聚合 + 30 天趋势）/ 文章增删改 / 评论审核 / 分类&标签管理 / 7-tab 站点设置 / 设备白名单管理 |
| **系统** | JWT 鉴权 / 设备白名单（X-Device-Id 绑定 token）/ API 路由白名单（DB 驱动，最长前缀匹配）/ 文件上传（本地存储）/ 站点设置 5 section（blog/social/preferences/theme/advanced） / Swagger API 文档 / 全静态前端 |

### 1.3 版本记录

- **v2.7.0**（2026-06-17）— 前端改全静态（`nuxt generate` + nginx serve），1C2G 省 150-250MB 内存 + 公开页 SEO 预渲染 + 每日凌晨 3 点 cron `rebuild-static.sh` rebuild
- **v2.6.0**（2026-06-17）— dev/prod 默认改 SQLite（一文件 0 内存），MySQL 降级可选 profile（`spring.profiles.active=*,mysql`）+ 业务 SQL 跨方言统一（38 处）+ 数据迁移工具（MySQL → SQLite，123/123 行导入）+ 端到端 29 端点验证脚本
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
├── scripts/                           # 部署 / 验证 / 迁移 / rebuild 脚本
│   ├── deploy-sqlite.sh              # 1C2G 无 docker 一键部署
│   ├── rebuild-static.sh             # 每日 cron 重建前端静态文件
│   ├── verify-sqlite.sh              # 端到端 29 端点验证
│   ├── migrate-mysql-to-sqlite-direct.py  # MySQL → SQLite 数据迁移
│   ├── dev-frontend.sh / restart-*.sh     # 本地开发辅助
│   └── ...
├── docs/                              # 设计文档与审计报告
│   ├── sql/                           # 数据库脚本（v2.6.0 整合后只剩 2 个 schema）
│   │   ├── schema-mysql.sql           # MySQL 完整 schema + seed data（11 张表）
│   │   └── schema-sqlite.sql          # SQLite 完整 schema + seed data（dev/prod 默认）
│   ├── docker/                        # 历史 docker-compose（v2.5 之前用，v2.6+ 不再推荐）
│   ├── nginx/                         # Nginx 反向代理配置（nginx.conf / nginx-https.conf）
│   ├── 设计文档/                      # 设计方案
│   │   ├── 博客系统设计方案.md
│   │   └── 防重放攻击方案设计.md
│   ├── changelogs/                    # 版本变更记录（v2.0.0 → v2.7.0）
│   ├── 接口契约审计报告.md            # API 契约 100% 一致性审计
│   └── 阿里云部署方案.md              # 生产部署方案（阿里云 ECS 1C2G，无 docker）
├── AGENTS.md                          # 项目级 agent 上下文（v2.7.0 同步）
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
│       ├── ResultCode.java           # 业务码枚举（200/400/401/403/404/500）
│       ├── BusinessException.java    # 业务异常
│       ├── GlobalExceptionHandler.java # 全局异常处理
│       ├── PageRequest.java          # 分页请求参数
│       └── PageResult.java           # 分页响应结构
│
├── blog-auth/                         # 用户认证 & JWT
│   └── src/main/java/com/blog/auth/
│       ├── controller/
│       │   ├── AuthController.java        # 登录 / 个人信息
│       │   ├── DeviceController.java      # admin 设备管理（列表/审批/吊销）
│       │   └── PublicDeviceController.java # 公开设备状态查询 /public/device/check
│       ├── entity/
│       │   ├── User.java              # 用户实体（BCrypt 密码）
│       │   ├── AdminDevice.java      # 设备白名单（pending/approved/revoked）
│       │   └── ApiWhitelist.java     # API 路由白名单（path_prefix/type）
│       ├── mapper/
│       │   ├── UserMapper.java       # MyBatis-Plus BaseMapper
│       │   ├── AdminDeviceMapper.java
│       │   └── ApiWhitelistMapper.java
│       ├── service/
│       │   ├── DeviceService.java     # 设备注册/审批/校验逻辑
│       │   └── ApiWhitelistService.java # 白名单最长前缀匹配 + 缓存刷新
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
│       │   └── PageViewService.java   # 业务层去重（X-Visitor-Id + UK 双重去重）
│       └── security/
│           └── PageViewFilter.java    # 公开页自动写 page_view
│
├── blog-comment/                       # 评论
│   └── src/main/java/com/blog/comment/
│       ├── controller/
│       │   └── CommentController.java # 公开列表 + 审核 + admin 列表
│       └── entity/
│           └── Comment.java
│
├── blog-settings/                      # 站点设置 & 文件上传
│   └── src/main/java/com/blog/settings/
│       ├── controller/
│       │   ├── SettingsController.java           # admin 读写（7 tab）+ 文件上传
│       │   ├── PublicSettingsController.java    # 公开读端点 /public/settings/{section}（匿名可看）
│       │   └── PublicProfileController.java     # 公开个人资料 /public/profile（前台关于页用）
│       ├── entity/
│       │   └── SiteSettings.java                 # 站点设置（5 section）
│       ├── mapper/
│       │   └── SiteSettingsMapper.java
│       └── service/
│           └── SiteSettingsService.java          # 业务逻辑 + Redis 5min 缓存
│
└── blog-app/ # 启动模块（唯一可执行的 Spring Boot）
    └── src/main/
        ├── java/com/blog/
        │   ├── BlogApplication.java # 启动类
        │   ├── config/
        │   │   ├── CorsConfig.java     # CORS 跨域（读 yml 环境变量）
        │   │   ├── MybatisPlusConfig.java
        │   │   └── OpenApiConfig.java   # Swagger
        │   ├── security/
        │   │   └── AdminAuthFilter.java # JWT 每请求鉴权 + 设备白名单校验
        │   └── controller/
        │       ├── HelloController.java
        │       ├── DashboardController.java # 仪表盘聚合（KPI + 30 天趋势 + 热门）
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
│   ├── useApi.ts                      # Axios 封装（统一拦截/错误处理）
│   ├── useAuth.ts                     # 鉴权状态（login/logout/me，v2.7.0 已删 SSR cookie 读取）
│   ├── useAdminApi.ts                 # admin API 封装
│   ├── usePublicApi.ts                # 公开 API 封装（自动加 X-Visitor-Id）
│   ├── useDialog.ts / useToast.ts     # 替代浏览器原生 alert/confirm/prompt
│   ├── useDevice.ts                   # 设备指纹
│   └── useAdminMeta.ts                # Admin meta 标签
│
├── components/                        # 公开组件（NavBar / SiteFooter / GlobalDialog / ...）
├── plugins/                           # Nuxt 插件（auth.client.ts 等）
│
├── pages/ # 13 个页面（5 公开 + 8 admin）
│   ├── index.vue                      # 首页（Hero + 文章列表）
│   ├── post/[slug].vue                # 文章详情（v2.7.0 build 时预渲染）
│   ├── archives.vue                   # 归档
│   ├── tags.vue                       # 标签云
│   ├── about.vue                      # 关于页
│   └── admin/
│       ├── login.vue                  # 登录页
│       ├── dashboard.vue              # 仪表盘
│       ├── posts.vue                  # 文章管理
│       ├── edit.vue                   # 文章编辑
│       ├── comments.vue               # 评论管理
│       ├── categories.vue             # 分类管理
│       ├── tags.vue                   # 标签管理
│       ├── settings.vue               # 个人设置（7 tab）
│       └── devices.vue                # 设备管理
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
│   - 限流（登录 5req/min，API 20req/s）                  │
│   - 前端静态目录 /var/www/blog/（v2.7.0 配套）         │
└────────────────────────┬────────────────────────────────┘
          ┌──────────────┴──────────────┐
          ▼                             ▼
┌──────────────────────┐   ┌─────────────────────────────┐
│ nginx:alpine (3000)  │   │   Spring Boot (8080)         │
│ 静态文件 serve       │   │   后端 REST API              │
│ .output/public/      │   │   (v2.7.0 后只剩后端 JVM)    │
│ v2.7.0 预渲染 HTML   │   └──────────────┬──────────────┘
└──────────────────────┘                  │ JDBC / Lettuce
                               ┌──────────┴──────────┐
                               ▼                     ▼
                        ┌────────────┐         ┌──────────────┐
                        │  SQLite    │         │    Redis     │
                        │  blog.db   │         │  7.x         │
                        │ (v2.6 默认)│         │  :6379       │
                        └────────────┘         └──────────────┘
```

**v2.7.0 关键变化**：前端进程从 "node + Nitro server" 改成 "nginx:alpine 静态服务"，**省 150-250MB 内存**（1C2G 服务器上 JVM heap 可从 256MB 提到 384MB）。

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

详见 `docs/接口契约审计报告.md`。

### 3.5 数据库设计（11 张表）

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| `user` | 管理员账号 | username / password_hash (BCrypt) / nickname / email / avatar / bio / location / role |
| `article` | 文章 | title / slug / summary / content_md / cover_url / status / view_count / category_id / deleted |
| `category` | 分类 | name / slug / description / sort / visible |
| `tag` | 标签 | name / slug |
| `article_tag` | 文章-标签关联 | article_id / tag_id |
| `comment` | 评论 | content / article_id / parent_id / nickname / email / website / ip / user_agent / status (0待审/1通过/2屏蔽) |
| `article_view_log` | 历史表（v2.5.0 之前的访问日志，0 数据保留 schema 兼容） | article_id / view_date / view_count |
| `site_settings` | 站点设置（按 section 存整段 JSON，v2.1 新增） | section (blog/social/preferences/theme/advanced) / data (JSON) |
| `admin_device` | 设备白名单（v2.2 新增） | device_id / status (pending/approved/revoked) |
| `api_whitelist` | API 路由白名单（AdminAuthFilter 用，v2.2 新增） | path_prefix / type (public/admin) / enabled / description |
| `page_view` | 访问统计（v2.5.0 新增，v2.6.0 业务层去重） | visitor / url / visit_date / ua / ip |

**SQL 文件**（v2.6.0 整合后，2 个 schema 替代 8 个散文件）：
- `docs/sql/schema-sqlite.sql` — **dev/prod 默认**，11 张表 + seed data
- `docs/sql/schema-mysql.sql` — MySQL 可选 profile，11 张表 + seed data（按月分区）

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
3. **SQLite 单写者锁**：Hikari `maximum-pool-size: 1` 必须保持，否则并发写会 SQLITE_BUSY
4. **admin SSR 鉴权**：`frontend/middleware/admin-auth.ts` 已修复 SSR 阶段跳过 localStorage，直接访问 `/admin/*` 不会误踢已登录用户（v2.7.0 全静态化后 `import.meta.server` 恒为 `false`，SSR 判断代码已删）
5. **端到端验证**：`scripts/verify-sqlite.sh` 跑 29 个端点（含 page_view 业务层去重验证），全过后才算 dev 完成

---

## 五、生产环境部署

> 详细方案见 [`docs/阿里云部署方案.md`](docs/阿里云部署方案.md)。**v2.6.0 起部署架构简化**：1C2G 无 docker + SQLite + 全静态前端。

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

| 资源 | 规格 | 成本 |
|------|------|------|
| 阿里云 ECS 香港轻量 | 1C2G / 40G SSD / 3M 带宽 | ~¥30/月 |
| 域名 | `.com` | ¥55/年 |
| SSL 证书 | Let's Encrypt (certbot 自动续期) | ¥0 |

> v2.7.0 释放前端 150-250MB 内存后，JVM heap 可从 256MB 提到 384MB（详见 `scripts/deploy-sqlite.sh`）。

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
scp scripts/deploy-sqlite.sh myblog@<ecs-ip>:/tmp/

# ============ Day 3：ECS 上一键部署 ============
ssh myblog@<ecs-ip>
sudo mv /tmp/blog-app.jar /opt/myblog/
sudo mv /tmp/schema-sqlite.sql /opt/myblog/
sudo mv /tmp/public /opt/myblog/frontend-static
sudo mv /tmp/deploy-sqlite.sh /opt/myblog/scripts/
sudo chmod +x /opt/myblog/scripts/deploy-sqlite.sh
sudo bash /opt/myblog/scripts/deploy-sqlite.sh
```

`deploy-sqlite.sh` 自动完成：
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
# 在本地（dev 环境）跑 29 端点验证
bash scripts/verify-sqlite.sh

# 期望输出："✅ 全部 29 个端点通过"
# 包含：
#   - 12 个公开端点（健康、文章、分类、标签、归档、评论、设置、设备检查）
#   - 11 个 admin 端点（鉴权 + 仪表盘 + 各管理模块）
#   - 4 个写操作端点（更新 profile、page_view 写入/去重）
#   - 1 个业务层去重验证（X-Visitor-Id 同访客连刷 5 次 → DB +1 行）
#   - 1 个 page_view 业务层去重（同 visitor 5 次 → 1 行）
```

### 5.6 生产环境配置

所有敏感信息通过环境变量注入，不在仓库中明文保存：

```bash
# 复制模板
cp .env.prod.example .env.prod
chmod 600 .env.prod
# 填入实际值：DB_PASSWORD / REDIS_PASSWORD / JWT_SECRET / CORS_ORIGINS 等
```

关键生产改造（`docs/阿里云部署方案.md` §七）：

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
- [ ] **配置**：`application-prod.yml` / `scripts/deploy-sqlite.sh` / `nginx.conf` 就绪
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

# ============ MySQL → SQLite 数据迁移 ============
# 需要先有 MySQL 数据 + 新的空 SQLite db
python3 scripts/migrate-mysql-to-sqlite-direct.py

# ============ 生产部署（v2.6.0 起）============
# 上传 jar + schema + 脚本到 ECS
scp backend/blog-app/target/blog-app.jar myblog@<ecs-ip>:/tmp/
scp docs/sql/schema-sqlite.sql myblog@<ecs-ip>:/tmp/
scp scripts/deploy-sqlite.sh myblog@<ecs-ip>:/tmp/

# ECS 上一键部署
sudo bash /opt/myblog/scripts/deploy-sqlite.sh

# 每日 cron rebuild（v2.7.0 配套）
0 3 * * * bash /opt/myblog/scripts/rebuild-static.sh

# ============ 数据库备份（SQLite）============
# 在线热备份（不锁库）
sqlite3 /opt/myblog/blog.db ".backup /opt/myblog/backups/blog-$(date +%Y%m%d-%H%M%S).db"
```

### 6.2 目录约定

| 约定 | 值 |
|------|---|
| 后端包名 | `com.blog.*` |
| 数据库 | SQLite: `backend/blog.db`（dev）/ `/opt/myblog/blog.db`（prod）；MySQL: `blog` |
| API 前缀 | `/api/v1` |
| 文件上传 | `/data/uploads/yyyy/mm/<uuid>.<ext>`（dev: `backend/tmp/blog-uploads/`） |
| Swagger | `http://localhost:8080/api/v1/swagger-ui.html` |
| 静态产物 | `frontend/.output/public/`（dev build） → `/var/www/blog/`（prod） |
| systemd service | `/etc/systemd/system/myblog.service` |

### 6.3 参考文档

| 文档 | 说明 |
|------|------|
| [`docs/设计文档/博客系统设计方案.md`](docs/设计文档/博客系统设计方案.md) | 完整需求与架构设计（v0.3，含 v2.6.0/v2.7.0 变更记录） |
| [`docs/阿里云部署方案.md`](docs/阿里云部署方案.md) | 生产部署方案（阿里云 ECS 1C2G，无 docker，v2.6.0/v2.7.0 配套） |
| [`docs/接口契约审计报告.md`](docs/接口契约审计报告.md) | API 契约 100% 一致性审计 |
| [`docs/设计文档/防重放攻击方案设计.md`](docs/设计文档/防重放攻击方案设计.md) | 防重放攻击方案 |
| [`docs/changelogs/`](docs/changelogs/) | 版本变更记录（v2.0.0 → v2.7.0，每个版本独立 md） |
| [`AGENTS.md`](AGENTS.md) | 项目级 agent 上下文（v2.7.0 同步更新） |
| [`docs/changelogs/2026-06-17-v2.6.0-sqlite-migration.md`](docs/changelogs/2026-06-17-v2.6.0-sqlite-migration.md) | v2.6.0 SQLite 改造完整 changelog |
| [`docs/changelogs/2026-06-17-v2.7.0-nuxt-static.md`](docs/changelogs/2026-06-17-v2.7.0-nuxt-static.md) | v2.7.0 全静态化完整 changelog |

---

## 七、暂未实现（后续规划）

### 7.1 已完成（v2.6.0 / v2.7.0 落地）

- ✅ **数据导入/导出**（v2.6.0 `migrate-mysql-to-sqlite-direct.py` 落地 MySQL → SQLite 工具）
- ✅ **自动化单元测试**（v2.6.0 `verify-sqlite.sh` 端到端 29 端点验证脚本）
- ✅ **Flyway / Liquibase 数据库迁移**（v2.6.0 整合到 2 个 schema 文件，等价于"单文件 migration"）

### 7.2 backlog（按优先级）

| 优先级 | 项 | 说明 |
|---|---|---|
| 🟡 中 | SQLite 单写者锁缓解 | 当前 Hikari `maximum-pool-size: 1`，5 并发请求会排队；可考虑 WAL 模式 + connection pool 调优 |
| 🟡 中 | page_view 长期数据清理 | v2.6.0 起无分区（SQLite 不支持），按月清理脚本待加 |
| 🟡 中 | article.word_count 写时计算 | 写文章时计算并入库，避免 dashboard `computeTotalWordCount` 全表扫描 |
| 🟡 中 | 评论树形结构 | 按 `parent_id` 递归（当前扁平列表） |
| 🟡 中 | Redis 缓存层扩展 | 当前仅 `site_settings` 接入 Redis（5min TTL），文章详情/列表/分类/标签缓存待补 |
| 🟢 低 | Service 层下沉 | 已有 `DeviceService` / `ApiWhitelistService` / `SiteSettingsService` / `PageViewService`，文章/评论/分类 Controller 直调 Mapper 待统一下沉 |
| 🟢 低 | 图片懒加载 + WebP 转换 | 公开页图片优化 |
| 🟢 低 | deploy-mysql.sh | 保留 5 容器 docker-compose 方案的部署脚本（用户已明确推迟） |
| 🟢 低 | GitHub Actions CI/CD | 自动化测试 + 镜像推送（v2.6.0/v2.7.0 部署脚本已就绪，CI 配套待加） |
| 🟢 低 | rebuild 事件触发 | 从每日 cron 改为写文章时触发（30-60s 延迟） |
| 🟢 低 | CDN 加速 | 把 `/var/www/blog/` 同步到阿里云 OSS / CDN，国内访问加速 |
| 🟢 低 | 告警脚本 | build 失败发邮件/微信 |
| 🟢 低 | 访客 cookie 强制下发 | PageViewFilter `Set-Cookie: blog_visitor_id` 给无 X-Visitor-Id 的访客（彻底解决 curl/反爬漏统计） |
| 🟢 低 | 预渲染缓存 + 增量构建 | nuxt generate 改成只构建新增/修改的文章 |
| 🟢 低 | 设计稿 P0/P1 修复 | 暂无文档归档，后续补 `docs/设计稿符合性报告.md` |
