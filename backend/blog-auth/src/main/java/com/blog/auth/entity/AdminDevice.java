package com.blog.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台设备白名单
 * 配合 X-Device-Id header 实现"只允许授权设备登录"
 */
@Data
@TableName("admin_device")
public class AdminDevice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备 UUID（前端生成，存 localStorage） */
    private String deviceId;

    /** 设备名（从 UA 解析得到，如 "Safari on macOS"） */
    private String deviceName;

    /** 原始 User-Agent */
    private String userAgent;

    /** 登录 IP */
    private String ip;

    /** pending-待授权 / approved-已授权 / revoked-已吊销 */
    private String status;

    /** 最后活跃时间（每次成功调用 admin API 时更新） */
    private LocalDateTime lastSeenAt;

    /** 授权时间 */
    private LocalDateTime approvedAt;

    /** 授权来源 device_id（首次系统自动迁移为 "system"） */
    private String approvedBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
