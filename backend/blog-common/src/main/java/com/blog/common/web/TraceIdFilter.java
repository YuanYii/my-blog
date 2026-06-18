package com.blog.common.web;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 traceId filter（REQ-LOG-2026-06-18 / FR-5）
 *
 * 职责：
 *  - FR-5.1：入口生成 traceId；若请求头已带 X-Trace-Id 则沿用（便于跨层/重放排查）
 *  - FR-5.2：traceId 写入 SLF4J MDC（key=traceId），所有日志经 logback-spring.xml 的
 *            %X{traceId} 自动携带，无需业务代码手动拼
 *  - FR-5.3：响应头回写 X-Trace-Id，前端 devtools 可读、可复制给 owner 定位（US-3）
 *  - FR-5.4：请求结束后 finally 清理 MDC，防 Tomcat 线程复用导致 traceId 串号/泄漏
 *
 * 顺序（FR-5.5）：
 *  - @Order(Ordered.HIGHEST_PRECEDENCE)：显式声明为 filter 链最前，
 *    保证 IpRateLimitFilter / AdminAuthFilter 的拒绝日志也带 traceId。
 *  - 不依赖 Spring Boot 默认 bean 名字母序（项目无 FilterRegistrationBean）。
 *  - 注：IpRateLimitFilter 同为 HIGHEST_PRECEDENCE，已下调为 HIGHEST_PRECEDENCE+1，
 *    确保 traceId 在限流判定之前就绪。
 *
 * 范围（RISK-3）：本期不做 @Async/线程池的 MDC 透传，单请求同线程内有效即可。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key —— 与 logback-spring.xml 的 %X{traceId} 对齐，兼容未来 Micrometer Tracing */
    public static final String MDC_TRACE_ID = "traceId";
    /** 请求/响应头名 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        // 响应头回写：即使后续 filter/controller 抛错，header 也已就绪（FR-5.3）
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程复用前务必清理，否则下一个请求会复用上一个 traceId（FR-5.4）
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /**
     * 优先沿用上游传入的 X-Trace-Id（做长度/字符兜底），否则生成 32 字符（UUID 去横线）。
     * 决策记录：32 字符唯一性更强，未来对接 Micrometer Tracing 无冲突。
     */
    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_TRACE_ID);
        if (incoming != null) {
            String trimmed = incoming.trim();
            // 防注入/防超长：只接受合理长度的 [0-9a-zA-Z-] 串，否则重新生成
            if (!trimmed.isEmpty() && trimmed.length() <= 64 && trimmed.matches("[0-9a-zA-Z\\-]+")) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
