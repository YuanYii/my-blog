package com.blog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置
 * 访问 http://localhost:8080/api/v1/swagger-ui.html
 *
 * 安全修复（2026-06-07）：
 * - 加 @ConditionalOnProperty(matchingIfMissing=false)——只有显式设置 blog.swagger.enabled=true 才注册
 * - dev profile：application.yml 设了 true → 启；prod profile：application-prod.yml 设了 false → 关闭
 * - 防止 Swagger UI 在生产环境无鉴权暴露所有 API 端点
 */
@Configuration
@ConditionalOnProperty(name = "blog.swagger.enabled", havingValue = "true")
public class OpenApiConfig {

    @Bean
    public OpenAPI blogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Yuan Yi Blog API")
                        .description("个人博客后端 API 文档（多模块：common / auth / article / comment / settings）")
                        .version("v0.1.0")
                        .contact(new Contact()
                                .name("Yuan Yi")
                                .email("hello@example.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
