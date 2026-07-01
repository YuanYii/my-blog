package com.blog.auth.service;

import com.blog.auth.entity.AuditLog;
import com.blog.auth.mapper.AuditLogMapper;
import com.blog.common.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志 Service（2026-07-01 DEV-004）
 *
 * 设计要点：
 *  - record() 走 @Async + JdbcTemplate 直插：避免污染业务事务；Aspect 拦截的是业务线程，异步落库不影响主路径
 *  - list() / count() 走 PageResult 分页查询，支持 target + operation 筛选
 *  - 写入失败不影响主业务（仅打 WARN）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final JdbcTemplate jdbc;

    /**
     * 异步写一条审计日志。
     *
     * 故意走 JdbcTemplate 而不是 BaseMapper.insert：
     *  - BaseMapper.insert 默认走 MetaObjectHandler 填充 createdAt，但 Aspect 是直接 Service 调用，
     *    MetaObjectHandler 链上 behavior 可能被 Spring AOP 代理吃掉；显式传 createdAt 更稳。
     *  - 跨 SQLite / MySQL 一致，避免 @TableName + Auto 行为在双 profile 下的差异。
     *
     * @param operator 操作人 username（公开下载传 "anonymous"）
     * @param operation 操作类型 CREATE/UPDATE/DELETE/APPROVE/REJECT/DOWNLOAD
     * @param target 模块名称（20 个）
     * @param detail 操作详情（可空）
     * @param ip 操作 IP（可空）
     */
    @Async
    public void record(String operator, String operation, String target, String detail, String ip) {
        try {
            LocalDateTime now = LocalDateTime.now();
            jdbc.update(
                    "INSERT INTO audit_log (operator, operation, target, detail, ip, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    operator, operation, target, detail, ip, now);
        } catch (Exception e) {
            // 审计日志写入失败不影响主业务——只打 WARN
            log.warn("[audit_log] 写库失败：operator={} operation={} target={} detail={} ip={}",
                    operator, operation, target, detail, ip, e);
        }
    }

    /**
     * 分页查询 + 筛选（target + operation 可选）。
     *
     * SQL 走字符串拼接而非 MyBatis-Plus Wrapper：
     *  - 审计日志表无 deleted 字段（不会触发 logic-delete 副作用），但 WHERE 动态拼接更易读
     *  - 过滤条件经白名单校验（target/operation 都从固定枚举取值），无 SQL 注入面
     */
    public PageResult<Map<String, Object>> list(int page, int size, String target, String operation) {
        long offset = (long) (page - 1) * size;
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();
        if (target != null && !target.isEmpty() && !"all".equalsIgnoreCase(target)) {
            where.append(" AND target = ?");
            params.add(target);
        }
        if (operation != null && !operation.isEmpty() && !"all".equalsIgnoreCase(operation)) {
            where.append(" AND operation = ?");
            params.add(operation);
        }
        String countSql = "SELECT COUNT(*) FROM audit_log" + where;
        String listSql = "SELECT id, operator, operation, target, detail, ip, created_at FROM audit_log"
                + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        List<Map<String, Object>> records = jdbc.queryForList(listSql,
                concatParams(params, size, offset));
        // 字段转换：snake_case → camelCase（与 AttachmentService.normalizeRow 一致风格）
        List<Map<String, Object>> normalized = records.stream().map(this::normalizeRow).collect(java.util.stream.Collectors.toList());
        return PageResult.of(normalized, total != null ? total : 0L, page, size);
    }

    private static Object[] concatParams(List<Object> base, Object... extras) {
        Object[] all = new Object[base.size() + extras.length];
        for (int i = 0; i < base.size(); i++) all[i] = base.get(i);
        for (int i = 0; i < extras.length; i++) all[base.size() + i] = extras[i];
        return all;
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", ((Number) row.get("id")).longValue());
        m.put("operator", row.get("operator"));
        m.put("operation", row.get("operation"));
        m.put("target", row.get("target"));
        m.put("detail", row.get("detail"));
        m.put("ip", row.get("ip"));
        m.put("createdAt", row.get("created_at"));
        return m;
    }

    /**
     * 目标模块名白名单（与 AuditLogAspect.TARGET_TABLE 对齐，用于前端下拉筛选）
     */
    public static final List<String> TARGET_OPTIONS = java.util.Arrays.asList(
            "文章", "分类", "标签", "评论", "附件",
            "个人资料", "密码修改", "站点信息", "技术栈", "个人经历",
            "主题设置", "社交链接", "偏好设置", "高级设置",
            "设备授权", "数据备份", "数据恢复", "API 白名单", "IP 封禁",
            "文件上传", "文件下载"
    );

    public static final List<String> OPERATION_OPTIONS = java.util.Arrays.asList(
            "CREATE", "UPDATE", "DELETE", "APPROVE", "REJECT", "DOWNLOAD"
    );
}
