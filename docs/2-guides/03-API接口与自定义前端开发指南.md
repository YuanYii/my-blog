# my-blog 后端 API 接口与自定义前端开发指南

> 本文档为 `my-blog` 系统（v6.1.0）面向自定义前端开发人员整理的全量 RESTful API 接口规格说明。
> 开发人员可依据本文档提供的基准地址、鉴权标头、入参格式与响应 JSON 结构，独立开发第三方前端（如小程序、桌面客户端、静态博客主题等）。

---

## 一、 通信协议与交互约定

### 1. 网络基准地址 (Base URL)
所有 API 接口均带有版本号统一前缀：
`http(s)://<your-domain>/api/v1`

### 2. HTTP 请求头 (Request Headers)
- **通用请求头**：
  - `Content-Type: application/json` （文件上传使用 `multipart/form-data`）
- **受保护接口鉴权标头**（所有 `/admin/*` 前缀接口及部分受保护接口必须带上）：
  - `Authorization: Bearer <jwt_token>` （用户登录 `/auth/login` 获取）
  - `X-Device-Id: <device_uuid>` （当前设备的 UUID 标识，必须在后台通过设备审批）

### 3. 统一响应包结构 (Envelope Response Standard)
后端所有 JSON 接口一律返回固定结构：

```json
{
  "code": 200,       // 业务状态码 (200 成功，400 参数错误，401 未登录/Token过期，403 无权限，2001 设备未授权，500 系统异常)
  "data": { ... },   // 业务载荷对象 / 数组 / 分页器
  "message": "ok"    // 状态或错误描述字符串
}
```

---

## 二、 全量 API 接口清单 (92 端点)

### 1. 认证、设备授权与审计日志模块 (`blog-auth`)

