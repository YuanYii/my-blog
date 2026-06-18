package com.blog.common.security;

import com.blog.auth.entity.ApiWhitelist;
import com.blog.auth.service.ApiWhitelistService;
import com.blog.auth.service.DeviceService;
import com.blog.auth.util.JwtUtil;
import com.blog.common.BusinessException;
import com.blog.common.Result;
import com.blog.common.ResultCode;
import com.blog.common.TrustedProxyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Admin 鉴权 filter
 * - 拦截 /admin/* 路径
 * - 校验 JWT（token 是否有效）
 * - 校验设备白名单（X-Device-Id 是否在 approved 列表）
 * - /auth/* /health /articles /comments 等公开接口不走这里
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final DeviceService deviceService;
    private final ApiWhitelistService whitelistService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 放行 OPTIONS 预检请求（CORS）
        if ("OPTIONS".equalsIgnoreCase(method)) return true;

        // 2026-06-12 严重安全修复（CVE-级别）：
        // 原逻辑只看 path prefix，不看 HTTP 方法。配合 api_whitelist 里 `/api/v1/articles`
        // 和 `/api/v1/comments` 被标为 public，导致以下端点全部 **匿名可访问**：
        //   - POST/PUT/DELETE /api/v1/articles[/{id}]            (创建/编辑/删除文章)
        //   - POST/PUT/DELETE /api/v1/articles/categories[/...]   (分类管理)
        //   - POST/DELETE     /api/v1/articles/tags[/...]         (标签管理)
        //   - GET             /api/v1/articles/admin/all          (admin 列表，能看草稿)
        //   - GET             /api/v1/articles/id/{id}            (admin 详情)
        //   - GET             /api/v1/comments/admin              (admin 评论列表)
        //   - PUT             /api/v1/comments/{id}/status        (审核/拒评论)
        //   - DELETE          /api/v1/comments/{id}               (物理删评论)
        // 攻击者无需登录即可批量发文/删全站文章/清空评论审核队列。
        //
        // 双层修复：
        //   1) 这里：在 public 命中下，对写方法（POST/PUT/DELETE/PATCH）默认要求鉴权，
        //      仅 isPublicWriteAllowed 白名单的两个端点（登录、提交评论）保持匿名；
        //   2) docs/sql/migrations/20260612_admin_subpaths.sql：给 api_whitelist 加更精细的
        //      admin 子前缀（/articles/admin、/articles/id、/comments/admin），
        //      配合 ApiWhitelistService 已有的"最长前缀优先"匹配，确保 admin 子路径被识别。
        ApiWhitelist hit = whitelistService.matchPath(path);

        // 明确 admin → 必须鉴权
        if (hit != null && "admin".equals(hit.getType())) return false;

        // 2026-06-13 修复（auto_fix BUG-001 + BUG-002 联合兜底）：
        // 即使最长前缀命中了 public（如 `/api/v1/articles`），子路径含以下任一特征
        // 时强制走鉴权，防止"父前缀 public 把 admin 子路径一起放行"：
        //   - `/admin/`：admin 子空间（/articles/admin/all、/comments/admin、/admin/uploads 等）
        //   - `/id/{数字}`：admin 按 id 取详情（/articles/id/123）
        //   - path 以 `/api/v1/admin/` 开头但未在白名单显式登记（如 /admin/uploads 漏登记）
        // 这些端点都是 admin 写/读敏感数据，匿名放行属于严重安全漏洞。
        if (isAdminSubpath(path)) return false;

        // 未命中 + 非 admin 子空间 → 防御性默认放行（注释保持原意：新增接口不要误伤）
        if (hit == null) return true;

        // 命中 public：GET / HEAD 是读，匿名 OK
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) return true;

        // 命中 public 但是写方法：除非显式在 isPublicWriteAllowed 里授权，
        // 否则一律要求鉴权（兜底防御）
        return isPublicWriteAllowed(path, method);
    }

    /**
     * 判定 path 是否为"应被鉴权但可能被父前缀 public 误放行"的 admin 子空间。
     * 配合最长前缀匹配使用，作为多层防御。
     */
    private boolean isAdminSubpath(String path) {
        if (path == null) return false;
        // 1. 含 `/admin/` 子段（排除 `/admin/login` —— 那个走 isPublicWriteAllowed）
        if (path.contains("/admin/")) return true;
        // 2. 以 `/admin` 结尾（如 `/comments/admin`、`/articles/admin`）—— 父前缀 public 的子段
        if (path.endsWith("/admin")) return true;
        // 3. `/id/{纯数字}` —— admin 按 id 取详情（编辑页加载）
        if (path.matches(".*/id/\\d+(/.*)?$")) return true;
        // 4. `/api/v1/admin/*` 但白名单未登记（如 /admin/uploads 漏登记）
        if (path.startsWith("/api/v1/admin/")) return true;
        return false;
    }

    /**
     * 显式列出"可匿名调用"的写端点。除了这里之外，所有 public 前缀下的写请求都要求 token。
     * 例如：
     *   - POST /api/v1/auth/login         登录本身
     *   - POST /api/v1/comments           访客提交评论
     */
    private boolean isPublicWriteAllowed(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) return false;
        if (path == null) return false;
        // 登录
        if (path.endsWith("/auth/login")) return true;
        // 访客评论提交：bare /comments，不允许后缀（/{id} / status / /admin 等）
        if (path.endsWith("/comments")) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 鉴权链路日志（REQ-LOG-2026-06-18 / FR-2）：每个拒绝点打 WARN，含 path / method / IP / 拒绝原因，
        // 便于 owner grep 溯源（US-1）。traceId 由 TraceIdFilter 注入 MDC，logback pattern 自动带上。
        // 安全（FR-2.5 / NFR-2）：禁打 token 全文 / password，仅记录必要要素。
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = TrustedProxyUtil.resolveClientIp(request);

        // 给所有 /admin/* 响应加 CORS 头
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
        try {
            io.jsonwebtoken.Claims claims = jwtUtil.parse(token);
            uid = claims.get("uid");
            if (uid == null) {
                log.warn("admin 鉴权拒绝：token 缺少 uid claim, method={} path={} ip={}", method, path, ip);
                writeUnauthorized(response, ResultCode.TOKEN_INVALID.getCode(), "token 无效");
                return;
            }
            tokenDeviceId = claims.get("deviceId");
        } catch (Exception e) {
            // 具体失败分类由 JwtUtil.parse 内部已打 WARN，这里补充请求上下文
            log.warn("admin 鉴权拒绝：token 过期或无效, method={} path={} ip={}", method, path, ip);
            writeUnauthorized(response, ResultCode.TOKEN_INVALID.getCode(), "token 过期或无效");
            return;
        }

        // 2) 校验设备白名单——吊销/待授权设备的 token 立即失效
        String deviceId = request.getHeader("X-Device-Id");
        try {
            deviceService.verifyOnRequest(deviceId);
        } catch (BusinessException e) {
            // 设备相关 code 透传（2001 待授权 / 2002 吊销 / 401 未授权）
            log.warn("admin 鉴权拒绝：设备校验未通过 code={} reason={} deviceId={} method={} path={} ip={}",
                    e.getCode(), e.getMessage(), deviceId, method, path, ip);
            writeUnauthorized(response, e.getCode(), e.getMessage());
            return;
        }

        // 3) 防 token 借用：token.claim.deviceId 必须 == header.X-Device-Id
        // ——防止 A 设备的 token 被用到 B 设备（即使 B 是 approved 状态）
        // token 未带 deviceId claim（老 token 兼容）放行；header 没 deviceId 也放行
        // ——但两者都缺失会进入 #2 的设备白名单校验，自然被拒
        if (deviceId != null && !deviceId.isEmpty()
                && tokenDeviceId != null && !tokenDeviceId.toString().isEmpty()
                && !tokenDeviceId.toString().equals(deviceId)) {
            log.warn("admin 鉴权拒绝：token 与设备不匹配 tokenDeviceId={} headerDeviceId={} method={} path={} ip={}",
                    tokenDeviceId, deviceId, method, path, ip);
            writeUnauthorized(response, ResultCode.UNAUTHORIZED.getCode(), "token 与设备不匹配");
            return;
        }

        // 放行：INFO 含 uid / deviceId / path（FR-2.2）。INFO 级，量可控（admin 操作低频）。
        if (log.isInfoEnabled()) {
            log.info("admin 鉴权通过：uid={} deviceId={} method={} path={} ip={}", uid, deviceId, method, path, ip);
        }
        // 传递操作人给下游 controller，供业务日志记录"操作人"（FR-3.1/3.4/3.7）
        com.blog.common.web.AuthContext.set(request, uid, deviceId);
        chain.doFilter(request, response);
    }

    /**
     * 写 401 响应（code + message 透传）
     */
    private void writeUnauthorized(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
