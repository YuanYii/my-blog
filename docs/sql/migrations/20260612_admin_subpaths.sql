-- =============================================================
-- 2026-06-12 安全补丁：补齐 admin 子路径白名单
-- =============================================================
-- 原 api_whitelist 把 /api/v1/articles 和 /api/v1/comments 标为 public，
-- 但这两个 controller 下还挂着 admin-only 子路径（/articles/admin/all、
-- /articles/id/{id}、/comments/admin），它们被前缀匹配错认成 public，
-- AdminAuthFilter 完全跳过鉴权——匿名即可调用。
--
-- 配合 AdminAuthFilter 新加的"public-prefix 上写方法默认要求鉴权"逻辑，
-- 这里再显式注册 admin 子前缀，让 ApiWhitelistService.matchPath（最长前缀优先）
-- 把 /articles/admin/* /articles/id/* /comments/admin/* 识别为 admin。
--
-- 注意：本 migration 幂等——重复执行也只会被 UNIQUE KEY (path_prefix, type) 拦掉，
-- 用 INSERT IGNORE 而不是 INSERT 保证。

INSERT IGNORE INTO `api_whitelist` (`path_prefix`, `type`, `enabled`, `description`, `created_at`, `updated_at`) VALUES
  ('/api/v1/articles/admin', 'admin', 1, '文章 admin 子路径（admin/all）', NOW(), NOW()),
  ('/api/v1/articles/id',    'admin', 1, '文章 admin 详情（按 id）',        NOW(), NOW()),
  ('/api/v1/comments/admin', 'admin', 1, '评论 admin 列表',                 NOW(), NOW());

-- 刷新生效：
-- 1) ApiWhitelistService 启动时 + 每 5 min 定时刷新；
-- 2) 也可调 POST /api/v1/admin/api-whitelist/refresh 立即刷。
