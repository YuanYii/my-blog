package com.blog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域配置
 *
 * 修复记录（2026-06-07 v1）：
 * - 之前硬编码 allowedOriginPatterns("*")，prod yml 配置的 blog.cors.allowed-origins 不生效
 * - 改成读 ${blog.cors.allowed-origins}（逗号分隔，Spring 自动 split 成 String[]）
 * - dev 没配时 fallback "*"（保持原行为）
 * - prod 配了具体域名时用 allowedOrigins（更安全）
 *
 * 修复记录（2026-06-07 v2）：
 * - 项目用 Java 1.8，禁用 var（Java 10+）和 isBlank()（Java 11+）—— 上一版用了这两个会编译失败
 * - 改用 CorsRegistration 显式类型 + trim().isEmpty()
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许的来源列表（逗号分隔），prod 必须配置
     * 例如：https://yourname.com,https://www.yourname.com
     */
    @Value("${blog.cors.allowed-origins:}")
    private String allowedOriginsConfig;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = (allowedOriginsConfig == null || allowedOriginsConfig.trim().isEmpty())
                ? new String[]{"*"}
                : allowedOriginsConfig.split("\\s*,\\s*");

        CorsRegistration mapping = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        if ("*".equals(origins[0]) && origins.length == 1) {
            // dev fallback（保持原行为）
            mapping.allowedOriginPatterns("*");
        } else {
            // prod：明确允许的来源（更安全）
            mapping.allowedOrigins(origins);
        }
    }
}
