-- article.is_pinned 置顶字段（2026-07-19 v6.0.2）
-- 增量迁移：为已有数据库添加 is_pinned 字段
ALTER TABLE article ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_article_pinned ON article(is_pinned);
