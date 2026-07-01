package com.blog.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.article.entity.Article;
import com.blog.article.entity.Category;
import com.blog.article.entity.Tag;
import com.blog.article.mapper.ArticleMapper;
import com.blog.article.mapper.CategoryMapper;
import com.blog.article.mapper.TagMapper;
import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.common.BusinessException;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
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
    }

    // ===== 公开接口 =====

    public Result<PageResult<Map<String, Object>>> list(long page, long size,
                                                        Long categoryId, Long tagId, String keyword) {
        if (page < 1) throw new BusinessException(400, "page 必须 >= 1");
        if (size < 1 || size > 100) throw new BusinessException(400, "size 必须在 1-100 之间");
        // 2026-06-30 BUG-001：关键词长度下限校验。
        // 单字符 / 通用词（如"的"、"用"、"？"）会命中几乎所有文章 title，导致"搜索结果 = 全部"
        // 用户感知为"搜索按钮无法使用"。trim 后长度 < 2 字符直接 400 拒绝。
        if (keyword != null && !keyword.isEmpty() && keyword.trim().length() < 2) {
            throw new BusinessException(400, "关键词至少 2 个字符");
        }

        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("status", 1).eq("deleted", 0);
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
        qw.orderByDesc("published_at");

        Page<Article> p = articleMapper.selectPage(new Page<>(page, size), qw);
        List<Map<String, Object>> records = p.getRecords().stream().map(a -> toMap(a, true)).collect(Collectors.toList());
        fillTagIds(records);
        return Result.success(PageResult.of(records, p.getTotal(), p.getCurrent(), p.getSize()));
    }

    public Result<Map<String, Object>> detail(String slug) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("slug", slug).eq("status", 1).eq("deleted", 0);
        Article article = articleMapper.selectOne(qw);
        if (article == null) throw new BusinessException(1001, "文章不存在");
        jdbc.update("UPDATE article SET view_count = view_count + 1, updated_at = updated_at WHERE id = ?",
                article.getId());
        article.setViewCount(article.getViewCount() == null ? 1 : article.getViewCount() + 1);
        return Result.success(toMap(article, true));
    }

    public Result<List<Map<String, Object>>> archives() {
        // 2026-06-22 v4.x polish：.last("LIMIT 1000") 是绕过 MyBatis-Plus 方言处理的硬编码 SQL。
        //   当前 SQLite / MySQL 都兼容 LIMIT <n>，没问题；
        //   TODO：未来切 SQL Server（TOP）/ Oracle（ROWNUM <=）时改为方言感知（用 DialectFactory 或在 mapper xml 里写多套）。
        List<Article> list = articleMapper.selectList(
            new QueryWrapper<Article>()
                .eq("status", 1)
                .eq("deleted", 0)
                .orderByDesc("published_at")
                .last("LIMIT 1000"));
        List<Map<String, Object>> records = list.stream()
            .map(a -> toMap(a, true))
            .collect(Collectors.toList());
        fillTagIds(records);
        return Result.success(records);
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
        return Result.success(m);
    }

    // ===== Admin 文章 =====

    public Result<PageResult<Map<String, Object>>> adminList(long page, long size,
                                                             Integer status, Long categoryId,
                                                             String keyword, String sort) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("deleted", 0);
        if (status != null) qw.eq("status", status);
        if (categoryId != null) qw.eq("category_id", categoryId);
        if (keyword != null && !keyword.isEmpty()) qw.like("title", keyword);
        if (sort != null && !sort.isEmpty()) {
            // 2026-06-22 v4.x polish：sort 改用白名单 Map（不再 split(":") 静默吞 :extra）
            //   格式：field:dir，多余段（:foo:bar）直接拒绝 → 静默走默认排序
            //   加新字段只需扩 ADMIN_SORT_FIELDS，新增不会忘改两处
            String[] parts = sort.split(":", 3);
            if (parts.length == 2) {
                String field = parts[0].trim();
                String dir = parts[1].trim().toLowerCase();
                Boolean asc = ADMIN_SORT_FIELDS.get(field);
                if (asc != null) {
                    // dir 为 "desc" → false（降序），其他（包括 "asc"）→ 走 Map 预置默认升序
                    qw.orderBy(true, "desc".equals(dir) ? false : asc, field);
                }
            }
        } else {
            qw.orderByDesc("updated_at");
        }

        Page<Article> p = articleMapper.selectPage(new Page<>(page, size), qw);
        List<Map<String, Object>> records = p.getRecords().stream().map(a -> toMap(a, true)).collect(Collectors.toList());
        fillTagIds(records);
        return Result.success(PageResult.of(records, p.getTotal(), p.getCurrent(), p.getSize()));
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
        if (article.getCategoryId() == null) {
            throw new BusinessException(400, "分类不能为空");
        }
        if (categoryMapper.selectById(article.getCategoryId()) == null) {
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
        if (!Arrays.asList(0, 1, 2).contains(article.getStatus())) {
            log.warn("文章创建校验失败：status 非法 status={} slug={} operator={}", article.getStatus(), article.getSlug(), AuthContext.uid(request));
            throw new BusinessException(1010, "status 取值非法: " + article.getStatus());
        }
        if (Integer.valueOf(1).equals(article.getStatus()) && article.getPublishedAt() == null) {
            article.setPublishedAt(java.time.LocalDateTime.now());
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
        if (article.getStatus() != null && !Arrays.asList(0, 1, 2).contains(article.getStatus())) {
            log.warn("文章更新校验失败：status 非法 id={} status={} operator={}", id, article.getStatus(), AuthContext.uid(request));
            throw new BusinessException(1010, "status 取值非法: " + article.getStatus());
        }
        Integer effectiveStatus = article.getStatus() != null ? article.getStatus() : existing.getStatus();
        if (Integer.valueOf(1).equals(effectiveStatus)
                && article.getPublishedAt() == null
                && existing.getPublishedAt() == null) {
            article.setPublishedAt(java.time.LocalDateTime.now());
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
     * 删除文章（多步写：articleMapper.deleteById + DELETE article_tag → 事务保护）
     *
     * 2026-06-22 v4.x polish：原实现缺事务保护——articleMapper.deleteById 成功后，
     * 若后续 DELETE FROM article_tag 失败（如并发锁），会留下"幽灵标签"（指向已删除文章）。
     */
    @Transactional
    public Result<Void> delete(Long id, HttpServletRequest request) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) throw new BusinessException(1001, "文章不存在");
        articleMapper.deleteById(id);
        jdbc.update("DELETE FROM article_tag WHERE article_id = ?", id);
        log.info("文章删除：id={} slug={} operator={}", id, existing.getSlug(), AuthContext.uid(request));
        return Result.success();
    }

    // ===== 分类 =====

    public Result<List<Category>> categories() {
        QueryWrapper<Category> qw = new QueryWrapper<>();
        qw.eq("visible", 1).orderByAsc("sort");
        return Result.success(categoryMapper.selectList(qw));
    }

    public Result<List<Category>> categoriesAll() {
        QueryWrapper<Category> qw = new QueryWrapper<>();
        qw.orderByAsc("sort");
        return Result.success(categoryMapper.selectList(qw));
    }

    public Result<Map<Long, Long>> categoryArticleCounts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category_id AS cid, COUNT(*) AS cnt FROM article WHERE deleted = 0 GROUP BY category_id");
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object cid = row.get("cid");
            if (cid == null) continue;
            counts.put(((Number) cid).longValue(), ((Number) row.get("cnt")).longValue());
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
        // A3（2026-06-20）：消除 N+1
        Map<Long, Long> countByTag = new HashMap<>();
        List<Map<String, Object>> countRows = jdbc.queryForList(
            "SELECT tag_id AS tid, COUNT(*) AS cnt FROM article_tag GROUP BY tag_id");
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
        m.put("coverUrl", a.getCoverUrl());
        m.put("status", a.getStatus());
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
}
