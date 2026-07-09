package com.blog.upgrade;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.PageResult;
import com.blog.upgrade.mapper.UpgradeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 升级记录 Service（2026-07-08）
 *
 * 记录每次升级操作的状态和结果。
 * 时间使用 JVM 默认时区（Asia/Shanghai）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpgradeRecordService {

    private final UpgradeRecordMapper upgradeRecordMapper;
    private final JdbcTemplate jdbc;

    /**
     * 创建升级记录（PENDING 状态）
     */
    public UpgradeRecord createRecord(String targetVersion, String mode, boolean importDb,
                                       Long operatorId, String operatorName, String ip, String fromVersion) {
        UpgradeRecord record = new UpgradeRecord();
        record.setTargetVersion(targetVersion);
        record.setMode(mode);
        record.setImportDb(importDb);
        record.setStatus("PENDING");
        record.setStartedAt(LocalDateTime.now());
        record.setFromVersion(fromVersion);
        record.setOperatorId(operatorId);
        record.setOperatorName(operatorName);
        record.setIp(ip);
        upgradeRecordMapper.insert(record);
        return record;
    }

    /**
     * 更新状态为 RUNNING
     */
    public void markRunning(Long id) {
        jdbc.update("UPDATE upgrade_record SET status = 'RUNNING' WHERE id = ?", id);
    }

    /**
     * 更新状态为 SUCCESS
     */
    public void markSuccess(Long id) {
        jdbc.update("UPDATE upgrade_record SET status = 'SUCCESS', finished_at = ? WHERE id = ?",
                LocalDateTime.now(), id);
    }

    /**
     * 更新状态为 FAILED
     */
    public void markFailed(Long id, String errorMessage) {
        jdbc.update("UPDATE upgrade_record SET status = 'FAILED', finished_at = ?, error_message = ? WHERE id = ?",
                LocalDateTime.now(), errorMessage, id);
    }

    /**
     * 分页查询升级历史
     */
    public PageResult<Map<String, Object>> list(int page, int size) {
        long offset = (long) (page - 1) * size;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM upgrade_record", Long.class);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT id, target_version, mode, import_db, status, started_at, finished_at, "
                        + "from_version, error_message, operator_name, ip "
                        + "FROM upgrade_record ORDER BY id DESC LIMIT ? OFFSET ?",
                size, offset);

        List<Map<String, Object>> normalized = records.stream().map(this::normalizeRow)
                .collect(java.util.stream.Collectors.toList());
        return PageResult.of(normalized, total != null ? total : 0L, page, size);
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", ((Number) row.get("id")).longValue());
        m.put("targetVersion", row.get("target_version"));
        m.put("mode", row.get("mode"));
        m.put("importDb", row.get("import_db"));
        m.put("status", row.get("status"));
        m.put("startedAt", row.get("started_at"));
        m.put("finishedAt", row.get("finished_at"));
        m.put("fromVersion", row.get("from_version"));
        m.put("errorMessage", row.get("error_message"));
        m.put("operatorName", row.get("operator_name"));
        m.put("ip", row.get("ip"));
        return m;
    }
}
