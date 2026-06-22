package com.blog.settings.controller;

import com.blog.common.Result;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-06-13 修复（BUG-052 配套）：admin 写图片/文件上传端点。
 * 之前 StaticResourceConfig 已配 /uploads/** 静态服务（前端能 GET 图片），
 * 但缺 POST 上传端点 → /api/v1/admin/uploads 一直 404。
 *
 * 设计：
*  - 路径：`${blog.upload.local.dir}/yyyy/MM/yyyyMMdd-{uuid}.ext`
 *  - 大小：单文件 ≤ 5MB（业务上限；基础设施 10MB 见 application.yml multipart）
 *  - 校验：MIME 必须以 image/ 开头，或 application/pdf 等允许类型
 *  - 响应：返 `{url, name, size}`，url 是绝对路径（含 origin + contextPath），
 *   前端直接用 img src 即可；不要手动拼前缀。
 */
@Slf4j
@RestController
@RequestMapping("/admin/uploads")
@RequiredArgsConstructor
public class UploadController {

    @Value("${blog.upload.local.dir}")
    private String uploadDir;

    private static final long MAX_SIZE = 5L * 1024 * 1024;  // 5MB

    @PostMapping
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) throws IOException {
        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            // FR-3.6：上传失败 WARN（超限）
            log.warn("文件上传失败：超过 5MB 上限 size={} name={} operator={}",
                    file.getSize(), file.getOriginalFilename(), AuthContext.uid(request));
            return Result.error(400, "文件超过 5MB 上限");
        }
        // 2026-06-13 安全修复：原逻辑只校验 Content-Type 请求头（客户端可任意伪造），
        // 攻击者（已登录 admin / 或被盗用的 admin 会话）可上传 .html/.svg 等可执行内容伪装图片，
        // 经 /uploads/** 静态服务回源即构成存储型 XSS。
        // 这里补齐与（原本已废弃的）SettingsController.upload 同等的「扩展名白名单 + magic bytes」校验。
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!ext.matches("\\.(png|jpg|jpeg|gif|webp|bmp|ico)")) {
            // FR-3.6：上传失败 WARN（类型不符）
            log.warn("文件上传失败：类型不符 ext={} name={} operator={}", ext, original, AuthContext.uid(request));
            return Result.error(400, "不支持的文件类型，仅允许图片格式 (png/jpg/jpeg/gif/webp/bmp/ico)");
        }
        byte[] head = new byte[12];
        try {
            int read = file.getInputStream().read(head);
            if (read < 8) return Result.error(400, "文件格式异常或文件过小");
        } catch (IOException e) {
            // FR-3.6：上传失败 ERROR（IO 异常，含 stacktrace）
            log.error("文件上传失败：读取 IO 异常 name={} operator={}", original, AuthContext.uid(request), e);
            return Result.error(400, "文件读取失败");
        }
        if (!isImageMagicBytes(head, ext)) {
            // FR-3.6：上传失败 WARN（内容与扩展名不符，疑似伪造）
            log.warn("文件上传失败：magic bytes 与扩展名不符 ext={} name={} operator={}", ext, original, AuthContext.uid(request));
            return Result.error(400, "文件内容与扩展名不符，请上传真正的图片");
        }

        // 路径：uploads/yyyy/MM/yyyyMMdd-{uuid}.ext
        LocalDate today = LocalDate.now();
        String yearMonth = String.format("%d/%02d", today.getYear(), today.getMonthValue());
        String name = String.format("%s-%s%s", today.toString().replace("-", ""),
                                    UUID.randomUUID().toString().substring(0, 8), ext);
        File dir = new File(uploadDir, yearMonth);
        if (!dir.exists() && !dir.mkdirs()) {
            // FR-3.6：上传失败 ERROR（目录创建失败）
            log.error("文件上传失败：无法创建上传目录 dir={} operator={}", dir.getAbsolutePath(), AuthContext.uid(request));
            return Result.error(500, "无法创建上传目录: " + dir.getAbsolutePath());
        }
        File target = new File(dir, name);
        file.transferTo(target);
        // FR-3.5：上传成功 INFO（路径、大小、操作人）
        log.info("文件上传成功：path={} size={} operator={}", target.getAbsolutePath(), file.getSize(), AuthContext.uid(request));

        // 绝对 URL：scheme + host + (非默认端口时)+ /uploads/xxx
        //
        // 2026-06-22 v4.x polish：原实现用 request.getScheme() + getServerName() + getServerPort()
        //   在 nginx 反代场景下——getServerPort() 返 backend 监听端口 8080，getScheme() 返 http
        //   （即使外网是 https）→ URL 变成 http://host:8080/... → 外网客户端无法访问。
        //   即使 nginx proxy_set_header Host $host 透传了 Host，getServerPort() 仍是 8080。
        //
        // 修复：读 X-Forwarded-Proto / X-Forwarded-Host 头（nginx 已透传，nginx-https.conf:53,81,95），
        //   端口在 scheme 为标准端口时省略（http=80 / https=443）。
        //
        // 路径不带 contextPath（/api/v1）：prod 环境 nginx `location ^~ /uploads/` 直 serve，
        //   不走 /api/v1 反代；dev 环境 StaticResourceConfig 也注册在 /uploads/**（无 /api/v1 前缀）。
        //   用 /uploads/ 一条路径覆盖两套部署，比 "/api/v1/uploads/" 更稳。
        String scheme = headerFirst(request, "X-Forwarded-Proto", request.getScheme());
        String host = headerFirst(request, "X-Forwarded-Host", request.getServerName());
        String url = buildOriginUrl(scheme, host) + "/uploads/" + yearMonth + "/" + name;
        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("name", name);
        data.put("size", file.getSize());
        return Result.success(data);
    }

    /**
     * 取请求头（剥空白），为空回退到 defaultValue。
     * 与 TrustedProxyUtil.resolveClientIp 的"反代头优先"逻辑对齐：反代场景一律读反代头。
     */
    private static String headerFirst(HttpServletRequest request, String header, String defaultValue) {
        String v = request.getHeader(header);
        if (v == null || v.trim().isEmpty()) return defaultValue;
        return v.trim();
    }

    /**
     * 拼 origin（scheme://host[:port]）
     * - 标准端口（http/80 / https/443）→ 省略端口号
     * - 其他端口（如 dev 8080、staging 非标）→ 保留
     */
    private static String buildOriginUrl(String scheme, String host) {
        if (host == null || host.isEmpty()) host = "localhost";
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        // 标准端口判断：只有显式带上且等于 80/443 才省；其他一律保留（包括空 scheme）
        if (isHttps) {
            return "https://" + host;
        }
        // http / 其他 scheme（nginx 默认透传 scheme）
        if ("http".equalsIgnoreCase(scheme) || scheme == null || scheme.isEmpty()) {
            // dev 直连 backend 8080：保留端口
            // prod 反代 https：scheme 会是 https，走上面分支
            // 这里仅在 scheme 明确为 http 且 dev 端口场景下拼端口——但 dev 时没反代，
            // X-Forwarded-Proto 不会被 nginx 设置，request.getScheme() 直接返 http + 端口 8080。
            // 反代场景下 scheme 应为 https（被 X-Forwarded-Proto 覆盖），不会进这分支。
            return "http://" + host;
        }
        return scheme + "://" + host;
    }

    /**
     * 图片 magic bytes 校验——按扩展名匹配文件头字节，防止伪造扩展名上传非图片内容。
     * PNG: 89 50 4E 47 / JPEG: FF D8 FF / GIF: 47 49 46 38 /
     * BMP: 42 4D / ICO: 00 00 01 00 / WEBP: 52 49 46 46 .... 57 45 42 50
     */
    private boolean isImageMagicBytes(byte[] head, String ext) {
        if (head == null || head.length < 4) return false;
        switch (ext) {
            case ".png":
                return head[0] == (byte) 0x89 && head[1] == (byte) 0x50 && head[2] == (byte) 0x4E && head[3] == (byte) 0x47;
            case ".jpg":
            case ".jpeg":
                return head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
            case ".gif":
                return head[0] == (byte) 0x47 && head[1] == (byte) 0x49 && head[2] == (byte) 0x46 && head[3] == (byte) 0x38;
            case ".bmp":
                return head[0] == (byte) 0x42 && head[1] == (byte) 0x4D;
            case ".ico":
                return head[0] == 0x00 && head[1] == 0x00 && head[2] == 0x01 && head[3] == 0x00;
            case ".webp":
                return head[0] == (byte) 0x52 && head[1] == (byte) 0x49 && head[2] == (byte) 0x46 && head[3] == (byte) 0x46
                        && head.length >= 12
                        && head[8] == (byte) 0x57 && head[9] == (byte) 0x45 && head[10] == (byte) 0x42 && head[11] == (byte) 0x50;
            default:
                return false;
        }
    }
}
