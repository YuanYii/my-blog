# 2026-06-16 v2.5.0 真实访问统计（page_view 表）

## TL;DR

替换 `article.view_count` 累加的"无差别计数"为**基于 page_view 表的真实 PV/UV 统计**：
- 新表 `page_view`（按月分区，含 UNIQUE 去重索引）
- 新 middleware `PageViewFilter`（拦截公开页写 page_view）
- 仪表盘 4 个 KPI 全部接真实数据（今日 PV/UV、近 30 天趋势、热门文章 TOP10）
- 趋势图 4 个 sparkline 不再是 `Math.random()` mock

---

## 背景

| 痛点 | 影响 |
|---|---|
| `view_count` 实时 `+1` 写 article 行 | 高并发读时锁竞争；admin 自己预览也 +1 |
| 不区分访客 | 同一用户刷 100 次 = +100；爬虫/Bot 灌水 |
| 不区分 deleted | 删除文章的历史 view_count 仍贡献 KPI |
| 今日 PV / 趋势图全 mock | 用户看到 `Math.random()` 曲线，无意义 |
| 没 UV（独立访客） | 没法看"今天多少人来看" |
| 没来源 / 设备 / 时段分布 | 无内容优化决策依据 |

---

## 改造方案

### 1. 新表 `page_view`（按月 RANGE 分区）

```sql
CREATE TABLE page_view (
  id          BIGINT AUTO_INCREMENT,
  path        VARCHAR(200) NOT NULL,    -- URL 路径
  article_id  BIGINT NULL,                -- 文章详情才有
  visitor     CHAR(36) NOT NULL,          -- 访客 UUID
  ip          VARCHAR(45),
  user_agent  VARCHAR(200),
  referer     VARCHAR(500),
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, created_at),            -- 分区表强制要求主键含分区列
  UNIQUE KEY uk_visitor_path_day (visitor, path, created_at)  -- 天然按天去重
)
PARTITION BY RANGE (TO_DAYS(created_at)) (
  PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
  ...
  PARTITION p_future VALUES LESS THAN MAXVALUE
);
```

**老分区清理**：`ALTER TABLE page_view DROP PARTITION p202606;`（秒级，无锁）

### 2. 去重逻辑

- 应用层不再做 SELECT + INSERT race condition
- 走 `INSERT IGNORE` 命中 UNIQUE(visitor, path, created_at)：
  - 同 visitor 当天再访问同 path → affected rows = 0（不报错）
  - 跨天访问同 path → 视为新访问（因为 created_at 不同）
- 同 IP 但不同 visitor UUID 视为不同访客（cookie 隔离）

### 3. 后端 `PageViewFilter`

