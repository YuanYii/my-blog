# AGENTS.md

> my-blog 项目的 agent 上下文,轻量版（~200 行）。
> 供 OpenCode / Codex / Cursor / Aider / Devin / Gemini CLI 等 agent 启动时自动加载。
> 消费规范：https://agents.md

---

## 1. TL;DR

- **栈**：Spring Boot 2.7（多模块）+ Nuxt 3 前后端分离；MySQL 8.0 + Redis 7.x；JWT 鉴权；API 前缀 `/api/v1`
- **入口**：先看 §10 关键文件索引 + §8 用户偏好（强制遵守） + §9 安全红线

---

## 2. 项目结构

- `backend/` — Spring Boot 多模块
  - `blog-common` / `blog-auth` / `blog-article` / `blog-comment` / `blog-settings` / `blog-app`
- `frontend/` — Nuxt 3
  - `pages/` 13 个（5 公开 + 8 admin）
  - `layouts/admin.vue` — **admin 布局 + 鉴权兜底**
  - `middleware/admin-auth.ts` — **SSR-safe 鉴权**
  - `composables/` — `useApi` / `useAuth` / `useAdminApi` / `useDialog` / `useToast` / `useDevice` / `useAdminMeta`
  - `components/` / `plugins/` / `nuxt.config.ts`
- `docs/` — `设计文档/` / `接口契约审计报告.md` / `阿里云部署方案.md` / `changelogs/` / `audits/` / `sql/` / `docker/` / `nginx/`
- `README.md` / `AGENTS.md`（本文件）

---

## 3. Tech Stack

| 层 | 选型 | 备注 |
|---|---|---|
| 后端 | Java 1.8 + Spring Boot 2.7.18 + MyBatis-Plus 3.4.3.4 | **环境降级**（原始目标 Java 21 / SB 3.3.5 / MP 3.5+），详见 §4.1 |
| DB | MySQL 8.0（utf8mb4）+ Redis 7.x | JJWT 0.11.5 + Hutool 5.8.27 + Lombok |
| 前端 | Nuxt 3 ^3.13 + Tailwind ^3.4 + TS ^5.5 + Pinia | npm（无 pnpm 改用） |
| 工具 | Playwright + Chromium（设计稿审计、端到端） | — |

---

## 4. 关键决策

### 4.1 环境降级链（**不要升级**）
开发机只有 JDK 1.8。**不要试图升级** Java / Spring Boot / MyBatis-Plus / 包管理工具到"原始目标"版本——会破坏所有依赖。

### 4.2 字符编码：双重 UTF-8
**症状**：`GET /api/v1/articles/categories` 返回 `"name":"æŠ€æœ¯"`（API 端乱码）。
**根因**：`docker exec mysql ... < blog.sql` 默认走 latin1，中文被双重编码写入 utf8mb4。
**修复 SQL** 见 `docs/sql/migrations/20260608_fix_double_utf8.sql`（不删表，可逆）。
**后续导入必须**：
```bash
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/blog.sql
```

### 4.3 admin-auth SSR 修复（BUG-001，**不要回退**）
**症状**：直接访问 `/admin/*`，已登录用户被踢回 `/admin/login`。
**根因**：`admin-auth.ts` 在 SSR 阶段（`import.meta.server`）调 `localStorage.getItem('token')`——服务端无 localStorage。
**修复**：
1. `admin-auth.ts` 头部加 `if (import.meta.server) return`
2. `layouts/admin.vue` 加 `onMounted` 兜底，client `initAuth()` 后未登录再 `router.replace('/admin/login')`

### 4.4 简化项（首版 MVP 决策）
- Controller 直调 Mapper（无 Service 层）
- 仪表盘直查 DB
- 评论列表扁平（无 parent_id 树形）
- 单体单 DB
- 详细 changelog 见 `docs/changelogs/`（v2.0.0 → v2.5.0）

---

## 5. 常用命令

