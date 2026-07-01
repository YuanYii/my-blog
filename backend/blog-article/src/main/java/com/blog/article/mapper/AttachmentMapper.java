package com.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.article.entity.Attachment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章附件 Mapper（2026-07-01 DEV-001）
 * 软删用 MyBatis-Plus 的 @TableLogic 在 entity 上声明；
 * 想"不过滤软删"时走 service 层自定义 SQL。
 */
@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {
}