package com.blog.auth.controller;

import com.blog.auth.entity.IpBan;
import com.blog.auth.service.IpBanService;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IP 封禁管理（仅 admin 角色可用）
 *
 * path 含 "/admin/" → AdminAuthFilter.isAdminSubpath 自动要求登录鉴权，
 * 同时 api_whitelist 表里也有 ('/admin/', 'admin', ...) 的最长前缀兜底，双层防护。
 */
@RestController
@RequestMapping("/admin/ip-bans")
@RequiredArgsConstructor
public class IpBanController {

    private final IpBanService ipBanService;

    /** 封禁列表。status=active 只看当前生效的；不传或传其他值返回全部历史（按封禁时间倒序） */
    @GetMapping
    public Result<List<IpBan>> list(@RequestParam(defaultValue = "all") String status) {
        return Result.success(ipBanService.list("active".equals(status)));
    }

    /** 手动解封：立即生效（删 Redis 标记），不用等封禁到期 */
    @PutMapping("/{id}/unban")
    public Result<Void> unban(@PathVariable Long id) {
        ipBanService.unban(id);
        return Result.success();
    }
}
