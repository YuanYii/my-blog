# 个人博客系统

> **v2.2.0** — Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离的个人博客 MVP。
> 当前状态：前后端完整功能 + 设备/API 白名单鉴权 + 设计稿审计 + 生产部署方案就绪。

---

## 一、项目介绍

### 1.1 是什么

一个前后端分离的个人博客系统，包含**公开前台**（文章浏览/归档/标签/关于）和**管理后台**（仪表盘/文章管理/评论审核/分类标签/个人设置）。

### 1.2 核心功能

| 模块 | 功能 |
|------|------|
| **前台公开** | 首页（Hero 个人介绍 + 文章列表）/ 文章详情 / 归档 / 标签云 / 关于页 |
| **管理后台** | 仪表盘（KPI 聚合）/ 文章增删改 / 评论审核 / 分类&标签管理 / 个人资料设置 |
| **系统** | JWT 鉴权 / 设备白名单（X-Device-Id 绑定 token）/ API 路由白名单（DB 驱动）/ 文件上传 / 站点设置（blog/social/preferences/theme/advanced）/ Swagger API文档 |

### 1.3 版本记录

- **v2.2.0**（2026-06-12）— 设备白名单（X-Device-Id 绑定 token）+ API 路由白名单（DB 驱动 + 最长前缀匹配）+ 防重放攻击设计 + 写端点匿名访问漏洞修复
- **v2.1.0**（2026-06-08）— site_settings DB 持久化 + 公开读端点 + 前台首页实时同步后台改动
- **v2.0.0**（2026-06-07）— 8 admin 页 + 5 公开页 + JWT Filter + 13 对页面设计稿审计

---

## 二、项目结构

### 2.1 整体目录

```
my-blog/
├── backend/                           # Spring Boot 多模块后端
├── frontend/                          # Nuxt 3 前端
├── docs/                              # 设计文档与审计报告
│   ├── sql/                           # 数据库脚本
│   │   ├── blog.sql                   # MySQL 初始化（10 张表 + 种子数据）
│   │   └── migrations/                # 增量迁移 SQL（按日期命名）
│   ├── docker/                        # 生产 docker-compose（docker-compose.prod.yml）
│   ├── nginx/                         # Nginx 反向代理配置（nginx.conf / nginx-https.conf）
│   ├── 设计文档/                      # 设计方案
│   │   ├── 博客系统设计方案.md
│   │   └── 防重放攻击方案设计.md
│   ├── 接口契约审计报告.md            # API 契约 100% 一致性审计
│   └── 阿里云部署方案.md              # 生产部署方案（阿里云 ECS）
├── docs/docker/docker-compose.prod.yml  # 生产环境 Docker 编排
├── docs/nginx/nginx.conf / nginx-https.conf # Nginx 配置（生产用）
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
├── blog-article/                       # 文章 & 分类 & 标签
│   └── src/main/java/com/blog/article/
│       ├── controller/
│       │   └── ArticleController.java # 公开列表/详情 + admin CRUD
│       ├── entity/
│       │   ├── Article.java           # 文章（逻辑删除 deleted=1）
│       │   ├── Category.java          # 分类
│       │   └── Tag.java # 标签
│       └── mapper/
│           ├── ArticleMapper.java
│           ├── CategoryMapper.java
│           └── TagMapper.java
│
├── blog-comment/                       # 评论
│   └── src/main/java/com/blog/comment/
│       ├── controller/
│       │   └── CommentController.java # 公开列表 + 审核 + admin 列表
│       └── entity/
│           └── Comment.java
│
├── blog-settings/                       #站点设置 & 文件上传
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
        │   │   └── AdminAuthFilter.java # JWT 每请求鉴权 +设备白名单校验
        │   └── controller/
        │       ├── HelloController.java
        │       ├── DashboardController.java #仪表盘聚合
        │       └── ApiWhitelistController.java
        └── resources/
            ├── application-dev.yml      # 开发配置
            ├── application-prod.yml    # 生产配置
            └── logback-spring.xml # 日志配置
```

### 2.3 前端项目结构

