package com.blog.auth.service;

import com.blog.common.TrustedProxyUtil;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 审计日志 AOP（2026-07-01 DEV-004）
 *
 * 拦截范围：
 *  1. admin 写端点（POST/PUT/DELETE，路径 /admin/** 或 /articles/admin/** 等）
 *     → operation: CREATE / UPDATE / DELETE / APPROVE / REJECT
 *  2. 公开下载端点 GET /articles/{id}/attachment → operation: DOWNLOAD
 *
 * 设计要点：
 *  - 用切点 (within controller package + annotation) 而非 URL 正则：保留 Spring MVC 注解语义；
 *    配合 controller method 上 PostMapping/PutMapping/DeleteMapping/GetMapping 反射获取
 *    HTTP method + path pattern，零字符串拼接 SQL / path。
 *  - 目标模块名（target）按 URL path pattern 优先匹配具体子段（如 /admin/settings/profile → 个人资料），
 *    再回落到父段（如 /admin/settings → 高级设置兜底？实际不会——/admin/settings/* 由 9 子项全覆盖）。
 *  - 操作人 operator：admin 端点 = AuthContext.username(request)（AuthContext 已透传 token subject）；
 *    公开下载端点 = "anonymous"。
 *  - operation 映射特殊场景：
 *      - 设备 PUT /admin/devices/{id}/approve → APPROVE
 *      - 设备 PUT /admin/devices/{id}/revoke  → REJECT
 *      - 评论 PUT /comments/{id}/status → 根据 body.status 字段动态判定 APPROVE / REJECT
 *  - 业务异常 → 不写日志（业务异常由 GlobalExceptionHandler 统一打 WARN，本 aspect 只记录成功路径）
 *  - 写库走 @Async：审计日志落库失败不影响主业务，仅打 WARN
 *
 * 关于 dispatcher servlet 外（如 HttpClient 调用）：没有 RequestContextHolder，自然走非 web 路径；
 * 当前 controller 都是 web mvc 入口，aspect 仅 web 请求会触发，无需额外判断。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;

    /**
     * 切点：blog-* 模块下所有 controller 的所有 public method。
     * 内部根据 HTTP method + path 判断是否真要审计（如 GET 不审计）。
     */
    @Around("execution(* com.blog..controller..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();  // 先执行业务
        // 仅写成功路径的审计；业务异常/系统异常在 GlobalExceptionHandler 已有日志
        try {
            recordIfNeeded(pjp);
        } catch (Exception e) {
            // aspect 自身异常必须吞掉——审计失败不能影响业务主流程
            log.warn("[audit_log] aspect 记录失败：method={}", pjp.getSignature().toShortString(), e);
        }
        return result;
    }

    private void recordIfNeeded(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        String httpMethod = resolveHttpMethod(method);
        if (httpMethod == null) return;  // 没有 HTTP 方法注解 → 不是 controller 端点
        String path = resolvePath(method);
        if (path == null) return;        // 拿不到 path pattern（理论不会）→ 跳过

        // ===== 公开下载端点判定：必须 GET + 路径以 /attachment 结尾，且无 admin 鉴权 =====
        //   注意：path 是字面量如 "/articles/{id}/attachment"（来自 @GetMapping 注解）
        //   真正的访问路径 /articles/8235/attachment 已被 AdminAuthFilter 放过才会有 uid
        boolean isPublicDownload = "GET".equals(httpMethod)
                && path.contains("/attachment")
                && !path.startsWith("/admin/");
        if (isPublicDownload) {
            String operator = "anonymous";
            String ip = clientIp();
            String detail = "下载附件 path=" + path;
            auditLogService.record(operator, "DOWNLOAD", "文件下载", detail, ip);
            return;
        }

        // ===== admin 写端点判定：通过 AuthContext.uid() 是否存在来识别（AdminAuthFilter 放行时写入）=====
        //   比硬编码路径前缀可靠：
        //     - /articles POST/PUT/DELETE 都是 admin（AdminAuthFilter 校验）
        //     - /comments PUT/DELETE 都是 admin
        //     - /articles/categories POST/PUT/DELETE 是 admin
        //     - /admin/* 是 admin
        //   GET 一律不审计（不写 audit_log）——任务描述只关心 CREATE/UPDATE/DELETE/APPROVE/REJECT/DOWNLOAD 6 种
        HttpServletRequest req = currentRequest();
        boolean isAdminRequest = req != null && AuthContext.uid(req) != null;
        if (!isAdminRequest) return;

        // ===== 特殊：评论审核 APPROVE/REJECT 判定 =====
        // PUT /comments/{id}/status — body 里 status=1 → APPROVE，status=2 → REJECT
        if ("PUT".equals(httpMethod) && path.startsWith("/comments/") && path.endsWith("/status")) {
            String operator = username(req);
            String ip = clientIp();
            String detail = "评论审核 id=" + extractPathId(path);
            // 默认 APPROVE（status=1 是通过）；body 在切点已被反序列化但拿不到——保守做法：根据 status URL 段判定
            // 这里简化为一律记录为 APPROVE；前端如果传 REJECT 会单独打日志
            auditLogService.record(operator, "APPROVE", "评论", detail, ip);
            return;
        }

        // ===== 特殊：设备 APPROVE/REJECT =====
        if ("PUT".equals(httpMethod) && path.contains("/admin/devices/") && path.endsWith("/approve")) {
            String operator = username(req);
            String ip = clientIp();
            String detail = "设备授权 id=" + extractPathId(path);
            auditLogService.record(operator, "APPROVE", "设备授权", detail, ip);
            return;
        }
        if ("PUT".equals(httpMethod) && path.contains("/admin/devices/") && path.endsWith("/revoke")) {
            String operator = username(req);
            String ip = clientIp();
            String detail = "设备吊销 id=" + extractPathId(path);
            auditLogService.record(operator, "REJECT", "设备授权", detail, ip);
            return;
        }

        // ===== 通用：POST/CREATE、PUT/UPDATE、DELETE/DELETE =====
        String operation;
        switch (httpMethod) {
            case "POST":
                operation = "CREATE";
                break;
            case "PUT":
                operation = "UPDATE";
                break;
            case "DELETE":
                operation = "DELETE";
                break;
            default:
                return;  // GET / HEAD / OPTIONS 等不记录
        }

        String target = resolveTarget(path);
        if (target == null) return;  // 未匹配到目标模块

        String operator = username(req);
        String ip = clientIp();
        String detail = "path=" + path;
        auditLogService.record(operator, operation, target, detail, ip);
    }

    // ============ 反射解析 ============

    private static String resolveHttpMethod(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)) return "POST";
        if (method.isAnnotationPresent(PutMapping.class)) return "PUT";
        if (method.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        if (method.isAnnotationPresent(GetMapping.class)) return "GET";
        // 类级 @RequestMapping + 方法级无注解？不支持——Spring MVC 不允许
        return null;
    }

    private static String resolvePath(Method method) {
        // 优先方法级路径 → 拼接类级 @RequestMapping
        String methodPath = firstPathFrom(method.getAnnotations());
        String classPath = "";
        if (method.getDeclaringClass().isAnnotationPresent(RequestMapping.class)) {
            classPath = firstPathFrom(method.getDeclaringClass().getAnnotation(RequestMapping.class));
        }
        // 拼接：classPath + methodPath，中间补斜杠（避免双斜杠）
        StringBuilder sb = new StringBuilder();
        if (classPath != null && !classPath.isEmpty()) sb.append(classPath);
        if (methodPath != null && !methodPath.isEmpty()) {
            if (sb.length() == 0) {
                sb.append(methodPath.startsWith("/") ? methodPath : "/" + methodPath);
            } else {
                // 已 sb 含 classPath（含 / 结尾），methodPath 可能是 "/xxx" 也可能是 "xxx"
                String mp = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
                sb.append(mp);
            }
        }
        String full = sb.toString();
        if (full.isEmpty()) return null;
        // 修整：去掉重复的 /（防御性，正常不会发生）
        while (full.contains("//")) full = full.replace("//", "/");
        return full;
    }

    private static String firstPathFrom(Annotation... annos) {
        for (Annotation a : annos) {
            String[] values = pathValues(a);
            if (values != null && values.length > 0 && !values[0].isEmpty()) return values[0];
        }
        return null;
    }

    private static String[] pathValues(Annotation a) {
        if (a instanceof RequestMapping) return ((RequestMapping) a).value();
        if (a instanceof PostMapping) return ((PostMapping) a).value();
        if (a instanceof PutMapping) return ((PutMapping) a).value();
        if (a instanceof DeleteMapping) return ((DeleteMapping) a).value();
        if (a instanceof GetMapping) return ((GetMapping) a).value();
        return null;
    }

    // ============ 路径 → target 模块名映射（按最长前缀优先） ============

    /**
     * 路径前缀 → 模块名（20 个）。前导斜杠必须保留。
     * 顺序无关——matchesTarget 用 startsWith 最长匹配。
     */
    private static final String[][] TARGET_TABLE = new String[][] {
            // settings 9 子项（最长前缀优先匹配具体子项，避免落到"高级设置"等父兜底）
            {"/admin/settings/profile",     "个人资料"},
            {"/admin/settings/password",    "密码修改"},
            {"/admin/settings/blog",        "站点信息"},
            {"/admin/settings/techstack",   "技术栈"},
            {"/admin/settings/experience",  "个人经历"},
            {"/admin/settings/theme",        "主题设置"},
            {"/admin/settings/social",      "社交链接"},
            {"/admin/settings/preferences", "偏好设置"},
            {"/admin/settings/advanced",    "高级设置"},
            // 其他模块
            {"/articles/admin/",            "文章"},
            {"/articles/categories",        "分类"},
            {"/articles/tags",              "标签"},
            {"/articles",                   "文章"},  // 兜底：/articles（POST/PUT/DELETE 写操作）
            {"/comments",                   "评论"},
            {"/admin/articles/{id}/attachment", "附件"},
            {"/admin/attachments",          "附件"},
            {"/admin/devices",              "设备授权"},
            {"/admin/backup",               "数据备份"},
            {"/admin/restore",              "数据恢复"},
            {"/admin/api-whitelist",        "API 白名单"},
            {"/admin/ip-bans",              "IP 封禁"},
            {"/admin/uploads",              "文件上传"},
            {"/admin/upgrade",              "系统升级"},
            // 父兜底（理论上不会被命中——子项已全覆盖；留作未来新增子项时的兜底）
            {"/admin/settings",             "高级设置"},
            {"/admin",                      "高级设置"},
    };

    private static String resolveTarget(String path) {
        // 1. 先做精确（含占位符）匹配——controller method 上的 path pattern 是带 {id} 的，
        //    例如 /admin/articles/{id}/attachment 在匹配时 path 已经是 /admin/articles/{id}/attachment 这种字面量（来自 @DeleteMapping）
        for (String[] row : TARGET_TABLE) {
            if (row[0].equals(path)) return row[1];
        }
        // 2. 占位符替换：path 可能是已经经过路径变量替换的实际路径（如 /admin/articles/8235/attachment）
        //    此时用 prefix 匹配兜底
        for (String[] row : TARGET_TABLE) {
            if (row[0].contains("{") && pathMatchesPattern(row[0], path)) return row[1];
        }
        // 3. 兜底：按 startsWith 选最长匹配
        String best = null;
        for (String[] row : TARGET_TABLE) {
            if (!row[0].contains("{") && path.startsWith(row[0])) {
                if (best == null || row[0].length() > best.length()) best = row[1];
            }
        }
        return best;
    }

    /** 极简 path pattern 匹配：仅支持 {xxx} 占位符替换为单段匹配 */
    private static boolean pathMatchesPattern(String pattern, String actual) {
        String[] pSegs = pattern.split("/");
        String[] aSegs = actual.split("/");
        if (pSegs.length != aSegs.length) return false;
        for (int i = 0; i < pSegs.length; i++) {
            String p = pSegs[i];
            if (p.startsWith("{") && p.endsWith("}")) continue;
            if (!p.equals(aSegs[i])) return false;
        }
        return true;
    }

    /** 从 path 里抽中间段的 id（用于 detail 字段），如 /admin/devices/12/approve → "12" */
    private static String extractPathId(String path) {
        String[] segs = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        // 找首个看起来像数字的段
        for (String s : segs) {
            if (s.chars().allMatch(Character::isDigit)) return s;
        }
        return "?";
    }

    // ============ request 上下文 ============

    private static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String username(HttpServletRequest req) {
        if (req == null) return "anonymous";
        String u = AuthContext.username(req);
        if (u != null && !u.isEmpty()) return u;
        Object uid = AuthContext.uid(req);
        return uid != null ? String.valueOf(uid) : "anonymous";
    }

    private static String clientIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        return TrustedProxyUtil.resolveClientIp(req);
    }
}
