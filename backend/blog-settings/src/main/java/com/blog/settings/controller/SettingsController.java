package com.blog.settings.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.auth.entity.User;
import com.blog.auth.mapper.UserMapper;
import com.blog.common.Result;
import com.blog.settings.service.SiteSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

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
@RestController
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UserMapper userMapper;
    private final SiteSettingsService siteSettingsService;

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
            data.put("location", user.getLocation() != null ? user.getLocation() : "");
        }
        return Result.success(data);
    }

    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        User user = userMapper.selectById(1L);
        if (user == null) return Result.error(1005, "用户不存在");
        if (body.containsKey("nickname")) {
            String nickname = body.get("nickname");
            if (nickname != null && nickname.length() > 50) {
                return Result.error(400, "昵称长度不能超过 50 字符");
            }
            user.setNickname(nickname);
        }
        if (body.containsKey("email")) {
            String email = body.get("email");
            if (email != null && !email.isEmpty() && !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                return Result.error(400, "邮箱格式不正确");
            }
            user.setEmail(email);
        }
        if (body.containsKey("avatar")) user.setAvatar(body.get("avatar"));
        if (body.containsKey("bio")) {
            String bio = body.get("bio");
            if (bio != null && bio.length() > 500) {
                return Result.error(400, "简介长度不能超过 500 字符");
            }
            user.setBio(bio);
        }
        if (body.containsKey("location")) {
            String loc = body.get("location");
            if (loc != null && loc.length() > 50) {
                return Result.error(400, "地址长度不能超过 50 字符");
            }
            user.setLocation(loc);
        }
        userMapper.updateById(user);
        return Result.success();
    }

    @PutMapping("/password")
    public Result<Void> updatePassword(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");
        if (oldPwd == null || oldPwd.isEmpty()) return Result.error(400, "旧密码不能为空");
        if (newPwd == null || newPwd.isEmpty()) return Result.error(400, "新密码不能为空");
        if (newPwd.length() < 6) return Result.error(400, "新密码至少 6 个字符");
        if (newPwd.length() > 50) return Result.error(400, "新密码不能超过 50 个字符");
        User user = userMapper.selectById(1L);
        if (user == null) return Result.error(1005, "用户不存在");
        // 2026-06-12 修复：原逻辑硬编码比对明文 "123456"，新密码直接 setPasswordHash(newPwd) 明文落库
        // ——会导致下次登录 BCrypt.checkpw 失败，用户改完密码无法登录
        // 修复：用 BCrypt 校验旧密码，BCrypt hash 新密码
        if (!BCrypt.checkpw(oldPwd, user.getPasswordHash())) {
            return Result.error(1001, "旧密码不正确");
        }
        user.setPasswordHash(BCrypt.hashpw(newPwd, BCrypt.gensalt()));
        userMapper.updateById(user);
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
        siteSettingsService.merge(SiteSettingsService.SECTION_PREFERENCES, body);
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
        return Result.success(siteSettingsService.get(SiteSettingsService.SECTION_EXPERIENCE));
    }

    // ========== 文件上传 ==========

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest request) throws IOException {
        if (file == null || file.isEmpty()) return Result.error(400, "文件为空");
        if (file.getSize() > 5 * 1024 * 1024) {
            return Result.error(400, "文件大小不能超过 5MB");
        }
        String original = file.getOriginalFilename();
        String ext = original != null && original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!ext.matches("\\.(png|jpg|jpeg|gif|webp|bmp|ico)")) {
            return Result.error(400, "不支持的文件类型，仅允许图片格式 (png/jpg/jpeg/gif/webp/bmp/ico)");
        }
        // 2026-06-12 修复：原 upload 只校验扩展名，没校验文件 magic bytes，攻击者可上传 "evil.png"（实际是 HTML/JS）伪装图片
        // 修复：读前 12 字节验证 magic bytes
        byte[] head = new byte[12];
        try {
            int read = file.getInputStream().read(head);
            if (read < 8) return Result.error(400, "文件格式异常或文件过小");
        } catch (IOException e) {
            return Result.error(400, "文件读取失败");
        }
        if (!isImageMagicBytes(head, ext)) {
            return Result.error(400, "文件内容与扩展名不符，请上传真正的图片");
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        String monthDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        File dir = new File(uploadDir, monthDir);
        if (!dir.exists()) dir.mkdirs();
        File dest = new File(dir, filename);
        file.transferTo(dest);
        Map<String, Object> data = new HashMap<>();
        data.put("url", "/uploads/" + monthDir + "/" + filename);
        data.put("filename", filename);
        data.put("size", file.getSize());
        return Result.success(data);
    }

    /**
     * 2026-06-12 新增：图片 magic bytes 校验——按扩展名匹配前几个字节
     * PNG: 89 50 4E 47 0D 0A 1A 0A
     * JPEG: FF D8 FF
     * GIF: 47 49 46 38 (GIF8)
     * WEBP: 52 49 46 46 ?? ?? ?? ?? 57 45 42 50 (RIFF....WEBP)
     * BMP: 42 4D
     * ICO: 00 00 01 00
     */
    private boolean isImageMagicBytes(byte[] head, String ext) {
        if (head == null || head.length < 4) return false;
        switch (ext) {
            case ".png":
                return head[0] == (byte)0x89 && head[1] == (byte)0x50 && head[2] == (byte)0x4E && head[3] == (byte)0x47;
            case ".jpg":
            case ".jpeg":
                return head[0] == (byte)0xFF && head[1] == (byte)0xD8 && head[2] == (byte)0xFF;
            case ".gif":
                return head[0] == (byte)0x47 && head[1] == (byte)0x49 && head[2] == (byte)0x46 && head[3] == (byte)0x38;
            case ".bmp":
                return head[0] == (byte)0x42 && head[1] == (byte)0x4D;
            case ".ico":
                return head[0] == 0x00 && head[1] == 0x00 && head[2] == 0x01 && head[3] == 0x00;
            case ".webp":
                // RIFF + 4 bytes size + WEBP
                return head[0] == (byte)0x52 && head[1] == (byte)0x49 && head[2] == (byte)0x46 && head[3] == (byte)0x46
                    && head.length >= 12
                    && head[8] == (byte)0x57 && head[9] == (byte)0x45 && head[10] == (byte)0x42 && head[11] == (byte)0x50;
            default:
                return false;
        }
    }
}