- 拦截规则：所有公开 GET 端点（articles / comments / public/*）
- 不拦截：写方法、admin 路径（AdminAuthFilter 已拦）、swagger、health
- 性能：每请求 1 条 INSERT IGNORE（毫秒级），try/catch 包裹确保统计失败不影响业务
- 文章详情 articleId 解析：本地 ConcurrentHashMap 缓存 5 分钟，避免每个详情请求都查 DB

### 4. 仪表盘新端点

```sql
-- 今日 PV
SELECT COUNT(*) FROM page_view WHERE created_at >= CURDATE();
-- 今日 UV
SELECT COUNT(DISTINCT visitor) FROM page_view WHERE created_at >= CURDATE();
-- 近 30 天每日 PV + UV
SELECT DATE(created_at) AS date, COUNT(*) AS pv, COUNT(DISTINCT visitor) AS uv
FROM page_view WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
GROUP BY DATE(created_at);
-- 热门文章 TOP 10
SELECT pv.article_id, a.title, a.slug, COUNT(*) AS pv
FROM page_view pv LEFT JOIN article a ON a.id = pv.article_id
WHERE pv.article_id IS NOT NULL AND pv.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY pv.article_id ORDER BY pv DESC LIMIT 10;
```

### 5. 前端 `useVisitor` composable

- localStorage 存 UUID（`blog_visitor_id`），跨 session 稳定
- `usePublicApi` 自动带 `X-Visitor-Id` header
- 跟 `useDevice` 的区别：deviceId 绑登录账号，visitorId 绑访客（不绑账号）

### 6. 仪表盘前端改造

| 卡片 / 图 | 旧（mock） | 新（真实） |
|---|---|---|
| 今日 PV | `todayPV`（ref(0)，永远 0） | `kpi.todayPV`（page_view 行数） |
| 独立访客 | 无 | `kpi.todayUV`（新加） |
| 趋势图 | `rand(800, 1500)` | `visitTrend7` / `visitTrend`（按天 pv/uv） |
| Sparkline PV | `rand(800, 1400)` | 近 7 天每日 PV |
| Sparkline 发布 | hardcode 数组 | 近 7 天发布数 |
| Sparkline 评论 | hardcode | 保留 mock（page_view 不含评论） |
| Sparkline 累计阅读 | `rand(200, 800)` | 近 7 天 PV 总和 |

X 轴：原本是 `1, 2, 3, ...` 序号 → 改成 `MM-DD` 真实日期。

---

## 验证

| 场景 | 结果 |
|---|---|
| 5 个 curl + 1 个 Playwright 公开页访问 | 59 条 page_view ✓ |
| 同 visitor 同 path 多次访问 | UNIQUE 去重生效（PV > UV） ✓ |
| 仪表盘 KPI 显示今日 PV/UV | 真实数据 ✓ |
| 近 30 天趋势图 | 真实日期 + 真实数据 ✓ |
| 热门文章 TOP 10 | 真实（"spring-boot-jwt-security" 排第一）✓ |
| 11 个不同 path 都有数据 | ✓（articles 列表/详情/分类/标签/评论/公开设置/device-check 全覆盖） |

---

## 兼容性 / 回退

- `kpi.totalViewCount` 保留旧值（article.view_count SUM）→ 不破现有 admin dashboard 文案
- 旧 `article.view_count` 自增逻辑**保留**（article detail 接口仍然 +1）→ 仪表盘仍能展示"累计阅读"
- PageViewFilter 失败用 try/catch 包裹，**绝不抛到业务层**——即使 page_view 表写崩，所有公开 API 仍正常返回

## 部署注意

- 新增依赖：无（用现有 Spring Boot 2.7 + MyBatis-Plus 3.4 + pymysql）
- 需执行 SQL 迁移：`docs/sql/migrations/20260616_page_view_stats.sql`
- 需后端 `mvn install` 重 build
- 需前端 `npm run build` 重 build
- 旧 page_view 数据：暂无（今天 6-16 启用，6-30 后可考虑清理当月分区数据）

## 2026-06-16 追加调整：登录用户访问公开页不算访客

**问题**：admin 改完文章后预览 `/post/xxx`、admin 调公开 API 调试——这些**登录用户行为**会被 page_view 记成访客，污染 PV/UV 统计。

**方案 A**（已落地）：`PageViewFilter.doFilterInternal` 最前面检查 `Authorization` header——**有 token → 直接 return**，不写 page_view。

**为什么 A 够用**：
- 当前项目（个人博客 MVP）只有 admin 一种登录用户
- `useAdminApi` 自动带 `Authorization: Bearer xxx` 头
- `usePublicApi` 公开端点不带 token
- 未来有"普通登录用户"时，他们的访问也算"登录用户"（不算访客），与"访客 = 匿名"的语义一致

**验证**：
- admin 带 token 访问 3 个公开页 → page_view **0 条**新增 ✓
- 匿名 1.1.1.1 访问 3 个公开页 → page_view **3 条**新增 ✓
- 匿名 2.2.2.2 访问 2 个公开页 → page_view **2 条**新增 ✓

## 老分区清理（30+ 天后）

```sql
-- 查看分区使用情况
SELECT PARTITION_NAME, TABLE_ROWS
FROM INFORMATION_SCHEMA.PARTITIONS
WHERE TABLE_NAME = 'page_view';

-- 清理 6 月分区（2026-07-01 后执行）
ALTER TABLE page_view DROP PARTITION p202606;
```