| 接口请求地址 | 访问方式 | 是否认证 | 入参方式及字段 | 出参内容 JSON 结构 |
|---|---|---|---|---|
| `/auth/login` | `POST` | 公开 | Body JSON: `{ username, password, deviceId, deviceName }` | 签发令牌及用户信息: `{ token, user: { id, username, nickname, avatar } }` |
| `/auth/me` | `GET` | 鉴权 | 无 | 当前管理员信息对象: `{ id, username, nickname, avatar, role }` |
| `/auth/me/password` | `PUT` | 鉴权 | Body JSON: `{ oldPassword, newPassword }` | 提示字符串/成功反馈 |
| `/public/device/check` | `GET` | 公开 | Query 参数: `?deviceId=xxx` | 设备授权状态: `{ status: "approved" \| "pending" \| "revoked", deviceId }` |
| `/admin/devices` | `GET` | 鉴权 | Query 分页: `?page=1&size=10` | 设备列表: `[{ id, deviceId, deviceName, status, lastLoginIp, lastLoginTime }]` |
| `/admin/devices/{id}/approve` | `PUT` | 鉴权 | Path 参数: `{id}` | 授权变更提示对象 |
| `/admin/devices/{id}/revoke` | `PUT` | 鉴权 | Path 参数: `{id}` | 吊销成功提示 |
| `/admin/devices/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 物理删除设备记录成功提示 |
| `/admin/ip-bans` | `GET` | 鉴权 | Query 参数: `?page=1&size=10&status=active` | 自动封禁 IP 列表: `[{ id, ip, reason, banCount, unbanTime }]` |
| `/admin/ip-bans/{id}/unban` | `PUT` | 鉴权 | Path 参数: `{id}` | 手动解封反馈 |
| `/admin/audit-logs` | `GET` | 鉴权 | Query 参数: `?page=1&size=10&target=xxx&operation=xxx&username=xxx` | 审计日志分页对象: `{ records: [{ id, username, operation, target, ip, createdAt }], total }` |
| `/admin/audit-logs/targets` | `GET` | 鉴权 | 无 | 可用审计模块白名单字符串列表: `["article", "comment", "device", ...]` |
| `/admin/audit-logs/operations` | `GET` | 鉴权 | 无 | 可用操作类型字符串列表: `["CREATE", "UPDATE", "DELETE", "LOGIN"]` |

---

### 2. 文章、分类、标签与附件模块 (`blog-article`)

| 接口请求地址 | 访问方式 | 是否认证 | 入参方式及字段 | 出参内容 JSON 结构 |
|---|---|---|---|---|
| `/articles` | `GET` | 公开 | Query 参数: `?page=1&size=10&categoryId=1&tagId=2&keyword=xxx` | 文章前台分页列表: `{ records: [{ id, title, slug, summary, cover, views, isPinned, category, tags }], total, pages }` |
| `/articles/{slug}` | `GET` | 公开 | Path 参数: `{slug}` | 前台文章完整内容详情: `{ id, title, slug, content, views, category, tags, attachment }` |
| `/articles/archives` | `GET` | 公开 | 无 | 归档分组数据: `[{ year: "2026", month: "08", count: 5, articles: [...] }]` |
| `/articles/id/{id}` | `GET` | 鉴权 | Path 参数: `{id}` | 编辑器用文章详情实体 (含未发布草稿) |
| `/articles/admin/all` | `GET` | 鉴权 | Query 参数: `?page=1&size=10&status=all&keyword=xxx` | 后台文章全量分页列表 (包含 `viewCount3d` 近 3 天对比数据) |
| `/articles` | `POST` | 鉴权 | Body JSON: `{ title, slug, content, summary, categoryId, tagIds, isPinned, status }` | 新建文章的主键自增对象: `{ id: 10, slug: "xxx" }` |
| `/articles/{id}` | `PUT` | 鉴权 | Path 参数: `{id}` + Body JSON (包含对应修改字段) | 更新后的文章详细实体 |
| `/articles/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 软删除 (逻辑删除) 提示 |
| `/articles/categories` | `GET` | 公开 | 无 | 可见分类列表: `[{ id, name, slug, articleCount }]` |
| `/articles/categories/all` | `GET` | 鉴权 | 无 | 后台包含已隐藏 (`visible=0`) 的分类完整列表 |
| `/articles/categories/with-count` | `GET` | 公开 | 无 | 带精确已发布文章总数计算的分类列表 |
| `/articles/categories` | `POST` | 鉴权 | Body JSON: `{ name, slug, description, visible }` | 创建后的分类实体 |
| `/articles/categories/{id}` | `PUT` | 鉴权 | Path 参数: `{id}` + Body JSON | 修改后的分类实体 |
| `/articles/categories/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 删除分类成功提示 |
| `/articles/tags` | `GET` | 公开 | 无 | 标签列表: `[{ id, name, articleCount }]` |
| `/articles/tags` | `POST` | 鉴权 | Body JSON: `{ name: "Java" }` | 新建标签实体 |
| `/articles/tags/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 删除标签结果提示 |
| `/articles/admin/preview/{slug}` | `GET` | 鉴权 | Path 参数: `{slug}` | 草稿文章实时预览渲染结构 |
| `/articles/admin/batch-delete` | `POST` | 鉴权 | Body JSON: `{ ids: [1, 2, 3] }` | 批量软删除结果数提示 |
| `/articles/admin/articles/{id}/hard` | `DELETE` | 鉴权 | Path 参数: `{id}` | 物理永久删除文章成功提示 |
| `/articles/admin/articles/{id}/restore` | `PUT` | 鉴权 | Path 参数: `{id}` | 软删除恢复结果提示 |
| `/articles/admin/import-html` | `POST` | 鉴权 | Multipart Form: `file` (.html 文件) | HTML 转 Markdown 转换后的文章实体 |
| `/articles/{id}/attachment` | `GET` | 公开 | Path 参数: `{id}` | 文章附件流式二进制下载流 |
| `/admin/articles/{id}/attachment` | `POST` | 鉴权 | Path 参数: `{id}` + Multipart Form: `file` | 附件关联对象: `{ id, filename, url, size }` |
| `/admin/articles/{id}/attachment` | `DELETE` | 鉴权 | Path 参数: `{id}` | 附件软删除提示 |
| `/admin/attachments` | `GET` | 鉴权 | Query 参数: `?page=1&size=10` | 全局文章附件列表分页对象 |
| `/admin/attachments/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 硬删除物理文件与数据库记录提示 |
| `/admin/attachments/{id}/restore` | `PUT` | 鉴权 | Path 参数: `{id}` | 恢复软删附件提示 |

---

### 3. 互动评论模块 (`blog-comment`)

| 接口请求地址 | 访问方式 | 是否认证 | 入参方式及字段 | 出参内容 JSON 结构 |
|---|---|---|---|---|
| `/comments` | `GET` | 公开 | Query 参数: `?articleId=1&page=1&size=20` | 前台已审核评论列表: `[{ id, nickname, content, createdAt, reply }]` |
| `/comments` | `POST` | 公开 | Body JSON: `{ articleId, nickname, email, website, content }` | 提交评论成功反馈 (`status=0` 待审核) |
| `/comments/admin` | `GET` | 鉴权 | Query 参数: `?page=1&size=10&status=0` | 后台评论全量记录分页 (包含所属文章标题) |
| `/comments/{id}/status` | `PUT` | 鉴权 | Path 参数: `{id}` + Body JSON: `{ status: 1, reply: "回复文本" }` | 审核更新后的评论实体 |
| `/comments/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 评论删除成功提示 |

---

### 4. 站点配置、文件上传与备份恢复 (`blog-settings`)

