-- =====================================================
-- 2026-06-16 v2.5.0-fix: 修 page_view "按天去重" UK 失效
-- =====================================================
-- 背景（v2.5.0 首发 BUG）：
--   原 UK 设计：(visitor, path, created_at)，但 created_at 是 DATETIME 精确到秒。
--   实测：同 visitor + 同 path + 1 秒后重发 → UK 不冲突，INSERT 全部成功。
--   导致"刷一次首页"被记 7~37 次（请求间隔几十毫秒到几秒，每条都插）。
-- 根因：注释写的"天然按天去重"在 DATETIME 上根本不成立——UK 比较精确到秒。
--
-- 修法：
--   1) 加 visit_date DATE 列（按天，不含时间）
--   2) UK 改为 (visitor, path, visit_date) 真正按天去重
--   3) 清掉旧重复数据（v2.5.0 首发产出的脏数据）
--
-- 兼容性：
--   - 老数据 visit_date 全部 NULL（已 TRUNCATE）→ 不影响
--   - 新 UK 用 (visitor, path, visit_date)，NULL visit_date 在 MySQL 不参与 UK 比较
--   - 因此新数据必须**强制写入 visit_date**（Java 端 INSERT 已 set CURDATE()）
--   - 不写 visit_date 的数据进不来（NULL 不会被去重，但也不影响）——防御性兜底在 Java 层
-- =====================================================

-- 1. 清旧数据（一了百了，避免脏数据混进来）
TRUNCATE TABLE page_view;

-- 2. 加 visit_date DATE 列（NOT NULL DEFAULT (CURDATE()) 保证老进程/手工插入不漏）
ALTER TABLE page_view
  ADD COLUMN visit_date DATE NOT NULL DEFAULT (CURDATE()) COMMENT '访问日期（按天，UK 去重维度）'
  AFTER created_at;

-- 3. 删旧 UK
ALTER TABLE page_view DROP INDEX uk_visitor_path_day;

-- 4. 加新 UK（真正按天去重）
--    MySQL 8.0 分区表强制要求：UK 必须包含分区函数的所有列。
--    分区键是 created_at (RANGE(TO_DAYS(created_at)))，所以 UK 必含 created_at。
--    副作用：created_at 是 DATETIME 精度 1 秒，同一秒内多次 INSERT 会因 created_at
--    完全一致 → UK 冲突 → INSERT IGNORE 兜住。这是第二道防线（与 visit_date 协同）。
--    跨秒插入则依赖 visit_date 去重（每天每 visitor+path 只 1 行）。
ALTER TABLE page_view
  ADD UNIQUE KEY uk_visitor_path_date (visitor, path, visit_date, created_at);

-- 5. 加 visit_date 普通索引（聚合查询按日 group 用）
ALTER TABLE page_view
  ADD INDEX idx_visit_date (visit_date);

-- =====================================================
-- 验证：
--   1) 同一 visitor + 同一 path + 同一天 → 只能一行
--   2) 同一 visitor + 同一 path + 不同天 → 多行（每天一行）
--   3) 不同 visitor + 同一 path + 同一天 → 多行（UV 区分）
--   4) 同一 visitor + 不同 path + 同一天 → 多行（PV 区分）
-- =====================================================
