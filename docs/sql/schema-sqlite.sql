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
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_slug ON category(slug);

-- ----------------------------------------------------
-- 3. tag 标签
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS tag (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          VARCHAR(50)  NOT NULL,
  slug          VARCHAR(50)  NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  visit_date      DATE         NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_page_view_visitor_path_date
  ON page_view(visitor, path, visit_date, created_at);
CREATE INDEX IF NOT EXISTS idx_page_view_path ON page_view(path);
CREATE INDEX IF NOT EXISTS idx_page_view_article ON page_view(article_id);
CREATE INDEX IF NOT EXISTS idx_page_view_created ON page_view(created_at);
CREATE INDEX IF NOT EXISTS idx_page_view_visitor ON page_view(visitor);
CREATE INDEX IF NOT EXISTS idx_page_view_visit_date ON page_view(visit_date);

-- ----------------------------------------------------
-- 11. article_view_log 历史访问日志（v2.5.0 之前的，0 数据保留 schema 兼容）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS article_view_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id      BIGINT   NOT NULL,
  view_date       DATE     NOT NULL,
  view_count      INT      NOT NULL DEFAULT 1,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_view_log_article_date
  ON article_view_log(article_id, view_date);
CREATE INDEX IF NOT EXISTS idx_article_view_log_article ON article_view_log(article_id);
CREATE INDEX IF NOT EXISTS idx_article_view_log_date ON article_view_log(view_date);

-- =====================================================
-- Seed Data
-- =====================================================

-- 默认 admin（密码 123456）
-- 2026-06-17 v2.6.0：password_hash 用实际改过的 hash（不是默认占位）
INSERT OR IGNORE INTO user (id, username, password_hash, nickname, role)
VALUES (1, 'admin', '$2a$10$0YSdd8Tf7xcsmAk.05Kn4uEDSUAIT7ukAZqLnUMLrE5Gnd4wj5jEa', 'Corey', 'ADMIN');

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
