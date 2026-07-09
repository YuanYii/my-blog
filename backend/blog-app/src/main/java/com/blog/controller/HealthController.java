package com.blog.controller;

import com.blog.common.Result;
import com.blog.upgrade.UpgradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 服务健康检查（admin）
 *
 * 2026-06-22 新增：dashboard "站点状态" 面板（services 数组）从硬编码改真实数据。
 *
 * 设计原则：
 * 1. 错误隔离 —— 任何子系统查询失败只影响该字段返回，其他字段正常出
 *    （前端 dashboard 仍能展示能查到的部分）
 * 2. 不阻塞 —— 不依赖外部服务调用（不发 HTTP），所有数据走本地读取
 * 3. 不写日志 —— 高频调用（前端每 30s 轮询），错误仅在该子系统异常时打 WARN
 * 4. 轻依赖 —— 只读文件系统 / X509 parse / Redis INFO；不引入新依赖
 *
 * 数据源：
 * - uptime：从 application 启动时刻算（构造完成后 set）
 * - database size：SQLite 文件实际大小（h2/mysql 走 SQL 查询）
 * - database max：从 ${DB_MAX_BYTES} env 读，软上限 1GB（个人博客量级）
 * - redis：StringRedisTemplate.execute → INFO memory
 * - ssl：读 /etc/letsencrypt/live/<domain>/cert.pem 的 X509 notAfter
 *       （certbot 默认路径；非 certbot 部署返回 configured=false）
 */
