package com.blog.settings.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.common.Result;
import com.blog.common.web.AuthContext;
import com.blog.settings.service.SettingsMdImporter;
import com.blog.settings.service.SiteSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 设置 + 上传 + 文件管理
 *
 * 2026-06-08 重构：
 *  - 原 5 个 static Map（blog/social/preferences/theme/advanced）→ 走 SiteSettingsService（DB + Redis 缓存）
 *  - profile 仍走 user 表（UserMapper）
 *  - 启动时 SiteSettingsService.@PostConstruct 兜底默认值
 *
 * 简版：6 个 tab 端点 + 单文件上传（本地磁盘）
 * social / preferences / blog / theme / advanced 全部持久化到 site_settings 表
 */
@Slf4j
@RestController
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UserMapper userMapper;
    private final SiteSettingsService siteSettingsService;
    private final SettingsMdImporter settingsMdImporter;

    /**
     * FR-3.7：配置修改 INFO（含 key 名 + 操作人）。
     * 通过 RequestContextHolder 取当前请求里的操作人 uid（AdminAuthFilter 已写入），
     * 避免给每个 PUT 方法都加 HttpServletRequest 形参。
     */
    private void logSettingChange(String key) {
        Object operator = null;
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) operator = AuthContext.uid(attrs.getRequest());
        } catch (Exception ignore) {
            // 取不到操作人不影响主流程
        }
        log.info("配置修改：key={} operator={}", key, operator);
    }

    // 修复（2026-06-07）：原 static final + System.getProperty("user.home") 在容器里是 /root/blog-uploads
    // 改成读 yml/env，prod 容器用 /data/uploads（volume 持久化）
    @Value("${blog.upload.local.dir:/root/blog-uploads}")
    private String uploadDir;

    @PostConstruct
    public void init() {
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();
    }

    // ========== 个人资料（走 user 表） ==========

    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        User user = userMapper.selectById(1L);
        Map<String, Object> data = new HashMap<>();
        if (user != null) {
            data.put("id", user.getId());
            data.put("username", user.getUsername());
            data.put("nickname", user.getNickname());
            data.put("email", user.getEmail());
            data.put("avatar", user.getAvatar() != null ? user.getAvatar() : "");
            data.put("bio", user.getBio() != null ? user.getBio() : "");
            data.put("intro", user.getIntro() != null ? user.getIntro() : "");
            data.put("quote", user.getQuote() != null ? user.getQuote() : "");
            data.put("footerText", user.getFooterText() != null ? user.getFooterText() : "");
            data.put("location", user.getLocation() != null ? user.getLocation() : "");
        }
        return Result.success(data);
    }

    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        // 2026-06-15 修复：原实现 selectById + 字段级 setXxx + updateById(user)——
        // MyBatis-Plus updateById 默认**全字段更新**，会把 user.password_hash 也写回数据库。
        // 即使本次没动密码字段，只要前面有人改过密码（落库了正确 BCrypt hash），
        // 这一步 updateById 也会把 password_hash 一并更新——看似无害，但场景一旦变成
        //   "前端 PUT /admin/settings/profile 时 body 里包含 password_hash 字段（被覆盖）"
        // 就会把 hash 改成错的旧值，导致后续登录失败。
        //
        // 修法：改用 UpdateWrapper + set("col", val) 严格只更新 body 里出现的列，
        // 不依赖实体的 select-then-update 模式，杜绝 password_hash 等敏感字段被波及。
        User exist = userMapper.selectById(1L);
        if (exist == null) return Result.error(1005, "用户不存在");

        UpdateWrapper<User> uw = new UpdateWrapper<>();
        uw.eq("id", 1L);
        boolean changed = false;

        if (body.containsKey("nickname")) {
            String nickname = body.get("nickname");
            if (nickname != null && nickname.length() > 50) {
                return Result.error(400, "昵称长度不能超过 50 字符");
            }
            uw.set("nickname", nickname);
            changed = true;
        }
        if (body.containsKey("email")) {
            String email = body.get("email");
            if (email != null && !email.isEmpty() && !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                return Result.error(400, "邮箱格式不正确");
            }
            uw.set("email", email);
            changed = true;
        }
        if (body.containsKey("avatar")) {
            uw.set("avatar", body.get("avatar"));
            changed = true;
        }
        if (body.containsKey("bio")) {
            String bio = body.get("bio");
            if (bio != null && bio.length() > 500) {
                return Result.error(400, "简介长度不能超过 500 字符");
            }
            uw.set("bio", bio);
            changed = true;
        }
        if (body.containsKey("intro")) {
            String intro = body.get("intro");
            if (intro != null && intro.length() > 2000) {
                return Result.error(400, "个人介绍长度不能超过 2000 字符");
            }
            uw.set("intro", intro);
            changed = true;
        }
        if (body.containsKey("quote")) {
            String quote = body.get("quote");
            if (quote != null && quote.length() > 500) {
                return Result.error(400, "引用语长度不能超过 500 字符");
            }
            uw.set("quote", quote);
            changed = true;
        }
        if (body.containsKey("footerText")) {
            String footerText = body.get("footerText");
            if (footerText != null && footerText.length() > 500) {
                return Result.error(400, "底部文案长度不能超过 500 字符");
            }
            uw.set("footer_text", footerText);
            changed = true;
        }
        if (body.containsKey("location")) {
            String loc = body.get("location");
            if (loc != null && loc.length() > 50) {
                return Result.error(400, "地址长度不能超过 50 字符");
            }
            uw.set("location", loc);
            changed = true;
        }
        // 防御性兜底：body 里即便带上 password_hash / role / username 等敏感字段也忽略
        // ——只允许改以上 5 个白名单字段。这是这一轮 password_hash 被覆盖的根本防御。

        if (!changed) return Result.success();  // 没东西可改直接返回 200
        // UpdateWrapper + update(null, uw)（实体为 null）不触发 MetaObjectHandler 自动填充，手动刷新
        uw.set("updated_at", LocalDateTime.now());
        userMapper.update(null, uw);
        logSettingChange("profile");
        return Result.success();
    }

    @PutMapping("/password")
    public Result<Void> updatePassword(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");
        if (oldPwd == null || oldPwd.isEmpty()) return Result.error(400, "旧密码不能为空");
        if (newPwd == null || newPwd.isEmpty()) return Result.error(400, "新密码不能为空");
        // 2026-06-22 v4.x polish：统一密码强度策略——admin 后台改密与 /auth/me/password 改密
        //   保持完全一致：8-64 位（避开 BCrypt 72 字节截断边界，留 8 字节余量）。
        //   原"6-50"是早期简化阈值，admin 后台策略松于用户自主改密既无业务理由也有混淆，
        //   统一到 8 位起，统一文案。
        if (newPwd.length() < 8 || newPwd.length() > 64) {
            return Result.error(400, "新密码长度须在 8-64 位之间");
        }
        User user = userMapper.selectById(1L);
        if (user == null) return Result.error(1005, "用户不存在");
        // 2026-06-12 修复：原逻辑硬编码比对明文 "123456"，新密码直接 setPasswordHash(newPwd) 明文落库
        // ——会导致下次登录 BCrypt.checkpw 失败，用户改完密码无法登录
        // 修复：用 BCrypt 校验旧密码，BCrypt hash 新密码
        if (!BCrypt.checkpw(oldPwd, user.getPasswordHash())) {
            return Result.error(1001, "旧密码不正确");
        }
        // 2026-06-15 修复：用 UpdateWrapper 只 set password_hash + updated_at，
        // 避免 updateById 全字段更新（虽然此处就是要改 password_hash，但后续如果加字段
        // 一并写到 user 对象里时会一并被 UPDATE——白名单式更新更稳）。
        String newHash = BCrypt.hashpw(newPwd, BCrypt.gensalt());
        UpdateWrapper<User> uw = new UpdateWrapper<>();
        uw.eq("id", 1L).set("password_hash", newHash).set("updated_at", LocalDateTime.now());
        userMapper.update(null, uw);
        // FR-3.7 / FR-2.5：只记录"密码已修改"事件，绝不打印新旧密码 / hash
        logSettingChange("password");
        return Result.success();
    }

    // ========== 5 个 settings tab（全部走 SiteSettingsService） ==========

    @GetMapping("/social")
    public Result<Map<String, Object>> getSocial() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_SOCIAL));
    }

    @PutMapping("/social")
    public Result<Map<String, Object>> updateSocial(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        // 2026-06-12 修复：website 长度上限
        if (body.containsKey("github") && body.get("github") != null) {
            String s = (String) body.get("github");
            if (s.length() > 500) return Result.error(400, "github URL 不能超过 500 字符");
        }
        if (body.containsKey("twitter") && body.get("twitter") != null) {
            String s = (String) body.get("twitter");
            if (s.length() > 500) return Result.error(400, "twitter URL 不能超过 500 字符");
        }
        siteSettingsService.merge(SiteSettingsService.SECTION_SOCIAL, body);
        logSettingChange(SiteSettingsService.SECTION_SOCIAL);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_SOCIAL));
    }

    @GetMapping("/preferences")
    public Result<Map<String, Object>> getPreferences() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_PREFERENCES));
    }

    @PutMapping("/preferences")
    public Result<Map<String, Object>> updatePreferences(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        if (body.containsKey("language") && body.get("language") != null) {
            String l = (String) body.get("language");
            if (!Arrays.asList("zh-CN", "en").contains(l)) {
                return Result.error(400, "language 必须是 zh-CN / en");
            }
        }
        // 2026-06-27 DEV-002：偏好设置字段白名单校验
        if (body.containsKey("density") && body.get("density") != null) {
            String d = String.valueOf(body.get("density"));
            if (!Arrays.asList("comfortable", "compact").contains(d)) {
                return Result.error(400, "density 必须是 comfortable / compact");
            }
        }
        if (body.containsKey("codeTheme") && body.get("codeTheme") != null) {
            String c = String.valueOf(body.get("codeTheme"));
            if (!Arrays.asList("github", "monokai", "nord").contains(c)) {
                return Result.error(400, "codeTheme 必须是 github / monokai / nord");
            }
        }
        if (body.containsKey("timezone") && body.get("timezone") != null) {
            String tz = String.valueOf(body.get("timezone"));
            try {
                java.time.ZoneId.of(tz);
            } catch (Exception e) {
                return Result.error(400, "timezone 不是有效的时区 ID");
            }
        }
        siteSettingsService.merge(SiteSettingsService.SECTION_PREFERENCES, body);
        logSettingChange(SiteSettingsService.SECTION_PREFERENCES);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_PREFERENCES));
    }

    /** 站点信息：title / subtitle / description / copyright / logo */
    @GetMapping("/blog")
    public Result<Map<String, Object>> getBlog() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_BLOG));
    }

    @PutMapping("/blog")
    public Result<Map<String, Object>> updateBlog(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        // 2026-06-12 修复：长度上限
        if (body.containsKey("title") && body.get("title") != null && ((String) body.get("title")).length() > 200) {
            return Result.error(400, "title 不能超过 200 字符");
        }
        if (body.containsKey("description") && body.get("description") != null && ((String) body.get("description")).length() > 500) {
            return Result.error(400, "description 不能超过 500 字符");
        }
        if (body.containsKey("copyright") && body.get("copyright") != null && ((String) body.get("copyright")).length() > 200) {
            return Result.error(400, "copyright 不能超过 200 字符");
        }
        if (body.containsKey("logo") && body.get("logo") != null && ((String) body.get("logo")).length() > 500) {
            return Result.error(400, "logo URL 不能超过 500 字符");
        }
        siteSettingsService.merge(SiteSettingsService.SECTION_BLOG, body);
        logSettingChange(SiteSettingsService.SECTION_BLOG);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_BLOG));
    }

    @GetMapping("/theme")
    public Result<Map<String, Object>> getTheme() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_THEME));
    }

    @PutMapping("/theme")
    public Result<Map<String, Object>> updateTheme(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        // 2026-06-12 修复：原 PUT 不校验字段白名单，theme.color 接 javascript:alert(1) 也能存
        if (body.containsKey("mode")) {
            String mode = String.valueOf(body.get("mode"));
            if (!Arrays.asList("auto", "light", "dark").contains(mode)) {
                return Result.error(400, "mode 必须是 auto/light/dark");
            }
        }
        if (body.containsKey("primaryColor") || body.containsKey("accentColor")) {
            // 简单 hex 校验：#RRGGBB
            String[] colorFields = {"primaryColor", "accentColor"};
            for (String f : colorFields) {
                if (body.containsKey(f)) {
                    String c = String.valueOf(body.get(f));
                    if (!c.matches("^#[0-9a-fA-F]{6}$")) {
                        return Result.error(400, f + " 必须是 #RRGGBB 格式的 hex 颜色");
                    }
                }
            }
        }
        if (body.containsKey("fontFamily")) {
            String ff = String.valueOf(body.get("fontFamily"));
            if (!Arrays.asList("serif", "sans", "mono").contains(ff)) {
                return Result.error(400, "fontFamily 必须是 serif/sans/mono");
            }
        }
        siteSettingsService.merge(SiteSettingsService.SECTION_THEME, body);
        logSettingChange(SiteSettingsService.SECTION_THEME);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_THEME));
    }

    @GetMapping("/advanced")
    public Result<Map<String, Object>> getAdvanced() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_ADVANCED));
    }

    @PutMapping("/advanced")
    public Result<Map<String, Object>> updateAdvanced(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        siteSettingsService.merge(SiteSettingsService.SECTION_ADVANCED, body);
        logSettingChange(SiteSettingsService.SECTION_ADVANCED);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_ADVANCED));
    }

    // ========== 2026-06-13 新增：技术栈 / 个人经历（关于我页面维护） ==========

    /** 技术栈：{ groups: [ { label, items: [ { name, dim } ] } ] } */
    @GetMapping("/techstack")
    public Result<Map<String, Object>> getTechstack() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_TECHSTACK));
    }

    @PutMapping("/techstack")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> updateTechstack(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        Object groupsObj = body.get("groups");
        if (!(groupsObj instanceof List)) {
            return Result.error(400, "groups 必须是数组");
        }
        List<Object> groups = (List<Object>) groupsObj;
        if (groups.size() > 20) return Result.error(400, "分组数量不能超过 20 个");
        for (Object go : groups) {
            if (!(go instanceof Map)) return Result.error(400, "分组格式不正确");
            Map<String, Object> g = (Map<String, Object>) go;
            Object label = g.get("label");
            if (label != null && String.valueOf(label).length() > 50) {
                return Result.error(400, "分组标题不能超过 50 字符");
            }
            Object itemsObj = g.get("items");
            if (itemsObj != null && !(itemsObj instanceof List)) {
                return Result.error(400, "items 必须是数组");
            }
            if (itemsObj instanceof List) {
                List<Object> items = (List<Object>) itemsObj;
                if (items.size() > 50) return Result.error(400, "单个分组的标签不能超过 50 个");
                for (Object io : items) {
                    if (!(io instanceof Map)) return Result.error(400, "标签格式不正确");
                    Map<String, Object> it = (Map<String, Object>) io;
                    Object name = it.get("name");
                    if (name == null || String.valueOf(name).trim().isEmpty()) {
                        return Result.error(400, "标签名称不能为空");
                    }
                    if (String.valueOf(name).length() > 50) {
                        return Result.error(400, "标签名称不能超过 50 字符");
                    }
                }
            }
        }
        // 全量替换（编辑器每次提交完整结构）
        siteSettingsService.merge(SiteSettingsService.SECTION_TECHSTACK, body);
        logSettingChange(SiteSettingsService.SECTION_TECHSTACK);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_TECHSTACK));
    }

    /** 个人经历：{ items: [ { time, title, desc } ] } */
    @GetMapping("/experience")
    public Result<Map<String, Object>> getExperience() {
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_EXPERIENCE));
    }

    @PutMapping("/experience")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> updateExperience(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        Object itemsObj = body.get("items");
        if (!(itemsObj instanceof List)) {
            return Result.error(400, "items 必须是数组");
        }
        List<Object> items = (List<Object>) itemsObj;
        if (items.size() > 50) return Result.error(400, "经历条数不能超过 50 条");
        for (Object io : items) {
            if (!(io instanceof Map)) return Result.error(400, "经历格式不正确");
            Map<String, Object> it = (Map<String, Object>) io;
            Object title = it.get("title");
            if (title == null || String.valueOf(title).trim().isEmpty()) {
                return Result.error(400, "经历标题不能为空");
            }
            if (String.valueOf(title).length() > 200) {
                return Result.error(400, "经历标题不能超过 200 字符");
            }
            Object time = it.get("time");
            if (time != null && String.valueOf(time).length() > 50) {
                return Result.error(400, "时间不能超过 50 字符");
            }
            Object desc = it.get("desc");
            if (desc != null && String.valueOf(desc).length() > 500) {
                return Result.error(400, "描述不能超过 500 字符");
            }
        }
        siteSettingsService.merge(SiteSettingsService.SECTION_EXPERIENCE, body);
        logSettingChange(SiteSettingsService.SECTION_EXPERIENCE);
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_EXPERIENCE));
    }

    // 2026-06-21 清理：原 POST /admin/settings/upload 端点已删除——前端所有上传（avatar/cover/markdown）
    // 全部走 UploadController(/admin/uploads)，本方法无任何调用方。唯一上传入口。

    // ========== 2026-07-01 DEV-005：上传 md 文档批量更新 4 段 settings ==========

    /**
     * 上传 md 文档（YAML frontmatter + 4 段：profile/blog/techstack/experience），
     * 整体原子事务写入 4 段。任一段校验失败或写入失败 → 全部回滚。
     *
     * @param file md 文件（≤2MB，仅 .md 格式）
     * @return {appliedSections: [...], count: N} 成功导入的段名列表
     */
    @PostMapping("/upload-md")
    public Result<Map<String, Object>> uploadMd(@RequestParam("file") MultipartFile file) {
        List<String> applied = settingsMdImporter.importFromMd(file);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appliedSections", applied);
        data.put("count", applied.size());
        logSettingChange("upload-md");
        return Result.success(data);
    }

    /**
     * 模拟服务报错日志（隐藏功能，需连续点击 5 次触发）
     * 用于测试日志采集系统，日志格式严格遵循 logback-spring.xml 的 LOG_PATTERN
     */
    @PostMapping("/simulate-log")
    public Result<Void> simulateLog(@RequestBody Map<String, Object> body) {
        String timestamp = (String) body.get("timestamp");
        String level = (String) body.get("level");
        String thread = (String) body.get("thread");
        String traceId = (String) body.get("traceId");
        String message = (String) body.get("message");
        Object countObj = body.get("count");

        // 参数校验
        if (message == null || message.trim().isEmpty()) {
            return Result.error(400, "错误信息不能为空");
        }
        if (level == null || !Arrays.asList("INFO", "WARN", "ERROR").contains(level)) {
            return Result.error(400, "日志级别必须是 INFO / WARN / ERROR");
        }

        int count = 1;
        if (countObj instanceof Number) {
            count = ((Number) countObj).intValue();
        }
        if (count < 1 || count > 20) {
            return Result.error(400, "打印次数必须在 1-20 之间");
        }

        // 设置 MDC traceId
        org.slf4j.MDC.put("traceId", traceId != null ? traceId : "");

        // 构建日志消息前缀
        String prefix = "[mock-simulate] ";
        String logMessage = prefix + message.trim();

        // 根据级别打印日志
        for (int i = 0; i < count; i++) {
            switch (level) {
                case "INFO":
                    log.info(logMessage);
                    break;
                case "WARN":
                    log.warn(logMessage);
                    break;
                case "ERROR":
                    log.error(logMessage);
                    break;
            }
        }

        // 清理 MDC
        org.slf4j.MDC.remove("traceId");

        log.info("模拟日志已打印：level={} count={} thread={}", level, count, thread);
        return Result.success();
    }

    /**
     * 紧急 SQL 执行（隐藏功能，需连续点击 5 次触发）
     * 仅支持 SELECT / INSERT / UPDATE / DELETE，禁止 DROP / ALTER / CREATE
     */
    @PostMapping("/exec-sql")
    public Result<Map<String, Object>> execSql(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return Result.error(400, "SQL 不能为空");
        }
        String upper = sql.trim().toUpperCase();
        // 安全校验：只允许 SELECT / INSERT / UPDATE / DELETE
        if (!upper.startsWith("SELECT") && !upper.startsWith("INSERT")
                && !upper.startsWith("UPDATE") && !upper.startsWith("DELETE")) {
            return Result.error(400, "仅支持 SELECT / INSERT / UPDATE / DELETE");
        }
        // 禁止危险关键字
        String[] forbidden = {"DROP ", "ALTER ", "CREATE ", "TRUNCATE ", "GRANT ", "REVOKE "};
        for (String kw : forbidden) {
            if (upper.contains(kw)) {
                return Result.error(400, "禁止执行: " + kw.trim());
            }
        }
        try {
            if (upper.startsWith("SELECT")) {
                List<Map<String, Object>> rows = siteSettingsService.execQuery(sql);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("rows", rows);
                data.put("count", rows.size());
                return Result.success(data);
            } else {
                int affected = siteSettingsService.execUpdate(sql);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("affected", affected);
                return Result.success(data);
            }
        } catch (Exception e) {
            log.warn("exec-sql 失败: sql={} err={}", sql, e.getMessage());
            return Result.error(500, "执行失败: " + e.getMessage());
        }
    }
}
