package com.blog.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章
 */
@Data
@TableName("article")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String slug;
    private String summary;
    private String contentMd;
    private String coverUrl;
    /** 0-草稿 1-已发布 2-已归档 */
    private Integer status;
    private Integer viewCount;
    private Long categoryId;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /** 关联的标签 ID 列表（不映射到 DB） */
    @TableField(exist = false)
    private List<Long> tagIds;
}
