package com.blog.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章导入记录（2026-06-24 DEV-002）
 *
 * 状态机：PENDING → RUNNING → SUCCESS / FAILED
 *  - PENDING：进入队列等待执行
 *  - RUNNING：导入进行中
 *  - SUCCESS：导入完成（fail_count 可能 >0,部分失败仍算 SUCCESS,前端按 fail_count 提示）
 *  - FAILED：导入异常中止（如 ZIP 解析失败）
 */
@Data
@TableName("import_record")
public class ImportRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传的 ZIP 文件名（仅用于历史展示） */
    private String fileName;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;

    /** 失败详情（500 字截断） */
    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private Long operatorId;
    private String operatorName;
}
