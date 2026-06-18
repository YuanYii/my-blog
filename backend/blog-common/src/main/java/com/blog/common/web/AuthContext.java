package com.blog.common.web;

import javax.servlet.http.HttpServletRequest;

/**
 * 鉴权上下文传递（REQ-LOG-2026-06-18 / FR-3）
 *
 * AdminAuthFilter 校验通过后，把当前操作人 uid / deviceId 暂存到 request attribute，
 * 供下游 admin controller 在打"操作人"业务日志时读取（FR-3.1/3.4/3.7 要求含操作人）。
 *
 * 为什么用 request attribute 而不是 SecurityContext / MDC：
 *  - 项目无 Spring Security，没有现成 principal 通道；
 *  - request attribute 随请求线程天然隔离，无需像 MDC 那样手动清理，零泄漏风险；
 *  - 只在 admin 写操作日志里按需读取，不污染全量日志 pattern。
 *
 * 注意：公开端点（访客评论提交等）不走 AdminAuthFilter，attribute 为空 —— 调用方需容忍 null。
 */
public final class AuthContext {

    private AuthContext() {}

    public static final String ATTR_UID = "blog.auth.uid";
    public static final String ATTR_DEVICE_ID = "blog.auth.deviceId";

    /** 写入当前操作人（AdminAuthFilter 放行时调用） */
    public static void set(HttpServletRequest request, Object uid, String deviceId) {
        if (request == null) return;
        if (uid != null) request.setAttribute(ATTR_UID, uid);
        if (deviceId != null) request.setAttribute(ATTR_DEVICE_ID, deviceId);
    }

    /** 读取当前操作人 uid；非 admin 链路返回 null */
    public static Object uid(HttpServletRequest request) {
        return request == null ? null : request.getAttribute(ATTR_UID);
    }

    /** 读取当前操作设备 deviceId；非 admin 链路返回 null */
    public static Object deviceId(HttpServletRequest request) {
        return request == null ? null : request.getAttribute(ATTR_DEVICE_ID);
    }
}
