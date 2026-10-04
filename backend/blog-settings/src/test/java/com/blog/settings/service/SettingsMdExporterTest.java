package com.blog.settings.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.settings.entity.SiteSettings;
import com.blog.settings.mapper.SiteSettingsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SettingsMdExporter 导出器与 YAML 逆向序列化测试 (8 段体系与空值跳过加固)
 *
 * 验收标准核验：
 * 1. 直读 SQLite 组装 8 段数据（profile / blog / social / preferences / theme / advanced / techstack / experience）；
 * 2. 导出配置 splitLines(false) 避免长文本与长 URL 自动折行；
 * 3. 空值与 null 字段强类型保全为空串，严格匹配 SettingsMdTemplate 字段白名单；
 * 4. 导出的 Markdown 文本能够被 SettingsMdImporter 成功无损解析并完成 8 段回写；
 * 5. 导入非破坏性空值跳过保护：空字符串不覆盖已有字段，空列表不洗白已有技能组或履历条目；
 * 6. 导出文件名与字节数组格式正确。
 */
@DisplayName("SettingsMdExporter 导出器与 YAML 逆向序列化测试")
class SettingsMdExporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("完整 8 段数据导出与 SettingsMdImporter 无损反向解析回写闭环")
    void testExportNormalDataAndImportLosslessRoundtrip() throws Exception {
        // 1. 模拟 DB 中的 8 段完整业务数据
        User user = new User();
        user.setId(1L);
        user.setNickname("Corey");
        user.setEmail("corey@example.com");
        user.setAvatar("https://avatar.com/test.png");
        user.setBio("后端与 AI 应用工程师");
        user.setIntro("热爱开源架构");
        user.setQuote("知行合一");
        user.setFooterText("© 2026 Corey");
        user.setLocation("北京");

        Map<String, Object> blogMap = new LinkedHashMap<>();
        blogMap.put("title", "Corey 博客");
        blogMap.put("subtitle", "Java / AI 架构日常");
        blogMap.put("description", "记录后端与 Agent 协作日常");
        blogMap.put("copyright", "© 2026 Corey");
        blogMap.put("logo", "https://logo.com/test.png");

        Map<String, Object> socialMap = new LinkedHashMap<>();
        socialMap.put("github", "https://github.com/corey");
        socialMap.put("twitter", "https://twitter.com/corey");
        socialMap.put("emailPublic", "public@corey.dev");
        socialMap.put("wechat", "corey_wx");
        socialMap.put("weibo", "corey_weibo");
        socialMap.put("rss", "/rss.xml");

        Map<String, Object> prefMap = new LinkedHashMap<>();
        prefMap.put("language", "zh-CN");
        prefMap.put("timezone", "Asia/Shanghai");
        prefMap.put("density", "comfortable");
        prefMap.put("codeTheme", "monokai");

        Map<String, Object> themeMap = new LinkedHashMap<>();
        themeMap.put("mode", "dark");
        themeMap.put("primaryColor", "#10b981");
        themeMap.put("accentColor", "#f59e0b");
        themeMap.put("fontFamily", "sans-serif");

        Map<String, Object> advMap = new LinkedHashMap<>();
        advMap.put("enableCache", true);
        advMap.put("enableRss", false);
        advMap.put("enableSearch", true);
        advMap.put("enableCommentModeration", false);

        Map<String, Object> techstackMap = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("label", "常用技能");
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> it1 = new LinkedHashMap<>();
        it1.put("name", "Java / Spring Boot");
        it1.put("dim", false);
        Map<String, Object> it2 = new LinkedHashMap<>();
        it2.put("name", "Python");
        it2.put("dim", true);
        items.add(it1);
        items.add(it2);
        g1.put("items", items);
        groups.add(g1);
        techstackMap.put("groups", groups);

        Map<String, Object> experienceMap = new LinkedHashMap<>();
        List<Map<String, Object>> expItems = new ArrayList<>();
        Map<String, Object> exp1 = new LinkedHashMap<>();
        exp1.put("time", "2024 — 至今");
        exp1.put("title", "高级研发工程师");
        exp1.put("desc", "主导多 Agent 自动化研发流程落地");
        expItems.add(exp1);
        experienceMap.put("items", expItems);

        Map<String, Map<String, Object>> allSections = new HashMap<>();
        allSections.put(SettingsMdTemplate.SECTION_BLOG, blogMap);
        allSections.put(SettingsMdTemplate.SECTION_SOCIAL, socialMap);
        allSections.put(SettingsMdTemplate.SECTION_PREFERENCES, prefMap);
        allSections.put(SettingsMdTemplate.SECTION_THEME, themeMap);
        allSections.put(SettingsMdTemplate.SECTION_ADVANCED, advMap);
        allSections.put(SettingsMdTemplate.SECTION_TECHSTACK, techstackMap);
        allSections.put(SettingsMdTemplate.SECTION_EXPERIENCE, experienceMap);

        // 2. 构造 Mapper 动态代理模拟直查 SQLite DB
        UserMapper mockUserMapper = createMockUserMapper(user, null);
        SiteSettingsMapper mockSettingsMapper = createMockSettingsMapper(allSections);

        SettingsMdExporter exporter = new SettingsMdExporter(mockUserMapper, mockSettingsMapper);

        // 3. 执行导出
        String md = exporter.exportToMd();
        assertNotNull(md);
        assertTrue(md.startsWith("---"), "导出内容必须以 YAML frontmatter 分隔符 --- 开头");
        assertTrue(md.contains("\n---\n"), "导出内容必须包含 YAML frontmatter 闭合分隔符");
        for (String sec : SettingsMdTemplate.ALL_SECTIONS) {
            assertTrue(md.contains(sec + ":"), "必须包含 " + sec + " 段");
        }
        assertTrue(md.contains("Corey 博客"), "必须包含 blog.title 实际数据");
        assertTrue(md.contains("https://github.com/corey"), "必须包含 social.github 实际数据");
        assertTrue(md.contains("monokai"), "必须包含 preferences.codeTheme 实际数据");

        // 4. 将导出的 Markdown 输入给 SettingsMdImporter 执行解析回写
        Map<String, Object> capturedAppliedData = new HashMap<>();
        UserMapper importUserMapper = createMockUserMapper(user, capturedAppliedData);
        SiteSettingsService importSettingsService = createMockSiteSettingsService(capturedAppliedData, null);

        SettingsMdImporter importer = new SettingsMdImporter(importUserMapper, importSettingsService);
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-test").toString());
        importer.init();

        MultipartFile multipartFile = createMockMultipartFile("export-test.md", md);

        List<String> applied = importer.importFromMd(multipartFile);

        // 5. 断言解析与回写无损性：全部 8 段成功应用
        assertEquals(SettingsMdTemplate.ALL_SECTIONS, applied,
                "SettingsMdImporter 必须成功无损解析并应用全部 8 段数据");

        // 断言 blog 段回写数据与导出源一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedBlog = (Map<String, Object>) capturedAppliedData.get("blog");
        assertNotNull(appliedBlog);
        assertEquals("Corey 博客", appliedBlog.get("title"));
        assertEquals("© 2026 Corey", appliedBlog.get("copyright"));

        // 断言 social 段回写数据一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedSocial = (Map<String, Object>) capturedAppliedData.get("social");
        assertNotNull(appliedSocial);
        assertEquals("https://github.com/corey", appliedSocial.get("github"));
        assertEquals("public@corey.dev", appliedSocial.get("emailPublic"));

        // 断言 preferences 段回写数据一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedPref = (Map<String, Object>) capturedAppliedData.get("preferences");
        assertNotNull(appliedPref);
        assertEquals("monokai", appliedPref.get("codeTheme"));

        // 断言 theme 段回写数据一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedTheme = (Map<String, Object>) capturedAppliedData.get("theme");
        assertNotNull(appliedTheme);
        assertEquals("dark", appliedTheme.get("mode"));
        assertEquals("#10b981", appliedTheme.get("primaryColor"));

        // 断言 advanced 段回写数据一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedAdv = (Map<String, Object>) capturedAppliedData.get("advanced");
        assertNotNull(appliedAdv);
        assertEquals(true, appliedAdv.get("enableCache"));
        assertEquals(false, appliedAdv.get("enableRss"));

        // 断言 techstack 段回写数据一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedTech = (Map<String, Object>) capturedAppliedData.get("techstack");
        assertNotNull(appliedTech);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedGroups = (List<Map<String, Object>>) appliedTech.get("groups");
        assertEquals(1, appliedGroups.size());
        assertEquals("常用技能", appliedGroups.get(0).get("label"));

        // 断言 experience 段回写数据一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedExp = (Map<String, Object>) capturedAppliedData.get("experience");
        assertNotNull(appliedExp);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedExpItems = (List<Map<String, Object>>) appliedExp.get("items");
        assertEquals(1, appliedExpItems.size());
        assertEquals("高级研发工程师", appliedExpItems.get(0).get("title"));
    }

    @Test
    @DisplayName("DB 为空或 null 字段时强类型保全为空串/默认布尔值，且符合 SettingsMdTemplate 8 段白名单")
    void testExportNullAndEmptyDataHandling() throws Exception {
        // 1. User 表为 null，各 section 在 site_settings 中均为空
        UserMapper mockUserMapper = createMockUserMapper(null, null);
        SiteSettingsMapper mockSettingsMapper = createMockSettingsMapper(Collections.emptyMap());

        SettingsMdExporter exporter = new SettingsMdExporter(mockUserMapper, mockSettingsMapper);

        // 2. 组装数据核验
        Map<String, Object> data = exporter.assembleSettingsData();

        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) data.get("profile");
        assertNotNull(profile);
        for (String field : SettingsMdTemplate.PROFILE_FIELDS) {
            assertTrue(profile.containsKey(field), "profile 段必须保留 key: " + field);
            assertEquals(SettingsMdTemplate.NULL_SENTINEL, profile.get(field), "profile 段 key " + field + " 在 DB 为空时必须填入清空哨兵");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> blog = (Map<String, Object>) data.get("blog");
        assertNotNull(blog);
        for (String field : SettingsMdTemplate.BLOG_FIELDS) {
            assertTrue(blog.containsKey(field), "blog 段必须保留 key: " + field);
            assertEquals(SettingsMdTemplate.NULL_SENTINEL, blog.get(field), "blog 段 key " + field + " 在 DB 为空时必须填入清空哨兵");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> social = (Map<String, Object>) data.get("social");
        assertNotNull(social);
        for (String field : SettingsMdTemplate.SOCIAL_FIELDS) {
            assertTrue(social.containsKey(field), "social 段必须保留 key: " + field);
            assertEquals(SettingsMdTemplate.NULL_SENTINEL, social.get(field), "social 段 key " + field + " 在 DB 为空时必须填入清空哨兵");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> preferences = (Map<String, Object>) data.get("preferences");
        assertNotNull(preferences);
        for (String field : SettingsMdTemplate.PREFERENCES_FIELDS) {
            assertTrue(preferences.containsKey(field), "preferences 段必须保留 key: " + field);
            assertEquals(SettingsMdTemplate.NULL_SENTINEL, preferences.get(field), "preferences 段 key " + field + " 在 DB 为空时必须填入清空哨兵");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> theme = (Map<String, Object>) data.get("theme");
        assertNotNull(theme);
        for (String field : SettingsMdTemplate.THEME_FIELDS) {
            assertTrue(theme.containsKey(field), "theme 段必须保留 key: " + field);
            assertEquals(SettingsMdTemplate.NULL_SENTINEL, theme.get(field), "theme 段 key " + field + " 在 DB 为空时必须填入清空哨兵");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> advanced = (Map<String, Object>) data.get("advanced");
        assertNotNull(advanced);
        for (String field : SettingsMdTemplate.ADVANCED_FIELDS) {
            assertTrue(advanced.containsKey(field), "advanced 段必须保留 key: " + field);
            assertTrue((Boolean) advanced.get(field), "advanced 段 key " + field + " 在 DB 为空时默认返回 true");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> techstack = (Map<String, Object>) data.get("techstack");
        assertNotNull(techstack);
        assertTrue(techstack.containsKey("groups"));
        assertEquals(SettingsMdTemplate.NULL_SENTINEL, techstack.get("groups"),
                "空分组必须导出为清空哨兵，否则导入时会被防洗白跳过而无法还原");

        @SuppressWarnings("unchecked")
        Map<String, Object> experience = (Map<String, Object>) data.get("experience");
        assertNotNull(experience);
        assertTrue(experience.containsKey("items"));
        assertEquals(SettingsMdTemplate.NULL_SENTINEL, experience.get("items"),
                "空履历必须导出为清空哨兵，否则导入时会被防洗白跳过而无法还原");

        // 3. 生成 Markdown 并验证 SettingsMdImporter 能成功通过 Schema 校验
        String md = exporter.exportToMd();

        Map<String, Object> capturedAppliedData = new HashMap<>();
        User existUser = new User();
        existUser.setId(1L);
        UserMapper importUserMapper = createMockUserMapper(existUser, capturedAppliedData);
        SiteSettingsService importSettingsService = createMockSiteSettingsService(capturedAppliedData, null);

        SettingsMdImporter importer = new SettingsMdImporter(importUserMapper, importSettingsService);
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-test-null").toString());
        importer.init();

        MultipartFile multipartFile = createMockMultipartFile("null-test.md", md);

        // 验证 8 段全部通过校验并应用
        List<String> applied = importer.importFromMd(multipartFile);
        assertEquals(8, applied.size());
    }

    @Test
    @DisplayName("导入空值非破坏性跳过保护：空串不覆盖已有数据，空列表不洗白已有技能组或履历条目")
    void testSkipEmptyValuesAndPreventDataWashout() throws Exception {
        // 1. 模拟数据库中已有完整的预存数据
        User existUser = new User();
        existUser.setId(1L);
        existUser.setNickname("原用户名");
        existUser.setEmail("original@example.com");

        Map<String, Map<String, Object>> dbStorage = new HashMap<>();

        Map<String, Object> existingBlog = new LinkedHashMap<>();
        existingBlog.put("title", "已存博客标题");
        existingBlog.put("subtitle", "已存副标题");
        existingBlog.put("copyright", "© 原版权");
        dbStorage.put(SettingsMdTemplate.SECTION_BLOG, existingBlog);

        Map<String, Object> existingSocial = new LinkedHashMap<>();
        existingSocial.put("github", "https://github.com/original");
        existingSocial.put("rss", "/original-rss.xml");
        dbStorage.put(SettingsMdTemplate.SECTION_SOCIAL, existingSocial);

        Map<String, Object> existingTechstack = new LinkedHashMap<>();
        List<Map<String, Object>> existGroups = new ArrayList<>();
        Map<String, Object> eg1 = new LinkedHashMap<>();
        eg1.put("label", "核心技能");
        eg1.put("items", Collections.singletonList(Collections.singletonMap("name", "Spring Boot")));
        existGroups.add(eg1);
        existingTechstack.put("groups", existGroups);
        dbStorage.put(SettingsMdTemplate.SECTION_TECHSTACK, existingTechstack);

        Map<String, Object> existingExperience = new LinkedHashMap<>();
        List<Map<String, Object>> existExpItems = new ArrayList<>();
        Map<String, Object> ee1 = new LinkedHashMap<>();
        ee1.put("title", "系统架构师");
        existExpItems.add(ee1);
        existingExperience.put("items", existExpItems);
        dbStorage.put(SettingsMdTemplate.SECTION_EXPERIENCE, existingExperience);

        // 2. 构造一个包含部分空串与空列表的导入 Markdown
        // profile: nickname="", email="new@example.com"
        // blog: title="", subtitle="更新副标题", copyright=""
        // social: github="", rss=""
        // techstack: groups=[] (空列表，模拟误导或空模板)
        // experience: items=[] (空列表，模拟误导或空模板)
        String md = "---\n"
                + "profile:\n"
                + "  nickname: \"\"\n"
                + "  email: \"new@example.com\"\n"
                + "  avatar: \"\"\n"
                + "  bio: \"\"\n"
                + "  intro: \"\"\n"
                + "  quote: \"\"\n"
                + "  footerText: \"\"\n"
                + "  location: \"\"\n"
                + "blog:\n"
                + "  title: \"\"\n"
                + "  subtitle: \"更新副标题\"\n"
                + "  description: \"\"\n"
                + "  copyright: \"\"\n"
                + "  logo: \"\"\n"
                + "social:\n"
                + "  github: \"\"\n"
                + "  twitter: \"\"\n"
                + "  emailPublic: \"\"\n"
                + "  wechat: \"\"\n"
                + "  weibo: \"\"\n"
                + "  rss: \"\"\n"
                + "preferences:\n"
                + "  language: \"\"\n"
                + "  timezone: \"\"\n"
                + "  density: \"\"\n"
                + "  codeTheme: \"\"\n"
                + "theme:\n"
                + "  mode: \"\"\n"
                + "  primaryColor: \"\"\n"
                + "  accentColor: \"\"\n"
                + "  fontFamily: \"\"\n"
                + "advanced:\n"
                + "  enableCache: true\n"
                + "  enableRss: false\n"
                + "  enableSearch: true\n"
                + "  enableCommentModeration: true\n"
                + "techstack:\n"
                + "  groups: []\n"
                + "experience:\n"
                + "  items: []\n"
                + "---\n\n"
                + "# 导入测试文档\n";

        Map<String, Object> capturedAppliedData = new HashMap<>();
        UserMapper importUserMapper = createMockUserMapper(existUser, capturedAppliedData);
        SiteSettingsService importSettingsService = createMockSiteSettingsService(capturedAppliedData, dbStorage);

        SettingsMdImporter importer = new SettingsMdImporter(importUserMapper, importSettingsService);
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-washout-test").toString());
        importer.init();

        MultipartFile multipartFile = createMockMultipartFile("washout-test.md", md);
        List<String> applied = importer.importFromMd(multipartFile);

        assertEquals(8, applied.size(), "8 段均需正常处理完毕");

        // 3. 验证 profile：只有非空字段 email 进入更新，nickname 为空串被跳过，绝不覆盖为 ""
        String profileSqlSet = (String) capturedAppliedData.get("profileSqlSet");
        assertNotNull(profileSqlSet, "email 有更新时应执行 update");
        assertTrue(profileSqlSet.contains("email"), "SQL SET 中应包含 email");
        assertFalse(profileSqlSet.contains("nickname"), "SQL SET 中严禁包含空串字段 nickname");

        // 4. 验证 blog：只有非空字段 subtitle 进入 merge，title 和 copyright 为空串被跳过，库中原有值得以保留
        @SuppressWarnings("unchecked")
        Map<String, Object> blogMerged = (Map<String, Object>) capturedAppliedData.get("blog");
        assertNotNull(blogMerged);
        assertEquals("更新副标题", blogMerged.get("subtitle"));
        assertFalse(blogMerged.containsKey("title"), "merge 的 payload 严禁包含空串 title");
        assertFalse(blogMerged.containsKey("copyright"), "merge 的 payload 严禁包含空串 copyright");
        // 库中状态保留
        assertEquals("已存博客标题", dbStorage.get(SettingsMdTemplate.SECTION_BLOG).get("title"));
        assertEquals("更新副标题", dbStorage.get(SettingsMdTemplate.SECTION_BLOG).get("subtitle"));
        assertEquals("© 原版权", dbStorage.get(SettingsMdTemplate.SECTION_BLOG).get("copyright"));

        // 5. 验证 social：所有字段均为空串，完全跳过 merge，不产生任何覆盖
        assertFalse(capturedAppliedData.containsKey("social"), "全空字段的 social 不应调用 merge 覆盖 DB");
        assertEquals("https://github.com/original", dbStorage.get(SettingsMdTemplate.SECTION_SOCIAL).get("github"));
        assertEquals("/original-rss.xml", dbStorage.get(SettingsMdTemplate.SECTION_SOCIAL).get("rss"));

        // 6. 验证 techstack：groups 为空列表 []，触发防洗白保护，库中已有 groups 完整保留
        @SuppressWarnings("unchecked")
        List<?> techGroups = (List<?>) dbStorage.get(SettingsMdTemplate.SECTION_TECHSTACK).get("groups");
        assertEquals(1, techGroups.size(), "techstack 库中有数据时，传入空列表严禁洗白原有数据");

        // 7. 验证 experience：items 为空列表 []，触发防洗白保护，库中已有 items 完整保留
        @SuppressWarnings("unchecked")
        List<?> expItems = (List<?>) dbStorage.get(SettingsMdTemplate.SECTION_EXPERIENCE).get("items");
        assertEquals(1, expItems.size(), "experience 库中有数据时，传入空列表严禁洗白原有履历");
    }

    @Test
    @DisplayName("哨兵 __NULL__ 显式清空标量字段：email / logo 被置空，非空字段正常覆盖")
    void testNullSentinelClearsScalarFields() throws Exception {
        User existUser = new User();
        existUser.setId(1L);
        existUser.setNickname("原用户名");
        existUser.setEmail("original@example.com");
        existUser.setAvatar("https://avatar.com/old.png");

        Map<String, Map<String, Object>> dbStorage = new HashMap<>();
        Map<String, Object> existingBlog = new LinkedHashMap<>();
        existingBlog.put("title", "已存博客标题");
        existingBlog.put("logo", "https://logo.com/old.png");
        dbStorage.put(SettingsMdTemplate.SECTION_BLOG, existingBlog);

        String md = "---\n"
                + "profile:\n"
                + "  nickname: 新用户名\n"
                + "  email: __NULL__\n"
                + "  avatar: \"\"\n"
                + "  bio: __NULL__\n"
                + "  intro: \"\"\n"
                + "  quote: \"\"\n"
                + "  footerText: \"\"\n"
                + "  location: \"\"\n"
                + "blog:\n"
                + "  title: 新标题\n"
                + "  subtitle: \"\"\n"
                + "  description: \"\"\n"
                + "  copyright: \"\"\n"
                + "  logo: __NULL__\n"
                + "social:\n"
                + "  github: __NULL__\n"
                + "  twitter: \"\"\n"
                + "  emailPublic: \"\"\n"
                + "  wechat: \"\"\n"
                + "  weibo: \"\"\n"
                + "  rss: \"\"\n"
                + "preferences:\n"
                + "  language: \"\"\n"
                + "  timezone: \"\"\n"
                + "  density: \"\"\n"
                + "  codeTheme: \"\"\n"
                + "theme:\n"
                + "  mode: \"\"\n"
                + "  primaryColor: \"\"\n"
                + "  accentColor: \"\"\n"
                + "  fontFamily: \"\"\n"
                + "advanced:\n"
                + "  enableCache: true\n"
                + "  enableRss: true\n"
                + "  enableSearch: true\n"
                + "  enableCommentModeration: true\n"
                + "techstack:\n"
                + "  groups: []\n"
                + "experience:\n"
                + "  items: []\n"
                + "---\n";

        Map<String, Object> capturedAppliedData = new HashMap<>();
        UserMapper importUserMapper = createMockUserMapper(existUser, capturedAppliedData);
        SiteSettingsService importSettingsService = createMockSiteSettingsService(capturedAppliedData, dbStorage);

        SettingsMdImporter importer = new SettingsMdImporter(importUserMapper, importSettingsService);
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-sentinel-test").toString());
        importer.init();

        List<String> applied = importer.importFromMd(createMockMultipartFile("sentinel-test.md", md));
        assertEquals(8, applied.size());

        // profile：哨兵字段 email/bio 必须进入 SQL SET（置 null）；空串 avatar/intro 严禁出现；非空 nickname 正常覆盖
        String profileSqlSet = (String) capturedAppliedData.get("profileSqlSet");
        assertNotNull(profileSqlSet, "存在哨兵/非空字段时应执行 update");
        assertTrue(profileSqlSet.contains("email"), "哨兵 email 必须进入 SET 以显式清空");
        assertTrue(profileSqlSet.contains("bio"), "哨兵 bio 必须进入 SET 以显式清空");
        assertTrue(profileSqlSet.contains("nickname"), "非空 nickname 应正常覆盖");
        assertFalse(profileSqlSet.contains("avatar"), "空串 avatar 严禁进入 SET");
        assertFalse(profileSqlSet.contains("intro"), "空串 intro 严禁进入 SET");

        // blog：哨兵 logo 以 null 进入 merge payload；空串 subtitle 被过滤
        @SuppressWarnings("unchecked")
        Map<String, Object> blogMerged = (Map<String, Object>) capturedAppliedData.get("blog");
        assertNotNull(blogMerged);
        assertEquals("新标题", blogMerged.get("title"));
        assertTrue(blogMerged.containsKey("logo"), "哨兵 logo 必须进入 payload");
        assertNull(blogMerged.get("logo"), "哨兵值应被转换为 null 以显式清空");
        assertFalse(blogMerged.containsKey("subtitle"), "空串 subtitle 严禁进入 payload");

        // social：只有一个哨兵字段，也应触发 merge
        @SuppressWarnings("unchecked")
        Map<String, Object> socialMerged = (Map<String, Object>) capturedAppliedData.get("social");
        assertNotNull(socialMerged, "含哨兵的 social 段不应被整体跳过");
        assertNull(socialMerged.get("github"));
    }

    @Test
    @DisplayName("哨兵 __NULL__ 显式清空数组段：绕过防洗白，techstack.groups 与 experience.items 被清空")
    void testNullSentinelClearsArraySections() throws Exception {
        User existUser = new User();
        existUser.setId(1L);
        existUser.setNickname("原用户名");

        Map<String, Map<String, Object>> dbStorage = new HashMap<>();
        Map<String, Object> existingTechstack = new LinkedHashMap<>();
        List<Map<String, Object>> existGroups = new ArrayList<>();
        Map<String, Object> eg1 = new LinkedHashMap<>();
        eg1.put("label", "核心技能");
        eg1.put("items", Collections.singletonList(Collections.singletonMap("name", "Spring Boot")));
        existGroups.add(eg1);
        existingTechstack.put("groups", existGroups);
        dbStorage.put(SettingsMdTemplate.SECTION_TECHSTACK, existingTechstack);

        Map<String, Object> existingExperience = new LinkedHashMap<>();
        existingExperience.put("items", new ArrayList<>(
                Collections.singletonList(Collections.singletonMap("title", "系统架构师"))));
        dbStorage.put(SettingsMdTemplate.SECTION_EXPERIENCE, existingExperience);

        String md = "---\n"
                + "profile:\n"
                + "  nickname: 原用户名\n"
                + "  email: \"\"\n"
                + "  avatar: \"\"\n"
                + "  bio: \"\"\n"
                + "  intro: \"\"\n"
                + "  quote: \"\"\n"
                + "  footerText: \"\"\n"
                + "  location: \"\"\n"
                + "blog:\n"
                + "  title: \"\"\n" + "  subtitle: \"\"\n" + "  description: \"\"\n"
                + "  copyright: \"\"\n" + "  logo: \"\"\n"
                + "social:\n"
                + "  github: \"\"\n" + "  twitter: \"\"\n" + "  emailPublic: \"\"\n"
                + "  wechat: \"\"\n" + "  weibo: \"\"\n" + "  rss: \"\"\n"
                + "preferences:\n"
                + "  language: \"\"\n" + "  timezone: \"\"\n" + "  density: \"\"\n" + "  codeTheme: \"\"\n"
                + "theme:\n"
                + "  mode: \"\"\n" + "  primaryColor: \"\"\n" + "  accentColor: \"\"\n" + "  fontFamily: \"\"\n"
                + "advanced:\n"
                + "  enableCache: true\n" + "  enableRss: true\n" + "  enableSearch: true\n"
                + "  enableCommentModeration: true\n"
                + "techstack:\n"
                + "  groups: __NULL__\n"
                + "experience:\n"
                + "  items: __NULL__\n"
                + "---\n";

        Map<String, Object> capturedAppliedData = new HashMap<>();
        UserMapper importUserMapper = createMockUserMapper(existUser, capturedAppliedData);
        SiteSettingsService importSettingsService = createMockSiteSettingsService(capturedAppliedData, dbStorage);

        SettingsMdImporter importer = new SettingsMdImporter(importUserMapper, importSettingsService);
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-sentinel-array-test").toString());
        importer.init();

        List<String> applied = importer.importFromMd(createMockMultipartFile("sentinel-array-test.md", md));
        assertEquals(8, applied.size());

        @SuppressWarnings("unchecked")
        List<?> techGroups = (List<?>) dbStorage.get(SettingsMdTemplate.SECTION_TECHSTACK).get("groups");
        assertEquals(0, techGroups.size(), "哨兵必须绕过防洗白，清空已有技能分组");

        @SuppressWarnings("unchecked")
        List<?> expItems = (List<?>) dbStorage.get(SettingsMdTemplate.SECTION_EXPERIENCE).get("items");
        assertEquals(0, expItems.size(), "哨兵必须绕过防洗白，清空已有履历条目");
    }

    @Test
    @DisplayName("导出→导入往返可完整还原：库中空字段经哨兵导出后能真正清空目标库中的残留值")
    void testRoundtripRestoresEmptyFields() throws Exception {
        // 源库：email 为空（模拟备份时刻的真实状态）
        User sourceUser = new User();
        sourceUser.setId(1L);
        sourceUser.setNickname("Corey");
        sourceUser.setEmail("");
        Map<String, Map<String, Object>> sourceSections = new HashMap<>();
        Map<String, Object> sourceBlog = new LinkedHashMap<>();
        sourceBlog.put("title", "Corey 博客");
        sourceBlog.put("logo", "");
        sourceSections.put(SettingsMdTemplate.SECTION_BLOG, sourceBlog);

        SettingsMdExporter exporter = new SettingsMdExporter(
                createMockUserMapper(sourceUser, null), createMockSettingsMapper(sourceSections));
        String md = exporter.exportToMd();

        // 目标库：email / logo 均有值（模拟"备份后又被改动"的现场）
        User targetUser = new User();
        targetUser.setId(1L);
        targetUser.setNickname("旧昵称");
        targetUser.setEmail("stale@example.com");
        Map<String, Map<String, Object>> targetDb = new HashMap<>();
        Map<String, Object> targetBlog = new LinkedHashMap<>();
        targetBlog.put("title", "旧标题");
        targetBlog.put("logo", "https://logo.com/stale.png");
        targetDb.put(SettingsMdTemplate.SECTION_BLOG, targetBlog);

        Map<String, Object> capturedAppliedData = new HashMap<>();
        SettingsMdImporter importer = new SettingsMdImporter(
                createMockUserMapper(targetUser, capturedAppliedData),
                createMockSiteSettingsService(capturedAppliedData, targetDb));
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-roundtrip-test").toString());
        importer.init();

        List<String> applied = importer.importFromMd(createMockMultipartFile("roundtrip.md", md));
        assertEquals(8, applied.size());

        String profileSqlSet = (String) capturedAppliedData.get("profileSqlSet");
        assertNotNull(profileSqlSet);
        assertTrue(profileSqlSet.contains("email"),
                "备份里 email 为空（哨兵），还原时必须进入 SET 把目标库的残留值清空");

        @SuppressWarnings("unchecked")
        Map<String, Object> blogMerged = (Map<String, Object>) capturedAppliedData.get("blog");
        assertNotNull(blogMerged);
        assertNull(blogMerged.get("logo"), "备份里 logo 为空（哨兵），还原时必须置 null 覆盖目标库旧值");
        assertEquals("Corey 博客", blogMerged.get("title"));
    }

    @Test
    @DisplayName("哨兵逃逸：业务数据里的字面量 __NULL__ 导出为 \\__NULL__，导入还原为真实值，不被误清空")
    void testSentinelEscapeRoundtrip() throws Exception {
        // 逃逸工具本身的自反性
        assertEquals(SettingsMdTemplate.NULL_SENTINEL_ESCAPED,
                SettingsMdTemplate.escapeSentinel(SettingsMdTemplate.NULL_SENTINEL),
                "哨兵字面量在导出侧必须逃逸");
        assertEquals(SettingsMdTemplate.NULL_SENTINEL,
                SettingsMdTemplate.unescapeSentinel(SettingsMdTemplate.NULL_SENTINEL_ESCAPED),
                "逃逸形式在导入侧必须还原成字面量");
        assertEquals("普通值", SettingsMdTemplate.escapeSentinel("普通值"));
        assertEquals("普通值", SettingsMdTemplate.unescapeSentinel("普通值"));
        assertFalse(SettingsMdTemplate.isNullSentinel(SettingsMdTemplate.NULL_SENTINEL_ESCAPED),
                "逃逸形式不得被识别成清空指令");

        // 端到端：源库 bio 的值恰好是字符串 "__NULL__"
        User sourceUser = new User();
        sourceUser.setId(1L);
        sourceUser.setNickname("Corey");
        sourceUser.setBio(SettingsMdTemplate.NULL_SENTINEL);
        Map<String, Map<String, Object>> sourceSections = new HashMap<>();
        Map<String, Object> sourceBlog = new LinkedHashMap<>();
        sourceBlog.put("title", "博客");
        sourceBlog.put("description", SettingsMdTemplate.NULL_SENTINEL);
        sourceSections.put(SettingsMdTemplate.SECTION_BLOG, sourceBlog);

        SettingsMdExporter exporter = new SettingsMdExporter(
                createMockUserMapper(sourceUser, null), createMockSettingsMapper(sourceSections));
        String md = exporter.exportToMd();
        assertTrue(md.contains(SettingsMdTemplate.NULL_SENTINEL_ESCAPED),
                "字面量 __NULL__ 必须导出为逃逸形式（允许 YAML 加引号），否则会被导入端清零");

        // 目标库：bio 有旧值，导入后必须变成字面量 "__NULL__" 而不是被清空
        User targetUser = new User();
        targetUser.setId(1L);
        targetUser.setNickname("旧昵称");
        targetUser.setBio("旧签名");
        Map<String, Map<String, Object>> targetDb = new HashMap<>();
        Map<String, Object> targetBlog = new LinkedHashMap<>();
        targetBlog.put("title", "旧标题");
        targetBlog.put("description", "旧描述");
        targetDb.put(SettingsMdTemplate.SECTION_BLOG, targetBlog);

        Map<String, Object> captured = new HashMap<>();
        SettingsMdImporter importer = new SettingsMdImporter(
                createMockUserMapper(targetUser, captured),
                createMockSiteSettingsService(captured, targetDb));
        Field uploadDirField = SettingsMdImporter.class.getDeclaredField("uploadDir");
        uploadDirField.setAccessible(true);
        uploadDirField.set(importer, Files.createTempDirectory("settings-md-escape-test").toString());
        importer.init();

        List<String> applied = importer.importFromMd(createMockMultipartFile("escape.md", md));
        assertEquals(8, applied.size());

        String profileSqlSet = (String) captured.get("profileSqlSet");
        assertNotNull(profileSqlSet);
        @SuppressWarnings("unchecked")
        Map<String, Object> profileParams = (Map<String, Object>) captured.get("profileParams");
        assertNotNull(profileParams);
        assertTrue(profileParams.containsValue(SettingsMdTemplate.NULL_SENTINEL),
                "逃逸值必须还原成字面量 __NULL__ 写回，不能被当成清空指令");
        assertFalse(profileParams.containsValue(SettingsMdTemplate.NULL_SENTINEL_ESCAPED),
                "逃逸前缀不得落库");

        @SuppressWarnings("unchecked")
        Map<String, Object> blogMerged = (Map<String, Object>) captured.get("blog");
        assertNotNull(blogMerged);
        assertEquals(SettingsMdTemplate.NULL_SENTINEL, blogMerged.get("description"),
                "扁平段同样要把逃逸值还原成字面量");
    }

    @Test
    @DisplayName("YAML 导出配置 splitLines(false) 确保超长文本与长 URL 不被自动折行")
    void testYamlSplitLinesDisabled() {
        String longText = "https://example.com/very/long/path/to/resource?param1=abcdefghijklmnopqrstuvwxyz0123456789&param2=abcdefghijklmnopqrstuvwxyz0123456789&param3=long-description-content-overflowing-80-columns";

        Map<String, Object> blogMap = new LinkedHashMap<>();
        blogMap.put("title", "常规标题");
        blogMap.put("subtitle", "副标题");
        blogMap.put("description", longText);
        blogMap.put("copyright", "版权");
        blogMap.put("logo", longText);

        Map<String, Map<String, Object>> allSections = new HashMap<>();
        allSections.put(SettingsMdTemplate.SECTION_BLOG, blogMap);

        SettingsMdExporter exporter = new SettingsMdExporter(createMockUserMapper(null, null), createMockSettingsMapper(allSections));
        String md = exporter.exportToMd();

        // 验证 description 超过 100 字符仍然在单行中，未被折成多行
        assertTrue(md.contains(longText), "长文本在 splitLines(false) 下必须保持完整未拆行");
        assertFalse(md.contains(longText.substring(0, 50) + "\n"), "长文本在 splitLines(false) 下严禁在中间换行");
    }

    @Test
    @DisplayName("DB 存在脏字段或非白名单字段时严格过滤")
    void testStrictWhitelistFiltering() throws Exception {
        Map<String, Object> blogMap = new LinkedHashMap<>();
        blogMap.put("title", "合法标题");
        blogMap.put("subtitle", "合法副标题");
        blogMap.put("description", "合法描述");
        blogMap.put("copyright", "合法版权");
        blogMap.put("logo", "合法logo");
        // 非法脏字段
        blogMap.put("unknownKey", "hacker_payload");
        blogMap.put("adminPassword", "secret123");

        Map<String, Object> socialMap = new LinkedHashMap<>();
        socialMap.put("github", "https://github.com/abc");
        socialMap.put("maliciousField", "rm -rf /");

        User user = new User();
        user.setId(1L);
        user.setNickname("ValidNick");

        Map<String, Map<String, Object>> allSections = new HashMap<>();
        allSections.put(SettingsMdTemplate.SECTION_BLOG, blogMap);
        allSections.put(SettingsMdTemplate.SECTION_SOCIAL, socialMap);

        SiteSettingsMapper mockSettingsMapper = createMockSettingsMapper(allSections);
        UserMapper mockUserMapper = createMockUserMapper(user, null);

        SettingsMdExporter exporter = new SettingsMdExporter(mockUserMapper, mockSettingsMapper);
        Map<String, Object> assembled = exporter.assembleSettingsData();

        @SuppressWarnings("unchecked")
        Map<String, Object> blog = (Map<String, Object>) assembled.get("blog");
        assertEquals(SettingsMdTemplate.BLOG_FIELDS, blog.keySet(),
                "blog 字段集合必须与 SettingsMdTemplate.BLOG_FIELDS 完全一致，杜绝任何外部注入字段");
        assertFalse(blog.containsKey("unknownKey"));
        assertFalse(blog.containsKey("adminPassword"));

        @SuppressWarnings("unchecked")
        Map<String, Object> social = (Map<String, Object>) assembled.get("social");
        assertEquals(SettingsMdTemplate.SOCIAL_FIELDS, social.keySet(),
                "social 字段集合必须与 SettingsMdTemplate.SOCIAL_FIELDS 完全一致");
        assertFalse(social.containsKey("maliciousField"));

        String md = exporter.exportToMd();
        assertFalse(md.contains("unknownKey"), "导出的 Markdown 严禁包含非白名单字段");
        assertFalse(md.contains("adminPassword"), "导出的 Markdown 严禁包含敏感/非白名单字段");
        assertFalse(md.contains("maliciousField"), "导出的 Markdown 严禁包含注入字段");
    }

    @Test
    @DisplayName("文件名生成规则与字节数组导出格式核验")
    void testFilenameAndBytes() {
        SettingsMdExporter exporter = new SettingsMdExporter(createMockUserMapper(null, null), createMockSettingsMapper(Collections.emptyMap()));

        String filename = exporter.generateExportFilename();
        assertNotNull(filename);
        Pattern pattern = Pattern.compile("^site-settings-\\d{8}-\\d{6}\\.md$");
        assertTrue(pattern.matcher(filename).matches(),
                "文件名必须满足 site-settings-yyyyMMdd-HHmmss.md 格式，实际为: " + filename);

        byte[] bytes = exporter.exportToMdBytes();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(exporter.exportToMd(), decoded, "字节数组解码后必须与 Markdown 文本完全一致");
    }

    // ============ Helper 动态代理与 Mock 工具 ============

    @SuppressWarnings("unchecked")
    private UserMapper createMockUserMapper(User returnUser, Map<String, Object> capturedApplied) {
        return (UserMapper) Proxy.newProxyInstance(
                UserMapper.class.getClassLoader(),
                new Class<?>[]{UserMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectById".equals(name)) {
                        return returnUser;
                    }
                    if ("update".equals(name)) {
                        if (capturedApplied != null && args.length > 1 && args[1] instanceof UpdateWrapper) {
                            UpdateWrapper<?> uw = (UpdateWrapper<?>) args[1];
                            capturedApplied.put("profileUpdated", true);
                            capturedApplied.put("profileSqlSet", uw.getSqlSet());
                            capturedApplied.put("profileParams",
                                    new LinkedHashMap<>(uw.getParamNameValuePairs()));
                        }
                        return 1;
                    }
                    return null;
                }
        );
    }

    private SiteSettingsMapper createMockSettingsMapper(Map<String, Map<String, Object>> sections) {
        return (SiteSettingsMapper) Proxy.newProxyInstance(
                SiteSettingsMapper.class.getClassLoader(),
                new Class<?>[]{SiteSettingsMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        if (args != null && args.length > 0 && args[0] instanceof QueryWrapper) {
                            QueryWrapper<?> qw = (QueryWrapper<?>) args[0];
                            qw.getCustomSqlSegment(); // 触发 MyBatis-Plus 参数惰性编译
                            String targetSection = null;
                            Map<String, Object> params = qw.getParamNameValuePairs();
                            if (params != null) {
                                for (Object v : params.values()) {
                                    if (v instanceof String && sections != null && sections.containsKey(v)) {
                                        targetSection = (String) v;
                                        break;
                                    }
                                }
                            }
                            if (targetSection != null) {
                                SiteSettings ss = new SiteSettings();
                                ss.setSection(targetSection);
                                Map<String, Object> secMap = sections.get(targetSection);
                                ss.setData(secMap != null ? objectMapper.writeValueAsString(secMap) : "");
                                return ss;
                            }
                        }
                    }
                    return null;
                }
        );
    }

    private SiteSettingsService createMockSiteSettingsService(Map<String, Object> capturedApplied, Map<String, Map<String, Object>> dbStorage) {
        return new SiteSettingsService(null, null, null) {
            @Override
            public Map<String, Object> get(String section) {
                if (dbStorage != null && dbStorage.containsKey(section)) {
                    return new LinkedHashMap<>(dbStorage.get(section));
                }
                return new LinkedHashMap<>();
            }

            @Override
            public void merge(String section, Map<String, Object> partial) {
                capturedApplied.put(section, partial);
                if (dbStorage != null) {
                    Map<String, Object> cur = dbStorage.computeIfAbsent(section, k -> new LinkedHashMap<>());
                    cur.putAll(partial);
                }
            }
        };
    }

    private MultipartFile createMockMultipartFile(String originalFilename, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return (MultipartFile) Proxy.newProxyInstance(
                MultipartFile.class.getClassLoader(),
                new Class<?>[]{MultipartFile.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getName".equals(name)) return "file";
                    if ("getOriginalFilename".equals(name)) return originalFilename;
                    if ("getContentType".equals(name)) return "text/markdown";
                    if ("isEmpty".equals(name)) return bytes.length == 0;
                    if ("getSize".equals(name)) return (long) bytes.length;
                    if ("getBytes".equals(name)) return bytes;
                    if ("getInputStream".equals(name)) return new ByteArrayInputStream(bytes);
                    if ("transferTo".equals(name)) {
                        if (args != null && args.length > 0 && args[0] instanceof File) {
                            File dest = (File) args[0];
                            Files.write(dest.toPath(), bytes);
                        }
                        return null;
                    }
                    return null;
                }
        );
    }
}
