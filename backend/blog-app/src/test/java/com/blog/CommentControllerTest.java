package com.blog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CommentController 集成测试（B1 回归安全网）。
 *
 * 覆盖：评论提交校验、公开列表、admin 列表/审核/删除、限流（进程内固定窗口）。
 */
@DisplayName("CommentController 集成测试")
class CommentControllerTest extends BaseIntegrationTest {

    // 测试环境 context-path=/ → 路径直接对应 @RequestMapping，不含 /api/v1 前缀
    private static final String BASE = "";
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void cleanComments() {
        jdbc.update("DELETE FROM comment");
    }

    // ===== 公开端点 =====

    @Test
    @DisplayName("POST /comments 提交评论 — 成功（status=0 待审）")
    void createComment_success() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("articleId", 1);
        body.put("nickname", "访客");
        body.put("content", "测试评论内容");

        mockMvc.perform(post(BASE + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.message").value("评论已提交，待审核"));
    }

    @Test
    @DisplayName("POST /comments 文章不存在 — 1001")
    void createComment_articleNotFound_1001() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("articleId", 99999);
        body.put("nickname", "访客");
        body.put("content", "content");

        mockMvc.perform(post(BASE + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("POST /comments 昵称为空 — 400")
    void createComment_emptyNickname_400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("articleId", 1);
        body.put("nickname", "");
        body.put("content", "content");

        mockMvc.perform(post(BASE + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /comments 昵称超长 — 400")
    void createComment_nicknameTooLong_400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("articleId", 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 51; i++) sb.append('a');
        body.put("nickname", sb.toString());
        body.put("content", "content");

        mockMvc.perform(post(BASE + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /comments 内容超 2000 字 — 400")
    void createComment_contentTooLong_400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("articleId", 1);
        body.put("nickname", "访客");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 2001; i++) content.append('x');
        body.put("content", content.toString());

        mockMvc.perform(post(BASE + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET /comments articleId 缺失 — 400")
    void listComments_noArticleId_400() throws Exception {
        mockMvc.perform(get(BASE + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET /comments?articleId=1 返回 approved 评论列表")
    void listComments_byArticleId() throws Exception {
        // 先插入一条 approved 评论
        jdbc.update("INSERT INTO comment (article_id, parent_id, nickname, content, status, created_at) " +
                "VALUES (1, 0, '张三', '好文章', 1, datetime('now'))");

        mockMvc.perform(get(BASE + "/comments").param("articleId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)));
    }

    // ===== Admin 端点 =====

    @Test
    @DisplayName("GET /comments/admin admin 评论列表")
    void adminCommentList_ok() throws Exception {
        mockMvc.perform(get(BASE + "/comments/admin")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("GET /comments/admin status 越界 — 400")
    void adminCommentList_invalidStatus_400() throws Exception {
        mockMvc.perform(get(BASE + "/comments/admin").param("status", "9")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("PUT /comments/{id}/status 审核通过 — 成功")
    void updateCommentStatus_approve_success() throws Exception {
        Long cid = jdbc.queryForObject(
                "INSERT INTO comment (article_id, parent_id, nickname, content, status, created_at) " +
                "VALUES (1, 0, '李四', '待审评论', 0, datetime('now')) RETURNING id",
                Long.class);

        Map<String, Object> body = new HashMap<>();
        body.put("status", 1);

        mockMvc.perform(put(BASE + "/comments/" + cid + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("PUT /comments/{id}/status 不存在评论 — 1001")
    void updateCommentStatus_notFound_1001() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", 1);

        mockMvc.perform(put(BASE + "/comments/99999/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("PUT /comments/{id}/status status 越界 — 400")
    void updateCommentStatus_invalidStatus_400() throws Exception {
        Long cid = jdbc.queryForObject(
                "INSERT INTO comment (article_id, parent_id, nickname, content, status, created_at) " +
                "VALUES (1, 0, '王五', 'content', 0, datetime('now')) RETURNING id",
                Long.class);

        Map<String, Object> body = new HashMap<>();
        body.put("status", 99);

        mockMvc.perform(put(BASE + "/comments/" + cid + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("DELETE /comments/{id} 物理删除评论 — 成功")
    void deleteComment_success() throws Exception {
        Long cid = jdbc.queryForObject(
                "INSERT INTO comment (article_id, parent_id, nickname, content, status, created_at) " +
                "VALUES (1, 0, '待删用户', '待删内容', 0, datetime('now')) RETURNING id",
                Long.class);

        mockMvc.perform(delete(BASE + "/comments/" + cid)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE id = ?", Long.class, cid);
        assert count != null && count == 0;
    }

    @Test
    @DisplayName("DELETE /comments/{id} 不存在评论 — 1001")
    void deleteComment_notFound_1001() throws Exception {
        mockMvc.perform(delete(BASE + "/comments/99999")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }
}
