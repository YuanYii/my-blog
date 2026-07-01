# 集成测试报告 · 20260701 · 本地 Docker 升级验证

**测试环境**：myblog-sim 容器（localhost:28000 + 28080）
**生成时间**：2026-07-01 11:30（北京时间 UTC+8）
**升级包**：blog-app.jar 60735741 bytes + frontend build 29 routes
**测试范围**：35 个集成测试用例 + 1 个开发期发现并修复的 bug

---

## 部署状态

| 检查项 | 结果 |
|--------|------|
| 后端 jar 部署 | ✅ /opt/myblog/blog-app.jar (60735741 bytes, Jul 1 02:55) |
| 前端 build 部署 | ✅ /opt/myblog/frontend/ (Jul 1 02:58, 8 prerender routes + 1863-byte SPA shell) |
| myblog (Spring Boot) | ✅ RUNNING pid 62333 |
| nginx | ✅ RUNNING pid 58290 |
| Redis（容器内）| ✅ RUNNING pid 10284 |
| DB schema article_attachment | ✅ 已创建（手动初始化 + 双方言 schema 已同步）|
| api_whitelist 2 条 attachment 路径 | ✅ id 38/39 |
| /data/attachments 目录 | ✅ 已创建（prod profile 默认路径）|

---

## 测试用例结果（35 / 35 通过，1 个开发期 bug 发现并修复）

### 完整 happy path（TEST 1-18）

| # | 用例 | 结果 |
|---|------|------|
| 1 | 上传 zip 附件 | ✅ 200 + DB id=5 + 磁盘 232 bytes |
| 2 | 重复上传（一文一附件 UNIQUE 约束）| ✅ 400「请先删除旧附件」|
| 3 | 公开下载（流式响应 + RFC 5987 双字段）| ✅ 200 + Content-Length:232 + filename*=UTF-8'' |
| 4 | DB + 磁盘双落确认 | ✅ `/data/attachments/2026/07/20260701-21e81626.zip` |
| 5 | 软删 DELETE /admin/articles/{id}/attachment | ✅ 200 |
| 6 | 软删后公开下载 | ✅ 410「附件已删除」|
| 7 | 软删后磁盘文件保留 | ✅ 文件仍在 |
| 8 | 软删后 DB deleted=1 | ✅ |
| 9 | 软删后再上传（约束生效）| ✅ 400「请先删除旧附件」|
| 10 | 后台列表 3 tab（未删/已删/全部）| ✅ 0 / 1 / 1 正确 |
| 11 | 恢复 PUT /admin/attachments/{id}/restore | ⚠️ **发现 bug — 见下文修复段** |
| 12 | 恢复后 DB deleted=0 | ⚠️ 修复后 ✅ |
| 13 | 恢复后下载 | ✅ 200 + Content-Length:232 |
| 14 | 硬删 DELETE /admin/attachments/{id} | ✅ 200 |
| 15 | 硬删后 DB 记录消失 | ✅ |
| 16 | 硬删后磁盘文件清理 | ✅ |
| 17 | 硬删后下载（应 1001）| ✅ 1001「附件不存在」|
| 18 | 硬删后再上传（article 已无 attachment）| ✅ 200 + 新 id=6 |

### 边界 case（TEST 19-28）

| # | 用例 | 结果 |
|---|------|------|
| 19 | 非 .zip 类型拒绝 | ✅ 400「仅允许 zip 格式」|
| 20 | 空文件拒绝 | ✅ 400「文件不能为空」|
| 21 | 5.5MB zip 超业务上限 | ✅ 400「文件超过 5MB 上限」|
| 22 | 5.1MB 略超业务上限（multipart 通过）| ✅ 400「文件超过 5MB 上限」|
| 23 | 上传到不存在的文章 | ✅ 1001「文章不存在」|
| 24 | 公开下载不存在的文章 | ✅ 1001「附件不存在」|
| 25 | 公开下载没附件的文章 | ✅ 1001「附件不存在」|
| 26 | 硬删不存在的 attachment id | ✅ 1001「附件不存在」|
| 27 | 恢复不存在的 attachment id | ✅ 1001「附件不存在」|
| 28 | 未鉴权访问 admin 接口 | ✅ 401「未登录」|

### 级联删除 + 列表 + 中文文件名（TEST 29-30）

| # | 用例 | 结果 |
|---|------|------|
| 29 | 级联删除：删文章 → 附件自动软删 + 410 + 磁盘保留 + 硬删清理 | ✅ 全部通过 |
| 30 | 中文文件名上传 + RFC 5987 双字段 | ✅ `filename*=UTF-8''%E4%B8%AD%E6%96%87...` |

### 分页 + nginx + 前端（TEST 31-35）

| # | 用例 | 结果 |
|---|------|------|
| 31 | 后台列表分页（page=1/2, size=2）| ✅ total=3 records=2/1 totalPages=2 |
| 32 | nginx 静态服务 8 个路由可达（admin/posts/login/attachments 等）| ✅ 全部 200 + 同尺寸 SPA shell 1863 bytes |
| 33 | nginx 反代 /api/v1 → 后端 | ✅ 200 + 完整 health 响应 |
| 34 | 前端 bundle 含 attachments-page | ✅ `_nuxt/syKSSq7o.js` 命中 |
| 35 | 上传文件 ↔ 下载文件字节一致 | ✅ diff 无差异（zip magic bytes 验证）|

---

## 开发期发现并修复的 bug

### BUG-20260701-RESTORE · restore API 返 200 但 DB 未更新

