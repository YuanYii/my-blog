-- =====================================================
-- 2026-06-16 v2.5.0: 访问统计 page_view 表
-- =====================================================
-- 背景：原 article.view_count 累加无法区分访客/今日/UV/趋势图全是 mock。
-- 解决：新建 page_view 表（按月分区）记录每次公开页访问，后端 middleware 采集，
--       仪表盘基于此表做今日 PV/UV/近 30 天趋势等真实统计。
-- 关联：v2.5.0 changelog - docs/changelogs/2026-06-16-page-view-stats.md

CREATE TABLE IF NOT EXISTS page_view (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  path        VARCHAR(200) NOT NULL                COMMENT 'URL 路径（不含域名）',
  article_id  BIGINT       NULL                    COMMENT '文章详情才有，列表/其它页 NULL',
  visitor     CHAR(36)     NOT NULL                COMMENT '访客 UUID（前端 localStorage 持久化）',
  ip          VARCHAR(45)  NULL                    COMMENT '客户端 IP（X-Forwarded-For 解析）',
  user_agent  VARCHAR(200) NULL                    COMMENT '浏览器 UA',
  referer     VARCHAR(500) NULL                    COMMENT '来源 URL（document.referrer）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
  PRIMARY KEY (id, created_at),  -- 分区表主键必须包含分区列
  UNIQUE KEY uk_visitor_path_day (visitor, path, created_at),  -- 天然去重：当天同 visitor+path 只一行
  INDEX idx_path (path),
  INDEX idx_article (article_id),
  INDEX idx_created (created_at),
  INDEX idx_visitor (visitor)
)
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
PARTITION BY RANGE (TO_DAYS(created_at)) (
  PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
  PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
  PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
  PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
  PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
  PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
  PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
  PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- =====================================================
-- 表设计要点：
-- 1. 主键 (id, created_at)：分区表强制要求主键含分区列
-- 2. UNIQUE (visitor, path, created_at)：
--    - visitor 是前端 localStorage 生成的 UUID
--    - path 是 URL 路径（如 /post/spring-boot-jwt-security）
--    - created_at 用于按天去重
--    - 用 INSERT IGNORE 即可天然去重（同 visitor 当天再访问同 path 不会插第二行）
-- 3. 分区按月：单分区超 100MB 时再考虑按天
-- 4. 老分区清理：DROP PARTITION p202606（秒级，无锁）
-- =====================================================
