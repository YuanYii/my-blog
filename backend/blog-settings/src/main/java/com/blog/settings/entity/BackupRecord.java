package com.blog.settings.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据备份记录（REQ-BACKUP-2026-06-20，v4.2.0）
 *
 * 设计依据：docs/设计文档/博客数据备份方案设计.md §6
 *
 * 字段说明：
 *  - status:  PENDING / RUNNING / SUCCESS / FAILED
 *  - errorStage: 失败阶段定位 (DUMP / PACK / UPLOAD / SCRIPT)
 *  - assetUrls: JSON 数组字符串（GitHub asset URL 列表）
 *  - manifestJson: 备份清单原文（db 表行数 / uploads 文件数 / SHA256）
 *
 * 配套：
 *  - BackupRecordMapper（CRUD）
 *  - BackupService（异步执行脚本 + 状态机）
 *  - BackupController（3 个 admin API：run / list / get）
 */
@Data
@TableName("backup_record")
public class BackupRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 备份 tag（GitHub Release tag_name），成功后才填 */
    private String tag;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime startedAt;

    /** 结束时间（成功或失败） */
    private LocalDateTime finishedAt;

    /** db dump 大小（字节） */
    private Long dbSize;

    /** uploads 加密包大小（字节） */
    private Long uploadsSize;

    /** asset 数量（含 manifest / SHA256SUMS） */
    private Integer assetCount;

    /** GitHub asset URL 列表（JSON 数组字符串） */
    private String assetUrls;

    /** 备份清单原文 */
    private String manifestJson;

    /** 失败阶段：DUMP / PACK / UPLOAD / SCRIPT */
    private String errorStage;

    /** 失败信息（不含密码/secret） */
    private String errorMessage;

    /** 触发人 uid（admin 鉴权透传） */
    private Long operatorId;

    /** 触发人 username（冗余存储，user 表改名不影响历史） */
    private String operatorName;

    /**
     * 2026-06-21：触发请求的 traceId（MDC 透传 @Async 不靠谱,存到 record 字段里）
     * 用途：失败详情里展示给 owner,反查 server log(/opt/myblog/logs/blog.log.*)
     */
    private String traceId;
}
