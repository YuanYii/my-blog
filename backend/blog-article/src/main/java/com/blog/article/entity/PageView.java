package com.blog.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 访问记录（公开页）
 * 2026-06-16 v2.5.0 新增：替代 article.view_count 累加的"无差别计数"，
 * 提供 PV / UV / 今日 / 趋势图 等真实统计所需粒度。
 *
 * 表设计要点（详见 docs/sql/migrations/20260616_page_view_stats.sql）：
 * - 主键 (id, created_at)：分区表强制要求主键含分区列
 * - UNIQUE (visitor, path, created_at)：天然按天去重（INSERT IGNORE）
 * - 按月 RANGE 分区：老数据直接 DROP PARTITION 秒级清理
 */
@Data
@TableName("page_view")
public class PageView {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** URL 路径（不含域名），如 /post/spring-boot-jwt-security */
    private String path;

    /** 文章详情才有，列表/其它页 NULL */
    @TableField("article_id")
    private Long articleId;

    /** 访客 UUID（前端 localStorage 持久化，跨 session 稳定） */
    private String visitor;

    /** 客户端 IP（X-Forwarded-For 解析） */
    private String ip;

    /** 浏览器 UA（截断 200 字符） */
    private String userAgent;

    /** 来源 URL（document.referrer，截断 500 字符） */
    private String referer;

    private LocalDateTime createdAt;
}
