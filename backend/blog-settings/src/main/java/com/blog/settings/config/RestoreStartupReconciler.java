package com.blog.settings.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.settings.entity.RestoreRecord;
import com.blog.settings.mapper.RestoreRecordMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 恢复记录启动回填器（REQ-RESTORE-2026-06-20，v4.3.0）
 *
 * 设计依据：docs/设计文档/博客数据恢复方案设计.md §5.4
 *
 * 核心问题：
 *   RestoreService.pb.start() 立刻 return 不 waitFor, 当前 JVM 不知道脚本结果
 *   脚本在 myblog-restore.slice 独立 cgroup 里跑（systemctl stop myblog 杀不到）
 *   脚本完成后写 .result.json 到 RESTORE_RESULT_DIR（固定路径, 不进 stage）
 *
 * 双轨回填（v3 关键修复）：
 *   1. ApplicationReadyEvent: JVM 启动时跑一次, 兜住"恢复期间服务重启"场景
 *   2. @Scheduled(fixedDelay = 5min): 定时巡检, 兜住"脚本启动即失败服务没重启"场景
 *
 * 修复了两个 v2 致命 bug:
 *   - v2 onAppReady 只跑一次 + 只捞 30min 前的 RUNNING → 正常完成的 RUNNING 永远捞不到
 *   - v2 无定时巡检 → 脚本启动即失败服务没重启 → RUNNING 永远卡住
 *
 * 修复了 v3 致命 bug:
 *   - v3 scan 仍带 started_at < now - 30min 过滤 → 改成无条件扫所有 RUNNING,
 *     先看磁盘 result.json, 再看时间阈值
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestoreStartupReconciler {

    private final RestoreRecordMapper mapper;
    private final ObjectMapper objectMapper;

    /** .result.json 落点（与 blog-restore.sh 的 RESTORE_RESULT_DIR 对齐） */
    @Value("${blog.restore.result.dir:/var/lib/myblog/restore-results}")
    private String resultDir;

    /** orphan 阈值（默认 30 分钟） */
    @Value("${blog.restore.orphan.threshold.min:30}")
    private int orphanThresholdMin;

    // ============= 入口 1：JVM 启动时跑一次 =============
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("[RestoreReconciler] JVM 启动, 开始回填 RUNNING 记录");
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("[RestoreReconciler] 启动回填失败 (不阻塞启动): {}", e.getMessage(), e);
        }
    }

    // ============= 入口 2：每 5 分钟定时巡检 =============
    // 兜住"脚本启动即失败 → 服务没重启 → 永久卡 RUNNING"的窗口
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void reconcileOnSchedule() {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("[RestoreReconciler] 定时巡检失败 (下次继续): {}", e.getMessage(), e);
        }
    }

    // ============= 回填主体（双轨共用）=============
    void reconcile() {
        // v3 关键修复: 无条件扫所有 RUNNING（不带时间阈值）
        List<RestoreRecord> running = mapper.findAllRunning();
        if (running.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        int successCount = 0, failedCount = 0, orphanCount = 0, skipCount = 0;

        for (RestoreRecord r : running) {
            Path resultPath = Paths.get(resultDir, ".blog-restore-result." + r.getId() + ".json");

            if (Files.exists(resultPath)) {
                // 情况 1：有 result.json → 按它标 SUCCESS/FAILED
                try {
                    ResultJson result = objectMapper.readValue(resultPath.toFile(), ResultJson.class);
                    applyResult(r, result);
                    String finalStatus = r.getStatus();
                    if ("SUCCESS".equalsIgnoreCase(finalStatus)) successCount++;
                    else failedCount++;
                    log.info("[RestoreReconciler] 回填 record {} → {} (从 result.json)",
                        r.getId(), finalStatus);
                } catch (Exception e) {
                    // 2026-06-22 v4.x polish：原实现 catch (Exception e) 只 log.warn 不动 record——
                    //   record 会继续留在 RUNNING,直到 30min 后被孤儿逻辑标 UNKNOWN,
                    //   错失 result.json 里已有的 stage/message 字段（如果只是部分字段缺失或 status=null）。
                    //   修复：解析/校验失败也按 FAILED 标,把 e.getMessage() 写到 errorMessage,
                    //   下次 reconcile 不再重复处理(已非 RUNNING)。
                    log.warn("[RestoreReconciler] 读 result.json 失败(按 FAILED 兜底): record={} err={}",
                        r.getId(), e.getMessage());
                    r.setStatus("FAILED");
                    r.setFinishedAt(now);
                    r.setErrorStage("RESULT_PARSE_FAILED");
                    r.setErrorMessage(truncate(
                        "result.json 解析失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), 500));
                    mapper.updateById(r);
                    failedCount++;
                    // 解析失败不删 result.json,保留现场供运维排查;下次 reconcile 该 record 已非 RUNNING 不会重复进
                }
            } else if (r.getStartedAt() != null
                    && r.getStartedAt().isBefore(now.minusMinutes(orphanThresholdMin))) {
                // 情况 2：没 result + 超过阈值 → 孤儿, 标 UNKNOWN
                r.setStatus("UNKNOWN");
                r.setErrorStage("ORPHAN");
                r.setErrorMessage("脚本未在 " + orphanThresholdMin + " 分钟内产出 .result.json, 判定为孤儿");
                r.setFinishedAt(now);
                mapper.updateById(r);
                orphanCount++;
                log.warn("[RestoreReconciler] 孤儿 record {} → UNKNOWN (started={})",
                    r.getId(), r.getStartedAt());
            } else {
                // 情况 3：没 result + 时间没到阈值 → 还在跑, 跳过
                skipCount++;
                log.debug("[RestoreReconciler] record {} 还在跑, 跳过 (started={})",
                    r.getId(), r.getStartedAt());
            }
        }

        if (successCount + failedCount + orphanCount > 0) {
            log.info("[RestoreReconciler] 本轮回填完成: success={} failed={} orphan={} skip={}",
                successCount, failedCount, orphanCount, skipCount);
        }
    }

    /**
     * 应用 result.json 内容到 record
     * stage 字段: 写入 errorStage（SUCCESS 时为空, 不覆盖）
     * verifyDiff 单独存
     *
     * 2026-06-22 v4.x polish：原实现直接 result.status.toUpperCase()——若脚本崩溃写一半
     *   .result.json（status 字段缺失）→ NPE → 被外层 catch 吞 → record 永久卡 RUNNING
     *   （直到 30min 后被孤儿逻辑标 UNKNOWN,错失 stage/message 字段）。
     *   修复：status 为 null/空时按 FAILED 处理,errorStage=RESULT_INVALID,不再 NPE。
     */
    private void applyResult(RestoreRecord r, ResultJson result) {
        String status = result.status;
        if (status == null || status.trim().isEmpty()) {
            // status 缺失：脚本写了一半 .result.json。按 FAILED 处理,保留现有 stage/message（如果有）。
            r.setStatus("FAILED");
            r.setErrorStage(result.stage != null && !result.stage.isEmpty() ? result.stage : "RESULT_INVALID");
            if (result.message != null && !result.message.isEmpty()) {
                r.setErrorMessage(truncate(result.message, 500));
            } else {
                r.setErrorMessage(".result.json 缺少 status 字段,判定为不完整,按 FAILED 处理");
            }
            r.setFinishedAt(LocalDateTime.now());
            if (result.verifyDiff != null && !result.verifyDiff.isEmpty()) {
                r.setVerifyDiff(truncate(result.verifyDiff, 500));
            }
            mapper.updateById(r);
            return;
        }

        r.setStatus(status.toUpperCase());
        r.setFinishedAt(LocalDateTime.now());

        // SUCCESS: 不填 errorStage
        if (!"SUCCESS".equalsIgnoreCase(status)) {
            r.setErrorStage(result.stage);
            if (result.message != null && !result.message.isEmpty()) {
                r.setErrorMessage(truncate(result.message, 500));
            }
        }

        // verify_diff 单独存（不论 SUCCESS/FAILED 都可能有）
        if (result.verifyDiff != null && !result.verifyDiff.isEmpty()) {
            r.setVerifyDiff(truncate(result.verifyDiff, 500));
        }

        mapper.updateById(r);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * .result.json 解析对象
     * 字段名跟 blog-restore.sh 的 write_result() / jq 输出一一对齐
     */
    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultJson {
        /** SUCCESS / FAILED */
        private String status;
        /** 失败阶段（SUCCESS 为空） */
        private String stage;
        /** 失败信息 / verify_diff（截断到 500） */
        private String message;
        private String tag;
        private String scope;
        /**
         * v3 新增: 数据完整性校验差异（仅成功路径下 blog-restore.sh 写入）
         * snake_case 字段名 → @JsonProperty 显式映射
         */
        @JsonProperty("verify_diff")
        private String verifyDiff;
        private String finishedAt;
    }
}