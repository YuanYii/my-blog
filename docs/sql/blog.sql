-- =============================================
-- 个人博客数据库 schema
-- 数据库: MySQL 8.0+
-- 字符集: utf8mb4 / utf8mb4_unicode_ci
-- 排序: utf8mb4_0900_ai_ci
-- =============================================

CREATE DATABASE IF NOT EXISTS `blog`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `blog`;

-- =============================================
-- 1. user 表（管理员账号）
-- =============================================
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)  NOT NULL                COMMENT '登录用户名',
  `password_hash` VARCHAR(200) NOT NULL                COMMENT 'BCrypt 加密后的密码',
  `nickname`      VARCHAR(50)                          COMMENT '昵称',
  `email`         VARCHAR(100)                         COMMENT '邮箱',
  `avatar`        VARCHAR(500)                         COMMENT '头像 URL',
  `bio`           TEXT                                 COMMENT '个人简介',
  `location`      VARCHAR(100)                         COMMENT '所在地',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT '角色',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB COMMENT ='管理员账号';

-- 默认账号 admin / 密码 123456（BCrypt 加密）
-- 实际部署时务必修改
INSERT INTO `user` (`username`, `password_hash`, `nickname`, `email`, `bio`, `location`, `role`)
VALUES (
  'admin',
  '$2a$10$0YSdd8Tf7xcsmAk.05Kn4uEDSUAIT7ukAZqLnUMLrE5Gnd4wj5jEa',
  'Yuan Yi',
  'hello@example.com',
  '后端工程师，在上海工作。日常写 Java / Spring Boot，偶尔折腾前端。',
  '上海',
  'ADMIN'
);

-- =============================================
-- 2. category 表（分类）
-- =============================================
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `name`       VARCHAR(50)  NOT NULL,
  `slug`       VARCHAR(50)  NOT NULL,
  `description` VARCHAR(500)                         COMMENT '描述',
  `sort`       INT          NOT NULL DEFAULT 0       COMMENT '排序（数字越小越靠前）',
  `visible`    TINYINT      NOT NULL DEFAULT 1       COMMENT '0-隐藏 1-显示',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`),
  KEY `idx_sort` (`sort`)
) ENGINE = InnoDB COMMENT ='文章分类';

-- =============================================
-- 3. tag 表（标签）
-- =============================================
DROP TABLE IF EXISTS `tag`;
CREATE TABLE `tag` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `name`       VARCHAR(50)  NOT NULL,
  `slug`       VARCHAR(50)  NOT NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`)
) ENGINE = InnoDB COMMENT ='文章标签';

-- =============================================
-- 4. article 表（文章）
-- =============================================
DROP TABLE IF EXISTS `article`;
CREATE TABLE `article` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(200) NOT NULL,
  `slug`         VARCHAR(200) NOT NULL,
  `summary`      VARCHAR(500)                         COMMENT '摘要',
  `content_md`   MEDIUMTEXT   NOT NULL                COMMENT 'Markdown 原文',
  `cover_url`    VARCHAR(500)                         COMMENT '封面图 URL',
  `status`       TINYINT      NOT NULL DEFAULT 0       COMMENT '0-草稿 1-已发布 2-已归档',
  `view_count`   INT          NOT NULL DEFAULT 0,
  `category_id`  BIGINT                                COMMENT '分类 ID',
  `published_at` DATETIME                              COMMENT '发布时间',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除 0-未删 1-已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slug` (`slug`),
  KEY `idx_status_published` (`status`, `published_at` DESC),
  KEY `idx_category` (`category_id`),
  KEY `idx_deleted` (`deleted`)
) ENGINE = InnoDB COMMENT ='文章';

-- =============================================
-- 5. article_tag 表（文章-标签关联）
-- =============================================
DROP TABLE IF EXISTS `article_tag`;
CREATE TABLE `article_tag` (
  `article_id` BIGINT NOT NULL,
  `tag_id`     BIGINT NOT NULL,
  PRIMARY KEY (`article_id`, `tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE = InnoDB COMMENT ='文章-标签多对多';

-- =============================================
-- 6. comment 表（评论）
-- =============================================
DROP TABLE IF EXISTS `comment`;
CREATE TABLE `comment` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `article_id` BIGINT       NOT NULL,
  `parent_id`  BIGINT       NOT NULL DEFAULT 0       COMMENT '父评论 ID，0 表示顶级',
  `nickname`   VARCHAR(50)  NOT NULL,
  `email`      VARCHAR(100)                          COMMENT '邮箱（不公开）',
  `website`    VARCHAR(200)                          COMMENT '个人网站',
  `content`    TEXT         NOT NULL,
  `ip`         VARCHAR(45)                           COMMENT '评论者 IP',
  `user_agent` VARCHAR(500)                          COMMENT '浏览器 UA',
  `status`     TINYINT      NOT NULL DEFAULT 0       COMMENT '0-待审 1-已通过 2-已屏蔽',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_article` (`article_id`),
  KEY `idx_status_created` (`status`, `created_at` DESC),
  KEY `idx_parent` (`parent_id`)
) ENGINE = InnoDB COMMENT ='评论（树形）';

-- =============================================
-- 7. article_view_log 表（仪表盘昨日数据聚合）
-- =============================================
DROP TABLE IF EXISTS `article_view_log`;
CREATE TABLE `article_view_log` (
  `id`         BIGINT   NOT NULL AUTO_INCREMENT,
  `article_id` BIGINT   NOT NULL,
  `view_date`  DATE     NOT NULL                      COMMENT '访问日期（用于按天聚合）',
  `view_count` INT      NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_date` (`article_id`, `view_date`),
  KEY `idx_date` (`view_date`)
) ENGINE = InnoDB COMMENT ='文章每日阅读数（仪表盘聚合用）';

