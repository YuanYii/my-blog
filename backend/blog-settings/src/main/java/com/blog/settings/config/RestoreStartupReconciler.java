package com.blog.settings.config;

import com.blog.settings.entity.RestoreRecord;
import com.blog.settings.mapper.RestoreRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 恢复记录启动回填器（REQ-RESTORE-2026-06-24，v5.0 · 极简版）
 *
 * 设计依据：docs/design/博客数据恢复方案设计.md §4.2 + §5.5
 *
 * v5 简化背景：
 *   v4 时代恢复走外部脚本 + JVM 重启 + result.json 跨进程通信,需要双轨触发
 *   (ApplicationReadyEvent + 5s 二次 + 60s scheduled)+ 孤儿重建 (reconcileOrphanResults)
 *   + meta 文件等一大套机制兜底各种时序窗口。
 *
 *   v5 改为同 JVM 内执行,RestoreExecutor 全程同步推进 record 状态,无跨进程通信。
 *   本类仅保留一种场景的兜底:
 *
 *   场景: JVM 在 RUNNING 中途崩溃(OOM / kill -9 / 宿主机断电),新 JVM 起来时
 *         restore_record 滞留在 RUNNING 状态。
 *   处置: ApplicationReadyEvent 触发时扫一次,把 started_at < now - 30min 的 RUNNING
 *         记录标 UNKNOWN(errorStage=ORPHAN),用户从前端可以看到结果。
 *
 * 不再保留的逻辑(已无触发场景):
 *   - 5s 延迟二次触发(v4 BUG-004 修的"脚本写 result.json 滞后"窗口)
 *   - @Scheduled 60s 定时巡检(v4 兜"脚本启动即失败"场景)
 *   - reconcileOrphanResults(v4 BUG-001 恢复后 restore_record 被 wipe 的孤儿重建)
 *   - applyResult / ResultJson / RestoreMeta 等跨进程通信结构
 *   - fixRunningToSuccessByTag 反向修正 backup_record(BackupStartupReconciler 已独立处理)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestoreStartupReconciler {

    private final RestoreRecordMapper mapper;

    /** orphan 阈值(默认 30 分钟):RUNNING > 此值才标 UNKNOWN */
    @Value("${blog.restore.orphan.threshold.min:30}")
    private int orphanThresholdMin;

    /**
     * JVM 启动时扫一次 RUNNING 记录
     * 处理"JVM 在恢复中途崩溃,新 JVM 起来后状态机滞留 RUNNING"场景
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("[RestoreReconciler] JVM 启动, 开始扫 RUNNING 孤儿记录");
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("[RestoreReconciler] 启动回填失败 (不阻塞启动): {}", e.getMessage(), e);
        }
    }

    /** 主扫描逻辑(单独抽出便于单元测试) */
    void reconcile() {
        List<RestoreRecord> running = mapper.findAllRunning();
        if (running == null || running.isEmpty()) {
            log.info("[RestoreReconciler] 无 RUNNING 记录");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusMinutes(orphanThresholdMin);
        int orphanCount = 0, skipCount = 0;
        for (RestoreRecord r : running) {
            if (r.getStartedAt() != null && r.getStartedAt().isBefore(threshold)) {
                // 超阈值 → JVM 崩溃留下的孤儿,标 UNKNOWN
                r.setStatus("UNKNOWN");
                r.setErrorStage("ORPHAN");
                r.setErrorMessage("JVM 在 RUNNING 状态中途崩溃 (startedAt > "
                    + orphanThresholdMin + " 分钟前),判定为孤儿");
                r.setFinishedAt(now);
                mapper.updateById(r);
                orphanCount++;
                log.warn("[RestoreReconciler] 孤儿 record {} → UNKNOWN (started={})",
                    r.getId(), r.getStartedAt());
            } else {
                skipCount++;
                log.debug("[RestoreReconciler] record {} 仍在阈值内,跳过 (started={})",
                    r.getId(), r.getStartedAt());
            }
        }
        if (orphanCount > 0) {
            log.info("[RestoreReconciler] 本轮回填完成: orphan={} skip={}", orphanCount, skipCount);
        }
    }
}
