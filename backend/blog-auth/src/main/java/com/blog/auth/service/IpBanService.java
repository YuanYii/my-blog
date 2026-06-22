package com.blog.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.auth.entity.IpBan;
import com.blog.auth.mapper.IpBanMapper;
import com.blog.common.BusinessException;
import com.blog.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * IP 封禁服务（2026-06-18 新增）
 *
 * 配合 IpRateLimitFilter：同一 IP 1 秒内请求数超过阈值 → 封禁。
 *
 * 存储分层：
 *  - Redis（ip_ban:{ip}，TTL=封禁时长）：请求热路径唯一查询的地方，O(1)，
 *    避免每个请求都打 SQLite——生产环境 SQLite 连接池只有 1 个连接（单写者锁），
 *    全站每请求一次 DB 查询会直接把站点拖垮。
 *  - DB（ip_ban 表）：封禁事件的持久记录，只在"触发封禁那一刻"写一行，
 *    给 admin 后台用——查看封禁历史、原因、触发时的请求数，支持手动解封。
 *  - 启动时把 DB 里仍然有效（未手动解封 + 未过期）的封禁回灌进 Redis，
 *    覆盖"Redis 重启缓存丢失"或"app 重启但封禁还没到期"的场景，
 *    避免封禁名单在缓存层重建前出现空窗期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpBanService {

    private final IpBanMapper ipBanMapper;
    private final StringRedisTemplate redis;

    private static final String BAN_KEY_PREFIX = "ip_ban:";

    /**
     * 启动时回灌：DB 里"未手动解封 + 未过期"的封禁记录 → 重新写入 Redis（剩余 TTL）
     */
    @PostConstruct
    public void warmUpFromDb() {
        try {
            LocalDateTime now = LocalDateTime.now();
            QueryWrapper<IpBan> qw = new QueryWrapper<>();
            qw.eq("unbanned", false).gt("expire_at", now);
            List<IpBan> activeBans = ipBanMapper.selectList(qw);
            int restored = 0;
            for (IpBan ban : activeBans) {
                long remainingSeconds = Duration.between(now, ban.getExpireAt()).getSeconds();
                if (remainingSeconds > 0) {
                    redis.opsForValue().set(BAN_KEY_PREFIX + ban.getIp(), "1", Duration.ofSeconds(remainingSeconds));
                    restored++;
                }
            }
            if (restored > 0) {
                log.info("[IpBan] 启动回灌 {} 条有效封禁记录到 Redis", restored);
            }
        } catch (Exception e) {
            // 回灌失败不影响启动；最坏情况是这些 IP 在 Redis 缓存重建前短暂被放行
            log.warn("[IpBan] 启动回灌封禁记录失败", e);
        }
    }

    /**
     * 是否已被封禁（请求热路径调用，必须快）
     * Redis 异常 → fail-open（返回 false，放行），避免缓存层故障拖垮全站
     */
    public boolean isBanned(String ip) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(BAN_KEY_PREFIX + ip));
        } catch (Exception e) {
            log.warn("[IpBan] redis 查询封禁状态异常，降级放行: ip={}", ip, e);
            return false;
        }
    }

    /**
     * 封禁一个 IP
     *  - Redis SETNX 占坑：只有抢到坑位的那个线程才写 DB，避免并发场景下
     *    同一次封禁事件（同一秒内多个请求都触发了阈值判断）插入多条 DB 记录
     *  - DB 写失败不影响封禁本身生效（Redis 已经设置成功，后续请求照样会被拦）
     */
    public void ban(String ip, int requestCount, String reason, Duration duration) {
        String key = BAN_KEY_PREFIX + ip;
        boolean acquired;
        try {
            Boolean result = redis.opsForValue().setIfAbsent(key, "1", duration);
            acquired = Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("[IpBan] redis 写封禁标记失败: ip={}", ip, e);
            // Redis 写失败时仍然落库（至少留下审计记录，下次启动能回灌），
            // 代价是这次没法立刻通过 Redis 拦截同一秒内的后续请求
            acquired = true;
        }
        if (!acquired) {
            // 已经被并发的另一个请求封过了，不重复插入 DB
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            IpBan ban = new IpBan();
            ban.setIp(ip);
            ban.setRequestCount(requestCount);
            ban.setReason(reason);
            ban.setBannedAt(now);
            ban.setExpireAt(now.plusSeconds(duration.getSeconds()));
            ban.setUnbanned(false);
            ipBanMapper.insert(ban);
            log.warn("[IpBan] 封禁 IP: ip={} requestCount={} duration={}min", ip, requestCount, duration.toMinutes());
        } catch (Exception e) {
            log.error("[IpBan] 写封禁记录到 DB 失败（Redis 封禁仍然生效）: ip={}", ip, e);
        }
    }

    /** 封禁列表：activeOnly=true 只看当前生效的，否则返回全部历史（按封禁时间倒序） */
    public List<IpBan> list(boolean activeOnly) {
        QueryWrapper<IpBan> qw = new QueryWrapper<>();
        if (activeOnly) {
            qw.eq("unbanned", false).gt("expire_at", LocalDateTime.now());
        }
        qw.orderByDesc("banned_at");
        return ipBanMapper.selectList(qw);
    }

    /** 管理员手动解封：DB 标记 unbanned=true + 删除 Redis 标记位（立即生效，不用等 TTL 过期） */
    public void unban(Long id) {
        IpBan ban = ipBanMapper.selectById(id);
        if (ban == null) throw new BusinessException(ResultCode.NOT_FOUND);
        ban.setUnbanned(true);
        ban.setUnbannedAt(LocalDateTime.now());
        ipBanMapper.updateById(ban);
        try {
            redis.delete(BAN_KEY_PREFIX + ban.getIp());
        } catch (Exception e) {
            log.warn("[IpBan] 解封时删除 Redis 标记失败（DB 已标记解封，Redis key 会在 TTL 后自然过期）: ip={}", ban.getIp(), e);
        }
    }
}
