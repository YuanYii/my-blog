-- 测试用 SQLite schema（与 docs/sql/schema-sqlite.sql 保持同步）
-- 内存库每次进程启动重建，spring.sql.init 在 Spring context 完成前执行

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
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON user(username);

CREATE TABLE IF NOT EXISTS category (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          VARCHAR(50)  NOT NULL,
  slug          VARCHAR(50)  NOT NULL,
  description   VARCHAR(200),
  visible       TINYINT      NOT NULL DEFAULT 1,
  sort          INT          NOT NULL DEFAULT 0,
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_slug ON category(slug);

CREATE TABLE IF NOT EXISTS tag (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          VARCHAR(50)  NOT NULL,
  slug          VARCHAR(50)  NOT NULL,
  created_at    DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tag_slug ON tag(slug);

CREATE TABLE IF NOT EXISTS article (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  title           VARCHAR(200)  NOT NULL,
  slug            VARCHAR(200)  NOT NULL,
  summary         VARCHAR(500),
  content_md      TEXT          NOT NULL,
  cover_url       VARCHAR(500),
  status          TINYINT       NOT NULL DEFAULT 0,
  view_count      INT           NOT NULL DEFAULT 0,
  category_id     BIGINT,
  published_at    DATETIME,
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL,
  is_pinned       TINYINT       NOT NULL DEFAULT 0,
  deleted         TINYINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_slug ON article(slug);
CREATE INDEX IF NOT EXISTS idx_article_category ON article(category_id);
CREATE INDEX IF NOT EXISTS idx_article_status ON article(status);
CREATE INDEX IF NOT EXISTS idx_article_published_at ON article(published_at);
CREATE INDEX IF NOT EXISTS idx_article_deleted ON article(deleted);

CREATE TABLE IF NOT EXISTS article_tag (
  article_id      BIGINT  NOT NULL,
  tag_id          BIGINT  NOT NULL,
  PRIMARY KEY (article_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_article_tag_tag ON article_tag(tag_id);

CREATE TABLE IF NOT EXISTS comment (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT       NOT NULL,
  parent_id       BIGINT       NOT NULL DEFAULT 0,
  nickname        VARCHAR(50)  NOT NULL,
  email           VARCHAR(100),
  website         VARCHAR(200),
  content         TEXT         NOT NULL,
  ip              VARCHAR(45),
  user_agent      VARCHAR(500),
  status          TINYINT      NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comment_article ON comment(article_id);
CREATE INDEX IF NOT EXISTS idx_comment_parent ON comment(parent_id);
CREATE INDEX IF NOT EXISTS idx_comment_status ON comment(status);
CREATE INDEX IF NOT EXISTS idx_comment_created ON comment(created_at);

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
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_admin_device_device_id ON admin_device(device_id);
CREATE INDEX IF NOT EXISTS idx_admin_device_status ON admin_device(status);

CREATE TABLE IF NOT EXISTS api_whitelist (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  path_prefix     VARCHAR(200) NOT NULL,
  type            VARCHAR(20)  NOT NULL,
  enabled         TINYINT      NOT NULL DEFAULT 1,
  description     VARCHAR(200),
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_api_whitelist_path_type ON api_whitelist(path_prefix, type);

CREATE TABLE IF NOT EXISTS site_settings (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  section         VARCHAR(32)  NOT NULL,
  data            TEXT         NOT NULL,
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_settings_section ON site_settings(section);

CREATE TABLE IF NOT EXISTS page_view (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  path            VARCHAR(200) NOT NULL,
  article_id      BIGINT,
  visitor         VARCHAR(36)  NOT NULL,
  ip              VARCHAR(45),
  user_agent      VARCHAR(200),
  referer         VARCHAR(500),
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  visit_date      DATE         NOT NULL
);

CREATE TABLE IF NOT EXISTS article_view_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT   NOT NULL,
  view_date       DATE     NOT NULL,
  view_count      INT      NOT NULL DEFAULT 1,
  created_at      DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS ip_ban (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  ip              VARCHAR(45)   NOT NULL,
  request_count   INT           NOT NULL DEFAULT 0,
  reason          VARCHAR(255)  NOT NULL DEFAULT '',
  banned_at       DATETIME      NOT NULL,
  expire_at       DATETIME      NOT NULL,
  unbanned        TINYINT       NOT NULL DEFAULT 0,
  unbanned_at     DATETIME,
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ip_ban_ip ON ip_ban(ip);

-- ----------------------------------------------------
-- v4.2.0 backup_record（REQ-BACKUP-2026-06-20）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS backup_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  tag             VARCHAR(64),
  status          VARCHAR(16) NOT NULL,
  started_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at     DATETIME,
  db_size         BIGINT       DEFAULT 0,
  uploads_size    BIGINT       DEFAULT 0,
  asset_count     INTEGER      DEFAULT 0,
  asset_urls      TEXT,
  manifest_json   TEXT,
  error_stage     VARCHAR(16),
  error_message   TEXT,
  operator_id     BIGINT,
  operator_name   VARCHAR(64),
  trace_id        VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_backup_record_started_at ON backup_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_record_status ON backup_record(status);

CREATE TABLE IF NOT EXISTS article_attachment (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT       NOT NULL,
  file_name       VARCHAR(255) NOT NULL,
  file_path       VARCHAR(500) NOT NULL,
  file_size       BIGINT       NOT NULL DEFAULT 0,
  mime_type       VARCHAR(100),
  deleted         TINYINT      NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_attachment_article ON article_attachment(article_id);
CREATE INDEX IF NOT EXISTS idx_article_attachment_deleted ON article_attachment(deleted);
CREATE INDEX IF NOT EXISTS idx_article_attachment_updated ON article_attachment(updated_at);

CREATE TABLE IF NOT EXISTS audit_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  operator        VARCHAR(50)  NOT NULL,
  operation       VARCHAR(20)  NOT NULL,
  target          VARCHAR(100) NOT NULL,
  detail          VARCHAR(500),
  ip              VARCHAR(45),
  created_at      DATETIME     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);

CREATE TABLE IF NOT EXISTS upgrade_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  target_version  VARCHAR(32)  NOT NULL,
  mode            VARCHAR(16)  NOT NULL,
  import_db       TINYINT      NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL,
  started_at      DATETIME     NOT NULL,
  finished_at     DATETIME,
  from_version    VARCHAR(32),
  error_message   TEXT,
  operator_id     BIGINT,
  operator_name   VARCHAR(64),
  ip              VARCHAR(45)
);

CREATE TABLE IF NOT EXISTS restore_record (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  status            VARCHAR(16)  NOT NULL,
  source_record_id  BIGINT,
  source_tag        VARCHAR(64),
  scope             VARCHAR(16)  NOT NULL,
  started_at        DATETIME     NOT NULL,
  finished_at       DATETIME,
  error_stage       VARCHAR(32),
  error_message     TEXT,
  verify_diff       TEXT,
  operator_id       BIGINT,
  operator_name     VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS import_record (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  file_name         VARCHAR(255) NOT NULL,
  status            VARCHAR(16)  NOT NULL,
  total_count       INT          NOT NULL DEFAULT 0,
  success_count     INT          NOT NULL DEFAULT 0,
  fail_count        INT          NOT NULL DEFAULT 0,
  error_message     TEXT,
  started_at        DATETIME     NOT NULL,
  finished_at       DATETIME,
  operator_id       BIGINT,
  operator_name     VARCHAR(64)
);
