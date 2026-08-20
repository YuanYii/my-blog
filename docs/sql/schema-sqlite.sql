-- =============================================================
-- my-blog 完整 schema（SQLite 版）
-- 2026-06-17 v2.6.0：整合所有迁移到单一文件
--
# 执行方式：
#   sqlite3 /data/blog.db < docs/sql/schema-sqlite.sql
--
# 与 MySQL 版差异：
#   - INTEGER PRIMARY KEY AUTOINCREMENT（不是 BIGINT AUTO_INCREMENT）
#   - 无 ENGINE/CHARSET/COLLATE（SQLite 全局 UTF-8）
#   - TEXT/DATETIME 存时间（SQLite 类型 affinity）
#   - 无 JSON 类型，site_settings.data 改 TEXT（应用层用 Jackson 转）
#   - 无 PARTITION BY（SQLite 不支持分区，page_view 不分区）
#   - 无 FOREIGN KEY 约束（保持与 MySQL 兼容，业务层保证）
-- =============================================================

-- ----------------------------------------------------
-- 1. user 管理员账号
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS user (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  username        VARCHAR(50)   NOT NULL,
  password_hash   VARCHAR(200)  NOT NULL,
  nickname        VARCHAR(50),
  email           VARCHAR(100),
  avatar          VARCHAR(500),
  bio             TEXT,
  intro           TEXT,
  quote           TEXT,
  footer_text     TEXT,
  location        VARCHAR(100),
  role            VARCHAR(20)   NOT NULL DEFAULT 'ADMIN',
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON user(username);

-- ----------------------------------------------------
-- 2. category 分类
-- 字段名 sort（不是 sort_order），与 Category entity 的 sort 字段对应
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS category (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          VARCHAR(50)  NOT NULL,
  slug          VARCHAR(50)  NOT NULL,
  description   VARCHAR(200),
  visible       TINYINT      NOT NULL DEFAULT 1,
  sort          INT          NOT NULL DEFAULT 0,
  -- 2026-06-19：时间字段统一由 Java（MyBatis-Plus MetaObjectHandler，北京时间）填充，
  -- 不用 DEFAULT CURRENT_TIMESTAMP（SQLite 该默认值永远写 UTC）。
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_slug ON category(slug);

-- ----------------------------------------------------
-- 3. tag 标签
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS tag (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          VARCHAR(50)  NOT NULL,
  slug          VARCHAR(50)  NOT NULL,
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at    DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tag_slug ON tag(slug);

-- ----------------------------------------------------
-- 4. article 文章
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS article (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  title           VARCHAR(200)  NOT NULL,
  slug            VARCHAR(200)  NOT NULL,
  summary         VARCHAR(500),
  content_md      TEXT          NOT NULL,    -- SQLite 无 MEDIUMTEXT，TEXT 不限长
  cover_url       VARCHAR(500),
  status          TINYINT       NOT NULL DEFAULT 0,
  view_count      INT           NOT NULL DEFAULT 0,
  category_id     BIGINT,                    -- 业务层保证对应 category.id
  published_at    DATETIME,
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL,
  is_pinned       TINYINT       NOT NULL DEFAULT 0,    -- 0-普通 1-置顶
  deleted         TINYINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_slug ON article(slug);
CREATE INDEX IF NOT EXISTS idx_article_category ON article(category_id);
CREATE INDEX IF NOT EXISTS idx_article_status ON article(status);
CREATE INDEX IF NOT EXISTS idx_article_published_at ON article(published_at);
CREATE INDEX IF NOT EXISTS idx_article_deleted ON article(deleted);

-- ----------------------------------------------------
-- 5. article_tag 文章-标签关联
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS article_tag (
  article_id      BIGINT  NOT NULL,
  tag_id          BIGINT  NOT NULL,
  PRIMARY KEY (article_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_article_tag_tag ON article_tag(tag_id);

-- ----------------------------------------------------
-- 6. comment 评论
-- 2026-06-17 v2.6.0 补字段：parent_id（预留树形）+ website（评论者网站）
-- 与 MySQL 对齐：user_agent 长度 500，无 updated_at 字段
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS comment (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT       NOT NULL,
  parent_id       BIGINT       NOT NULL DEFAULT 0,  -- 父评论 ID（0=顶层，预留树形）
  nickname        VARCHAR(50)  NOT NULL,
  email           VARCHAR(100),
  website         VARCHAR(200),  -- 评论者网站
  content         TEXT         NOT NULL,
  ip              VARCHAR(45),
  user_agent      VARCHAR(500),
  status          TINYINT      NOT NULL DEFAULT 0,
  -- 2026-06-19：时间由 Java（北京时间）填充——CommentController 走 JdbcTemplate 显式传
  -- 'yyyy-MM-dd HH:mm:ss' 字符串，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at      DATETIME     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comment_article ON comment(article_id);
CREATE INDEX IF NOT EXISTS idx_comment_parent ON comment(parent_id);
CREATE INDEX IF NOT EXISTS idx_comment_status ON comment(status);
CREATE INDEX IF NOT EXISTS idx_comment_created ON comment(created_at);

-- ----------------------------------------------------
-- 7. admin_device 设备白名单
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_device (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id       VARCHAR(64)   NOT NULL,
  device_name     VARCHAR(100)  NOT NULL DEFAULT '',
  user_agent      VARCHAR(500)  NOT NULL DEFAULT '',
  ip              VARCHAR(45)   NOT NULL DEFAULT '',
  status          VARCHAR(20)   NOT NULL DEFAULT 'pending',
  last_seen_at    DATETIME,
  approved_at     DATETIME,
  approved_by     VARCHAR(64)   NOT NULL DEFAULT '',
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_admin_device_device_id ON admin_device(device_id);
CREATE INDEX IF NOT EXISTS idx_admin_device_status ON admin_device(status);
CREATE INDEX IF NOT EXISTS idx_admin_device_last_seen ON admin_device(last_seen_at);

-- ----------------------------------------------------
-- 8. api_whitelist API 路径白名单
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS api_whitelist (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  path_prefix     VARCHAR(200) NOT NULL,
  type            VARCHAR(20)  NOT NULL,
  enabled         TINYINT      NOT NULL DEFAULT 1,
  description     VARCHAR(200),
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_api_whitelist_path_type ON api_whitelist(path_prefix, type);
CREATE INDEX IF NOT EXISTS idx_api_whitelist_type ON api_whitelist(type);
CREATE INDEX IF NOT EXISTS idx_api_whitelist_enabled ON api_whitelist(enabled);

-- ----------------------------------------------------
-- 9. site_settings 站点设置（按 section 存 JSON 字符串）
-- ----------------------------------------------------
-- SQLite 无 JSON 类型 → 用 TEXT 存 JSON 字符串，Java 端 Jackson 转
CREATE TABLE IF NOT EXISTS site_settings (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  section         VARCHAR(32)  NOT NULL,
  data            TEXT         NOT NULL,
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_settings_section ON site_settings(section);

-- ----------------------------------------------------
-- 10. page_view 访问统计（v2.5.0，无分区）
-- ----------------------------------------------------
-- SQLite 不支持 PARTITION BY RANGE → 不分区
-- UK 改为 (visitor, path, visit_date, created_at) 真正按天去重
CREATE TABLE IF NOT EXISTS page_view (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  path            VARCHAR(200) NOT NULL,
  article_id      BIGINT,
  visitor         VARCHAR(36)  NOT NULL,
  ip              VARCHAR(45),
  user_agent      VARCHAR(200),
  referer         VARCHAR(500),
  -- ⚠️ 2026-06-19：此处 DEFAULT CURRENT_TIMESTAMP **有意保留，请勿清理**。
  --   PageViewService.recordVisitAsync 始终显式传 dayStart（北京当天 00:00）作 created_at，
  --   DEFAULT 实际永不触发；但 UK (visitor, path, visit_date, created_at) 的按天去重依赖
  --   "created_at = visit_date 北京 00:00"。保留 DEFAULT 不影响现有逻辑，删了亦无害，
  --   但与其他表统一删除会让人误以为此列也该由 fill 托管——本列不走 MyBatis-Plus，故标注。
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  visit_date      DATE         NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_page_view_visitor_path_date
  ON page_view(visitor, path, visit_date, created_at);
CREATE INDEX IF NOT EXISTS idx_page_view_path ON page_view(path);
CREATE INDEX IF NOT EXISTS idx_page_view_article ON page_view(article_id);
CREATE INDEX IF NOT EXISTS idx_page_view_created ON page_view(created_at);
CREATE INDEX IF NOT EXISTS idx_page_view_visitor ON page_view(visitor);

-- ----------------------------------------------------
-- 11. backup_record 数据备份记录（v4.2.0，REQ-BACKUP-2026-06-20）
-- ----------------------------------------------------
-- 状态机：PENDING → RUNNING → SUCCESS / FAILED
-- 配套：BackupRecord / BackupRecordMapper / BackupService / BackupController
CREATE TABLE IF NOT EXISTS backup_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  tag             VARCHAR(64),                            -- GitHub Release tag（SUCCESS 才有）
  status          VARCHAR(16) NOT NULL,                   -- PENDING/RUNNING/SUCCESS/FAILED
  started_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- v4.3.2: 保留 DEFAULT (历史遗留)
                                                              -- BackupService.triggerBackup 显式 setStartedAt 兜底填北京时间,
                                                              -- DEFAULT 是冗余兜底 — 新表 (如 restore_record) 建议不要 DEFAULT
  finished_at     DATETIME,
  db_size         BIGINT       DEFAULT 0,                 -- db dump 加密后大小
  uploads_size    BIGINT       DEFAULT 0,                 -- uploads tar 加密后大小
  asset_count     INTEGER      DEFAULT 0,
  asset_urls      TEXT,                                  -- JSON 数组（GitHub asset URL）
  manifest_json   TEXT,                                  -- 备份清单原文
  error_stage     VARCHAR(16),                           -- 失败阶段 DUMP/PACK/UPLOAD/SCRIPT
  error_message   TEXT,                                  -- 失败信息（不含密码/secret）
  operator_id     BIGINT,                                -- 触发人 uid
  operator_name   VARCHAR(64),                           -- 触发人 username
  trace_id        VARCHAR(64)                            -- 2026-06-21: 触发请求的 traceId，失败详情里展示
                                                          -- 用于 owner 反查 server log(/opt/myblog/logs/blog.log.*)
                                                          -- @Async 新线程 MDC 不会透传,所以存到 record 字段里
);
CREATE INDEX IF NOT EXISTS idx_backup_record_started_at ON backup_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_record_status ON backup_record(status);
-- v4.3.2 (REQ-RESTORE-2026-06-20): 双向并发互斥的数据库兜底
-- 任意时刻只允许一条 backup_record.status='RUNNING' (单进程 countRunning() + insert 之间有 TOCTOU 窗口)
-- SQLite partial unique index 在写入层强保证, 第二次 INSERT 会直接抛 UNIQUE constraint failed
-- Java 侧捕获这个异常转 BusinessException(BACKUP_CONFLICT)
CREATE UNIQUE INDEX IF NOT EXISTS uk_backup_record_running ON backup_record(status) WHERE status = 'RUNNING';

-- ----------------------------------------------------
-- 12. restore_record 数据恢复记录（v4.3.0，REQ-RESTORE-2026-06-20）
-- ----------------------------------------------------
-- 状态机：PENDING → RUNNING → SUCCESS / FAILED / UNKNOWN
-- UNKNOWN 给"启动回填发现孤儿但磁盘没 result.json"用
-- 配套：RestoreRecord / RestoreRecordMapper / RestoreService / RestoreStartupReconciler / RestoreController
-- 2026-06-21：时间由 Java（MyBatis-Plus MetaObjectHandler，北京时间）填充，
-- 不用 DEFAULT CURRENT_TIMESTAMP（SQLite 该默认值永远写 UTC）。
-- Java 侧 RestoreService.triggerRestore 显式 record.setStartedAt(LocalDateTime.now()) 兜底。
CREATE TABLE IF NOT EXISTS restore_record (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  status            VARCHAR(16)  NOT NULL,                  -- PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN
  source_record_id  BIGINT,                                -- 关联 backup_record.id（哪个备份被恢复）
  source_tag        VARCHAR(64),                           -- 备份的 GitHub Release tag（崩溃后回填用）
  scope             VARCHAR(16)  NOT NULL,                  -- DB_ONLY / DB_UPLOADS
  started_at        DATETIME     NOT NULL,                 -- 由 Java setStartedAt 显式填, 不依赖 SQLite DEFAULT
  finished_at       DATETIME,
  error_stage       VARCHAR(32),                           -- 失败阶段 PRECHECK/STOP/DOWNLOAD/SHA256/DECRYPT/IMPORT/UPLOADS/START/HEALTH/VERIFY/ORPHAN/TRAP
  error_message     TEXT,                                  -- 失败信息（不含密码/secret）
  verify_diff       TEXT,                                  -- v3 数据完整性校验差异（manifest 行数 vs 实际）
  operator_id       BIGINT,                                -- 触发人 uid
  operator_name     VARCHAR(64)                            -- 触发人 username
);
CREATE INDEX IF NOT EXISTS idx_restore_record_started_at ON restore_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_restore_record_status ON restore_record(status);
-- v4.3.2 (REQ-RESTORE-2026-06-20): 同上, 双向互斥的数据库兜底
CREATE UNIQUE INDEX IF NOT EXISTS uk_restore_record_running ON restore_record(status) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_page_view_visit_date ON page_view(visit_date);

-- ----------------------------------------------------
-- 13. import_record 文章导入记录（2026-06-24 DEV-002，文章批量导入）
-- ----------------------------------------------------
-- 状态机：PENDING → RUNNING → SUCCESS / FAILED
-- 配套：ImportRecord / ImportRecordMapper / ArticleImportService
-- 时间由 Java 端 ZoneId UTC+8 填充
CREATE TABLE IF NOT EXISTS import_record (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  file_name         VARCHAR(255) NOT NULL,                  -- 上传的 ZIP 文件名
  status            VARCHAR(16)  NOT NULL,                  -- PENDING/RUNNING/SUCCESS/FAILED
  total_count       INT          NOT NULL DEFAULT 0,        -- 总文章数（解析后）
  success_count     INT          NOT NULL DEFAULT 0,        -- 成功导入文章数
  fail_count        INT          NOT NULL DEFAULT 0,        -- 失败文章数
  error_message     TEXT,                                   -- 失败详情（前 500 字）
  started_at        DATETIME     NOT NULL,                  -- 由 Java 显式填
  finished_at       DATETIME,
  operator_id       BIGINT,
  operator_name     VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_import_record_started_at ON import_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_import_record_status ON import_record(status);

-- ----------------------------------------------------
-- 14. article_attachment 文章附件（2026-07-01 DEV-001）
-- ----------------------------------------------------
-- 一文一附件：article_id UNIQUE 约束（DB 层兜底，强制"先删旧附件再上传"）
-- 二段删除：softDelete (deleted=1, 文件保留) → hardDelete (先删文件后删 DB)
-- 公开下载软删返 410 Gone
-- 配套：Attachment / AttachmentMapper / AttachmentService / AttachmentController
CREATE TABLE IF NOT EXISTS article_attachment (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT       NOT NULL,                    -- UNIQUE（一文一附件）
  file_name       VARCHAR(255) NOT NULL,                    -- 原始文件名（含扩展名）
  file_path       VARCHAR(500) NOT NULL,                    -- 相对 blog.attachment.local.dir 路径，如 attachments/2026/07/20260701-{uuid}.zip
  file_size       BIGINT       NOT NULL DEFAULT 0,          -- 字节数
  mime_type       VARCHAR(100),                             -- 如 application/zip
  deleted         TINYINT      NOT NULL DEFAULT 0,          -- 0=未删 / 1=软删（文件保留在磁盘）
  created_at      DATETIME     NOT NULL,                    -- Java 填北京时间
  updated_at      DATETIME     NOT NULL                     -- Java 填北京时间
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_attachment_article ON article_attachment(article_id);
CREATE INDEX IF NOT EXISTS idx_article_attachment_deleted ON article_attachment(deleted);
CREATE INDEX IF NOT EXISTS idx_article_attachment_updated ON article_attachment(updated_at);

-- ----------------------------------------------------
-- 11. article_view_log 历史访问日志（v2.5.0 之前的，0 数据保留 schema 兼容）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS article_view_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT   NOT NULL,
  view_date       DATE     NOT NULL,
  view_count      INT      NOT NULL DEFAULT 1,
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  -- 注：本表为历史死表（v2.5.0 起 0 数据，无任何 insert/select），仅保留 schema 兼容。
  created_at      DATETIME NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_view_log_article_date
  ON article_view_log(article_id, view_date);
CREATE INDEX IF NOT EXISTS idx_article_view_log_article ON article_view_log(article_id);
CREATE INDEX IF NOT EXISTS idx_article_view_log_date ON article_view_log(view_date);

-- ----------------------------------------------------
-- 12. ip_ban 全站请求频率封禁（2026-06-18 新增）
-- 触发：IpRateLimitFilter 检测到同一 IP 1 秒内请求数超过阈值
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS ip_ban (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  ip              VARCHAR(45)   NOT NULL,
  request_count   INT           NOT NULL DEFAULT 0,
  reason          VARCHAR(255)  NOT NULL DEFAULT '',
  -- 2026-06-19：时间由 Java（北京时间）填充，不用 DEFAULT CURRENT_TIMESTAMP（SQLite 写 UTC）。
  -- banned_at 由 IpBanService 显式 set，created_at/updated_at 由 MetaObjectHandler 自动填充。
  banned_at       DATETIME      NOT NULL,
  expire_at       DATETIME      NOT NULL,
  unbanned        TINYINT       NOT NULL DEFAULT 0,
  unbanned_at     DATETIME,
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ip_ban_ip ON ip_ban(ip);
CREATE INDEX IF NOT EXISTS idx_ip_ban_expire ON ip_ban(expire_at);
CREATE INDEX IF NOT EXISTS idx_ip_ban_unbanned ON ip_ban(unbanned);

-- ----------------------------------------------------
-- 14. audit_log 审计日志（2026-07-01 DEV-004）
-- ----------------------------------------------------
-- AOP 自动拦截 admin 写端点 + 公开下载端点，每条操作一行记录
-- operator：admin 端点 = AuthContext.username；公开下载 = 'anonymous'
-- operation：CREATE / UPDATE / DELETE / APPROVE / REJECT / DOWNLOAD
-- target：模块名称（20 个，含 settings 9 子项，详见 AuditLogAspect 路径映射表）
-- detail：操作详情（拼接主体名 / id 等可读信息）
-- 配套：AuditLog / AuditLogMapper / AuditLogService / AuditLogAspect / AuditLogController
CREATE TABLE IF NOT EXISTS audit_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  operator        VARCHAR(50)  NOT NULL,                     -- 操作人 username（admin）或 'anonymous'（公开下载）
  operation       VARCHAR(20)  NOT NULL,                     -- CREATE / UPDATE / DELETE / APPROVE / REJECT / DOWNLOAD
  target          VARCHAR(100) NOT NULL,                     -- 模块名称（20 个）
  detail          VARCHAR(500),                              -- 操作详情（可空）
  ip              VARCHAR(45),                               -- 操作 IP
  created_at      DATETIME     NOT NULL                      -- Java 填北京时间
);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_target ON audit_log(target);
CREATE INDEX IF NOT EXISTS idx_audit_log_operation ON audit_log(operation);
CREATE INDEX IF NOT EXISTS idx_audit_log_operator ON audit_log(operator);

-- ----------------------------------------------------
-- 15. upgrade_record 升级记录（2026-07-08）
-- ----------------------------------------------------
-- 状态机：PENDING → RUNNING → SUCCESS / FAILED
-- 配套：UpgradeRecord / UpgradeRecordMapper / UpgradeRecordService / UpgradeController
CREATE TABLE IF NOT EXISTS upgrade_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  target_version  VARCHAR(32)  NOT NULL,                     -- 目标版本号，如 v5.3.0
  mode            VARCHAR(16)  NOT NULL,                     -- full（全量代码升级）/ init（初始化）
  import_db       TINYINT      NOT NULL DEFAULT 0,           -- 是否导入数据
  status          VARCHAR(16)  NOT NULL,                     -- PENDING / RUNNING / SUCCESS / FAILED
  started_at      DATETIME     NOT NULL,                     -- 由 Java 显式填
  finished_at     DATETIME,
  from_version    VARCHAR(32),                               -- 升级前版本号
  error_message   TEXT,                                      -- 失败信息
  operator_id     BIGINT,                                    -- 触发人 uid
  operator_name   VARCHAR(64),                               -- 触发人 username
  ip              VARCHAR(45)                                -- 操作 IP
);
CREATE INDEX IF NOT EXISTS idx_upgrade_record_started_at ON upgrade_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_upgrade_record_status ON upgrade_record(status);

-- ----------------------------------------------------
-- 15. _migration_history 增量 SQL 迁移追踪
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS _migration_history (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  script_name     VARCHAR(255) NOT NULL,
  executed_at     DATETIME     NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_migration_script ON _migration_history(script_name);

-- =====================================================
-- Seed Data
-- =====================================================

-- 默认 admin（密码 123456）
INSERT OR IGNORE INTO user (id, username, password_hash, nickname, role, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$RiTjk3eJcUBN2xKUE4FAQ.4xzURKOSUrpbgvou1uGjEV7tQ70fpJW', 'Corey', 'ADMIN', datetime('now', 'localtime'), datetime('now', 'localtime'));

-- API 白名单
-- 2026-06-22 修复 [BUG-LOGIN-401]：v4.1.0 把 created_at/updated_at 的 DEFAULT 去掉后，
-- 这条裸 INSERT 没传时间 → NOT NULL 约束失败 + OR IGNORE 静默吞掉 → api_whitelist 整张表空
-- → AdminAuthFilter.matchPath 拿不到 /auth/login → 401 拒绝登录。
-- 修复：显式给 created_at/updated_at，datetime('now', 'localtime') 取本地时间。
-- 业务路径（MyBatis-Plus entity + MetaObjectHandler）不受影响，仍由 Java 填北京时间。
INSERT OR IGNORE INTO api_whitelist (path_prefix, type, enabled, description, created_at, updated_at) VALUES
  ('/auth/login', 'public', 1, '登录', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/auth/me/password', 'public', 1, '改密（自身鉴权）', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/public/', 'public', 1, '公开端点', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/articles', 'public', 1, '文章公开端点', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/comments', 'public', 1, '评论公开端点', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/health', 'public', 1, '健康检查', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/seo/', 'public', 1, 'SEO 渲染直出端点', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/sitemap.xml', 'public', 1, 'Sitemap 站点地图', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/v3/api-docs', 'public', 1, 'Swagger API 文档（生产可关）', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/swagger-ui', 'public', 1, 'Swagger UI（生产可关）', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/admin/', 'admin', 1, '所有 admin/* 路径必须鉴权', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/auth/me', 'admin', 1, '获取当前用户信息', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/auth/logout', 'admin', 1, '登出', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/auth/devices', 'admin', 1, '设备管理', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/uploads', 'admin', 1, '文件上传（multipart）', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/admin/articles/{id}/attachment', 'admin', 1, '文章附件上传/软删', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/admin/attachments', 'admin', 1, '附件后台列表/恢复/硬删', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/admin/audit-logs', 'admin', 1, '审计日志查询（2026-07-01 DEV-004）', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/admin/settings/upload-md', 'admin', 1, '上传 md 文档批量更新 settings（2026-07-01 DEV-005）', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/admin/settings/exec-sql', 'admin', 1, '紧急 SQL 执行（隐藏功能）', datetime('now', 'localtime'), datetime('now', 'localtime'));

-- 站点设置：不预置种子数据，由用户在管理后台初始化填写
-- SiteSettingsService 在首次 GET 时会自动创建空默认值
