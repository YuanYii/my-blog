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
  started_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at     DATETIME,
  db_size         BIGINT       DEFAULT 0,                 -- db dump 加密后大小
  uploads_size    BIGINT       DEFAULT 0,                 -- uploads tar 加密后大小
  asset_count     INTEGER      DEFAULT 0,
  asset_urls      TEXT,                                  -- JSON 数组（GitHub asset URL）
  manifest_json   TEXT,                                  -- 备份清单原文
  error_stage     VARCHAR(16),                           -- 失败阶段 DUMP/PACK/UPLOAD/SCRIPT
  error_message   TEXT,                                  -- 失败信息（不含密码/secret）
  operator_id     BIGINT,                                -- 触发人 uid
  operator_name   VARCHAR(64)                            -- 触发人 username
);
CREATE INDEX IF NOT EXISTS idx_backup_record_started_at ON backup_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_record_status ON backup_record(status);
CREATE INDEX IF NOT EXISTS idx_page_view_visit_date ON page_view(visit_date);

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

-- =====================================================
-- Seed Data
-- =====================================================

-- 默认 admin（密码 123456）
INSERT OR IGNORE INTO user (id, username, password_hash, nickname, role)
VALUES (1, 'admin', '$2a$10$RiTjk3eJcUBN2xKUE4FAQ.4xzURKOSUrpbgvou1uGjEV7tQ70fpJW', 'Corey', 'ADMIN');

-- API 白名单
INSERT OR IGNORE INTO api_whitelist (path_prefix, type, enabled, description) VALUES
  ('/auth/login', 'public', 1, '登录'),
  ('/auth/me/password', 'public', 1, '改密（自身鉴权）'),
  ('/public/', 'public', 1, '公开端点'),
  ('/articles', 'public', 1, '文章公开端点'),
  ('/comments', 'public', 1, '评论公开端点'),
  ('/health', 'public', 1, '健康检查'),
  ('/v3/api-docs', 'public', 1, 'Swagger API 文档（生产可关）'),
  ('/swagger-ui', 'public', 1, 'Swagger UI（生产可关）'),
  ('/admin/', 'admin', 1, '所有 admin/* 路径必须鉴权'),
  ('/auth/me', 'admin', 1, '获取当前用户信息'),
  ('/auth/logout', 'admin', 1, '登出'),
  ('/auth/devices', 'admin', 1, '设备管理'),
  ('/uploads', 'admin', 1, '文件上传（multipart）');

-- 站点设置（SQLite 无 JSON_OBJECT 函数 → 直接写 JSON 字符串字面量）
INSERT OR IGNORE INTO site_settings (section, data) VALUES
  ('profile',     '{"nickname":"Corey","email":"corey@example.com","avatar":"","bio":"个人博客作者","location":""}'),
  ('blog',        '{"title":"加载中","subtitle":"","description":"","keywords":"","author":""}'),
  ('social',      '{"github":"","twitter":"","email":"","weibo":"","rss":true}'),
  ('preferences', '{"theme":"light","language":"zh-CN","timezone":"Asia/Shanghai"}'),
  ('theme',       '{"primaryColor":"#2f6f5e","accentColor":"#c97b3f","mode":"auto"}'),
  ('advanced',    '{"enableCache":true,"enableRss":true,"enableSearch":true,"commentModeration":true}'),
  ('techstack',   '{"groups":[]}'),
  ('experience',  '{"items":[]}');
