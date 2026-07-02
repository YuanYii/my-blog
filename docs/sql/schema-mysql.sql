-- =============================================================
-- my-blog 完整 schema（MySQL 版）
-- 2026-06-17 v2.6.0：整合所有迁移（migrations/、blog.sql、migration-*.sql）到单一文件
--
# 执行方式：
#   docker exec -i blog-mysql mysql -uroot -proot --default-character-set=utf8mb4 blog < docs/sql/schema-mysql.sql
#
# 表清单（12 张）：
#   - user           管理员账号
#   - article        文章
#   - category       分类
#   - tag            标签
#   - article_tag    文章-标签关联
#   - comment        评论
#   - admin_device   设备白名单
#   - api_whitelist  API 路径白名单
#   - site_settings  站点设置（按 section 存 JSON）
#   - page_view      访问统计（v2.5.0 新增，按月分区）
#   - article_view_log  历史表（v2.5.0 之前的访问日志，0 数据保留 schema 兼容）
#   - ip_ban         IP 频率限制封禁记录（2026-06-18 新增）
-- =============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------------------------------
-- 1. user 管理员账号
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)   NOT NULL                COMMENT '登录用户名',
  `password_hash` VARCHAR(200)  NOT NULL                COMMENT 'BCrypt 加密后的密码',
  `nickname`      VARCHAR(50)   DEFAULT NULL            COMMENT '昵称',
  `email`         VARCHAR(100)  DEFAULT NULL            COMMENT '邮箱',
  `avatar`        VARCHAR(500)  DEFAULT NULL            COMMENT '头像 URL',
  `bio`           TEXT          DEFAULT NULL            COMMENT '个人简介',
  `intro`         TEXT          DEFAULT NULL            COMMENT '我的介绍（关于页面）',
  `quote`         TEXT          DEFAULT NULL            COMMENT '引用语（关于页引用块）',
  `footer_text`   TEXT          DEFAULT NULL            COMMENT '底部欢迎语（关于页底部）',
  `location`      VARCHAR(100)  DEFAULT NULL            COMMENT '所在地',
  `role`          VARCHAR(20)   NOT NULL DEFAULT 'ADMIN' COMMENT '角色',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员账号';

-- ----------------------------------------------------
-- 2. category 分类
-- 字段名 sort（不是 sort_order），与 Category entity 的 sort 字段对应
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`       VARCHAR(50)  NOT NULL                 COMMENT '分类名',
  `slug`       VARCHAR(50)  NOT NULL                 COMMENT 'URL slug',
  `description` VARCHAR(200) DEFAULT NULL            COMMENT '描述',
  `visible`    TINYINT      NOT NULL DEFAULT 1       COMMENT '1-显示 / 0-隐藏',
  `sort`       INT          NOT NULL DEFAULT 0       COMMENT '排序权重',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章分类';

-- ----------------------------------------------------
-- 3. tag 标签
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `tag` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `name`       VARCHAR(50)  NOT NULL,
  `slug`       VARCHAR(50)  NOT NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章标签';

-- ----------------------------------------------------
-- 4. article 文章
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `article` (
  `id`             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`          VARCHAR(200)   NOT NULL                COMMENT '标题',
  `slug`           VARCHAR(200)   NOT NULL                COMMENT 'URL slug',
  `summary`        VARCHAR(500)   DEFAULT NULL            COMMENT '摘要',
  `content_md`     MEDIUMTEXT     NOT NULL                COMMENT 'Markdown 内容',
  `cover_url`      VARCHAR(500)   DEFAULT NULL            COMMENT '封面图 URL',
  `status`         TINYINT        NOT NULL DEFAULT 0      COMMENT '0-草稿 / 1-已发布 / 2-已归档',
  `view_count`     INT            NOT NULL DEFAULT 0      COMMENT '浏览数（兼容字段，v2.5.0 起主用 page_view 表）',
  `category_id`    BIGINT         DEFAULT NULL            COMMENT '分类 ID',
  `published_at`   DATETIME       DEFAULT NULL            COMMENT '发布时间',
  `created_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT        NOT NULL DEFAULT 0      COMMENT '逻辑删除 0-未删 / 1-已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`),
  KEY `idx_category` (`category_id`),
  KEY `idx_status` (`status`),
  KEY `idx_published_at` (`published_at`),
  KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章';

