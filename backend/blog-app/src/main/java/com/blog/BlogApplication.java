package com.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 博客后端启动类
 * 多模块聚合：扫描所有 com.blog.* 下的组件
 *
 * @EnableScheduling：2026-06-08 启用——给 ApiWhitelistService 定时刷新用
 */
@SpringBootApplication(scanBasePackages = "com.blog")
@MapperScan("com.blog.**.mapper")
@EnableScheduling
public class BlogApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
        // System.out.println("\n============================================\n"  // auto-removed by auto-bug-scan
                // + "  Blog Backend started successfully\n"  // auto-removed by auto-bug-scan
                // + "  Modules: common / auth / article / comment / settings\n"  // auto-removed by auto-bug-scan
                // + "  API doc: http://localhost:8080/api/v1/swagger-ui.html\n"  // auto-removed by auto-bug-scan
                // + "  Health:  http://localhost:8080/api/v1/actuator/health\n"  // auto-removed by auto-bug-scan
                // + "============================================\n");  // auto-removed by auto-bug-scan
    }
}
