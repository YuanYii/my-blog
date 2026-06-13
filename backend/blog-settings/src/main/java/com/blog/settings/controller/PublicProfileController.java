package com.blog.settings.controller;

import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 公开个人资料端点（前台使用）
 *
 * 2026-06-12 新增：原前台 index.vue / about.vue 走 /admin/settings/profile，
 * 但前台用的是 usePublicApi（不带 Authorization），所有访客永远拿到 401，
 * hero/about 页只能显示默认占位文本——admin 在后台改的 bio/avatar/location 完全不生效。
 *
 * 暴露策略：只返回安全字段（nickname/avatar/bio/location），
 * 不暴露 email、passwordHash、role 等敏感信息。
 *
 * 路由：GET /api/v1/public/profile
 */
@RestController
@RequestMapping("/public/profile")
@RequiredArgsConstructor
public class PublicProfileController {

    private final UserMapper userMapper;

    /** 单站点博客只有一个站主，固定取 id=1 */
    @GetMapping
    public Result<Map<String, Object>> get() {
        User user = userMapper.selectById(1L);
        Map<String, Object> data = new HashMap<>();
        if (user != null) {
            data.put("nickname", user.getNickname());
            data.put("avatar", user.getAvatar() != null ? user.getAvatar() : "");
            data.put("bio", user.getBio() != null ? user.getBio() : "");
            data.put("location", user.getLocation() != null ? user.getLocation() : "");
        }
        return Result.success(data);
    }
}