**症状**：
- `PUT /api/v1/admin/attachments/{id}/restore` 返回 200 OK
- DB 中记录的 `deleted` 字段未变（仍是 1）
- API 调用方以为恢复成功，实际文章页附件仍显示"已删除"

**根因**：
MyBatis-Plus 全局配置 `mybatis-plus.global-config.db-config.logic-delete-field: deleted` 不仅给 SELECT/DELETE 自动加 `AND deleted=0` WHERE，**也给 UPDATE 加**。

restore 是 `deleted=1 → 0` 的转换，自动加 `AND deleted=0` 后 WHERE 永远不匹配，UPDATE 实际影响 0 行——但 API 仍返 200 OK（MyBatis-Plus 默认 UPDATE 返回 affected rows，**调用方未检查返回值**）。

**修复**（`AttachmentService.java`）：
```java
// 原代码（bug）— attachmentMapper.update(null, uw)
//   MyBatis-Plus 自动加 AND deleted=0 WHERE → 软删记录 UPDATE 不匹配
UpdateWrapper<Attachment> uw = new UpdateWrapper<>();
uw.eq("id", id).set("deleted", 0).set("updated_at", now);
attachmentMapper.update(null, uw);

// 修复后 — 用 JdbcTemplate 直 UPDATE 绕过
int updated = jdbc.update(
    "UPDATE article_attachment SET deleted = 0, updated_at = ? WHERE id = ?",
    now, id);
if (updated == 0) {
    log.warn("附件恢复无更新：id={} (可能 record 已不存在)", id);
}
```

**修复后实测**：
```
Before:  id=7 article_id=8237 deleted=1
PUT /admin/attachments/7/restore → 200 OK
After:   id=7 article_id=8237 deleted=0  ✓
Download: HTTP=200 SIZE=232  ✓
```

**Stage 5 为什么没发现**：Stage 5 测试时只跑了 happy path 的前 10 个用例（TEST 1-10），后 8 个用例（TEST 11-18）没跑到。**本轮用户要求的"完整集成测试"补上了这个遗漏**。

**影响范围**：仅 restore 一处方法；softDelete（0→1）+ hardDelete（DELETE）+ softDeleteByArticleId 不受影响（前者 0→1 自动 WHERE 匹配；后两者用 JdbcTemplate 已经在本轮 Stage 5 修复过）。

**Stage 6 影响**：原本 Stage 4 静态审计标"0 事实修正"——restore 测试未覆盖到，所以漏检。**Stage 5 集成测试覆盖不够**是 bug 漏过的根因，已在本轮 35 个用例扩展里补上。

---

## 性能基线（粗略）

| 操作 | 实测耗时 |
|------|----------|
| 上传 232 bytes zip | < 100ms |
| 公开下载（含 RFC 5987 头）| < 50ms |
| 列表查询 3 records | < 50ms |
| 分页查询 page=1/2 | < 50ms |
| 软删/恢复/硬删 | < 50ms |
| 级联删除（删文章 + 软删附件）| < 200ms |

无性能瓶颈。

---

## 清理状态

| 项 | 数量 |
|----|------|
| 残留 article | 13（业务文章，未删）|
| 残留 attachment | 0 |
| 残留 disk file | 0 |
| 残留测试文章 | 0（test-restore-fix / page-test-1/2/3 / test-cascade-delete 已删）|

测试副作用：**0**（articles 8237/8238/8239/8240/8241 + 对应 attachment 已全清理）

---

## 关键正面验证

1. **修复 5 个开发期 bug 全部走通**（4 个 Stage 5 发现 + 1 个本轮 Stage 6 集成测试发现）：
   - 修复 1：MyBatis-Plus 全局 logic-delete 绕过（list / getById / getByArticleIdIncludeDeleted 改 JdbcTemplate）
   - 修复 2：SQLite UNIQUE 异常翻译为 400（catch Exception + 字符串匹配）
   - 修复 3：getByArticleId 软删过滤导致下载返 1001 而非 410
   - 修复 4：SQLite JDBC 时间戳解析（parseTimestamp 兼容 ISO 8601 + yyyy-MM-dd HH:mm:ss）
   - **修复 5（本轮新增）**：MyBatis-Plus UPDATE 自动加 AND deleted=0 导致 restore 1→0 永远不匹配 → API 返 200 但 DB 0 行更新

2. **0 事实修正 + 0 测试副作用** — 35 个集成测试用例覆盖 DEV-001 全部 19 项验证 + DEV-002/003 关键路径，myblog-sim 容器实测全部通过。

3. **Stage 6 集成测试发现 Stage 5 漏检的 bug**——证明用户要求的"完整集成测试"必要性。**Stage 5 静态审计 happy path 应扩展到包含 restore/hardDelete 完整闭环**。

---

## 后续建议

1. **未来修复 5 关联清理**：softDelete 用的是 `attachmentMapper.update(null, uw)`，理论上同样可能被 logic-delete 干扰。但因为是 0→1 转换、自动 WHERE 匹配，实际工作。**为一致性建议也改 JdbcTemplate**（本次未改，下次重构统一处理）。

2. **Stage 5 测试扩展建议**：
   - 把 TEST 11-18（restore/hardDelete 完整闭环）加进 stage 5 测试基线
   - 加 fixture 数据：3-5 个 attachment 含不同 deleted 状态，覆盖分页 + 跨页操作

3. **前端验证项（11 项 🔍 仍需 dev 环境人工跑）**：
   - 编辑器上传/删除/重复上传提示
   - 公开页下载按钮 + 中文文件名 + 防多点击锁
   - 软删灰条「原附件已被作者删除」
   - 后台附件管理页 3 tab 切换/badge/恢复/关联文章/查看文章跳公开页
   - 删除文章联动软删提示