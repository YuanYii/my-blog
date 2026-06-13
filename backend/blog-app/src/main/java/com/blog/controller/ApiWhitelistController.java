package com.blog.controller;

import com.blog.auth.entity.ApiWhitelist;
import com.blog.auth.service.ApiWhitelistService;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API 白名单管理（仅 admin 角色可用）
 * 路径：/api/v1/admin/api-whitelist
 */
@RestController
@RequestMapping("/admin/api-whitelist")
@RequiredArgsConstructor
public class ApiWhitelistController {

    private final ApiWhitelistService service;

    /** 列出所有白名单（含禁用） */
    @GetMapping
    public Result<List<ApiWhitelist>> list() {
        return Result.success(service.listAll());
    }

    /** 新增白名单（保存后自动 refreshCache） */
    @PostMapping
    public Result<Void> add(@RequestBody ApiWhitelist w) {
        service.add(w);
        service.refreshCache();
        return Result.success();
    }

    /** 更新白名单（保存后自动 refreshCache） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ApiWhitelist w) {
        service.update(id, w);
        service.refreshCache();
        return Result.success();
    }

    /** 删除白名单（保存后自动 refreshCache） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        service.refreshCache();
        return Result.success();
    }

    /** 手动触发缓存刷新（保底机制） */
    @PostMapping("/refresh")
    public Result<Void> refresh() {
        service.refreshCache();
        return Result.success();
    }
}
