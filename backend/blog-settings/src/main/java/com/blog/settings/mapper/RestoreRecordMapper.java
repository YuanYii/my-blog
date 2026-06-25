package com.blog.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.settings.entity.RestoreRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据恢复记录 Mapper（REQ-RESTORE-2026-06-24，v5.0）
 *
 * 与 BackupRecordMapper 对称，提供：
 *  - countRunning() → 双向并发互斥（restore trigger 查，BackupService.triggerBackup 也查）
 *  - findAllRunning() → JVM 启动孤儿兜底（RestoreStartupReconciler 用）
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
     * 查所有 RUNNING 记录（JVM 启动孤儿兜底用）
     * v5.0 极简化: 不再有 result.json 协议,Reconciler 仅按 startedAt 阈值标 UNKNOWN
     */
    @Select("SELECT * FROM restore_record WHERE status = 'RUNNING' ORDER BY started_at ASC")
    List<RestoreRecord> findAllRunning();
}
