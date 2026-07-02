package com.blog.settings.service.restore;

import com.blog.settings.entity.RestoreRecord;
import com.blog.settings.service.SiteSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 数据恢复执行器（REQ-RESTORE-2026-06-24，v5.0）
 *
 * 设计依据：docs/design/博客数据恢复方案设计.md §3 + §4
 *
 * 全过程在 myblog JVM 进程内同步执行,**不停服**：
 *   Step 1 · DOWNLOAD    GitHub Release assets
 *   Step 2 · VERIFY      SHA256 校验 + manifest 解析
 *   Step 3 · DECRYPT     openssl AES-256-CBC + gunzip
 *   Step 4 · IMPORT      staged sqlite + Online Backup API 原地覆盖
 *   Step 5 · UPLOADS     scope=DB_UPLOADS 时 tar.gz 原子切换
 *   Step 6 · POSTCHECK   table_stats_exact 对比 (verifyDiff 仅记录不阻断)
 *
 * 失败处理：抛 {@link RestoreException}，调用方按 errorStage 落库。
 * 资源清理：所有临时文件通过 try-with-resources + finally 块在 {@link #execute} 末尾删除。
 *
 * TODO(v5.x)：当前仅支持 SQLite profile,MySQL profile 触发本方法应在 RestoreService 上游拒绝。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestoreExecutor {

    private final GithubReleaseClient githubClient;
    private final EncryptedDumpCodec codec;
    private final SqliteOnlineBackup onlineBackup;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SiteSettingsService siteSettingsService;

    @Value("${BACKUP_ENCRYPTION_PASSWORD:}")
    private String backupPassword;

    @Value("${BACKUP_GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${GITHUB_BACKUP_REPO:}")
    private String githubBackupRepo;

    @Value("${SQLITE_PATH:/opt/myblog/blog.db}")
    private String sqlitePath;

    @Value("${UPLOAD_DIR:/opt/myblog/uploads}")
    private String uploadDir;

    /** 临时工作目录 root（每次执行下创建 {recordId} 子目录） */
    @Value("${blog.restore.stage.root:/tmp/blog-restore}")
    private String stageRoot;

    /** 单 tar entry 解压大小上限（防 tar bomb） */
    private static final long MAX_ENTRY_SIZE = 100L * 1024 * 1024;       // 100 MB
    /** 单次解压总大小上限 */
    private static final long MAX_TOTAL_EXTRACTED = 1024L * 1024 * 1024; // 1 GB
    /** 单次解压 entry 数上限 */
    private static final int MAX_ENTRY_COUNT = 100_000;

    /**
     * 执行完整恢复流水线
     * @param record 已是 RUNNING 状态的 RestoreRecord
     * @return 执行结果（成功或失败）
     */
    public Result execute(RestoreRecord record) {
        Path stageDir = Paths.get(stageRoot, String.valueOf(record.getId()));
        Path stagedSqlite = stageDir.resolve("staged.sqlite");
        try {
            Files.createDirectories(stageDir);

            // Step 1: DOWNLOAD
            List<Path> assets = step1Download(record, stageDir);

            // Step 2: VERIFY (SHA256 + manifest 解析)
            JsonNode manifest = step2Verify(record, stageDir, assets);

            // Step 3 + 4: DECRYPT + IMPORT (db)
            String dbEncName = manifest.path("db").path("encrypted_file").asText("");
            if (dbEncName.isEmpty()) {
                throw new RestoreException("VERIFY", "manifest.db.encrypted_file 缺失");
            }
            Path dbEncFile = stageDir.resolve(dbEncName);
            Path dumpSql = stageDir.resolve("dump.sql");
            try {
                codec.decryptAndDecompress(dbEncFile, backupPassword, dumpSql);
            } catch (Exception e) {
                throw new RestoreException("DECRYPT",
                    "解密 / gunzip 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
            try {
                onlineBackup.buildStagedDbFromSql(dumpSql, stagedSqlite);
                onlineBackup.copyOver(stagedSqlite, Paths.get(sqlitePath));
                if (!onlineBackup.smokeTest(Paths.get(sqlitePath))) {
                    throw new RestoreException("IMPORT", "RESTORE 后 smoke test SELECT 1 失败");
                }
                // 恢复后确保 admin 用户存在（备份可能不含 admin user）
                ensureAdminUser();
                // 恢复后清空设备授权表，让下次登录触发 bootstrap 重新授权
                clearDevices();
                // 恢复后确保 API 白名单存在（备份可能不含白名单数据，导致登录 401）
                ensureApiWhitelist();
                // 恢复后清除站点设置 Redis 缓存，让下次读强制走已替换的 SQLite
                evictSettingsCache();
            } catch (RestoreException re) {
                throw re;
            } catch (Exception e) {
                throw new RestoreException("IMPORT",
                    "Online Backup 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }

            // Step 5: UPLOADS (可选)
            if ("DB_UPLOADS".equals(record.getScope())) {
                String upEncName = manifest.path("uploads").path("encrypted_file").asText("");
                if (upEncName.isEmpty()) {
                    throw new RestoreException("UPLOADS",
                        "scope=DB_UPLOADS 但 manifest.uploads.encrypted_file 缺失");
                }
                Path upEncFile = stageDir.resolve(upEncName);
                Path tarGz = stageDir.resolve("uploads.tar.gz");
                try {
                    codec.decrypt(upEncFile, backupPassword, tarGz);
                } catch (Exception e) {
                    throw new RestoreException("UPLOADS",
                        "uploads 解密失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
                step5UploadsAtomicSwap(tarGz);
            }

            // Step 6: POSTCHECK
            String verifyDiff = step6Postcheck(manifest);

            return Result.success(verifyDiff);
        } catch (RestoreException re) {
            log.warn("[RestoreExecutor] 失败 stage={} record={} err={}",
                re.getStage(), record.getId(), re.getMessage());
            return Result.failed(re.getStage(), re.getMessage());
        } catch (Exception e) {
            log.error("[RestoreExecutor] 未预期异常 record={}", record.getId(), e);
            return Result.failed("INTERNAL",
                e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
        } finally {
            // 清临时文件 (敏感数据,失败时也清)
            cleanupStageDir(stageDir);
        }
    }

    // ============== Step 实现 ==============

    private List<Path> step1Download(RestoreRecord record, Path stageDir) throws RestoreException {
        try {
            return githubClient.downloadAssets(
                githubToken, githubBackupRepo, record.getSourceTag(), stageDir);
        } catch (IOException e) {
            throw new RestoreException("DOWNLOAD",
                "拉取 release assets 失败: " + e.getMessage(), e);
        }
    }

    private JsonNode step2Verify(RestoreRecord record, Path stageDir, List<Path> assets)
            throws RestoreException {
        // 1. 解析 SHA256SUMS
        Path sumsFile = stageDir.resolve("SHA256SUMS");
        if (!Files.exists(sumsFile)) {
            throw new RestoreException("VERIFY", "SHA256SUMS 文件未下载");
        }
        Map<String, String> expectedSums = parseSha256Sums(sumsFile);

        // 2. 校验每个有 sum 的 asset
        for (Path asset : assets) {
            String name = asset.getFileName().toString();
            if ("SHA256SUMS".equals(name)) continue;
            String expected = expectedSums.get(name);
            if (expected == null) {
                log.warn("[Restore-VERIFY] {} 未在 SHA256SUMS 中,跳过校验", name);
                continue;
            }
            String actual = sha256Hex(asset);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new RestoreException("VERIFY",
                    name + " SHA256 不匹配 (expected=" + expected.substring(0, 16) + "..., actual=" + actual.substring(0, 16) + "...)");
            }
        }
        log.info("[Restore-VERIFY] SHA256 校验全部通过 ({} 个 asset)", expectedSums.size());

        // 3. 解析 manifest.json
        Path manifestFile = stageDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            throw new RestoreException("VERIFY", "manifest.json 未下载");
        }
        try {
            JsonNode root = objectMapper.readTree(manifestFile.toFile());
            log.info("[Restore-VERIFY] manifest 解析完成 tag={}", root.path("tag").asText("?"));
            return root;
        } catch (IOException e) {
            throw new RestoreException("VERIFY", "manifest.json 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * uploads 内容切换（v5.0.1 改写: 不动 mount 挂载点）。
     *
     * v5.0 旧实现把整个 uploads 目录 mv → uploads.old, 在 Docker 容器内 uploads
     * 是 volume mount 挂载点,Linux 内核拒绝 rename mount point 触发 EBUSY,
     * 无论是否停 nginx 都不行。
     *
     * 新实现: 永远只操作 mount 内部的 entry,从不碰挂载点本身:
     *   1. 解压 tar.gz → uploads/.restore-staging.{ts}/
     *   2. 把 uploads/ 下当前 top-level entry 全部 mv 到 uploads/.restore-old.{ts}/
     *   3. 把 .restore-staging.{ts}/ 下的 entry mv 到 uploads/ top-level
     *   4. 删空的 .restore-staging.{ts}/
     *   5. 异步删 .restore-old.{ts}/
     *
     * 每次单文件 / 单子目录 mv 在同一 filesystem 内是 entry-level rename,
     * 与 mount point 无关,不触发 EBUSY。整体不是严格 atomic
     * (步骤 2-3 之间 uploads 短暂内容混合,几秒钟),但对非公开数据可接受。
     *
     * nginx 不需要 stop: nginx 对已 open 的文件 fd 通过 inode 引用,
     * entry 级 mv 不影响 inode,新请求会 resolve 到新 entry。
     */
    private void step5UploadsAtomicSwap(Path tarGz) throws RestoreException {
        Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        long ts = System.currentTimeMillis();
        Path stagingDir = uploadDirPath.resolve(".restore-staging." + ts);
        Path oldHoldDir = uploadDirPath.resolve(".restore-old." + ts);

        try {
            // mount 点必须先存在(Docker volume 已挂载)。极端情况首次部署无挂载也兜底创建。
            if (!Files.exists(uploadDirPath)) {
                Files.createDirectories(uploadDirPath);
            }
            Files.createDirectories(stagingDir);

            // 解压到 stagingDir
            long totalSize = 0;
            int entryCount = 0;
            try (FileInputStream fis = new FileInputStream(tarGz.toFile());
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 GZIPInputStream gz = new GZIPInputStream(bis);
                 TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    if (++entryCount > MAX_ENTRY_COUNT) {
                        throw new RestoreException("UPLOADS",
                            "tar entry 数超过上限 " + MAX_ENTRY_COUNT);
                    }
                    if (entry.getSize() > MAX_ENTRY_SIZE) {
                        throw new RestoreException("UPLOADS",
                            "tar entry " + entry.getName() + " 大小 " + entry.getSize()
                            + " 超过上限 " + MAX_ENTRY_SIZE);
                    }
                    totalSize += Math.max(0, entry.getSize());
                    if (totalSize > MAX_TOTAL_EXTRACTED) {
                        throw new RestoreException("UPLOADS",
                            "解压总大小超过上限 " + MAX_TOTAL_EXTRACTED);
                    }

                    String rawName = entry.getName();
                    String relPath = stripUploadsPrefix(rawName);
                    if (relPath == null) continue;
                    Path target = stagingDir.resolve(relPath).normalize();
                    if (!target.startsWith(stagingDir)) {
                        throw new RestoreException("UPLOADS",
                            "Zip Slip 攻击检测: " + rawName + " 解析后超出 staging");
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (FileOutputStream out = new FileOutputStream(target.toFile());
                             BufferedOutputStream bout = new BufferedOutputStream(out)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = tar.read(buf)) > 0) {
                                bout.write(buf, 0, n);
                            }
                        }
                    }
                }
            }
            log.info("[Restore-UPLOADS] 解压完成 entries={} bytes={} → {}",
                entryCount, totalSize, stagingDir);

            // 步骤 2: 把 uploads/ top-level 当前 entry 全部 mv 到 oldHoldDir
            // (排除 .restore-staging / .restore-old 自己的临时目录)
            Files.createDirectories(oldHoldDir);
            int movedOut = 0;
            try (java.util.stream.Stream<Path> entries = Files.list(uploadDirPath)) {
                for (Path p : (Iterable<Path>) entries::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".restore-staging.") || name.startsWith(".restore-old.")) {
                        continue;
                    }
                    Files.move(p, oldHoldDir.resolve(name));
                    movedOut++;
                }
            }
            log.info("[Restore-UPLOADS] 旧 entry 已暂存 {} 项 → {}", movedOut, oldHoldDir);

            // 步骤 3: 把 stagingDir 内的 entry mv 到 uploads/ top-level
            int movedIn = 0;
            try (java.util.stream.Stream<Path> entries = Files.list(stagingDir)) {
                for (Path p : (Iterable<Path>) entries::iterator) {
                    Files.move(p, uploadDirPath.resolve(p.getFileName()));
                    movedIn++;
                }
            }
            log.info("[Restore-UPLOADS] 新 entry 已切换 {} 项 → {}", movedIn, uploadDirPath);

            // 步骤 4: 清掉空的 stagingDir
            Files.deleteIfExists(stagingDir);

            // 步骤 5: 异步删旧 entry 集合
            if (Files.exists(oldHoldDir)) {
                deleteRecursivelyAsync(oldHoldDir);
            }
        } catch (RestoreException re) {
            rollbackUploadsSwap(uploadDirPath, stagingDir, oldHoldDir);
            throw re;
        } catch (Exception e) {
            rollbackUploadsSwap(uploadDirPath, stagingDir, oldHoldDir);
            throw new RestoreException("UPLOADS",
                "tar 解压/切换失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * best-effort 回滚: 把 oldHoldDir 内的 entry 移回 uploads/ top-level,然后删 staging/old 目录。
     * 失败时各种异常都吞掉(本来就在 catch 里),保证不掩盖原始报错。
     */
    private void rollbackUploadsSwap(Path uploadDirPath, Path stagingDir, Path oldHoldDir) {
        try {
            if (Files.exists(oldHoldDir)) {
                try (java.util.stream.Stream<Path> entries = Files.list(oldHoldDir)) {
                    for (Path p : (Iterable<Path>) entries::iterator) {
                        Path target = uploadDirPath.resolve(p.getFileName());
                        if (Files.exists(target)) continue;  // 已经切到 uploads 里就不覆盖
                        try { Files.move(p, target); } catch (IOException ignored) {}
                    }
                }
                try { deleteRecursively(oldHoldDir); } catch (IOException ignored) {}
            }
            if (Files.exists(stagingDir)) {
                try { deleteRecursively(stagingDir); } catch (IOException ignored) {}
            }
        } catch (Exception ignored) {
            // 回滚失败也不抛,让原始 RestoreException 继续传出去
        }
    }

    /** 提取 entry name 在 "uploads/" 后的相对路径；返回 null 表示该 entry 不该解 */
    private static String stripUploadsPrefix(String entryName) {
        if (entryName == null || entryName.isEmpty()) return null;
        // 备份脚本: tar -C $INSTALL_DIR uploads/ → entry 形如 "uploads/2026/..."
        // 也可能没有 uploads/ 前缀（如果备份脚本改了 -C 行为）→ 直接当 uploads 内相对路径
        String n = entryName.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.equals(".") || n.isEmpty()) return null;
        if (n.startsWith("uploads/")) {
            return n.substring("uploads/".length());
        }
        if (n.equals("uploads") || n.equals("uploads/")) return null;
        // 不带 uploads/ 前缀直接当相对路径（兼容历史备份格式差异）
        return n;
    }

    private String step6Postcheck(JsonNode manifest) {
        JsonNode expectedNode = manifest.path("db").path("table_stats_exact");
        if (!expectedNode.isObject() || expectedNode.size() == 0) {
            log.info("[Restore-POSTCHECK] manifest 无 table_stats_exact,跳过对比");
            return null;
        }
        Map<String, Long> expected = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = expectedNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            expected.put(e.getKey(), e.getValue().asLong(-1));
        }

        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            String table = e.getKey();
            Long exp = e.getValue();
            try {
                Long actual = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\"", Long.class);
                if (actual == null || !actual.equals(exp)) {
                    diffs.add(table + ": expected=" + exp + " actual=" + actual);
                }
            } catch (Exception qx) {
                diffs.add(table + ": query失败 " + qx.getMessage());
            }
        }

        if (diffs.isEmpty()) {
            log.info("[Restore-POSTCHECK] 全部 {} 张表行数一致", expected.size());
            return null;
        }
        String diffStr = String.join("\n", diffs);
        log.warn("[Restore-POSTCHECK] 行数差异 {} 项:\n{}", diffs.size(), diffStr);
        return diffStr.length() > 500 ? diffStr.substring(0, 500) + "..." : diffStr;
    }

    // ============== 工具方法 ==============

    private static Map<String, String> parseSha256Sums(Path file) throws RestoreException {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // 格式: <hex>  <filename> 或 <hex> *<filename>
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length != 2) continue;
                String name = parts[1];
                if (name.startsWith("*")) name = name.substring(1);
                map.put(name, parts[0]);
            }
        } catch (IOException e) {
            throw new RestoreException("VERIFY", "SHA256SUMS 读取失败: " + e.getMessage(), e);
        }
        return map;
    }

    private static String sha256Hex(Path file) throws RestoreException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file.toFile());
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = bis.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RestoreException("VERIFY",
                "SHA256 计算失败 " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private void cleanupStageDir(Path stageDir) {
        if (!Files.exists(stageDir)) return;
        try {
            deleteRecursively(stageDir);
            log.info("[Restore] 清理 stage 目录: {}", stageDir);
        } catch (IOException e) {
            log.warn("[Restore] 清理 stage 目录失败 (不阻塞): {} err={}", stageDir, e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) {}
                });
        }
    }

    private void deleteRecursivelyAsync(Path path) {
        Thread t = new Thread(() -> {
            try { deleteRecursively(path); }
            catch (IOException e) {
                log.warn("[Restore-UPLOADS] 异步清理旧 uploads 失败: {} err={}", path, e.getMessage());
            }
        }, "restore-uploads-cleanup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 确保恢复后能用 admin/123456 登录(可控登入兜底)。
     *
     * 语义: UPSERT。
     *   - admin 不存在 → 插入(id=1, username=admin, password_hash=bcrypt("123456"))
     *   - admin 存在(任意密码) → 强制覆盖密码为 bcrypt("123456")
     *
     * 设计取舍: 这是 destructive 的, 备份里 admin 改过的密码会被覆盖回默认。
     * 但恢复操作本身就是 destructive 的(整个 DB 都被替换), 让操作者能确定性地
     * 用 admin/123456 重新登入, 比保留备份里那个可能已忘记的密码更友好。
     *
     * 不阻断恢复主流程: SQL 失败仅记 WARN, 操作者还能用其他方式恢复访问。
     *
     * hash 来自 schema-sqlite.sql:346, 对应明文 "123456"。
     */
    private void ensureAdminUser() {
        try {
            String hash = "$2a$10$RiTjk3eJcUBN2xKUE4FAQ.4xzURKOSUrpbgvou1uGjEV7tQ70fpJW";
            // SQLite 3.24+ UPSERT 语法 (xerial 3.45 携带的 SQLite 满足)
            //   ON CONFLICT(username) 命中 uk_user_username 唯一索引 → 走 UPDATE 分支
            //   命不中 → 正常 INSERT
            int rows = jdbcTemplate.update(
                "INSERT INTO user (id, username, password_hash, nickname, role, created_at, updated_at) "
                + "VALUES (1, 'admin', ?, 'Corey', 'ADMIN', datetime('now','localtime'), datetime('now','localtime')) "
                + "ON CONFLICT(username) DO UPDATE SET "
                + "  password_hash = excluded.password_hash, "
                + "  updated_at = excluded.updated_at",
                hash);
            log.info("[Restore-IMPORT] ensureAdminUser: admin 已确保为默认密码 (rows={})", rows);
        } catch (Exception e) {
            log.warn("[Restore-IMPORT] ensureAdminUser 失败(不阻断): {}", e.getMessage());
        }
    }

    /**
     * 恢复后清空设备授权表。
     *
     * 备份里的设备记录属于备份时刻的环境，恢复后当前浏览器的 device_id 大概率不在表内，
     * 会被 AdminAuthFilter 拦截为"未授权"。清空后 DeviceService.verifyOnLogin 的 bootstrap
     * 逻辑生效：下一次用 admin/123456 登录的设备自动 approved，避免恢复后无法进入后台。
     *
     * 不阻断恢复主流程：SQL 失败仅记 WARN。
     */
    private void clearDevices() {
        try {
            int rows = jdbcTemplate.update("DELETE FROM admin_device");
            log.info("[Restore-IMPORT] clearDevices: 已清空设备授权表 (rows={})", rows);
        } catch (Exception e) {
            log.warn("[Restore-IMPORT] clearDevices 失败(不阻断): {}", e.getMessage());
        }
    }

    /**
     * 恢复后确保 API 白名单存在。
     * 备份数据库可能不含 api_whitelist 种子数据，导致 AdminAuthFilter 对 POST /auth/login
     * 走 default-deny 分支返回 401。重新插入必要条目。
     */
    private void ensureApiWhitelist() {
        try {
            // 始终执行 INSERT OR IGNORE，确保关键条目存在（不依赖 COUNT 判断）
            String now = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
            String sql = "INSERT OR IGNORE INTO api_whitelist (path_prefix, type, enabled, description, created_at, updated_at) VALUES (?, ?, 1, ?, ?, ?)";
            Object[][] rows = {
                {"/auth/login", "public", "登录", now, now},
                {"/auth/me/password", "public", "改密", now, now},
                {"/public/", "public", "公开端点", now, now},
                {"/articles", "public", "文章公开端点", now, now},
                {"/comments", "public", "评论公开端点", now, now},
                {"/health", "public", "健康检查", now, now},
                {"/v3/api-docs", "public", "Swagger API 文档", now, now},
                {"/swagger-ui", "public", "Swagger UI", now, now},
                {"/admin/", "admin", "所有 admin/* 路径必须鉴权", now, now},
                {"/auth/me", "admin", "获取当前用户信息", now, now},
                {"/auth/logout", "admin", "登出", now, now},
                {"/auth/devices", "admin", "设备管理", now, now},
                {"/uploads", "admin", "文件上传", now, now},
                {"/admin/articles/{id}/attachment", "admin", "文章附件上传/软删", now, now},
                {"/admin/attachments", "admin", "附件后台列表/恢复/硬删", now, now},
                {"/admin/audit-logs", "admin", "审计日志查询", now, now},
                {"/admin/settings/upload-md", "admin", "上传 md 文档批量更新 settings", now, now},
                {"/admin/settings/exec-sql", "admin", "紧急 SQL 执行", now, now},
            };
            int inserted = 0;
            for (Object[] row : rows) {
                jdbcTemplate.update(sql, row);
                inserted++;
            }
            log.info("[Restore-IMPORT] ensureApiWhitelist: 已插入 {} 条白名单", inserted);
        } catch (Exception e) {
            log.warn("[Restore-IMPORT] ensureApiWhitelist 失败(不阻断): {}", e.getMessage());
        }
    }

    private void evictSettingsCache() {
        try {
            siteSettingsService.evictAllCaches();
        } catch (Exception e) {
            log.warn("[Restore-IMPORT] evictSettingsCache 失败(不阻断): {}", e.getMessage());
        }
    }

    // ============== 返回结构 ==============

    public static final class Result {
        public final boolean success;
        public final String errorStage;
        public final String errorMessage;
        public final String verifyDiff;

        private Result(boolean success, String errorStage, String errorMessage, String verifyDiff) {
            this.success = success;
            this.errorStage = errorStage;
            this.errorMessage = errorMessage;
            this.verifyDiff = verifyDiff;
        }
        public static Result success(String verifyDiff) {
            return new Result(true, null, null, verifyDiff);
        }
        public static Result failed(String stage, String message) {
            return new Result(false, stage, message, null);
        }
    }

    /** 内部失败信号 */
    static final class RestoreException extends Exception {
        private final String stage;
        RestoreException(String stage, String message) { super(message); this.stage = stage; }
        RestoreException(String stage, String message, Throwable cause) {
            super(message, cause); this.stage = stage;
        }
        String getStage() { return stage; }
    }
}
