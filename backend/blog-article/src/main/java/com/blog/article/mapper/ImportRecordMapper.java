package com.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.article.entity.ImportRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章导入记录 Mapper（2026-06-24 DEV-002）
 */
@Mapper
public interface ImportRecordMapper extends BaseMapper<ImportRecord> {
}
