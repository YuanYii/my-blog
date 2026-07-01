package com.blog.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（2026-07-01 DEV-004）
 *
 * AOP 自动拦截 admin 写端点 + 公开下载端点，每条操作一行记录。
 *
 * 字段语义：
 *  - operator：admin 端点 = AuthContext.username（来自 token subject）；
 *                公开下载端点 = "anonymous"
 *  - operation：CREATE / UPDATE / DELETE / APPROVE / REJECT / DOWNLOAD
 *  - target：模块名称（20 个，含 settings 9 子项；详见 AuditLogAspect 路径映射表）
 *  - detail：操作详情（拼接主体名 / id 等可读信息，可空）
 *  - ip：操作人 IP（admin 端由 AuthContext → request 链路；公开下载由 servlet request）
 *
 * 写入路径：AuditLogAspect 在目标方法正常返回（success 状态）后异步写库，
 * 避免污染业务事务。失败 / 异常场景暂不记录（业务异常由 GlobalExceptionHandler 统一打日志）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人（admin = username；公开下载 = anonymous） */
    private String operator;

    /** 操作类型：CREATE / UPDATE / DELETE / APPROVE / REJECT / DOWNLOAD */
    private String operation;

    /** 模块名称（20 个） */
    private String target;

    /** 操作详情（可空，如 "article #8235: 「测试文章」"） */
    private String detail;

    /** 操作 IP */
    private String ip;

    /** 操作时间（Java 填北京时间，不依赖 MyBatis-Plus MetaObjectHandler，因为 Aspect 直接 JDBC 写） */
    private LocalDateTime createdAt;
}
