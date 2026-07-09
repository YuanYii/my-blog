package com.blog.upgrade;

import com.blog.auth.service.AuditLogService;
import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 升级控制器 — 20260708-DEV-002 + 2026-07-08 升级记录
 *
 * 管理系统升级操作：触发升级、查询状态、获取版本列表、回滚、查询升级历史。
 * 通过 HTTP 调用 upgrade-agent（127.0.0.1:28081）执行实际升级。
 */
@Slf4j
@RestController
@RequestMapping("/admin/upgrade")
@RequiredArgsConstructor
public class UpgradeController {

    private final UpgradeService upgradeService;
    private final UpgradeRecordService upgradeRecordService;
    private final AuditLogService auditLogService;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @Value("${upgrade.agent.host:127.0.0.1}")
    private String agentHost;

    @Value("${upgrade.agent.port:28081}")
    private int agentPort;

    /**
     * POST /admin/upgrade — 触发升级，SSE 流式返回日志
     * version 可选：为空时自动获取 GitHub 最新 release
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter upgrade(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String version = String.valueOf(body.getOrDefault("version", "")).trim();
        String mode = String.valueOf(body.getOrDefault("mode", "full")).trim();
        boolean importDb = Boolean.TRUE.equals(body.get("importDb"));
        String confirm = String.valueOf(body.getOrDefault("confirm", "")).trim();

        // version 为空时，尝试获取最新版本
        if (version.isEmpty()) {
            try {
                version = upgradeService.getLatestVersion();
            } catch (Exception e) {
                return sendError("无法获取最新版本：" + e.getMessage());
            }
            if (version.isEmpty()) {
                return sendError("version 不能为空，也无法获取最新版本");
            }
        }
        if (!"full".equals(mode) && !"init".equals(mode)) {
            return sendError("mode 必须是 full 或 init");
        }
        if (importDb && !"确认".equals(confirm)) {
            return sendError("importDb=true 时需输入 confirm=\"确认\"");
        }

        SseEmitter emitter = new SseEmitter(600_000L);

        String operator = AuthContext.username(request);
        Object operatorUid = AuthContext.uid(request);
        String ip = request.getRemoteAddr();
        String fromVersion = upgradeService.getCurrentVersion();

        // 创建升级记录
        UpgradeRecord record = upgradeRecordService.createRecord(
                version, mode, importDb,
                operatorUid != null ? Long.valueOf(String.valueOf(operatorUid)) : null,
                operator, ip, fromVersion);
        upgradeRecordService.markRunning(record.getId());

        String detail = String.format("version=%s, mode=%s, importDb=%s", version, mode, importDb);

        // 创建 final 变量供 lambda 使用
        final String finalVersion = version;

        sseExecutor.execute(() -> {
            try {
                String agentUrl = "http://" + agentHost + ":" + agentPort + "/upgrade";
                String requestBody = String.format(
                    "{\"version\":\"%s\",\"mode\":\"%s\",\"importDb\":%s,\"confirm\":\"%s\"}",
                    finalVersion, mode, importDb, confirm
                );

                // 连接 agent（捕获 ConnectException）
                HttpURLConnection conn;
                try {
                    URL url = new URL(agentUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(1800_000);  // 30 分钟，deploy-server.sh 执行时间可能很长
                    conn.getOutputStream().write(requestBody.getBytes("UTF-8"));
                } catch (java.net.ConnectException e) {
                    String errMsg = "升级代理未运行（" + agentHost + ":" + agentPort + "）";
                    log.error("[upgrade] {}", errMsg, e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(errMsg));
                        emitter.complete();
                    } catch (Exception ignored) {}
                    upgradeRecordService.markFailed(record.getId(), errMsg);
                    auditLogService.record(operator, "UPGRADE", "系统升级",
                            detail + ", result=failed, reason=" + errMsg, ip);
                    return;
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    emitter.send(SseEmitter.event().name("error").data("Agent 返回 HTTP " + responseCode));
                    emitter.complete();
                    upgradeRecordService.markFailed(record.getId(), "Agent HTTP " + responseCode);
                    auditLogService.record(operator, "UPGRADE", "系统升级",
                            detail + ", result=failed, reason=agent HTTP " + responseCode, ip);
                    return;
                }

                upgradeService.setStatus("running");

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event: ")) {
                            String eventName = line.substring(7).trim();
                            if ("done".equals(eventName)) {
                                String dataLine = reader.readLine();
                                if (dataLine != null && dataLine.startsWith("data: ")) {
                                    String data = dataLine.substring(6);
                                    emitter.send(SseEmitter.event().name("done").data(data));
                                    boolean success = data.contains("\"success\":true");
                                    upgradeService.setStatus(success ? "completed" : "failed");
                                    if (success) {
                                        upgradeRecordService.markSuccess(record.getId());
                                    } else {
                                        upgradeRecordService.markFailed(record.getId(), "升级失败");
                                    }
                                    auditLogService.record(operator, "UPGRADE", "系统升级",
                                            detail + ", result=" + (success ? "success" : "failed"), ip);
                                }
                            }
                        } else if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            emitter.send(SseEmitter.event().name("log").data(data));
                        }
                    }
                }

                emitter.complete();

            } catch (Exception e) {
                log.error("[upgrade] 升级执行异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("升级异常: " + e.getMessage()));
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
                upgradeService.setStatus("failed");
                upgradeRecordService.markFailed(record.getId(), e.getMessage());
                auditLogService.record(operator, "UPGRADE", "系统升级",
                        detail + ", result=failed, reason=" + e.getMessage(), ip);
            }
        });

        return emitter;
    }

    /**
     * GET /admin/upgrade/status — 查询当前升级状态
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentVersion", upgradeService.getCurrentVersion());
        data.put("agentStatus", upgradeService.getAgentStatus());
        data.put("upgradeStatus", upgradeService.getStatus());
        return Result.success(data);
    }

    /**
     * GET /admin/upgrade/versions — 查询 GitHub Release 列表
     */
    @GetMapping("/versions")
    public Result<List<Map<String, Object>>> versions() {
        return Result.success(upgradeService.getVersions());
    }

    /**
     * GET /admin/upgrade/records — 查询升级历史
     */
    @GetMapping("/records")
    public Result<PageResult<Map<String, Object>>> records(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(upgradeRecordService.list(page, size));
    }

    /**
     * POST /admin/upgrade/rollback — 回滚到指定版本
     */
    @PostMapping("/rollback")
    public Result<Map<String, Object>> rollback(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String version = String.valueOf(body.getOrDefault("version", "")).trim();
        if (version.isEmpty()) {
            return Result.error(400, "version 不能为空");
        }

        String operator = AuthContext.username(request);
        String ip = request.getRemoteAddr();

        log.info("[upgrade] 回滚请求: version={}, operator={}", version, operator);
        auditLogService.record(operator, "ROLLBACK", "系统回滚",
                "targetVersion=" + version, ip);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "回滚请求已记录，请通过升级代理执行: mode=full, version=" + version);
        data.put("version", version);
        return Result.success(data);
    }

    private SseEmitter sendError(String message) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (Exception ignored) {}
        return emitter;
    }
}
