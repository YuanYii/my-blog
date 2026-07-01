package com.blog.article.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章附件（2026-07-01 DEV-001）
 *
 * - 一文一附件：article_id 唯一约束，强制"先删旧附件再上传"
 * - 二段删除：softDelete (deleted=1, 文件保留) → hardDelete (先删文件后删 DB)
 * - 公开下载软删返 410 Gone（语义更准：资源存在但已删除）
 */
@Data
@TableName("article_attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文章 ID（UNIQUE） */
    private Long articleId;

    /** 原始文件名（含扩展名，用于下载时 Content-Disposition） */
    private String fileName;

    /** 磁盘相对路径，相对于 blog.attachment.local.dir，如 attachments/2026/07/20260701-{uuid}.zip */
    private String filePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型，如 application/zip */
    private String mimeType;

    /** 0=未删除 / 1=软删除（文件保留在磁盘） */
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}