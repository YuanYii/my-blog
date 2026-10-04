package com.blog.settings.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.settings.entity.SiteSettings;
import com.blog.settings.mapper.SiteSettingsMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 站点设置 Markdown 导出器与 YAML 逆向序列化服务
 *
 * 核心能力：
 * 1. 直查 SQLite DB 组装 profile / blog / techstack / experience 4 段数据；
 * 2. 强类型保全：null/空值统一保全为空字符串 ""，严格匹配 SettingsMdTemplate 字段白名单；
 * 3. 逆向序列化为符合 SettingsMdImporter 规范的 YAML frontmatter + Markdown 结构；
 * 4. 支持文件命名生成与二进制字节数组导出，用于 GET /admin/settings/export-md 文件下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsMdExporter {

    private final UserMapper userMapper;
    private final SiteSettingsMapper siteSettingsMapper;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter FILENAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 生成导出文件名：site-settings-yyyyMMdd-HHmmss.md
     */
    public String generateExportFilename() {
        return "site-settings-" + LocalDateTime.now().format(FILENAME_DATE_FORMAT) + ".md";
    }

    /**
     * 导出站点设置 Markdown 字节数组（UTF-8 编码）
     */
    public byte[] exportToMdBytes() {
        return exportToMd().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 导出完整站点设置 Markdown 文档（YAML frontmatter + 结构说明 Body）
     */
    public String exportToMd() {
        Map<String, Object> root = assembleSettingsData();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setSplitLines(false);

        Yaml yaml = new Yaml(options);
        String yamlContent = yaml.dump(root);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append(yamlContent.trim()).append("\n");
        sb.append("---\n\n");
        sb.append("# 站点设置导出归档\n\n");
        sb.append("> 本文件由博客系统自动导出，包含站点设置的 8 个核心区段（profile / blog / social / preferences / theme / advanced / techstack / experience）。\n");
        sb.append("> 可直接用于备份归档，或在「站点设置 -> 高级」中通过导入功能无损回写系统。\n\n");
        sb.append("- 导出时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("- 字段规范: 严格遵循 SettingsMdTemplate 白名单定义\n");
        sb.append("- 空值约定: ").append(SettingsMdTemplate.NULL_SENTINEL)
          .append(" = 该字段为空，导入时会被显式清空；普通空字符串 '' 表示未填写，导入时跳过不覆盖\n");

        return sb.toString();
    }

    /**
     * 组装 8 段核心配置数据（profile / blog / social / preferences / theme / advanced / techstack / experience）
     */
    public Map<String, Object> assembleSettingsData() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(SettingsMdTemplate.SECTION_PROFILE, assembleProfile());
        root.put(SettingsMdTemplate.SECTION_BLOG, assembleBlog());
        root.put(SettingsMdTemplate.SECTION_SOCIAL, assembleSocial());
        root.put(SettingsMdTemplate.SECTION_PREFERENCES, assemblePreferences());
        root.put(SettingsMdTemplate.SECTION_THEME, assembleTheme());
        root.put(SettingsMdTemplate.SECTION_ADVANCED, assembleAdvanced());
        root.put(SettingsMdTemplate.SECTION_TECHSTACK, assembleTechstack());
        root.put(SettingsMdTemplate.SECTION_EXPERIENCE, assembleExperience());
        return root;
    }

    /**
     * 组装 profile 段（直读 user 表，id=1L）
     * 严格匹配 SettingsMdTemplate.PROFILE_FIELDS 白名单，null 字段强类型保全为空字符串 ""
     */
    public Map<String, Object> assembleProfile() {
        User user = null;
        try {
            user = userMapper.selectById(1L);
        } catch (Exception e) {
            log.warn("[settings-md-export] 查询 profile 失败，使用空值兜底", e);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nickname", sentinelIfBlank(user != null ? user.getNickname() : null));
        map.put("email", sentinelIfBlank(user != null ? user.getEmail() : null));
        map.put("avatar", sentinelIfBlank(user != null ? user.getAvatar() : null));
        map.put("bio", sentinelIfBlank(user != null ? user.getBio() : null));
        map.put("intro", sentinelIfBlank(user != null ? user.getIntro() : null));
        map.put("quote", sentinelIfBlank(user != null ? user.getQuote() : null));
        map.put("footerText", sentinelIfBlank(user != null ? user.getFooterText() : null));
        map.put("location", sentinelIfBlank(user != null ? user.getLocation() : null));
        return map;
    }

    /**
     * 组装 blog 段（直读 site_settings 表 section=blog）
     * 严格匹配 SettingsMdTemplate.BLOG_FIELDS 白名单，null 字段强类型保全为空字符串 ""
     */
    public Map<String, Object> assembleBlog() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_BLOG);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", getStringOrSentinel(rawData, "title"));
        map.put("subtitle", getStringOrSentinel(rawData, "subtitle"));
        map.put("description", getStringOrSentinel(rawData, "description"));
        map.put("copyright", getStringOrSentinel(rawData, "copyright"));
        map.put("logo", getStringOrSentinel(rawData, "logo"));
        return map;
    }

    /**
     * 组装 social 段（直读 site_settings 表 section=social）
     * 严格匹配 SettingsMdTemplate.SOCIAL_FIELDS 白名单，null 字段强类型保全为空字符串 ""
     */
    public Map<String, Object> assembleSocial() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_SOCIAL);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("github", getStringOrSentinel(rawData, "github"));
        map.put("twitter", getStringOrSentinel(rawData, "twitter"));
        map.put("emailPublic", getStringOrSentinel(rawData, "emailPublic"));
        map.put("wechat", getStringOrSentinel(rawData, "wechat"));
        map.put("weibo", getStringOrSentinel(rawData, "weibo"));
        map.put("rss", getStringOrSentinel(rawData, "rss"));
        return map;
    }

    /**
     * 组装 preferences 段（直读 site_settings 表 section=preferences）
     * 严格匹配 SettingsMdTemplate.PREFERENCES_FIELDS 白名单，null 字段强类型保全为空字符串 ""
     */
    public Map<String, Object> assemblePreferences() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_PREFERENCES);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("language", getStringOrSentinel(rawData, "language"));
        map.put("timezone", getStringOrSentinel(rawData, "timezone"));
        map.put("density", getStringOrSentinel(rawData, "density"));
        map.put("codeTheme", getStringOrSentinel(rawData, "codeTheme"));
        return map;
    }

    /**
     * 组装 theme 段（直读 site_settings 表 section=theme）
     * 严格匹配 SettingsMdTemplate.THEME_FIELDS 白名单，null 字段强类型保全为空字符串 ""
     */
    public Map<String, Object> assembleTheme() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_THEME);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", getStringOrSentinel(rawData, "mode"));
        map.put("primaryColor", getStringOrSentinel(rawData, "primaryColor"));
        map.put("accentColor", getStringOrSentinel(rawData, "accentColor"));
        map.put("fontFamily", getStringOrSentinel(rawData, "fontFamily"));
        return map;
    }

    /**
     * 组装 advanced 段（直读 site_settings 表 section=advanced）
     * 严格匹配 SettingsMdTemplate.ADVANCED_FIELDS 白名单，null/缺少时使用系统默认 true
     */
    public Map<String, Object> assembleAdvanced() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_ADVANCED);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enableCache", getBooleanOrDefault(rawData, "enableCache", true));
        map.put("enableRss", getBooleanOrDefault(rawData, "enableRss", true));
        map.put("enableSearch", getBooleanOrDefault(rawData, "enableSearch", true));
        map.put("enableCommentModeration", getBooleanOrDefault(rawData, "enableCommentModeration", true));
        return map;
    }

    /**
     * 组装 techstack 段（直读 site_settings 表 section=techstack）
     * 严格遵循 { groups: [ { label, items: [ { name, dim } ] } ] } 结构约束，过滤非白名单字段
     */
    public Map<String, Object> assembleTechstack() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_TECHSTACK);
        Map<String, Object> map = new LinkedHashMap<>();
        List<Map<String, Object>> cleanGroups = new ArrayList<>();

        Object groupsObj = rawData != null ? rawData.get(SettingsMdTemplate.TECHSTACK_KEY_GROUPS) : null;
        if (groupsObj instanceof List) {
            List<?> groups = (List<?>) groupsObj;
            for (Object go : groups) {
                if (go instanceof Map) {
                    Map<?, ?> group = (Map<?, ?>) go;
                    Map<String, Object> cleanGroup = new LinkedHashMap<>();
                    cleanGroup.put("label", group.get("label") != null ? String.valueOf(group.get("label")) : "");

                    List<Map<String, Object>> cleanItems = new ArrayList<>();
                    Object itemsObj = group.get("items");
                    if (itemsObj instanceof List) {
                        for (Object io : (List<?>) itemsObj) {
                            if (io instanceof Map) {
                                Map<?, ?> item = (Map<?, ?>) io;
                                Map<String, Object> cleanItem = new LinkedHashMap<>();
                                cleanItem.put("name", item.get("name") != null ? String.valueOf(item.get("name")) : "");
                                Object dimObj = item.get("dim");
                                boolean dim = false;
                                if (dimObj instanceof Boolean) {
                                    dim = (Boolean) dimObj;
                                } else if (dimObj != null) {
                                    dim = Boolean.parseBoolean(String.valueOf(dimObj));
                                }
                                cleanItem.put("dim", dim);
                                cleanItems.add(cleanItem);
                            }
                        }
                    }
                    cleanGroup.put("items", cleanItems);
                    cleanGroups.add(cleanGroup);
                }
            }
        }

        // 2026-10-03 DEV-007：空分组导出为哨兵，避免导入时被防洗白规则跳过而无法还原
        map.put(SettingsMdTemplate.TECHSTACK_KEY_GROUPS,
                cleanGroups.isEmpty() ? SettingsMdTemplate.NULL_SENTINEL : cleanGroups);
        return map;
    }

    /**
     * 组装 experience 段（直读 site_settings 表 section=experience）
     * 严格遵循 { items: [ { time, title, desc } ] } 结构约束，过滤非白名单字段，null 统一为空串 ""
     */
    public Map<String, Object> assembleExperience() {
        Map<String, Object> rawData = querySectionData(SettingsMdTemplate.SECTION_EXPERIENCE);
        Map<String, Object> map = new LinkedHashMap<>();
        List<Map<String, Object>> cleanItems = new ArrayList<>();

        Object itemsObj = rawData != null ? rawData.get(SettingsMdTemplate.EXPERIENCE_KEY_ITEMS) : null;
        if (itemsObj instanceof List) {
            for (Object io : (List<?>) itemsObj) {
                if (io instanceof Map) {
                    Map<?, ?> item = (Map<?, ?>) io;
                    Map<String, Object> cleanItem = new LinkedHashMap<>();
                    cleanItem.put("time", getStringOrEmpty(item, "time"));
                    cleanItem.put("title", getStringOrEmpty(item, "title"));
                    cleanItem.put("desc", getStringOrEmpty(item, "desc"));
                    cleanItems.add(cleanItem);
                }
            }
        }

        // 2026-10-03 DEV-007：空履历导出为哨兵，避免导入时被防洗白规则跳过而无法还原
        map.put(SettingsMdTemplate.EXPERIENCE_KEY_ITEMS,
                cleanItems.isEmpty() ? SettingsMdTemplate.NULL_SENTINEL : cleanItems);
        return map;
    }

    /**
     * 直查 SQLite 对应 section 的 JSON 数据并反序列化为 Map
     */
    private Map<String, Object> querySectionData(String section) {
        try {
            QueryWrapper<SiteSettings> qw = new QueryWrapper<>();
            qw.eq("section", section);
            SiteSettings row = siteSettingsMapper.selectOne(qw);
            if (row == null || row.getData() == null || row.getData().trim().isEmpty()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(row.getData(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[settings-md-export] 直查 SQLite section={} 失败，返回空数据", section, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 安全提取字符串，null 或不存在时返回空字符串 ""
     * 仅用于嵌套数组元素（techstack.items / experience.items）—— 这些走整体数组覆盖，
     * 空串即可原样还原，无需哨兵。
     */
    private String getStringOrEmpty(Map<?, ?> map, String key) {
        if (map == null) return "";
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : "";
    }

    /**
     * 安全提取字符串，null 或空串时返回显式清空哨兵 __NULL__（2026-10-03 DEV-007）
     *
     * 目的：让「导出 → 导入」能完整还原现场。若空值导出为 ''，导入侧会按防洗白规则跳过，
     * 导致数据库里残留旧值（如 email 有值而备份为空 → 还原后仍是旧值）。
     */
    private String getStringOrSentinel(Map<?, ?> map, String key) {
        if (map == null) return SettingsMdTemplate.NULL_SENTINEL;
        Object val = map.get(key);
        if (val == null) return SettingsMdTemplate.NULL_SENTINEL;
        String s = String.valueOf(val);
        if (s.isEmpty()) return SettingsMdTemplate.NULL_SENTINEL;
        // 2026-10-03 DEV-007：真实值恰为哨兵字面量时逃逸，否则导回会被清零
        return SettingsMdTemplate.escapeSentinel(s);
    }

    /**
     * 空值转哨兵：null 或空串 → __NULL__，否则原样返回
     */
    private String sentinelIfBlank(String val) {
        if (val == null || val.isEmpty()) return SettingsMdTemplate.NULL_SENTINEL;
        // 2026-10-03 DEV-007：真实值恰为哨兵字面量时逃逸，否则导回会被清零
        return SettingsMdTemplate.escapeSentinel(val);
    }

    /**
     * 安全提取布尔值，null 或不存在时返回默认值
     */
    private boolean getBooleanOrDefault(Map<?, ?> map, String key, boolean defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String && ((String) val).trim().isEmpty()) return defaultValue;
        if (val != null) return Boolean.parseBoolean(String.valueOf(val));
        return defaultValue;
    }
}
