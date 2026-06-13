-- =============================================================
-- 2026-06-12 公开端点：设备授权状态查询
-- =============================================================
-- 新增 PublicDeviceController：GET /api/v1/public/device/check
-- 用途：前台 NavBar 据此决定是否给「未授权设备」隐藏后台管理入口图标。
-- 仅是 UI 隐藏，admin 接口本身仍由 AdminAuthFilter + DeviceService.verifyOnRequest 兜底。
--
-- 必须显式在 api_whitelist 注册为 public——否则 AdminAuthFilter 的
-- shouldNotFilter 会走"未命中则放行"的兜底分支（依赖默认行为不稳）。
--
-- 注意：本 migration 幂等——重复执行被 UNIQUE KEY (path_prefix, type) 拦掉，
-- 用 INSERT IGNORE 兜底。

INSERT IGNORE INTO `api_whitelist` (`path_prefix`, `type`, `enabled`, `description`, `created_at`, `updated_at`) VALUES
  ('/api/v1/public/device', 'public', 1, '前台公开设备授权状态查询（仅返回 approved bool）', NOW(), NOW());

-- 刷新生效：
-- 1) ApiWhitelistService 启动时 + 每 5 min 定时刷新；
-- 2) 也可调 POST /api/v1/admin/api-whitelist/refresh 立即刷。
