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
 * 数据备份管理（REQ-BACKUP-2026-06-20 + REQ-BACKUP-POLISH-2026-06-21，v4.2.0 / v4.2.1）
 *
 * 设计依据：docs/设计文档/博客数据备份方案设计.md §5
 *
 * API：
 *  - POST   /api/v1/admin/backup/run     触发备份（异步，立即返回 record id）
 *  - GET    /api/v1/admin/backup/list    历史列表（分页）
 *  - GET    /api/v1/admin/backup/{id}    单条详情 + 状态
 *  - DELETE /api/v1/admin/backup/{id}    删除一条备份记录(v4.2.1 polish)
 *          仅删除本地 db 记录，GitHub Release 文件保留
 *          PENDING/RUNNING → 拒绝(3002 BACKUP_RECORD_RUNNING)
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

    /**
     * 2026-06-21 v4.2.1 polish 新增：删除一条备份记录
     * 业务规则在 BackupService.deleteBackupRecord（SUCCESS→删 GitHub+db / FAILED→直删 db / PENDING|RUNNING→拒 3002）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        log.info("备份删除触发: id={} operator={}", id, AuthContext.username(request));
        backupService.deleteBackupRecord(id, request);
        return Result.success();
    }

    /**
     * 2026-06-24 DEV-003 新增：从 GitHub 备份仓库同步最近 3 条 release
     * 项目初始化场景下用于回填 backup_record（已存在 tag 跳过，天然幂等）
     */
    @PostMapping("/sync")
    public Result<Integer> sync(HttpServletRequest request) {
        log.info("备份同步触发: operator={}", AuthContext.username(request));
        int inserted = backupService.syncFromGithub(request);
        return Result.success(inserted);
    }
}
