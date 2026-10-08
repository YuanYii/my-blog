package com.blog.article.controller;

import com.blog.article.entity.Article;
import com.blog.article.entity.Category;
import com.blog.article.entity.ImportRecord;
import com.blog.article.entity.Tag;
import com.blog.article.service.ArticleImportService;
import com.blog.article.service.ArticleService;
import com.blog.common.BusinessException;
import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.settings.service.AdvancedSettingsAccessor;
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
    // 2026-06-27 DEV-003：高级开关——enableSearch=false 时禁用 keyword 搜索
    private final AdvancedSettingsAccessor advancedSettings;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        // 2026-06-27 DEV-003：搜索开关——关闭时拒绝带 keyword 的请求（普通列表/分类/标签仍正常）
        if (keyword != null && !keyword.isEmpty() && !advancedSettings.searchEnabled()) {
            throw new BusinessException(403, "站点搜索已禁用");
        }
        return articleService.list(page, size, categoryId, tagId, keyword, request);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return articleService.stats();
    }

    @GetMapping("/{slug}")
    public Result<Map<String, Object>> detail(@PathVariable String slug) {
        return articleService.detail(slug);
    }

    @GetMapping("/archives")
    public Result<List<Map<String, Object>>> archives() {
        return articleService.archives();
    }

    @GetMapping("/admin/detail/{id}")
    public Result<Map<String, Object>> detailById(@PathVariable Long id) {
        return articleService.detailById(id);
    }

    /**
     * 2026-07-15 BUG-001：管理员预览草稿（绕过 status=1 过滤）。
     * 路径 /articles/admin/preview/{slug} 命中 AdminAuthFilter 的 /admin/ 白名单 → 仅管理员可访问，
     * 公开 /post/{slug} 仍只显示已发布，草稿不被访客访问。
     */
    @GetMapping("/admin/preview/{slug}")
    public Result<Map<String, Object>> adminPreview(@PathVariable String slug) {
        return articleService.adminPreview(slug);
    }

    @GetMapping("/admin/all")
    public Result<PageResult<Map<String, Object>>> adminList(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            // 2026-07-01 BUG-002：adminList 加 deleted 参数（默认 0 = 未删；1 = 已删；all = 不过滤）
            //   配合 posts.vue 顶部 3 tab 切换
            @RequestParam(required = false, defaultValue = "0") String deleted) {
        return articleService.adminList(page, size, status, categoryId, keyword, sort, deleted);
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
        // 2026-07-01 BUG-002：行为变更——原 articleMapper.deleteById 物理删除
        //   现在改为软删除（UPDATE deleted=1）。前端用 hardDelete 端点做硬删。
        return articleService.delete(id, request);
    }

    /**
     * 批量软删文章（单次请求处理多个 ID，避免并发触发 IP 限流）。
     * 用 POST 而非 DELETE：HTTP DELETE 规范不建议带 request body。
     */
    @PostMapping("/admin/batch-delete")
    public Result<Map<String, Object>> batchDelete(@RequestBody List<Long> ids, HttpServletRequest request) {
        return articleService.batchDelete(ids, request);
    }

    /**
     * 2026-07-01 BUG-002：硬删除文章（admin/posts.vue "已删除"tab 行操作二次确认后调用）。
     * 已删除文章二次确认走 GET /admin/devices 等接口同模式的 danger confirm。
     */
    @DeleteMapping("/admin/articles/{id}/hard")
    public Result<Void> hardDelete(@PathVariable Long id, HttpServletRequest request) {
        return articleService.hardDelete(id, request);
    }

    /**
     * 2026-07-01 BUG-002：恢复文章（admin/posts.vue "已删除"tab 行操作）。
     */
    @PutMapping("/admin/articles/{id}/restore")
    public Result<Void> restore(@PathVariable Long id, HttpServletRequest request) {
        return articleService.restore(id, request);
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
     * 上传 HTML 文件创建文章（admin）
     * POST /articles/admin/import-html
     * 将 HTML 文件保存到磁盘，并在数据库中存储文件路径
     */
    @PostMapping("/admin/import-html")
    public Result<Map<String, Object>> importHtml(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "title", required = false) String title,
                                                   HttpServletRequest request) throws IOException {
        // 校验文件类型
        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".html") && !fileName.toLowerCase().endsWith(".htm"))) {
            throw new BusinessException(400, "仅支持 .html 或 .htm 文件");
        }
        // 校验文件大小（最大 2MB）
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new BusinessException(400, "HTML 文件大小不能超过 2MB");
        }
        
        // 读取文件内容
        String htmlContent = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        
        // 保存 HTML 文件到磁盘
        String uploadDir = System.getProperty("UPLOAD_DIR", "/opt/myblog/uploads");
        String htmlDir = uploadDir + "/html";
        java.io.File dir = new java.io.File(htmlDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        // 生成唯一文件名：时间戳 + 随机数 + .html
        long timestamp = System.currentTimeMillis();
        int random = (int) (Math.random() * 10000);
        String uniqueFileName = String.format("html_%d_%04d.html", timestamp, random);
        String filePath = htmlDir + "/" + uniqueFileName;
        
        // 写入文件
        java.io.File htmlFile = new java.io.File(filePath);
        java.nio.file.Files.write(htmlFile.toPath(), file.getBytes(), java.nio.file.StandardOpenOption.CREATE);
        
        // 生成访问 URL
        String htmlFileUrl = "/uploads/html/" + uniqueFileName;
        
        // 使用自定义标题或文件名
        String effectiveTitle = title != null && !title.trim().isEmpty() ? title.trim() : fileName;
        
        // 调用 Service 创建文章（传入文件路径）
        return articleService.createHtmlArticle(htmlContent, effectiveTitle, htmlFileUrl, request);
    }

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
