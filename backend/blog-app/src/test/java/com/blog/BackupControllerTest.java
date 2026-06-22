package com.blog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BackupController 集成测试（v4.2.0，REQ-BACKUP-2026-06-20）
 *
 * 覆盖：
 *  - GET  /admin/backup/list  → 空列表
 *  - GET  /admin/backup/list  → 有数据时分页
 *  - GET  /admin/backup/{id}  → 不存在返 404
 *  - GET  /admin/backup/{id}  → 存在返详情
 *  - POST /admin/backup/run   → 缺 env 返 500
 *
 * 注意：
 *  - test profile 走 test 数据,backup_record 表通过 schema-test.sql 创建
 *  - 不测真实 run(会真的启脚本+上传 GitHub),只测查询端点
 */
@DisplayName("BackupController 集成测试")
class BackupControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM backup_record");
    }

    @Test
    @DisplayName("GET /admin/backup/list - 空表")
    void listEmpty() throws Exception {
        mvc.perform(get("/admin/backup/list")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /admin/backup/list - 有 2 条数据")
    void listWithData() throws Exception {
        jdbc.update("INSERT INTO backup_record(status, started_at) VALUES('SUCCESS', datetime('now'))");
        jdbc.update("INSERT INTO backup_record(status, started_at) VALUES('FAILED', datetime('now'))");

        mvc.perform(get("/admin/backup/list")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].status").exists());
    }

    @Test
    @DisplayName("GET /admin/backup/{id} - 不存在返 404")
    void getNotFound() throws Exception {
        mvc.perform(get("/admin/backup/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET /admin/backup/{id} - 存在返详情")
    void getFound() throws Exception {
        jdbc.update("INSERT INTO backup_record(id, status, started_at, tag) VALUES(100, 'SUCCESS', datetime('now'), 'backup-test')");

        mvc.perform(get("/admin/backup/100")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.tag").value("backup-test"));
    }

    @Test
    @DisplayName("GET /admin/backup/list - 未鉴权 HTTP 401")
    void listUnauthorized() throws Exception {
        // AdminAuthFilter 在 filter 链就拦了,直接返 HTTP 401
        mvc.perform(get("/admin/backup/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /admin/backup/run - 立即返回不阻塞 (@Async 回归测试)")
    void runReturnsImmediately() throws Exception {
        // 2026-06-20 修 P0-2 后: @Async 真的异步, POST /run 应该在 < 1s 内返回
        // (未修前 @Async 自调用失效, POST 会等脚本跑完, test 里 > 30s 必失败)
        long start = System.currentTimeMillis();
        mvc.perform(post("/admin/backup/run")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                // 即使 env 缺失返 500/3001 错误码, body 形态也是 Result 包装
                .andExpect(jsonPath("$.code").exists());
        long elapsed = System.currentTimeMillis() - start;
        // 异步生效 → 立即返回 (< 1s)；同步阻塞 → 触发脚本执行 → 远超此阈值
        // 留 2s 余量给 MockMvc / Spring 启动开销
        if (elapsed > 2000) {
            throw new AssertionError("POST /run 耗时 " + elapsed + "ms,疑似 @Async 自调用失效(同步阻塞)");
        }
    }
}
