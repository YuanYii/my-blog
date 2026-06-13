package com.blog.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.auth.entity.ApiWhitelist;
import com.blog.auth.mapper.ApiWhitelistMapper;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API 白名单服务（带缓存）
 *
 * 缓存机制（双重保险）：
 * - 启动时加载 + 每 5 分钟定时刷新（保底最终一致）
 * - 增/改/删后立即 refreshCache()（admin 改完即生效）
 *
 * 详见 docs/阿里云部署方案.md §ADR-002
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiWhitelistService {

    private final ApiWhitelistMapper mapper;

    /** 缓存（AtomicReference 保证无锁读） */
    private final AtomicReference<List<ApiWhitelist>> cache = new AtomicReference<>(Collections.emptyList());

    public static final String TYPE_PUBLIC = "public";
    public static final String TYPE_ADMIN = "admin";

    /** 启动时加载一次 */
    @PostConstruct
    public void init() {
        refreshCache();
    }

    /** 定时刷新：每 5 分钟（保底最终一致） */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void scheduledRefresh() {
        refreshCache();
    }

    /** 主动刷新缓存（admin 增/改/删后立即调用） */
    public synchronized void refreshCache() {
        QueryWrapper<ApiWhitelist> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        List<ApiWhitelist> list = mapper.selectList(qw);
        // 2026-06-12 修复：缓存按 pathPrefix 长度倒序排——matchPath 用"最长前缀优先"，
        // 防止 `/admin`（admin）排在 `/admin/public-thing`（public）前面时，
        // 公开子路径被当成 admin 拦截；或反之 admin 子路径泄漏成 public。
        list.sort((a, b) -> {
            String pa = a.getPathPrefix() == null ? "" : a.getPathPrefix();
            String pb = b.getPathPrefix() == null ? "" : b.getPathPrefix();
            return Integer.compare(pb.length(), pa.length());
        });
        cache.set(list);
        log.info("[ApiWhitelist] 缓存刷新，加载 {} 条", list.size());
    }

    /**
     * 路径匹配：返回第一个 pathPrefix 命中的记录；未命中返回 null
     * - 缓存按 prefix 长度倒序排（refreshCache 里完成）→ 这里 for-loop 第一个命中即是最长前缀
     * - 未命中 → AdminAuthFilter 默认放行（防御性，新增接口不被误伤）
     */
    public ApiWhitelist matchPath(String path) {
        for (ApiWhitelist w : cache.get()) {
            if (path.startsWith(w.getPathPrefix())) return w;
        }
        return null;
    }

    public List<ApiWhitelist> listAll() {
        return mapper.selectList(null);
    }

    public void add(ApiWhitelist w) {
        mapper.insert(w);
    }

    public void update(Long id, ApiWhitelist w) {
        w.setId(id);
        mapper.updateById(w);
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }
}
