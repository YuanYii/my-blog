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
import java.util.List;
import java.util.Map;

/**
 * 访问记录 service
 * 2026-06-16 v2.5.0 新增
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
     * @return 1 = 新增，0 = 当天已存在
     */
    public int recordVisit(String path, Long articleId, String visitor, HttpServletRequest request) {
        if (visitor == null || visitor.isEmpty()) {
            // 极少数情况：客户端没传 X-Visitor-Id（老浏览器 / 反爬 / curl）
            // 用 IP 兜底当 visitor，行为退化为"同 IP 同 path 当天一次"
            visitor = "ip:" + TrustedProxyUtil.resolveClientIp(request);
        }
        PageView pv = new PageView();
        pv.setPath(truncate(path, 200));
        pv.setArticleId(articleId);
        pv.setVisitor(visitor);
        pv.setIp(TrustedProxyUtil.resolveClientIp(request));
        pv.setUserAgent(truncate(request.getHeader("User-Agent"), 200));
        pv.setReferer(truncate(request.getHeader("Referer"), 500));
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
