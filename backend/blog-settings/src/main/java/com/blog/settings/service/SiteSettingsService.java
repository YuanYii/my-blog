package com.blog.settings.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.settings.entity.SiteSettings;
import com.blog.settings.mapper.SiteSettingsMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点设置 service
 *
 * 2026-06-08 重构：
 *  - 把原 SettingsController 的 5 个 static Map（blog / social / preferences / theme / advanced）迁到 DB
 *  - 5min Redis 缓存（StringRedisTemplate + JSON 字符串）
 *  - 写时主动 DEL 缓存（保证管理后台改了立即生效）
 *  - 启动时检查 + 缺失则补默认值（不依赖 SQL seed，避免 docker pipe 中文乱码坑）
 *
 * 注意：
 *  - profile 段不在此服务里（走 user 表 / UserMapper / SettingsController.profile()）
 *  - 公开读：PublicSettingsController.get(section) 走此 service
 *  - 后台读写：SettingsController.{getX,updateX} 走此 service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteSettingsService {

    public static final String SECTION_BLOG = "blog";
    public static final String SECTION_SOCIAL = "social";
    public static final String SECTION_PREFERENCES = "preferences";
    public static final String SECTION_THEME = "theme";
    public static final String SECTION_ADVANCED = "advanced";
    // 2026-06-13 新增：关于我页面的「技术栈」「个人经历」可后台维护
    public static final String SECTION_TECHSTACK = "techstack";
    public static final String SECTION_EXPERIENCE = "experience";

    /** 公开可读的 section 白名单（PublicSettingsController 用）
     *  2026-06-27 DEV-001/002：theme 与 preferences 须公开（前台 useSiteTheme / useSitePreferences 拉取） */
    public static final List<String> PUBLIC_SECTIONS =
        Arrays.asList(SECTION_BLOG, SECTION_SOCIAL, SECTION_TECHSTACK, SECTION_EXPERIENCE,
                      SECTION_THEME, SECTION_PREFERENCES);

    /** Redis 缓存 key 前缀 */
    private static final String CACHE_KEY_PREFIX = "site_settings:";
    /** 缓存 TTL：5 分钟 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final SiteSettingsMapper mapper;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 2026-06-27 DEV-003：缓存开关——admin 关闭 enableCache 后所有 section 读穿透 DB（仅排障用） */
    private volatile boolean cacheEnabled = true;
    /** 由 AdvancedSettingsAccessor 调用，让此服务知道 advanced.enableCache 当前值 */
    public void setCacheEnabled(boolean enabled) { this.cacheEnabled = enabled; }

    /**
     * 启动兜底：5 个 section 缺则补默认值
     * ——避免 SQL seed 中文 docker pipe 双重编码 + 新部署空表
     */
    @PostConstruct
    public void initDefaultsIfMissing() {
        ensureSection(SECTION_BLOG, defaultBlog());
        ensureSection(SECTION_SOCIAL, defaultSocial());
        ensureSection(SECTION_PREFERENCES, defaultPreferences());
        ensureSection(SECTION_THEME, defaultTheme());
        ensureSection(SECTION_ADVANCED, defaultAdvanced());
        ensureSection(SECTION_TECHSTACK, defaultTechstack());
        ensureSection(SECTION_EXPERIENCE, defaultExperience());
    }

    private void ensureSection(String section, Map<String, Object> defaultData) {
        if (getRaw(section) == null) {
            try {
                String json = objectMapper.writeValueAsString(defaultData);
                SiteSettings s = new SiteSettings();
                s.setSection(section);
                s.setData(json);
                mapper.insert(s);
                log.info("[site_settings] init default section={}", section);
            } catch (Exception e) {
                log.error("[site_settings] init default failed: section={}", section, e);
            }
        }
    }

    /**
     * 读 section 配置（带缓存）
     *  - 命中 Redis → 直接返回
     *  - 未命中 → 查 DB + 回填 Redis（TTL 5min）
     *  - section 不存在 → 返回 null（不抛错，让 controller 决定 fallback）
     */
    public Map<String, Object> get(String section) {
        String key = CACHE_KEY_PREFIX + section;
        // 2026-06-27 DEV-003：cacheEnabled=false 直接穿透 DB，不读不写 Redis（advanced 段例外，保证开关本身的读取）
        boolean useCache = cacheEnabled || SECTION_ADVANCED.equals(section);
        if (useCache) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    return objectMapper.readValue(cached, new TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception e) {
                log.warn("[site_settings] redis read fail, fallback DB: section={}", section, e);
            }
        }
        SiteSettings row = getRaw(section);
        if (row == null) return null;
        try {
            Map<String, Object> data = objectMapper.readValue(row.getData(), new TypeReference<Map<String, Object>>() {});
            if (useCache) {
                try {
                    redis.opsForValue().set(key, row.getData(), CACHE_TTL);
                } catch (Exception e) {
                    log.warn("[site_settings] redis write fail: section={}", section, e);
                }
            }
            return data;
        } catch (Exception e) {
            log.error("[site_settings] parse data fail: section={}", section, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 写 section 配置（全量替换 + 清缓存）
     *  - DB upsert（section 唯一键冲突时 UPDATE）
     *  - DEL Redis key（让下次读强制从 DB 拉）
     */
    public void put(String section, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            SiteSettings existing = getRaw(section);
            if (existing == null) {
                SiteSettings s = new SiteSettings();
                s.setSection(section);
                s.setData(json);
                mapper.insert(s);
            } else {
                existing.setData(json);
                mapper.updateById(existing);
            }
            try {
                Boolean deleted = redis.delete(CACHE_KEY_PREFIX + section);
                log.info("[site_settings] evict cache: section={} deleted={}", section, deleted);
            } catch (Exception e) {
                log.warn("[site_settings] redis evict fail: section={}", section, e);
            }
        } catch (Exception e) {
            throw new RuntimeException("site_settings.put fail: " + section, e);
        }
    }

    /**
     * 合并写（putAll 风格，不存在的 key 用 default，已有 key 覆盖）
     *  - 当前 put 走全量替换，合并语义在 controller 层做
     *  - 2026-06-12 修复：原 merge 是 read-modify-write 没有任何并发保护，
     *    并发场景 A 写 title、B 写 subtitle 会 lost update
     *  - 修复：Redis 分布式锁 SETNX（10s 超时），CAS 失败抛 409 让前端重试
     *
     * 2026-06-22 v4.x polish（trade-off 备忘，不修）：
     *   锁 TTL=10s 对个人博客量级（get→put→DEL 全链路 <100ms）足够。
     *   极端 GC 暂停（Stop-The-World >10s）下锁可能过期,第二个请求拿锁覆盖第一个的写入 →
     *   lost update。当前 settings 改的是非关键配置（title / subtitle / theme）,
     *   lost update 后果可控(用户重新编辑即可),不需要上续期机制。
     *   TODO: 若未来 settings 改"评论开关 / 安全策略"等关键配置,加 Redisson lock watchdog 或
     *         自己实现 1/3 TTL 周期的续期线程。
     */
    public void merge(String section, Map<String, Object> partial) {
        String lockKey = "site_settings_lock:" + section;
        String lockToken = String.valueOf(System.nanoTime());
        try {
            // SETNX 拿锁（10s TTL 防死锁）
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(10));
            if (acquired == null || !acquired) {
                // 拿不到锁——说明有别的线程正在改这个 section
                // 用 BusinessException (code 409) 让 GlobalExceptionHandler 返 409
                throw new com.blog.common.BusinessException(409, "site_settings: section " + section + " 正在被修改，请重试");
            }
            Map<String, Object> current = get(section);
            if (current == null) current = new LinkedHashMap<>();
            current.putAll(partial);
            put(section, current);
        } finally {
            // 简单释放：用 lua 脚本保证只删自己加的锁
            try {
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
                redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(lockKey), lockToken);
            } catch (Exception e) {
                log.warn("[site_settings] lock release fail: section={}", section, e);
            }
        }
    }

    /**
     * 清除全部 section 的 Redis 缓存（数据恢复后调用）。
     * 恢复后 SQLite 已替换为备份数据，但 Redis 仍持有旧值（TTL 5min）。
     * 主动 DEL 让下次读强制走 DB，保证设置立即生效。
     */
    public void evictAllCaches() {
        List<String> sections = Arrays.asList(
            SECTION_BLOG, SECTION_SOCIAL, SECTION_PREFERENCES,
            SECTION_THEME, SECTION_ADVANCED, SECTION_TECHSTACK, SECTION_EXPERIENCE
        );
        int evicted = 0;
        for (String section : sections) {
            try {
                Boolean deleted = redis.delete(CACHE_KEY_PREFIX + section);
                if (Boolean.TRUE.equals(deleted)) evicted++;
            } catch (Exception e) {
                log.warn("[site_settings] evictAllCaches 失败: section={} err={}", section, e.getMessage());
            }
        }
        log.info("[site_settings] 已清除 {}/{} 个 section 缓存（数据恢复后同步）", evicted, sections.size());
    }

    /** DB 直查（不走缓存） */
    private SiteSettings getRaw(String section) {
        QueryWrapper<SiteSettings> qw = new QueryWrapper<>();
        qw.eq("section", section);
        return mapper.selectOne(qw);
    }

    // ============ 默认值（启动兜底） ============

    private static Map<String, Object> defaultBlog() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Yuan Yi · 个人博客");
        m.put("subtitle", "后端工程师的日常");
        m.put("description", "记录技术、读书、生活");
        m.put("copyright", "© 2026 Yuan Yi");
        m.put("logo", "");
        return m;
    }

    private static Map<String, Object> defaultSocial() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("github", "");
        m.put("twitter", "");
        m.put("emailPublic", "");
        m.put("wechat", "");
        m.put("weibo", "");
        m.put("rss", "/rss.xml");
        return m;
    }

    private static Map<String, Object> defaultPreferences() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", "zh-CN");
        m.put("timezone", "Asia/Shanghai");
        m.put("density", "comfortable");
        m.put("codeTheme", "github");
        return m;
    }

    private static Map<String, Object> defaultTheme() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", "auto");
        m.put("primaryColor", "#2f6f5e");
        m.put("accentColor", "#c97b3f");
        m.put("fontFamily", "serif");
        return m;
    }

    private static Map<String, Object> defaultAdvanced() {
        // 2026-06-27 DEV-003：与前端 AdvancedForm 字段对齐，统一用 enableCommentModeration
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enableCache", true);
        m.put("enableRss", true);
        m.put("enableSearch", true);
        m.put("enableCommentModeration", true);
        return m;
    }

    /**
     * 2026-06-13 新增：技术栈默认值（对应 about.vue 原硬编码 skills）
     * 结构：{ groups: [ { label, items: [ { name, dim } ] } ] }
     */
    private static Map<String, Object> defaultTechstack() {
        Map<String, Object> g1 = techGroup("工作中常用",
            techItem("Java / Spring Boot", false),
            techItem("MySQL / PostgreSQL", false),
            techItem("Redis / Kafka", false),
            techItem("Docker / Kubernetes", false),
            techItem("Linux / Nginx", false),
            techItem("Git / CI/CD", false));
        Map<String, Object> g2 = techGroup("会用但不够熟",
            techItem("Vue / Nuxt", false),
            techItem("TypeScript", false),
            techItem("Go / Rust（学习中）", false),
            techItem("Elasticsearch", false),
            techItem("React", true),
            techItem("Swift / iOS 开发", true));
        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(g1);
        groups.add(g2);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groups", groups);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> techGroup(String label, Map<String, Object>... items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("label", label);
        g.put("items", new ArrayList<>(Arrays.asList(items)));
        return g;
    }

    private static Map<String, Object> techItem(String name, boolean dim) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("name", name);
        i.put("dim", dim);
        return i;
    }

    /**
     * 2026-06-13 新增：个人经历默认值（对应 about.vue 原硬编码 experiences）
     * 结构：{ items: [ { time, title, desc } ] }
     */
    private static Map<String, Object> defaultExperience() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(expItem("2022 — 现在", "某互联网公司 · 高级后端工程师",
            "负责核心交易链路，从单体应用到逐步服务化。带过 3 人小组。"));
        items.add(expItem("2018 — 2022", "某 SaaS 公司 · 后端工程师",
            "从 0 到 1 参与了多租户 SaaS 平台的搭建，深入理解了权限、计费、数据隔离。"));
        items.add(expItem("2016 — 2018", "某电商公司 · Java 开发",
            "写了两年的 CRUD，第一次体会到「能跑起来」和「能扛住流量」之间有巨大的鸿沟。"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> expItem(String time, String title, String desc) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("time", time);
        i.put("title", title);
        i.put("desc", desc);
        return i;
    }

    /** 执行 SELECT 查询，返回行列表 */
    public List<Map<String, Object>> execQuery(String sql) {
        return jdbc.queryForList(sql);
    }

    /** 执行 INSERT / UPDATE / DELETE，返回影响行数 */
    public int execUpdate(String sql) {
        return jdbc.update(sql);
    }
}
