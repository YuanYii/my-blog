package com.blog.common.security;

import com.blog.auth.service.IpBanService;
import com.blog.common.Result;
import com.blog.common.TrustedProxyUtil;
import com.blog.common.web.TraceIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

/**
 * 全站 IP 限流 + 封禁 filter（2026-06-18 新增，防刷流量）
 *
 * 规则：同一 IP 1 秒内请求数超过 maxRequestsPerSecond → 封禁 banDurationMinutes 分钟，
 * 期间该 IP 的所有请求直接拒绝（HTTP 429），不再转发到后面的 filter / controller。
 *
 * 设计：
 *  - @Order(HIGHEST_PRECEDENCE)：放在 filter 链最前面，比 AdminAuthFilter / PageViewFilter
 *    都先跑——被封的 IP 应该最早被拦截，省掉鉴权 / 统计的开销，也避免把压力传到后面的 DB 操作。
 *  - 计数走 Redis INCR（key 按 IP+秒分桶：rate_limit:{ip}:{epochSecond}），不查/写 SQLite——
 *    生产环境 SQLite 连接池只有 1 个连接，全站每请求一次 DB 操作会直接拖垮站点；
 *    Redis 方案额外的好处是未来如果多实例部署，限流状态天然是共享的。
 *  - 封禁状态查询也走 Redis（IpBanService.isBanned，O(1) GET），DB 只在"触发封禁那一刻"
 *    写一行（IpBanService.ban），不在请求热路径上。
 *  - Redis 故障 → fail-open（放行 + 打日志），避免限流层抖动拖垮全站可用性。
 *
 * 范围：全站所有请求（含 admin 接口），OPTIONS 预检请求不计入，避免误伤 CORS。
 */
@Slf4j
@Component
// REQ-LOG-2026-06-18 / FR-5.5：traceId 必须最先就绪，故 TraceIdFilter 占 HIGHEST_PRECEDENCE，
// 本限流 filter 下调一格（仍是业务 filter 链最前），保证被限流拒绝的请求日志也带 traceId。
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final IpBanService ipBanService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 1 秒内最大请求数，超过即封禁 */
    @Value("${blog.security.ip-rate-limit.max-requests-per-second:10}")
    private int maxRequestsPerSecond;

    /** 封禁时长（分钟） */
    @Value("${blog.security.ip-rate-limit.ban-duration-minutes:30}")
    private int banDurationMinutes;

    /** 总开关：默认开启，出问题时可只改配置临时关闭，不用重新发版 */
    @Value("${blog.security.ip-rate-limit.enabled:true}")
    private boolean enabled;

    private static final String COUNTER_KEY_PREFIX = "rate_limit:";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        // OPTIONS 预检请求不算业务请求，不计入限流
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = TrustedProxyUtil.resolveClientIp(request);

        try {
            // 1) 已被封禁 → 直接拒，不再计数（Redis O(1) GET，是这个 filter 唯一的热路径开销）
            if (ipBanService.isBanned(ip)) {
                writeTooManyRequests(response, "请求过于频繁，该 IP 已被封禁");
                return;
            }

            // 2) 按"IP + 当前秒"分桶计数
            // 2026-06-22 v4.x polish：原子 SET NX EX + INCR——
            //   原"INCR + 独立 EXPIRE"两步非原子，INCR 后 EXPIRE 前服务崩溃/Redis 抖动
            //   会留下"counter 永久不消"的 key 持续累积，理论 OOM。
            //   新方案：先用 SETNX EX 把"首次创建"和"设过期"合成一步原子操作（key 不存在时设 1 并带 TTL）；
            //   已存在的 key 直接 INCR 累加，TTL 跟着原 key 走（首次 SET 时就设好了）。
            long epochSecond = System.currentTimeMillis() / 1000;
            String counterKey = COUNTER_KEY_PREFIX + ip + ":" + epochSecond;
            Boolean created = redis.opsForValue().setIfAbsent(counterKey, "0", Duration.ofSeconds(2));
            Long count = redis.opsForValue().increment(counterKey);
            // 防御：SETNX 失败 + INCR 也失败（如 Redis 短暂不可用）→ count=null → 跳过阈值判断放行
            //      Redis 整体故障已在 catch (Exception e) 里降级放行，这条只补 null 边界
            if (count == null) {
                chain.doFilter(request, response);
                return;
            }
            // created == null 是 Redis 在 SETNX 和 INCR 之间失联（极罕见），TTL 可能没设上，
            // 但下一行 INCR 已成功，下一秒新桶的 SETNX 会兜底，整体仍可控——记 debug 即可
            if (created == null) {
                log.debug("[IpRateLimit] setIfAbsent 返回 null,INCR 已成功: ip={} count={}", ip, count);
            }

            if (count != null && count > maxRequestsPerSecond) {
                String reason = "1秒内请求" + count + "次，超过阈值" + maxRequestsPerSecond;
                ipBanService.ban(ip, count.intValue(), reason, Duration.ofMinutes(banDurationMinutes));
                writeTooManyRequests(response, "请求过于频繁，该 IP 已被封禁");
                return;
            }
        } catch (Exception e) {
            // 限流层本身故障绝不能拖垮主站——降级为放行
            log.warn("[IpRateLimit] 限流检查异常，降级放行: ip={}", ip, e);
        }

        chain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, String message) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        // 2026-06-21 v4.2.1 polish: 拼 traceId 后缀,与 AdminAuthFilter / GlobalExceptionHandler 一致
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(429, TraceIdUtil.withTraceId(message))));
    }
}