-- =============================================
-- 测试数据：分类
-- =============================================
INSERT INTO `category` (`name`, `slug`, `description`, `sort`, `visible`) VALUES
  ('技术', 'tech', '后端开发、架构设计、技术选型相关内容', 1, 1),
  ('生活', 'life', '日常生活、读书笔记、偶尔的杂感', 2, 1),
  ('随笔', 'thoughts', '工作感悟、思考、关于技术与人的关系', 3, 1),
  ('读书笔记', 'reading', '读过的书、读后感、推荐与吐槽', 4, 1),
  ('工具', 'tools', '开发工具、效率软件、命令行技巧', 5, 1);

-- =============================================
-- 测试数据：标签
-- =============================================
INSERT INTO `tag` (`name`, `slug`) VALUES
  ('Java', 'java'),
  ('Spring Boot', 'spring-boot'),
  ('JWT', 'jwt'),
  ('MySQL', 'mysql'),
  ('Redis', 'redis'),
  ('Nuxt 3', 'nuxt-3'),
  ('Vue', 'vue'),
  ('Docker', 'docker');

-- =============================================
-- 测试数据：1 篇示例文章
-- =============================================
INSERT INTO `article` (`title`, `slug`, `summary`, `content_md`, `status`, `view_count`, `category_id`, `published_at`)
VALUES (
  'Spring Boot 3 整合 Spring Security 6：JWT 鉴权最佳实践',
  'spring-boot-jwt-security',
  '从零搭建一个前后端分离项目的鉴权体系，对比 Session 与 JWT 的取舍，详细讲解 JJWT 0.12 的新 API 用法。',
  '# Spring Boot 3 JWT 鉴权

## 为什么选 JWT

传统的 Session 方案在前后端分离下面临几个问题：
- 需要服务端存储 session id
- 跨域时 Cookie 处理繁琐
- 移动端不天然支持 Cookie

JWT 的优势是**无状态**：服务端不存会话信息。

## 依赖

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
```

## Security 配置

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) {
    http.csrf(csrf -> csrf.disable());
    return http.build();
}
```',
  1,
  1248,
  1,
  '2026-06-05 14:30:00'
);

-- 关联标签
INSERT INTO `article_tag` (`article_id`, `tag_id`) VALUES
  (1, 1), (1, 2), (1, 3);

-- =============================================
-- 7. api_whitelist 表（AdminAuthFilter 内部路由配置）
-- =============================================
DROP TABLE IF EXISTS `api_whitelist`;
CREATE TABLE `api_whitelist` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `path_prefix` VARCHAR(200) NOT NULL COMMENT 'API 路径前缀，例 /api/v1/articles',
  `type`        VARCHAR(20)  NOT NULL COMMENT 'public-公开放行 / admin-必须鉴权',
  `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 / 0-禁用',
  `description` VARCHAR(200)          DEFAULT NULL COMMENT '用途说明',
  `created_at`  DATETIME     NOT NULL COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_path_type` (`path_prefix`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API 路由白名单（AdminAuthFilter 用）';

-- 公开 API（AdminAuthFilter 放行）
INSERT INTO `api_whitelist` (`path_prefix`, `type`, `description`) VALUES
  ('/api/v1/health',  'public', '健康检查'),
  ('/api/v1/articles','public', '文章列表/详情/分类/标签'),
  ('/api/v1/comments','public', '评论列表/提交'),
  ('/api/v1/auth',     'public', '登录/当前用户');

-- Admin API（AdminAuthFilter 强制鉴权 + 设备白名单）
-- 2026-06-12 重要：以下 admin 子前缀必须比对应 public 前缀更精细——
-- ApiWhitelistService.matchPath 走"最长前缀优先"，这样 /articles/admin/all
-- 才会被识别为 admin，而不是被 /api/v1/articles (public) 抢到。
INSERT INTO `api_whitelist` (`path_prefix`, `type`, `description`) VALUES
  ('/api/v1/admin/dashboard', 'admin', '仪表盘'),
  ('/api/v1/admin/posts',     'admin', '文章管理'),
  ('/api/v1/admin/articles',  'admin', '文章 CRUD'),
  ('/api/v1/admin/comments',  'admin', '评论审核'),
  ('/api/v1/admin/categories','admin', '分类管理'),
  ('/api/v1/admin/tags',      'admin', '标签管理'),
  ('/api/v1/admin/devices',   'admin', '设备管理'),
  ('/api/v1/admin/settings',  'admin', '站点设置'),
  ('/api/v1/admin/api-whitelist', 'admin', 'API 白名单管理'),
  -- 2026-06-12 安全补丁：嵌在 public 前缀里的 admin 子路径
  ('/api/v1/articles/admin', 'admin', '文章 admin 子路径（admin/all）'),
  ('/api/v1/articles/id',    'admin', '文章 admin 详情（按 id）'),
  ('/api/v1/comments/admin', 'admin', '评论 admin 列表');
