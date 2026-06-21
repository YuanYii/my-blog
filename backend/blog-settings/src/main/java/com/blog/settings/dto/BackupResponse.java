package com.blog.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 备份详情响应（REQ-BACKUP-2026-06-20，v4.2.0）
 *
 * 对应 API：
 *  - GET /api/v1/admin/backup/{id}
 *  - GET /api/v1/admin/backup/list
 *  - POST /api/v1/admin/backup/run（只填 id/status/message）
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupResponse {

    private Long id;
    private String status;

    /** 备份 tag（SUCCESS 才有） */
    private String tag;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 耗时（秒） */
    private Long durationSec;

    private Long dbSize;
    private Long uploadsSize;
    private Integer assetCount;
    /** GitHub asset URL 列表 */
    private List<String> assetUrls;

    /** 失败时才有 */
    private String errorStage;
    private String errorMessage;

    /**
     * 2026-06-21：触发请求的 traceId（失败时透传给 owner,反查 server log 用）
     * 触发成功也会带,便于跨系统排查；前端在失败弹框里展示 + 复制
     */
    private String traceId;

    private Long operatorId;
    private String operatorName;

    /** 仅 run 响应里有 message 字段 */
    private String message;
}
