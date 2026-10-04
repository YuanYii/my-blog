package com.blog.settings.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 站点设置 md 导入字段白名单（2026-07-01 DEV-005）
 *
 * 字段定义与默认值严格对齐 settings 4 段（profile / blog / techstack / experience）。
 * 校验逻辑在 {@link SettingsMdImporter} 里调本类的常量，错误信息直接引用段名 + 字段名。
 */
public final class SettingsMdTemplate {

    private SettingsMdTemplate() {}

    // ============ 8 段名称（用户决策：拼错段名直接报错）============
    public static final String SECTION_PROFILE = "profile";
    public static final String SECTION_BLOG = "blog";
    public static final String SECTION_SOCIAL = "social";
    public static final String SECTION_PREFERENCES = "preferences";
    public static final String SECTION_THEME = "theme";
    public static final String SECTION_ADVANCED = "advanced";
    public static final String SECTION_TECHSTACK = "techstack";
    public static final String SECTION_EXPERIENCE = "experience";

    public static final List<String> ALL_SECTIONS = Arrays.asList(
            SECTION_PROFILE, SECTION_BLOG, SECTION_SOCIAL, SECTION_PREFERENCES,
            SECTION_THEME, SECTION_ADVANCED, SECTION_TECHSTACK, SECTION_EXPERIENCE);

    /**
     * 显式清空哨兵值（2026-10-03 DEV-007 补）
     *
     * 背景：导入侧为了防数据洗白，对空串一律「跳过不覆盖」。这带来一个副作用——
     * 「原本为空的字段」无法被备份文件还原：数据库里 email 有值，而备份 md 里 email: ''
     * 会被跳过，还原后仍是旧值，备份与现场不一致。
     *
     * 语义约定（导入侧三态）：
     *   1. 字段缺失 / null    → 跳过（不覆盖）
     *   2. 空字符串 ''        → 跳过（防洗白，视作「未填写」）
     *   3. 哨兵 __NULL__      → 显式清空该字段（写入 null）
     *
     * 导出侧同步：DB 中为 null/空串的标量字段导出为 __NULL__，空数组（groups/items）
     * 导出为 __NULL__，保证「导出 → 导入」能完整还原现场。
     *
     * 向后兼容：旧备份文件里的 '' 行为不变（仍跳过），不会破坏既有防洗白保护。
     */
    public static final String NULL_SENTINEL = "__NULL__";

    /**
     * 哨兵逃逸形式：业务数据里真的存在字面量 "__NULL__" 时，导出侧写成 "\__NULL__"，
     * 导入侧再还原为 "__NULL__"，避免真实数据被误判成「清空」指令而丢失。
     */
    public static final String NULL_SENTINEL_ESCAPED = "\\__NULL__";

    /**
     * 判断某个值是否为显式清空哨兵（忽略首尾空白）。
     */
    public static boolean isNullSentinel(Object val) {
        return val instanceof String && NULL_SENTINEL.equals(((String) val).trim());
    }

    /**
     * 导出侧逃逸：值为哨兵字面量时加反斜杠前缀。
     */
    public static String escapeSentinel(String val) {
        return NULL_SENTINEL.equals(val) ? NULL_SENTINEL_ESCAPED : val;
    }

    /**
     * 导入侧还原：被逃逸的哨兵还原成字面量 "__NULL__"（普通值原样返回）。
     */
    public static String unescapeSentinel(String val) {
        return NULL_SENTINEL_ESCAPED.equals(val) ? NULL_SENTINEL : val;
    }

    // ============ profile 段字段白名单（user 表）============
    public static final Set<String> PROFILE_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "nickname", "email", "avatar", "bio", "intro", "quote", "footerText", "location"
    )));

    // ============ blog 段字段白名单（site_settings.section=blog）============
    public static final Set<String> BLOG_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "title", "subtitle", "description", "copyright", "logo"
    )));

    // ============ social 段字段白名单（site_settings.section=social）============
    public static final Set<String> SOCIAL_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "github", "twitter", "emailPublic", "wechat", "weibo", "rss"
    )));

    // ============ preferences 段字段白名单（site_settings.section=preferences）============
    public static final Set<String> PREFERENCES_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "language", "timezone", "density", "codeTheme"
    )));

    // ============ theme 段字段白名单（site_settings.section=theme）============
    public static final Set<String> THEME_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "mode", "primaryColor", "accentColor", "fontFamily"
    )));

    // ============ advanced 段字段白名单（site_settings.section=advanced）============
    public static final Set<String> ADVANCED_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "enableCache", "enableRss", "enableSearch", "enableCommentModeration"
    )));

    // ============ techstack 段结构约束 ===========
    // 结构：{ groups: [ { label, items: [ { name, dim } ] } ] }
    public static final String TECHSTACK_KEY_GROUPS = "groups";
    public static final int TECHSTACK_MAX_GROUPS = 20;
    public static final int TECHSTACK_MAX_ITEMS_PER_GROUP = 50;
    public static final Set<String> TECHSTACK_GROUP_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "label", "items"
    )));
    public static final Set<String> TECHSTACK_ITEM_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "name", "dim"
    )));

    // ============ experience 段结构约束 ===========
    // 结构：{ items: [ { time, title, desc } ] }
    public static final String EXPERIENCE_KEY_ITEMS = "items";
    public static final int EXPERIENCE_MAX_ITEMS = 50;
    public static final Set<String> EXPERIENCE_ITEM_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "time", "title", "desc"
    )));

    /**
     * 取得各段对应的字段白名单（仅顶层字段）。
     */
    public static Set<String> getFieldsForSection(String section) {
        switch (section) {
            case SECTION_PROFILE: return PROFILE_FIELDS;
            case SECTION_BLOG: return BLOG_FIELDS;
            case SECTION_SOCIAL: return SOCIAL_FIELDS;
            case SECTION_PREFERENCES: return PREFERENCES_FIELDS;
            case SECTION_THEME: return THEME_FIELDS;
            case SECTION_ADVANCED: return ADVANCED_FIELDS;
            case SECTION_TECHSTACK: return Collections.emptySet(); // techstack 走嵌套校验
            case SECTION_EXPERIENCE: return Collections.emptySet(); // experience 走嵌套校验
            default: return null;
        }
    }

    /**
     * 字段白名单的"反查入口"——给定 section + Map，看哪些字段缺失、哪些非法。
     * 仅适用于扁平结构（profile / blog）。techstack / experience 走专用嵌套校验。
     *
     * @return 错误信息，null = 校验通过
     */
    public static String validateFlatSection(String section, Map<String, Object> data) {
        Set<String> whitelist = getFieldsForSection(section);
        if (whitelist == null) return null;  // techstack/experience 不走这条路径
        if (data == null) return "section." + section + " 不能为空";

        Set<String> inputKeys = data.keySet();
        // 缺字段（白名单有但输入没有）——用 Set diff 取
        Set<String> missing = new HashSet<>(whitelist);
        missing.removeAll(inputKeys);
        if (!missing.isEmpty()) {
            return "section." + section + " 缺字段: " + String.join(", ", new java.util.TreeSet<>(missing));
        }
        // 多字段（输入有但白名单没有）
        Set<String> extra = new HashSet<>(inputKeys);
        extra.removeAll(whitelist);
        if (!extra.isEmpty()) {
            return "section." + section + " 不允许字段: " + String.join(", ", new java.util.TreeSet<>(extra));
        }
        return null;  // 通过
    }
}
