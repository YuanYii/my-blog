-- 测试基线种子数据
-- 注意：created_at/updated_at 必须显式给值（NOT NULL，无 DEFAULT）

-- admin 用户（密码 123456）
INSERT INTO user (id, username, password_hash, nickname, role, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$0YSdd8Tf7xcsmAk.05Kn4uEDSUAIT7ukAZqLnUMLrE5Gnd4wj5jEa', 'Test Admin', 'ADMIN',
        datetime('now'), datetime('now'));

-- API 白名单（测试中 context-path=/ 因此 filter 看到的 URI 无 /api/v1 前缀，用无前缀版本）
-- 精确对齐生产白名单逻辑：/auth/me 和 /auth/me/password 分开为 admin/public
INSERT INTO api_whitelist (path_prefix, type, enabled, description, created_at, updated_at) VALUES
  ('/auth/me/password', 'public', 1, '改密（需自身 token 但不走 admin filter）', datetime('now'), datetime('now')),
  ('/auth/login',       'public', 1, '登录',                                      datetime('now'), datetime('now')),
  ('/auth/me',          'admin',  1, '当前用户信息（需 token）',                   datetime('now'), datetime('now')),
  ('/articles',         'public', 1, '文章公开端点',                               datetime('now'), datetime('now')),
  ('/comments',         'public', 1, '评论公开端点',                               datetime('now'), datetime('now')),
  ('/health',           'public', 1, '健康检查',                                   datetime('now'), datetime('now')),
  ('/public',           'public', 1, '公开杂项端点',                               datetime('now'), datetime('now')),
  ('/admin/',           'admin',  1, 'admin 路径（最长前缀兜底）',                  datetime('now'), datetime('now'));

-- 已授权测试设备（用于需要鉴权的测试）
INSERT INTO admin_device (device_id, device_name, status, created_at, updated_at)
VALUES ('test-device-001', 'Test Device', 'approved', datetime('now'), datetime('now'));

-- 测试用分类
INSERT INTO category (id, name, slug, visible, sort, created_at, updated_at)
VALUES (1, '测试分类', 'test-category', 1, 0, datetime('now'), datetime('now'));

-- 测试用标签
INSERT INTO tag (id, name, slug, created_at)
VALUES (1, '测试标签', 'test-tag', datetime('now'));

-- 测试用已发布文章（供 CommentControllerTest 使用）
INSERT INTO article (id, title, slug, content_md, status, view_count, category_id, published_at, created_at, updated_at, deleted)
VALUES (1, '测试文章', 'test-article', '# 测试', 1, 0, 1, datetime('now'), datetime('now'), datetime('now'), 0);
