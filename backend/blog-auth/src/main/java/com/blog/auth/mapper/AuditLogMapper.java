package com.blog.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.auth.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper（2026-07-01 DEV-004）
 *
 * 通用 CRUD 由 BaseMapper 提供；分页查询走 Page<AuditLog>。
 *
 * 注意：审计日志**只插入不更新**，所以用 BaseMapper.insert 即可。
 * 历史数据查询用 jdbc.queryForList 走自定义 SQL（见 AuditLogService.list）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
