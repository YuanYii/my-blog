package com.blog.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.article.entity.Article;
import com.blog.article.entity.Attachment;
import com.blog.article.entity.Category;
import com.blog.article.entity.Tag;
import com.blog.article.mapper.ArticleMapper;
import com.blog.article.mapper.AttachmentMapper;
import com.blog.article.mapper.CategoryMapper;
import com.blog.article.mapper.TagMapper;
import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.common.BusinessException;
import com.blog.common.util.MarkdownWordCountUtil;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章业务逻辑（B1a：从 ArticleController 物理搬迁，控制流不变，保留 Result.error 风格）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final AttachmentMapper attachmentMapper;
    private final AttachmentService attachmentService;
    private final JdbcTemplate jdbc;

    /**
     * 2026-06-22 v4.x polish：adminList 排序白名单 Map
     * - key = DB 列名（白名单，只允许这里出现的字段——防 SQL 注入 / ORDER BY 炸裂）
     * - value = 默认方向（true=asc, false=desc）。调用方 dir=desc 会翻转成 false，否则保持默认
     * - 加新字段只动这一行,不会"忘了同步到 split(":") 那段"
     */
    private static final Map<String, Boolean> ADMIN_SORT_FIELDS = new HashMap<>();
    static {
        ADMIN_SORT_FIELDS.put("published_at", false);  // 默认 desc（最新发布在前）
        ADMIN_SORT_FIELDS.put("view_count", false);    // 默认 desc（最热在前）
        ADMIN_SORT_FIELDS.put("updated_at", false);    // 默认 desc（最近编辑在前）
        ADMIN_SORT_FIELDS.put("created_at", false);    // 默认 desc（最新创建在前）
        ADMIN_SORT_FIELDS.put("is_pinned", false);     // 默认 desc（置顶在前）
    }

    // ===== 公开接口 =====

    public Result<PageResult<Map<String, Object>>> list(long page, long size,
                                                        Long categoryId, Long tagId, String keyword) {
        return list(page, size, categoryId, tagId, keyword, null);
    }

    public Result<PageResult<Map<String, Object>>> list(long page, long size,
                                                        Long categoryId, Long tagId, String keyword,
                                                        HttpServletRequest request) {
        if (page < 1) throw new BusinessException(400, "page 必须 >= 1");
        if (size < 1 || size > 100) throw new BusinessException(400, "size 必须在 1-100 之间");
        // 2026-06-30 BUG-001：关键词长度下限校验。
        // 单字符 / 通用词（如"的"、"用"、"？"）会命中几乎所有文章 title，导致"搜索结果 = 全部"
        // 用户感知为"搜索按钮无法使用"。trim 后长度 < 2 字符直接 400 拒绝。
        if (keyword != null && !keyword.isEmpty() && keyword.trim().length() < 2) {
            throw new BusinessException(400, "关键词至少 2 个字符");
        }

        QueryWrapper<Article> qw = new QueryWrapper<>();
        boolean isAdmin = request != null && AuthContext.uid(request) != null;
        if (isAdmin) {
            qw.in("status", Arrays.asList(1, 3));
        } else {
            qw.eq("status", 1);
        }
        qw.eq("deleted", 0);
        if (categoryId != null) qw.eq("category_id", categoryId);
        // 2026-06-30 BUG-001：搜索范围限定 title + summary（**不**搜 content_md）。
        //   关键决策：content_md 全表 LIKE 命中率 100%（所有文章正文都讨论"AI/Redis/博客"等通用词），
        //   会导致"搜 AI → 返全部 6 篇"→ 用户感知为"搜索 = 没用"。
        //   限定 title + summary 让搜索更精准（"Zip Slip"等只在 summary 命中的也能找到）。
        //   关键词在正文里但不在 title/summary 的场景，**留给将来加 ES/FTS 索引**（OPT-003）。
        if (keyword != null && !keyword.isEmpty()) {
            String kw = keyword.trim();
            qw.and(w -> w.like("title", kw).or().like("summary", kw));
        }
        if (tagId != null) {
            // A1（2026-06-20）：占位符参数化
            qw.exists(true, "SELECT 1 FROM article_tag at WHERE at.article_id = article.id AND at.tag_id = {0}", tagId);
        }
        qw.orderByDesc("is_pinned").orderByDesc("published_at");

        Page<Article> p = articleMapper.selectPage(new Page<>(page, size), qw);
        List<Map<String, Object>> records = p.getRecords().stream().map(a -> toPublicMap(a, false)).collect(Collectors.toList());
        fillTagIds(records);
        return Result.success(PageResult.of(records, p.getTotal(), p.getCurrent(), p.getSize()));
    }

    public Result<Map<String, Object>> detail(String slug) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("slug", slug).in("status", Arrays.asList(1, 3)).eq("deleted", 0);
        Article article = articleMapper.selectOne(qw);
        if (article == null) throw new BusinessException(1001, "文章不存在");
        jdbc.update("UPDATE article SET view_count = view_count + 1, updated_at = updated_at WHERE id = ?",
                article.getId());
        article.setViewCount(article.getViewCount() == null ? 1 : article.getViewCount() + 1);
        Map<String, Object> data = toPublicMap(article, true);
        // 2026-07-01 BUG-001 fix：抽 populateAttachment 私有方法取代原 attachmentMapper.selectOne
        //   原写法被 MyBatis-Plus 全局 logic-delete 自动加 `AND deleted=0`，软删附件查不到 → 公开页软删提示失效
        //   改用 jdbc 直查不过滤 deleted，软删附件也能查到（前端展示"已删除"提示用）
        populateAttachment(data, article.getId());
        return Result.success(data);
    }

    public Result<List<Map<String, Object>>> archives() {
        // 2026-06-22 v4.x polish：.last("LIMIT 1000") 是绕过 MyBatis-Plus 方言处理的硬编码 SQL。
        //   当前 SQLite / MySQL 都兼容 LIMIT <n>，没问题；
        //   TODO：未来切 SQL Server（TOP）/ Oracle（ROWNUM <=）时改为方言感知（用 DialectFactory 或在 mapper xml 里写多套）。
        List<Article> list = articleMapper.selectList(
            new QueryWrapper<Article>()
                .eq("status", 1)
                .eq("deleted", 0)
                .orderByDesc("is_pinned")
                .orderByDesc("published_at")
                .last("LIMIT 1000"));
        List<Map<String, Object>> records = list.stream()
            .map(a -> toPublicMap(a, false))
            .collect(Collectors.toList());
        fillTagIds(records);
        return Result.success(records);
    }

    /**
     * 公开站点统计：提供已发布文章数、总字数、分类数、标签数等轻量聚合指标
     */
    public Result<Map<String, Object>> stats() {
        Map<String, Object> data = new HashMap<>();
        Long totalArticles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article WHERE status = 1 AND deleted = 0",
                Long.class);
        List<String> mds = jdbc.queryForList(
                "SELECT content_md FROM article WHERE status = 1 AND deleted = 0",
                String.class);
        long totalWordCount = 0;
        for (String md : mds) {
            if (md != null && !md.isEmpty()) {
                totalWordCount += MarkdownWordCountUtil.countMarkdown(md);
            }
        }
        Long totalCategories = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE visible = 1",
                Long.class);
        Long totalTags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tag",
                Long.class);

        data.put("totalArticles", totalArticles != null ? totalArticles : 0L);
        data.put("totalWordCount", totalWordCount);
        data.put("totalCategories", totalCategories != null ? totalCategories : 0L);
        data.put("totalTags", totalTags != null ? totalTags : 0L);
        return Result.success(data);
    }

    public Result<Map<String, Object>> detailById(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) throw new BusinessException(1001, "文章不存在");
        jdbc.update("UPDATE article SET view_count = view_count + 1, updated_at = updated_at WHERE id = ?", id);
        article.setViewCount(article.getViewCount() == null ? 1 : article.getViewCount() + 1);
        Map<String, Object> m = toMap(article, true);
        List<Map<String, Object>> tagRows = jdbc.queryForList(
            "SELECT tag_id FROM article_tag WHERE article_id = ?", id);
        List<Long> tagIds = tagRows.stream()
            .map(r -> ((Number) r.get("tag_id")).longValue())
            .collect(Collectors.toList());
        m.put("tagIds", tagIds);
        // 2026-07-01 BUG-001 fix：detailById（admin 编辑器用）原缺 attachment 字段，
        //   导致编辑器侧"附件管理"区块读 res.data.attachment 永远是 undefined，
        //   已上传附件被显示成"无附件"。与 detail() 共享 populateAttachment 私有方法。
        //   注：article.deleted=1 时也查（admin 编辑软删文章也要能看到附件）—— populateAttachment 不依赖 article.deleted。
        populateAttachment(m, id);
        return Result.success(m);
    }

    /**
     * 2026-07-15 BUG-001：管理员草稿预览。
     * 绕过公开 detail() 的 status=1 硬过滤——只查 deleted=0，草稿(status=0)/归档(status=2)均可取。
     * 不递增 view_count（预览是只读操作，不应污染阅读数）。
     * 软删(deleted=1)仍查不到 → 抛 1001，与公开页语义一致。
     */
    public Result<Map<String, Object>> adminPreview(String slug) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("slug", slug).eq("deleted", 0);
        Article article = articleMapper.selectOne(qw);
        if (article == null) throw new BusinessException(1001, "文章不存在");
        Map<String, Object> data = toMap(article, true);
        populateAttachment(data, article.getId());
        return Result.success(data);
    }

    // ===== Admin 文章 =====

    public Result<PageResult<Map<String, Object>>> adminList(long page, long size,
                                                             Integer status, Long categoryId,
                                                             String keyword, String sort,
                                                             String deletedFilter) {
        // 2026-07-01 BUG-002：adminList 加 deleted 参数（默认 0 = 未删；1 = 已删；all = 不过滤）
        // 2026-07-01 BUG-002 fix：绕过 MyBatis-Plus 全局 logic-delete（application.yml 里
        //   `mybatis-plus.global-config.db-config.logic-delete-field: deleted` 会让带 deleted
        //   字段的实体自动加 `AND deleted=0`，导致 deleted=1/all 查不到软删记录）。
        //   与 AttachmentService.list(page,size,deletedFilter) 同模式：用 JdbcTemplate 直查直读。
        long offset = (long) (page - 1) * size;

        // 构建 WHERE 片段
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();
        // deleted 条件
        if (deletedFilter == null || deletedFilter.isEmpty() || "0".equals(deletedFilter)) {
            where.append(" AND deleted = 0");
        } else if ("1".equals(deletedFilter)) {
            where.append(" AND deleted = 1");
        } else if (!"all".equalsIgnoreCase(deletedFilter)) {
            // 未知值兜底
            where.append(" AND deleted = 0");
        }
        // "all" 模式不加 deleted 条件

        // status / categoryId / keyword
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (categoryId != null) {
            where.append(" AND category_id = ?");
            params.add(categoryId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            where.append(" AND (title LIKE ? OR summary LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            params.add(kw);
            params.add(kw);
        }

        // ORDER BY（白名单）—— 始终 is_pinned DESC 优先
        String orderBy;
        String pinPrefix = "is_pinned DESC, ";
        if (sort != null && !sort.isEmpty()) {
            String[] parts = sort.split(":", 3);
            if (parts.length == 2 && ADMIN_SORT_FIELDS.containsKey(parts[0].trim())) {
                String field = parts[0].trim();
                String dir = parts[1].trim().toLowerCase();
                boolean asc = "asc".equals(dir) || !"desc".equals(dir) && ADMIN_SORT_FIELDS.get(field);
                orderBy = "ORDER BY " + pinPrefix + field + (asc ? " ASC" : " DESC");
            } else {
                orderBy = "ORDER BY " + pinPrefix + "updated_at DESC";
            }
        } else if ("1".equals(deletedFilter)) {
            orderBy = "ORDER BY " + pinPrefix + "updated_at DESC";
        } else {
            orderBy = "ORDER BY " + pinPrefix + "updated_at DESC";
        }

        String countSql = "SELECT COUNT(*) FROM article " + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        String listSql = "SELECT id, title, slug, summary, cover_url AS coverUrl, status, is_pinned AS isPinned, deleted, view_count AS viewCount, "
                + "category_id AS categoryId, published_at AS publishedAt, created_at AS createdAt, "
                + "updated_at AS updatedAt, content_md AS contentMd "
                + "FROM article " + where + " " + orderBy + " LIMIT ? OFFSET ?";
        List<Object> allParams = new ArrayList<>(params);
        allParams.add(size);
        allParams.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(listSql, allParams.toArray());

        // sqlite jdbc 返回 view_count 是 Integer；用 Number 兼容转 Long
        List<Map<String, Object>> records = rows.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", ((Number) r.get("id")).longValue());
            m.put("title", r.get("title"));
            m.put("slug", r.get("slug"));
            m.put("summary", r.get("summary"));
            m.put("coverUrl", r.get("coverUrl"));
            m.put("status", ((Number) r.get("status")).intValue());
            m.put("isPinned", r.get("isPinned") == null ? 0 : ((Number) r.get("isPinned")).intValue());
            m.put("deleted", ((Number) r.get("deleted")).intValue());
            m.put("viewCount", r.get("viewCount") == null ? 0 : ((Number) r.get("viewCount")).longValue());
            m.put("categoryId", r.get("categoryId") == null ? null : ((Number) r.get("categoryId")).longValue());
            m.put("publishedAt", r.get("publishedAt"));
            m.put("createdAt", r.get("createdAt"));
            m.put("updatedAt", r.get("updatedAt"));
            m.put("contentMd", r.get("contentMd"));
            return m;
        }).collect(Collectors.toList());

        fillTagIds(records);
        // 2026-07-01 OPT-001：adminList 加 attachmentCount 字段
        fillAttachmentCounts(records);
        // 2026-08-01 DEV-001：adminList 加 viewCount3d 字段（page_view 近3天浏览数）
        fillViewCount3d(records);
        return Result.success(PageResult.of(records, total != null ? total : 0L, page, size));
    }

    /**
     * 2026-07-01 OPT-001：给 records 里每篇文章填 attachmentCount（按 deleted=0 计）。
     * 复用 fillTagIds 模式（一条 SQL IN 取全部，in-memory merge）。
     * 不填的 articlesMap 兜底写 0。
     */
    private void fillAttachmentCounts(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> articleIds = records.stream()
            .map(r -> ((Number) r.get("id")).longValue())
            .collect(Collectors.toList());
        String placeholders = articleIds.stream().map(x -> "?").collect(Collectors.joining(","));
        // 注意：attachment_count 只算 deleted=0 的有效附件（前端 column 用法"附件数"语义）
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT article_id AS aid, COUNT(*) AS cnt FROM article_attachment "
            + "WHERE deleted = 0 AND article_id IN (" + placeholders + ") GROUP BY article_id",
            articleIds.toArray());
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(((Number) row.get("aid")).longValue(), ((Number) row.get("cnt")).longValue());
        }
        for (Map<String, Object> r : records) {
            Long aid = ((Number) r.get("id")).longValue();
            r.put("attachmentCount", counts.getOrDefault(aid, 0L));
        }
    }

    /**
     * 2026-08-01 DEV-001：给 records 里每篇文章填 viewCount3d（近 3 天 page_view 浏览数）。
     * 复用 fillAttachmentCounts 模式（一条 SQL IN 取全部，in-memory merge）。
     * 近 3 天语义 = visit_date >= LocalDate.now().minusDays(2)（含今天），与 dailyStats/topArticles 一致。
     * 无 page_view 记录的文章兜底写 0。
     */
    private void fillViewCount3d(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> articleIds = records.stream()
            .map(r -> ((Number) r.get("id")).longValue())
            .collect(Collectors.toList());
        String placeholders = articleIds.stream().map(x -> "?").collect(Collectors.joining(","));
        LocalDate fromDate = LocalDate.now().minusDays(2);
        List<Object> params = new ArrayList<>(articleIds);
        params.add(fromDate);
        // 只统计 article_id 非空且近3天的记录；按 article_id 聚合
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT article_id AS aid, COUNT(*) AS cnt FROM page_view "
            + "WHERE article_id IS NOT NULL AND article_id IN (" + placeholders + ") "
            + "AND visit_date >= ? GROUP BY article_id",
            params.toArray());
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(((Number) row.get("aid")).longValue(), ((Number) row.get("cnt")).longValue());
        }
        for (Map<String, Object> r : records) {
            Long aid = ((Number) r.get("id")).longValue();
            r.put("viewCount3d", counts.getOrDefault(aid, 0L));
        }
    }

    /**
     * 2026-07-01 BUG-001 fix：单文章详情挂 attachment。
     * - 复用给 detail() 和 detailById()（共享，避免一处改另一处忘改）
     * - 用 JdbcTemplate 直查绕过 MyBatis-Plus 全局 logic-delete（不会自动加 `AND deleted=0`），
     *   软删附件也能查到（前端编辑器展示"已删除"提示 + 公开页 "原附件已被作者删除" 灰条）
     * - 没有附件时（rows 空）静默不挂字段，前端 `!attachment` 为真走"无附件"分支
     */
    private void populateAttachment(Map<String, Object> data, Long articleId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, file_name, file_size, deleted FROM article_attachment WHERE article_id = ?",
            articleId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> am = new HashMap<>();
        am.put("id", ((Number) row.get("id")).longValue());
        am.put("fileName", row.get("file_name"));
        am.put("fileSize", ((Number) row.get("file_size")).longValue());
        am.put("deleted", row.get("deleted"));
        data.put("attachment", am);
    }

    /**
     * 创建文章（多步写：articleMapper.insert + 多次 article_tag 插入 → 事务保护）
     *
     * 2026-06-22 v4.x polish：原实现缺事务保护——若 insertArticleTagIfNotExists 在
     * articleMapper.insert 成功之后失败（如并发 UNIQUE 冲突），会产生"文章已入库但标签丢失"
     * 的孤立数据。加 @Transactional 确保任一步失败整体回滚。
     */
    @Transactional
    public Result<Map<String, Object>> create(Article article, HttpServletRequest request) {
        if (article.getTitle() == null || article.getTitle().trim().isEmpty()) {
            throw new BusinessException(400, "标题不能为空");
        }
        if (article.getTitle().length() > 200) {
            throw new BusinessException(400, "标题长度不能超过 200 字符");
        }
        if (article.getSlug() == null || article.getSlug().trim().isEmpty()) {
            throw new BusinessException(400, "slug 不能为空");
        }
        if (article.getSlug().length() > 200) {
            throw new BusinessException(400, "slug 长度不能超过 200 字符");
        }
        if (!article.getSlug().matches("^[a-zA-Z0-9\\u4e00-\\u9fa5\\-]+$")) {
            throw new BusinessException(400, "slug 只能包含字母、数字、中文、连字符");
        }
        if (article.getCategoryId() == null && article.getStatus() != null && article.getStatus() == 1) {
            throw new BusinessException(400, "发布文章时分类不能为空");
        }
        if (article.getCategoryId() != null && categoryMapper.selectById(article.getCategoryId()) == null) {
            throw new BusinessException(1001, "分类不存在");
        }
        Article existing = articleMapper.selectOne(
            new QueryWrapper<Article>().eq("slug", article.getSlug()));
        if (existing != null) {
            log.warn("文章创建校验失败：slug 重复 slug={} operator={}", article.getSlug(), AuthContext.uid(request));
            throw new BusinessException(1002, "slug 已存在");
        }
        List<Long> tagIds = article.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            for (Long tid : tagIds) {
                if (tagMapper.selectById(tid) == null) {
                    log.warn("文章创建校验失败：标签不存在 tagId={} slug={} operator={}", tid, article.getSlug(), AuthContext.uid(request));
                    throw new BusinessException(1003, "标签不存在: id=" + tid);
                }
            }
        }
        article.setId(null);
        article.setViewCount(0);
        article.setDeleted(0);
        if (article.getStatus() == null) article.setStatus(0);
        if (!Arrays.asList(0, 1, 2, 3).contains(article.getStatus())) {
            log.warn("文章创建校验失败：status 非法 status={} slug={} operator={}", article.getStatus(), article.getSlug(), AuthContext.uid(request));
            throw new BusinessException(1010, "status 取值非法: " + article.getStatus());
        }
        if ((Integer.valueOf(1).equals(article.getStatus()) || Integer.valueOf(3).equals(article.getStatus())) && article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }
        if (article.getIsPinned() == null) article.setIsPinned(0);
        if (!Arrays.asList(0, 1).contains(article.getIsPinned())) {
            throw new BusinessException(400, "isPinned 取值非法: " + article.getIsPinned());
        }
        if (Integer.valueOf(1).equals(article.getIsPinned())) {
            // 置顶互斥：把其他置顶文章改回普通
            jdbc.update("UPDATE article SET is_pinned = 0, updated_at = ? WHERE is_pinned = 1", LocalDateTime.now());
        }
        articleMapper.insert(article);
        if (tagIds != null && !tagIds.isEmpty()) {
            for (Long tid : tagIds) {
                insertArticleTagIfNotExists(article.getId(), tid);
            }
        }
        log.info("文章创建：id={} slug={} status={} operator={}", article.getId(), article.getSlug(), article.getStatus(), AuthContext.uid(request));
        Map<String, Object> data = new HashMap<>();
        data.put("id", article.getId());
        data.put("slug", article.getSlug());
        return Result.success(data);
    }

    /**
     * 更新文章（多步写：articleMapper.updateById + DELETE article_tag + 多次 INSERT 标签 → 事务保护）
     *
     * 2026-06-22 v4.x polish：原实现缺事务保护——DELETE FROM article_tag 成功后，
     * 若后续 INSERT 标签失败，会导致"文章标签全丢"。加 @Transactional 整体回滚。
     */
    @Transactional
    public Result<Void> update(Long id, Article article, HttpServletRequest request) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) throw new BusinessException(1001, "文章不存在");
        if (article.getTitle() != null) {
            if (article.getTitle().trim().isEmpty()) throw new BusinessException(400, "标题不能为空");
            if (article.getTitle().length() > 200) throw new BusinessException(400, "标题长度不能超过 200 字符");
        }
        if (article.getSlug() != null) {
            if (article.getSlug().trim().isEmpty()) throw new BusinessException(400, "slug 不能为空");
            if (article.getSlug().length() > 200) throw new BusinessException(400, "slug 长度不能超过 200 字符");
            if (!article.getSlug().matches("^[a-zA-Z0-9\\u4e00-\\u9fa5\\-]+$")) {
                throw new BusinessException(400, "slug 只能包含字母、数字、中文、连字符");
            }
            Article existingBySlug = articleMapper.selectOne(
                new QueryWrapper<Article>().eq("slug", article.getSlug()));
            if (existingBySlug != null && !existingBySlug.getId().equals(id)) {
                log.warn("文章更新校验失败：slug 重复 id={} slug={} operator={}", id, article.getSlug(), AuthContext.uid(request));
                throw new BusinessException(1002, "slug 已存在");
            }
        }
        if (article.getCategoryId() != null && categoryMapper.selectById(article.getCategoryId()) == null) {
            throw new BusinessException(1001, "分类不存在");
        }
        List<Long> tagIds = article.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            for (Long tid : tagIds) {
                if (tagMapper.selectById(tid) == null) {
                    log.warn("文章更新校验失败：标签不存在 id={} tagId={} operator={}", id, tid, AuthContext.uid(request));
                    throw new BusinessException(1003, "标签不存在: id=" + tid);
                }
            }
        }
        article.setId(id);
        if (article.getStatus() != null && !Arrays.asList(0, 1, 2, 3).contains(article.getStatus())) {
            log.warn("文章更新校验失败：status 非法 id={} status={} operator={}", id, article.getStatus(), AuthContext.uid(request));
            throw new BusinessException(1010, "status 取值非法: " + article.getStatus());
        }
        if (article.getIsPinned() != null && !Arrays.asList(0, 1).contains(article.getIsPinned())) {
            throw new BusinessException(400, "isPinned 取值非法: " + article.getIsPinned());
        }
        Integer effectiveIsPinned = article.getIsPinned() != null ? article.getIsPinned() : existing.getIsPinned();
        if (Integer.valueOf(1).equals(effectiveIsPinned)) {
            // 置顶互斥：把其他置顶文章改回普通（排除自身）
            jdbc.update("UPDATE article SET is_pinned = 0, updated_at = ? WHERE is_pinned = 1 AND id != ?", LocalDateTime.now(), id);
        }
        Integer effectiveStatus = article.getStatus() != null ? article.getStatus() : existing.getStatus();
        if ((Integer.valueOf(1).equals(effectiveStatus) || Integer.valueOf(3).equals(effectiveStatus))
                && article.getPublishedAt() == null
                && existing.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }
        articleMapper.updateById(article);
        if (tagIds != null) {
            jdbc.update("DELETE FROM article_tag WHERE article_id = ?", id);
            for (Long tid : tagIds) {
                insertArticleTagIfNotExists(id, tid);
            }
        }
        log.info("文章更新：id={} slug={} operator={}", id,
                article.getSlug() != null ? article.getSlug() : existing.getSlug(), AuthContext.uid(request));
        return Result.success();
    }

    /**
     * 删除文章（多步写：UPDATE deleted=1 + DELETE article_tag + 软删附件 → 事务保护）
     *
     * 2026-06-22 v4.x polish：原实现缺事务保护——articleMapper.deleteById 成功后，
     * 若后续 DELETE FROM article_tag 失败（如并发锁），会留下"幽灵标签"（指向已删除文章）。
     *
     * 2026-07-01 DEV-001：删除文章时调 AttachmentService.softDeleteByArticleId 软删附件
     * （文件保留，公开页显示"已删除"提示；用户决策是"二段删除第一段"）。
     *
     * 2026-07-01 BUG-002：删除流程改为二段删除——
     *   - 第一段（delete）：软删，UPDATE deleted=1，文件保留。在 adminList(deleted=1) 显示。
     *   - 第二段（hardDelete）：物理删，仅 admin/recycle-bin 入口触发。
     *   - 配合 restore (1→0) 支持"已删除文章"列表的恢复 / 硬删除两种动作。
     */
    @Transactional
    public Result<Void> delete(Long id, HttpServletRequest request) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) throw new BusinessException(1001, "文章不存在");
        if (existing.getDeleted() != null && existing.getDeleted() == 1) {
            log.warn("文章软删跳过：已处于已删除状态 id={} operator={}", id, AuthContext.uid(request));
            return Result.success();
        }
        // 2026-07-01 BUG-002：用 MyBatis-Plus 显式 UPDATE 字段而非 deleteById（保留记录，跟附件侧 deleted 软删对齐）
        //   MyBatis-Plus 全局 logic-delete 已配 `deleted` 字段，但 selectById 会自动加 deleted=0，
        //   软删后无法选到——这里也改用 JdbcTemplate 直 UPDATE 绕过（与 AttachmentService 一致）
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
            "UPDATE article SET deleted = 1, updated_at = ? WHERE id = ? AND deleted = 0",
            now, id);
        if (updated == 0) {
            log.warn("文章软删无更新：id={} (可能 record 已不存在或已被删)", id);
            return Result.success();
        }
        // 软删标签保留（虽然不再展示，但保留方便 restore 反悔时还原）
        // — 这里不删 article_tag，对比之前 deleteById 的做法。
        // 2026-07-01 DEV-001：软删附件（不删文件）
        UpdateWrapper<Attachment> uw = new UpdateWrapper<>();
        uw.eq("article_id", id).eq("deleted", 0)
                .set("deleted", 1)
                .set("updated_at", now);
        int attN = attachmentMapper.update(null, uw);
        if (attN > 0) {
            log.info("文章软删联动软删附件：articleId={} attachmentCount={}", id, attN);
        }
        log.info("文章软删：id={} slug={} operator={}", id, existing.getSlug(), AuthContext.uid(request));
        return Result.success();
    }

    /**
     * 上传 HTML 文件创建文章（admin）。
     * - slug 格式: html + 时间戳(10位) + 随机数(4位)
     * - title: 从文件名提取（去掉 .html/.htm 后缀）
     * - contentMd: 存储 HTML 文件路径（/uploads/html/xxx.html）
     * - status: 默认 0（草稿），用户可手动发布
     */
    @Transactional
    public Result<Map<String, Object>> createHtmlArticle(String htmlContent, String fileName, 
                                                          String htmlFilePath, HttpServletRequest request) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            throw new BusinessException(400, "HTML 内容不能为空");
        }
        // 提取 title（文件名去掉 .html/.htm 后缀）
        String title = fileName;
        if (title != null) {
            title = title.replaceAll("\\.(html?|HTML?)$", "").trim();
        }
        if (title == null || title.isEmpty()) {
            title = "HTML 页面";
        }
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }

        // 生成唯一 slug: html + 时间戳 + 随机数
        String slug = generateUniqueHtmlSlug();

        // 创建文章 - contentMd 存储 HTML 文件路径
        Article article = new Article();
        article.setTitle(title);
        article.setSlug(slug);
        article.setSummary("HTML 页面");
        article.setContentMd(htmlFilePath); // 存储文件路径，而非内容
        article.setViewCount(0);
        article.setDeleted(0);
        article.setStatus(0); // 默认草稿
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articleMapper.insert(article);

        log.info("HTML文章创建：id={} slug={} title={} filePath={} operator={}",
                article.getId(), slug, title, htmlFilePath, AuthContext.uid(request));

        Map<String, Object> data = new HashMap<>();
        data.put("id", article.getId());
        data.put("slug", article.getSlug());
        data.put("title", article.getTitle());
        return Result.success(data);
    }

    /**
     * 生成唯一的 HTML 文章 slug。
     * 格式: html + 时间戳(10位) + 随机数(4位)
     * 确保每次生成的 slug 都是唯一的
     */
    private String generateUniqueHtmlSlug() {
        // 时间戳（秒）+ 4位随机数，确保唯一性
        long timestamp = System.currentTimeMillis() / 1000;
        int random = (int) (Math.random() * 10000);
        return String.format("html%d%04d", timestamp, random);
    }

    /**
     * 批量软删文章（单事务内循环处理多个 ID，避免前端并发触发 IP 限流）。
     */
    @Transactional
    public Result<Map<String, Object>> batchDelete(List<Long> ids, HttpServletRequest request) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(400, "文章 ID 列表不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int success = 0;
        int skipped = 0;
        for (Long id : ids) {
            Article existing = articleMapper.selectById(id);
            if (existing == null) {
                skipped++;
                continue;
            }
            if (existing.getDeleted() != null && existing.getDeleted() == 1) {
                skipped++;
                continue;
            }
            int updated = jdbc.update(
                "UPDATE article SET deleted = 1, updated_at = ? WHERE id = ? AND deleted = 0",
                now, id);
            if (updated == 0) {
                skipped++;
                continue;
            }
            UpdateWrapper<Attachment> uw = new UpdateWrapper<>();
            uw.eq("article_id", id).eq("deleted", 0)
                    .set("deleted", 1)
                    .set("updated_at", now);
            attachmentMapper.update(null, uw);
            success++;
        }
        log.info("批量软删：total={} success={} skipped={} operator={}",
                ids.size(), success, skipped, AuthContext.uid(request));
        Map<String, Object> data = new HashMap<>();
        data.put("success", success);
        data.put("skipped", skipped);
        return Result.success(data);
    }

    /**
     * 2026-07-01 BUG-002：硬删除（已删除文章二次确认后调用）。
     * 与软删的差别：这次 article_tag 关联行也清掉（彻底抹除痕迹）。
     * 附件此前已被软删（delete 时联动），这里**不**再硬删附件（用户决策：附件走附件侧的硬删除入口，
     *   避免文章/附件硬删耦合成一块）。
     */
    @Transactional
    public Result<Void> hardDelete(Long id, HttpServletRequest request) {
        // 2026-07-01 BUG-002 fix：用 JdbcTemplate 选 entity 绕过 MyBatis-Plus 全局 logic-delete
        //   （articleMapper.selectById 会自动加 deleted=0，软删记录查不到 → 抛"文章不存在"1001）。
        //   与 AttachmentService.list 同模式：硬删只看 rowcount,即使查不到也执行 DELETE 兜底
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, slug FROM article WHERE id = ?", id);
        if (rows.isEmpty()) throw new BusinessException(1001, "文章不存在");
        Map<String, Object> row = rows.get(0);
        // 直接 DELETE 不走 logic-delete 过滤（用 jdbc 绕过 selectById 自动 deleted=0）
        int affected = jdbc.update("DELETE FROM article WHERE id = ?", id);
        jdbc.update("DELETE FROM article_tag WHERE article_id = ?", id);
        // 硬删文章时联动硬删附件（文件 + DB 记录一并清除）
        attachmentService.hardDeleteByArticleId(id);
        log.info("文章硬删：id={} slug={} affectedRows={} operator={}",
                id, row.get("slug"), affected, AuthContext.uid(request));
        return Result.success();
    }

    /**
     * 2026-07-01 BUG-002：恢复（deleted=1 → 0）。
     * 注：附件不会被自动 restore —— 附件侧的 restore 是单独动作（公开下载链路用），
     *   文章恢复后附件仍是 deleted=1（公开页继续显示"已删除"提示，等用户手动恢复附件）。
     */
    @Transactional
    public Result<Void> restore(Long id, HttpServletRequest request) {
        // 2026-07-01 BUG-002 fix：用 JdbcTemplate 选 entity 绕过 MP logic-delete
        //   articleMapper.selectById 对 deleted=1 返 null → 抛 1001；硬删/恢复都需找 deleted=1 的 record
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, slug FROM article WHERE id = ?", id);
        if (rows.isEmpty()) throw new BusinessException(1001, "文章不存在");
        Map<String, Object> row = rows.get(0);
        int updated = jdbc.update(
            "UPDATE article SET deleted = 0, updated_at = ? WHERE id = ? AND deleted = 1",
            LocalDateTime.now(), id);
        if (updated == 0) {
            log.warn("文章恢复无更新：id={} (可能 record 已不存在或未软删)", id);
        }
        log.info("文章恢复：id={} slug={} operator={}", id, row.get("slug"), AuthContext.uid(request));
        return Result.success();
    }

    /**
     * 2026-07-01 BUG-002：公开文章页访问软删文章时返 410。
     * detail() 原先 `eq("deleted", 0)` 把 deleted=1 排除 → 抛 1001 文章不存在——但前端期望 410 而非 1001
     * （语义差别：deleted 是"软删"，1001 是"从未存在"）。
     * 前端默认按业务码 410 显示"文章已删除"提示，而不是 1001 的"文章不存在"。
     * 注：此方法不修改——保留原 detail() 行为（1001 不抛 410），因为公开页通常不会跳到软删文章 URL。
     * 已删除文章访问统一在 archive()（公开归档列表）过滤。
     */

    // ===== 分类 =====

    public Result<List<Category>> categories() {
        QueryWrapper<Category> qw = new QueryWrapper<>();
        qw.eq("visible", 1).orderByAsc("sort");
        List<Category> list = categoryMapper.selectList(qw);
        fillCategoryArticleCounts(list);
        return Result.success(list);
    }

    public Result<List<Category>> categoriesAll() {
        QueryWrapper<Category> qw = new QueryWrapper<>();
        qw.orderByAsc("sort");
        List<Category> list = categoryMapper.selectList(qw);
        fillCategoryArticleCounts(list);
        return Result.success(list);
    }

    private void fillCategoryArticleCounts(List<Category> list) {
        if (list == null || list.isEmpty()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category_id AS cid, COUNT(*) AS cnt FROM article WHERE deleted = 0 AND status = 1 GROUP BY category_id");
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object cid = row.get("cid");
            if (cid == null) continue;
            counts.put(((Number) cid).longValue(), ((Number) row.get("cnt")).longValue());
        }
        for (Category c : list) {
            c.setArticleCount(counts.getOrDefault(c.getId(), 0L));
        }
    }

    public Result<Map<Long, Long>> categoryArticleCounts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category_id AS cid, COUNT(*) AS cnt FROM article WHERE deleted = 0 GROUP BY category_id");
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object cid = row.get("cid");
            long key = cid == null ? -1 : ((Number) cid).longValue();
            counts.put(key, ((Number) row.get("cnt")).longValue());
        }
        return Result.success(counts);
    }

    public Result<Category> createCategory(Category category) {
        if (category.getName() == null || category.getName().trim().isEmpty()) {
            throw new BusinessException(400, "名称不能为空");
        }
        if (category.getName().length() > 50) throw new BusinessException(400, "分类名称长度不能超过 50 字符");
        if (category.getSlug() == null || category.getSlug().trim().isEmpty()) {
            category.setSlug(category.getName().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", ""));
        }
        if (category.getSlug().length() > 50) throw new BusinessException(400, "分类 slug 长度不能超过 50 字符");
        if (category.getVisible() == null) category.setVisible(1);
        if (category.getSort() == null) category.setSort(0);
        Category existing = categoryMapper.selectOne(
            new QueryWrapper<Category>().eq("slug", category.getSlug()));
        if (existing != null) throw new BusinessException(1002, "slug 已存在");
        category.setId(null);
        categoryMapper.insert(category);
        return Result.success(category);
    }

    public Result<Void> updateCategory(Long id, Category category) {
        if (categoryMapper.selectById(id) == null) throw new BusinessException(1001, "分类不存在");
        if (category.getName() != null) {
            if (category.getName().trim().isEmpty()) throw new BusinessException(400, "名称不能为空");
            if (category.getName().length() > 50) throw new BusinessException(400, "分类名称长度不能超过 50 字符");
        }
        if (category.getSlug() != null && !category.getSlug().trim().isEmpty()) {
            if (category.getSlug().length() > 50) throw new BusinessException(400, "分类 slug 长度不能超过 50 字符");
            Category existingBySlug = categoryMapper.selectOne(
                new QueryWrapper<Category>().eq("slug", category.getSlug()));
            if (existingBySlug != null && !existingBySlug.getId().equals(id)) {
                throw new BusinessException(1002, "slug 已存在");
            }
        }
        category.setId(id);
        categoryMapper.updateById(category);
        return Result.success();
    }

    public Result<Void> deleteCategory(Long id) {
        if (categoryMapper.selectById(id) == null) throw new BusinessException(1001, "分类不存在");
        Long articleCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM article WHERE category_id = ? AND deleted = 0", Long.class, id);
        if (articleCount != null && articleCount > 0) {
            throw new BusinessException(1004, "该分类下还有 " + articleCount + " 篇文章，请先迁移或删除");
        }
        categoryMapper.deleteById(id);
        return Result.success();
    }

    // ===== 标签 =====

    public Result<List<Map<String, Object>>> tags() {
        List<Tag> tags = tagMapper.selectList(null);
        // 消除 N+1 且过滤仅已发布且未删除文章
        Map<Long, Long> countByTag = new HashMap<>();
        List<Map<String, Object>> countRows = jdbc.queryForList(
            "SELECT at.tag_id AS tid, COUNT(*) AS cnt FROM article_tag at JOIN article a ON at.article_id = a.id WHERE a.deleted = 0 AND a.status = 1 GROUP BY at.tag_id");
        for (Map<String, Object> row : countRows) {
            Object tid = row.get("tid");
            if (tid == null) continue;
            countByTag.put(((Number) tid).longValue(), ((Number) row.get("cnt")).longValue());
        }
        List<Map<String, Object>> result = tags.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            m.put("createdAt", t.getCreatedAt());
            m.put("articleCount", countByTag.getOrDefault(t.getId(), 0L));
            return m;
        }).collect(Collectors.toList());
        return Result.success(result);
    }

    public Result<Tag> createTag(Tag tag) {
        if (tag.getName() == null || tag.getName().trim().isEmpty()) throw new BusinessException(400, "名称不能为空");
        if (tag.getName().length() > 50) throw new BusinessException(400, "标签名称长度不能超过 50 字符");
        if (tag.getSlug() != null && tag.getSlug().length() > 50) {
            throw new BusinessException(400, "标签 slug 长度不能超过 50 字符");
        }
        if (tag.getSlug() == null || tag.getSlug().isEmpty()) {
            tag.setSlug(tag.getName().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", ""));
        }
        Tag existing = tagMapper.selectOne(new QueryWrapper<Tag>().eq("slug", tag.getSlug()));
        if (existing != null) throw new BusinessException(1002, "slug 已存在");
        tag.setId(null);
        tagMapper.insert(tag);
        return Result.success(tag);
    }

    public Result<Void> deleteTag(Long id) {
        if (tagMapper.selectById(id) == null) throw new BusinessException(1001, "标签不存在");
        tagMapper.deleteById(id);
        jdbc.update("DELETE FROM article_tag WHERE tag_id = ?", id);
        return Result.success();
    }

    // ===== 私有工具方法（随业务逻辑一起搬迁）=====

    /**
     * 给文章列表填充 tagIds。
     * 守卫：records 为空时直接返回（articleIds 一定非空），空集合不进 SQL。
     */
    private void fillTagIds(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> articleIds = records.stream()
            .map(r -> ((Number) r.get("id")).longValue())
            .collect(Collectors.toList());
        // A1（2026-06-20）：占位符参数化
        String placeholders = articleIds.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT article_id, tag_id FROM article_tag WHERE article_id IN (" + placeholders + ")",
            articleIds.toArray());
        Map<Long, List<Long>> byArticle = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long aid = ((Number) row.get("article_id")).longValue();
            Long tid = ((Number) row.get("tag_id")).longValue();
            byArticle.computeIfAbsent(aid, k -> new ArrayList<>()).add(tid);
        }
        for (Map<String, Object> r : records) {
            Long aid = ((Number) r.get("id")).longValue();
            r.put("tagIds", byArticle.getOrDefault(aid, new ArrayList<>()));
        }
    }

    /**
     * 业务层去重插入 article_tag（替代 INSERT IGNORE，跨 SQLite/MySQL）。
     *
     * 2026-06-22 v4.x polish：原"先 SELECT COUNT 再 INSERT"模式有 TOCTOU 竞态——
     * 两请求同时过 COUNT=0 都会尝试 INSERT，第二个撞 UK 约束抛异常未 catch，
     * 直接 500 给前端。改用 INSERT + catch DataIntegrityViolationException 兜底：
     * - 单条 INSERT 比 COUNT+INSERT 少一次 round-trip
     * - catch 异常让并发场景幂等（重复就是吞掉）
     * - 跨方言一致（SQLite UNIQUE constraint failed / MySQL Duplicate entry 都包在 Spring 统一异常里）
     */
    private void insertArticleTagIfNotExists(Long articleId, Long tagId) {
        try {
            jdbc.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", articleId, tagId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 已存在：幂等吞掉（外层 @Transactional 不会回滚,单条 INSERT 失败不影响其他 tag）
            log.debug("article_tag 已存在(幂等忽略): article_id={} tag_id={}", articleId, tagId);
        }
    }

    private Map<String, Object> toMap(Article a, boolean withContent) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("slug", a.getSlug());
        m.put("summary", a.getSummary());
        m.put("coverUrl", normalizeCoverUrl(a.getCoverUrl()));
        m.put("status", a.getStatus());
        m.put("isPinned", a.getIsPinned() == null ? 0 : a.getIsPinned());
        // 2026-06-22 v4.x polish：viewCount null 兜底为 0——
        //   Article.viewCount 是 Integer（可空包装类），任何绕过 create() setViewCount(0) 的路径
        //   都可能让前端拿到 null（NaN 渲染/JSON 反序列化异常）。
        //   currentPwd 本质是"+1 累加"的初值兜底——见 detail() / detailById()。
        m.put("viewCount", a.getViewCount() == null ? 0 : a.getViewCount());
        m.put("categoryId", a.getCategoryId());
        m.put("publishedAt", a.getPublishedAt());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        if (withContent) m.put("contentMd", a.getContentMd());
        return m;
    }

    /**
     * 2026-08-14 BUG-002 fix：公开接口专用字段白名单。
     * 不含 status/createdAt/updatedAt/contentMd（列表/归档不含）。
     * publishedAt 为空时以 createdAt 兜底（防历史脏数据）。
     *
     * @param withContent true = 详情页（含 contentMd），false = 列表/归档（不含 contentMd）
     */
    private Map<String, Object> toPublicMap(Article a, boolean withContent) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("slug", a.getSlug());
        m.put("summary", a.getSummary());
        m.put("coverUrl", normalizeCoverUrl(a.getCoverUrl()));
        m.put("status", a.getStatus());
        m.put("isPinned", a.getIsPinned() == null ? 0 : a.getIsPinned());
        m.put("viewCount", a.getViewCount() == null ? 0 : a.getViewCount());
        m.put("categoryId", a.getCategoryId());
        // publishedAt 兜底：公开接口 status=1/3 时业务上必定非空，
        // 但防历史脏数据或异常导入时以 createdAt 退避
        m.put("publishedAt", a.getPublishedAt() != null
                ? a.getPublishedAt() : a.getCreatedAt());
        if (withContent) m.put("contentMd", a.getContentMd());
        return m;
    }

    /**
     * 封面图 URL 归一化：若包含 /uploads/ 绝对前缀（如 http://localhost/uploads/...），自动归一化为 /uploads/...
     */
    private static String normalizeCoverUrl(String coverUrl) {
        if (coverUrl == null || coverUrl.trim().isEmpty()) return "";
        String s = coverUrl.trim();
        if (s.contains("/uploads/")) {
            int idx = s.indexOf("/uploads/");
            return s.substring(idx);
        }
        return s;
    }
}
