# 个人博客系统 (MyBlog)

> 基于 **Spring Boot 2.7（多模块） + Nuxt 3** 前后端分离的轻量高能个人博客系统。
>
> 专为 **1C2G 低配服务器** 深度优化：默认 SQLite 数据库（零内存开销）+ 前端全静态化预渲染（省 150-250MB 内存）+ 极速单机部署。

---

[在线 Demo](https://blog.coreyai.cn/) · [项目架构](#二项目架构) · [快速开始](#三开发环境部署) · [部署指南](#四生产环境部署) · [变更日志](docs/changelogs/)

---

## 一、项目介绍

### 1.1 项目定位

一个干净、高性能、易于部署和扩展的个人博客系统：
- **前台公开**：文章浏览、归档、分类、标签云、关于页、全局搜索，支持 SEO 静态预渲染与渐进增强。
- **管理后台**：仪表盘 KPI 统计、文章 Markdown 编辑/附件管理、评论审核、分类与标签、多子页站点设置、设备白名单、IP 限流封禁、数据加密备份与恢复、审计日志、系统一键升级。

### 1.2 核心功能矩阵

| 模块 | 功能说明 |
|------|----------|
| **前台公开** | 首页（Hero + 文章列表）/ 文章详情（**SEO 渐进增强**） / 归档 / 分类 / 标签云 / 关于页 / 全局搜索 / RSS 订阅 |
| **管理后台** | 仪表盘（KPI 聚合 + 30天趋势 + 访客IP来源与归属地）/ 文章增删改与批量 ZIP 导入 / 附件管理（一文一附件 5MB zip）/ 评论审核 / 分类&标签管理 / 9 子路由站点设置 / Markdown 模版批量导入设置 / 审计日志（21 模块映射 + 操作类型筛选）/ 设备白名单 / 数据备份与恢复 / 系统一键升级 |
| **系统核心** | JWT 鉴权 / 设备绑定 (X-Device-Id) / API 路由白名单 (DB驱动) / 安全文件上传 / SLF4J+Logback 日志体系（traceId 全链路串联，3GB 滚动上限）/ IP 限流与封禁（Redis + DB 持久化）/ 全静态前端构建 |

### 1.3 版本里程碑摘要

- **v6.0.2** — 访客 IP 来源追加 IP 归属地展示、分类与标签文章计数精准统计修复、公开分类页及文章列表带参分页、后台文章列表浏览增量高亮。
- **v5.3.0** — 系统升级模块：管理后台一键升级（Python 标准库宿主代理 + SSE 流式日志 + 升级历史记录表）。
- **v5.2.0** — SEO 可搜索与渐进增强（服务端 HTML 预渲染 + Markdown 渲染器 + robots.txt / sitemap.xml）。
- **v5.1.0** — 审计日志系统（AOP 拦截写端点 + 操作类型过滤）与 Markdown 模版批量更新站点设置（SnakeYAML SafeConstructor 严格校验）。
- **v5.0.0** — 文章附件管理系统（一文一附件、软删/硬删/恢复）与数据恢复同进程不停服架构。
- **v4.2.0** — 数据加密备份与恢复（AES-256-CBC + PBKDF2 加密推送到 GitHub Release）。
- **v4.0.0** — 服务日志体系 (traceId 串联) 与全站 IP 限流封禁 (10次/秒 + 30分钟封禁)。
- **v2.7.0** — 前端全静态化架构（`nuxt generate` + Nginx 部署，省 150-250MB 内存）。
- **v2.6.0** — 数据库 SQLite 双 Profile 架构（SQLite 默认，MySQL 8.0 可选）。

> 详细的版本变更明细请查阅：[`docs/changelogs/`](docs/changelogs/)

---

## 二、项目架构

### 2.1 技术栈总览

| 层级 | 选型 | 版本/说明 |
|------|------|-----------|
| **后端语言/框架** | Java 8 + Spring Boot | 2.7.18（多模块单体） |
| **ORM / 数据库** | MyBatis-Plus + SQLite (默认) / MySQL (可选) | SQLite 3.45.0 / MySQL 8.0 |
| **缓存 / 鉴权** | Redis 7.x + JJWT | 0.11.5 |
| **前端框架** | Nuxt 3 (Vue 3 + TypeScript) | ^3.13 (`ssr: false` 全静态预渲染) |
| **前端样式** | Tailwind CSS + Vanilla CSS | ^3.4 |
| **部署环境** | Nginx:alpine + OpenJDK 8 + Systemd | 零 Docker 轻量运行（1C2G VPS 友好） |

### 2.2 整体架构图

```
┌───────────────────────────────────────────────────────────┐
│                        用户浏览器                          │
│               https://yourblog.cn (HTTPS)                 │
└─────────────────────────────┬─────────────────────────────┘
                              │ HTTP / HTTPS
                              ▼
┌───────────────────────────────────────────────────────────┐
│                          Nginx                            │
│   - HTTPS (SSL 证书) / 80 → 443 强制重定向                │
│   - 静态资源 serve (/var/www/blog/)                       │
│   - /uploads/ & /attachments/ 静态文件直传               │
│   - /api/* 反代至后端端口 :8080                           │
└─────────────────────────────┬─────────────────────────────┘
                              ▼
┌───────────────────────────────────────────────────────────┐
│                    Spring Boot Backend                    │
│   - Filter 链: TraceIdFilter → IpRateLimitFilter          │
│               → AdminAuthFilter                          │
│   - AOP 审计日志拦截 (AuditLogAspect)                      │
└──────────────────────┬─────────────────────────────┬──────┘
                       ▼                             ▼
            ┌────────────────────┐        ┌────────────────────┐
            │   SQLite blog.db   │        │     Redis 7.x      │
            │   (或 MySQL 8.0)   │        │  (限流/封禁/缓存)   │
            └────────────────────┘        └────────────────────┘
```

### 2.3 代码目录结构

```text
my-blog/
├── backend/                              # Spring Boot 多模块后端
│   ├── blog-common/                      # 公共工具类 (Result, IpLocation, ExceptionHandler, TraceId)
│   ├── blog-auth/                        # 认证授权 (JWT, 设备白名单, IP封禁, 审计日志)
│   ├── blog-article/                     # 文章/分类/标签/页面浏览统计 (PageViewService)
│   ├── blog-comment/                     # 评论管理
│   ├── blog-settings/                    # 站点设置, 文件上传, 数据备份/恢复
│   └── blog-app/                         # 启动模块 (BlogApplication, Dashboard, Swagger)
│
├── frontend/                             # Nuxt 3 前端 (静态构建)
│   ├── assets/                           # CSS 样式与设计 Token
│   ├── components/                       # 复用 UI 组件与 Admin 后台组件
│   ├── composables/                      # 组合式 API (useAuth, useAdminApi, useSiteTheme 等)
│   ├── pages/                            # 路由页面 (公开前台 + Admin 后台 + Settings 子路由)
│   ├── nuxt.config.ts                    # Nuxt 预渲染与 build 配置
│   └── scripts/fetch-routes.js           # 预渲染公开路由生成脚本
│
├── scripts/                              # 部署/备份/导出/导入运维脚本
│   ├── deploy-server.sh                  # 服务器一键部署/升级脚本
│   ├── sqlite-export.sh / import.sh      # 数据库 AES-256 加密导出/解密导入
│   └── blog-backup.sh / restore.sh       # 数据自动备份与恢复脚本
│
├── docs/                                 # 项目设计与审计文档
│   ├── changelogs/                       # 各版本 Changelog 归档
│   ├── design/                           # 系统设计与架构方案文档
│   └── sql/                              # SQLite 与 MySQL 初始化 Schema
└── AGENTS.md                             # AI Agent 工作规范与偏好
```

---

## 三、开发环境部署

### 3.1 环境要求

- **JDK**：1.8+
- **Maven**：3.6+
- **Node.js**：18+ (推荐 20+)
- **Redis**：7.x (开发机可使用 Docker 或本地安装)

### 3.2 本地启动步骤

#### 1. 启动 Redis
```bash
docker run -d --name blog-redis -p 6379:6379 redis:7-alpine
```

#### 2. 初始化数据库 (仅首次需要)
SQLite 默认在启动时自动建库。如需使用 Seed Data 手动初始化：
```bash
sqlite3 backend/blog.db < docs/sql/schema-sqlite.sql
```

#### 3. 启动后端服务
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
- 后端接口地址：`http://localhost:8080`
- Swagger API 文档：`http://localhost:8080/api/v1/swagger-ui.html`
- 默认管理员账号密码：`admin / 123456`

#### 4. 启动前端服务
```bash
cd frontend
npm install
npm run dev
```
- 前端访问地址：`http://localhost:3000`

---

## 四、生产环境部署

> 详细部署方案与 ADR 设计请参考：[`docs/项目部署操作手册.md`](docs/项目部署操作手册.md)。

### 4.1 一键部署流程

项目提供了全自动的服务器部署脚本 `scripts/deploy-server.sh`（支持环境初始化、代码拉取、自动化编译与服务安装）：

```bash
# 在 Linux 服务器上执行一键部署命令
sudo DEPLOY_MODE=full bash /opt/myblog/scripts/deploy-server.sh v6.0.2
```

### 4.2 每日 Cron 静态重构建

配合全静态化前端架构，配置每日凌晨 3 点自动拉取增量静态生成：
```bash
0 3 * * * bash /opt/myblog/scripts/rebuild-static.sh
```

---

## 五、常用运维命令

```bash
# ============ 运行端到端自动化验证脚本 ============
bash scripts/verify-sqlite.sh

# ============ 加密导出 SQLite 数据库 ============
bash scripts/sqlite-export.sh --exclude page_view -o /tmp/migration.sql.gz.enc

# ============ 解密导入 SQLite 数据库 ============
bash scripts/sqlite-import.sh /opt/myblog/db/blog.db /tmp/migration.sql.gz.enc

# ============ SQLite 热备份 ============
sqlite3 /opt/myblog/blog.db ".backup /opt/myblog/backups/blog-$(date +%Y%m%d).db"
```

---

## 六、未来规划 (Roadmap)

| 优先级 | 功能模块 | 说明 |
|:---:|---|---|
| 🟡 中 | `page_view` 历史数据清理 | 提供自动按月归档与定期清理脚本 |
| 🟡 中 | 文章评论树形递归 | 支持二级评论回复与树状结构展现 |
| 🟡 中 | Redis 缓存扩展 | 拓展文章详情与热门标签组的 Redis 缓存层 |
| 🟢 低 | 图片 WebP 转换与懒加载 | 前台图片加载体验升级 |
| 🟢 低 | GitHub Actions CI/CD | 自动化测试与打包部署流水线 |

---

## 七、开源协议与贡献

本项目采用 [MIT License](LICENSE) 协议开源。欢迎提交 Issue 或 Pull Request！
