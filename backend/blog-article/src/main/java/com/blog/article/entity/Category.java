package com.blog.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章分类
 */
@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String slug;
    private String description;
    private Integer sort;
    /** 0-隐藏 1-显示 */
    private Integer visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
