package com.blog.upgrade;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 升级记录（2026-07-08）
 *
 * 状态机：PENDING → RUNNING → SUCCESS / FAILED
 */
@Data
@TableName("upgrade_record")
public class UpgradeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目标版本号，如 v5.3.0 */
    private String targetVersion;

    /** full（全量代码升级）/ init（初始化） */
    private String mode;

    /** 是否导入数据 */
    private Boolean importDb;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 升级开始时间 */
    private LocalDateTime startedAt;

    /** 升级完成时间 */
    private LocalDateTime finishedAt;

    /** 升级前版本号 */
    private String fromVersion;

    /** 失败信息 */
    private String errorMessage;

    /** 触发人 uid */
    private Long operatorId;

    /** 触发人 username */
    private String operatorName;

    /** 操作 IP */
    private String ip;
}
