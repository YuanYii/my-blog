package com.blog.settings.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 2026-06-27 DEV-003：高级开关运行时读取（带 30s 内存缓存）。
 *
 * 高级开关（enableCache / enableRss / enableSearch / enableCommentModeration）需要在请求路径中频繁读取，
 * 直接走 SiteSettingsService.get(advanced) → Redis（5min TTL）或 DB 查询会拉慢热路径。
 * 本类做 30s 短缓存：
 *   - 30s 内多次访问返回同一份 snapshot
 *   - 过期后下次访问触发刷新（lazy refresh，不阻塞）
 *   - 兜底默认 true（保持现有行为：除非显式关闭，否则功能启用）
 *
 * 不做：异步轮询、Redis 监听——博客量级 30s 滞后可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvancedSettingsAccessor {

    private final SiteSettingsService siteSettingsService;

    private static final long CACHE_TTL_MS = 30_000L;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    private Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> c = cached;
        if (c != null && now - cachedAt < CACHE_TTL_MS) return c;
        try {
            Map<String, Object> fresh = siteSettingsService.get(SiteSettingsService.SECTION_ADVANCED);
            cached = fresh;
            cachedAt = now;
            // 同步给 SiteSettingsService，让它知道是否应继续走 Redis 缓存
            if (fresh != null) {
                Object v = fresh.get("enableCache");
                boolean enabled = v == null || (v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v)));
                siteSettingsService.setCacheEnabled(enabled);
            }
            return fresh;
        } catch (Exception e) {
            log.warn("[advanced] load fail, fallback to defaults: {}", e.getMessage());
            return c; // 回退上一次成功值；首次失败则返 null
        }
    }

    private boolean flag(String key, boolean def) {
        Map<String, Object> m = snapshot();
        if (m == null) return def;
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public boolean cacheEnabled() { return flag("enableCache", true); }
    public boolean rssEnabled()   { return flag("enableRss", true); }
    public boolean searchEnabled(){ return flag("enableSearch", true); }

    /**
     * 评论审核：兼容两种 key——前端表单写 `enableCommentModeration`（新），历史 default 用过 `commentModeration`（旧）。
     * 优先级：新 key 显式存在 → 取新 key；否则 → 取旧 key；都缺 → 默认开启（保守）。
     * （早期版本用 OR 合并，会导致 user 明确关闭 enableCommentModeration 时被 legacy commentModeration=true 抵消。）
     */
    public boolean commentModerationEnabled() {
        Map<String, Object> m = snapshot();
        if (m == null) return true;
        Object v = m.get("enableCommentModeration");
        if (v == null) v = m.get("commentModeration");
        if (v == null) return true;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** 手动失效（admin 改设置后可主动调，但即便不调 30s 内也会自然过期） */
    public void invalidate() {
        cached = null;
        cachedAt = 0;
    }
}
