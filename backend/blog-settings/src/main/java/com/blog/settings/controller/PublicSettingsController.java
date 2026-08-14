package com.blog.settings.controller;

import com.blog.common.Result;
import com.blog.settings.service.AdvancedSettingsAccessor;
import com.blog.settings.service.SiteSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * 2026-06-28 OPT-001/002（autopush）：新增 /site-flags 端点，仅暴露 advanced 段中
 * 前台需要做 UI 显隐的开关（enableRss / enableSearch）。其它敏感开关
 * （enableCache / enableCommentModeration）不通过此端点暴露。
 *
 * 路由：GET /api/v1/public/settings/{section}
 *       GET /api/v1/public/site-flags
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicSettingsController {

    private final SiteSettingsService siteSettingsService;
    private final AdvancedSettingsAccessor advancedSettings;

    @GetMapping("/settings/{section}")
    public Result<Map<String, Object>> get(@PathVariable String section) {
        if (!SiteSettingsService.PUBLIC_SECTIONS.contains(section)) {
            // 非公开 section → 返空 + 200（不返 404，避免泄漏 section 是否存在）
            return Result.success(Collections.emptyMap());
        }
        Map<String, Object> data = siteSettingsService.get(section);
        if (data == null) data = Collections.emptyMap();
        return Result.success(data);
    }

    /**
     * 公开的能力开关（白名单子集）：只回前台需要做 UI 显隐的开关。
     * 不暴露 enableCache（运维排障开关）、enableCommentModeration（内部策略）。
     */
    @GetMapping("/site-flags")
    public Result<Map<String, Object>> siteFlags() {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("enableRss", advancedSettings.rssEnabled());
        flags.put("enableSearch", advancedSettings.searchEnabled());
        return Result.success(flags);
    }

    /**
     * 公开的服务器环境信息：用于本地局域网二维码识别与跨端流转。
     */
    @GetMapping("/server-info")
    public Result<Map<String, Object>> serverInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("lanIp", com.blog.common.NetworkUtil.getLocalLanIp());
        return Result.success(info);
    }
}
