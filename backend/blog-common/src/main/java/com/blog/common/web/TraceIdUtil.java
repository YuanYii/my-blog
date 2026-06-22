package com.blog.common.web;

import org.slf4j.MDC;

/**
 * traceId 工具（2026-06-21 v4.2.1 polish 新增）
 *
 * 背景：GlobalExceptionHandler 已经把 traceId 拼到业务错/兜底 500 的 message 后缀，
 *       但 AdminAuthFilter / IpRateLimitFilter 这两个"直接 writeUnauthorized / 429"的
 *       filter 不走 GlobalExceptionHandler，response message 不带 traceId。
 *       前端 toast 看到"token 过期或无效"无法定位日志。
 *
 * 解法：TraceIdFilter 在入口已把 traceId 写入 MDC（filter chain 还在执行时 MDC 必在），
 *       这两个拒绝 filter 在写响应前从 MDC 读 traceId，拼到 message 后缀。
 *
 * 用法：
 *   response.getWriter().write(objectMapper.writeValueAsString(
 *       Result.error(code, TraceIdUtil.withTraceId(message))
 *   ));
 */
public final class TraceIdUtil {

    private TraceIdUtil() {}

    /** MDC key —— 与 TraceIdFilter / logback-spring.xml 对齐 */
    public static final String MDC_TRACE_ID = "traceId";

    /**
     * 给 message 拼 traceId 后缀（无 traceId 时原样返回）。
     * 格式："原消息（请反馈编号 traceId=xxx）"，与 GlobalExceptionHandler 现有行为一致。
     */
    public static String withTraceId(String message) {
        if (message == null) message = "";
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) return message;
        return message + "（请反馈编号 traceId=" + traceId + "）";
    }
}
