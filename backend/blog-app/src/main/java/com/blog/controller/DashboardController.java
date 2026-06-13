package com.blog.controller;

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
 * 简版：直接查 DB，不做定时任务
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> data = new HashMap<>();

        // KPI
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("totalArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE deleted = 0", Long.class));
        kpi.put("publishedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 1 AND deleted = 0", Long.class));
        kpi.put("draftArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 0 AND deleted = 0", Long.class));
        // 2026-06-12 新增：前端 admin/posts.vue 的"已归档" tab 一直 hard-code 0，因为 KPI 里没归档计数。
        kpi.put("archivedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 2 AND deleted = 0", Long.class));
        kpi.put("totalComments", jdbc.queryForObject("SELECT COUNT(*) FROM comment", Long.class));
        kpi.put("pendingComments", jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE status = 0", Long.class));
        kpi.put("totalCategories", jdbc.queryForObject("SELECT COUNT(*) FROM category", Long.class));
        kpi.put("totalTags", jdbc.queryForObject("SELECT COUNT(*) FROM tag", Long.class));
        kpi.put("totalViewCount", jdbc.queryForObject("SELECT COALESCE(SUM(view_count), 0) FROM article", Long.class));
        data.put("kpi", kpi);

        // 分类分布
        // 2026-06-12 修复：原 SQL 不带 c.visible = 1 过滤，已隐藏分类仍出现在 dashboard
        List<Map<String, Object>> categoryDist = jdbc.queryForList(
                "SELECT c.id, c.name, COUNT(a.id) AS cnt FROM category c " +
                        "LEFT JOIN article a ON a.category_id = c.id AND a.deleted = 0 " +
                        "WHERE c.visible = 1 " +
                        "GROUP BY c.id, c.name ORDER BY cnt DESC");
        data.put("categoryDistribution", categoryDist);

        // 标签分布（tag 表没 visible 字段，不需过滤）
        List<Map<String, Object>> tagDist = jdbc.queryForList(
                "SELECT t.id, t.name, COUNT(at.article_id) AS cnt FROM tag t " +
                        "LEFT JOIN article_tag at ON at.tag_id = t.id " +
                        "GROUP BY t.id, t.name ORDER BY cnt DESC LIMIT 20");
        data.put("tagDistribution", tagDist);

        // 近 30 天文章发布趋势
        List<Map<String, Object>> trend = new ArrayList<>();
        try {
            trend = jdbc.queryForList(
                    "SELECT DATE(created_at) AS date, COUNT(*) AS cnt FROM article " +
                            "WHERE deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY) " +
                            "GROUP BY DATE(created_at) ORDER BY date ASC");
        } catch (Exception ignored) {}
        data.put("publishTrend", trend);

        // 待办
        Map<String, Object> todos = new HashMap<>();
        todos.put("pendingComments", kpi.get("pendingComments"));
        todos.put("draftArticles", kpi.get("draftArticles"));
        data.put("todos", todos);

        return Result.success(data);
    }
}
