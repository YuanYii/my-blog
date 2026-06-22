package com.blog.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 测试专用 DataSource。
 *
 * 核心目的：在 DataSource bean 创建时（最早时机）立即执行 schema-test.sql + data-test.sql，
 * 确保表和种子数据在任何 @PostConstruct（ApiWhitelistService.init 等）运行前就绪。
 *
 * 若仅靠 spring.datasource.schema / spring.sql.init，Spring Boot 2.7 不保证 schema 在
 * ApiWhitelistService @PostConstruct 前完成，会出现"no such table: api_whitelist"。
 */
@TestConfiguration
public class TestSchemaConfig {

    @Bean
    @Primary
    public DataSource testDataSource() throws Exception {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setJdbcUrl("jdbc:sqlite:file::memory:?cache=shared");
        ds.setMaximumPoolSize(1);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5000);

        // 在此处同步执行 schema + seed，早于所有依赖 DataSource 的 bean 的 @PostConstruct
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-test.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("data-test.sql"));
        }
        return ds;
    }
}
