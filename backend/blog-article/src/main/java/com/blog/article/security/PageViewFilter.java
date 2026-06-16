package com.blog.article.security;

import com.blog.article.service.PageViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公开页访问统计 filter（v2.5.0）
 *
 * 拦截公开 GET 请求，写一行 page_view。
 * 与 AdminAuthFilter 互不冲突（PageViewFilter 只关心 public，admin 路径早被 AdminAuthFilter 拦了）。
 *
 * 拦截规则（白名单）：
 * - GET /api/v1/articles          （公开文章列表）
 * - GET /api/v1/articles/{slug}  （文章详情 → 写 article_id）
 * - GET /api/v1/articles/categories
 * - GET /api/v1/articles/tags
 * - GET /api/v1/articles/archives
 * - GET /api/v1/public/settings/{section}
 * - GET /api/v1/public/device/check
 *
 * 不拦截：
 * - admin 路径（被 AdminAuthFilter 拦下，不进这里）
 * - 写方法（POST/PUT/DELETE/PATCH）
 * - /health / swagger
 * - 带 Authorization 头的请求（已登录用户 → 不算访客；2026-06-16 调整）
 *
 * 性能：
 * - INSERT IGNORE 天然去重（同 visitor+path+day 一次）
 * - 即使 filter 抛异常也不影响业务（try/catch）
 * - 同步写库（filter 链不能 async），但每次只 1 条 INSERT，毫秒级
 */
@Slf4j
@Component
@Order(10)  // 优先级高于业务 filter，低于 admin auth
@RequiredArgsConstructor
public class PageViewFilter extends OncePerRequestFilter {

    private final PageViewService pageViewService;

    @Value("${server.servlet.context-path:/api/v1}")
    private String contextPath;

    /** 文章详情 slug → articleId 缓存（用 ConcurrentHashMap 简易缓存，避免每个详情请求都查 DB） */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>> SLUG_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final Pattern ARTICLE_DETAIL = Pattern.compile("/articles/([^/]+)$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只关心 GET
        if (!"GET".equalsIgnoreCase(request.getMethod())) return true;

        String path = request.getRequestURI();
        // 只看 public 路径
        if (!path.startsWith(contextPath)) return true;
        String sub = path.substring(contextPath.length());

        // 白名单
        return !(sub.equals("/articles")
                || sub.equals("/articles/archives")
                || sub.equals("/articles/categories")
                || sub.equals("/articles/tags")
                || sub.startsWith("/articles/")        // /articles/{slug}
                || sub.startsWith("/public/settings/")
                || sub.equals("/public/device/check")
                || sub.equals("/comments"));            // 公开评论列表（公开端点）
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 2026-06-16 调整：登录用户（带 Authorization 头）访问公开页**不算访客**
        // 原因：admin 改完文章后预览、admin 调试公开 API、admin 自己的"阅读"行为
        //       都不应污染 PV/UV 统计。
        //       当前项目（个人博客 MVP）只有 admin 一种登录用户 → 有 token = admin。
        //       未来若有"普通登录用户"功能，他们的访问也算"登录用户"（不算访客），
        //       这与"访客 = 匿名访问"的语义一致，仍合理。
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String path = request.getRequestURI();
            String sub = path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;

            Long articleId = null;
            // 解析文章详情 → articleId
            Matcher m = ARTICLE_DETAIL.matcher(sub);
            if (m.find() && !sub.contains("/articles/admin") && !sub.contains("/articles/id/")) {
                String slug = m.group(1);
                articleId = resolveArticleId(slug);
            }

            String visitor = request.getHeader("X-Visitor-Id");

            pageViewService.recordVisit(sub, articleId, visitor, request);
        } catch (Exception e) {
            // 统计失败绝不能影响业务响应
            log.debug("[PageView] 记录失败：{}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    /**
     * 用本地缓存避免每个详情请求都查 DB
     * 缓存 key = slug，value = { articleId, expireAt }
     */
    private Long resolveArticleId(String slug) {
        long now = System.currentTimeMillis();
        java.util.concurrent.ConcurrentHashMap<String, Long> bucket =
                SLUG_CACHE.computeIfAbsent(slug, k -> new java.util.concurrent.ConcurrentHashMap<>());
        Long cached = bucket.get("id");
        Long expire = bucket.get("exp");
        if (cached != null && expire != null && expire > now) return cached;

        // miss 或过期：查 DB
        try {
            Long id = pageViewService.findArticleIdBySlug(slug);
            bucket.put("id", id);
            bucket.put("exp", now + CACHE_TTL_MS);
            return id;
        } catch (Exception e) {
            return null;
        }
    }
}
