package com.blog.settings.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.ResultCode;
import com.blog.common.BusinessException;
import com.blog.common.web.AuthContext;
import com.blog.settings.dto.BackupResponse;
import com.blog.settings.entity.BackupRecord;
import com.blog.settings.mapper.BackupRecordMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 数据备份服务（REQ-BACKUP-2026-06-20，v4.2.0）
 *
 * 设计依据：docs/设计文档/博客数据备份方案设计.md
 *
 * 核心逻辑：
 *  1. POST /run → 创建 PENDING record → 异步执行 blog-backup.sh
 *  2. ProcessBuilder 启脚本 → 同步等待（带超时，1h 兜底）
 *  3. 解析 .result.json → 更新 record（SUCCESS / FAILED）
 *
 * 安全：
 *  - 密码/token 不进 db,不入日志(只在 env / ProcessBuilder 内存中)
 *  - 失败信息只保留最后 500 字符(避免日志爆炸)
 *  - 已有 RUNNING 任务时拒绝新触发(防 SQLite 锁/资源争用)
 *
 * 配套：
 *  - BackupController（admin 鉴权后调）
 *  - BackupRecord / BackupRecordMapper（持久化）
 *  - blog-backup.sh（实际执行）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final BackupRecordMapper backupRecordMapper;
    private final ObjectMapper objectMapper;

    /**
     * 自注入代理（@Lazy 解决循环依赖）
     * 2026-06-20 修 P0-2：@Async 自调用失效问题
     * Spring @Async 靠动态代理，this.runScriptAsync() / this.trimOldRecords() 走不到代理 → 注解失效 → 同步执行
     * 通过 self 调用走 Spring 代理 → @Async 生效
     */
    @Autowired
    @Lazy
    private BackupService self;

    /** 备份脚本绝对路径（部署到 /opt/myblog/scripts/blog-backup.sh） */
    @Value("${blog.backup.script.path:/opt/myblog/scripts/blog-backup.sh}")
    private String backupScriptPath;

    /** 部署根（用于 ProcessBuilder 工作目录） */
    @Value("${blog.install.dir:/opt/myblog}")
    private String installDir;

    /** SQLite db 路径（透传给脚本） */
    @Value("${SQLITE_PATH:/opt/myblog/blog.db}")
    private String sqlitePath;

    /** 上传目录（透传给脚本） */
    @Value("${UPLOAD_DIR:/opt/myblog/uploads}")
    private String uploadDir;

    /**
     * 备份明文中转目录（与 blog-backup.sh 的 STAGE_DIR 对齐）
     * 必须透传给脚本作为 BACKUP_STAGE_DIR env,否则 shell 默认 /tmp/blog-backup-stage
     * 与 Java 侧读 result 的路径不匹配（P1-1 bug 修复）
     */
    @Value("${BACKUP_STAGE_DIR:/tmp/blog-backup-stage}")
    private String backupStageDir;

    /** 备份密码（从 env 读，绝不打日志） */
    @Value("${BACKUP_ENCRYPTION_PASSWORD:}")
    private String backupPassword;

    /** GitHub token（从 env 读） */
    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    /** 备份仓库 */
    @Value("${GITHUB_BACKUP_REPO:}")
    private String githubBackupRepo;

    /** 脚本执行超时（秒），默认 1 小时 */
    @Value("${blog.backup.timeout.sec:3600}")
    private long backupTimeoutSec;

    /** 历史保留份数 */
    @Value("${blog.backup.keep.records:30}")
    private int keepRecords;

    // ============= 1. 触发备份（异步）=============

    /**
     * 触发备份：创建 PENDING 记录 → 异步执行脚本
     * @return 新建 record 的 id
     * @throws BusinessException 已有 RUNNING 任务时抛 3001
     */
    public Long triggerBackup(HttpServletRequest request) {
        // 1. 预检：环境变量齐不齐
        if (backupPassword == null || backupPassword.length() < 8) {
            log.warn("备份触发拒绝: BACKUP_ENCRYPTION_PASSWORD 未配置或长度 < 8");
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "BACKUP_ENCRYPTION_PASSWORD 未配置,请先在 /etc/myblog/myblog.env 设置");
        }
        if (githubToken == null || githubToken.isEmpty()) {
            log.warn("备份触发拒绝: GITHUB_TOKEN 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "GITHUB_TOKEN 未配置,请先在 /etc/myblog/myblog.env 设置");
        }
        if (githubBackupRepo == null || githubBackupRepo.isEmpty()) {
            log.warn("备份触发拒绝: GITHUB_BACKUP_REPO 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "GITHUB_BACKUP_REPO 未配置,例 owner/my-blog-backup");
        }

        // 2. 并发控制：已有 RUNNING 任务?
        int running = backupRecordMapper.countRunning();
        if (running > 0) {
            log.warn("备份触发拒绝: 已有 RUNNING 中的备份任务,operator={}", AuthContext.uid(request));
            throw new BusinessException(ResultCode.BACKUP_CONFLICT);
        }

        // 3. 创建 PENDING 记录
        BackupRecord record = new BackupRecord();
        record.setStatus("PENDING");
        record.setStartedAt(LocalDateTime.now());
        Object uid = AuthContext.uid(request);
        if (uid != null) {
            try {
                record.setOperatorId(Long.parseLong(uid.toString()));
            } catch (NumberFormatException ignored) {
                // 设备 ID 走的就是字符串,不进 operator_id
            }
        }
        record.setOperatorName(resolveOperatorName(request));
        backupRecordMapper.insert(record);

        log.info("备份任务创建: id={} operator_id={} operator_name={}",
                record.getId(), record.getOperatorId(), record.getOperatorName());

        // 4. 异步执行脚本(@Async 走 Spring 默认线程池)
        // 2026-06-20 修 P0-2: 用 self.runScriptAsync 取代 this.runScriptAsync
        // 走 Spring 代理, @Async 才生效（自调用会绕过代理）
        self.runScriptAsync(record.getId());

        return record.getId();
    }

    /**
     * 异步执行 blog-backup.sh
     */
    @Async
    public void runScriptAsync(Long recordId) {
        BackupRecord record = backupRecordMapper.selectById(recordId);
        if (record == null) {
            log.error("备份 record {} 不存在,跳过执行", recordId);
            return;
        }

        // PENDING → RUNNING
        record.setStatus("RUNNING");
        backupRecordMapper.updateById(record);

        log.info("开始执行备份脚本: record_id={} script={}", recordId, backupScriptPath);

        // 2026-06-20 修 P1-2: ProcessBuilder 加 bash 包装 ——
        // 直接执行脚本依赖 chmod +x, deploy-server.sh 万一漏掉就会 Permission denied。
        // 用 bash 显式执行,和脚本 shebang #!/bin/bash 语义一致, 不依赖文件权限位。
        ProcessBuilder pb = new ProcessBuilder("bash", backupScriptPath);
        pb.directory(new File(installDir));
        // 透传 env（密码/token 走 env,不进命令行 → 不进 ps）
        pb.environment().put("BACKUP_ENCRYPTION_PASSWORD", backupPassword);
        pb.environment().put("GITHUB_TOKEN", githubToken);
        pb.environment().put("GITHUB_BACKUP_REPO", githubBackupRepo);
        pb.environment().put("SQLITE_PATH", sqlitePath);
        pb.environment().put("UPLOAD_DIR", uploadDir);
        pb.environment().put("INSTALL_DIR", installDir);
        // 2026-06-20 修 P1-1: BACKUP_STAGE_DIR 透传给 shell, 两侧路径对齐
        pb.environment().put("BACKUP_STAGE_DIR", backupStageDir);
        // 2026-06-20 修 P0-1: 把 record_id 传给 shell, 用于 .result.json 命名
        pb.environment().put("BACKUP_RECORD_ID", String.valueOf(recordId));
        // stdout/stderr 合并到一个临时日志文件,便于失败时回看
        File logFile = new File(System.getProperty("java.io.tmpdir"), "blog-backup-" + recordId + ".log");

        int exitCode;
        String outputTail = "";
        try {  // 2026-06-20: 外层 try/finally 包 runScriptAsync 主体,无论成功/失败/异常都触发 trimOldRecords
            try {
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            Process process = pb.start();
            boolean finished = process.waitFor(backupTimeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                // 2026-06-20 修 P0-2: SIGKILL 不可被 bash trap 捕获, 明文会残留磁盘。
                // 改为先 SIGTERM(给 trap 跑的机会清明文), 等 5s 还活着才 SIGKILL 兜底。
                process.destroy();
                boolean gracefulExit = process.waitFor(5, TimeUnit.SECONDS);
                if (!gracefulExit) {
                    process.destroyForcibly();
                    log.warn("备份脚本 SIGTERM 后 5s 未退出, 已强制 SIGKILL: record_id={}", recordId);
                } else {
                    log.warn("备份脚本执行超时(>{}s), SIGTERM 后已退出: record_id={}", backupTimeoutSec, recordId);
                }
                // Java 侧兜底: 即便 trap 没跑, 也强制清 stage 目录(防明文残留)
                forceCleanStageDir();
                markFailed(recordId, "SCRIPT", "执行超时(>" + backupTimeoutSec + "s),已 kill");
                return;
            }
            exitCode = process.exitValue();

            // 读日志最后 500 字符（避免 OOM）
            if (logFile.exists() && logFile.length() > 0) {
                byte[] allBytes = Files.readAllBytes(logFile.toPath());
                int tail = Math.min(allBytes.length, 500);
                outputTail = new String(allBytes, allBytes.length - tail, tail, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("备份脚本执行异常: record_id={}", recordId, e);
            markFailed(recordId, "SCRIPT", "执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        if (exitCode != 0) {
            log.warn("备份脚本退出码非零: record_id={} exit_code={} output_tail={}", recordId, exitCode, outputTail);
            // 脚本已经在 .progress 标了具体阶段,这里只取最后一行 ERR 段
            String stage = extractErrorStageFromOutput(outputTail);
            markFailed(recordId, stage, "脚本退出码 " + exitCode + "\n" + outputTail);
            return;
        }

        // 成功：解析 .result.json
        // 2026-06-20 修 P0-1 + P1-1：
        //   1) 路径用 @Value 注入的 backupStageDir(与 shell STAGE_DIR 一致),不再是 java.io.tmpdir 硬编码
        //   2) 文件名用 .blog-backup-result.$recordId.json(与 shell RESULT_FILE 对齐)
        //   3) result 在 STAGE_DIR 之外(STAGE_DIR_PARENT),不再被 trap rm -rf 误删
        //   4) 读完后立即删 result 文件（不留垃圾）
        Path stageDirParent = Paths.get(backupStageDir).getParent();
        if (stageDirParent == null) stageDirParent = Paths.get("/tmp");
        Path resultJson = stageDirParent.resolve(".blog-backup-result." + recordId + ".json");
        try {
            if (!Files.exists(resultJson)) {
                // 脚本 trap 会清 stage,但 result.json 已经在 STAGE_DIR 之外
                // 不存在 = 脚本根本没写成功(UPLOAD 阶段挂) or env BACKUP_RECORD_ID 没透传
                markFailed(recordId, "UPLOAD", "脚本未产出 .result.json (expected=" + resultJson + ")\n" + outputTail);
                return;
            }
            ResultJson result = objectMapper.readValue(resultJson.toFile(), ResultJson.class);
            record.setStatus("SUCCESS");
            record.setFinishedAt(LocalDateTime.now());
            record.setTag(result.getTag());
            record.setDbSize(result.getDbSize() == null ? 0L : result.getDbSize());
            record.setUploadsSize(result.getUploadsSize() == null ? 0L : result.getUploadsSize());
            record.setAssetCount(result.getAssets() == null ? 0 : result.getAssets().size());

            // 拼 asset URL 列表
            // 2026-06-20 修 P1-3: 旧实现用 /expanded_assets/ 是 GitHub 前端懒加载端点(返 HTML 片段),
            //   后面拼 /<filename> 是 404。正确路径是 /releases/download/<tag>/<filename>
            //   详见 GitHub Docs: https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases
            List<String> urls = new ArrayList<>();
            String releaseUrl = result.getReleaseUrl();
            if (releaseUrl != null && !releaseUrl.isEmpty() && result.getAssets() != null) {
                // releaseUrl 形如 https://github.com/owner/repo/releases/tag/<tag>
                // 单文件下载 URL 形如 https://github.com/owner/repo/releases/download/<tag>/<filename>
                // 替换段从 /releases/tag/ 改为 /releases/download/ 即可
                String downloadBase = releaseUrl.replace("/releases/tag/", "/releases/download/");
                for (ResultJson.Asset a : result.getAssets()) {
                    urls.add(downloadBase + "/" + a.getName());
                }
            }
            record.setAssetUrls(objectMapper.writeValueAsString(urls));

            // 2026-06-20 修 P1-4: 读 manifest.json 原文存到 manifest_json 列（之前是死代码）
            // manifest 在 STAGE_DIR 内,会被 trap 删, 所以读完后**立即**拷成临时字符串
            if (result.getManifestFile() != null && !result.getManifestFile().isEmpty()) {
                Path manifestPath = Paths.get(result.getManifestFile());
                if (Files.exists(manifestPath)) {
                    try {
                        String manifestStr = new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8);
                        // 截断到 64KB 防 db 撑爆（manifest 不应该这么大，正常 1-5KB）
                        if (manifestStr.length() > 65536) {
                            manifestStr = manifestStr.substring(0, 65536) + "...";
                        }
                        record.setManifestJson(manifestStr);
                    } catch (Exception e) {
                        log.warn("读 manifest.json 失败(不影响 SUCCESS 标记): record_id={} err={}", recordId, e.getMessage());
                    }
                } else {
                    log.warn("manifest.json 不存在(脚本可能没生成): record_id={} path={}", recordId, manifestPath);
                }
            }

            backupRecordMapper.updateById(record);
            log.info("备份成功: record_id={} tag={} db_size={} uploads_size={} asset_count={}",
                    recordId, record.getTag(), record.getDbSize(), record.getUploadsSize(), record.getAssetCount());
        } catch (Exception e) {
            log.error("解析备份结果失败: record_id={}", recordId, e);
            markFailed(recordId, "UPLOAD", "解析 .result.json 失败: " + e.getMessage() + "\n" + outputTail);
        } finally {
            // 读完后立即删 result,不留垃圾
            try {
                Files.deleteIfExists(resultJson);
            } catch (IOException ignored) {
                // best-effort
            }
        }
        } finally {
            // 2026-06-20 修 P2: 无论成功/失败/异常, 都触发清理超期备份
            // (成功/失败都会产生 SUCCESS 记录 → 触发 trimOldRecords)
            // 2026-06-20 修 P0-2: 用 self.trimOldRecords 取代 this.trimOldRecords, @Async 才生效
            try {
                self.trimOldRecords();
            } catch (Exception e) {
                log.warn("trimOldRecords 调度失败(不阻塞): {}", e.getMessage());
            }
        }
    }

    // ============= 2. 查询 ==============

    public BackupResponse getById(Long id) {
        BackupRecord r = backupRecordMapper.selectById(id);
        return r == null ? null : toResponse(r);
    }

    public IPage<BackupResponse> list(int page, int size) {
        Page<BackupRecord> pg = new Page<>(page, size);
        QueryWrapper<BackupRecord> qw = new QueryWrapper<>();
        qw.orderByDesc("started_at");
        IPage<BackupRecord> result = backupRecordMapper.selectPage(pg, qw);
        return result.convert(this::toResponse);
    }

    /**
     * 2026-06-20 修 P2：清理超出 keepRecords 的旧记录（db 行 + 对应 GitHub Release）
     *
     * 调用时机：runScriptAsync 完成后调一次（SUCCESS 或 FAILED 都触发）
     * 不在请求路径同步调——避免阻塞 list 响应
     *
     * GitHub Release 删除：best-effort,失败仅 WARN,不影响 db 清理
     */
    @Async
    public void trimOldRecords() {
        if (keepRecords <= 0) return;
        try {
            // 1. 查所有 SUCCESS 记录按时间倒序
            QueryWrapper<BackupRecord> qw = new QueryWrapper<>();
            qw.eq("status", "SUCCESS");
            qw.orderByDesc("started_at");
            qw.select("id", "tag", "started_at");
            List<BackupRecord> all = backupRecordMapper.selectList(qw);
            if (all.size() <= keepRecords) return;

            // 2. 超出 keepRecords 的 → 删 db + 删 GitHub Release
            List<BackupRecord> toDelete = all.subList(keepRecords, all.size());
            log.info("备份历史清理：保留 {} 条，删除 {} 条", keepRecords, toDelete.size());

            for (BackupRecord old : toDelete) {
                // GitHub Release 先删(best-effort)
                if (old.getTag() != null && !old.getTag().isEmpty()) {
                    boolean releaseDeleted = deleteGitHubRelease(old.getTag());
                    if (!releaseDeleted) {
                        log.warn("GitHub Release {} 删除失败(可能已被手动删)，仍删 db 行", old.getTag());
                    }
                }
                // db 行删
                backupRecordMapper.deleteById(old.getId());
            }
        } catch (Exception e) {
            log.warn("备份历史清理失败（不阻塞流程）: {}", e.getMessage());
        }
    }

    /**
     * 删 GitHub Release（best-effort，失败返 false 不抛异常）
     * 优先用 gh CLI（项目内已有），fallback curl + REST API
     */
    private boolean deleteGitHubRelease(String tag) {
        if (githubToken == null || githubToken.isEmpty()
                || githubBackupRepo == null || githubBackupRepo.isEmpty()) {
            return false;
        }
        // 1) 优先 gh CLI
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "gh release delete '" + tag.replace("'", "'\\''") + "' --repo '" +
                githubBackupRepo + "' --yes 2>&1");
            pb.environment().put("GH_TOKEN", githubToken);
            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                log.info("gh release delete {} 成功", tag);
                return true;
            }
        } catch (Exception ignored) {
            // gh 不在或失败,fallback curl
        }
        // 2) fallback curl + REST API
        try {
            // 先 GET 拿 release id
            ProcessBuilder getPb = new ProcessBuilder("bash", "-c",
                "curl -fsS -H 'Authorization: token " + githubToken + "' " +
                "'https://api.github.com/repos/" + githubBackupRepo + "/releases/tags/" + tag + "' " +
                "| python3 -c \"import json,sys;print(json.load(sys.stdin)['id'])\"");
            Process get = getPb.start();
            // Java 1.8 兼容：用 BufferedReader 代替 InputStream.readAllBytes()(Java 9+)
            StringBuilder idBuf = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(get.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) idBuf.append(line);
            }
            String idStr = idBuf.toString().trim();
            if (!get.waitFor(15, TimeUnit.SECONDS) || get.exitValue() != 0 || idStr.isEmpty()) {
                return false;
            }
            // DELETE release
            ProcessBuilder delPb = new ProcessBuilder("bash", "-c",
                "curl -fsS -X DELETE -H 'Authorization: token " + githubToken + "' " +
                "'https://api.github.com/repos/" + githubBackupRepo + "/releases/" + idStr + "'");
            Process del = delPb.start();
            del.getOutputStream().close();
            boolean delOk = del.waitFor(15, TimeUnit.SECONDS) && del.exitValue() == 0;
            if (delOk) log.info("curl release delete {} 成功", tag);
            return delOk;
        } catch (Exception e) {
            log.warn("curl release delete {} 失败: {}", tag, e.getMessage());
            return false;
        }
    }

    // ============= 3. 工具方法 ==============

    private void markFailed(Long recordId, String stage, String message) {
        BackupRecord r = backupRecordMapper.selectById(recordId);
        if (r == null) return;
        r.setStatus("FAILED");
        r.setFinishedAt(LocalDateTime.now());
        r.setErrorStage(stage);
        // 只保留最后 500 字符,避免 db 撑爆
        r.setErrorMessage(message == null ? null
                : (message.length() > 500 ? message.substring(message.length() - 500) : message));
        backupRecordMapper.updateById(r);
    }

    private String extractErrorStageFromOutput(String output) {
        // 脚本会在 .progress 文件写 [STEP-X] / [DB_DUMP_FAILED] 等
        // 这里从 stdout 末尾回溯找
        if (output == null) return "SCRIPT";
        if (output.contains("UPLOAD_FAILED") || output.contains("gh release create") || output.contains("create release 失败")) {
            return "UPLOAD";
        }
        if (output.contains("UPLOADS_PACK_FAILED") || output.contains("uploads 打包/加密失败")) {
            return "PACK";
        }
        if (output.contains("DB_DUMP_FAILED") || output.contains("sqlite-export.sh 失败")) {
            return "DUMP";
        }
        return "SCRIPT";
    }

    private String resolveOperatorName(HttpServletRequest request) {
        if (request == null) return null;
        Object uid = AuthContext.uid(request);
        return uid == null ? null : "uid:" + uid;
    }

    private BackupResponse toResponse(BackupRecord r) {
        BackupResponse.BackupResponseBuilder b = BackupResponse.builder()
                .id(r.getId())
                .status(r.getStatus())
                .tag(r.getTag())
                .startedAt(r.getStartedAt())
                .finishedAt(r.getFinishedAt())
                .dbSize(r.getDbSize())
                .uploadsSize(r.getUploadsSize())
                .assetCount(r.getAssetCount())
                .operatorId(r.getOperatorId())
                .operatorName(r.getOperatorName())
                .errorStage(r.getErrorStage())
                .errorMessage(r.getErrorMessage());
        if (r.getStartedAt() != null && r.getFinishedAt() != null) {
            b.durationSec(Duration.between(r.getStartedAt(), r.getFinishedAt()).getSeconds());
        }
        if (r.getAssetUrls() != null && !r.getAssetUrls().isEmpty()) {
            try {
                b.assetUrls(objectMapper.readValue(r.getAssetUrls(), new TypeReference<List<String>>() {}));
            } catch (Exception e) {
                b.assetUrls(Collections.emptyList());
            }
        }
        return b.build();
    }

    /**
     * Java 侧兜底清理 stage 目录
     * 2026-06-20 修 P0-2: 超时 SIGKILL 不可被 bash trap 捕获, 明文 tar 残留磁盘
     * → 这里 best-effort 清一遍, 配合 shell 的 trap 双重保险
     */
    private void forceCleanStageDir() {
        try {
            Path stage = Paths.get(backupStageDir);
            if (Files.exists(stage)) {
                // 走 walk + delete, 普通 File.delete 递归会有 NIO 异常
                try (java.util.stream.Stream<Path> walk = Files.walk(stage)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                }
                log.info("Java 侧兜底清理 stage 目录: {}", stage);
            }
        } catch (Exception e) {
            log.warn("Java 侧清理 stage 目录失败(不阻塞流程): {}", e.getMessage());
        }
    }

    /**
     * .result.json 解析对象
     */
    @lombok.Data
    public static class ResultJson {
        private String tag;
        @JsonProperty("release_url")
        private String releaseUrl;
        @JsonProperty("db_size")
        private Long dbSize;
        @JsonProperty("uploads_size")
        private Long uploadsSize;
        /**
         * 2026-06-20 修 P1-4: manifest.json 绝对路径（脚本第 8 段写入）
         * shell 输出字段名是 snake_case "manifest_file",Jackson 默认不自动映射,
         * 这里用 @JsonProperty 显式声明,避免字段永远为 null
         */
        @JsonProperty("manifest_file")
        private String manifestFile;
        private List<Asset> assets;

        @lombok.Data
        public static class Asset {
            private String name;
            private Long size;
        }
    }
}
