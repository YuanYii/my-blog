package com.blog.settings.controller;

import com.blog.common.Result;
import com.blog.settings.service.SiteSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 公开设置读端点（前台使用）
 *
 * 2026-06-08 新增：
 *  - 前台 index.vue 拉站点信息（blog）+ 社交账号（social）走这里
 *  - 不走 AdminAuthFilter（在 api_whitelist 注册为 public）
 *  - section 白名单：SiteSettingsService.PUBLIC_SECTIONS（blog/social）
 *  - preferences/theme/advanced/admin-only，不通过此端点暴露
 *
 * 路由：GET /api/v1/public/settings/{section}
 */
@RestController
@RequestMapping("/public/settings")
@RequiredArgsConstructor
public class PublicSettingsController {

    private final SiteSettingsService siteSettingsService;

    @GetMapping("/{section}")
    public Result<Map<String, Object>> get(@PathVariable String section) {
        if (!SiteSettingsService.PUBLIC_SECTIONS.contains(section)) {
            // 非公开 section → 返空 + 200（不返 404，避免泄漏 section 是否存在）
            return Result.success(Collections.emptyMap());
        }
        Map<String, Object> data = siteSettingsService.get(section);
        if (data == null) data = Collections.emptyMap();
        return Result.success(data);
    }
}
