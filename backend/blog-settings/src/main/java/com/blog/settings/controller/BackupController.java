package com.blog.settings.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.blog.common.Result;
import com.blog.common.web.AuthContext;
import com.blog.settings.dto.BackupResponse;
import com.blog.settings.service.BackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 数据备份管理（REQ-BACKUP-2026-06-20，v4.2.0）
 *
 * 设计依据：docs/设计文档/博客数据备份方案设计.md §5
 *
 * API：
 *  - POST /api/v1/admin/backup/run   触发备份（异步，立即返回 record id）
 *  - GET  /api/v1/admin/backup/list  历史列表（分页）
 *  - GET  /api/v1/admin/backup/{id}  单条详情 + 状态
 *
 * 鉴权：admin（AdminAuthFilter 已在 /admin/** 路径统一拦截）
 */
@Slf4j
@RestController
@RequestMapping("/admin/backup")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    /**
     * 触发备份
     * 立即返回新建 record id，备份在后台异步执行
     */
    @PostMapping("/run")
    public Result<BackupResponse> run(HttpServletRequest request) {
        log.info("备份触发: operator={}", AuthContext.uid(request));
        Long id = backupService.triggerBackup(request);
        return Result.success(BackupResponse.builder()
                .id(id)
                .status("PENDING")
                .message("备份任务已创建，异步执行中")
                .build());
    }

    /**
     * 历史列表
     * @param page 页码（从 1 开始）
     * @param size 每页条数（默认 20）
     */
    @GetMapping("/list")
    public Result<IPage<BackupResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(backupService.list(page, size));
    }

    /**
     * 单条详情（前端轮询用）
     */
    @GetMapping("/{id}")
    public Result<BackupResponse> get(@PathVariable Long id) {
        BackupResponse r = backupService.getById(id);
        if (r == null) {
            return Result.error(404, "备份记录不存在");
        }
        return Result.success(r);
    }
}
