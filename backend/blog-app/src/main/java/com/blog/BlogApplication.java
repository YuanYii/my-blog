package com.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

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
        // 2026-06-19：统一 JVM 默认时区为北京时间（Asia/Shanghai）。
        // 生产 ECS 在洛杉矶，若不固定时区，LocalDateTime.now()/LocalDate.now()/new Date()
        // 会取宿主机时区（洛杉矶），导致所有 Java 端写入/统计的时间偏差 15-16h。
        // 在 SpringApplication.run 之前设置，确保所有 bean 初始化即用北京时区。
        // 与 deploy-server.sh 的 -Duser.timezone=Asia/Shanghai 双保险（兼顾 dev/IDE 启动）。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(BlogApplication.class, args);
        // System.out.println("\n============================================\n"  // auto-removed by auto-bug-scan
                // + "  Blog Backend started successfully\n"  // auto-removed by auto-bug-scan
                // + "  Modules: common / auth / article / comment / settings\n"  // auto-removed by auto-bug-scan
                // + "  API doc: http://localhost:8080/api/v1/swagger-ui.html\n"  // auto-removed by auto-bug-scan
                // + "  Health:  http://localhost:8080/api/v1/actuator/health\n"  // auto-removed by auto-bug-scan
                // + "============================================\n");  // auto-removed by auto-bug-scan
    }
}
