package com.blog.upgrade;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 升级服务 — 20260708-DEV-002
 *
 * 通过 HTTP 调用 upgrade-agent（127.0.0.1:28081）执行升级操作。
 * 代理负责实际执行 deploy-server.sh 并通过 SSE 流式返回日志。
 */
@Slf4j
@Service
public class UpgradeService {

    @Value("${upgrade.agent.host:127.0.0.1}")
    private String agentHost;

    @Value("${upgrade.agent.port:28081}")
    private int agentPort;

    @Value("${app.version:unknown}")
    private String appVersion;

    @Value("${GITHUB_REPO:YuanYii/my-blog}")
    private String githubRepo;

    private final JdbcTemplate jdbc;
    private final AtomicReference<String> upgradeStatus = new AtomicReference<>("idle");

    public UpgradeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取 upgrade-agent 基础 URL
     */
    private String agentBaseUrl() {
        return "http://" + agentHost + ":" + agentPort;
    }

    /**
     * 当前版本号：优先从升级记录中读取最后一次成功的版本，否则回退到 app.version
     */
    public String getCurrentVersion() {
        try {
            String version = jdbc.queryForObject(
                    "SELECT target_version FROM upgrade_record WHERE status = 'SUCCESS' ORDER BY id DESC LIMIT 1",
                    String.class);
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (Exception e) {
            // 表可能不存在，忽略
        }
        return appVersion;
    }

    private String getGithubToken() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isEmpty()) {
            token = System.getenv("GITHUB_TOKEN");
        }
        return token;
    }

    /**
     * 查询 upgrade-agent 状态
     */
    public Map<String, Object> getAgentStatus() {
        try {
            String json = httpGet(agentBaseUrl() + "/status");
            return parseJsonMap(json);
        } catch (Exception e) {
            log.warn("[upgrade] 查询 agent 状态失败: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("status", "unreachable");
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    /**
     * 查询升级日志
     */
    public Map<String, Object> getLogs(int limit) {
        try {
            String json = httpGet(agentBaseUrl() + "/logs?limit=" + limit);
            return parseJsonMap(json);
        } catch (Exception e) {
            log.warn("[upgrade] 查询 agent 日志失败: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("logs", new ArrayList<>());
            fallback.put("total", 0);
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    /**
     * 查询 GitHub Release 列表
     */
    public List<Map<String, Object>> getVersions() {
        try {
            String url = "https://api.github.com/repos/" + githubRepo + "/releases?per_page=20";
            String token = getGithubToken();
            String authHeader = (token != null && !token.isEmpty()) ? "token " + token : null;
            String json = httpGet(url, authHeader);
            return parseJsonReleaseList(json);
        } catch (Exception e) {
            log.warn("[upgrade] 查询 GitHub Release 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 检查 agent 是否可达
     */
    public boolean isAgentReachable() {
        try {
            String json = httpGet(agentBaseUrl() + "/health");
            return json != null && json.contains("UP");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置升级状态
     */
    public void setStatus(String status) {
        this.upgradeStatus.set(status);
    }

    public String getStatus() {
        return upgradeStatus.get();
    }

    // ============ HTTP 工具 ============

    private String httpGet(String urlStr) throws Exception {
        return httpGet(urlStr, null);
    }

    private String httpGet(String urlStr, String authHeader) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null && !authHeader.isEmpty()) {
            conn.setRequestProperty("Authorization", authHeader);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("HTTP " + code);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 简易 JSON Map 解析（避免引入额外依赖）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("raw", json);
            return result;
        }
    }

    /**
     * 简易 JSON Release 列表解析
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonReleaseList(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Object> raw = mapper.readValue(json, List.class);
            List<Map<String, Object>> releases = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Map) {
                    Map<String, Object> release = new LinkedHashMap<>();
                    Map<String, Object> r = (Map<String, Object>) item;
                    release.put("tagName", r.get("tag_name"));
                    release.put("name", r.get("name"));
                    release.put("publishedAt", r.get("published_at"));
                    release.put("body", r.get("body"));
                    releases.add(release);
                }
            }
            return releases;
        } catch (Exception e) {
            log.warn("[upgrade] 解析 Release 列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
