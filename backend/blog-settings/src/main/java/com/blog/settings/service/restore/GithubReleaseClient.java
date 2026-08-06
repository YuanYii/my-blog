package com.blog.settings.service.restore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * GitHub Release 客户端（REQ-RESTORE-2026-06-24，v5.0）
 *
 * 设计依据：docs/design/博客数据恢复方案设计.md §3 Step 1
 *
 * 职责：
 *  1. GET /repos/{repo}/releases/tags/{tag} 拿 release 元信息
 *  2. 解析 assets 数组，按 name 模式过滤（*.enc + manifest.json + SHA256SUMS）
 *  3. 逐个下载 asset 到本地 stage 目录
 *
 * 安全：
 *  - token 仅放在 Authorization header,不进 URL / 不进日志
 *  - HTTPS only（GitHub API 强制）
 *  - Accept: application/octet-stream 走 asset binary download
 *
 * 替代品：旧版 blog-restore.sh 用 gh CLI + curl fallback,新版纯 Java 无外部依赖
 */
@Slf4j
@Component
public class GithubReleaseClient {

    /** GitHub API base */
    private static final String API_BASE = "https://api.github.com";

    /** 连接超时 / 读超时 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** 关心的 asset 名前缀/后缀（其他 asset 忽略） */
    private static final Set<String> EXACT_NAMES = new HashSet<>();
    static {
        EXACT_NAMES.add("manifest.json");
        EXACT_NAMES.add("SHA256SUMS");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 拉取 release tag 下所有目标 asset 到 stageDir
     * @param token GitHub PAT (BACKUP_GITHUB_TOKEN)
     * @param repo 仓库 owner/name
     * @param tag release tag (= backup_record.tag)
     * @param stageDir 落盘目录（调用方负责创建）
     * @return 下载的 asset 文件路径列表（用于后续 SHA256 + 解密）
     * @throws IOException HTTP / IO 失败
     */
    public List<Path> downloadAssets(String token, String repo, String tag, Path stageDir)
            throws IOException {
        // 1. 拉 release metadata
        String metaUrl = API_BASE + "/repos/" + repo + "/releases/tags/" + tag;
        JsonNode root = httpGetJson(metaUrl, token);
        JsonNode assets = root.path("assets");
        if (!assets.isArray() || assets.size() == 0) {
            throw new IOException("release tag=" + tag + " 无 assets");
        }

        // 2. 逐个下载关心的 asset
        List<Path> downloaded = new ArrayList<>();
        Iterator<JsonNode> it = assets.elements();
        while (it.hasNext()) {
            JsonNode asset = it.next();
            String name = asset.path("name").asText("");
            String url = asset.path("url").asText("");  // API URL（需要 Authorization）
            if (name.isEmpty() || url.isEmpty()) continue;
            if (!shouldDownload(name)) continue;

            Path dest = stageDir.resolve(name);
            downloadAssetBinary(url, token, dest);
            downloaded.add(dest);
            log.info("[Restore-DOWNLOAD] {} 已下载 ({} bytes)", name, Files.size(dest));
        }

        if (downloaded.isEmpty()) {
            throw new IOException("release tag=" + tag + " 无任何 .enc / manifest.json / SHA256SUMS asset");
        }
        return downloaded;
    }

    /** 判断 asset 名是否需要下载 */
    private boolean shouldDownload(String name) {
        if (EXACT_NAMES.contains(name)) return true;
        return name.endsWith(".enc");
    }

    /** GET JSON（解析为 JsonNode） */
    private JsonNode httpGetJson(String url, String token) throws IOException {
        HttpURLConnection conn = openConn(url, token, "application/vnd.github+json");
        int code = conn.getResponseCode();
        try (InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (code < 200 || code >= 300) {
                String body = in == null ? "" : readBodyTail(in, 500);
                throw new IOException("GitHub API GET " + url + " 返回 " + code + ": " + body);
            }
            return objectMapper.readTree(in);
        } finally {
            conn.disconnect();
        }
    }

    /** 下载 asset 重试上限 + 每次重试间隔 */
    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_DELAY_MS = 1000L;

    /**
     * GET asset binary,带重试包装。
     *
     * 重试原因：Docker Desktop for Mac 的 VPNKit 网络层与 GitHub CDN
     * (objects.githubusercontent.com) 的 TLS 握手存在非确定性失败
     * (~10% 概率),典型表现 SSLHandshakeException / SocketException。
     * objects.githubusercontent.com 解析到 4 个 CDN IP(185.199.108-111.133),
     * DNS 轮转后下次重试通常落到能正常握手的 IP。
     *
     * 重试策略：网络类异常(SSL/Socket/Timeout/EOF/5xx) sleep 1s 重试,最多 3 次;
     * 非可重试异常(4xx 等)立即抛出不重试。
     */
    private void downloadAssetBinary(String apiUrl, String token, Path dest) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                doDownloadAssetBinary(apiUrl, token, dest);
                if (attempt > 1) {
                    log.info("[Restore-DOWNLOAD] {} 第 {} 次重试成功", dest.getFileName(), attempt);
                }
                return;
            } catch (IOException e) {
                if (!isRetryable(e) || attempt >= DOWNLOAD_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("[Restore-DOWNLOAD] {} 第 {}/{} 次下载失败({}: {}), {}ms 后重试",
                    dest.getFileName(), attempt, DOWNLOAD_MAX_ATTEMPTS,
                    e.getClass().getSimpleName(), e.getMessage(), DOWNLOAD_RETRY_DELAY_MS);
                // 清理可能已写入一半的目标文件,避免下次重试拼接旧数据
                try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
                try {
                    Thread.sleep(DOWNLOAD_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载重试被中断", ie);
                }
            }
        }
    }

