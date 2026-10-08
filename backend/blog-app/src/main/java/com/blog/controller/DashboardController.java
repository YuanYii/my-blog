package com.blog.controller;

import com.blog.article.service.PageViewService;
import com.blog.common.Result;
import com.blog.common.util.MarkdownWordCountUtil;
import com.blog.settings.entity.BackupRecord;
import com.blog.settings.mapper.BackupRecordMapper;
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
    // 2026-06-21：dashboard 待办"数据备份"项需要显示距上次成功备份的天数
    private final BackupRecordMapper backupRecordMapper;

    @GetMapping
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> data = new HashMap<>();

        // === KPI ===
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("totalArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE deleted = 0", Long.class));
        kpi.put("publishedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 1 AND deleted = 0", Long.class));
        kpi.put("draftArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 0 AND deleted = 0", Long.class));
        kpi.put("archivedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE status = 2 AND deleted = 0", Long.class));
        kpi.put("pinnedArticles", jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE is_pinned = 1 AND deleted = 0", Long.class));
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

        // 2026-06-16 v2.5.0 OPT：仪表盘"总字数"按已发布文章的实际字数（strip markdown）累加
        // - 仅已发布（status=1）符合"读者可见内容"语义
        // - 一次性 query 取所有 content_md，到 Java 端 strip markdown 后累加
        // - mediumtext 不全量 SELECT * 没问题：只取 content_md 字段，传输可控
        kpi.put("totalWordCount", computeTotalWordCount());

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
        // 2026-06-17 v2.6.0：DATE_SUB(NOW(), INTERVAL 30 DAY) → Java 端传 LocalDateTime
        // - SQLite / MySQL 都支持参数化绑定
        List<Map<String, Object>> trend = new ArrayList<>();
        try {
            java.time.LocalDateTime fromTime = java.time.LocalDateTime.now().minusDays(30);
            trend = jdbc.queryForList(
                    "SELECT DATE(created_at) AS date, COUNT(*) AS cnt FROM article " +
                            "WHERE deleted = 0 AND created_at >= ? " +
                            "GROUP BY DATE(created_at) ORDER BY date ASC",
                    fromTime);
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

        // === 2026-07-15 DEV-001：访客 IP 来源 Top 5（30 天）替换原 referer 维度 ===
        // 按 page_view.ip（真实公网 IP）分组；原 trafficSources(referer) 维度移除
        // 返回格式 [{ip, count, percentage}]，前端 visitorIpSources 渲染
        try {
            data.put("visitorIpSources", pageViewService.topVisitorIpsToday(10));
            data.put("visitorIpSourcesHistory", pageViewService.topVisitorIps(30, 10));
        } catch (Exception e) {
            // 兜底：page_view 表查询异常不阻断 dashboard 其他字段
            data.put("visitorIpSources", new ArrayList<Map<String, Object>>());
            data.put("visitorIpSourcesHistory", new ArrayList<Map<String, Object>>());
        }

        // === 待办 ===
        Map<String, Object> todos = new HashMap<>();
        todos.put("pendingComments", kpi.get("pendingComments"));
        todos.put("draftArticles", kpi.get("draftArticles"));
        data.put("todos", todos);

        // === 2026-06-21：最近一次 SUCCESS 备份（前端 dashboard 待办用） ===
        // 没备份过则 lastBackupAt = null，前端展示"未备份过"
        try {
            BackupRecord last = backupRecordMapper.selectLatestSuccess();
            Map<String, Object> lastBackup = new HashMap<>();
            lastBackup.put("lastBackupAt", last == null ? null : last.getStartedAt());
            lastBackup.put("lastBackupTag", last == null ? null : last.getTag());
            data.put("lastBackup", lastBackup);
        } catch (Exception e) {
            // 兜底：万一 backup_record 表还没建好,不影响 dashboard 其他字段
            Map<String, Object> lastBackup = new HashMap<>();
            lastBackup.put("lastBackupAt", null);
            lastBackup.put("lastBackupTag", null);
            data.put("lastBackup", lastBackup);
        }

        return Result.success(data);
    }

    /**
     * 2026-06-16 v2.5.0 OPT：已发布文章总字数（strip markdown 后按字符数累加）
     *
     * 策略：
     * 1) 一次性 SELECT content_md 取所有已发布文章原文
     * 2) 逐篇调用 stripMarkdown 去掉标记（# ** * ` ``` > - * + [text](url) ![alt](url) 等）
     * 3) strip 后用 Java String length 累加（CJK 字符每个 1 字，ASCII 每个 1 字符——MVP 阶段不做词分词）
     *
     * 为什么不存 article.word_count 字段：
     * - schema 改动 = 改表 + 改 entity + 改 mapper + 改所有写路径
     * - 当前文章量（个位数～几十）量级下，每秒 dashboard 调用成本可控
     * - 后续如文章量 >1000，再加 word_count 字段（写时计算 + 增量更新）即可
     *
     * 2026-06-22 v4.x polish：明确"全量加载 content_md"的量级边界——
     *   假设单文章平均 10KB markdown、1000 篇 = 10MB 一次性进 JVM 堆。SQLite 单连接串行读
     *   也至少 1-2 秒。当前个人博客量级（个位数～几十）远低于此，但**禁止无脑迁移量级**。
     *   触发迁移条件（任一）：文章数 >200 / 平均 content_md >50KB / dashboard P99 >3s。
     *   迁移路径：article 表加 word_count 列 + 文章 insert/update 钩子里算并写库，
     *            dashboard 改 SELECT SUM(word_count) FROM article WHERE status=1。
     */
    private long computeTotalWordCount() {
        List<String> mds = jdbc.queryForList(
                "SELECT content_md FROM article WHERE status = 1 AND deleted = 0",
                String.class);
        long total = 0;
        for (String md : mds) {
            if (md == null || md.isEmpty()) continue;
            total += countChars(stripMarkdown(md));
        }
        return total;
    }

    /**
     * Markdown 标记去除（轻量版——按"渲染后读者看到的"字符为准，不做完美 HTML 还原）
     * 处理顺序：
     * 1) 代码块 ```...``` → 内部内容保留（代码字符也算"字数"）
     * 2) 行内代码 `...` → 内部内容保留
     * 3) 图片 ![alt](url) → 替换成 alt
     * 4) 链接 [text](url) → 替换成 text
     * 5) 标题前缀 # / ## / ### 等 → 去掉
     * 6) 引用前缀 > → 去掉
     * 7) 列表标记 - / * / + / 1. → 去掉
     * 8) 粗体 **xxx** / 斜体 *xxx* / 删除线 ~~xxx~~ → 去掉标记符
     */
    static String stripMarkdown(String md) {
        return MarkdownWordCountUtil.stripMarkdown(md);
    }

    static int countChars(String s) {
        return MarkdownWordCountUtil.countChars(s);
    }
}