```
frontend/
├── package.json / nuxt.config.ts / tsconfig.json / tailwind.config.js
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
│                                          （BUG-001 修复：SSR 阶段跳过 localStorage）
│
├── composables/ # 组合式函数
│   ├── useApi.ts                      # Axios 封装（统一拦截/错误处理）
│   ├── useAuth.ts # 鉴权状态（login/logout/me）
│   └── useAdminMeta.ts # Admin meta 标签
│
├── components/
│   ├── NavBar.vue                     # 导航栏（公开页）
│   └── SiteFooter.vue                  # 页脚
│
└── pages/
    ├── index.vue                      # 首页（Hero + 文章列表）
    ├── post/[slug].vue                # 文章详情
    ├── archives.vue                    # 归档
    ├── tags.vue # 标签云
    ├── about.vue                       # 关于页（404 兜底由根 error.vue 处理）
    └── admin/
        ├── login.vue # 登录页
        ├── dashboard.vue              # 仪表盘
        ├── posts.vue                  # 文章管理
        ├── edit.vue                   # 文章编辑
        ├── comments.vue               # 评论管理
        ├── categories.vue             # 分类管理
        ├── tags.vue                   # 标签管理
        ├── settings.vue               # 个人设置（5 tab）
        └── devices.vue                # 设备管理
```

---

## 三、技术方案

### 3.1 技术栈总览

| 层 | 选型 | 版本 |
|----|------|------|
| **后端语言** | Java | 1.8（开发机环境限制） |
| **后端框架** | Spring Boot | 2.7.18 |
| **ORM** | MyBatis-Plus | 3.4.3.4 |
| **数据库** | MySQL | 8.0（utf8mb4 字符集） |
| **缓存** | Redis | 7.x |
| **鉴权** | JJWT | 0.11.5 |
| **工具库** | Hutool + Lombok | 5.8.27 / 1.18.x |
| **API 文档** | springdoc-openapi-ui | 1.7.0 |
| **前端框架** | Nuxt 3 | ^3.13 |
| **包管理** | npm | 11.x |
| **样式** | Tailwind CSS | ^3.4 |
| **类型** | TypeScript | ^5.5 |
| **构建** | Maven | 3.6.0 |

### 3.2 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                        用户浏览器 │
│              http://localhost:3000 (dev)                 │
│ https://yourname.com (prod, HTTPS)              │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP / HTTPS
                        ▼
