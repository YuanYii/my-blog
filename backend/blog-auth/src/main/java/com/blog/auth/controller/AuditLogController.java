package com.blog.auth.controller;

import com.blog.auth.service.AuditLogService;
import com.blog.common.PageResult;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计日志查询 Controller（2026-07-01 DEV-004）
 *
 * 提供分页 + 筛选（target 模块名 / operation 操作类型）的查询接口，供 admin 报表页用。
 *
 * 写入路径由 AuditLogAspect 自动处理，本 controller 只读不写。
 */
@Slf4j
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 分页查询 + 筛选。
     *
     * @param page      页码（从 1 起，默认 1）
     * @param size      每页大小（默认 20）
     * @param target    模块名（可选，传 'all' 或空=不过滤）
     * @param operation 操作类型（可选，传 'all' 或空=不过滤）
     */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "all") String target,
            @RequestParam(required = false, defaultValue = "all") String operation) {
        return Result.success(auditLogService.list(page, size, target, operation));
    }

    /**
     * 暴露给前端的"模块名白名单"——保证下拉选项与服务端一致。
     * 不传则服务端用 'all' = 不过滤。
     */
    @GetMapping("/targets")
    public Result<List<String>> targets() {
        return Result.success(AuditLogService.TARGET_OPTIONS);
    }

    /**
     * 暴露给前端的"操作类型白名单"——保证下拉选项与服务端一致。
     */
    @GetMapping("/operations")
    public Result<List<String>> operations() {
        return Result.success(AuditLogService.OPERATION_OPTIONS);
    }
}
