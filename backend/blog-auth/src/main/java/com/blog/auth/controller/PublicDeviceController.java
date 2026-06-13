package com.blog.auth.controller;

import com.blog.auth.service.DeviceService;
import com.blog.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 公开设备校验端点
 *
 * 用途：前台 NavBar 据此决定是否给「未授权设备」隐藏后台管理入口图标。
 * 仅是 UI 隐藏，admin 接口本身仍由 AdminAuthFilter + DeviceService.verifyOnRequest 兜底。
 *
 * 不返回任何设备明细（id / name / ip / 创建时间），避免给未登录访客泄漏。
 *
 * 路由：GET /api/v1/public/device/check  (api_whitelist 注册为 public)
 */
@RestController
@RequestMapping("/public/device")
@RequiredArgsConstructor
public class PublicDeviceController {

    private final DeviceService deviceService;

    @GetMapping("/check")
    public Result<Map<String, Object>> check(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        boolean approved = deviceService.isApproved(deviceId);
        return Result.success(Collections.singletonMap("approved", approved));
    }
}
