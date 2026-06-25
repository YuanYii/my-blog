package com.blog.settings.config;

import com.blog.settings.entity.BackupRecord;
import com.blog.settings.mapper.BackupRecordMapper;
import com.blog.settings.service.BackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份记录启动回填器（BUG-005, 2026-06-24, v4.4.0）
 *
 * 解决场景：
 *   `BackupService.runScriptCore` 用 `process.waitFor` 同步等待脚本退出,
 *   正常路径下 SUCCESS/FAILED 都在同一调用栈中标记。
 *   但若 JVM 在 waitFor 之后、updateById 之前被强杀（kill -9 / OOM）,
 *   backup_record 永远卡 RUNNING,前端列表持续显示"执行中"。
 *
 * 对称参考：`RestoreStartupReconciler`(BUG-001)。差异：
 *   - backup 不会 wipe 当前 DB,backup_record 永远在,只需 RUNNING 兜底
 *   - 不需要"双写元信息" + "孤儿重建"逻辑
 *
 * 启动节奏:
 *   1. `ApplicationReadyEvent`: JVM 启动一次 + 5s 后再跑一次（对齐 RestoreReconciler BUG-004 修复）
 *   2. `@Scheduled(fixedDelay=60s, initialDelay=60s)`: 定时巡检
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupStartupReconciler {

    private final BackupRecordMapper backupMapper;
    private final ObjectMapper objectMapper;

    /** STAGE_DIR 父目录,result.json 落点（与 blog-backup.sh:201 RESULT_FILE 对齐） */
    @Value("${BACKUP_STAGE_DIR:/tmp/blog-backup-stage}")
    private String backupStageDir;

    /** 孤儿阈值（默认 30 分钟,RUNNING + 无 result.json + 超阈值 → UNKNOWN） */
    @Value("${blog.backup.orphan.threshold.min:30}")
    private int orphanThresholdMin;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("[BackupReconciler] JVM 启动, 开始回填 RUNNING 备份记录");
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("[BackupReconciler] 启动回填失败 (不阻塞启动): {}", e.getMessage(), e);
        }
        // 对齐 BUG-004:5s 后再跑一次,覆盖"脚本写 .result.json 比 JVM 启动稍晚"窗口
        try {
            Thread.sleep(5_000);
            log.info("[BackupReconciler] 启动 5s 后二次回填");
            reconcile();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[BackupReconciler] 启动 5s 后回填失败: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 60 * 1000)
    public void reconcileOnSchedule() {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("[BackupReconciler] 定时巡检失败 (下次继续): {}", e.getMessage(), e);
        }
    }

    void reconcile() {
        List<BackupRecord> running = backupMapper.findAllRunning();
        if (running.isEmpty()) return;

        Path stageDirParent = Paths.get(backupStageDir).getParent();
        if (stageDirParent == null) stageDirParent = Paths.get("/tmp");

        LocalDateTime now = LocalDateTime.now();
        int successCount = 0, failedCount = 0, orphanCount = 0, skipCount = 0;

        for (BackupRecord r : running) {
            Path resultPath = stageDirParent.resolve(".blog-backup-result." + r.getId() + ".json");

            if (Files.exists(resultPath)) {
                // 有 result.json → 解析并标 SUCCESS
                try {
                    BackupService.ResultJson result = objectMapper.readValue(
                        resultPath.toFile(), BackupService.ResultJson.class);
                    applySuccess(r, result, stageDirParent);
                    successCount++;
                    log.info("[BackupReconciler] 回填 backup_record {} → SUCCESS (从 result.json)", r.getId());
                } catch (Exception e) {
                    // 解析失败:按 FAILED 兜底,避免永久卡 RUNNING（对齐 RestoreReconciler 的 RESULT_PARSE_FAILED 路径）
                    log.warn("[BackupReconciler] 读 result.json 失败(按 FAILED 兜底): record={} err={}",
                        r.getId(), e.getMessage());
                    r.setStatus("FAILED");
                    r.setFinishedAt(now);
                    r.setErrorStage("RESULT_PARSE_FAILED");
                    r.setErrorMessage(truncate(
                        "result.json 解析失败: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage(), 500));
                    backupMapper.updateById(r);
                    failedCount++;
                }
            } else if (r.getStartedAt() != null
                    && r.getStartedAt().isBefore(now.minusMinutes(orphanThresholdMin))) {
                // 没 result + 超阈值 → 孤儿
                // 用 FAILED 替代 UNKNOWN（backup_record 没有 UNKNOWN 状态,且语义接近"失败"）
                r.setStatus("FAILED");
                r.setErrorStage("ORPHAN");
                r.setErrorMessage("脚本未在 " + orphanThresholdMin
                    + " 分钟内产出 .result.json, 判定为孤儿");
                r.setFinishedAt(now);
                backupMapper.updateById(r);
                orphanCount++;
                log.warn("[BackupReconciler] 孤儿 record {} → FAILED/ORPHAN (started={})",
                    r.getId(), r.getStartedAt());
            } else {
                // 没 result + 时间没到阈值 → 还在跑
                skipCount++;
                log.debug("[BackupReconciler] record {} 还在跑, 跳过 (started={})",
                    r.getId(), r.getStartedAt());
            }
        }

        if (successCount + failedCount + orphanCount > 0) {
            log.info("[BackupReconciler] 本轮回填完成: success={} failed={} orphan={} skip={}",
                successCount, failedCount, orphanCount, skipCount);
        }
    }

    /**
     * 把 result.json 内容应用到 record（SUCCESS 路径,对齐 BackupService.runScriptCore:328-369 主流程）
     */
    private void applySuccess(BackupRecord r, BackupService.ResultJson result, Path stageDirParent) {
        r.setStatus("SUCCESS");
        r.setFinishedAt(LocalDateTime.now());
        r.setTag(result.getTag());
        r.setDbSize(result.getDbSize() == null ? 0L : result.getDbSize());
        r.setUploadsSize(result.getUploadsSize() == null ? 0L : result.getUploadsSize());
        r.setAssetCount(result.getAssets() == null ? 0 : result.getAssets().size());

        // 拼 asset URL 列表
        List<String> urls = new ArrayList<>();
        String releaseUrl = result.getReleaseUrl();
        if (releaseUrl != null && !releaseUrl.isEmpty() && result.getAssets() != null) {
            String downloadBase = releaseUrl.replace("/releases/tag/", "/releases/download/");
            for (BackupService.ResultJson.Asset a : result.getAssets()) {
                urls.add(downloadBase + "/" + a.getName());
            }
        }
        try {
            r.setAssetUrls(objectMapper.writeValueAsString(urls));
        } catch (Exception e) {
            log.warn("[BackupReconciler] 序列化 asset_urls 失败: {}", e.getMessage());
        }

        // manifest.json 原文（如可读）
        if (result.getManifestFile() != null && !result.getManifestFile().isEmpty()) {
            Path manifestPath = Paths.get(result.getManifestFile());
            if (Files.exists(manifestPath)) {
                try {
                    String manifestStr = new String(
                        Files.readAllBytes(manifestPath), java.nio.charset.StandardCharsets.UTF_8);
                    if (manifestStr.length() > 65536) {
                        manifestStr = manifestStr.substring(0, 65536) + "...";
                    }
                    r.setManifestJson(manifestStr);
                } catch (IOException e) {
                    log.warn("[BackupReconciler] 读 manifest.json 失败 (不影响 SUCCESS 标记): record={} err={}",
                        r.getId(), e.getMessage());
                }
            }
        }

        backupMapper.updateById(r);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
