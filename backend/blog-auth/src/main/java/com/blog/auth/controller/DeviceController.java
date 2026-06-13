package com.blog.auth.controller;

import com.blog.auth.entity.AdminDevice;
import com.blog.auth.service.DeviceService;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 设备管理（仅 admin 角色可用，靠前端的 admin-auth middleware 保证）
 */
@RestController
@RequestMapping("/admin/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /** 列出所有设备 */
    @GetMapping
    public Result<List<AdminDevice>> list() {
        return Result.success(deviceService.listAll());
    }

    /** 批准待授权设备 */
    @PutMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String approvedBy = body == null ? "" : body.getOrDefault("approvedBy", "");
        deviceService.approve(id, approvedBy);
        return Result.success();
    }

    /**
     * 吊销设备（pending/approved 都能吊销）
     * 防止自吊销：X-Device-Id header 是当前操作者自己的 deviceId
     * ——如果和被吊销设备的 deviceId 一致则拒（防误操作把自己踢出）
     */
    @PutMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id,
                               @RequestHeader(value = "X-Device-Id", required = false) String currentDeviceId) {
        deviceService.revoke(id, currentDeviceId);
        return Result.success();
    }

    /**
     * 2026-06-13 新增：物理删除设备记录
     * 跟 revoke（软删除改 status）的区别：直接 DELETE 数据库行，不留审计
     * 适用场景：pending 误授权 / 长期 revoked 不再需要 / 想换新设备 等
     *
     * 不阻止自删：用户明确要求"支持删除当前设备"——admin 可以删除自己当前正在用的设备
     * 后果：下一次 admin API 请求 X-Device-Id 校验发现设备记录不存在 → 401 → 自动跳登录页
     * 这是"删了就要重新登录"的预期行为（参见 DeviceService.delete 注释）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestHeader(value = "X-Device-Id", required = false) String currentDeviceId) {
        deviceService.delete(id, currentDeviceId);
        return Result.success();
    }
}
