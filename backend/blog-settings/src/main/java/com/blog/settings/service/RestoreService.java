package com.blog.settings.service;

import com.blog.common.BusinessException;
import com.blog.common.ResultCode;
import com.blog.common.web.AuthContext;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.settings.dto.RestoreResponse;
import com.blog.settings.entity.BackupRecord;
import com.blog.settings.entity.RestoreRecord;
import com.blog.settings.mapper.BackupRecordMapper;
import com.blog.settings.mapper.RestoreRecordMapper;
import com.blog.settings.service.restore.RestoreExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 数据恢复服务（REQ-RESTORE-2026-06-24，v5.0 · 同进程不停服）
 *
 * 设计依据：docs/design/博客数据恢复方案设计.md
 *
 * 核心流程：
 *  1. POST /run → 预检 env + 查 backup_record → 双向并发互斥 → 建 PENDING record → 异步执行
 *  2. runRestoreAsync() → status=RUNNING → RestoreExecutor.execute(record)
 *  3. RestoreExecutor 在同 JVM 内顺序执行 6 步:
 *       DOWNLOAD → VERIFY → DECRYPT → IMPORT (SQLite Online Backup) → UPLOADS → POSTCHECK
 *  4. 成功 status=SUCCESS + verifyDiff (如有);失败 status=FAILED + errorStage + errorMessage
 *
 * 全程同 JVM 进程,不停服,无外部依赖（gh / jq / supervisorctl / sudo / systemd-run 全部不需要）。
 *
 * 安全：
 *  - 密码 / token 通过 @Value 注入,不进日志 / 异常 message
 *  - 失败信息截断至 500 字符
 *  - @Async 必须通过 self.runRestoreAsync() 调用(自调用绕过 Spring 代理失效)
 *
 * 双向并发互斥（与 v4 保留）：
 *  - restore trigger 查 backup + restore 两表 RUNNING
 *  - BackupService.triggerBackup() 也查 restore 表 RUNNING
 *
 * TODO(v5.x): 当前仅支持 SQLite profile,MySQL profile 暂不支持。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestoreService {

    private final RestoreRecordMapper restoreRecordMapper;
    private final BackupRecordMapper backupRecordMapper;
    private final RestoreExecutor restoreExecutor;

    /**
     * 自注入代理（@Lazy 解决循环依赖）
     * Spring @Async 靠动态代理, this.runRestoreAsync() 走不到代理 → 注解失效 → 同步执行
     */
    @Autowired
    @Lazy
    private RestoreService self;

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
     * 触发恢复：预检 → 双向并发互斥 → 建 PENDING → 异步执行
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

        // 4. 双向并发互斥
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
            // partial unique index uk_restore_record_running 保证同一时刻只有 1 条 RUNNING
            // 两个并发请求都通过上面 countRunning()==0 后, 第二个 insert 会抛 UNIQUE constraint failed
            restoreRecordMapper.insert(record);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("UNIQUE constraint failed") && msg.contains("uk_restore_record_running")) {
                log.warn("恢复触发拒绝 (DB 唯一索引兜底): concurrent restore detected, operator={}",
                    AuthContext.uid(request));
                throw new BusinessException(ResultCode.BACKUP_CONFLICT,
                    "已有正在执行的任务 (备份或恢复),请等待完成后再试");
            }
            throw e;
        }

        log.info("恢复任务创建: id={} source_record_id={} source_tag={} scope={} operator={}",
            record.getId(), sourceRecordId, backup.getTag(), scope, record.getOperatorName());

        // 6. 异步执行（必须 self 走代理, @Async 才生效）
        self.runRestoreAsync(record.getId());

        return record.getId();
    }

    /**
     * 异步执行恢复流水线（v5.0 同 JVM 内）
     * 全程通过 RestoreExecutor 同步推进 record 状态,无脚本 / 无跨进程通信
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
        log.info("[Restore] 开始执行 record={} sourceTag={} scope={}",
            recordId, record.getSourceTag(), record.getScope());

        RestoreExecutor.Result result;
        try {
            result = restoreExecutor.execute(record);
        } catch (Throwable t) {
            // 兜底,RestoreExecutor 内部已有 try/catch,理论上到不了这里
            log.error("[Restore] 执行抛出未捕获异常 record={}", recordId, t);
            record.setStatus("FAILED");
            record.setFinishedAt(LocalDateTime.now());
            record.setErrorStage("INTERNAL");
            record.setErrorMessage(truncate(t.getClass().getSimpleName() + ": "
                + (t.getMessage() == null ? "" : t.getMessage()), 500));
            restoreRecordMapper.updateById(record);
            return;
        }

        // 落库
        record.setFinishedAt(LocalDateTime.now());
        if (result.success) {
            record.setStatus("SUCCESS");
            if (result.verifyDiff != null && !result.verifyDiff.isEmpty()) {
                record.setVerifyDiff(truncate(result.verifyDiff, 500));
            }
            log.info("[Restore] 完成 record={} status=SUCCESS verifyDiff={}",
                recordId, result.verifyDiff == null ? "无" : "有差异");
        } else {
            record.setStatus("FAILED");
            record.setErrorStage(result.errorStage);
            record.setErrorMessage(truncate(result.errorMessage, 500));
            log.warn("[Restore] 完成 record={} status=FAILED stage={} msg={}",
                recordId, result.errorStage, result.errorMessage);
        }
        restoreRecordMapper.updateById(record);
    }

    // ============= 2. 查询 =============

    public RestoreResponse getById(Long id) {
        RestoreRecord r = restoreRecordMapper.selectById(id);
        return r == null ? null : toResponse(r);
    }

    /**
     * 恢复历史列表（分页，按 started_at 降序）
     */
    public IPage<RestoreResponse> list(int page, int size) {
        Page<RestoreRecord> pg = new Page<>(page, size);
        QueryWrapper<RestoreRecord> qw = new QueryWrapper<>();
        qw.orderByDesc("started_at");
        IPage<RestoreRecord> result = restoreRecordMapper.selectPage(pg, qw);
        return result.convert(this::toResponse);
    }

    // ============= 3. 工具方法 =============

    private String resolveOperatorName(HttpServletRequest request) {
        if (request == null) return null;
        Object uid = AuthContext.uid(request);
        if (uid == null) return null;
        String username = AuthContext.username(request);
        return (username != null && !username.isEmpty()) ? username : "uid:" + uid;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
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
