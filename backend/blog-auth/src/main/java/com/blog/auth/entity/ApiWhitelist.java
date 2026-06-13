package com.blog.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API 路由白名单（AdminAuthFilter 用）
 * 配合数据库配置 + 缓存机制实现"内部路由"动态管理
 */
@Data
@TableName("api_whitelist")
public class ApiWhitelist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** API 路径前缀，例 /api/v1/articles */
    private String pathPrefix;

    /** public-公开放行 / admin-必须鉴权 */
    private String type;

    /** 1-启用 / 0-禁用 */
    private Boolean enabled;

    /** 用途说明 */
    private String description;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
