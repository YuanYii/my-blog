package com.blog.settings.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据恢复记录（REQ-RESTORE-2026-06-20，v4.3.0）
 *
 * 设计依据：docs/设计文档/博客数据恢复方案设计.md §4
 *
 * 字段说明：
 *  - status:  PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN
 *      UNKNOWN 给"JVM 在 RUNNING 中途崩溃,新 JVM 启动 30min 后兜底"用
 *  - sourceRecordId: 关联 backup_record.id（哪个备份被恢复）
 *  - sourceTag: 备份的 GitHub Release tag
 *  - scope: DB_ONLY / DB_UPLOADS
 *  - errorStage: 失败阶段定位 DOWNLOAD / VERIFY / DECRYPT / IMPORT / UPLOADS / POSTCHECK / ORPHAN / INTERNAL
 *  - verifyDiff: 数据完整性校验差异（manifest 行数 vs 实际）
 *
 * 配套：
 *  - RestoreRecordMapper（CRUD + countRunning + findAllRunning）
 *  - RestoreService（双向互斥 + 同 JVM 异步执行）
 *  - RestoreExecutor（v5 新增,同进程 6 步流水线）
 *  - RestoreStartupReconciler（v5 极简版:仅 JVM 崩溃孤儿兜底）
 *  - RestoreController（admin API: run / get / list）
 */
@Data
@TableName("restore_record")
public class RestoreRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN */
    private String status;

    /** 关联的 backup_record.id（哪个备份被恢复） */
    private Long sourceRecordId;

    /** 备份的 GitHub Release tag（崩溃后回填用） */
    private String sourceTag;

    /** 恢复范围：DB_ONLY / DB_UPLOADS */
    private String scope;

    /**
     * 开始时间
     * v4.3.1: 不再用 @TableField(fill=FieldFill.INSERT), 改由 RestoreService.triggerRestore 显式
     *   record.setStartedAt(LocalDateTime.now()) 填充（与项目"Java 侧填北京时间,不用 SQLite
     *   DEFAULT CURRENT_TIMESTAMP"约定一致 — MybatisPlusConfig.metaObjectHandler 只填
     *   createdAt/updatedAt, 不填 startedAt）。
     */
    private LocalDateTime startedAt;

    /** 结束时间（成功/失败/UNKNOWN） */
    private LocalDateTime finishedAt;

    /**
     * 失败阶段：
     * PRECHECK / DOWNLOAD / SHA256 / DECRYPT / IMPORT / UPLOADS / START / HEALTH / VERIFY / ORPHAN
     */
    private String errorStage;

    /** 失败信息（不含密码/secret） */
    private String errorMessage;

    /** v3 数据完整性校验差异（manifest 行数 vs 实际） */
    private String verifyDiff;

    /** 触发人 uid（admin 鉴权透传） */
    private Long operatorId;

    /** 触发人 username（冗余存储） */
    private String operatorName;
}