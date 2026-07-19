-- ============================================================
-- 通用增量升级脚本（幂等，可重复执行）
-- deploy-server.sh full 模式在 DB 已存在时执行此脚本
-- 新增字段/表在此文件中追加，不要修改下方已有语句
-- ============================================================

-- 1. import_record 文章导入记录（v4.3.0+）
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

-- 2. upgrade_record 系统升级记录表（v5.3.0+）
CREATE TABLE IF NOT EXISTS upgrade_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  target_version  VARCHAR(32)  NOT NULL,
  mode            VARCHAR(16)  NOT NULL,
  import_db       TINYINT      NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL,
  started_at      DATETIME     NOT NULL,
  finished_at     DATETIME,
  from_version    VARCHAR(32),
  error_message   TEXT,
  operator_id     BIGINT,
  operator_name   VARCHAR(64),
  ip              VARCHAR(45)
);
CREATE INDEX IF NOT EXISTS idx_upgrade_record_started_at ON upgrade_record(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_upgrade_record_status ON upgrade_record(status);

-- 3. backup_record.trace_id（v4.2.0+）
-- PLACEHOLDER_BACKUP_TRACE_ID

-- 4. article.is_pinned 置顶字段（v6.0.2+）
-- PLACEHOLDER_ARTICLE_IS_PINNED