@Slf4j
@RestController
@RequestMapping("/admin/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final UpgradeService upgradeService;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${DB_MAX_BYTES:1073741824}")  // 默认 1 GB
    private long dbMaxBytes;

    @Value("${SSL_DOMAIN:}")
    private String sslDomain;

    @Value("${SSL_CERT_PATH:}")
    private String sslCertPath;

    @Value("${app.version:unknown}")
    private String appVersion;

    private Instant startedAt;

    @PostConstruct
    public void init() {
        startedAt = Instant.now();
    }

    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 0. Version + Uptime
        data.put("version", upgradeService.getCurrentVersion());
        if (startedAt != null) {
            long sec = ChronoUnit.SECONDS.between(startedAt, Instant.now());
            data.put("uptimeSec", sec);
            data.put("startedAt", LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault())
                    .toString().replace('T', ' ').substring(0, 19));
        } else {
            data.put("uptimeSec", -1);
            data.put("startedAt", null);
        }

        // 2. Database
        data.put("database", probeDatabase());

        // 3. Redis
        data.put("redis", probeRedis());

        // 4. SSL 证书
        data.put("ssl", probeSsl());

        // 5. CDN（暂时只标记是否配置；详细命中率需要 nginx 日志分析，留待下一轮）
        // 当前实现：从 env CDN_DOMAIN 读，存在即视为已配置
        String cdnDomain = System.getenv("CDN_DOMAIN");
        Map<String, Object> cdnInfo = new LinkedHashMap<>();
        cdnInfo.put("configured", cdnDomain != null && !cdnDomain.trim().isEmpty());
        data.put("cdn", cdnInfo);

        data.put("generatedAt", LocalDateTime.now().toString().replace('T', ' ').substring(0, 19));

        return Result.success(data);
    }

    /**
     * 数据库 size 探测
     * - SQLite：从 url 提路径，读 File.length()
     * - MySQL/PostgreSQL：SELECT 走 information_schema（暂不实现，超出 MVP）
     */
    private Map<String, Object> probeDatabase() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            boolean isSqlite = datasourceUrl != null && datasourceUrl.startsWith("jdbc:sqlite:");
            info.put("type", isSqlite ? "sqlite" : "other");
            long sizeBytes = 0;
            if (isSqlite) {
                // jdbc:sqlite:/data/blog.db?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL
                String path = datasourceUrl.substring("jdbc:sqlite:".length());
                int q = path.indexOf('?');
                if (q > 0) path = path.substring(0, q);
                File f = new File(path);
                if (f.exists()) {
                    sizeBytes = f.length();
                }
            } else {
                // 非 SQLite：尝试 SQL 查询数据库大小（MySQL 走 information_schema）
                try {
                    Long bytes = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(data_length + index_length), 0) " +
                        "FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE()", Long.class);
                    sizeBytes = bytes == null ? 0 : bytes;
                } catch (Exception ignored) {
                    // 非 MySQL 或权限不足 → 留 0
                }
            }
            info.put("sizeBytes", sizeBytes);
            info.put("sizeHuman", humanBytes(sizeBytes));
            info.put("maxBytes", dbMaxBytes);
            info.put("maxHuman", humanBytes(dbMaxBytes));
            double pct = dbMaxBytes > 0 ? (sizeBytes * 100.0 / dbMaxBytes) : 0;
            info.put("pct", Math.round(pct * 10) / 10.0);  // 1 位小数
        } catch (Exception e) {
            log.warn("health probe 数据库异常: {}", e.getMessage());
            info.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return info;
    }

    /**
     * Redis 内存探测
     * - 走 INFO memory 命令（Redis 4.0+ 标准）
     * - used_memory / maxmemory 都是字节数
     */
    private Map<String, Object> probeRedis() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            // RedisServerCommands.info(String) 返回 Properties（key = 配置项，value = 值）
            // section "memory" 取 used_memory / maxmemory
            Properties props = redis.execute((RedisConnection conn) -> conn.serverCommands().info("memory"));
            if (props == null || props.isEmpty()) {
                info.put("connected", false);
                info.put("error", "INFO 返回空");
                return info;
            }
            long usedMem = parseLong(props.getProperty("used_memory"), 0);
            long maxMem = parseLong(props.getProperty("maxmemory"), 0);  // 0 = 不限
            info.put("connected", true);
            info.put("usedMemoryBytes", usedMem);
            info.put("usedMemoryHuman", humanBytes(usedMem));
            info.put("maxMemoryBytes", maxMem);
            info.put("maxMemoryHuman", maxMem > 0 ? humanBytes(maxMem) : "无限制");
            double pct = maxMem > 0 ? (usedMem * 100.0 / maxMem) : 0;
            info.put("pct", Math.round(pct * 10) / 10.0);
        } catch (Exception e) {
            log.warn("health probe Redis 异常: {}", e.getMessage());
            info.put("connected", false);
            info.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return info;
    }

    /**
     * SSL 证书探测
     * - 优先读 SSL_CERT_PATH（用户显式指定路径，最稳）
     * - 否则根据 SSL_DOMAIN 拼 /etc/letsencrypt/live/<domain>/cert.pem（certbot 默认）
     * - 解析 X509 拿 notAfter
     */
    private Map<String, Object> probeSsl() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            // CDN 场景：SSL 由 CDN 终结，源站无证书文件
            String cdnDomain = System.getenv("CDN_DOMAIN");
            if (cdnDomain != null && !cdnDomain.trim().isEmpty()) {
                info.put("configured", true);
                info.put("provider", "CDN");
                info.put("domain", cdnDomain.trim());
                info.put("note", "SSL 由 CDN 终结（Flexible 模式），源站无需本地证书");
                return info;
            }
            Path certPath = resolveCertPath();
            if (certPath == null || !Files.exists(certPath)) {
                info.put("configured", false);
                info.put("reason", "未找到证书文件（设置 SSL_CERT_PATH 或 SSL_DOMAIN）");
                return info;
            }
            info.put("configured", true);
            info.put("path", certPath.toString());
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (java.io.InputStream is = Files.newInputStream(certPath)) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                Instant notAfter = cert.getNotAfter().toInstant();
                long daysLeft = ChronoUnit.DAYS.between(Instant.now(), notAfter);
                info.put("expiresAt", LocalDateTime.ofInstant(notAfter, ZoneId.systemDefault())
                        .toString().replace('T', ' ').substring(0, 19));
                info.put("daysLeft", daysLeft);
                if (daysLeft < 0)       info.put("status", "expired");
                else if (daysLeft < 30) info.put("status", "warning");
                else                    info.put("status", "ok");
            }
        } catch (Exception e) {
            log.warn("health probe SSL 异常: {}", e.getMessage());
            info.put("configured", true);
            info.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return info;
    }

    private Path resolveCertPath() {
        if (sslCertPath != null && !sslCertPath.trim().isEmpty()) {
            return Paths.get(sslCertPath);
        }
        if (sslDomain != null && !sslDomain.trim().isEmpty()) {
            return Paths.get("/etc/letsencrypt/live", sslDomain, "cert.pem");
        }
        return null;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return (v >= 100 || i == 0 ? Math.round(v) : Math.round(v * 10) / 10.0) + " " + units[i];
    }
}
