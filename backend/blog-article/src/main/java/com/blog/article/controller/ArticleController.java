package com.blog.article.controller;

import com.blog.article.entity.Article;
import com.blog.article.entity.Category;
import com.blog.article.entity.Tag;
import com.blog.article.service.ArticleService;
import com.blog.common.PageResult;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 文章 CRUD（薄 controller，业务逻辑委托 ArticleService）。
 */
@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String keyword) {
        return articleService.list(page, size, categoryId, tagId, keyword);
    }

    @GetMapping("/{slug}")
    public Result<Map<String, Object>> detail(@PathVariable String slug) {
        return articleService.detail(slug);
    }

    @GetMapping("/archives")
    public Result<List<Map<String, Object>>> archives() {
        return articleService.archives();
    }

    @GetMapping("/id/{id}")
    public Result<Map<String, Object>> detailById(@PathVariable Long id) {
        return articleService.detailById(id);
    }

    @GetMapping("/admin/all")
    public Result<PageResult<Map<String, Object>>> adminList(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort) {
        return articleService.adminList(page, size, status, categoryId, keyword, sort);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Article article, HttpServletRequest request) {
        return articleService.create(article, request);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Article article, HttpServletRequest request) {
        return articleService.update(id, article, request);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        return articleService.delete(id, request);
    }

    @GetMapping("/categories")
    public Result<List<Category>> categories() {
        return articleService.categories();
    }

    @GetMapping("/categories/all")
    public Result<List<Category>> categoriesAll() {
        return articleService.categoriesAll();
    }

    @GetMapping("/categories/with-count")
    public Result<Map<Long, Long>> categoryArticleCounts() {
        return articleService.categoryArticleCounts();
    }

    @PostMapping("/categories")
    public Result<Category> createCategory(@RequestBody Category category) {
        return articleService.createCategory(category);
    }

    @PutMapping("/categories/{id}")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Category category) {
        return articleService.updateCategory(id, category);
    }

    @DeleteMapping("/categories/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        return articleService.deleteCategory(id);
    }

    @GetMapping("/tags")
    public Result<List<Map<String, Object>>> tags() {
        return articleService.tags();
    }

    @PostMapping("/tags")
    public Result<Tag> createTag(@RequestBody Tag tag) {
        return articleService.createTag(tag);
    }

    @DeleteMapping("/tags/{id}")
    public Result<Void> deleteTag(@PathVariable Long id) {
        return articleService.deleteTag(id);
    }
}
