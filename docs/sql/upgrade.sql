-- ⚠️  警告：ALTER TABLE 语句的特殊处理 ⚠️
-- deploy-server.sh §5.1 用 sed '/ALTER TABLE/d' 过滤了本文件中所有 ALTER TABLE，
-- 改为 if-block 逐条 PRAGMA 检查后执行（防止重复执行报错）。
-- 因此：
--   1. 向本文件新增 ALTER TABLE 时，必须同步更新 deploy-server.sh §5.1 中
--      对应的 if-block（搜索 "Patching" 关键字找到已有模式）
--   2. 本文件中的 ALTER TABLE 仅作为文档参考（实际不会被执行）
--   3. CREATE TABLE/INDEX IF NOT EXISTS 部分会正常执行（不受过滤影响）
--
-- ============================================================
-- 通用增量升级脚本（幂等，可重复执行）
-- deploy-server.sh full 模式在 DB 已存在时执行此脚本
-- 新增字段/表在此文件中追加，不要修改下方已有语句
-- ============================================================

-- 1. article.is_pinned 置顶字段（v6.0.2+）
ALTER TABLE article ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_article_pinned ON article(is_pinned);

-- 2. api_whitelist 补充 /seo/ 与 /sitemap.xml（v7.0.0+）
INSERT OR IGNORE INTO api_whitelist (path_prefix, type, enabled, description, created_at, updated_at) VALUES
  ('/seo/', 'public', 1, 'SEO 渲染直出端点', datetime('now', 'localtime'), datetime('now', 'localtime')),
  ('/sitemap.xml', 'public', 1, 'Sitemap 站点地图', datetime('now', 'localtime'), datetime('now', 'localtime'));
