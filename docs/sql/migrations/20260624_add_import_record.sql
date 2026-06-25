-- import_record 文章导入记录（2026-06-24 DEV-002）
-- 增量迁移：为已有数据库添加 import_record 表
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
CREATE INDEX IF NOT EXISTS idx_import_record_started_at ON import_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_import_record_status ON import_record(status);
