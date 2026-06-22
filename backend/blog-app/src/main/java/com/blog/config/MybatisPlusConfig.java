package com.blog.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 2026-06-22 v4.x polish：分页方言按实际 DataSource 决定，不再写死 DbType.MYSQL。
     * 原实现 pageSize > 0 时分页拦截器按 MySQL 语法追加 LIMIT，但项目主用 SQLite
     * （dev/prod 默认，v2.6.0+），虽然恰好兼容，未来切 SQL Server / PostgreSQL /
     * Oracle 会直接报语法错误。按 Connection metadata 动态选 DbType 才是稳的做法。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DataSource dataSource) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        DbType dbType = resolveDbType(dataSource);
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(dbType));
        return interceptor;
    }

    /**
     * 从 DataSource 取一条连接，查 DatabaseProductName 映射到 MyBatis-Plus DbType。
     * 取完即关，不占池——只是 metadata 查询，开销 < 1ms。
     */
    private DbType resolveDbType(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            String name = c.getMetaData().getDatabaseProductName();
            if (name == null) return DbType.MYSQL;  // 兜底
            String upper = name.toUpperCase();
            if (upper.contains("SQLITE")) return DbType.SQLITE;
            if (upper.contains("MYSQL") || upper.contains("MARIADB")) return DbType.MYSQL;
            if (upper.contains("POSTGRES")) return DbType.POSTGRE_SQL;
            if (upper.contains("ORACLE")) return DbType.ORACLE;
            if (upper.contains("MICROSOFT") || upper.contains("SQL SERVER")) return DbType.SQL_SERVER;
            if (upper.contains("H2")) return DbType.H2;
            if (upper.contains("DM")) return DbType.DM;
            // 未识别：返回 MYSQL 兜底（与历史行为一致，分页 LIMIT 子句多数方言仍兼容）
            return DbType.MYSQL;
        } catch (SQLException e) {
            // DataSource 暂不可用（如启动早期）：兜底 MYSQL，不阻塞 bean 初始化
            return DbType.MYSQL;
        }
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
