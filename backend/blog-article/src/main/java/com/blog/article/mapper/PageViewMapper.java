package com.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.article.entity.PageView;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问记录 mapper
 * 2026-06-16 v2.5.0 新增
 * 2026-06-17 v2.6.0：去掉方言特定 SQL
 *  - 原 `@Insert("INSERT IGNORE INTO ...")` 在 SQLite 下语法错（SQLite 用 `INSERT OR IGNORE`）
 *  - 改用 MyBatis-Plus BaseMapper.insert() 走通用 INSERT
 *  - 去重逻辑移到 PageViewService 业务层（SELECT COUNT + INSERT 两步）
 */
@Mapper
public interface PageViewMapper extends BaseMapper<PageView> {
    // 通用 insert 由 BaseMapper 提供，业务层 PageViewService 负责去重
}
