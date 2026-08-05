# 2026-06-16 v2.5.0-fix: page_view UK 失效导致"刷新一次被记 7~37 次"

## TL;DR

v2.5.0 首发上线后，**用户实测"刷新一次博客首页 → page_view 写了 7 行"**。原以为是巧合，深入排查发现是**复合 BUG**：SQL 层的 UK + Java 层的 visitor 兜底 + 前端 useVisitor 时序**三层共同失效**。本次一次性修齐。

---

## 现象

刷新一次首页：
- `/public/settings/blog` 路径 → **37 行**
- `/articles` 路径 → **23 行**
- `/articles/categories` → 23 行
- `/public/device/check` → 21 行

仪表盘 `todayPV` 显示几百（用户报告 7 次），与"今天只有我一个人访问"的直觉严重不符。

---

## 根因（三层 BUG 叠加）

### Bug 1：SQL 层的 UK 根本没"按天去重" ❌ 致命

**原设计**（`docs/sql/migrations/20260616_page_view_stats.sql`）：

```sql
UNIQUE KEY uk_visitor_path_day (visitor, path, created_at)
```

注释自我陶醉：
> *"用 INSERT IGNORE 即可天然去重（同 visitor 当天再访问同 path 不会插第二行）"*

**这是错的**。

- `created_at` 是 `DATETIME` 精度 1 秒
- 刷新一次首页，浏览器在 50ms~几秒内连发 7 个请求
- 每次 `INSERT IGNORE ... NOW()` → `created_at` 是不同秒值（如 13:33:52 / 13:33:58）
- **UK `(visitor, path, created_at)` 每次都不同 → 不冲突 → 全部成功**
- "按天去重"在精确到秒的 DATETIME 上根本不成立

### Bug 2：visitor 缺失时用 IP 兜底 ❌ 严重

**原实现**（`PageViewService.recordVisit`）：

```java
if (visitor == null || visitor.isEmpty()) {
    visitor = "ip:" + TrustedProxyUtil.resolveClientIp(request);
}
```

- 本机 dev 环境 IP 是 `0:0:0:0:0:0:0:1`，所有本机请求共享一个 visitor
- 配上 Bug 1 → 本机 dev 用户刷一次首页就被记 7 行 + 全是 `ip:0:0:0:0:0:0:0:1`
- 实测数据铁证：

```
+-----------+-----------------------------+
| path      | visitor                     |
+-----------+-----------------------------+
| /articles | ip:0:0:0:0:0:0:0:1          |  ← 23 行
| /articles | ip:0:0:0:0:0:0:0:1          |
| /articles | ip:0:0:0:0:0:0:0:1          |
| /articles | ba25321e-...（真 UUID 仅 1 行） |
+-----------+-----------------------------+
```

**副作用**：UV 统计被严重污染——同一台机的多个浏览器（或同一 IP 的不同用户）被算成 1 个 UV。

### Bug 3：前端 `useVisitor` ref 时序竞态 ⚠️ 触发 Bug 2

**原实现**（`composables/useVisitor.ts`）：

```ts
const visitorId = ref<string>('')
if (import.meta.client) {
  let id = localStorage.getItem(VISITOR_ID_KEY)
  if (!id) { id = crypto.randomUUID(); localStorage.setItem(...) }
  visitorId.value = id
}
```

+ `usePublicApi.ts`：

```ts
if (import.meta.client) {
  const { visitorId } = useVisitor()
  if (visitorId.value) headers['X-Visitor-Id'] = visitorId.value
}
```

**问题**：
- `usePublicApi` 在 `useVisitor` 还没完全初始化好时（如 SSR 渲染后第一次 client 端发请求）就调
- `visitorId.value` 仍为 `''` → `X-Visitor-Id` 漏发
- 后端走 `ip:` 兜底 → Bug 2 触发
- 等到 `useVisitor` 写完 localStorage，**已经晚了一个请求周期**——而这一个请求就是脏数据来源

---

## 修复

### 修 1：SQL 层加 `visit_date DATE` 列 + 真按天 UK

新文件 `docs/sql/migrations/20260616_fix_page_view_uk.sql`：

```sql
-- 加 visit_date DATE（按天，UK 去重维度）
ALTER TABLE page_view
  ADD COLUMN visit_date DATE NOT NULL DEFAULT (CURDATE()) ...;

-- 删旧 UK
ALTER TABLE page_view DROP INDEX uk_visitor_path_day;

-- 新 UK：MySQL 分区表强制要求 UK 含分区列 created_at
-- 配合 Java 把 created_at 截断到 visit_date 00:00:00，UK 真正按天去重
ALTER TABLE page_view
  ADD UNIQUE KEY uk_visitor_path_date (visitor, path, visit_date, created_at);

ALTER TABLE page_view ADD INDEX idx_visit_date (visit_date);
```

