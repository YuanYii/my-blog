-- ============================================================
-- Migration: site_settings 表
-- 日期: 2026-06-08
-- 说明: 把原 SettingsController 的 static 内存 Map（blog / social /
--       preferences / theme / advanced）持久化到 DB
-- 配套: 前台首页可走 /api/v1/public/settings/{section} 公开读
-- 缓存: Redis（5min TTL，写时主动失效）
-- ============================================================

-- 1) 建表
CREATE TABLE IF NOT EXISTS `site_settings` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `section`    VARCHAR(32)  NOT NULL UNIQUE                  COMMENT '配置段：blog/social/preferences/theme/advanced',
  `data`       JSON         NOT NULL                         COMMENT '配置数据（JSON 对象）',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站点设置（按 section 存 JSON）';

-- 2) 初始化 5 个 section 的默认值（对应原 SettingsController static 块的默认值）
INSERT INTO `site_settings` (`section`, `data`) VALUES
  ('blog', JSON_OBJECT(
    'title',       'Yuan Yi · 个人博客',
    'subtitle',    '后端工程师的日常',
    'description', '记录技术、读书、生活',
    'copyright',   '© 2026 Yuan Yi',
    'logo',        ''
  )),
  ('social', JSON_OBJECT(
    'github',      '',
    'twitter',     '',
    'emailPublic', '',
    'wechat',      '',
    'weibo',       '',
    'rss',         '/rss.xml'
  )),
  ('preferences', JSON_OBJECT(
    'language',  'zh-CN',
    'timezone',  'Asia/Shanghai',
    'density',   'comfortable',
    'codeTheme', 'github'
  )),
  ('theme', JSON_OBJECT(
    'mode',          'auto',
    'primaryColor',  '#2f6f5e',
    'accentColor',   '#c97b3f',
    'fontFamily',    'serif'
  )),
  ('advanced', JSON_OBJECT(
    'enableCache',        true,
    'enableRss',          true,
    'enableSearch',       true,
    'commentModeration',  true
  ));

-- 3) api_whitelist 加公开读端点
-- /api/v1/public/settings 前缀 → public → AdminAuthFilter 放行
INSERT INTO `api_whitelist` (`path_prefix`, `type`, `description`) VALUES
  ('/api/v1/public/settings', 'public', '站点公开设置（blog/social，公开读）');
