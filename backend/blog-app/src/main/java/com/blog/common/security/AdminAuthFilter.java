package com.blog.common.security;

import com.blog.auth.entity.ApiWhitelist;
import com.blog.auth.service.ApiWhitelistService;
import com.blog.auth.service.DeviceService;
import com.blog.auth.util.JwtUtil;
import com.blog.common.BusinessException;
import com.blog.common.Result;
import com.blog.common.ResultCode;
import com.blog.common.TrustedProxyUtil;
import com.blog.common.web.TraceIdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B2（2026-06-20）：Admin 鉴权 filter 重构。
 *
 * 核心原则：
 * 1. 显式 admin 路由表优先——枚举每个需要鉴权的端点，命中即拦截。
 * 2. 写操作默认拒绝——POST/PUT/DELETE/PATCH 不在 public 白名单内的一律要求鉴权。
 * 3. 防遗漏 WARN——未在 admin 表 / 未在 public 白名单命中的请求打 WARN（含去重 + IP 频次限制）。
 * 4. 路径边界修复——ApiWhitelistService.matchPath 已补充边界校验（风险④）。
 * 5. #14 修复——GET /articles/categories/all + with-count 收入 admin 路由表（信息泄露）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final DeviceService deviceService;
    private final ApiWhitelistService whitelistService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 漏登记 WARN：同 IP 每分钟最多打 N 条，之后降 DEBUG（防日志放大） */
    @Value("${blog.security.unregistered-warn.per-min:5}")
    private int unregisteredWarnPerMin;

    /** 已告警 key（method:path），进程生命周期内同 key 只打一次 */
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    /** IP 频次窗口：long[]{count, windowStartSec} */
    private final ConcurrentHashMap<String, long[]> warnRateMap = new ConcurrentHashMap<>();

    // ======================== 显式 Admin 路由表 ========================
    //
    // 格式：RouteSpec(method, pathPattern)
    //   method:      精确 HTTP 方法，或 "*" 表示任意方法
    //   pathPattern: 以 "/" 结尾 → 前缀匹配；否则精确匹配
    //
    // 覆盖附录 C 所有 admin 端点（全量 grep 核验 2026-06-20）。

    private static final List<RouteSpec> ADMIN_ROUTES = Arrays.asList(
        // /admin/** 前缀——dashboard/devices/ip-bans/settings/uploads/api-whitelist
        new RouteSpec("*",      "/admin/"),

        // 文章 admin（散落在 /articles/ 下）
        new RouteSpec("GET",    "/articles/admin/"),           // GET /articles/admin/all
        new RouteSpec("GET",    "/articles/id/"),              // GET /articles/id/{id}
        new RouteSpec("POST",   "/articles"),                  // POST /articles（精确）
        new RouteSpec("PUT",    "/articles/"),                 // PUT /articles/{id}
        new RouteSpec("DELETE", "/articles/"),                 // DELETE /articles/{id}

        // 分类 admin
        new RouteSpec("POST",   "/articles/categories"),       // POST /articles/categories（精确）
        new RouteSpec("PUT",    "/articles/categories/"),      // PUT /articles/categories/{id}
        new RouteSpec("DELETE", "/articles/categories/"),      // DELETE /articles/categories/{id}

        // #14 修复：categories/all + with-count 含隐藏分类/草稿计数，信息泄露，收入 admin
        new RouteSpec("GET",    "/articles/categories/all"),
        new RouteSpec("GET",    "/articles/categories/with-count"),

        // 标签 admin
        new RouteSpec("POST",   "/articles/tags"),             // POST /articles/tags（精确）
        new RouteSpec("DELETE", "/articles/tags/"),            // DELETE /articles/tags/{id}

        // 评论 admin
        new RouteSpec("GET",    "/comments/admin"),            // GET /comments/admin（精确）
        new RouteSpec("PUT",    "/comments/"),                 // PUT /comments/{id}/status
        new RouteSpec("DELETE", "/comments/"),                 // DELETE /comments/{id}

        // Auth admin
        new RouteSpec("GET",    "/auth/me"),                   // GET /auth/me（精确）
        new RouteSpec("PUT",    "/auth/me/")                   // PUT /auth/me/password
    );

    // ======================== 显式 Public 写端点白名单 ========================
    // 匿名可访问的写操作（只有这两个；其余写操作一律要求鉴权）
    private static final List<RouteSpec> PUBLIC_WRITE_ROUTES = Arrays.asList(
        new RouteSpec("POST", "/auth/login"),
        new RouteSpec("POST", "/comments")      // 精确：POST /comments，不含 /comments/{id}
    );

    // ======================== shouldNotFilter ========================

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 剥离 context-path（如 /api/v1），确保路径与 ADMIN_ROUTES / DB 白名单一致
        // 不用 getServletPath()：MockMvc 默认将其置为 ""，会导致单测全部失效
        String path = stripContextPath(request);
        String method = request.getMethod();

        // OPTIONS 预检直接放行
        if ("OPTIONS".equalsIgnoreCase(method)) return true;

        // 1. 命中显式 admin 路由表 → 必须鉴权（return false）
        if (matchesRoutes(method, path, ADMIN_ROUTES)) return false;

        // 2. 命中 DB public 白名单
        ApiWhitelist hit = whitelistService.matchPath(path);
        if (hit != null && ApiWhitelistService.TYPE_PUBLIC.equals(hit.getType())) {
            // GET / HEAD 匿名可读
            if (isReadMethod(method)) return true;
            // 显式 public 写端点
            if (matchesRoutes(method, path, PUBLIC_WRITE_ROUTES)) return true;
            // 其余写操作——default-deny（风险①）
            return false;
        }

        // 3. 未命中 admin 表、未命中 public 白名单
        if (isReadMethod(method)) {
            // GET 未登记：防遗漏 WARN，但放行（防误伤新增公开接口）
            warnUnregistered(method, path, request);
            return true;
        }
        // 写操作未登记：default-deny，并打 WARN
        warnUnregistered(method, path, request);
        return false;
    }

    // ======================== doFilterInternal（鉴权核心） ========================

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = stripContextPath(request);
        String method = request.getMethod();
        String ip = TrustedProxyUtil.resolveClientIp(request);

        // CORS 头
        String origin = request.getHeader("Origin");
        if (origin != null) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,X-Device-Id");
        }

        // 1) 校验 token
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            log.warn("admin 鉴权拒绝：缺少 Bearer token, method={} path={} ip={}", method, path, ip);
            writeUnauthorized(response, ResultCode.UNAUTHORIZED.getCode(), "未登录");
            return;
        }
        String token = auth.substring(7);
        Object tokenDeviceId;
        Object uid;
        String username = null; // 2026-06-21 v4.2.1 polish: 从 token subject 取 username,供业务日志/操作人字段使用
        try {
            io.jsonwebtoken.Claims claims = jwtUtil.parse(token);
            uid = claims.get("uid");
            if (uid == null) {
                log.warn("admin 鉴权拒绝：token 缺少 uid claim, method={} path={} ip={}", method, path, ip);
                writeUnauthorized(response, ResultCode.TOKEN_INVALID.getCode(), "token 无效");
                return;
            }
            tokenDeviceId = claims.get("deviceId");
            // 2026-06-21 v4.2.1 polish: token 签发时 JwtUtil.generate 已 .setSubject(username)
            // 这里零额外 IO 拿一下,后续业务日志能直接显示真实用户名(不再 "uid:1")
            try {
                String sub = claims.getSubject();
                if (sub != null && !sub.isEmpty()) username = sub;
            } catch (Exception ignored) {
                // subject 解析失败不阻塞(旧 token 可能没 subject),username 留 null
            }
        } catch (Exception e) {
            log.warn("admin 鉴权拒绝：token 过期或无效, method={} path={} ip={}", method, path, ip);
            writeUnauthorized(response, ResultCode.TOKEN_INVALID.getCode(), "token 过期或无效");
            return;
        }

        // 2) 校验设备白名单
        String deviceId = request.getHeader("X-Device-Id");
        try {
            deviceService.verifyOnRequest(deviceId);
        } catch (BusinessException e) {
            log.warn("admin 鉴权拒绝：设备校验未通过 code={} reason={} deviceId={} method={} path={} ip={}",
                    e.getCode(), e.getMessage(), deviceId, method, path, ip);
            writeUnauthorized(response, e.getCode(), e.getMessage());
            return;
        }

        // 3) 防 token 借用：token.deviceId == header.X-Device-Id
        if (deviceId != null && !deviceId.isEmpty()
                && tokenDeviceId != null && !tokenDeviceId.toString().isEmpty()
                && !tokenDeviceId.toString().equals(deviceId)) {
            log.warn("admin 鉴权拒绝：token 与设备不匹配 tokenDeviceId={} headerDeviceId={} method={} path={} ip={}",
                    tokenDeviceId, deviceId, method, path, ip);
            writeUnauthorized(response, ResultCode.UNAUTHORIZED.getCode(), "token 与设备不匹配");
            return;
        }

        // 2026-06-21 v4.2.1 polish: 透传 username 到 AuthContext,业务日志/操作人字段不再 "uid:xxx"
        com.blog.common.web.AuthContext.set(request, uid, deviceId, username);
        chain.doFilter(request, response);
    }

    // ======================== 工具方法 ========================

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** 检查 (method, path) 是否匹配 routes 列表中任一规则 */
    private static boolean matchesRoutes(String method, String path, List<RouteSpec> routes) {
        for (RouteSpec spec : routes) {
            if (spec.matches(method, path)) return true;
        }
        return false;
    }

    /**
     * 防遗漏 WARN：路径未在 admin 表 / public 白名单登记。
     * 去重（同 key 进程生命周期只打一次）+ IP 频次上限（防日志放大）。
     */
    private void warnUnregistered(String method, String path, HttpServletRequest request) {
        String key = method + ":" + path;
        boolean firstTime = warnedKeys.add(key);

        String ip = TrustedProxyUtil.resolveClientIp(request);
        boolean withinRateLimit = checkAndIncrementWarnRate(ip);

        if (firstTime && withinRateLimit) {
            log.warn("疑似未登记端点（admin/public 表均未命中）method={} path={} ip={}", method, path, ip);
        } else {
            log.debug("疑似未登记端点（已去重/超频次降级）method={} path={} ip={}", method, path, ip);
        }
    }

    /** 固定窗口限频：同 IP 每分钟最多 unregisteredWarnPerMin 条 WARN，超出返回 false */
    private boolean checkAndIncrementWarnRate(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        long now = Instant.now().getEpochSecond();
        boolean[] allowed = {true};
        warnRateMap.compute(ip, (k, old) -> {
            if (old == null || now - old[1] > 60) {
                return new long[]{1L, now};
            }
            if (old[0] >= unregisteredWarnPerMin) {
                allowed[0] = false;
                return old;
            }
            old[0]++;
            return old;
        });
        return allowed[0];
    }

    /**
     * 2026-06-22 v4.x polish：warnRateMap 周期清理（防内存泄漏）
     * 原实现只在 checkAndIncrementWarnRate 路径上累加 entry,无清理逻辑。
     * 攻击者扫各种未登记路径 → warnRateMap 持续膨胀。
     * 修法：每 60s 扫一遍,删掉窗口已过期的 entry。
     */
    @Scheduled(fixedDelay = 60 * 1000L, initialDelay = 60 * 1000L)
    void evictExpiredWarnRate() {
        long now = Instant.now().getEpochSecond();
        int n = 0;
        java.util.Iterator<java.util.Map.Entry<String, long[]>> it = warnRateMap.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, long[]> e = it.next();
            if (now - e.getValue()[1] > 60) {
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            log.info("[AdminAuthFilter] warnRateMap 周期清理: removed={} remaining={}", n, warnRateMap.size());
        }
    }

    private void writeUnauthorized(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        // 2026-06-21 v4.2.1 polish: 拼 traceId 后缀,与 GlobalExceptionHandler 行为一致
        // 不走 GlobalExceptionHandler,这里手工补(避免前端 toast "token 过期"看不到 traceId 没法定位日志)
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(code, TraceIdUtil.withTraceId(message))));
    }

    // 剥离 context-path，生产环境（/api/v1/admin/...）→ /admin/...；测试环境 contextPath="" 不变
    private static String stripContextPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    // ======================== 路由规则描述符 ========================

    private static final class RouteSpec {
        private final String method;   // HTTP 方法，"*" 表示任意
        private final String pattern;  // 以 "/" 结尾 → 前缀匹配；否则精确匹配

        RouteSpec(String method, String pattern) {
            this.method = method;
            this.pattern = pattern;
        }

        boolean matches(String reqMethod, String reqPath) {
            if (!"*".equals(method) && !method.equalsIgnoreCase(reqMethod)) return false;
            if (pattern.endsWith("/")) {
                // 前缀匹配（含路径边界）
                return reqPath.startsWith(pattern) || reqPath.equals(pattern.substring(0, pattern.length() - 1));
            }
            // 精确匹配
            return pattern.equals(reqPath);
        }
    }
}