-- ----------------------------------------------------
-- 5. article_tag 文章-标签关联
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `article_tag` (
  `article_id` BIGINT NOT NULL,
  `tag_id`     BIGINT NOT NULL,
  PRIMARY KEY (`article_id`, `tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章-标签关联';

-- ----------------------------------------------------
-- 6. comment 评论
-- 2026-06-17 v2.6.0 补字段：parent_id（预留树形）+ website（评论者网站）
-- 字段顺序与 SQLite 对齐
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `article_id`  BIGINT       NOT NULL                COMMENT '文章 ID',
  `parent_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '父评论 ID（0=顶层，预留树形）',
  `nickname`    VARCHAR(50)  NOT NULL                COMMENT '评论者昵称',
  `email`       VARCHAR(100) DEFAULT NULL            COMMENT '评论者邮箱',
  `website`     VARCHAR(200) DEFAULT NULL            COMMENT '评论者网站',
  `content`     TEXT         NOT NULL                COMMENT '评论内容',
  `ip`          VARCHAR(45)  DEFAULT NULL            COMMENT '评论者 IP',
  `user_agent`  VARCHAR(500) DEFAULT NULL,
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '0-待审 / 1-已通过 / 2-已拒绝',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_article` (`article_id`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论';

-- ----------------------------------------------------
-- 7. admin_device 设备白名单
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `admin_device` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`     VARCHAR(64)   NOT NULL                COMMENT '前端生成的设备 UUID（localStorage 持久化）',
  `device_name`   VARCHAR(100)  NOT NULL DEFAULT ''     COMMENT '设备名（UA 解析：Safari on macOS）',
  `user_agent`    VARCHAR(500)  NOT NULL DEFAULT ''     COMMENT '原始 User-Agent',
  `ip`            VARCHAR(45)   NOT NULL DEFAULT ''     COMMENT '登录 IP',
  `status`        VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT 'pending-待授权 / approved-已授权 / revoked-已吊销',
  `last_seen_at`  DATETIME      DEFAULT NULL            COMMENT '最后活跃时间',
  `approved_at`   DATETIME      DEFAULT NULL            COMMENT '授权时间',
  `approved_by`   VARCHAR(64)   NOT NULL DEFAULT ''     COMMENT '授权来源 device_id（系统自动迁移时为 system）',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`),
  KEY `idx_status` (`status`),
  KEY `idx_last_seen` (`last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理后台设备白名单';

-- ----------------------------------------------------
-- 8. api_whitelist API 路径白名单
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_whitelist` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `path_prefix` VARCHAR(200) NOT NULL                COMMENT 'API 路径前缀',
  `type`        VARCHAR(20)  NOT NULL                COMMENT 'public-公开放行 / admin-必须鉴权',
  `enabled`     TINYINT      NOT NULL DEFAULT 1      COMMENT '1-启用 / 0-禁用',
  `description` VARCHAR(200) DEFAULT NULL            COMMENT '用途说明',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_path_type` (`path_prefix`, `type`),
  KEY `idx_type` (`type`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 路径白名单（最长前缀匹配）';

-- ----------------------------------------------------
-- 9. site_settings 站点设置（按 section 存 JSON）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `site_settings` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `section`    VARCHAR(32)  NOT NULL                COMMENT '配置段：blog/social/preferences/theme/advanced/profile/techstack/experience',
  `data`       JSON         NOT NULL                COMMENT '配置数据（JSON 对象）',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_section` (`section`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站点设置（按 section 存 JSON）';

-- ----------------------------------------------------
-- 10. page_view 访问统计（v2.5.0，按月分区）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `page_view` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `path`       VARCHAR(200) NOT NULL                COMMENT 'URL 路径（不含域名）',
  `article_id` BIGINT       DEFAULT NULL            COMMENT '文章详情才有，列表/其它页 NULL',
  `visitor`    VARCHAR(36)  NOT NULL                COMMENT '访客 UUID（前端 localStorage 持久化）',
  `ip`         VARCHAR(45)  DEFAULT NULL            COMMENT '客户端 IP（X-Forwarded-For 解析）',
  `user_agent` VARCHAR(200) DEFAULT NULL            COMMENT '浏览器 UA',
  `referer`    VARCHAR(500) DEFAULT NULL            COMMENT '来源 URL（document.referrer）',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间（精确到秒）',
  `visit_date` DATE         NOT NULL                COMMENT '访问日期（按天，UK 去重维度）',
  PRIMARY KEY (`id`, `created_at`),
  UNIQUE KEY `uk_visitor_path_date` (`visitor`, `path`, `visit_date`, `created_at`),
  KEY `idx_path` (`path`),
  KEY `idx_article` (`article_id`),
  KEY `idx_created` (`created_at`),
  KEY `idx_visitor` (`visitor`),
  KEY `idx_visit_date` (`visit_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='访问统计（按月分区）'
PARTITION BY RANGE (TO_DAYS(`created_at`)) (
  PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
  PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
  PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
  PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
  PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
  PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
  PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
  PARTITION p_future  VALUES LESS THAN MAXVALUE
);

-- ----------------------------------------------------
-- 11. article_view_log 历史访问日志（v2.5.0 之前的，0 数据保留 schema 兼容）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `article_view_log` (
  `id`         BIGINT   NOT NULL AUTO_INCREMENT,
  `article_id` BIGINT   NOT NULL,
  `view_date`  DATE     NOT NULL COMMENT '访问日期（用于按天聚合）',
  `view_count` INT      NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_date` (`article_id`, `view_date`),
  KEY `idx_article` (`article_id`),
  KEY `idx_date` (`view_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章访问日志（v2.5.0 之前的旧表，新版用 page_view）';

-- ----------------------------------------------------
-- 11. backup_record 数据备份记录（v4.2.0，REQ-BACKUP-2026-06-20）
-- ----------------------------------------------------
-- 状态机：PENDING → RUNNING → SUCCESS / FAILED
CREATE TABLE IF NOT EXISTS `backup_record` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tag`           VARCHAR(64)  DEFAULT NULL              COMMENT 'GitHub Release tag（SUCCESS 才有）',
  `status`        VARCHAR(16)  NOT NULL                  COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `started_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '触发时间',
  `finished_at`   DATETIME     DEFAULT NULL              COMMENT '结束时间（成功或失败）',
  `db_size`       BIGINT       NOT NULL DEFAULT 0        COMMENT 'db dump 加密后大小',
  `uploads_size`  BIGINT       NOT NULL DEFAULT 0        COMMENT 'uploads tar 加密后大小',
  `asset_count`   INT          NOT NULL DEFAULT 0        COMMENT 'asset 总数',
  `asset_urls`    TEXT                                  COMMENT 'JSON 数组（GitHub asset URL）',
  `manifest_json` TEXT                                  COMMENT '备份清单原文',
  `error_stage`   VARCHAR(16)  DEFAULT NULL              COMMENT '失败阶段 DUMP/PACK/UPLOAD/SCRIPT',
  `error_message` TEXT                                  COMMENT '失败信息（不含密码/secret）',
  `operator_id`   BIGINT       DEFAULT NULL              COMMENT '触发人 uid',
  `operator_name` VARCHAR(64)  DEFAULT NULL              COMMENT '触发人 username',
  `trace_id`      VARCHAR(64)  DEFAULT NULL              COMMENT '2026-06-21: 触发请求的 traceId，失败详情展示用',
  PRIMARY KEY (`id`),
  KEY `idx_backup_record_started_at` (`started_at` DESC),
  KEY `idx_backup_record_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据备份记录（v4.2.0）';

-- ----------------------------------------------------
-- 12. ip_ban 全站请求频率封禁（2026-06-18 新增）
-- 触发：IpRateLimitFilter 检测到同一 IP 1 秒内请求数超过阈值
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `ip_ban` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ip`            VARCHAR(45)  NOT NULL                COMMENT '被封禁的客户端 IP',
  `request_count` INT          NOT NULL DEFAULT 0       COMMENT '触发封禁时 1 秒窗口内的请求数（快照）',
  `reason`        VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '封禁原因',
  `banned_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '封禁开始时间',
  `expire_at`     DATETIME     NOT NULL                COMMENT '封禁到期时间（过期自动解封）',
  `unbanned`      TINYINT      NOT NULL DEFAULT 0       COMMENT '是否被管理员手动解封',
  `unbanned_at`   DATETIME     DEFAULT NULL             COMMENT '手动解封时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ip` (`ip`),
  KEY `idx_expire` (`expire_at`),
  KEY `idx_unbanned` (`unbanned`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='IP 频率限制封禁记录';

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================
-- Seed Data：默认 admin + API 白名单 + 站点设置默认值
-- 2026-06-17 v2.6.0：从原 blog.sql / migration-*.sql 整合
-- =====================================================

-- 默认 admin 账号（密码 123456，BCrypt hash）
-- 2026-06-17 v2.6.0：password_hash 用实际改过的 hash
-- 注意：首次部署后必须改密码！生产环境强烈建议改。
INSERT IGNORE INTO `user` (`id`, `username`, `password_hash`, `nickname`, `role`) VALUES
(1, 'admin', '$2a$10$0YSdd8Tf7xcsmAk.05Kn4uEDSUAIT7ukAZqLnUMLrE5Gnd4wj5jEa', 'Corey', 'ADMIN');

-- API 白名单（最长前缀匹配）

-- ============================================================
-- restore_record（v4.3.0 / REQ-RESTORE-2026-06-20）
-- ============================================================
CREATE TABLE IF NOT EXISTS `restore_record` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `status`            VARCHAR(16)   NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN',
  `source_record_id`  BIGINT        DEFAULT NULL COMMENT '关联 backup_record.id',
  `source_tag`        VARCHAR(64)   DEFAULT NULL COMMENT '备份的 GitHub Release tag',
  `scope`             VARCHAR(16)   NOT NULL COMMENT 'DB_ONLY / DB_UPLOADS',
  `started_at`        DATETIME      NOT NULL,
  `finished_at`       DATETIME      DEFAULT NULL,
  `error_stage`       VARCHAR(32)   DEFAULT NULL COMMENT '失败阶段',
  `error_message`     TEXT          DEFAULT NULL,
  `verify_diff`       TEXT          DEFAULT NULL COMMENT '数据完整性校验差异',
  `operator_id`       BIGINT        DEFAULT NULL,
  `operator_name`     VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_restore_started` (`started_at` DESC),
  INDEX `idx_restore_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- import_record（2026-06-24 DEV-002，文章批量导入）
-- ============================================================
CREATE TABLE IF NOT EXISTS `import_record` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `file_name`       VARCHAR(255)  NOT NULL COMMENT '上传的 ZIP 文件名',
  `status`          VARCHAR(16)   NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `total_count`     INT           NOT NULL DEFAULT 0,
  `success_count`   INT           NOT NULL DEFAULT 0,
  `fail_count`     INT           NOT NULL DEFAULT 0,
  `error_message`   TEXT          DEFAULT NULL,
  `started_at`      DATETIME      NOT NULL,
  `finished_at`     DATETIME      DEFAULT NULL,
  `operator_id`     BIGINT        DEFAULT NULL,
  `operator_name`   VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_import_started` (`started_at` DESC),
  INDEX `idx_import_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- article_attachment（2026-07-01 DEV-001，文章附件）
-- ============================================================
-- 一文一附件：article_id UNIQUE 约束（DB 层兜底，强制"先删旧附件再上传"）
-- 二段删除：softDelete (deleted=1, 文件保留) → hardDelete (先删文件后删 DB)
-- 公开下载软删返 410 Gone
CREATE TABLE IF NOT EXISTS `article_attachment` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `article_id`      BIGINT        NOT NULL                COMMENT '所属文章 ID（UNIQUE，一文一附件）',
  `file_name`       VARCHAR(255)  NOT NULL                COMMENT '原始文件名（含扩展名）',
  `file_path`       VARCHAR(500)  NOT NULL                COMMENT '相对 blog.attachment.local.dir 路径',
  `file_size`       BIGINT        NOT NULL DEFAULT 0      COMMENT '字节数',
  `mime_type`       VARCHAR(100)  DEFAULT NULL            COMMENT 'MIME 类型，如 application/zip',
  `deleted`         TINYINT       NOT NULL DEFAULT 0      COMMENT '0=未删 / 1=软删（文件保留在磁盘）',
  `created_at`      DATETIME      NOT NULL                COMMENT 'Java 填北京时间',
  `updated_at`      DATETIME      NOT NULL                COMMENT 'Java 填北京时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_attachment_article` (`article_id`),
  INDEX `idx_article_attachment_deleted` (`deleted`),
  INDEX `idx_article_attachment_updated` (`updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- audit_log（2026-07-01 DEV-004，审计日志）
-- ============================================================
-- AOP 自动拦截 admin 写端点 + 公开下载端点，每条操作一行记录
-- operator：admin 端点 = AuthContext.username；公开下载 = 'anonymous'
-- operation：CREATE / UPDATE / DELETE / APPROVE / REJECT / DOWNLOAD
-- target：模块名称（20 个，含 settings 9 子项，详见 AuditLogAspect 路径映射表）
-- 配套：AuditLog / AuditLogMapper / AuditLogService / AuditLogAspect / AuditLogController
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `operator`        VARCHAR(50)   NOT NULL                COMMENT '操作人 username（admin）或 anonymous（公开下载）',
  `operation`       VARCHAR(20)   NOT NULL                COMMENT 'CREATE/UPDATE/DELETE/APPROVE/REJECT/DOWNLOAD',
  `target`          VARCHAR(100)  NOT NULL                COMMENT '模块名称（20 个）',
  `detail`          VARCHAR(500)  DEFAULT NULL            COMMENT '操作详情',
  `ip`              VARCHAR(45)   DEFAULT NULL            COMMENT '操作 IP',
  `created_at`      DATETIME      NOT NULL                COMMENT 'Java 填北京时间',
  PRIMARY KEY (`id`),
  INDEX `idx_audit_log_created` (`created_at` DESC),
  INDEX `idx_audit_log_target` (`target`),
  INDEX `idx_audit_log_operation` (`operation`),
  INDEX `idx_audit_log_operator` (`operator`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- type=public → 放行（GET 任意，写方法按 isPublicWriteAllowed 显式允许）
-- type=admin  → 必须鉴权
INSERT IGNORE INTO `api_whitelist` (`path_prefix`, `type`, `enabled`, `description`) VALUES
-- public
('/auth/login', 'public', 1, '登录'),
('/auth/me/password', 'public', 1, '改密（自身鉴权）'),
('/public/', 'public', 1, '公开端点'),
('/articles', 'public', 1, '文章公开端点'),
('/comments', 'public', 1, '评论公开端点'),
('/health', 'public', 1, '健康检查'),
('/v3/api-docs', 'public', 1, 'Swagger API 文档（生产可关）'),
('/swagger-ui', 'public', 1, 'Swagger UI（生产可关）'),
-- admin
('/admin/', 'admin', 1, '所有 admin/* 路径必须鉴权'),
('/auth/me', 'admin', 1, '获取当前用户信息'),
('/auth/logout', 'admin', 1, '登出'),
('/auth/devices', 'admin', 1, '设备管理'),
('/uploads', 'admin', 1, '文件上传（multipart）'),
('/admin/articles/{id}/attachment', 'admin', 1, '文章附件上传/软删（2026-07-01 DEV-001）'),
('/admin/attachments', 'admin', 1, '附件后台列表/恢复/硬删（2026-07-01 DEV-001）'),
('/admin/audit-logs', 'admin', 1, '审计日志查询（2026-07-01 DEV-004）'),
('/admin/settings/upload-md', 'admin', 1, '上传 md 文档批量更新 settings（2026-07-01 DEV-005）');

-- 站点设置默认值（按 section 存 JSON）
-- profile / blog / social / preferences / theme / advanced / techstack / experience
INSERT IGNORE INTO `site_settings` (`section`, `data`) VALUES
('profile',    JSON_OBJECT('nickname', 'Corey', 'email', 'corey@example.com', 'avatar', '', 'bio', '个人博客作者', 'location', '')),
('blog',       JSON_OBJECT('title', '加载中', 'subtitle', '', 'description', '', 'keywords', '', 'author', '')),
('social',     JSON_OBJECT('github', '', 'twitter', '', 'email', '', 'weibo', '', 'rss', true)),
('preferences',JSON_OBJECT('theme', 'light', 'language', 'zh-CN', 'timezone', 'Asia/Shanghai')),
('theme',      JSON_OBJECT('primaryColor', '#2f6f5e', 'accentColor', '#c97b3f', 'mode', 'auto')),
('advanced',   JSON_OBJECT('enableCache', true, 'enableRss', true, 'enableSearch', true, 'commentModeration', true)),
('techstack',  JSON_OBJECT('groups', JSON_ARRAY())),
('experience', JSON_OBJECT('items', JSON_ARRAY()));
