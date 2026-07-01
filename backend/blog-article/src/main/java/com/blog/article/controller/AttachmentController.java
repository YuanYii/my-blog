package com.blog.article.controller;

import com.blog.article.entity.Attachment;
import com.blog.article.service.AttachmentService;
import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.util.Map;

/**
 * 文章附件 Controller（2026-07-01 DEV-001）
 *
 * 路由约定：
 *  - /api/v1/articles/{id}/attachment        公开下载（文章页用）
 *  - /api/v1/admin/articles/{id}/attachment  admin 上传/软删
 *  - /api/v1/admin/attachments              admin 后台列表/恢复/硬删
 *
 * 下载用 InputStreamResource + StreamUtils.copy 流式响应，
 * 不读 byte[]（避 OOM，大文件友好）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    // ============ 公开下载 ============

    /**
     * 公开下载附件（前台文章页用）。
     * - 软删 / 文件不存在 → 410 Gone
     * - 命中 → 流式响应 + Content-Disposition: attachment; filename="..."
     */
    @GetMapping("/articles/{id}/attachment")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id,
                                                       HttpServletRequest request) throws IOException {
        // 2026-07-01 DEV-001 fix：不过滤 deleted（软删时也查找，让 resolveFile 返 410）
        Attachment a = attachmentService.getByArticleIdIncludeDeleted(id);
        Path p = attachmentService.resolveFile(a);  // 410 走全局异常处理
        // 日志：公开下载 INFO（含 IP / article_id / file_size）
        log.info("附件下载：id={} articleId={} size={} ip={}",
                a.getId(), id, a.getFileSize(),
                request.getHeader("X-Forwarded-For") != null
                        ? request.getHeader("X-Forwarded-For") : request.getRemoteAddr());

        HttpHeaders headers = new HttpHeaders();
        // 中文文件名 URL encode + RFC 5987
        String encoded = URLEncoder.encode(a.getFileName(), "UTF-8").replace("+", "%20");
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(a.getFileSize());

        InputStreamResource body = new InputStreamResource(new FileInputStream(p.toFile()));
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    // ============ Admin: 上传 / 软删 ============

    @PostMapping("/admin/articles/{id}/attachment")
    public Result<Attachment> upload(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      HttpServletRequest request) {
        Attachment a = attachmentService.upload(id, file, request);
        return Result.success(a);
    }

    @DeleteMapping("/admin/articles/{id}/attachment")
    public Result<Void> softDelete(@PathVariable Long id, HttpServletRequest request) {
        attachmentService.softDelete(id, request);
        return Result.success();
    }

    // ============ Admin: 后台列表 / 恢复 / 硬删 ============

    @GetMapping("/admin/attachments")
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "0") String deleted) {
        // 2026-07-01 OPT-002/OPT-003：返回类型由 PageResult<Attachment> 改 PageResult<Map>
        //   SQL 已 LEFT JOIN article，列表里 articleTitle/articleDeleted 是关键字段
        return Result.success(attachmentService.list(page, size, deleted));
    }

    @DeleteMapping("/admin/attachments/{id}")
    public Result<Void> hardDelete(@PathVariable Long id, HttpServletRequest request) {
        attachmentService.hardDelete(id, request);
        return Result.success();
    }

    @PutMapping("/admin/attachments/{id}/restore")
    public Result<Void> restore(@PathVariable Long id, HttpServletRequest request) {
        attachmentService.restore(id, request);
        return Result.success();
    }
}