package com.blog.article.controller;

import com.blog.article.entity.Article;
import com.blog.article.entity.Category;
import com.blog.article.entity.ImportRecord;
import com.blog.article.entity.Tag;
import com.blog.article.service.ArticleImportService;
import com.blog.article.service.ArticleService;
import com.blog.common.PageResult;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final ArticleImportService articleImportService;

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

    // ============ 2026-06-24 DEV-002：文章导入 ============

    /**
     * 上传 ZIP 包导入文章（admin）
     * 由 admin 鉴权（/articles/admin/* 在 ApiWhitelistInterceptor 中要求 admin）
     */
    @PostMapping("/admin/import")
    public Result<Map<String, Object>> importZip(@RequestParam("file") MultipartFile file,
                                                  HttpServletRequest request) throws IOException {
        Long id = articleImportService.enqueueImport(file, request);
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        return Result.success(data);
    }

    /** 查询单条导入任务状态 */
    @GetMapping("/admin/import/{id}")
    public Result<ImportRecord> getImport(@PathVariable Long id) {
        return Result.success(articleImportService.getRecord(id));
    }

    /** 列出最近导入记录（最多 50 条） */
    @GetMapping("/admin/import")
    public Result<List<ImportRecord>> listImports(
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(articleImportService.listRecent(limit));
    }

    /** 下载导入模板 ZIP */
    @GetMapping("/admin/import/template")
    public ResponseEntity<byte[]> downloadTemplate() throws IOException {
        byte[] data = articleImportService.readTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "import-template.zip");
        return new ResponseEntity<>(data, headers, org.springframework.http.HttpStatus.OK);
    }
}
