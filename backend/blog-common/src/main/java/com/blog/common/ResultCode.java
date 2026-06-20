package com.blog.common;

import lombok.Getter;

/**
 * 统一响应状态码
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "ok"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 业务错误 1xxx
    BUSINESS_ERROR(1000, "业务异常"),
    ARTICLE_NOT_FOUND(1001, "文章不存在"),
    CATEGORY_NOT_FOUND(1002, "分类不存在"),
    TAG_NOT_FOUND(1003, "标签不存在"),
    COMMENT_NOT_FOUND(1004, "评论不存在"),
    USER_NOT_FOUND(1005, "用户不存在"),
    INVALID_CREDENTIALS(1006, "用户名或密码错误"),
    TOKEN_INVALID(1007, "Token 无效或已过期"),
    // 设备白名单（2xxx）
    // 2026-06-07 二次精简：用户原话要求——
    // PENDING: 设备未授权，请联系管理员
    // REVOKED: 当前设备已被禁止登录
    DEVICE_PENDING(2001, "设备未授权，请联系管理员"),
    DEVICE_REVOKED(2002, "当前设备已被禁止登录"),
    // 2026-06-16 修订：消息文本同时覆盖"吊销/删除"两种自我解绑场景——见 DeviceService.revoke / delete
    DEVICE_SELF_REVOKE_FORBIDDEN(2003, "不能吊销/删除当前登录设备"),

    // 数据备份（3xxx，REQ-BACKUP-2026-06-20）
    // 3001: 已有 RUNNING 任务,触发新备份被拒
    BACKUP_CONFLICT(3001, "已有正在执行的备份任务,请等待完成后再试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
