package com.blog.controller;

import com.blog.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 / 模块状态
 *
 * 2026-06-08 @Tag 改名：name="public"（按内部路由分组：公开 API）
 */
@Tag(name = "public", description = "服务连通性 + 模块加载状态")
@RestController
@RequestMapping("/health")
public class HelloController {

    @Operation(summary = "健康检查", description = "返回服务运行状态 + 模块清单 + 当前时间")
    @GetMapping
    public Result<Map<String, String>> hello() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "blog-backend");
        data.put("version", "0.1.0");
        data.put("modules", "common, auth, article, comment, settings");
        data.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return Result.success(data);
    }
}
