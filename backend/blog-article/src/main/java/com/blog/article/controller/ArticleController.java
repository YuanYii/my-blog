package com.blog.article.controller;

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
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章 CRUD（简版：直接调 mapper）
 */
@Slf4j
@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 文章列表（公开） */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "10") long size,
                                                       @RequestParam(required = false) Long categoryId,
                                                       @RequestParam(required = false) Long tagId,
                                                       @RequestParam(required = false) String keyword) {
        if (page < 1) return Result.error(400, "page 必须 >= 1");
        if (size < 1 || size > 100) return Result.error(400, "size 必须在 1-100 之间");

        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("status", 1).eq("deleted", 0);
        if (categoryId != null) qw.eq("category_id", categoryId);
        if (keyword != null && !keyword.isEmpty()) qw.like("title", keyword);
        // tagId 过滤：2026-06-12 修复——原 SQL 不分页拉全量 article_id 进 IN 子句（1 万行 article_tag 就炸）
        // 修复：用 EXISTS 子查询让 MySQL 自己 join article_tag 拿分页后的 article
        if (tagId != null) {
            qw.exists("SELECT 1 FROM article_tag at WHERE at.article_id = article.id AND at.tag_id = " + tagId);
        }
        qw.orderByDesc("published_at");

        Page<Article> p = articleMapper.selectPage(new Page<>(page, size), qw);
        List<Map<String, Object>> records = p.getRecords().stream().map(a -> toMap(a, true)).collect(Collectors.toList());
        fillTagIds(records);
        return Result.success(PageResult.of(records, p.getTotal(), p.getCurrent(), p.getSize()));
    }

    /** 文章详情（公开，按 slug） */
    @GetMapping("/{slug}")
    public Result<Map<String, Object>> detail(@PathVariable String slug) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("slug", slug).eq("status", 1).eq("deleted", 0);
        Article article = articleMapper.selectOne(qw);
        if (article == null) return Result.error(1001, "文章不存在");
        // 2026-06-12 修复：原 detail 不自增 viewCount，导致首页 / 文章详情阅读数永远不变
        // 2026-06-13 修复：原实现 read-modify-write（getViewCount()+1 后 updateById）有两处 bug——
        //   ① 并发下两个请求都读到 N 再各写 N+1，净 +1 而非 +2，高并发阅读数丢失；
        //   ② updateById 写整行触发 article.updated_at 的 ON UPDATE，导致每次「被阅读」都污染
        //      admin 后台「按更新时间排序」，已发布文章被读一次就窜到列表顶部。
        // 改为单条原子 SQL 自增，并显式保留 updated_at 不被刷新。
        jdbc.update("UPDATE article SET view_count = view_count + 1, updated_at = updated_at WHERE id = ?",
                article.getId());
        article.setViewCount(article.getViewCount() == null ? 1 : article.getViewCount() + 1);
        return Result.success(toMap(article, true));
    }

    /** 归档：返回所有已发布文章，按 published_at DESC，无分页（供归档页用） */
    @GetMapping("/archives")
    public Result<List<Map<String, Object>>> archives() {
        List<Article> list = articleMapper.selectList(
            new QueryWrapper<Article>()
                .eq("status", 1)
                .eq("deleted", 0)
                .orderByDesc("published_at")
                .last("LIMIT 1000"));
        List<Map<String, Object>> records = list.stream()
            .map(a -> toMap(a, true))
            .collect(Collectors.toList());
        fillTagIds(records);  // 补 tagIds
        return Result.success(records);
    }

    /** 文章详情（admin，按 id，用于编辑页加载） */
    @GetMapping("/id/{id}")
    public Result<Map<String, Object>> detailById(@PathVariable Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) return Result.error(1001, "文章不存在");
        Map<String, Object> m = toMap(article, true);
        // 补 tagIds
        List<Map<String, Object>> tagRows = jdbc.queryForList(
            "SELECT tag_id FROM article_tag WHERE article_id = ?", id);
        List<Long> tagIds = tagRows.stream()
            .map(r -> ((Number) r.get("tag_id")).longValue())
            .collect(Collectors.toList());
        m.put("tagIds", tagIds);
        return Result.success(m);
    }

    /** 文章列表（admin，含草稿和已归档） */
    @GetMapping("/admin/all")
    public Result<PageResult<Map<String, Object>>> adminList(@RequestParam(defaultValue = "1") long page,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @RequestParam(required = false) Integer status,
                                                             @RequestParam(required = false) Long categoryId,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String sort) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("deleted", 0);
        if (status != null) qw.eq("status", status);
        // 2026-06-12 修复：原 admin/all 不支持 categoryId/sort，前端 UI 选了分类和排序完全无效
        if (categoryId != null) qw.eq("category_id", categoryId);
        if (keyword != null && !keyword.isEmpty()) qw.like("title", keyword);
        // 2026-06-12 修复：sort 参数支持——格式 "field:asc|desc"，白名单字段防 SQL 注入
        if (sort != null && !sort.isEmpty()) {
            String[] parts = sort.split(":");
            if (parts.length == 2) {
                String field = parts[0].trim();
                String dir = parts[1].trim().toLowerCase();
                if (("published_at".equals(field) || "view_count".equals(field) || "updated_at".equals(field) || "created_at".equals(field))
                    && ("asc".equals(dir) || "desc".equals(dir))) {
                    qw.orderBy(true, "asc".equals(dir), field);
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

    /** 给文章列表填充 tagIds */
    /**
     * 2026-06-17 v2.6.0：业务层去重插入 article_tag
     * 替代原 `INSERT IGNORE INTO article_tag ...`（SQLite 不支持该语法）
     * 性能：多一次 SELECT COUNT（毫秒级，文章-标签关联表通常 0~10 行）
     */
    private void insertArticleTagIfNotExists(Long articleId, Long tagId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE article_id = ? AND tag_id = ?",
                Integer.class, articleId, tagId);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", articleId, tagId);
        }
    }

    private void fillTagIds(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> articleIds = records.stream()
            .map(r -> ((Number) r.get("id")).longValue())
            .collect(Collectors.toList());
        String inClause = articleIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT article_id, tag_id FROM article_tag WHERE article_id IN (" + inClause + ")");
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

    /** 创建文章（admin） */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Article article, HttpServletRequest request) {
        if (article.getTitle() == null || article.getTitle().trim().isEmpty()) {
            return Result.error(400, "标题不能为空");
        }
        if (article.getTitle().length() > 200) {
            return Result.error(400, "标题长度不能超过 200 字符");
        }
        if (article.getSlug() == null || article.getSlug().trim().isEmpty()) {
            return Result.error(400, "slug 不能为空");
        }
        if (article.getSlug().length() > 200) {
            return Result.error(400, "slug 长度不能超过 200 字符");
        }
        if (!article.getSlug().matches("^[a-zA-Z0-9\\u4e00-\\u9fa5\\-]+$")) {
            return Result.error(400, "slug 只能包含字母、数字、中文、连字符");
        }
        if (article.getCategoryId() == null) {
            return Result.error(400, "分类不能为空");
        }
        // 校验分类存在
        if (categoryMapper.selectById(article.getCategoryId()) == null) {
            return Result.error(1001, "分类不存在");
        }
        // 校验 slug 唯一
        Article existing = articleMapper.selectOne(
            new QueryWrapper<Article>().eq("slug", article.getSlug()));
        if (existing != null) {
            // FR-3.2：业务校验失败 WARN（slug 重复）
            log.warn("文章创建校验失败：slug 重复 slug={} operator={}", article.getSlug(), AuthContext.uid(request));
            return Result.error(1002, "slug 已存在");
        }
        // 2026-06-12 修复：tagIds 校验——必须全部存在；不传则当作空
        List<Long> tagIds = article.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            for (Long tid : tagIds) {
                if (tagMapper.selectById(tid) == null) {
                    // FR-3.2：业务校验失败 WARN（标签不存在）
                    log.warn("文章创建校验失败：标签不存在 tagId={} slug={} operator={}", tid, article.getSlug(), AuthContext.uid(request));
                    return Result.error(1003, "标签不存在: id=" + tid);
                }
            }
        }
        article.setId(null);
        article.setViewCount(0);
        article.setDeleted(0);
        if (article.getStatus() == null) article.setStatus(0);
        // 2026-06-13 修复（BUG-064）：status 白名单 0/1/2；越界（4xx 业务码 1010）拒绝
        if (!Arrays.asList(0, 1, 2).contains(article.getStatus())) {
            // FR-3.2：业务校验失败 WARN（status 非法）
            log.warn("文章创建校验失败：status 非法 status={} slug={} operator={}", article.getStatus(), article.getSlug(), AuthContext.uid(request));
            return Result.error(1010, "status 取值非法: " + article.getStatus());
        }
        // 2026-06-13 修复：发布状态(status=1)但未携带 publishedAt 时，由后端补当前时间。
        // 配合前端不再自造 ISO 'Z' 字符串（LocalDateTime 无法解析且时区错位）。
        if (Integer.valueOf(1).equals(article.getStatus()) && article.getPublishedAt() == null) {
            article.setPublishedAt(java.time.LocalDateTime.now());
        }
        articleMapper.insert(article);
        // 2026-06-12 修复：原 create 完全没写 article_tag 关联，admin 编辑器选 tag 全部丢失
        // 2026-06-17 v2.6.0：改用业务层 selectCount 去重（替代 SQL `INSERT IGNORE`，跨 SQLite/MySQL）
        if (tagIds != null && !tagIds.isEmpty()) {
            for (Long tid : tagIds) {
                insertArticleTagIfNotExists(article.getId(), tid);
            }
        }
        // FR-3.1：创建成功 INFO（含 slug、操作人）
        log.info("文章创建：id={} slug={} status={} operator={}", article.getId(), article.getSlug(), article.getStatus(), AuthContext.uid(request));
        Map<String, Object> data = new HashMap<>();
        data.put("id", article.getId());
        data.put("slug", article.getSlug());
        return Result.success(data);
    }

    /** 更新文章（admin） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Article article, HttpServletRequest request) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) {
            return Result.error(1001, "文章不存在");
        }
        // 2026-06-12 修复：原 update 不校验 title/slug 非空 + 长度，导致空 title 也能更新（实测：title 被改成空）
        if (article.getTitle() != null) {
            if (article.getTitle().trim().isEmpty()) {
                return Result.error(400, "标题不能为空");
            }
            if (article.getTitle().length() > 200) {
                return Result.error(400, "标题长度不能超过 200 字符");
            }
        }
        if (article.getSlug() != null) {
            if (article.getSlug().trim().isEmpty()) {
                return Result.error(400, "slug 不能为空");
            }
            if (article.getSlug().length() > 200) {
                return Result.error(400, "slug 长度不能超过 200 字符");
            }
            if (!article.getSlug().matches("^[a-zA-Z0-9\\u4e00-\\u9fa5\\-]+$")) {
                return Result.error(400, "slug 只能包含字母、数字、中文、连字符");
            }
            // 2026-06-12 修复：原 update 不校验 slug 唯一性，改成重复 slug → 500 (DB UNIQUE 约束冲突)
            Article existingBySlug = articleMapper.selectOne(
                new QueryWrapper<Article>().eq("slug", article.getSlug()));
            if (existingBySlug != null && !existingBySlug.getId().equals(id)) {
                // FR-3.2：业务校验失败 WARN（slug 重复）
                log.warn("文章更新校验失败：slug 重复 id={} slug={} operator={}", id, article.getSlug(), AuthContext.uid(request));
                return Result.error(1002, "slug 已存在");
            }
        }
        if (article.getCategoryId() != null && categoryMapper.selectById(article.getCategoryId()) == null) {
            return Result.error(1001, "分类不存在");
        }
        // 2026-06-12 修复：tagIds 校验
        List<Long> tagIds = article.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            for (Long tid : tagIds) {
                if (tagMapper.selectById(tid) == null) {
                    // FR-3.2：业务校验失败 WARN（标签不存在）
                    log.warn("文章更新校验失败：标签不存在 id={} tagId={} operator={}", id, tid, AuthContext.uid(request));
                    return Result.error(1003, "标签不存在: id=" + tid);
                }
            }
        }
        article.setId(id);
        // 2026-06-13 修复（BUG-064）：status 白名单 0/1/2；越界（业务码 1010）拒绝
        if (article.getStatus() != null && !Arrays.asList(0, 1, 2).contains(article.getStatus())) {
            // FR-3.2：业务校验失败 WARN（status 非法）
            log.warn("文章更新校验失败：status 非法 id={} status={} operator={}", id, article.getStatus(), AuthContext.uid(request));
            return Result.error(1010, "status 取值非法: " + article.getStatus());
        }
        // 2026-06-13 修复：发布时若 publishedAt 仍为空（含 posts.vue 批量发布只传 {status:1} 的场景），
        // 后端自动补当前时间；已有 publishedAt 的文章保留原值，不重置发布时间。
        Integer effectiveStatus = article.getStatus() != null ? article.getStatus() : existing.getStatus();
        if (Integer.valueOf(1).equals(effectiveStatus)
                && article.getPublishedAt() == null
                && existing.getPublishedAt() == null) {
            article.setPublishedAt(java.time.LocalDateTime.now());
        }
        articleMapper.updateById(article);
        // 2026-06-12 修复：原 update 完全没动 article_tag，编辑 tag 完全无效
        // 2026-06-17 v2.6.0：改用业务层 selectCount 去重
        if (tagIds != null) {
            // 全量替换策略：先删后插
            jdbc.update("DELETE FROM article_tag WHERE article_id = ?", id);
            for (Long tid : tagIds) {
                insertArticleTagIfNotExists(id, tid);
            }
        }
        // FR-3.1：更新成功 INFO（含 slug、操作人）。slug 可能未传 → 用现有值兜底
        log.info("文章更新：id={} slug={} operator={}", id,
                article.getSlug() != null ? article.getSlug() : existing.getSlug(), AuthContext.uid(request));
        return Result.success();
    }

    /** 删除文章（admin，逻辑删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) {
            return Result.error(1001, "文章不存在");
        }
        articleMapper.deleteById(id);
        // 2026-06-12 修复：原 delete 没清 article_tag，删完留垃圾关联
        jdbc.update("DELETE FROM article_tag WHERE article_id = ?", id);
        // FR-3.1：删除成功 INFO（含 slug、操作人）
        log.info("文章删除：id={} slug={} operator={}", id, existing.getSlug(), AuthContext.uid(request));
        return Result.success();
    }

    /** 分类列表（公开） */
    @GetMapping("/categories")
    public Result<List<Category>> categories() {
        QueryWrapper<Category> qw = new QueryWrapper<>();
        qw.eq("visible", 1).orderByAsc("sort");
        return Result.success(categoryMapper.selectList(qw));
    }

    /** 分类列表（admin，含隐藏） */
    @GetMapping("/categories/all")
    public Result<List<Category>> categoriesAll() {
        QueryWrapper<Category> qw = new QueryWrapper<>();
        qw.orderByAsc("sort");
        return Result.success(categoryMapper.selectList(qw));
    }

    /**
     * 2026-06-13 修复（BUG-056 配套端点）：admin 后台分类页之前用
     *   /articles/admin/all?size=1000 + 自己 reduce 算每分类文章数——
     *   1000 size 截断 + 后端 size 上限 100 已改成 400 后，categories 显示永远 0。
     * 修复：单次 SQL GROUP BY 直接返 categoryId -> count 映射，前端一次拿到全分类文章数。
     * 注：含草稿/归档/已删除以外所有（admin 视角）。
     */
    @GetMapping("/categories/with-count")
    public Result<Map<Long, Long>> categoryArticleCounts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category_id AS cid, COUNT(*) AS cnt FROM article WHERE deleted = 0 GROUP BY category_id"
        );
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object cid = row.get("cid");
            if (cid == null) continue;
            counts.put(((Number) cid).longValue(), ((Number) row.get("cnt")).longValue());
        }
        return Result.success(counts);
    }

    /** 创建分类 */
    @PostMapping("/categories")
    public Result<Category> createCategory(@RequestBody Category category) {
        if (category.getName() == null || category.getName().trim().isEmpty()) {
            return Result.error(400, "名称不能为空");
        }
        if (category.getName().length() > 50) {
            return Result.error(400, "分类名称长度不能超过 50 字符");
        }
        // 2026-06-12 修复：原 createCategory 不传 slug 直接 500（DB slug NOT NULL）；
        // 不传时自动从 name 转换（与 tag 一致），slug 列长度限制是 50 不是 100
        if (category.getSlug() == null || category.getSlug().trim().isEmpty()) {
            category.setSlug(category.getName().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", ""));
        }
        if (category.getSlug().length() > 50) {
            return Result.error(400, "分类 slug 长度不能超过 50 字符");
        }
        if (category.getVisible() == null) category.setVisible(1);
        if (category.getSort() == null) category.setSort(0);
        // 校验 slug 唯一
        Category existing = categoryMapper.selectOne(
            new QueryWrapper<Category>().eq("slug", category.getSlug()));
        if (existing != null) {
            return Result.error(1002, "slug 已存在");
        }
        category.setId(null);
        categoryMapper.insert(category);
        return Result.success(category);
    }

    /** 更新分类 */
    @PutMapping("/categories/{id}")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Category category) {
        if (categoryMapper.selectById(id) == null) {
            return Result.error(1001, "分类不存在");
        }
        // 2026-06-13 修复：原 updateCategory 不做任何校验——
        //   ① name 超长 / 空、slug 超长直接写库或触发 DB 截断；
        //   ② slug 改成与其它分类重复时撞 UNIQUE(uk_slug) → 抛 500（前端只能看到"未知错误"）。
        // 补齐与 createCategory 同等的长度 + 唯一性校验（排除自身）。
        if (category.getName() != null) {
            if (category.getName().trim().isEmpty()) {
                return Result.error(400, "名称不能为空");
            }
            if (category.getName().length() > 50) {
                return Result.error(400, "分类名称长度不能超过 50 字符");
            }
        }
        if (category.getSlug() != null && !category.getSlug().trim().isEmpty()) {
            if (category.getSlug().length() > 50) {
                return Result.error(400, "分类 slug 长度不能超过 50 字符");
            }
            Category existingBySlug = categoryMapper.selectOne(
                new QueryWrapper<Category>().eq("slug", category.getSlug()));
            if (existingBySlug != null && !existingBySlug.getId().equals(id)) {
                return Result.error(1002, "slug 已存在");
            }
        }
        category.setId(id);
        categoryMapper.updateById(category);
        return Result.success();
    }

    /** 删除分类 */
    @DeleteMapping("/categories/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        if (categoryMapper.selectById(id) == null) {
            return Result.error(1001, "分类不存在");
        }
        // 2026-06-12 修复：先校验该分类下是否有文章——有就拒删，避免 article.categoryId 变孤儿
        Long articleCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM article WHERE category_id = ? AND deleted = 0",
            Long.class, id);
        if (articleCount != null && articleCount > 0) {
            return Result.error(1004, "该分类下还有 " + articleCount + " 篇文章，请先迁移或删除");
        }
        categoryMapper.deleteById(id);
        return Result.success();
    }

    /** 标签列表（公开） */
    @GetMapping("/tags")
    public Result<List<Map<String, Object>>> tags() {
        List<Tag> tags = tagMapper.selectList(null);
        List<Map<String, Object>> result = tags.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            m.put("createdAt", t.getCreatedAt());
            Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE tag_id = ?", Long.class, t.getId());
            m.put("articleCount", cnt != null ? cnt : 0);
            return m;
        }).collect(Collectors.toList());
        return Result.success(result);
    }

    /** 创建标签 */
    @PostMapping("/tags")
    public Result<Tag> createTag(@RequestBody Tag tag) {
        if (tag.getName() == null || tag.getName().trim().isEmpty()) {
            return Result.error(400, "名称不能为空");
        }
        if (tag.getName().length() > 50) {
            return Result.error(400, "标签名称长度不能超过 50 字符");
        }
        // 2026-06-13 修复：DB tag.slug 列为 VARCHAR(50)，原校验上限 100 会导致 51~100 字符的 slug
        // 通过校验但在 INSERT 时触发「Data too long」(500)。改为与 DB 列长度一致的 50。
        if (tag.getSlug() != null && tag.getSlug().length() > 50) {
            return Result.error(400, "标签 slug 长度不能超过 50 字符");
        }
        if (tag.getSlug() == null || tag.getSlug().isEmpty()) {
            tag.setSlug(tag.getName().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", ""));
        }
        // 校验 slug/name 唯一
        Tag existing = tagMapper.selectOne(
            new QueryWrapper<Tag>().eq("slug", tag.getSlug()));
        if (existing != null) {
            return Result.error(1002, "slug 已存在");
        }
        tag.setId(null);
        tagMapper.insert(tag);
        return Result.success(tag);
    }

    /** 删除标签 */
    @DeleteMapping("/tags/{id}")
    public Result<Void> deleteTag(@PathVariable Long id) {
        if (tagMapper.selectById(id) == null) {
            return Result.error(1001, "标签不存在");
        }
        tagMapper.deleteById(id);
        // 2026-06-12 修复：删 tag 同步清 article_tag，否则遗留垃圾关联
        jdbc.update("DELETE FROM article_tag WHERE tag_id = ?", id);
        return Result.success();
    }

    private Map<String, Object> toMap(Article a) {
        return toMap(a, false);
    }

    private Map<String, Object> toMap(Article a, boolean withContent) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("slug", a.getSlug());
        m.put("summary", a.getSummary());
        m.put("coverUrl", a.getCoverUrl());
        m.put("status", a.getStatus());
        m.put("viewCount", a.getViewCount());
        m.put("categoryId", a.getCategoryId());
        m.put("publishedAt", a.getPublishedAt());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        if (withContent) m.put("contentMd", a.getContentMd());
        return m;
    }
}
