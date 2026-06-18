package com.blog.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.auth.entity.AdminDevice;
import com.blog.auth.mapper.AdminDeviceMapper;
import com.blog.common.ResultCode;
import com.blog.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备白名单服务
 *
 * 登录流程（极简版）：
 * 1. 客户端带 X-Device-Id 调 /auth/login
 * 2. 密码对 → 查 admin_device 表
 *    - 不存在：建 pending 记录 → 抛 DEVICE_PENDING
 *    - pending：抛 DEVICE_PENDING
 *    - revoked：抛 DEVICE_REVOKED
 *    - approved：更新 last_seen_at → 通过
 * 3. 客户端不传 device_id（旧客户端兼容 / 升级过渡期）：trust-migrate，自动建 approved
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final AdminDeviceMapper deviceMapper;

    /** 状态枚举 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REVOKED = "revoked";

    /**
     * 登录时校验设备白名单
     *
     * @param deviceId   客户端 X-Device-Id
     * @param deviceName 客户端解析 UA 得到的设备名
     * @param ip         客户端 IP
     * @param userAgent  客户端 UA
     * @return 通过校验的 AdminDevice（status=approved）
     * @throws BusinessException DEVICE_PENDING / DEVICE_REVOKED
     */
    public AdminDevice verifyOnLogin(String deviceId, String deviceName, String ip, String userAgent) {
        // 兼容过渡：旧客户端不传 device_id → trust-migrate 通道
        // 安全加固（2026-06-07）：trust-migrate 限内网 IP，
        //   防止外网任意请求不传 deviceId 就绕过白名单拿 token
        if (deviceId == null || deviceId.isEmpty()) {
            if (!isInternalIp(ip)) {
                // 外网请求不带 deviceId → 视同"未授权设备"返回 DEVICE_PENDING
                // 引导用户用新前端（自动生成 deviceId）或联系 admin 加白名单
                throw new BusinessException(ResultCode.DEVICE_PENDING);
            }
            return trustMigrate(deviceName, ip, userAgent);
        }

        QueryWrapper<AdminDevice> qw = new QueryWrapper<>();
        qw.eq("device_id", deviceId);
        AdminDevice device = deviceMapper.selectOne(qw);

        if (device == null) {
            // 全新设备：默认 pending，等后台批准
            device = new AdminDevice();
            device.setDeviceId(deviceId);
            device.setDeviceName(safe(deviceName));
            device.setUserAgent(safe(userAgent));
            device.setIp(safe(ip));
            device.setLastSeenAt(LocalDateTime.now());

            // Bootstrap: admin_device 表为空时，第一个成功通过密码校验的设备自动 approved
            // Why: 否则首次部署 / 误清空表 后会陷入死锁——没有任何 approved 设备就无法登录后台,
            //      也就无法去批准其他设备。等同于鸡生蛋问题。
            // 安全：能走到这里说明密码已经校验通过（AuthController 在调本方法前先验密码），
            //      bootstrap 仅是"省去 admin 自批准自己"的一次性开关，不绕过密码。
            if (isDeviceTableEmpty()) {
                device.setStatus(STATUS_APPROVED);
                device.setApprovedAt(LocalDateTime.now());
                device.setApprovedBy("system-bootstrap");
                deviceMapper.insert(device);
                // FR-2.6：设备注册（bootstrap 自动授权）INFO
                log.info("设备注册：bootstrap 首设备自动授权 deviceId={} ip={}", deviceId, ip);
                return device;
            }

            device.setStatus(STATUS_PENDING);
            deviceMapper.insert(device);
            // FR-2.6/2.7：新设备登记为 pending（注册）+ 因 pending 被拒
            log.info("设备注册：新设备登记为 pending deviceId={} ip={}", deviceId, ip);
            log.warn("设备登录被拒：状态=pending（待授权）deviceId={} ip={}", deviceId, ip);
            throw new BusinessException(ResultCode.DEVICE_PENDING);
        }

        // 更新活跃时间（pending 也算"在线"，方便看到申请时间）
        device.setLastSeenAt(LocalDateTime.now());
        if (deviceName != null && !deviceName.isEmpty()) device.setDeviceName(deviceName);
        if (ip != null && !ip.isEmpty()) device.setIp(ip);
        if (userAgent != null && !userAgent.isEmpty()) device.setUserAgent(userAgent);
        deviceMapper.updateById(device);

        switch (device.getStatus()) {
            case STATUS_APPROVED:
                return device;
            case STATUS_REVOKED:
                // FR-2.7：拒绝访问 WARN（含 deviceId 状态）
                log.warn("设备登录被拒：状态=revoked（已吊销）deviceId={} ip={}", deviceId, ip);
                throw new BusinessException(ResultCode.DEVICE_REVOKED);
            case STATUS_PENDING:
            default:
                log.warn("设备登录被拒：状态=pending（待授权）deviceId={} ip={}", deviceId, ip);
                throw new BusinessException(ResultCode.DEVICE_PENDING);
        }
    }

    /**
     * 判断 IP 是否内网（loopback / 私有网段）
     * 用于限制 trust-migrate 通道：只允许开发/内网环境使用
     */
    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String s = ip.trim();
        // IPv6 loopback
        if (s.equals("0:0:0:0:0:0:0:1") || s.equals("::1") || s.equalsIgnoreCase("localhost")) return true;
        // IPv6 link-local fe80::/10
        if (s.toLowerCase().startsWith("fe80:")) return true;
        // IPv6 unique local fc00::/7
        if (s.toLowerCase().startsWith("fc") || s.toLowerCase().startsWith("fd")) return true;
        // IPv4 loopback
        if (s.startsWith("127.")) return true;
        // IPv4 私有网段
        if (s.startsWith("10.")) return true;
        if (s.startsWith("192.168.")) return true;
        // 172.16.0.0 ~ 172.31.255.255
        if (s.startsWith("172.")) {
            try {
                int second = Integer.parseInt(s.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception e) { return false; }
        }
        return false;
    }

    /**
     * trust-migrate：旧客户端没传 device_id 时，自动建一条 approved 记录
     * 一次性迁移通道——只在 DB 还没有 system 信任设备时建一次，
     * 后续 trust-migrate 请求复用同一条记录（不重复建）
     */
    private AdminDevice trustMigrate(String deviceName, String ip, String userAgent) {
        // 1. 复用已存在的 trust-migrate 设备
        // 用 approved_by='system' 作为"系统迁移通道"标识；用 status=approved 确保只复用通过的
        QueryWrapper<AdminDevice> qw = new QueryWrapper<>();
        qw.eq("status", STATUS_APPROVED).eq("approved_by", "system");
        qw.last("LIMIT 1");
        AdminDevice existing = deviceMapper.selectOne(qw);
        if (existing != null) {
            // 复用：只更新活跃时间和元数据（不重复建，避免"legacy-xxx" 越积越多）
            existing.setLastSeenAt(LocalDateTime.now());
            if (ip != null && !ip.isEmpty()) existing.setIp(ip);
            if (userAgent != null && !userAgent.isEmpty()) existing.setUserAgent(userAgent);
            deviceMapper.updateById(existing);
            return existing;
        }
        // 2. 首次：建新 trust-migrate 记录
        AdminDevice device = new AdminDevice();
        device.setDeviceId("legacy-" + System.currentTimeMillis());
        device.setDeviceName(safe(deviceName) + " [trust-migrate]");
        device.setUserAgent(safe(userAgent));
        device.setIp(safe(ip));
        device.setStatus(STATUS_APPROVED);
        device.setApprovedAt(LocalDateTime.now());
        device.setApprovedBy("system");
        device.setLastSeenAt(LocalDateTime.now());
        deviceMapper.insert(device);
        return device;
    }

    /** 列出所有设备（按 last_seen_at desc） */
    public List<AdminDevice> listAll() {
        QueryWrapper<AdminDevice> qw = new QueryWrapper<>();
        qw.orderByDesc("last_seen_at");
        return deviceMapper.selectList(qw);
    }

    /** 批准待授权设备 */
    public void approve(Long id, String approvedByDeviceId) {
        AdminDevice device = deviceMapper.selectById(id);
        if (device == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (STATUS_APPROVED.equals(device.getStatus())) return;  // 幂等
        device.setStatus(STATUS_APPROVED);
        device.setApprovedAt(LocalDateTime.now());
        device.setApprovedBy(safe(approvedByDeviceId));
        deviceMapper.updateById(device);
        // FR-2.6：设备授权 INFO（操作者 deviceId）
        log.info("设备授权：deviceId={} approvedBy={}", device.getDeviceId(), safe(approvedByDeviceId));
    }

    /**
     * 吊销设备（pending/approved 都能吊销）
     *
     * @param id                 要吊销的设备 id
     * @param currentDeviceId   当前操作者自己的 deviceId（X-Device-Id header）
     *                           防止 admin 误操作把自己踢出系统
     * @throws BusinessException 404（设备不存在）/ 2003（自吊销）
     */
    public void revoke(Long id, String currentDeviceId) {
        AdminDevice device = deviceMapper.selectById(id);
        if (device == null) throw new BusinessException(ResultCode.NOT_FOUND);
        // 安全（2026-06-07）：禁止自吊销——admin 不能把自己踢出系统
        // 双层防护之一（前端 UI 隐藏自己的吊销按钮 + 后端拒绝自吊销）
        if (currentDeviceId != null && !currentDeviceId.isEmpty()
                && currentDeviceId.equals(device.getDeviceId())) {
            throw new BusinessException(ResultCode.DEVICE_SELF_REVOKE_FORBIDDEN);
        }
        device.setStatus(STATUS_REVOKED);
        deviceMapper.updateById(device);
        // FR-2.6：设备吊销 INFO（被吊销 deviceId + 操作者 deviceId）
        log.info("设备吊销：deviceId={} operatorDeviceId={}", device.getDeviceId(), safe(currentDeviceId));
    }

    /**
     * 物理删除设备记录
     *
     * 跟 revoke 的区别：revoke 是软删除（改 status=revoked，留底审计），
     * delete 是真抹掉记录（pending 误授权 / 长期 revoked 不再需要 / 想换新设备 等场景）
     *
     * 2026-06-16 修正（BUG-077）：之前"不阻止自删"的策略被用户推翻。
     * 现在禁止自删——与 revoke 保持完全一致的安全模型（双层防护：UI 禁用 + 后端兜底）。
     * 后果：用户想解绑当前设备时，必须先在另一台已授权设备上吊销/删除本机。
     *
     * @param id               要删除的设备 id
     * @param currentDeviceId  当前操作者自己的 deviceId（X-Device-Id header）
     *                         与被删设备 deviceId 一致则拒绝（防误操作把自己踢出）
     * @throws BusinessException 404（设备不存在）/ 2003（自删——复用吊销的错误码）
     */
    public void delete(Long id, String currentDeviceId) {
        AdminDevice device = deviceMapper.selectById(id);
        if (device == null) throw new BusinessException(ResultCode.NOT_FOUND);
        // 安全（2026-06-16）：禁止自删——admin 不能把当前正在用的设备记录抹掉
        // 双层防护：前端 UI 禁用当前设备的删除按钮（devices.vue :disabled）+ 后端拒绝
        if (currentDeviceId != null && !currentDeviceId.isEmpty()
                && currentDeviceId.equals(device.getDeviceId())) {
            throw new BusinessException(ResultCode.DEVICE_SELF_REVOKE_FORBIDDEN);
        }
        deviceMapper.deleteById(id);
        // FR-2.6：设备物理删除 INFO
        log.info("设备删除：deviceId={} operatorDeviceId={}", device.getDeviceId(), safe(currentDeviceId));
    }

    /** 触摸活跃时间（每次 admin API 调用时更新，可选） */
    public void touch(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;
        AdminDevice device = deviceMapper.selectOne(new QueryWrapper<AdminDevice>().eq("device_id", deviceId));
        if (device == null || !STATUS_APPROVED.equals(device.getStatus())) return;
        device.setLastSeenAt(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    /**
     * 无副作用判断设备是否已被授权（status=approved）
     * 供 PublicDeviceController 使用：前台 NavBar 据此决定是否给「未授权设备」
     * 隐藏后台管理入口图标（仅是 UI 隐藏，真正访问控制仍由 AdminAuthFilter 兜底）
     *
     * 不抛异常、不更新 last_seen_at：纯查询，避免给未登录访客的探测请求产生写流量
     */
    public boolean isApproved(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return false;
        AdminDevice device = deviceMapper.selectOne(
                new QueryWrapper<AdminDevice>().eq("device_id", deviceId));
        return device != null && STATUS_APPROVED.equals(device.getStatus());
    }

    /**
     * admin API 请求时实时校验设备白名单
     * 配合 DeviceAuthInterceptor 实现"吊销即踢出"：
     * 吊销后该设备的 token 立即失效（被 status=revoked 拦截）
     *
     * @param deviceId X-Device-Id header
     * @throws BusinessException 401 / 2001 / 2002
     */
    public AdminDevice verifyOnRequest(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        QueryWrapper<AdminDevice> qw = new QueryWrapper<>();
        qw.eq("device_id", deviceId);
        AdminDevice device = deviceMapper.selectOne(qw);
        if (device == null) {
            // FR-2.7：请求侧设备未登记 —— WARN（含 deviceId）
            log.warn("设备请求校验被拒：deviceId 未登记 deviceId={}", deviceId);
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        switch (device.getStatus()) {
            case STATUS_APPROVED:
                // 顺便更新 last_seen_at（节省单独 touch 调用）
                device.setLastSeenAt(LocalDateTime.now());
                deviceMapper.updateById(device);
                return device;
            case STATUS_REVOKED:
                // FR-2.7：拒绝访问 WARN（含 deviceId 状态）—— 实现"吊销即踢出"
                log.warn("设备请求校验被拒：状态=revoked（已吊销）deviceId={}", deviceId);
                throw new BusinessException(ResultCode.DEVICE_REVOKED);
            case STATUS_PENDING:
            default:
                log.warn("设备请求校验被拒：状态=pending（待授权）deviceId={}", deviceId);
                throw new BusinessException(ResultCode.DEVICE_PENDING);
        }
    }

    private String safe(String s) { return s == null ? "" : s; }

    /**
     * admin_device 表是否完全为空（无任何记录，含 pending/revoked）
     * 用于 bootstrap 通道判断：首次部署或误清空时让第一个登录设备自动 approved。
     * 比 selectCount(*) 略快（命中第一行即返回），SQLite/MySQL 都兼容。
     */
    private boolean isDeviceTableEmpty() {
        QueryWrapper<AdminDevice> qw = new QueryWrapper<>();
        qw.select("id").last("LIMIT 1");
        return deviceMapper.selectOne(qw) == null;
    }
}
