package com.blog.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 恢复详情响应（REQ-RESTORE-2026-06-20，v4.3.0）
 *
 * 对应 API：
 *  - POST /api/v1/admin/restore/run（只填 id/status/message）
 *  - GET  /api/v1/admin/restore/{id}（全字段，轮询用）
 *
 * 与 BackupResponse 字段对齐但去掉 dbSize/uploadsSize/assetUrls/assetCount
 * （恢复是下载数据，不是产出数据，没有这些概念）。
 * 多出 verifyDiff（v3 数据校验结果）和 sourceTag/sourceRecordId。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestoreResponse {

    private Long id;
    private String status;

    /** 恢复的源备份 record id */
    private Long sourceRecordId;

    /** 恢复的源备份 GitHub Release tag */
    private String sourceTag;

    /** 恢复范围：DB_ONLY / DB_UPLOADS */
    private String scope;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 耗时（秒） */
    private Long durationSec;

    /** 失败时才有 */
    private String errorStage;
    private String errorMessage;

    /** v3 数据校验差异（SUCCESS 时也可能有，表示部分表行数对不上） */
    private String verifyDiff;

    private Long operatorId;
    private String operatorName;

    /** 仅 run 响应里有 message 字段 */
    private String message;
}