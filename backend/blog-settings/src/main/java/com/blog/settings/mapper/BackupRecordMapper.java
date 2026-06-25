package com.blog.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.settings.entity.BackupRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    /**
     * 最近一次 SUCCESS 备份记录（用于 dashboard 显示"上次备份时间"）
     * 2026-06-21 新增：dashboard 待办"数据备份"项需要显示距上次成功备份的天数
     */
    @Select("SELECT * FROM backup_record WHERE status = 'SUCCESS' ORDER BY started_at DESC LIMIT 1")
    BackupRecord selectLatestSuccess();

    /**
     * 按 tag 查 backup_record（恢复后回填 backup_record 状态用）
     * 2026-06-24 BUG-001：restore 后, snapshot 内 source backup_record 仍是 RUNNING,
     *   需按 tag 定位并修复为 SUCCESS（restore 能成功 → backup 必然成功）
     */
    @Select("SELECT * FROM backup_record WHERE tag = #{tag} LIMIT 1")
    BackupRecord selectByTag(@Param("tag") String tag);

    /**
     * 查所有 RUNNING 备份记录（BUG-005 启动回填用）
     * 2026-06-24 BUG-005：JVM 在 waitFor 之后、updateById 之前被强杀时,backup_record 卡 RUNNING,
     *   新增 BackupStartupReconciler 启动时扫这些 RUNNING + 看磁盘 .result.json 回填
     */
    @org.apache.ibatis.annotations.Select(
        "SELECT * FROM backup_record WHERE status = 'RUNNING' ORDER BY started_at ASC")
    java.util.List<BackupRecord> findAllRunning();
}
