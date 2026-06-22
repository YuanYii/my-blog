package com.blog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2 安全基线测试（D1 安全网）。
 *
 * 按附录 C 遍历 admin 端点，验证：
 *   - 匿名访问 admin 端点 → 401
 *   - 公开 GET 端点匿名可访问（非 401）
 *
 * 本测试类在 B2 改造前先跑一次建立基线，改造后再跑对比。
 */
@DisplayName("AdminAuthFilter 鉴权基线")
class AdminAuthFilterTest extends BaseIntegrationTest {

    // 测试环境 context-path=/ → 请求路径直接是 controller mapping，不含 /api/v1 前缀
    private static final String BASE = "";

    // ===== 公开端点：匿名访问不应返回 401 =====

    @Test
    @DisplayName("GET /articles 公开列表 — 匿名可访问")
    void publicArticleList_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/articles"))
                .andExpect(status().is(not401()));
    }

    @Test
    @DisplayName("GET /articles/{slug} 公开详情 — 匿名可访问")
    void publicArticleDetail_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/articles/test-article"))
                .andExpect(status().is(not401()));
    }

    @Test
    @DisplayName("GET /articles/archives 归档 — 匿名可访问")
    void publicArchives_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/articles/archives"))
                .andExpect(status().is(not401()));
    }

    @Test
    @DisplayName("GET /articles/categories 公开分类 — 匿名可访问")
    void publicCategories_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/articles/categories"))
                .andExpect(status().is(not401()));
    }

    @Test
    @DisplayName("GET /articles/tags 公开标签 — 匿名可访问")
    void publicTags_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/articles/tags"))
                .andExpect(status().is(not401()));
    }

    @Test
    @DisplayName("POST /auth/login 登录 — 匿名可访问")
    void login_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(post(BASE + "/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().is(not401()));
    }

    @Test
    @DisplayName("POST /comments 提交评论 — 匿名可访问")
    void createComment_anonymous_notUnauthorized() throws Exception {
        mockMvc.perform(post(BASE + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":1,\"nickname\":\"test\",\"content\":\"hello\"}"))
                .andExpect(status().is(not401()));
    }

    // ===== Admin 端点：匿名访问必须返回 401 =====

    @Test
    @DisplayName("GET /articles/admin/all admin 文章列表 — 匿名 → 401")
    void adminArticleList_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/articles/admin/all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /articles/id/{id} admin 文章详情 — 匿名 → 401")
    void adminArticleById_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/articles/id/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /articles 创建文章 — 匿名 → 401")
    void createArticle_anonymous_401() throws Exception {
        mockMvc.perform(post(BASE + "/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"slug\":\"s\",\"contentMd\":\"c\",\"categoryId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /articles/{id} 更新文章 — 匿名 → 401")
    void updateArticle_anonymous_401() throws Exception {
        mockMvc.perform(put(BASE + "/articles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /articles/{id} 删除文章 — 匿名 → 401")
    void deleteArticle_anonymous_401() throws Exception {
        mockMvc.perform(delete(BASE + "/articles/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /articles/categories 创建分类 — 匿名 → 401")
    void createCategory_anonymous_401() throws Exception {
        mockMvc.perform(post(BASE + "/articles/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /articles/categories/{id} 更新分类 — 匿名 → 401")
    void updateCategory_anonymous_401() throws Exception {
        mockMvc.perform(put(BASE + "/articles/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /articles/categories/{id} 删除分类 — 匿名 → 401")
    void deleteCategory_anonymous_401() throws Exception {
        mockMvc.perform(delete(BASE + "/articles/categories/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /articles/tags 创建标签 — 匿名 → 401")
    void createTag_anonymous_401() throws Exception {
        mockMvc.perform(post(BASE + "/articles/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"t\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /articles/tags/{id} 删除标签 — 匿名 → 401")
    void deleteTag_anonymous_401() throws Exception {
        mockMvc.perform(delete(BASE + "/articles/tags/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /comments/admin admin 评论列表 — 匿名 → 401")
    void adminCommentList_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/comments/admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /comments/{id}/status 审核评论 — 匿名 → 401")
    void updateCommentStatus_anonymous_401() throws Exception {
        mockMvc.perform(put(BASE + "/comments/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /comments/{id} 删除评论 — 匿名 → 401")
    void deleteComment_anonymous_401() throws Exception {
        mockMvc.perform(delete(BASE + "/comments/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /admin/dashboard admin 面板 — 匿名 → 401")
    void adminDashboard_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/admin/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /admin/devices 设备列表 — 匿名 → 401")
    void adminDevices_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/admin/devices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /articles/categories/all admin 全量分类 — 匿名 → 401（#14 信息泄露修复）")
    void categoriesAll_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/articles/categories/all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /articles/categories/with-count admin 分类计数 — 匿名 → 401（#14）")
    void categoriesWithCount_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/articles/categories/with-count"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /auth/me 当前用户信息 — 匿名 → 401")
    void authMe_anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /auth/me/password 改密 — 匿名 → 401")
    void changePassword_anonymous_401() throws Exception {
        mockMvc.perform(put(BASE + "/auth/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"x\",\"newPassword\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===== 带有效 token + 设备的请求，admin 端点应通过鉴权 =====

    @Test
    @DisplayName("GET /articles/admin/all 带 token + 设备 — 鉴权通过（非 401）")
    void adminArticleList_withAuth_notUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/articles/admin/all")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().is(not401()));
    }

    // ===== 辅助：非 401 的 ResultMatcher（用 hamcrest 比较） =====
    private static org.hamcrest.Matcher<Integer> not401() {
        return org.hamcrest.Matchers.not(401);
    }
}
