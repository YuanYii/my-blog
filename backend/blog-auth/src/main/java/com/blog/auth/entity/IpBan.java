package com.blog.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * IP 封禁记录（2026-06-18 新增：全站请求频率限制）
 *
 * 触发：IpRateLimitFilter 检测到同一 IP 1 秒内请求数超过阈值
 * （blog.security.ip-rate-limit.max-requests-per-second），写一条记录并封禁
 * blog.security.ip-rate-limit.ban-duration-minutes 分钟。
 *
 * 热路径（每个请求都要查的"是否被封禁"）不查这张表，走 Redis（ip_ban:{ip}）；
 * 这张表只在"触发封禁那一刻"写一行，供 admin 后台查看历史 / 手动解封 /
 * app 重启后回灌 Redis 缓存用。
 */
@Data
@TableName("ip_ban")
public class IpBan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被封禁的客户端 IP（TrustedProxyUtil.resolveClientIp 解析结果） */
    private String ip;

    /** 触发封禁时 1 秒窗口内的请求数（快照，审计用） */
    private Integer requestCount;

    /** 封禁原因 */
    private String reason;

    /** 封禁开始时间 */
    private LocalDateTime bannedAt;

    /** 封禁到期时间（过期后自动解封，无需人工干预） */
    private LocalDateTime expireAt;

    /** 是否被管理员手动解封（true 后即使 expireAt 未到也视为已解封） */
    private Boolean unbanned;

    /** 手动解封时间 */
    private LocalDateTime unbannedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