**为什么 UK 含 `created_at` 还能"按天去重"**：
- MySQL 8.0 分区表硬性规定：UK 必须包含分区函数的所有列
- 分区键是 `created_at` (RANGE(TO_DAYS(created_at)))
- 妥协：UK 列里**带** `created_at`，但 Java 端把 `created_at` 截断到 `visit_date 00:00:00`
- 同一天内多次插入：`visit_date` 同 + `created_at` 都是 `今天 00:00:00` → UK 冲突 → IGNORE
- 不同天：`visit_date` 不同 → UK 不冲突 → 多行（每天 1 行）

**TRUNCATE 表**：清掉 v2.5.0 首发产出的全部脏数据（一了百了，避免污染新数据；表无外键可安全 TRUNCATE）。

### 修 2：Java 端 `recordVisit` 三处改动

**`PageView` entity** 加 `visitDate: LocalDate` 字段。

**`PageViewService.recordVisit`**：

```java
// ① visitor 缺失时直接跳过，不再用 IP 兜底
if (visitor == null || visitor.isEmpty()) {
    log.debug("[PageView] visitor 缺失，跳过记录: path={}", path);
    return -1;
}

// ② createdAt 截到 visit_date 当天 00:00:00，配合 UK 命中
LocalDate today = LocalDate.now();
pv.setCreatedAt(today.atStartOfDay());
pv.setVisitDate(today);
```

**`PageViewMapper.insertIgnore`**：SQL 显式写 `#{pv.createdAt}` 和 `#{pv.visitDate}`，不再依赖 `NOW()`。

### 修 3：前端 `usePublicApi` 同步读 localStorage

不再依赖 `useVisitor().visitorId.value` ref 时序。每次 request 同步读 localStorage + 懒初始化：

```ts
if (import.meta.client) {
  const VISITOR_KEY = 'blog_visitor_id'
  let vid = localStorage.getItem(VISITOR_KEY)
  if (!vid) {
    vid = crypto.randomUUID?.() ?? 'v-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
    localStorage.setItem(VISITOR_KEY, vid)
  }
  headers['X-Visitor-Id'] = vid
}
```

**为什么不去 useVisitor.ts 改**：ref 初始化时机（composable 顶层 vs `onMounted`）在 Nuxt 3 里仍有 SSR 边界 case；用 localStorage 直接同步读写**最稳**。

---

## 验证

### SQL 层测（4 场景，全绿）

| 场景 | 期望 | 实际 |
|---|---|---|
| 同 visitor + 同 path + 同秒 3 次插入 | 1 行 | ✅ 1 |
| 同 visitor + 同 path + 同天不同秒 | 1 行 | ✅ 1 |
| 同 visitor + 同 path + 不同天 | 2 行 | ✅ 2 |
| 不同 visitor + 同 path + 同天 | N 行 | ✅ 3 |

### 端到端测（curl 模拟刷首页）

```
测试 visitorId = test-uuid-7times-1781617639
  刷第 1 次 ... 刷第 7 次
  → page_view 实际行数: 1 ✅
  → created_at: 2026-06-16 00:00:00（被截断）✅
  → 后端日志：第 2~7 次 <== Updates: 0（UK 命中未插入）✅

不带 X-Visitor-Id 测试：
  → HTTP 200 业务正常
  → 数据库: 0 行写入（visitor 缺失跳过）✅
```

### 真实环境验证

```
+-----------+-----------------------------+------------+---------------------+
| path      | visitor                     | visit_date | created_at          |
+-----------+-----------------------------+------------+---------------------+
| /articles | test-uuid-7times-1781617639 | 2026-06-16 | 2026-06-16 00:00:00 |
| /articles | another-uuid-1781617656     | 2026-06-16 | 2026-06-16 00:00:00 |
+-----------+-----------------------------+------------+---------------------+
```

---

## 影响范围

- **数据**：page_view 表 TRUNCATE 后重灌——v2.5.0 上线后到修复前的所有 PV/UV 数据丢失
  - 这些都是 BUG 产物，统计意义为零
  - 用户实测到的"7 次"是仪表盘呈现，已经恢复正确
- **代码**：4 个文件改动（entity / service / mapper / usePublicApi）+ 1 个新 SQL 迁移
- **后续**：考虑在 PageViewFilter 强制下发 `Set-Cookie: blog_visitor_id=xxx; Path=/; Max-Age=...` 给未带 X-Visitor-Id 的访客——彻底解决"老浏览器 / 反爬 / curl 漏 visitorId 永远不写入"的问题（本次未做，列入 backlog）

---

## 教训

1. **DDL 注释里写的"天然去重"必须用最严格的可重复单元测试验证**——UK 写了不代表去重生效
2. **兜底策略（IP fallback）会引入隐性 BUG**——`ip:` 兜底让所有同 IP 共享 visitor，污染 UV 统计还不留日志
3. **composable ref 初始化时序在 SSR 框架里不可靠**——需要写入操作直接走同步 API（localStorage），不绕 ref
4. **分区表 UK 的"必须含分区列"是 MySQL 8 硬规定**——妥协方案要把"日期"概念从 `created_at` 显式拆到 `visit_date` 列
