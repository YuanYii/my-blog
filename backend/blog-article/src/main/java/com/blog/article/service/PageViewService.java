package com.blog.article.service;

import com.blog.article.entity.PageView;
import com.blog.article.mapper.PageViewMapper;
import com.blog.common.TrustedProxyUtil;
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
 * 2026-06-16 v2.5.0-fix：修 page_view UK "按天去重"失效问题
 *
 * - recordVisit：被 PageViewFilter 同步调用（filter 链不能用 @Async，DB 写是必须的）
 * - 聚合查询（今日 PV/UV/趋势/热门）走 JdbcTemplate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageViewService {

    private final PageViewMapper pageViewMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 记录一次访问
     * @param path        URL 路径（已去掉 context-path）
     * @param articleId   文章详情 ID（其它页 NULL）
     * @param visitor     访客 UUID（前端 header X-Visitor-Id 透传，可能为 NULL）
     * @param request     原始 request（取 IP / UA / Referer）
     * @return 1 = 新增，0 = 当天已存在，-1 = visitor 缺失跳过
     *
     * 2026-06-16 v2.5.0-fix：
     * 1) visitor 缺失时**直接跳过不记录**（不再用 IP 兜底——IP 兜底会让所有同一 IP 用户
     *    共享 visitor，污染 UV 统计；同时让"无效请求"也能写入 DB 浪费存储）
     * 2) createdAt 截断到 visitDate 当天 00:00:00，配合 UK (visitor, path, visit_date, created_at)
     *    实现真正的按天去重（DATETIME 精度 1 秒，原 NOW() 在跨秒插入时 created_at 不同 → UK 不命中）
     */
    public int recordVisit(String path, Long articleId, String visitor, HttpServletRequest request) {
        if (visitor == null || visitor.isEmpty()) {
            // 2026-06-16 fix：visitor 缺失（X-Visitor-Id 没传）直接跳过，不写脏数据
            // - 老浏览器 / 反爬 / curl 等场景不进入统计——保护数据质量
            // - 期望后续考虑在 PageViewFilter 强制下发 Set-Cookie 标识访客（无需登录态）
            log.debug("[PageView] visitor 缺失，跳过记录: path={}, ip={}",
                    path, TrustedProxyUtil.resolveClientIp(request));
            return -1;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();  // visit_date 00:00:00

        PageView pv = new PageView();
        pv.setPath(truncate(path, 200));
        pv.setArticleId(articleId);
        pv.setVisitor(visitor);
        pv.setIp(TrustedProxyUtil.resolveClientIp(request));
        pv.setUserAgent(truncate(request.getHeader("User-Agent"), 200));
        pv.setReferer(truncate(request.getHeader("Referer"), 500));
        // 关键：把 createdAt 设为 visit_date 当天 00:00:00，UK 真正按天去重
        pv.setCreatedAt(dayStart);
        pv.setVisitDate(today);
        return pageViewMapper.insertIgnore(pv);
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

    /** 今日 PV（行数） */
    public long countTodayPv() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_view WHERE created_at >= CURDATE()",
                Long.class);
        return n == null ? 0L : n;
    }

    /** 今日 UV（按 visitor 去重） */
    public long countTodayUv() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT visitor) FROM page_view WHERE created_at >= CURDATE()",
                Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 近 N 天每日 PV + UV
     * 返回 [{date: '2026-06-16', pv: 100, uv: 80}, ...] 按日期升序
     * 用 LEFT JOIN + 日期序列表，保证没访问的日期也有 0
     */
    public List<Map<String, Object>> dailyStats(int days) {
        // MySQL 序列生成：使用递归 CTE 或 numbers 表
        // 简化方案：直接查实际有数据的日期，缺失的日期前端自己补 0
        return jdbc.queryForList(
                "SELECT DATE(created_at) AS date, " +
                        "       COUNT(*) AS pv, " +
                        "       COUNT(DISTINCT visitor) AS uv " +
                        "FROM page_view " +
                        "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                        "GROUP BY DATE(created_at) " +
                        "ORDER BY date ASC",
                days);
    }

    /**
     * 热门文章 TOP N（按 page_view 行数）
     * 返回 [{articleId, title, slug, pv}, ...]
     */
    public List<Map<String, Object>> topArticles(int limit) {
        return jdbc.queryForList(
                "SELECT pv.article_id AS articleId, a.title, a.slug, COUNT(*) AS pv " +
                        "FROM page_view pv " +
                        "LEFT JOIN article a ON a.id = pv.article_id AND a.deleted = 0 " +
                        "WHERE pv.article_id IS NOT NULL " +
                        "  AND pv.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY) " +
                        "GROUP BY pv.article_id, a.title, a.slug " +
                        "ORDER BY pv DESC " +
                        "LIMIT ?",
                limit);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
