package com.blog.article.service;

import com.blog.article.entity.PageView;
import com.blog.article.mapper.PageViewMapper;
import com.blog.common.TrustedProxyUtil;
// PageView 写库改用 JdbcTemplate.update（见 recordVisit），PageViewMapper/PageView 仍保留给 BaseMapper 兼容用

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
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
     * 记录一次访问（v2.6.0：业务层去重，跨方言兼容）
     *
     * 去重策略（替代原来的 SQL `INSERT IGNORE`）：
     * 1) 先 SELECT COUNT(*) 看当天同 visitor+path 是否已存在
     * 2) 存在 → 跳过
     * 3) 不存在 → INSERT
     *
     * 性能：
     * - 每访问多一次 SELECT（毫秒级，SQLite/MySQL 都快）
     * - 替代方案：分两步写 SQL `INSERT OR IGNORE`（SQLite）/ `INSERT IGNORE`（MySQL）
     *   —— 但 MyBatis @Insert 注解是字符串，跨方言要写两份 mapper（太丑）
     * - 业务层判断 = 单 SQL 路径，跨方言无歧义
     */
    public int recordVisit(String path, Long articleId, String visitor, HttpServletRequest request) {
        if (visitor == null || visitor.isEmpty()) {
            log.debug("[PageView] visitor 缺失，跳过记录: path={}", path);
            return -1;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();

        // 业务层去重（替代 SQL IGNORE）
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_view WHERE visitor = ? AND path = ? AND visit_date = ?",
                Integer.class, visitor, path, today);
        if (exists != null && exists > 0) {
            return 0;  // 当天已记录
        }

        // 2026-06-17 v2.6.0-fix：直接用 JdbcTemplate 写（不经过 MyBatis-Plus BaseMapper.insert）
        // 原因：MyBatis-Plus 在 SQLite 下用 IdType.AUTO 调 getGeneratedKeys()，
        //       SQLite 默认不返回 generated keys，导致异常被吞、SQL 不真执行
        // 性能：与 BaseMapper.insert 相同（Hikari 池 + 参数化绑定）
        int rows = jdbc.update(
                "INSERT INTO page_view (path, article_id, visitor, ip, user_agent, referer, created_at, visit_date) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                truncate(path, 200),
                articleId,
                visitor,
                TrustedProxyUtil.resolveClientIp(request),
                truncate(request.getHeader("User-Agent"), 200),
                truncate(request.getHeader("Referer"), 500),
                dayStart,
                today);
        if (rows == 0) {
            log.warn("[PageView] INSERT 影响 0 行（可能 UK 冲突）: visitor={}, path={}", visitor, path);
        }
        return rows;
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