```bash
# 1. 启 MySQL + Redis（dev 用 docker run，prod 看 docs/阿里云部署方案.md）
docker run -d --name blog-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=blog \
  -p 3306:3306 -v blog-mysql-data:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
docker run -d --name blog-redis -p 6379:6379 redis:7-alpine

# 2. 导入数据（**必须带 --default-character-set=utf8mb4**，详见 §4.2）
docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/blog.sql

# 3. 启动
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm run dev   # dev 模式，HMR；prod build 见 docs/阿里云部署方案.md

# 4. 健康检查
curl http://localhost:8080/api/v1/health
# 期望 {"code":200,"data":{"status":"UP",...}}
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
- **数据库** 表/字段 snake_case；主键 `id` BIGINT；时间 `created_at` / `updated_at` DATETIME；逻辑删除 `deleted` TINYINT

---

## 8. 用户偏好（**强制遵守**）

| 偏好 | 规则 |
|---|---|
| **8.1 需求跟踪文档 / 报告：先看后写** | 涉及 BUG/OPT/DEV 项时，**先在对话里展示完整内容**，等用户确认后再落盘 |
| **8.2 提示词优化：不落盘** | "优化提示词"任务**只在对话里输出**——不写文件（无论是否提示"输出为 MD"） |
| **8.3 过程文件：放项目目录** | 主动写的 draft / 临时分析 / 截图 / 比对资料一律写到**对应项目目录下**（docs/、scripts/、.audit/、tmp/、screenshots/ 等），或只输出在对话里。**不写到 `~/`、`~/Desktop/`、`~/.mavis/` 等家目录**。mavis 系统自管文件（scratchpad / memory / session log）不受此约束 |

跨项目 / 跨会话 / 跨场景适用。

---

## 9. 安全红线

- **不要在 commit / 文档 / 日志中暴露 secrets**（DB 密码、JWT secret、API key、密码 hash）
- **不要 SQL 字符串拼接**（用 MyBatis-Plus Wrapper 或 `@Select` 注解）
- **不要在前端 store / localStorage 存明文密码**（只用 token）
- 默认 admin 账号 `admin / 123456`（dev 占位）——**生产环境必须改 + BCrypt 加密**

### 9.1 应用层防护策略（2026-06-07 决策：不引入应用层加密）

**决策**：不引入应用层加密（前端预 hash / 端到端 / 国密），只靠**传输层 HTTPS + 设备白名单**。

- **传输层**：HTTPS + HSTS（`nginx-https.conf` listen 443 + `Strict-Transport-Security: max-age=31536000`）
- **应用层**：`admin_device` 表 3 状态（pending/approved/revoked） + token 带 `deviceId` claim + `AdminAuthFilter` 每请求校验
  - 账号密码即使泄漏，**未授权设备**无法登录
  - 已签发 token 在**未授权设备**上**无法使用**（deviceId claim 不匹配）

**未来 agent 不要再提"加 L3 前端 hash / 端到端加密吧"**——这是有意识的安全模型选择，**L1 + L2 + 设备白名单**对个人博客场景已足够。详见 `docs/阿里云部署方案.md`。

**用户原话**（2026-06-07）："A. 不改了，我有设备授权，即使别人拿到账号密码也无法登录"。

---

## 10. 关键文件索引

| 路径 | 用途 | 优先级 |
|---|---|---|
| `README.md` | 项目门面 | 🔴 必读 |
| `docs/设计文档/博客系统设计方案.md` | 完整设计 v0.3 | 🔴 必读 |
| `frontend/middleware/admin-auth.ts` | SSR-safe 鉴权（**不要回退 BUG-001 修复**） | 🔴 必读 |
| `frontend/layouts/admin.vue` | admin 布局 + 鉴权兜底 + 全局 Toast/Dialog 容器 | 🔴 必读 |
| `frontend/composables/useDialog.ts` / `useToast.ts` | 替代浏览器原生 alert/confirm/prompt | 🔴 必读 |
| `frontend/components/GlobalDialog.vue` | confirm + prompt 共用对话框 | 🔴 必读 |
| `docs/changelogs/` | 版本变更记录（v2.0.0 → v2.5.0） | 🟠 重要 |
| `docs/接口契约审计报告.md` | API 100% 一致 | 🟠 重要 |
| `docs/阿里云部署方案.md` | 部署方案 | 🟠 重要 |
| `AGENTS.md` | **本文件** | 🔴 必读 |

---

## 11. 备注

- 之前版本的"常见任务 / 踩坑记录 / 调试技巧 / 维护记录"已删除（内容散落 `docs/changelogs/` + 各文件注释里）
- agent 启动建议顺序：1) 读本文件 → 2) `docs/changelogs/` 最新版 → 3) 关键文件索引中的 🔴 必读项 → 4) 接到任务时再按需 Read
