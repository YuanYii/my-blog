package com.blog.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 时间字段自动填充（2026-06-19）：所有入库时间统一由 Java 计算（北京时间，见 BlogApplication
     * 固定的 Asia/Shanghai 时区），不再依赖 SQLite 的 DEFAULT CURRENT_TIMESTAMP（它永远写 UTC）。
     *
     * - insert：填 createdAt + updatedAt
     * - update（updateById / 实体 update）：刷新 updatedAt
     *
     * 仅作用于带 @TableField(fill=...) 注解的实体字段，且只通过 MyBatis-Plus 的实体 insert/update 生效；
     * 走原生 JdbcTemplate 的 page_view / comment 自行在 SQL 里传 Java 时间值；
     * UpdateWrapper + update(null, uw) 形式（实体为 null）不触发本填充，需在该处手动 set("updated_at", now)。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
