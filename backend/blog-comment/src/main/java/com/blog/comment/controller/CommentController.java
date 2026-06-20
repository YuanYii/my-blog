package com.blog.comment.controller;

import com.blog.comment.service.CommentService;
import com.blog.common.PageResult;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 评论接口（薄 controller，业务逻辑委托 CommentService）。
 */
@RestController
@RequestMapping("/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long articleId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return commentService.list(articleId, page, size);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return commentService.create(body, request);
    }

    @GetMapping("/admin")
    public Result<PageResult<Map<String, Object>>> adminList(
            @RequestParam(defaultValue = "0") int status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return commentService.adminList(status, page, size);
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Integer> body,
                                     HttpServletRequest request) {
        return commentService.updateStatus(id, body, request);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        return commentService.delete(id, request);
    }
}
