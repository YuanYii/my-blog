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

    // ============ 4 段名称（用户决策：拼错段名直接报错）============
    public static final String SECTION_PROFILE = "profile";
    public static final String SECTION_BLOG = "blog";
    public static final String SECTION_TECHSTACK = "techstack";
    public static final String SECTION_EXPERIENCE = "experience";

    public static final List<String> ALL_SECTIONS = Arrays.asList(
            SECTION_PROFILE, SECTION_BLOG, SECTION_TECHSTACK, SECTION_EXPERIENCE);

    // ============ profile 段字段白名单（user 表）============
    public static final Set<String> PROFILE_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "nickname", "email", "avatar", "bio", "intro", "quote", "footerText", "location"
    )));

    // ============ blog 段字段白名单（site_settings.section=blog）============
    public static final Set<String> BLOG_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "title", "subtitle", "description", "copyright", "logo"
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
     * 取得 4 段对应的字段白名单（仅顶层字段）。
     */
    public static Set<String> getFieldsForSection(String section) {
        switch (section) {
            case SECTION_PROFILE: return PROFILE_FIELDS;
            case SECTION_BLOG: return BLOG_FIELDS;
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
