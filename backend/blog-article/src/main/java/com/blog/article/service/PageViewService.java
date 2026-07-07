package com.blog.article.service;

import com.blog.article.entity.PageView;
import com.blog.article.mapper.PageViewMapper;
// PageView 写库改用 JdbcTemplate.update（见 recordVisitAsync），PageViewMapper/PageView 仍保留给 BaseMapper 兼容用

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 访问记录 service
 * 2026-06-16 v2.5.0 新增
 * 2026-06-17 v2.6.0：跨方言改造
 *  - 4 处 MySQL 特有函数（DATE_SUB / CURDATE / NOW）→ Java 端传 LocalDate/LocalDateTime
 *  - INSERT IGNORE 业务层 selectCount 判断（@Insert 注解不再写方言特定 SQL）
 *  - 聚合查询走 JdbcTemplate，参数化绑定（兼容 SQLite/MySQL）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageViewService {

    private final PageViewMapper pageViewMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 异步记录一次访问（2026-06-18 性能：从请求线程同步写库改为线程池异步写）
     *
     * 为什么异步：原实现在 PageViewFilter 里同步 SELECT 去重 + INSERT，每个公开 GET
     * （/articles、/articles/categories、/articles/tags、/public/settings/*）都要等写库完成才返回，
     * 在 SQLite + 单连接池下会层层串行，拖慢首页。改异步后业务响应不再等统计写库。
     *
     * 注意：ip/userAgent/referer 必须由调用方（filter）在请求线程内同步取出后传入——
     * 异步执行时 HttpServletRequest 可能已被容器回收，不能再持有 request 引用。
     *
     * 去重策略（跨方言）：先 SELECT COUNT(*) 判断当天同 visitor+path 是否已存在，不存在才 INSERT。
     */
    @org.springframework.scheduling.annotation.Async("pageViewExecutor")
    public void recordVisitAsync(String path, Long articleId, String visitor,
                                 String ip, String userAgent, String referer) {
        try {
            if (visitor == null || visitor.isEmpty()) {
                log.debug("[PageView] visitor 缺失，跳过记录: path={}", path);
                return;
            }
            LocalDate today = LocalDate.now();
            LocalDateTime dayStart = today.atStartOfDay();

            // 2026-06-22 v4.x polish：去掉"先 SELECT COUNT 再 INSERT"的 TOCTOU 模式——
            // 两请求并发都过 COUNT=0，第二个 INSERT 撞 UK 抛异常未 catch 会冒到日志当 ERROR。
            // 直接 INSERT + catch DataIntegrityViolationException：少一次 round-trip、并发幂等。
            // 同 path+visitor+visit_date 已在 uk_page_view_dedup 唯一索引上,DIV 异常即"重复",吞掉即可。
            // 2026-06-22 v4.x BUG 修复:SQLite JDBC driver 抛原生 SQLiteException(SQL state null,
            //   error code 19),Spring SQLExceptionTranslator 因 SQLite 错误码不在内置 mapping 里,
            //   **不会**翻译成 DataIntegrityViolationException,导致 catch 漏接、被最外层 catch 当 ERROR 兜底,
            //   日志刷屏 ([PageView] 异步记录失败)。同时 catch SQLiteException + DIV 才能彻底覆盖。
            try {
                jdbc.update(
                        "INSERT INTO page_view (path, article_id, visitor, ip, user_agent, referer, created_at, visit_date) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        truncate(path, 200),
                        articleId,
                        visitor,
                        ip,
                        truncate(userAgent, 200),
                        truncate(referer, 500),
                        dayStart,
                        today);
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // 同 visitor+path+day 当天已记录，幂等忽略（不记日志——这是高频路径，避免日志爆炸）
                // catch DIV(SQLite/MySQL 翻译后)
            } catch (RuntimeException dup) {
                // 2026-06-22 v4.x BUG 修复:SQLite JDBC driver 抛原生 SQLiteException
                //   (SQL state null, error code 19),Spring SQLExceptionTranslator 因 SQLite 错误码
                //   不在内置 mapping 里,不会翻译成 DataIntegrityViolationException,
                //   上面 catch 接不到 → 被最外层 catch 当 ERROR 兜底,日志刷屏。
                //   Spring 把 SQLiteException 包成 UncategorizedSQLException 等 unchecked RuntimeException,
                //   message 里直接带 "SQLITE_CONSTRAINT_UNIQUE" 字符串(SQLite driver 抛出的原文),
                //   用这个关键字兜底判断 + 递归查 cause 链,SQLite/MySQL 都覆盖。
                //   真异常靠外层 catch 看堆栈识别(必然伴随非 UNIQUE 的错误码或完全不同 message)。
                if (isUniqueConstraintDup(dup)) {
                    return;  // 幂等忽略
                }
                throw dup;  // 其他 RuntimeException 继续抛,让最外层 catch 记录
            }
        } catch (Exception e) {
            // 统计写库失败绝不能影响业务（已在 filter 之外的异步线程，这里兜底）
            log.warn("[PageView] 异步记录失败: path={}, err={}", path, e.getMessage());
        }
    }

    /**
     * 判断异常是否是"唯一约束冲突"（同 visitor+path+day 已记录）
     *
     * 2026-06-22 v4.x 新增：catch (RuntimeException) 后兜底判断。
     * 三种"重复"来源都覆盖：
     *   1. Spring 翻译好的 DataIntegrityViolationException（上面已经 catch 过一次,这里再防漏）
     *   2. SQLite driver 原生 SQLiteException,被 Spring 包成 UncategorizedSQLException,
     *      message 含 "SQLITE_CONSTRAINT_UNIQUE"（SQLite 错误码 19 = SQLITE_CONSTRAINT）
     *   3. MySQL DuplicateKeyException 等也会被翻译成 DIV,理论上 catch 1 已覆盖,
     *      这里 message 含 "Duplicate entry" 兜底（保守判断,不区分真重复和真异常,统一吞）
     *
     * 注：UK 索引 uk_page_view_visitor_path_date 覆盖 (visitor, path, visit_date, created_at),
     * 同 visitor+path+visit_date+created_at 重复 INSERT 是已记录过的访问,幂等吞掉正确;
     * 如果是别的 UK 冲突（理论上不可能,代码 INSERT 就这一个表）,也吞掉,统计可丢失但业务不受影响。
     */
    private boolean isUniqueConstraintDup(Throwable t) {
        if (t == null) return false;
        // 自身 message 检查
        String msg = t.getMessage();
        if (msg != null) {
            // SQLite driver 原生异常 message（错误码 19 + SQLITE_CONSTRAINT_UNIQUE 关键字）
            // Spring 包装的 UncategorizedSQLException message 里嵌套这段原文
            if (msg.contains("SQLITE_CONSTRAINT") || msg.contains("SQLITE_CONSTRAINT_UNIQUE")) {
                return true;
            }
            // MySQL driver 关键字
            if (msg.contains("Duplicate entry")) {
                return true;
            }
            // SQL state 23000 / 23505 (SQL standard integrity constraint violation)
            if (msg.contains("SQLSTATE=23000") || msg.contains("SQLSTATE[23000]")
                    || msg.contains("SQLSTATE=23505") || msg.contains("SQLSTATE[23505]")) {
                return true;
            }
        }
        // 递归查 cause 链
        return isUniqueConstraintDup(t.getCause());
    }

    /**
     * 用 slug 查 articleId（PageViewFilter 缓存 miss 时调用）
     */
    public Long findArticleIdBySlug(String slug) {
        try {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM article WHERE slug = ? AND status = 1 AND deleted = 0 LIMIT 1",
                    Long.class, slug);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 今日 PV（v2.6.0：传 today 进去，跨方言）
     */
    public long countTodayPv() {
        LocalDate today = LocalDate.now();
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_view WHERE visit_date = ?",
                Long.class, today);
        return n == null ? 0L : n;
    }

    /**
     * 今日 UV（v2.6.0：传 today 进去，跨方言）
     */
    public long countTodayUv() {
        LocalDate today = LocalDate.now();
        Long n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT visitor) FROM page_view WHERE visit_date = ?",
                Long.class, today);
        return n == null ? 0L : n;
    }

    /**
     * 近 N 天每日 PV + UV（v2.6.0：传 fromDate 进去）
     */
    public List<Map<String, Object>> dailyStats(int days) {
        LocalDate fromDate = LocalDate.now().minusDays(days - 1);
        return jdbc.queryForList(
                "SELECT visit_date AS date, " +
                        "       COUNT(*) AS pv, " +
                        "       COUNT(DISTINCT visitor) AS uv " +
                        "FROM page_view " +
                        "WHERE visit_date >= ? " +
                        "GROUP BY visit_date " +
                        "ORDER BY date ASC",
                fromDate);
    }

    /**
     * 热门文章 TOP N（v2.6.0：传 fromDate 进去）
     */
    public List<Map<String, Object>> topArticles(int limit) {
        LocalDate fromDate = LocalDate.now().minusDays(30);
        return jdbc.queryForList(
                "SELECT pv.article_id AS articleId, a.title, a.slug, COUNT(*) AS pv " +
                        "FROM page_view pv " +
                        "LEFT JOIN article a ON a.id = pv.article_id AND a.deleted = 0 " +
                        "WHERE pv.article_id IS NOT NULL " +
                        "  AND pv.visit_date >= ? " +
                        "GROUP BY pv.article_id, a.title, a.slug " +
                        "ORDER BY pv DESC " +
                        "LIMIT ?",
                fromDate, limit);
    }

    /**
     * 流量来源 Top N（2026-06-24 DEV-001 新增）
     *
     * 设计：
     *  - 从 page_view.referer 列提取域名（Java 端处理, 跨方言不写 SQL substr 函数）
     *  - referer 为 NULL/空/`-`/相对路径/不含 `://` → 归类「直接访问」
     *  - 域名映射: google.com→Google / baidu.com→百度 / bing.com→Bing / twitter.com|x.com→Twitter / github.com→GitHub
     *    去掉 www. 前缀再查表, 未知域名按域名本身显示
     *  - 返回 [{domain, label, count, percentage}], percentage 用浮点除法 (避免 Java int 截断)
     *  - 跨方言：拉所有 referer 到 Java 聚合, 避免 SQL 方言差异（page_view 表当前最多几千行级别可控）
     */
    public List<Map<String, Object>> topReferrers(int days, int limit) {
        if (days <= 0) days = 30;
        if (limit <= 0) limit = 5;

        LocalDate fromDate = LocalDate.now().minusDays(days - 1);

        // 跨方言：拉 referer 列表到 Java 端聚合 (避免 SQLite vs MySQL 字符串函数差异)
        // page_view 表数量级 (个人博客几千行) 可控,Java 内存聚合 OK
        List<String> referers = jdbc.queryForList(
                "SELECT referer FROM page_view WHERE visit_date >= ?",
                String.class, fromDate);

        // 域名 → 计数
        Map<String, Long> domainCount = new java.util.LinkedHashMap<>();
        long total = 0L;
        for (String r : referers) {
            String domain = extractDomain(r);
            domainCount.merge(domain, 1L, Long::sum);
            total++;
        }
        if (total == 0L) return new java.util.ArrayList<>();

        // 排序取 Top N
        List<Map.Entry<String, Long>> sorted = new java.util.ArrayList<>(domainCount.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        int n = Math.min(limit, sorted.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Long> e = sorted.get(i);
            String domain = e.getKey();
            long count = e.getValue();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("domain", domain);
            row.put("label", domainLabel(domain));
            row.put("count", count);
            row.put("percentage", Math.round((double) count / (double) total * 10000.0) / 100.0);
            result.add(row);
        }
        return result;
    }

    /**
     * 从 referer URL 提取域名（去掉 www. 前缀）
     * referer 为 NULL/空/`-`/相对路径/不含 `://` → "direct"（直接访问）
     * referer 为 IP 地址 → "direct"（通过 IP 直接访问，非正常来源）
     * referer 为同域名 → "direct"（SPA 内部跳转，无法追踪原始来源）
     */
    static String extractDomain(String referer) {
        if (referer == null) return "direct";
        String s = referer.trim();
        if (s.isEmpty() || "-".equals(s)) return "direct";
        int idx = s.indexOf("://");
        if (idx < 0) return "direct";
        String rest = s.substring(idx + 3);
        // 截到第一个 / 或 ? 或 # 或字符串末尾
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { cut = i; break; }
        }
        String host = rest.substring(0, cut).toLowerCase();
        // 去端口
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        // 去 www.
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.isEmpty()) return "direct";
        // IP 地址归类为直接访问（通过 IP 访问非正常来源）
        if (isIpAddress(host)) return "direct";
        // 同域名跳转归类为直接访问（SPA 内部跳转，无法追踪原始来源）
        if (isInternalReferrer(host)) return "direct";
        return host;
    }

    /**
     * 判断字符串是否为 IP 地址（IPv4 或 IPv6）
     */
    static boolean isIpAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        // IPv4: x.x.x.x（每段 0-255）
        if (host.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            return true;
        }
        // IPv6: 包含冒号的十六进制地址
        if (host.contains(":") && host.matches("^[0-9a-f:]+$")) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为内部来源（同域名跳转）
     * SPA 内部跳转无法追踪原始来源，归类为直接访问
     */
    static boolean isInternalReferrer(String host) {
        if (host == null) return false;
        // 博客域名（去掉 www.）
        String blogDomain = "blog.coreyai.cn";
        return host.equals(blogDomain) || host.equals("localhost") || host.equals("127.0.0.1");
    }

    /**
     * 域名 → 友好显示名映射
     * 未知域名兜底显示域名本身（如 example.com）
     */
    static String domainLabel(String domain) {
        if (domain == null || domain.isEmpty() || "direct".equals(domain)) return "直接访问";
        switch (domain) {
            case "google.com":
            case "google.com.hk":
                return "Google";
            case "baidu.com":
                return "百度";
            case "bing.com":
                return "Bing";
            case "duckduckgo.com":
                return "DuckDuckGo";
            case "sogou.com":
                return "搜狗";
            case "so.com":
            case "360.cn":
                return "360 搜索";
            case "twitter.com":
            case "x.com":
                return "Twitter";
            case "github.com":
                return "GitHub";
            case "facebook.com":
                return "Facebook";
            case "reddit.com":
                return "Reddit";
            case "weibo.com":
                return "微博";
            case "zhihu.com":
                return "知乎";
            case "v2ex.com":
                return "V2EX";
            case "blog.coreyai.cn":
            case "localhost":
            case "127.0.0.1":
                return "直接访问";
            default:
                return domain;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