┌─────────────────────────────────────────────────────────┐
│                     Nginx                                │
│   - 80 → 443 强制跳转                                   │
│   - HTTPS (Let's Encrypt 证书)                          │
│   -静态文件 /uploads/ 直接 serve                       │
│   - /api/* 反代到 backend:8080                         │
│   - 限流（登录 5req/min，API 20req/s）                  │
└───────────────────────┬─────────────────────────────────┘
         ┌─────────────┴─────────────┐
          ▼▼
┌──────────────────┐    ┌────────────────────────────┐
│   Nuxt SSR (3000) │    │   Spring Boot (8080)        │
│   前端 SSR/CSR │    │   后端 REST API              │
└──────────────────┘    └──────────────┬───────────────┘
                                        │ JDBC / Lettuce
                         ┌─────────────┴─────────────┐
                         ▼ ▼
                  ┌────────────┐          ┌──────────────┐
                  │   MySQL     │          │    Redis     │
                  │   8.0      │          │   7-alpine │
                  │ :3306       │          │  :6379        │
                  └────────────┘          └──────────────┘
```

### 3.3 后端模块依赖关系

```
blog-app（启动类，唯一可执行 jar）
  ├── blog-common（公共组件，所有模块共享）
  ├── blog-auth（用户 + JWT Filter）
  ├── blog-article（文章/分类/标签，依赖 User 显示作者）
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

业务码规范：200 成功 / 400 参数错误 / 401 未登录 / 403 无权限 / 404 不存在 / 500 服务器错误。

### 3.5 数据库设计（10 张表）

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| `user` | 管理员账号 | username / password_hash (BCrypt) / nickname / email / avatar / bio / location / role |
| `article` | 文章 | title / slug / summary / content_md / cover_url / status / view_count / category_id / deleted |
| `category` | 分类 | name / slug / description / sort / visible |
| `tag` | 标签 | name / slug |
| `article_tag` | 文章-标签关联 | article_id / tag_id |
| `comment` | 评论 | content / article_id / parent_id / nickname / email / website / ip / user_agent / status (0待审/1通过/2屏蔽) |
| `article_view_log` | 文章每日阅读数（仪表盘昨日聚合用） | article_id / view_date / view_count |
| `site_settings` | 站点设置（按 section 存整段 JSON，v2.1 新增） | section (blog/social/preferences/theme/advanced) / data (JSON) |
| `admin_device` | 设备白名单（v2.2 新增） | device_id / status (pending/approved/revoked) |
| `api_whitelist` | API 路由白名单（AdminAuthFilter 用，v2.2 新增） | path_prefix / type (public/admin) / enabled / description |

### 3.6 配色契约

- **墨绿**：`#2f6f5e` — 主色调
- **焦糖橙**：`#c97b3f` — 强调色
- 定义在 `frontend/assets/css/main.css`

---

## 四、开发环境部署

### 4.1 前置条件

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 1.8+ | 推荐17+ |
| Maven | 3.6+ | 后端构建 |
| Node.js | 18+ | 推荐 22+ |
| npm | 11.x | 前端包管理 |
| Docker | 最新 | 一键启动 MySQL + Redis |

### 4.2 启动步骤

#### 第一步：启动 MySQL + Redis

```bash
# 启动 MySQL
docker run -d --name blog-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=blog \
  -p 3306:3306 \
  -v blog-mysql-data:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

# 启动 Redis
docker run -d --name blog-redis -p 6379:6379 redis:7-alpine

# 等待 MySQL 就绪
docker exec blog-mysql mysql -uroot -proot -e "SELECT VERSION();"
```

#### 第二步：导入数据库

> **必须**显式声明 `--default-character-set=utf8mb4`，否则会触发双重 UTF-8 编码 bug。

```bash
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/blog.sql
```

验证导入结果（应看到 1 user / 5 categories / 8 tags / 1 article）：

```bash
docker exec blog-mysql mysql -uroot -proot blog -e "
SELECT 'user' AS t, COUNT(*) AS n FROM user
UNION ALL SELECT 'category', COUNT(*) FROM category
UNION ALL SELECT 'tag', COUNT(*) FROM tag
UNION ALL SELECT 'article', COUNT(*) FROM article;"
```

#### 第三步：启动后端

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

健康检查：

```bash
curl http://localhost:8080/api/v1/health
#期望：{"code":200,"data":{"status":"UP",...}}

# 测试登录（默认账号 admin / 123456）
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

Swagger 文档：[http://localhost:8080/api/v1/swagger-ui.html](http://localhost:8080/api/v1/swagger-ui.html)

#### 第四步：启动前端

```bash
cd frontend
npm install # 首次约 30 秒
npm run dev
```

打开 [http://localhost:3000](http://localhost:3000)。

### 4.3 一键停止

```bash
docker stop blog-mysql blog-redis
# backend/ 和 frontend/ 目录分别 Ctrl+C
```

### 4.4 开发注意事项

1. **改完模块必须 install**：每次修改 `blog-auth` / `blog-article` 等模块后，必须运行 `mvn -pl blog-xxx -am install -DskipTests`，否则 `mvn spring-boot:run` 不会加载新类（classpath 走 jar 而非 target/classes）
2. **中文 SQL 导入**：用 `--default-character-set=utf8mb4`，不要用 docker exec pipe 写含中文的 SQL 数据（会双重编码）
3. **admin SSR 鉴权**：`frontend/middleware/admin-auth.ts` 已修复 SSR 阶段跳过 localStorage，直接访问 `/admin/*` 不会误踢已登录用户

---

## 五、生产环境部署

>详细方案见 [`docs/阿里云部署方案.md`](docs/阿里云部署方案.md)，以下为核心流程概要。

### 5.1 部署架构

**阿里云香港轻量 ECS 单机部署**（2C2G，~¥60/月，免备案）：

```
用户浏览器 (HTTPS)
      ↓
Nginx (:80 → 443)
  ├── /api/* → Spring Boot :8080
  ├── /_nuxt/* → 静态文件
  └── /uploads/* → 本地磁盘 /data/uploads/
           ↓
   ┌──────────────────────────────┐
   │ ECS 单机 Docker Compose │
   │  - mysql (MySQL 8.0)         │
   │  - redis (Redis 7-alpine)    │
   │  - backend (Spring Boot jar) │
   │  - frontend (Nuxt SSR node)  │
   │  - web (Nginx) │
   └──────────────────────────────┘
```

### 5.2 前置资源

| 资源 | 规格 | 成本 |
|------|------|------|
| 阿里云 ECS 香港轻量 | 2C2G / 50G SSD / 5M 带宽 | ¥60/月 |
| 域名 | `.com` | ¥55/年 |
|阿里云 ACR 个人版 | 免费 | ¥0 |
| GitHub Actions | 公开仓库免费 2000 分钟/月 | ¥0 |
| SSL 证书 | Let's Encrypt (certbot 自动续期) | ¥0 |

### 5.3 CI/CD 流程

```
开发者 git push origin main
         ↓
GitHub Actions 触发（.github/workflows/deploy.yml）
        ↓
┌──────────────────────────────────┐
│ Job 1: build-backend             │
│  - mvn package                   │
│  - docker build → 阿里云 ACR │
└──────────────────────────────────┘
         ↓
┌──────────────────────────────────┐
│ Job 2: build-frontend            │
│  - npm ci / npm run build        │
│  - docker build → 阿里云 ACR    │
└──────────────────────────────────┘
         ↓
┌──────────────────────────────────┐
│ Job 3: deploy (SSH 到 ECS)       │
│  - docker-compose pull │
│  - docker-compose up -d          │
│  - health check + rollback │
└──────────────────────────────────┘
```

### 5.4 部署步骤

#### Day 1：资源采购
- [ ] 采购阿里云 ECS 香港轻量（2C2G）
- [ ] 注册域名 + 配置 DNS 解析（A 记录 → ECS 公网 IP）
- [ ] 创建阿里云 ACR 个人版
- [ ] 配置 GitHub Secrets（ACR 凭证 / ECS SSH 私钥 / ECS_HOST 等）

#### Day 2：环境搭建
- [ ] SSH 密钥对登录 ECS
- [ ] ECS 安装 Docker + Docker Compose
- [ ] 创建 `/data/uploads/` + 目录权限
- [ ] certbot 签发 Let's Encrypt 证书

#### Day 3：代码改造 + 本地验证
- [ ] 完成生产部署改造清单（详见 `docs/阿里云部署方案.md` §七）
- [ ] 本地 `docker compose -f docker-compose.prod.yml up` 验证
- [ ] 验证所有页面访问正常

#### Day 4：CI/CD + 首次部署
- [ ] 配置 `.github/workflows/deploy.yml`
- [ ] 编写 `scripts/deploy.sh`（pull + up + health check + rollback）
- [ ] git push → GitHub Actions → 自动部署
- [ ] 浏览器访问 `https://yourname.com` 验证

### 5.5 生产环境配置

所有敏感信息通过 `.env.prod` 注入，不在仓库中明文保存：

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

### 5.6 上线前 CheckList

- [ ] **安全**：BCrypt 密码 / JWT secret 强随机 / Swagger 关闭 / CORS 收紧 / SSH 密钥登录
- [ ] **配置**：`application-prod.yml` / `docker-compose.prod.yml` / `nginx.conf` 就绪
- [ ] **数据**：默认 admin 密码修改 / 删除测试数据
- [ ] **HTTPS**：证书部署 / 80 → 443 强制跳转
- [ ] **CI/CD**：GitHub Actions 跑通 / 自动部署 / 回滚脚本就绪
- [ ] **监控**：Docker 容器状态监控 + API 健康检查
- [ ] **备份**：mysqldump 自动备份 + `/data/uploads/` 同步 OSS 冷存

---

## 六、快速参考

### 6.1 常用命令

```bash
# 开发启动
docker run -d --name blog-mysql ... && docker run -d --name blog-redis -p 6379:6379 redis:7-alpine
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/blog.sql
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm run dev

# 健康检查
curl http://localhost:8080/api/v1/health

# 一键停止
docker stop blog-mysql blog-redis
```

### 6.2 目录约定

| 约定 | 值 |
|------|---|
| 后端包名 | `com.blog.*` |
| 数据库 | `blog` |
| API 前缀 | `/api/v1` |
| 文件上传 | `/data/uploads/yyyy/mm/<uuid>.<ext>` |
| Swagger | `http://localhost:8080/api/v1/swagger-ui.html` |

### 6.3 参考文档

| 文档 | 说明 |
|------|------|
| [`docs/设计文档/博客系统设计方案.md`](docs/设计文档/博客系统设计方案.md) | 完整需求与架构设计（v2.1） |
| [`docs/阿里云部署方案.md`](docs/阿里云部署方案.md) | 生产部署方案（阿里云 ECS 香港） |
| [`docs/接口契约审计报告.md`](docs/接口契约审计报告.md) | API 契约 100% 一致性审计 |
| [`docs/设计文档/防重放攻击方案设计.md`](docs/设计文档/防重放攻击方案设计.md) | 防重放攻击方案 |
| [`AGENTS.md`](AGENTS.md) | 项目级 agent 上下文 |

---

## 七、暂未实现（后续规划）

- 设计稿 P0/P1 修复（暂无文档归档，后续补 `docs/设计稿符合性报告.md`）
- Redis 缓存层扩展：目前仅 `site_settings` 接入 Redis（5min TTL），文章详情/列表/分类/标签缓存待补
- Service 层下沉：已有 `DeviceService`/`ApiWhitelistService`/`SiteSettingsService`，文章/评论/分类仍是 Controller 直调 Mapper，待统一下沉
- 评论树形结构（按 `parent_id` 递归）
- 图片懒加载 + WebP 转换
- 数据导入/导出
- Flyway / Liquibase 数据库迁移
- 自动化单元测试（JUnit 5 + Vitest）