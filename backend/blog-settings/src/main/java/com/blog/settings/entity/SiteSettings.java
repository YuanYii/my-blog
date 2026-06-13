package com.blog.settings.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点设置（按 section 存 JSON）
 *
 * 2026-06-08：从原 SettingsController 的 static 内存 Map 迁出
 *  - section: 配置段标识（blog / social / preferences / theme / advanced）
 *  - data: 配置数据 JSON 字符串（service 层 ObjectMapper 解析）
 *
 * 配套：SiteSettingsService（5min Redis 缓存 + 写时清缓存）
 */
@Data
@TableName("site_settings")
public class SiteSettings {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String section;

    /** JSON 字符串（service 层用 ObjectMapper 解析为 Map） */
    private String data;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
