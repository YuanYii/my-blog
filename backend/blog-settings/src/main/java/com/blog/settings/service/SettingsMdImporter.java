package com.blog.settings.service;

import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 站点设置 md 文档导入器（2026-07-01 DEV-005）
 *
 * 入口方法 {@link #importFromMd(MultipartFile)}，整体事务边界覆盖 4 段写入。
 * 任一校验失败 / 写入失败 → 抛 BusinessException，事务回滚（4 段全部未变）。
 *
 * 用户决策点（来自任务卡）：
 *  1. 整体原子性 —— 任一段失败全部回滚（事务边界 = 整个 importFromMd 方法）
 *  2. 严格 schema 校验 —— 缺/多/类型错字段直接报错（不让"宽容模式"静默通过）
 *  3. 错误位置精确到字段名（如 "section.techstack.groups[0].items[2] 缺 name 字段"）
 *
 * 文件生命周期：
 *  1. 校验 MultipartFile（仅 .md，≤2MB）
 *  2. 写临时文件 UPLOAD_DIR/settings-md-import/{uuid}.md（不入 article_attachment 表）
 *  3. 解析 frontmatter + 4 段校验
 *  4. 整体事务里写 4 段
 *  5. 临时文件删除（事务提交后 finally 兜底）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsMdImporter {

    private final UserMapper userMapper;
    private final SiteSettingsService siteSettingsService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** md 文件大小上限 2MB */
    private static final long MAX_MD_SIZE = 2L * 1024 * 1024;
    /** 仅允许 .md 扩展名 */
    private static final String MD_EXT = ".md";

    @Value("${blog.upload.local.dir:/data/uploads}")
    private String uploadDir;

    private Path tempDir;

    @PostConstruct
    public void init() {
        tempDir = Paths.get(uploadDir, "settings-md-import");
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            log.error("[settings-md-import] 创建临时目录失败：dir={}", tempDir, e);
        }
    }

    /**
     * md 导入主入口。
     *
     * 返回：成功导入的 section 名称列表（按 profile/blog/techstack/experience 顺序）
     *
     * 错误抛出：BusinessException(400, 具体错误位置)
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> importFromMd(MultipartFile file) {
        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "settings.md";
        if (!original.toLowerCase().endsWith(MD_EXT)) {
            throw new BusinessException(400, "仅允许 .md 格式");
        }
        if (file.getSize() > MAX_MD_SIZE) {
            throw new BusinessException(400, "文件超过 2MB 上限");
        }

        // 2. 落临时文件
        String tempName = UUID.randomUUID() + "-" + original;
        File tempFile = new File(tempDir.toFile(), tempName);
        try {
            file.transferTo(tempFile);
        } catch (IOException e) {
            log.error("[settings-md-import] 写临时文件失败：name={}", tempName, e);
            throw new BusinessException(500, "临时文件写入失败");
        }

        try {
            // 3. 解析 frontmatter
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(tempFile.toPath());
            } catch (IOException e) {
                log.error("[settings-md-import] 读临时文件失败：path={}", tempFile.getAbsolutePath(), e);
                throw new BusinessException(500, "临时文件读取失败");
            }
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> parsed = parseFrontmatter(content);

            // 4. 严格校验：未知段名 → 400（用户决策：模版不一致直接报错）
            Set<String> inputSections = parsed.keySet();
            Set<String> unknownSections = new java.util.HashSet<>(inputSections);
            unknownSections.removeAll(SettingsMdTemplate.ALL_SECTIONS);
            if (!unknownSections.isEmpty()) {
                throw new BusinessException(400, "未知段: "
                        + String.join(", ", new java.util.TreeSet<>(unknownSections))
                        + "（仅支持 profile/blog/techstack/experience）");
            }
            // 缺段检查（4 段必须齐全，不允许只传部分）
            for (String section : SettingsMdTemplate.ALL_SECTIONS) {
                if (!parsed.containsKey(section)) {
                    throw new BusinessException(400, "缺少必需段: " + section);
                }
            }

            // 5. 按段校验 + 收集 errors（任意一段失败 → 全部回滚）
            validateProfile(parsed);
            validateBlog(parsed);
            validateTechstack(parsed);
            validateExperience(parsed);

            // 6. 4 段整体写入（同一事务内，任何一段抛异常 → 全部回滚）
            List<String> applied = new ArrayList<>();
            applied.add(applyProfile(parsed));
            applied.add(applyBlog(parsed));
            applied.add(applyTechstack(parsed));
            applied.add(applyExperience(parsed));

            log.info("[settings-md-import] 成功导入 4 段：file={} applied={}", original, applied);
            return applied;
        } finally {
            // 7. 删除临时文件（无论成功失败都删，不入 article_attachment 表）
            if (tempFile.exists()) {
                if (!tempFile.delete()) {
                    log.warn("[settings-md-import] 删除临时文件失败：path={}", tempFile.getAbsolutePath());
                }
            }
        }
    }

    // ============ Frontmatter 解析 ============

    /**
     * 解析 md 文件的 YAML frontmatter（--- 开头/结尾）。
     * body 段不解析，直接丢弃。
     *
     * @return frontmatter Map（key=section 名，value=对应 Object）
     * @throws BusinessException 400 当 frontmatter 缺失或格式错时
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String content) {
        String trimmed = content.replace("\r\n", "\n").trim();
        if (!trimmed.startsWith("---")) {
            throw new BusinessException(400, "缺少 YAML frontmatter 段（必须以 --- 开头）");
        }
        int secondDash = trimmed.indexOf("\n---", 3);
        if (secondDash < 0) {
            throw new BusinessException(400, "YAML frontmatter 未闭合（缺少第二个 ---）");
        }
        String yaml = trimmed.substring(3, secondDash).trim();
        if (yaml.isEmpty()) {
            throw new BusinessException(400, "YAML frontmatter 内容为空");
        }
        try {
            // 2026-07-01 DEV-005 fix：snakeyaml 1.30 默认 Constructor 允许反序列化任意 Java 类型
            //   （!!javax.script.ScriptEngineManager [...] 等 type tag 可触发 RCE，CVE-2022-1471 模式）
            //   改用 SafeConstructor 限制只能反序列化 Map/List/String/Number/Boolean 等基础类型
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (!(parsed instanceof Map)) {
                throw new BusinessException(400, "YAML frontmatter 顶层必须是 Map");
            }
            return (Map<String, Object>) parsed;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[settings-md-import] YAML 解析失败", e);
            throw new BusinessException(400, "YAML frontmatter 解析失败: " + e.getMessage());
        }
    }

    // ============ 4 段校验（用户决策：严格校验，模版不一致直接报错）============

    @SuppressWarnings("unchecked")
    private void validateProfile(Map<String, Object> parsed) {
        Object raw = parsed.get(SettingsMdTemplate.SECTION_PROFILE);
        if (!(raw instanceof Map)) {
            throw new BusinessException(400, "section.profile 必须是 Map，实际是 "
                    + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        String err = SettingsMdTemplate.validateFlatSection(SettingsMdTemplate.SECTION_PROFILE,
                (Map<String, Object>) raw);
        if (err != null) throw new BusinessException(400, err);
    }

    @SuppressWarnings("unchecked")
    private void validateBlog(Map<String, Object> parsed) {
        Object raw = parsed.get(SettingsMdTemplate.SECTION_BLOG);
        if (!(raw instanceof Map)) {
            throw new BusinessException(400, "section.blog 必须是 Map，实际是 "
                    + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        String err = SettingsMdTemplate.validateFlatSection(SettingsMdTemplate.SECTION_BLOG,
                (Map<String, Object>) raw);
        if (err != null) throw new BusinessException(400, err);
    }

    @SuppressWarnings("unchecked")
    private void validateTechstack(Map<String, Object> parsed) {
        Object raw = parsed.get(SettingsMdTemplate.SECTION_TECHSTACK);
        if (!(raw instanceof Map)) {
            throw new BusinessException(400, "section.techstack 必须是 Map，实际是 "
                    + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        Map<String, Object> data = (Map<String, Object>) raw;
        Object groupsObj = data.get(SettingsMdTemplate.TECHSTACK_KEY_GROUPS);
        if (!(groupsObj instanceof List)) {
            throw new BusinessException(400, "section.techstack.groups 应是数组，实际是 "
                    + (groupsObj == null ? "null" : groupsObj.getClass().getSimpleName()));
        }
        List<Object> groups = (List<Object>) groupsObj;
        if (groups.size() > SettingsMdTemplate.TECHSTACK_MAX_GROUPS) {
            throw new BusinessException(400, "section.techstack.groups 数量超过 "
                    + SettingsMdTemplate.TECHSTACK_MAX_GROUPS);
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            Object go = groups.get(gi);
            if (!(go instanceof Map)) {
                throw new BusinessException(400, "section.techstack.groups[" + gi + "] 必须是 Map");
            }
            Map<String, Object> group = (Map<String, Object>) go;
            Set<String> groupKeys = group.keySet();
            Set<String> badKeys = new java.util.HashSet<>(groupKeys);
            badKeys.removeAll(SettingsMdTemplate.TECHSTACK_GROUP_FIELDS);
            if (!badKeys.isEmpty()) {
                throw new BusinessException(400, "section.techstack.groups[" + gi
                        + "] 不允许字段: " + String.join(", ", new java.util.TreeSet<>(badKeys)));
            }
            Object itemsObj = group.get("items");
            if (itemsObj != null) {
                if (!(itemsObj instanceof List)) {
                    throw new BusinessException(400, "section.techstack.groups[" + gi + "].items 应是数组");
                }
                List<Object> items = (List<Object>) itemsObj;
                if (items.size() > SettingsMdTemplate.TECHSTACK_MAX_ITEMS_PER_GROUP) {
                    throw new BusinessException(400, "section.techstack.groups[" + gi
                            + "].items 数量超过 " + SettingsMdTemplate.TECHSTACK_MAX_ITEMS_PER_GROUP);
                }
                for (int ii = 0; ii < items.size(); ii++) {
                    Object io = items.get(ii);
                    if (!(io instanceof Map)) {
                        throw new BusinessException(400, "section.techstack.groups[" + gi + "].items["
                                + ii + "] 必须是 Map");
                    }
                    Map<String, Object> item = (Map<String, Object>) io;
                    Set<String> itemKeys = item.keySet();
                    Set<String> badItemKeys = new java.util.HashSet<>(itemKeys);
                    badItemKeys.removeAll(SettingsMdTemplate.TECHSTACK_ITEM_FIELDS);
                    if (!badItemKeys.isEmpty()) {
                        throw new BusinessException(400, "section.techstack.groups[" + gi + "].items["
                                + ii + "] 不允许字段: " + String.join(", ", new java.util.TreeSet<>(badItemKeys)));
                    }
                    if (!item.containsKey("name")) {
                        throw new BusinessException(400, "section.techstack.groups[" + gi + "].items["
                                + ii + "] 缺字段: name");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateExperience(Map<String, Object> parsed) {
        Object raw = parsed.get(SettingsMdTemplate.SECTION_EXPERIENCE);
        if (!(raw instanceof Map)) {
            throw new BusinessException(400, "section.experience 必须是 Map，实际是 "
                    + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        Map<String, Object> data = (Map<String, Object>) raw;
        Object itemsObj = data.get(SettingsMdTemplate.EXPERIENCE_KEY_ITEMS);
        if (!(itemsObj instanceof List)) {
            throw new BusinessException(400, "section.experience.items 应是数组，实际是 "
                    + (itemsObj == null ? "null" : itemsObj.getClass().getSimpleName()));
        }
        List<Object> items = (List<Object>) itemsObj;
        if (items.size() > SettingsMdTemplate.EXPERIENCE_MAX_ITEMS) {
            throw new BusinessException(400, "section.experience.items 数量超过 "
                    + SettingsMdTemplate.EXPERIENCE_MAX_ITEMS);
        }
        for (int i = 0; i < items.size(); i++) {
            Object io = items.get(i);
            if (!(io instanceof Map)) {
                throw new BusinessException(400, "section.experience.items[" + i + "] 必须是 Map");
            }
            Map<String, Object> item = (Map<String, Object>) io;
            Set<String> itemKeys = item.keySet();
            Set<String> badItemKeys = new java.util.HashSet<>(itemKeys);
            badItemKeys.removeAll(SettingsMdTemplate.EXPERIENCE_ITEM_FIELDS);
            if (!badItemKeys.isEmpty()) {
                throw new BusinessException(400, "section.experience.items[" + i
                        + "] 不允许字段: " + String.join(", ", new java.util.TreeSet<>(badItemKeys)));
            }
            if (!item.containsKey("title")) {
                throw new BusinessException(400, "section.experience.items[" + i + "] 缺字段: title");
            }
        }
    }

    // ============ 4 段应用（事务边界内）============

    private String applyProfile(Map<String, Object> parsed) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get(SettingsMdTemplate.SECTION_PROFILE);
        User exist = userMapper.selectById(1L);
        if (exist == null) throw new BusinessException(1005, "用户不存在");

        UpdateWrapper<User> uw = new UpdateWrapper<>();
        uw.eq("id", 1L);
        boolean changed = false;
        for (String field : SettingsMdTemplate.PROFILE_FIELDS) {
            if (data.containsKey(field)) {
                uw.set(fieldToColumn(field), data.get(field));
                changed = true;
            }
        }
        if (!changed) return SettingsMdTemplate.SECTION_PROFILE;  // 空 body
        uw.set("updated_at", LocalDateTime.now());
        userMapper.update(null, uw);
        return SettingsMdTemplate.SECTION_PROFILE;
    }

    /** user 实体字段 → DB 列名映射（lombok @Data 字段与 DB 列名一致，但 avatar/bio 等是同名字段）*/
    private static String fieldToColumn(String field) {
        // 当前 entity 与 DB 列名一致（snake_case 自动映射走 MP 默认），直接返回
        return field;
    }

    private String applyBlog(Map<String, Object> parsed) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get(SettingsMdTemplate.SECTION_BLOG);
        siteSettingsService.merge(SiteSettingsService.SECTION_BLOG, data);
        return SettingsMdTemplate.SECTION_BLOG;
    }

    private String applyTechstack(Map<String, Object> parsed) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get(SettingsMdTemplate.SECTION_TECHSTACK);
        siteSettingsService.merge(SiteSettingsService.SECTION_TECHSTACK, data);
        return SettingsMdTemplate.SECTION_TECHSTACK;
    }

    private String applyExperience(Map<String, Object> parsed) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get(SettingsMdTemplate.SECTION_EXPERIENCE);
        siteSettingsService.merge(SiteSettingsService.SECTION_EXPERIENCE, data);
        return SettingsMdTemplate.SECTION_EXPERIENCE;
    }
}
