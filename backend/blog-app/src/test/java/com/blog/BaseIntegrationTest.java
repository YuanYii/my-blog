package com.blog;

import com.blog.auth.util.JwtUtil;
import com.blog.config.TestSchemaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类。
 *
 * - 用 @MockBean 替换 StringRedisTemplate，使测试不依赖真实 Redis
 * - 所有子类共享同一个 Spring context（Spring Boot test context 缓存）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSchemaConfig.class)
public abstract class BaseIntegrationTest {

    /** 所有需要鉴权的测试统一使用此 device_id（对应 data-test.sql 中的 approved 设备） */
    protected static final String TEST_DEVICE_ID = "test-device-001";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtUtil jwtUtil;

    // Redis 全部 mock —— 避免测试依赖真实 Redis；各 service 内 try-catch 保证降级安全
    @MockBean
    @SuppressWarnings("unused")
    protected StringRedisTemplate stringRedisTemplate;

    /** 每个测试方法前生成一个新 token（TTL 24h，device 绑定） */
    protected String adminToken;

    @BeforeEach
    public void generateAdminToken() {
        adminToken = jwtUtil.generate(1L, "admin", "ADMIN", TEST_DEVICE_ID);
    }
}
