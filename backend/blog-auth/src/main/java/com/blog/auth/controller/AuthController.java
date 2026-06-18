package com.blog.auth.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.auth.entity.AdminDevice;
import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.auth.service.DeviceService;
import com.blog.auth.util.JwtUtil;
import com.blog.common.Result;
import com.blog.common.ResultCode;
import com.blog.common.TrustedProxyUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证：登录 + 当前用户
 *
 * 安全修复（2026-06-07）：
 * - 密码用 BCrypt 校验（user.passwordHash 字段是 BCrypt hash，不再明文比对）
 * - SQL 默认值 password_hash=$2a$10$0YSdd8Tf7xcsmAk.05Kn4uEDSUAIT7ukAZqLnUMLrE5Gnd4wj5jEa
 *   对应密码 "123456"——部署后必须立即在 admin 后台改密码（改 passwordHash 字段）
 * - 登录限流：同一 IP 5 次/分钟失败后锁定 1 分钟（in-memory 计数器，**多实例部署需换 Redis**）
 * - 设备白名单集成：login 时校验 X-Device-Id，未授权设备返回 DEVICE_PENDING
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final DeviceService deviceService;

    /** 登录失败上限（per IP per minute） */
    private static final int LOGIN_FAIL_LIMIT = 5;
    private static final int LOGIN_FAIL_WINDOW_SECONDS = 60;

    /**
     * 登录失败计数器：key = IP，value = (count, windowStartEpoch)
     * in-memory 实现：单实例部署够用；多实例 / 集群部署需替换为 Redis
     * （为避免内存泄漏，清理掉过期的 entry）
     */
    private static final ConcurrentHashMap<String, long[]> LOGIN_FAIL_MAP = new ConcurrentHashMap<>();

    /** 登录 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return Result.error(ResultCode.BAD_REQUEST);
        }

        String ip = clientIp(request);

        // 1) 登录限流检查：同 IP 失败次数超限则直接拒
        if (isLoginLocked(ip)) {
            // FR-2.3：限流触发打 WARN（含 username + IP，便于排查暴力破解——US-2）
            log.warn("登录限流触发：username={} ip={} 已达 {} 次/分钟上限", username, ip, LOGIN_FAIL_LIMIT);
            return Result.error(429, "尝试次数过多，请 1 分钟后再试");
        }

        // 2) 校验账号密码
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("username", username);
        User user = userMapper.selectOne(qw);
        // 2026-06-12 修复：原逻辑区分"用户不存在(1005)"和"密码错(1006)"——攻击者可据此探测账号是否存在
        // 修复：合并为同一错误码 + 同一文案，且密码比对前对不存在的用户也跑一次 BCrypt（恒定时间，防时序探测）
        if (user == null || user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            // 即使没用户也跑一次 BCrypt 占用 CPU，避免时序攻击判断"用户是否存在"
            BCrypt.checkpw(password, "$2a$10$0YSdd8Tf7xcsmAk.05Kn4uEjjX8dQvqJYhqLrE5Gnd4wj5jEa0000");
            recordLoginFail(ip);
            // FR-2.3/2.4：登录失败打 WARN，含 username + IP（禁打 password / hash —— FR-2.5 合规硬线）
            // 文案与返回码合并为"凭证错误"（不暴露"用户是否存在"），日志侧不区分以保持安全语义一致
            log.warn("登录失败：凭证错误 username={} ip={}", username, ip);
            return Result.error(ResultCode.INVALID_CREDENTIALS);
        }
        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            recordLoginFail(ip);
            log.warn("登录失败：凭证错误 username={} ip={}", username, ip);
            return Result.error(ResultCode.INVALID_CREDENTIALS);
        }

        // 3) 设备白名单校验（deviceId 缺失走 trust-migrate 通道——内网限定，详见 DeviceService）
        String deviceId = body.get("deviceId");
        String deviceName = body.get("deviceName");
        String userAgent = request.getHeader("User-Agent");
        AdminDevice device;
        try {
            device = deviceService.verifyOnLogin(deviceId, deviceName, ip, userAgent);
        } catch (com.blog.common.BusinessException e) {
            // 设备白名单失败也算登录失败（防止攻击者用设备白名单做密码侧信道）
            recordLoginFail(ip);
            // FR-2.3：密码已对但设备未通过（待授权/吊销）——WARN 记录，便于区分"密码错"与"设备拦截"
            log.warn("登录受阻：密码正确但设备校验未通过 username={} ip={} code={} reason={}",
                    username, ip, e.getCode(), e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        }

        // 4) 登录成功，清零失败计数
        LOGIN_FAIL_MAP.remove(ip);

        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole(), device.getDeviceId());
        // FR-2.3/2.4：登录成功打 INFO，含 username + IP + deviceId（禁打 token 全文 —— FR-2.5）
        log.info("登录成功：username={} uid={} ip={} deviceId={}", username, user.getId(), ip, device.getDeviceId());

        // 响应平铺：前端 useAuth 需要 res.data.uid/username/role
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("uid", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getAvatar() != null ? user.getAvatar() : "");
        data.put("role", user.getRole());
        // 设备信息回传：方便前端确认当前登录是哪个设备
        data.put("deviceId", device.getDeviceId());
        data.put("deviceName", device.getDeviceName());
        return Result.success(data);
    }

    /** 当前用户（需要 Authorization: Bearer xxx） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Result.error(ResultCode.UNAUTHORIZED);
        }
        try {
            Claims claims = jwtUtil.parse(auth.substring(7));
            Map<String, Object> data = new HashMap<>();
            data.put("uid", claims.get("uid"));
            data.put("role", claims.get("role"));
            data.put("username", claims.getSubject());
            // 顺便回传 deviceId，前端如有需要可读
            Object did = claims.get("deviceId");
            if (did != null) data.put("deviceId", did);
            return Result.success(data);
        } catch (Exception e) {
            return Result.error(ResultCode.TOKEN_INVALID);
        }
    }

    /**
     * 2026-06-15 新增：修改当前登录用户密码
     * 路径：/auth/me/password（语义跟 /auth/me 一致——作用于"我"）
     *
     * 安全约束：
     * 1. 必须带 Authorization Bearer
     * 2. 必须传 oldPassword（防 CSRF 拿到 token 后恶意改密）
     * 3. 新密码 ≥ 8 位（简单强度，BCrypt 自身抗暴力）
     * 4. 新旧密码不能相同
     * 5. 写入用 BCrypt hash（不存明文）
     *
     * 改密后：当前 token 仍然有效（不强制重登，admin 场景下保持会话不断；
     * 如要"改密踢出所有设备"可在后端加 admin_device 状态翻转，本期不做）
     */
    @PutMapping("/me/password")
    public Result<Void> changePassword(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestBody Map<String, String> body) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Result.error(ResultCode.UNAUTHORIZED);
        }
        Long uid;
        try {
            Claims claims = jwtUtil.parse(auth.substring(7));
            uid = claims.get("uid", Long.class);
            if (uid == null) return Result.error(ResultCode.UNAUTHORIZED);
        } catch (Exception e) {
            return Result.error(ResultCode.TOKEN_INVALID);
        }

        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        String confirmPassword = body.get("confirmPassword");
        if (oldPassword == null || oldPassword.isEmpty()
            || newPassword == null || newPassword.isEmpty()
            || confirmPassword == null || confirmPassword.isEmpty()) {
            return Result.error(400, "请填写当前密码、新密码、确认密码");
        }
        if (!newPassword.equals(confirmPassword)) {
            return Result.error(400, "新密码两次输入不一致");
        }
        // 2026-06-15 修复（BUG-NEW-1/2）：
        // 原逻辑只校验下界 8 位，没上限。1000+ 字符密码会让 BCrypt 卡死请求几秒（DoS），
        // 且 BCrypt 实际截断 72 字节——超过 72 字节的密码会被静默截断，
        // 错误信息也会错位（截断后 hash 失败被误报"当前密码错误"）。
        // 业务上限 64 字符：留点余量避开 BCrypt 的 72 字节边界，UX 友好。
        if (newPassword.length() < 8 || newPassword.length() > 64) {
            return Result.error(400, "新密码长度须在 8-64 位之间");
        }
        if (newPassword.equals(oldPassword)) {
            return Result.error(400, "新密码不能与当前密码相同");
        }

        User user = userMapper.selectById(uid);
        if (user == null) return Result.error(ResultCode.UNAUTHORIZED);
        // 校验旧密码（恒定时间防时序）
        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()
            || !BCrypt.checkpw(oldPassword, user.getPasswordHash())) {
            return Result.error(400, "当前密码错误");
        }

        user.setPasswordHash(BCrypt.hashpw(newPassword, BCrypt.gensalt(10)));
        userMapper.updateById(user);
        return Result.success();
    }

    /**
     * 是否处于登录锁定状态：1 分钟内同 IP 失败 >= LOGIN_FAIL_LIMIT 次
     * 同时清理掉过期的 entry（避免内存泄漏）
     */
    private boolean isLoginLocked(String ip) {
        long now = Instant.now().getEpochSecond();
        cleanupExpired(now);
        long[] entry = LOGIN_FAIL_MAP.get(ip);
        if (entry == null) return false;
        // entry[0]=count, entry[1]=windowStartEpoch
        if (now - entry[1] > LOGIN_FAIL_WINDOW_SECONDS) {
            LOGIN_FAIL_MAP.remove(ip);
            return false;
        }
        return entry[0] >= LOGIN_FAIL_LIMIT;
    }

    /** 记录登录失败：累加计数，首次失败设窗口起点 */
    private void recordLoginFail(String ip) {
        long now = Instant.now().getEpochSecond();
        LOGIN_FAIL_MAP.compute(ip, (k, old) -> {
            if (old == null || now - old[1] > LOGIN_FAIL_WINDOW_SECONDS) {
                return new long[]{1L, now};
            }
            old[0]++;
            return old;
        });
    }

    /** 清理过期 entry（每分钟一次） */
    private void cleanupExpired(long now) {
        if (LOGIN_FAIL_MAP.isEmpty()) return;
        // 简易做法：遍历删除过期；map 通常不会太大（每 IP 一项）
        LOGIN_FAIL_MAP.entrySet().removeIf(e -> now - e.getValue()[1] > LOGIN_FAIL_WINDOW_SECONDS);
    }

    private String clientIp(HttpServletRequest req) {
        // 2026-06-12 修复：原逻辑只信任 IPv4 loopback（127.x）+ IPv6 loopback (::1)。
        // 在 docker compose 生产部署下，nginx 与 backend 在同一 bridge 网络，
        // backend.req.getRemoteAddr() 永远是 nginx 容器的私网 IP（如 172.20.0.5），
        // 命中不了 loopback 判定 → X-Real-IP / X-Forwarded-For 全被丢 →
        // 限流计数把所有用户合到 nginx 同一 IP，**任何人失败 5 次就把整站登录卡死**。
        //
        // 修复：把 RFC 1918 私网 + IPv6 ULA / link-local 也纳入 trusted proxy 范围。
        // 假设：backend 不直接暴露公网（docker-compose.prod.yml 里没有 ports: -8080:8080，
        // 只 expose 给容器网络），所以"从私网来的请求"必然是反代过来的——可信。
        // 与 DeviceService.isInternalIp 共用判定语义（loopback + 私网 + ULA）。
        return TrustedProxyUtil.resolveClientIp(req);
    }
}
