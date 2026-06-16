package com.blog.controller;

import com.blog.article.service.PageViewService;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合（admin）
 *
 * 2026-06-16 v2.5.0：原 100% mock 的趋势图换真实数据
 * - 今日 PV/UV 走 page_view
 * - 近 30 天趋势走 page_view.dailyStats
 * - 热门文章 TOP 10 走 page_view.topArticles
 *
 * 旧逻辑（article.view_count SUM、mock 趋势）保留作为兜底 + 兼容
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final PageViewService pageViewService;

    @GetMapping
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> data = new HashMap<>();

        // === KPI ===
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("totalArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE deleted = 0", Long.class));
        kpi.put("publishedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 1 AND deleted = 0", Long.class));
        kpi.put("draftArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 0 AND deleted = 0", Long.class));
        kpi.put("archivedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 2 AND deleted = 0", Long.class));
        kpi.put("totalComments", jdbc.queryForObject("SELECT COUNT(*) FROM comment", Long.class));
        kpi.put("pendingComments", jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE status = 0", Long.class));
        kpi.put("totalCategories", jdbc.queryForObject("SELECT COUNT(*) FROM category", Long.class));
        kpi.put("totalTags", jdbc.queryForObject("SELECT COUNT(*) FROM tag", Long.class));

        // v2.5.0 新增：今日 PV/UV 走 page_view
        kpi.put("todayPV", pageViewService.countTodayPv());
        kpi.put("todayUV", pageViewService.countTodayUv());

        // 兼容旧字段：累计阅读 = article.view_count SUM
        // 2026-06-16 v2.5.0 注：此字段含 deleted 文章（不改，避免 KPI 大波动）
        kpi.put("totalViewCount", jdbc.queryForObject(
                "SELECT COALESCE(SUM(view_count), 0) FROM article WHERE deleted = 0", Long.class));

        data.put("kpi", kpi);

        // === 分类分布（v2.1.0 已有） ===
        List<Map<String, Object>> categoryDist = jdbc.queryForList(
                "SELECT c.id, c.name, COUNT(a.id) AS cnt FROM category c " +
                        "LEFT JOIN article a ON a.category_id = c.id AND a.deleted = 0 " +
                        "WHERE c.visible = 1 " +
                        "GROUP BY c.id, c.name ORDER BY cnt DESC");
        data.put("categoryDistribution", categoryDist);

        // === 标签分布 ===
        List<Map<String, Object>> tagDist = jdbc.queryForList(
                "SELECT t.id, t.name, COUNT(at.article_id) AS cnt FROM tag t " +
                        "LEFT JOIN article_tag at ON at.tag_id = t.id " +
                        "GROUP BY t.id, t.name ORDER BY cnt DESC LIMIT 20");
        data.put("tagDistribution", tagDist);

        // === 近 30 天文章发布趋势（真实数据，v2.1.0 已有） ===
        List<Map<String, Object>> trend = new ArrayList<>();
        try {
            trend = jdbc.queryForList(
                    "SELECT DATE(created_at) AS date, COUNT(*) AS cnt FROM article " +
                            "WHERE deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY) " +
                            "GROUP BY DATE(created_at) ORDER BY date ASC");
        } catch (Exception ignored) {}
        data.put("publishTrend", trend);

        // === v2.5.0 新增：近 30 天访问趋势（真实 PV + UV） ===
        // 真实数据：每日 PV（行数）+ UV（去重 visitor 数）
        // 缺失的日期由前端补 0
        data.put("visitTrend", pageViewService.dailyStats(30));
        // 近 7 天（用于 sparkline）
        data.put("visitTrend7", pageViewService.dailyStats(7));

        // === v2.5.0 新增：热门文章 TOP 10 ===
        data.put("topArticles", pageViewService.topArticles(10));

        // === 待办 ===
        Map<String, Object> todos = new HashMap<>();
        todos.put("pendingComments", kpi.get("pendingComments"));
        todos.put("draftArticles", kpi.get("draftArticles"));
        data.put("todos", todos);

        return Result.success(data);
    }
}
