package com.blog.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.settings.entity.RestoreRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据恢复记录 Mapper（REQ-RESTORE-2026-06-20，v4.3.0）
 *
 * 与 BackupRecordMapper 对称，提供：
 *  - countRunning() → 双向并发互斥（restore trigger 查，BackupService.triggerBackup 也查）
 *  - findAllRunning() → 双轨回填（RestoreStartupReconciler 用）
 */
@Mapper
public interface RestoreRecordMapper extends BaseMapper<RestoreRecord> {

    /**
     * 检查是否有 RUNNING 任务（双向互斥用）
     * 返回 >0 = 已有, 0 = 无
     */
    @Select("SELECT COUNT(*) FROM restore_record WHERE status = 'RUNNING'")
    int countRunning();

    /**
     * 查所有 RUNNING 记录（启动时回填 + @Scheduled 定时巡检用）
     * v3 关键: 无时间过滤条件, 先看磁盘 result.json, 再看时间阈值
     * 旧版 v2 "started_at < now - 30min" 过滤会让正常完成的 RUNNING 永远捞不到
     */
    @Select("SELECT * FROM restore_record WHERE status = 'RUNNING' ORDER BY started_at ASC")
    List<RestoreRecord> findAllRunning();
}