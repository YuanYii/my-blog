package com.blog.settings.service;

import com.blog.common.BusinessException;
import com.blog.common.ResultCode;
import com.blog.common.web.AuthContext;
import com.blog.settings.dto.RestoreResponse;
import com.blog.settings.entity.BackupRecord;
import com.blog.settings.entity.RestoreRecord;
import com.blog.settings.mapper.BackupRecordMapper;
import com.blog.settings.mapper.RestoreRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 数据恢复服务（REQ-RESTORE-2026-06-20，v4.3.0）
 *
 * 设计依据：docs/设计文档/博客数据恢复方案设计.md
 *
 * 核心逻辑：
 *  1. POST /run → 预检 env + 查 backup_record → 双向并发互斥 → 建 PENDING record → 异步执行
 *  2. runRestoreAsync() → ProcessBuilder("sudo", "systemd-run", "--scope", "--slice=myblog-restore", "--uid=myblog", "--gid=myblog", "/bin/bash", script)
 *     pb.start() 后**立刻 return**（不 waitFor）
 *  3. 脚本在独立 cgroup (myblog-restore.slice) 跑 + myblog 身份 → systemctl stop myblog 杀不到
 *  4. 脚本最后写 .result.json 到 RESTORE_RESULT_DIR（固定路径，不进 stage）
 *  5. RestoreStartupReconciler 双轨回填：JVM 启动 + @Scheduled(5min)
 *
 * 安全：
 *  - 密码/token 走 env 注入 ProcessBuilder，不进 ps
 *  - 失败信息只保留最后 500 字符（避免 db 撑爆）
 *  - @Async 必须通过 self.runRestoreAsync() 调用（自调用绕过 Spring 代理失效）
 *
 * 双向并发互斥（v5 关键）：
 *  - restore trigger 查 backup + restore 两表 RUNNING
 *  - BackupService.triggerBackup() 也查 restore 表 RUNNING（已改 1 行）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestoreService {

    private final RestoreRecordMapper restoreRecordMapper;
    private final BackupRecordMapper backupRecordMapper;

    /**
     * 自注入代理（@Lazy 解决循环依赖）
     * Spring @Async 靠动态代理, this.runRestoreAsync() 走不到代理 → 注解失效 → 同步执行
     */
    @Autowired
    @Lazy
    private RestoreService self;

    /** 恢复脚本绝对路径 */
    @Value("${blog.restore.script.path:/opt/myblog/scripts/blog-restore.sh}")
    private String restoreScriptPath;

    /** 部署根 */
    @Value("${blog.install.dir:/opt/myblog}")
    private String installDir;

    /** SQLite db 路径 */
    @Value("${SQLITE_PATH:/opt/myblog/blog.db}")
    private String sqlitePath;

    /** 上传目录 */
    @Value("${UPLOAD_DIR:/opt/myblog/uploads}")
    private String uploadDir;

    /** 明文中转目录（trap 清理） */
    @Value("${RESTORE_STAGE_DIR:/tmp/blog-restore-stage}")
    private String restoreStageDir;

    /** .result.json 落点（永不被 trap 清） */
    @Value("${blog.restore.result.dir:/var/lib/myblog/restore-results}")
    private String restoreResultDir;

    /** .bak 保留份数 */
    @Value("${blog.restore.keep.baks:5}")
    private int keepBaks;

    /** 健康检查 URL（v3 新增，避免端口硬编码） */
    @Value("${blog.restore.health.url:http://localhost:8080/api/v1/health}")
    private String restoreHealthUrl;

    /** 加密密码 */
    @Value("${BACKUP_ENCRYPTION_PASSWORD:}")
    private String backupPassword;

    /** GitHub PAT（备份仓库权限） */
    @Value("${BACKUP_GITHUB_TOKEN:}")
    private String githubToken;

    /** 备份仓库 */
    @Value("${GITHUB_BACKUP_REPO:}")
    private String githubBackupRepo;

    // ============= 1. 触发恢复 =============

    /**
     * 触发恢复：预检 → 双向并发互斥 → 建 PENDING → 异步跑脚本
     * @return 新建 record 的 id
     * @throws BusinessException 预检失败/互斥冲突/源备份无效时
     */
    public Long triggerRestore(HttpServletRequest request, Long sourceRecordId, String scope) {
        // 1. 预检 env
        if (backupPassword == null || backupPassword.length() < 8) {
            log.warn("恢复触发拒绝: BACKUP_ENCRYPTION_PASSWORD 未配置或长度 < 8");
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "BACKUP_ENCRYPTION_PASSWORD 未配置,请先在 /etc/myblog/myblog.env 设置");
        }
        if (githubToken == null || githubToken.isEmpty()) {
            log.warn("恢复触发拒绝: BACKUP_GITHUB_TOKEN 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "BACKUP_GITHUB_TOKEN 未配置,请先在 /etc/myblog/myblog.env 设置（注意是 BACKUP_GITHUB_TOKEN,不是发布用的 GITHUB_TOKEN）");
        }
        if (githubBackupRepo == null || githubBackupRepo.isEmpty()) {
            log.warn("恢复触发拒绝: GITHUB_BACKUP_REPO 未配置");
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "GITHUB_BACKUP_REPO 未配置,例 owner/my-blog-backup");
        }

        // 2. scope 合法性
        if (!"DB_ONLY".equals(scope) && !"DB_UPLOADS".equals(scope)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "scope 必须为 DB_ONLY 或 DB_UPLOADS, 当前: " + scope);
        }

        // 3. 查 backup_record → 必须 status=SUCCESS + tag 非空
        if (sourceRecordId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "recordId 不能为空");
        }
        BackupRecord backup = backupRecordMapper.selectById(sourceRecordId);
        if (backup == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "源备份记录不存在: id=" + sourceRecordId);
        }
        if (!"SUCCESS".equals(backup.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "只能从 SUCCESS 状态的备份恢复, 当前状态: " + backup.getStatus());
        }
        if (backup.getTag() == null || backup.getTag().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "源备份没有 tag (GitHub Release tag 缺失), 无法定位 release: id=" + sourceRecordId);
        }

        // 4. 双向并发互斥（v5 关键）
        int runningBackup = backupRecordMapper.countRunning();
        int runningRestore = restoreRecordMapper.countRunning();
        if (runningBackup > 0 || runningRestore > 0) {
            log.warn("恢复触发拒绝: 有正在执行的任务 (backup={}, restore={}), operator={}",
                runningBackup, runningRestore, AuthContext.uid(request));
            throw new BusinessException(ResultCode.BACKUP_CONFLICT,
                "已有正在执行的任务 (备份或恢复),请等待完成后再试");
        }

        // 5. 建 PENDING record
        RestoreRecord record = new RestoreRecord();
        record.setStatus("PENDING");
        record.setSourceRecordId(sourceRecordId);
        record.setSourceTag(backup.getTag());
        record.setScope(scope);
        record.setStartedAt(LocalDateTime.now());

        Object uid = AuthContext.uid(request);
        if (uid != null) {
            try {
                record.setOperatorId(Long.parseLong(uid.toString()));
            } catch (NumberFormatException ignored) {
                // 设备 ID 走字符串, 不进 operator_id
            }
        }
        record.setOperatorName(resolveOperatorName(request));
        try {
            // v4.3.2: partial unique index uk_restore_record_running 保证同一时刻只有 1 条 RUNNING
            // 两个并发请求都通过上面 countRunning()==0 后, 第二个 insert 会抛 UNIQUE constraint failed
            restoreRecordMapper.insert(record);
        } catch (Exception e) {
            // 唯一索引冲突 → 兜底转 BACKUP_CONFLICT (TOCTOU 窗口兜底)
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("UNIQUE constraint failed") && msg.contains("uk_restore_record_running")) {
                log.warn("恢复触发拒绝 (DB 唯一索引兜底): concurrent restore detected, operator={}",
                    AuthContext.uid(request));
                throw new BusinessException(ResultCode.BACKUP_CONFLICT,
                    "已有正在执行的任务 (备份或恢复),请等待完成后再试");
            }
            throw e;  // 其他异常照常抛
        }

        log.info("恢复任务创建: id={} source_record_id={} source_tag={} scope={} operator={}",
            record.getId(), sourceRecordId, backup.getTag(), scope, record.getOperatorName());

        // 6. 异步执行（必须 self 走代理, @Async 才生效）
        self.runRestoreAsync(record.getId());

        return record.getId();
    }

    /**
     * 异步执行 blog-restore.sh
     * v4 关键: 用 sudo systemd-run --scope --slice=myblog-restore
     *          --uid=myblog --gid=myblog /bin/bash <script>
     * pb.start() 后立刻 return, 状态由 RestoreStartupReconciler 双轨回填
     */
    @Async
    public void runRestoreAsync(Long recordId) {
        RestoreRecord record = restoreRecordMapper.selectById(recordId);
        if (record == null) {
            log.error("恢复 record {} 不存在, 跳过执行", recordId);
            return;
        }

        // PENDING → RUNNING
        record.setStatus("RUNNING");
        restoreRecordMapper.updateById(record);

        log.info("开始执行恢复脚本: record_id={} script={}", recordId, restoreScriptPath);

        ProcessBuilder pb = new ProcessBuilder(
            "sudo", "-n", "systemd-run",
            "--scope",
            "--slice=myblog-restore",
            "--unit=blog-restore-" + recordId,
            "--uid=myblog", "--gid=myblog",
            "/bin/bash", restoreScriptPath
        );
        pb.directory(new File(installDir));

        // 透传 env（密码/token 走 env, 不进 ps）
        pb.environment().put("BACKUP_ENCRYPTION_PASSWORD", backupPassword);
        pb.environment().put("BACKUP_GITHUB_TOKEN", githubToken);
        pb.environment().put("GITHUB_BACKUP_REPO", githubBackupRepo);
        pb.environment().put("INSTALL_DIR", installDir);
        pb.environment().put("SQLITE_PATH", sqlitePath);
        pb.environment().put("UPLOAD_DIR", uploadDir);
        pb.environment().put("RESTORE_STAGE_DIR", restoreStageDir);
        pb.environment().put("RESTORE_RESULT_DIR", restoreResultDir);
        pb.environment().put("RESTORE_RECORD_ID", String.valueOf(recordId));
        pb.environment().put("RESTORE_SOURCE_TAG", record.getSourceTag());
        pb.environment().put("RESTORE_SCOPE", record.getScope());
        pb.environment().put("RESTORE_HEALTH_URL", restoreHealthUrl);
        pb.environment().put("RESTORE_KEEP_BAKS", String.valueOf(keepBaks));
        pb.environment().put("FORCE_RESTORE", "1");

        try {
            // v4 关键: pb.start() 后**立刻 return**
            // 不 waitFor（脚本可能跑 1-5 分钟，要让它在独立 cgroup 跑完整个流程）
            // 不读 stdout/stderr（脚本自己写 .result.json 到固定路径）
            // 异常由 RestoreStartupReconciler 双轨回填兜底
            // JDK 1.8 兼容: 不取 process.pid()（Java 9+ 才有）
            pb.start();
            log.info("恢复脚本已启动: record_id={} (systemd-run --scope, 立刻 return)",
                recordId);
        } catch (Exception e) {
            // v4.3.1: pb.start() 抛异常 = 脚本根本没启动 (如 sudoers 没配 / systemd-run 不可用)
            // 不会有 .result.json, 直接标 FAILED 让用户立刻看到 (不等 30min 标 UNKNOWN)
            log.error("恢复脚本启动失败: record_id={} err={}", recordId, e.getMessage(), e);
            record.setStatus("FAILED");
            record.setFinishedAt(LocalDateTime.now());
            record.setErrorStage("START");
            record.setErrorMessage("脚本启动失败: " + e.getClass().getSimpleName()
                + ": " + (e.getMessage() == null ? "" : e.getMessage()));
            restoreRecordMapper.updateById(record);
        }
    }

    // ============= 2. 查询 =============

    public RestoreResponse getById(Long id) {
        RestoreRecord r = restoreRecordMapper.selectById(id);
        return r == null ? null : toResponse(r);
    }

    // ============= 3. 工具方法 =============

    private String resolveOperatorName(HttpServletRequest request) {
        if (request == null) return null;
        Object uid = AuthContext.uid(request);
        return uid == null ? null : "uid:" + uid;
    }

    private RestoreResponse toResponse(RestoreRecord r) {
        RestoreResponse.RestoreResponseBuilder b = RestoreResponse.builder()
            .id(r.getId())
            .status(r.getStatus())
            .sourceRecordId(r.getSourceRecordId())
            .sourceTag(r.getSourceTag())
            .scope(r.getScope())
            .startedAt(r.getStartedAt())
            .finishedAt(r.getFinishedAt())
            .errorStage(r.getErrorStage())
            .errorMessage(r.getErrorMessage())
            .verifyDiff(r.getVerifyDiff())
            .operatorId(r.getOperatorId())
            .operatorName(r.getOperatorName());
        if (r.getStartedAt() != null && r.getFinishedAt() != null) {
            b.durationSec(Duration.between(r.getStartedAt(), r.getFinishedAt()).getSeconds());
        }
        return b.build();
    }
}