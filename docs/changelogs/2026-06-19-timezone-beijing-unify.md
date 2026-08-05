# 2026-06-19 时间统一为北京时间（Asia/Shanghai）

## TL;DR

生产 ECS 在洛杉矶，JVM 默认时区跟随宿主机 → 所有 Java 端 `LocalDateTime.now()` / `LocalDate.now()` / `new Date()` 取到洛杉矶时间，比北京偏 15-16h；SQLite 的 `DEFAULT CURRENT_TIMESTAMP` 又永远写 UTC，比北京偏 8h。**同一系统里出现两种错误偏移**。

本次统一为：**所有入库时间一律由 Java 计算（北京时间），不再依赖 SQLite 的 `CURRENT_TIMESTAMP`。**

## 改动

### 1. 固定 JVM 默认时区（根本修复）

- `BlogApplication.main`：`SpringApplication.run` 之前 `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))`，覆盖 dev/IDE/任意启动方式。
- `deploy-server.sh` systemd ExecStart 加 `-Duser.timezone=Asia/Shanghai`（生产双保险）。

效果：全部 `LocalDateTime.now()` / `LocalDate.now()` / `new Date()` 自动产出北京时间，无需逐处改。
`Instant.now()` / `System.currentTimeMillis()`（JWT 过期、限流计数）是 UTC 绝对时间戳，**有意保持不变**。

### 2. 时间字段统一由 MyBatis-Plus 自动填充

- `MybatisPlusConfig` 新增 `MetaObjectHandler`：insert 填 `createdAt`+`updatedAt`，update 刷新 `updatedAt`，取值 `LocalDateTime.now()`（即北京时间）。
- 8 个实体加 `@TableField(fill=...)`：Article、Category、Tag(仅 createdAt)、AdminDevice、ApiWhitelist、IpBan、User、SiteSettings。
- `SettingsController` profile 更新走 `update(null, uw)`（实体为 null，不触发自动填充）→ 手动 `set("updated_at", now)`。

### 3. schema-sqlite.sql 移除 `DEFAULT CURRENT_TIMESTAMP`

去掉以下表 time 列的 DEFAULT（保留 `NOT NULL`，由 Java 填）：
user、category、tag、article、comment、admin_device、api_whitelist、site_settings、ip_ban、article_view_log。

**有意保留的例外：**
- **page_view.created_at** —— **保留 DEFAULT，请勿清理**。`PageViewService` 始终显式传 `dayStart`（北京当天 00:00），DEFAULT 永不触发；UK `(visitor, path, visit_date, created_at)` 的按天去重依赖该列，已在 schema 加 `⚠️` 注释标注。

## 范围与未覆盖项（明确记录）

- **仅覆盖 SQLite profile（dev/prod 默认）。** `schema-mysql.sql` 仍保留 19 处 `DEFAULT / ON UPDATE CURRENT_TIMESTAMP`，**本次未动**。MySQL profile 默认不启用；若将来切回 MySQL，需另行统一（去 DEFAULT + 加 fill + 移除 ON UPDATE，并确认连接串 `serverTimezone=Asia/Shanghai`）。当前 MySQL 路径靠 `serverTimezone` + Java fill 双写北京时间，无冲突但未做收敛。
- **存量数据不修正。** schema 改默认只影响新建库（`CREATE TABLE IF NOT EXISTS`）；已部署 `blog.db` 历史行时间不变，本次只保证"今后写入正确"。

## 已知设计决策（非 bug）

- **page_view `visit_date` / "今日 PV/UV" 随时区变化。** `visit_date` 是按"自然日"统计的维度，取 `LocalDate.now()`。单一生产环境（固定北京）前后一致；但 dev(北京) 与 prod(若时区不同) 之间，"今日 PV"的自然日边界会差 8h。可接受，记录在此。
- **upload 按月归档目录** `yyyy/MM` 由 `LocalDate.now()` 决定，固定北京后按北京自然月，更确定。

## 验证

- `mvn compile` 全量通过。
- 行为验证（实际插入记录确认北京时间）需重启后端。