| 接口请求地址 | 访问方式 | 是否认证 | 入参方式及字段 | 出参内容 JSON 结构 |
|---|---|---|---|---|
| `/public/profile` | `GET` | 公开 | 无 | 关于页个人公开资料对象 |
| `/public/settings/{section}` | `GET` | 公开 | Path 参数: `{section}` (`blog` \| `social` \| `theme`) | 指定模块的公开配置 JSON |
| `/admin/settings/profile` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 个人详细资料查询/更新 |
| `/admin/settings/password` | `PUT` | 鉴权 | Body JSON: `{ oldPassword, newPassword }` | 密码重置反馈 |
| `/admin/settings/social` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 社交链接列表 `[{ name: "GitHub", url: "..." }]` |
| `/admin/settings/preferences` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 后台显示及交互偏好配置字典 |
| `/admin/settings/blog` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 博客站点信息 (名称, 描述, 备案号, 图标等) |
| `/admin/settings/theme` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | CSS 全局配色与字号主题配置 |
| `/admin/settings/advanced` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 自定义注入 CSS/JS 脚本配置 |
| `/admin/settings/techstack` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 关于页技术栈图标与熟练度列表 |
| `/admin/settings/experience` | `GET` / `PUT` | 鉴权 | Body JSON (`PUT` 模式时) | 履历与履历时间线数据 |
| `/admin/uploads` | `POST` | 鉴权 | Multipart Form: `file` | 文件上传对象: `{ url: "/uploads/2026/08/xxx.png", filename, size }` |
| `/admin/backup/run` | `POST` | 鉴权 | Body JSON (`BackupConfigDTO` 可选) | 启动异步数据备份响应: `{ id, status: "RUNNING", backupTag }` |
| `/admin/backup/list` | `GET` | 鉴权 | Query 参数: `?page=1&size=10` | 数据库及文件历史备份记录 |
| `/admin/backup/{id}` | `GET` | 鉴权 | Path 参数: `{id}` | 备份包细节 (体积/Hash/状态/文件清单) |
| `/admin/backup/{id}` | `DELETE` | 鉴权 | Path 参数: `{id}` | 物理删除冷备份数据包提示 |
| `/admin/backup/sync` | `POST` | 鉴权 | 无 | 从远程 Release 拉取同步备用包回显 |
| `/admin/restore/run` | `POST` | 鉴权 | Body JSON: `{ backupId: 5 }` | 启动进程内数据恢复流程状态对象 |
| `/admin/restore/list` | `GET` | 鉴权 | Query 参数: `?page=1&size=10` | 历史数据恢复日志清单 |
| `/admin/restore/{id}` | `GET` | 鉴权 | Path 参数: `{id}` | 恢复差异审计详情结构 |

---

### 5. 仪表盘、系统升级、SEO 与设施模块 (`blog-app`)

| 接口请求地址 | 访问方式 | 是否认证 | 入参方式及字段 | 出参内容 JSON 结构 |
|---|---|---|---|---|
| `/health` | `GET` | 公开 | 无 | 服务可用性文本响应: `"OK"` |
| `/admin/health` | `GET` | 鉴权 | 无 | SQLite/MySQL 及 Redis 连通状态: `{ status: "UP", db: "SQLite", redis: "CONNECTED" }` |
| `/admin/dashboard` | `GET` | 鉴权 | 无 | 仪表盘聚合数据: `{ kpi: { articleCount, totalViews, commentCount }, viewTrend: [...], topVisitorIps: [{ ip, location, pv }] }` |
| `/admin/api-whitelist` | `GET` / `POST` | 鉴权 | Body JSON (`POST` 模式) | API 路径免鉴权白名单配置列表与新增对象 |
| `/admin/api-whitelist/{id}` | `PUT` / `DELETE` | 鉴权 | Path 参数: `{id}` | 更新或删除 API 免鉴权前缀节点 |
| `/admin/api-whitelist/refresh` | `POST` | 鉴权 | 无 | 刷新 Backend 内存拦截器匹配白名单 |
| `/admin/upgrade` | `POST` | 鉴权 | Body JSON: `{ version: "v6.1.0" }` | 建立 SSE 通信流，按行推送 Release 拉取与部署 Shell 输出日志 |
| `/admin/upgrade/status` | `GET` | 鉴权 | 无 | 当前升级代理 Agent 状态及版本基线 |
| `/admin/upgrade/versions` | `GET` | 鉴权 | 无 | 可在线下载升级的版本号 Tag 列表 |
| `/admin/upgrade/records` | `GET` | 鉴权 | Query 参数: `?page=1&size=10` | 升级历史记录分页 |
| `/admin/upgrade/rollback` | `POST` | 鉴权 | Body JSON: `{ targetVersion: "v6.0.2" }` | 触发回滚到指定 Release 的任务确认 |
| `/public/site-flags` | `GET` | 公开 | 无 | 全局前台功能开关字典: `{ allowComments: true, enableSearch: true }` |
| `/rss` & `/rss.xml` | `GET` | 公开 | 无 | 二进制流: `application/rss+xml` |
| `/sitemap.xml` | `GET` | 公开 | 无 | 包含最新已发布文章及路由的 Sitemap XML 流 |
| `/seo/post/{slug}` | `GET` | 公开 | Path 参数: `{slug}` | 搜索引擎爬虫用 SSR 预构建 HTML 内容渲染流 |
