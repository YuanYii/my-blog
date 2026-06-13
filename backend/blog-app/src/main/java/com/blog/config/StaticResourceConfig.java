package com.blog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射：把 /uploads/** 映射到本地磁盘 upload 目录
 *
 * 2026-06-13 修复（BUG-052）：
 *   原 /uploads/* 路径完全 404（后端没配 static resource handler，
 *   前端也没 dev proxy）—— admin 上传的图片、头像、封面图全部看不到。
 *
 * 修复：和 CorsConfig 一样加一个 WebMvcConfigurer，
 *   - dev profile：写到 ${user.dir}/tmp/blog-uploads（application-dev.yml 配置）
 *   - prod profile：写到 /data/uploads（application-prod.yml 配置，nginx 也会反向代理）
 *
 * 注：双保险。生产环境 nginx 用 location /uploads/ 静态 serve（不走 Spring），
 *   本配置主要服务 dev / 单 jar 部署场景。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Value("${blog.upload.local.dir:/root/blog-uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // file: 前缀 + 路径：Spring 会 serve 该目录下的所有静态文件
        // 注意路径末尾必须以 / 结尾，否则会被当作 file path
        String location = "file:" + (uploadDir.endsWith("/") ? uploadDir : uploadDir + "/");
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