    /** GET asset binary 单次尝试（Accept: application/octet-stream 才会重定向到 CDN） */
    private void doDownloadAssetBinary(String apiUrl, String token, Path dest) throws IOException {
        HttpURLConnection conn = openConn(apiUrl, token, "application/octet-stream");
        // GitHub 会返 302 到 CDN URL,HttpURLConnection 默认跟随 302（同协议同 host 才跟随，跨 host 需手动）
        // 这里显式处理重定向，因为 CDN 在 objects.githubusercontent.com
        int code = conn.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isEmpty()) {
                throw new IOException("asset 下载返回 " + code + " 但无 Location header: " + apiUrl);
            }
            // 重定向到 CDN: 不需要 Authorization header（CDN URL 自带签名）
            conn = (HttpURLConnection) new URL(location).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            code = conn.getResponseCode();
        }
        if (code < 200 || code >= 300) {
            String body = readBodyTail(conn.getErrorStream(), 500);
            conn.disconnect();
            throw new IOException("asset 下载返回 " + code + ": " + body);
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(dest.toFile())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 判断异常是否值得重试。
     * 可重试：TLS/Socket/超时/EOF 等瞬时网络故障 + 5xx 临时服务端错误 + 重定向缺 Location
     * 不可重试：4xx (token 失效 / 仓库不存在 / asset 名错) — 重试也是错
     */
    private static boolean isRetryable(IOException e) {
        if (e instanceof javax.net.ssl.SSLException) return true;
        if (e instanceof java.net.SocketException) return true;
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof java.io.EOFException) return true;
        String msg = e.getMessage();
        if (msg != null) {
            // "asset 下载返回 5xx: ..." 字面匹配 5xx
            if (msg.contains("返回 50") || msg.contains("返回 52") || msg.contains("返回 53")) return true;
            // 302 但无 Location (上游短暂异常,重试可能正常返回)
            if (msg.contains("Location header")) return true;
        }
        return false;
    }

    private HttpURLConnection openConn(String url, String token, String accept) throws IOException {
        java.net.URL u = new URL(url);
        java.net.URLConnection rawConn = u.openConnection();
        // Docker Desktop for Mac VPNKit 与 GitHub CDN 的 TLS 1.3 握手存在兼容性问题
        // 强制 TLS 1.2 避免 "Remote host terminated the handshake"
        if (rawConn instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection sslConn = (javax.net.ssl.HttpsURLConnection) rawConn;
            try {
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                ctx.init(null, null, null);
                sslConn.setSSLSocketFactory(ctx.getSocketFactory());
            } catch (Exception e) {
                log.warn("[Restore] 设置 TLS 1.2 失败,使用默认: {}", e.getMessage());
            }
        }
        HttpURLConnection conn = (HttpURLConnection) rawConn;
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("User-Agent", "myblog-restore/5.0");
        conn.setInstanceFollowRedirects(false);  // 手动处理 302（GitHub 跨 host 重定向到 CDN）
        return conn;
    }

    private static String readBodyTail(InputStream in, int maxLen) {
        if (in == null) return "";
        try {
            byte[] buf = new byte[Math.min(maxLen, 4096)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (IOException ignored) {
            return "";
        }
    }
}
