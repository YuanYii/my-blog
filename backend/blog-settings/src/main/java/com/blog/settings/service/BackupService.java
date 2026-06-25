package com.blog.settings.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.ResultCode;
import com.blog.common.BusinessException;
import com.blog.common.web.AuthContext;
import com.blog.common.web.TraceIdUtil;
import com.blog.settings.dto.BackupResponse;
import com.blog.settings.entity.BackupRecord;
import com.blog.settings.mapper.BackupRecordMapper;
import com.blog.settings.mapper.RestoreRecordMapper;
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
    private final RestoreRecordMapper restoreRecordMapper;
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

    /**
     * 备份专用 GitHub token（从 env 读，**与发布用的 GITHUB_TOKEN 物理隔离**）
     * 2026-06-20 v4.2.0 重命名：原 GITHUB_TOKEN → BACKUP_GITHUB_TOKEN
     * 理由：发布链路的 GITHUB_TOKEN 指向 my-blog-prov，备份指向 my-blog-backup
     *       命名分开后两个 token 可以独立轮换、用 Fine-grained PAT 精确授权
     */
    @Value("${BACKUP_GITHUB_TOKEN:}")
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
            log.warn("备份触发拒绝: BACKUP_GITHUB_TOKEN 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "BACKUP_GITHUB_TOKEN 未配置,请先在 /etc/myblog/myblog.env 设置（注意是 BACKUP_GITHUB_TOKEN,不是发布用的 GITHUB_TOKEN）");
        }
        if (githubBackupRepo == null || githubBackupRepo.isEmpty()) {
            log.warn("备份触发拒绝: GITHUB_BACKUP_REPO 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "GITHUB_BACKUP_REPO 未配置,例 owner/my-blog-backup");
        }

        // 2. 并发控制：已有 RUNNING 任务?
        // v4.3.0 (REQ-RESTORE-2026-06-20): 双向互斥, restore 进行中也不能触发备份
        // 否则恢复期间覆盖 db 时, 备份会读到半截 db
        int runningBackup = backupRecordMapper.countRunning();
        int runningRestore = restoreRecordMapper.countRunning();
        if (runningBackup > 0 || runningRestore > 0) {
            log.warn("备份触发拒绝: 有正在执行的任务 (backup={}, restore={}), operator={}",
                runningBackup, runningRestore, AuthContext.uid(request));
            throw new BusinessException(ResultCode.BACKUP_CONFLICT,
                "已有正在执行的任务 (备份或恢复),请等待完成后再试");
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
        // 2026-06-21：记录触发请求的 traceId,失败详情里给 owner 反查 server log 用
        // 这里在请求线程,MDC 里有值;@Async 跑 runScriptAsync 时 MDC 不会透传(TraceIdFilter 注释也提到本期不做 @Async 透传),
        // 所以 markFailed 时从 record.getTraceId() 拿,而不是 MDC.get
        record.setTraceId(org.slf4j.MDC.get(TraceIdUtil.MDC_TRACE_ID));
        try {
            // v4.3.2: partial unique index uk_backup_record_running 兜底 TOCTOU 窗口
            backupRecordMapper.insert(record);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("UNIQUE constraint failed") && msg.contains("uk_backup_record_running")) {
                log.warn("备份触发拒绝 (DB 唯一索引兜底): concurrent backup detected, operator={}",
                        AuthContext.uid(request));
                throw new BusinessException(ResultCode.BACKUP_CONFLICT,
                    "已有正在执行的任务 (备份或恢复),请等待完成后再试");
            }
            throw e;
        }

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
        // 2026-06-20 v4.2.0: 改名 GITHUB_TOKEN → BACKUP_GITHUB_TOKEN
        // 与发布链路的 GITHUB_TOKEN (指向 my-blog-prov) 物理隔离
        pb.environment().put("BACKUP_GITHUB_TOKEN", githubToken);
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

        // 2026-06-22 v4.x polish：把"跑脚本 + 解析 result"抽到独立方法 runScriptCore，
        //   外层 try/finally 只负责"无论如何都触发 trimOldRecords"，
        //   消除原"外层 try { 内层 try { 120 行 } finally }"嵌套——原版缩进乱、可读性差。
        try {  // 外层只包一行：保证无论 runScriptCore 怎么走（return / 异常）都触发 trimOldRecords
            runScriptCore(recordId, record, pb, logFile);
        } finally {
            // 2026-06-20 修 P2: 无论成功/失败/异常, 都触发清理超期备份
            // 2026-06-20 修 P0-2: 用 self.trimOldRecords 取代 this.trimOldRecords, @Async 才生效
            try {
                self.trimOldRecords();
            } catch (Exception e) {
                log.warn("trimOldRecords 调度失败(不阻塞): {}", e.getMessage());
            }
        }
    }

    /**
     * 备份脚本执行主体（拆出来消嵌套 try）—— 跑进程 → 解析 result → 落库
     * @param recordId  backup_record.id
     * @param record    已是 RUNNING 状态的 BackupRecord
     * @param pb        ProcessBuilder（已配 env + 工作目录，调用方构造好）
     * @param logFile   进程 stdout/stderr 合并重定向文件
     */
    private void runScriptCore(Long recordId, BackupRecord record, ProcessBuilder pb, File logFile) {
        int exitCode;
        String outputTail = "";
        try {
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            Process process = pb.start();
            boolean finished = process.waitFor(backupTimeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                // SIGKILL 不可被 bash trap 捕获, 明文会残留磁盘。
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
        // 路径用 @Value 注入的 backupStageDir(与 shell STAGE_DIR 一致),
        // 文件名用 .blog-backup-result.$recordId.json(与 shell RESULT_FILE 对齐),
        // result 在 STAGE_DIR 之外(STAGE_DIR_PARENT),不再被 trap rm -rf 误删
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
            // releaseUrl 形如 https://github.com/owner/repo/releases/tag/<tag>
            // 单文件下载 URL 形如 https://github.com/owner/repo/releases/download/<tag>/<filename
            // 替换段从 /releases/tag/ 改为 /releases/download/ 即可
            List<String> urls = new ArrayList<>();
            String releaseUrl = result.getReleaseUrl();
            if (releaseUrl != null && !releaseUrl.isEmpty() && result.getAssets() != null) {
                String downloadBase = releaseUrl.replace("/releases/tag/", "/releases/download/");
                for (ResultJson.Asset a : result.getAssets()) {
                    urls.add(downloadBase + "/" + a.getName());
                }
            }
            record.setAssetUrls(objectMapper.writeValueAsString(urls));

            // 读 manifest.json 原文存到 manifest_json 列
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
     *
     * 安全（2026-06-22 v4.x polish）：
     * - token 一律走子进程 env（GITHUB_TOKEN / GH_TOKEN / BACKUP_GITHUB_TOKEN），
     *   不进 bash -c 命令行参数 → ps aux / /proc/<pid>/cmdline 看不到明文
     * - curl 走 -H @<(printf ...) 把 header 从 stdin 喂进 curl，避免 -H "Auth..." 字面量
     * - tag 用 bash 单引号 escape + shell 关键字黑名单校验（防御 tag 内嵌反引号/$() 注入）
     */
    private boolean deleteGitHubRelease(String tag) {
        if (githubToken == null || githubToken.isEmpty()
                || githubBackupRepo == null || githubBackupRepo.isEmpty()) {
            return false;
        }
        // tag 防御：拒绝任何含 shell 元字符的 tag（项目内 tag 由 blog-backup.sh 生成，
        // 格式 blog-YYYY-MM-DD-HHMMSS，理论上不可能含元字符；这层只是 defense in depth）
        if (!isShellSafe(tag)) {
            log.warn("GitHub Release 删除拒绝: tag 含 shell 元字符 tag-len={}", tag.length());
            return false;
        }
        // 1) 优先 gh CLI
        // gh CLI 强制读 GH_TOKEN env(我们自己的 BACKUP_GITHUB_TOKEN 它不认),
        //   透传时把值塞给 GH_TOKEN(只影响这个子进程的 gh 调用,不会泄漏到 Spring 主进程)
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "gh release delete '" + tag + "' --repo '" + githubBackupRepo + "' --yes 2>&1");
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
        // token 通过子进程 env (BACKUP_GITHUB_TOKEN) 注入,curl header 通过 process substitution
        // 从 stdin 喂进去 → bash 命令行 / ps 看不到 token 字符串
        try {
            String apiBase = "https://api.github.com/repos/" + githubBackupRepo;
            // 先 GET 拿 release id
            ProcessBuilder getPb = new ProcessBuilder("bash", "-c",
                "curl -fsS -H @<(printf '%s' \"Authorization: token ${BACKUP_GITHUB_TOKEN}\") " +
                "'" + apiBase + "/releases/tags/" + tag + "' " +
                "| python3 -c \"import json,sys;print(json.load(sys.stdin)['id'])\"");
            getPb.environment().put("BACKUP_GITHUB_TOKEN", githubToken);
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
                "curl -fsS -X DELETE -H @<(printf '%s' \"Authorization: token ${BACKUP_GITHUB_TOKEN}\") " +
                "'" + apiBase + "/releases/" + idStr + "'");
            delPb.environment().put("BACKUP_GITHUB_TOKEN", githubToken);
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

    /**
     * shell 元字符黑名单校验（defense in depth）
     * 允许：字母 / 数字 / `-` / `_` / `.` / `/`（GitHub tag 通常是 v1.2.3 / blog-2024-01-01-...）
     * 拒绝：单引号 / 双引号 / 反引号 / $ / \ / ; / & / | / < / > / ( / ) / { / } / 换行 / 空格
     */
    private static boolean isShellSafe(String s) {
        if (s == null || s.isEmpty() || s.length() > 200) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '/';
            if (!ok) return false;
        }
        return true;
    }

    // ============= 3. 工具方法 ==============

    /**
     * 2026-06-21 v4.2.1 polish 新增：手动删除一条备份记录
     *
     * 业务规则：
     *  - PENDING / RUNNING → 拒绝（BACKUP_RECORD_RUNNING/3002），脚本进程可能在写 db / 读 stage，删记录会留垃圾
     *  - FAILED → 直删 db 行（GitHub 端没产物可删）
     *  - SUCCESS → 先 deleteGitHubRelease(tag) best-effort → WARN 不回滚 db（GitHub 删失败 db 也要删，否则列表会一直显示）
     *    复用 trimOldRecords 已有的 deleteGitHubRelease，命令构造零分叉
     *
     * 写 INFO 操作日志（含 username）便于审计。
     *
     * 已知 trade-off（2026-06-21 自查 P1-3）：
     *  - 多 admin tab 同时点同一条 SUCCESS 删除时,两条请求都过 PENDING/RUNNING 校验后都会调
     *    deleteGitHubRelease(tag);第二个 release delete 返 404,日志噪音但不影响功能。
     *  - 单实例下前端 deletingId 单 tab 已挡;真要彻底防需引入 DELETING 状态枚举 + 乐观锁,
     *    改动面过大且触发概率极低（多 tab 同 id 同 ms 操作）,当前不修。
     */
    public void deleteBackupRecord(Long id, HttpServletRequest request) {
        BackupRecord r = backupRecordMapper.selectById(id);
        if (r == null) {
            log.warn("删除备份记录拒绝: 记录不存在 id={} operator={}", id, AuthContext.username(request));
            throw new BusinessException(ResultCode.NOT_FOUND, "备份记录不存在");
        }
        String status = r.getStatus();
        if ("PENDING".equals(status) || "RUNNING".equals(status)) {
            log.warn("删除备份记录拒绝: 任务进行中 id={} status={} operator={}",
                    id, status, AuthContext.username(request));
            throw new BusinessException(ResultCode.BACKUP_RECORD_RUNNING,
                    "备份任务进行中(" + status + "),无法删除");
        }

        String tag = r.getTag();
        String username = AuthContext.username(request);

        // SUCCESS: 先删 GitHub Release(best-effort)
        if ("SUCCESS".equals(status) && tag != null && !tag.isEmpty()) {
            boolean releaseDeleted = deleteGitHubRelease(tag);
            if (!releaseDeleted) {
                // 沿用 trimOldRecords 的策略:WARN 不回滚 db,避免重复手动清理
                log.warn("删除备份记录: GitHub Release {} 删除失败(可能已被手动删),仍删 db 行 operator={}",
                        tag, username);
            } else {
                log.info("删除备份记录: GitHub Release {} 已删除 operator={}", tag, username);
            }
        }

        backupRecordMapper.deleteById(id);
        log.info("删除备份记录成功: id={} status={} tag={} operator={}", id, status, tag, username);
    }

    /**
     * 2026-06-24 DEV-003：从 GitHub 备份仓库同步最近 3 条 release 到本地 backup_record
     *
     * 使用场景：项目初始化时本地 backup_record 表为空，无法选择历史备份恢复。
     * 通过此接口拉取最近 3 条 release 元数据回填 backup_record（status=SUCCESS），
     * 完成后这些记录就与本地备份一样可在恢复页面被选中走正常恢复流程。
     *
     * 实现要点：
     *  - 复用现有 BACKUP_GITHUB_TOKEN / GITHUB_BACKUP_REPO 配置
     *  - 复用 gh CLI 模式（项目内已有），不引入 HTTP 客户端
     *  - selectByTag 不存在才 INSERT（天然幂等）
     *  - manifest.json 拉取失败不阻塞 sync（db_size / uploads_size 兜底填 0）
     *
     * @return 实际新增的 record 数（已存在的 tag 跳过不计）
     * @throws BusinessException token/repo 未配置或 gh CLI 调用失败
     */
    public int syncFromGithub(HttpServletRequest request) {
        if (githubToken == null || githubToken.isEmpty()) {
            log.warn("备份同步拒绝: BACKUP_GITHUB_TOKEN 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "BACKUP_GITHUB_TOKEN 未配置,请先在 /etc/myblog/myblog.env 设置");
        }
        if (githubBackupRepo == null || githubBackupRepo.isEmpty()) {
            log.warn("备份同步拒绝: GITHUB_BACKUP_REPO 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "GITHUB_BACKUP_REPO 未配置,例 owner/my-blog-backup");
        }

        String traceId = org.slf4j.MDC.get(TraceIdUtil.MDC_TRACE_ID);
        String operatorName = resolveOperatorName(request);
        Long operatorId = null;
        Object uid = AuthContext.uid(request);
        if (uid != null) {
            try {
                operatorId = Long.parseLong(uid.toString());
            } catch (NumberFormatException ignored) {}
        }

        // 1. 拉最近 3 条 release（gh CLI -L 3 --json 拿结构化数据）
        List<GithubRelease> releases;
        try {
            releases = listGithubReleases(3);
        } catch (Exception e) {
            log.warn("备份同步失败: gh release list 调用异常 trace_id={} error={}",
                traceId, e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "GitHub release 列表拉取失败: " + e.getMessage());
        }

        // 2. 逐条 INSERT（selectByTag 不存在才插）
        int inserted = 0;
        for (GithubRelease rel : releases) {
            if (rel.tagName == null || rel.tagName.isEmpty()) continue;
            // defense in depth: tagName 来自 GitHub,虽然 release tag 受 owner 控制,
            // 但 fetchManifestContent 仍通过 bash -c 拼 URL,与 deleteGitHubRelease 对齐安全策略
            if (!isShellSafe(rel.tagName)) {
                log.warn("备份同步跳过: tag 含 shell 元字符 tag-len={}", rel.tagName.length());
                continue;
            }
            BackupRecord exist = backupRecordMapper.selectByTag(rel.tagName);
            if (exist != null) {
                log.debug("备份同步跳过: tag={} 已存在 record_id={}", rel.tagName, exist.getId());
                continue;
            }
            BackupRecord r = new BackupRecord();
            r.setTag(rel.tagName);
            r.setStatus("SUCCESS");
            LocalDateTime publishedLdt = parseGithubTimestamp(rel.publishedAt);
            r.setStartedAt(publishedLdt);
            r.setFinishedAt(publishedLdt);
            r.setAssetCount(rel.assets == null ? 0 : rel.assets.size());
            r.setOperatorId(operatorId);
            r.setOperatorName(operatorName);
            r.setTraceId(traceId);

            // assetUrls: github release download 直链
            List<String> urls = new ArrayList<>();
            if (rel.assets != null) {
                String downloadBase = "https://github.com/" + githubBackupRepo
                    + "/releases/download/" + rel.tagName;
                for (GithubAsset a : rel.assets) {
                    urls.add(downloadBase + "/" + a.name);
                }
            }
            try {
                r.setAssetUrls(objectMapper.writeValueAsString(urls));
            } catch (Exception ignored) {
                r.setAssetUrls("[]");
            }

            // manifest.json 拉取（失败兜底 0）
            long dbSize = 0L, uploadsSize = 0L;
            String manifestStr = fetchManifestContent(rel);
            if (manifestStr != null) {
                r.setManifestJson(manifestStr.length() > 65536
                    ? manifestStr.substring(0, 65536) + "..." : manifestStr);
                try {
                    java.util.Map<?, ?> m = objectMapper.readValue(manifestStr, java.util.Map.class);
                    Object db = m.get("db");
                    if (db instanceof java.util.Map) {
                        Object sz = ((java.util.Map<?, ?>) db).get("size_bytes");
                        if (sz instanceof Number) dbSize = ((Number) sz).longValue();
                    }
                    Object up = m.get("uploads");
                    if (up instanceof java.util.Map) {
                        Object sz = ((java.util.Map<?, ?>) up).get("size_bytes");
                        if (sz instanceof Number) uploadsSize = ((Number) sz).longValue();
                    }
                } catch (Exception e) {
                    log.warn("备份同步: manifest 解析失败 tag={} trace_id={} error={}",
                        rel.tagName, traceId, e.getMessage());
                }
            }
            r.setDbSize(dbSize);
            r.setUploadsSize(uploadsSize);

            backupRecordMapper.insert(r);
            inserted++;
            log.info("备份同步: 新增 record id={} tag={} operator={}",
                r.getId(), rel.tagName, operatorName);
        }

        log.info("备份同步完成: 拉取 {} 条 release,新增 {} 条 record trace_id={} operator={}",
            releases.size(), inserted, traceId, operatorName);
        return inserted;
    }

    /**
     * 调 GitHub REST API 拿最近 N 条 release(含 assets)
     * 用 curl + REST API 代替 gh release list --json（兼容旧版 gh CLI）
     * token 通过 env var 注入，不出现在命令行参数（防 ps/proc 泄漏）
     */
    private List<GithubRelease> listGithubReleases(int limit) throws Exception {
        // GET /repos/{owner}/{repo}/releases
        String apiUrl = "https://api.github.com/repos/" + githubBackupRepo + "/releases?per_page=" + limit;
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
            "curl -fsSL "
            + "-H 'Accept: application/vnd.github+json' "
            + "-H \"Authorization: token ${BACKUP_GITHUB_TOKEN}\" "
            + "'" + apiUrl + "'");
        pb.environment().put("BACKUP_GITHUB_TOKEN", githubToken);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder buf = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) buf.append(line).append('\n');
        }
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("GitHub API 超时 (>10s)");
        }
        if (p.exitValue() != 0) {
            throw new IOException("GitHub API 失败 退出码 " + p.exitValue() + ": " + buf.toString().trim());
        }
        String json = buf.toString().trim();
        if (json.isEmpty() || "[]".equals(json)) {
            return Collections.emptyList();
        }
        return objectMapper.readValue(json,
            new TypeReference<List<GithubRelease>>() {});
    }

    /**
     * 从 release.assets 中找 manifest.json 并拉取原文
     * 拉取失败返回 null（不抛异常,sync 兜底填 0）
     */
    private String fetchManifestContent(GithubRelease rel) {
        if (rel.assets == null) return null;
        GithubAsset manifestAsset = null;
        for (GithubAsset a : rel.assets) {
            if ("manifest.json".equals(a.name)) {
                manifestAsset = a;
                break;
            }
        }
        if (manifestAsset == null) return null;
        // 用 GitHub API assets 端点 + Accept: application/octet-stream 下载
        // 私有仓库不能用 /releases/download/ 浏览器路径（GitHub 重定向到 S3 签名 URL 时会剥掉 Authorization header → 403）
        // asset.url 形如 https://api.github.com/repos/{owner}/{repo}/releases/assets/{id}，不受重定向影响
        if (manifestAsset.url == null || manifestAsset.url.isEmpty()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "curl -fsSL "
                + "-H 'Accept: application/octet-stream' "
                + "-H \"Authorization: token ${BACKUP_GITHUB_TOKEN}\" "
                + "'" + manifestAsset.url + "'");
            pb.environment().put("BACKUP_GITHUB_TOKEN", githubToken);
            Process p = pb.start();
            StringBuilder buf = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) buf.append(line).append('\n');
            }
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return buf.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** GitHub Release 元数据（REST API / gh CLI JSON 输出反序列化用） */
    public static class GithubRelease {
        @JsonProperty("tag_name")
        public String tagName;
        @JsonProperty("published_at")
        public String publishedAt;
        public List<GithubAsset> assets;
    }

    /** GitHub Release Asset 元数据 */
    public static class GithubAsset {
        public String name;
        public Long size;
        /** GitHub API 端点，形如 https://api.github.com/repos/{owner}/{repo}/releases/assets/{id} */
        public String url;
    }

    /** 解析 GitHub 时间戳（"2026-06-24T08:00:00Z" → LocalDateTime） */
    private static LocalDateTime parseGithubTimestamp(String s) {
        if (s == null || s.isEmpty()) return LocalDateTime.now();
        try {
            return java.time.OffsetDateTime.parse(s).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s.replace("Z", ""));
            } catch (Exception e2) {
                return LocalDateTime.now();
            }
        }
    }

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
        if (uid == null) return null;
        // 2026-06-21：操作人显示当前登录用户名(username),无则兜底 "uid:"+uid
        String username = AuthContext.username(request);
        return (username != null && !username.isEmpty()) ? username : "uid:" + uid;
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
                .errorMessage(r.getErrorMessage())
                .traceId(r.getTraceId());
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
