# 个人博客系统 (MyBlog)

> 基于 **Spring Boot 2.7 + Nuxt 3** 前后端分离的轻量高能个人博客系统。
> 专为 **1C2G 低配服务器** 深度优化：默认 SQLite 数据库（零开销）+ 前端全静态预渲染（省 150-250MB 内存）+ 极速单机部署。

---

[在线 Demo](https://blog.coreyai.cn/) · [快速部署](#二快速部署) · [技术架构](#三技术架构) · [运维指南](#四生产部署与运维) · [变更日志](docs/changelogs/)

---

## ✨ 核心特性

- **前台公开体验**：静态 SEO 预渲染与渐进增强、文章浏览、归档、分类、标签云、全局搜索、RSS 订阅。
- **全能管理后台**：
  - **内容管理**：文章 Markdown 编辑、批量 ZIP 模板导入、一文一附件管理、分类与标签维护、评论审核。
  - **系统运维**：仪表盘 KPI 聚合与访客 IP 归属地统计、设备白名单绑定、全站 IP 限流与封禁、全局审计日志。
  - **数据与升级**：数据库 AES-256 加密备份与恢复、管理后台 Web UI 一键升级（含流式日志与失败回滚）。
- **极简极轻架构**：默认嵌入式 SQLite 数据库，无需安装复杂的数据库服务，1C2G VPS 即可流畅运行。

---

## 一、核心功能矩阵

| 模块 | 功能说明 |
|------|----------|
| **前台公开** | 首页 Hero / 文章详情（SEO 渐进增强）/ 归档 / 分类 / 标签云 / 关于页 / 全局搜索 / RSS 订阅 |
| **管理后台** | 仪表盘（KPI + 30天趋势 + 访客 IP 归属地）/ 文章增删改与 ZIP 导入 / 附件管理 / 评论审核 / 分类与标签 / 站点设置 / 审计日志 / 设备白名单 / 数据备份与恢复 / Web UI 一键升级 |
| **系统安全与性能** | JWT 鉴权 / 设备绑定 / API 路由白名单 / 日志 traceId 全链路串联 / IP 限流封禁 / 前端全静态预渲染 |

---

## 二、快速部署 (全新服务器 · 约 5-10 分钟)

### 2.1 服务器直接部署 (纯净宿主机 · 推荐)

直接在 Linux 宿主机运行部署脚本，自动完成依赖安装、资源下载、Nginx 反代与服务注册：

```bash
# 1) 下载部署脚本（物料仓库已公开，无需配置 Token 或 GitHub 账号）
curl -L https://github.com/YuanYii/my-blog-prov/releases/latest/download/deploy-server.sh -o deploy-server.sh
chmod +x deploy-server.sh

# 2) 一键部署（自动完成：依赖安装 → 下载 Release → 部署服务 → Nginx 反代 → 健康检查）
GITHUB_REPO=YuanYii/my-blog-prov sudo -E ./deploy-server.sh

# 3) 部署验收（返回 JSON 结果即成功）
curl -s http://127.0.0.1/api/v1/health
```

> 💡 **访问指南**：
> - 浏览器打开 `http://<服务器IP>/` 即可访问前台页面。
> - 默认管理后台：`http://<服务器IP>/admin/login`，初始账号/密码：`admin / 123456`。

---

### 2.2 服务器 Docker 容器化部署

如果您希望将整个系统隔离在 Docker 容器中运行，可使用项目内置的容器机制：

```bash
# 1) 创建部署容器（自动构建镜像并启动容器）
GITHUB_REPO=YuanYii/my-blog-prov sudo DEPLOY_MODE=docker-create -E ./deploy-server.sh

# 2) 容器内部业务初始化
GITHUB_REPO=YuanYii/my-blog-prov sudo DEPLOY_MODE=docker-init -E ./deploy-server.sh

# 3) 部署验收（容器暴露端口：28080 后端 / 28000 前端 Nginx）
curl -s http://127.0.0.1:28080/api/v1/health
```

---

## 三、技术架构

### 3.1 技术栈总览

| 层级 | 选型 | 说明 |
|------|------|------|
| **后端框架** | Spring Boot 2.7.18 (Java 8) | 多模块单体架构 |
| **持久层 / 数据库** | MyBatis-Plus + SQLite (默认) / MySQL (可选) | SQLite 3.45 (默认零内存开销) |
| **缓存 / 鉴权** | Redis 7.x + JJWT | JWT + 设备白名单机制 |
| **前端框架** | Nuxt 3 (Vue 3 + TypeScript) | SSG 全静态预渲染 |
| **前端样式** | Tailwind CSS + Vanilla CSS | 响应式现代化界面 |
| **运维环境** | Nginx + OpenJDK 8 + Systemd | 零 Docker 纯净宿主机运行 |

### 3.2 系统架构拓扑

```
┌───────────────────────────────────────────────────────────┐
│                        用户浏览器                          │
│               https://yourblog.cn (HTTPS)                 │
└─────────────────────────────┬─────────────────────────────┘
                               │ HTTP / HTTPS
                               ▼
┌───────────────────────────────────────────────────────────┐
│                          Nginx                            │
│   - HTTPS 证书与 HTTP 301 重定向                           │
│   - 静态页面 serve (/opt/myblog/frontend)                 │
│   - 文件上传直传 (/uploads/ & /attachments/)               │
│   - /api/* 接口请求反向代理至 :8080                        │
└─────────────────────────────┬─────────────────────────────┘
                               ▼
┌───────────────────────────────────────────────────────────┐
│                    Spring Boot Backend                    │
│   - Filter 过滤器链: TraceId → IP限流 → 设备鉴权          │
│   - AOP 审计日志拦截 & 业务逻辑服务                          │
└──────────────────────┬─────────────────────────────┬──────┘
                       ▼                             ▼
            ┌────────────────────┐        ┌────────────────────┐
            │   SQLite blog.db   │        │     Redis 7.x      │
            │   (默认嵌入式库)    │        │  (限流/封禁/缓存)   │
            └────────────────────┘        └────────────────────┘
```

### 3.3 代码结构规范

```text
my-blog/
├── backend/                              # Spring Boot 多模块后端
│   ├── blog-common/                      # 公共工具类 (Result, ExceptionHandler, TraceId)
│   ├── blog-auth/                        # 认证授权 (JWT, 设备白名单, IP封禁, 审计日志)
│   ├── blog-article/                     # 文章 / 分类 / 标签 / 浏览统计
│   ├── blog-comment/                     # 评论管理
│   ├── blog-settings/                    # 站点设置 / 文件上传 / 数据备份与恢复
│   └── blog-app/                         # 启动入口与 UpgradeController
│
├── frontend/                             # Nuxt 3 静态前端
│   ├── assets/ & components/             # 设计 System 与 UI 组件
│   ├── composables/                      # 组合式 API (useAuth, useAdminApi 等)
│   ├── pages/                            # 前台页面 + Admin 后台 + Settings 路由
│   └── nuxt.config.ts                    # 预渲染与打包配置
│
├── scripts/                              # 运维自动化脚本 (一键部署/加密备份/导入导出)
└── docs/                                 # 架构设计文档、Changelog 及 SQL Schema
```

---

## 四、生产部署与运维

> 详细部署手册与更多复杂场景说明请查阅：[`docs/项目部署操作手册.md`](docs/项目部署操作手册.md)。

### 4.1 核心运维命令速查

```bash
# ============ 1. 服务器代码/版本升级 ============
# 保留业务数据，自动跑增量 SQL 迁移
GITHUB_REPO=YuanYii/my-blog-prov DEPLOY_MODE=full sudo -E ./deploy-server.sh

# ============ 2. 服务状态与日志查看 ============
journalctl -u myblog -f               # 实时查看后端服务运行日志
tail -f /opt/myblog/logs/app.log       # 查看应用标准输出日志
systemctl restart myblog               # 重启后端服务

# ============ 3. 数据库备份与导入 ============
bash scripts/sqlite-export.sh -o /tmp/migration.sql.gz.enc  # 加密导出 SQLite
bash scripts/sqlite-import.sh /opt/myblog/db/blog.db /tmp/migration.sql.gz.enc # 解密导入 SQLite
```

### 4.2 部署注意事项与环境变量

| 环境变量 / 配置 | 说明 |
|-----------------|------|
| **`GITHUB_REPO`** | 部署物料源仓库，设置为 `YuanYii/my-blog-prov` |
| **`JWT_SECRET`** | 首次部署自动生成，位于 `/etc/myblog/myblog.env`，**切勿随意修改**，否则已登录用户将被踢出 |
| **`CORS_ORIGINS`** | 跨域域名配置，编辑 `/etc/myblog/myblog.env` 设置您的真实域名 |
| **`ENABLE_HTTPS`** | 设置 `ENABLE_HTTPS=1 HTTPS_DOMAIN=域名 HTTPS_EMAIL=邮箱` 可自动申请并配置 Let's Encrypt 证书 |

---

## 五、未来路线图 (Roadmap)

- [ ] **数据清理**：提供按月归档与 `page_view` 历史日志清理工具
- [ ] **评论体验优化**：支持二级评论回复与树状结构展现
- [ ] **性能优化**：扩展文章详情与热门标签组的 Redis 二级缓存
- [ ] **资源优化**：前台图片 WebP 自动转换与延迟加载

---

## 六、开源协议

本项目基于 [MIT License](LICENSE) 协议开源。欢迎提交 Issue 与 Pull Request！
