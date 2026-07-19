-- ============================================================
-- 通用增量升级脚本（幂等，可重复执行）
-- deploy-server.sh full 模式在 DB 已存在时执行此脚本
-- 新增字段/表在此文件中追加，不要修改下方已有语句
-- ============================================================

-- 1. article.is_pinned 置顶字段（v6.0.2+）
ALTER TABLE article ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_article_pinned ON article(is_pinned);
