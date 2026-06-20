package com.blog.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.settings.entity.BackupRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 数据备份记录 Mapper（REQ-BACKUP-2026-06-20，v4.2.0）
 */
@Mapper
public interface BackupRecordMapper extends BaseMapper<BackupRecord> {

    /**
     * 检查是否有 RUNNING 任务（用于并发控制）
     * 返回 1 = 已有,0 = 无
     */
    @Select("SELECT COUNT(*) FROM backup_record WHERE status = 'RUNNING'")
    int countRunning();

    /**
     * 按状态计数（用于清理/统计）
     */
    @Select("SELECT COUNT(*) FROM backup_record WHERE status = #{status}")
    int countByStatus(String status);
}
