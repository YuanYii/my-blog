package com.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.article.entity.PageView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 访问记录 mapper
 * 2026-06-16 v2.5.0 新增
 *
 * 复杂查询（统计聚合）走 JdbcTemplate，BaseMapper 只覆盖单表 CRUD。
 * INSERT IGNORE 走 @Insert 注解（MyBatis-Plus BaseMapper 不支持原生 SQL）。
 */
@Mapper
public interface PageViewMapper extends BaseMapper<PageView> {

    /**
     * 按天去重写入（UNIQUE(visitor, path, visit_date, created_at)）
     * 同样的 visitor 当天再访问同 path → affected rows = 0（不报错，IGNORE 模式）
     *
     * 2026-06-16 v2.5.0-fix：原 SQL 用 NOW()，多次同秒内插入 created_at 会变（DATETIME 精度秒），
     * 配合原 UK (visitor, path, created_at) 会因 created_at 不同导致不冲突而全部插入。
     * 改：Java 端在 PageViewService 把 created_at 截断到 visit_date 00:00:00，
     *    这里直接用 #{pv.createdAt} 写入——这样 UK 命中"完全相同 created_at" + "同 visit_date"。
     *
     * @return 1 = 新增，0 = 当天已存在
     */
    @Insert("INSERT IGNORE INTO page_view (path, article_id, visitor, ip, user_agent, referer, created_at, visit_date) " +
            "VALUES (#{pv.path}, #{pv.articleId}, #{pv.visitor}, #{pv.ip}, #{pv.userAgent}, #{pv.referer}, #{pv.createdAt}, #{pv.visitDate})")
    int insertIgnore(@Param("pv") PageView pv);
}
