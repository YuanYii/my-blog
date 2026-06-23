package com.blog.settings.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.blog.common.Result;
import com.blog.common.web.AuthContext;
import com.blog.settings.dto.RestoreResponse;
import com.blog.settings.service.RestoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 数据恢复管理（REQ-RESTORE-2026-06-20，v4.3.1）
 *
 * 设计依据：docs/设计文档/博客数据恢复方案设计.md §5.1
 *
 * API（v4.3.1 删掉 list 端点, 与设计文档一致）：
 *  - POST /api/v1/admin/restore/run   触发恢复（异步, 立即返回 record id）
 *  - GET  /api/v1/admin/restore/{id}  单条详情 + 状态（前端轮询用）
 *
 * 鉴权：admin（AdminAuthFilter 已在 /admin/** 路径统一拦截）
 *
 * 流程:
 *  1. POST /run → 建 restore_record(PENDING) → 异步跑 blog-restore.sh
 *  2. 脚本在 myblog-restore.slice 独立 cgroup + myblog 身份运行
 *  3. 脚本完成后写 .result.json → RestoreStartupReconciler 回填状态
 *  4. 前端轮询 GET /{id} 看到 SUCCESS / FAILED / UNKNOWN
 */
@Slf4j
@RestController
@RequestMapping("/admin/restore")
@RequiredArgsConstructor
public class RestoreController {

    private final RestoreService restoreService;

    /**
     * 触发恢复
     * body: { recordId: number, scope: "DB_ONLY" | "DB_UPLOADS" }
     * 立即返回新建 record id, 恢复在后台异步执行
     */
    @PostMapping("/run")
    public Result<RestoreResponse> run(@RequestBody RestoreRunRequest body, HttpServletRequest request) {
        if (body == null || body.recordId == null) {
            return Result.error(400, "recordId 不能为空");
        }
        if (body.scope == null || body.scope.isEmpty()) {
            body.scope = "DB_ONLY";  // 默认仅 db
        }
        log.info("恢复触发: operator={} record_id={} scope={}",
            AuthContext.uid(request), body.recordId, body.scope);
        try {
            Long id = restoreService.triggerRestore(request, body.recordId, body.scope);
            return Result.success(RestoreResponse.builder()
                .id(id)
                .status("PENDING")
                .sourceRecordId(body.recordId)
                .scope(body.scope)
                .message("恢复任务已创建, 服务将停止约 1-5 分钟, 异步执行中")
                .build());
        } catch (Exception e) {
            log.warn("恢复触发失败: operator={} record_id={} err={}",
                AuthContext.uid(request), body.recordId, e.getMessage());
            throw e;
        }
    }

    /**
     * 单条详情（前端轮询用）
     */
    @GetMapping("/{id}")
    public Result<RestoreResponse> get(@PathVariable Long id) {
        RestoreResponse r = restoreService.getById(id);
        if (r == null) {
            return Result.error(404, "恢复记录不存在");
        }
        return Result.success(r);
    }

    /**
     * 恢复历史列表（分页，按 started_at 降序）
     * @param page 页码（从 1 开始）
     * @param size 每页条数（默认 20）
     */
    @GetMapping("/list")
    public Result<IPage<RestoreResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(restoreService.list(page, size));
    }

    /**
     * 触发恢复的请求体
     */
    @lombok.Data
    public static class RestoreRunRequest {
        private Long recordId;
        /** DB_ONLY 或 DB_UPLOADS, 默认 DB_ONLY */
        private String scope;
    }
}