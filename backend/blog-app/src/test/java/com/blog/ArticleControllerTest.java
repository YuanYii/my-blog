package com.blog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ArticleController 集成测试（B1 回归安全网）。
 *
 * 覆盖：公开列表/详情/归档/标签/分类，admin CRUD + 校验分支 + tag 关联。
 */
@DisplayName("ArticleController 集成测试")
class ArticleControllerTest extends BaseIntegrationTest {

    // 测试环境 context-path=/ → 路径直接对应 @RequestMapping，不含 /api/v1 前缀
    private static final String BASE = "";
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void cleanArticles() {
        // 清除 data-test.sql 以外的文章（保留 id=1 的测试文章用于评论测试）
        jdbc.update("DELETE FROM article_tag WHERE article_id > 1");
        jdbc.update("DELETE FROM article WHERE id > 1");
        // 清除测试新增的标签（保留 id=1）
        jdbc.update("DELETE FROM tag WHERE id > 1");
        // 清除测试新增的分类（保留 id=1）
        jdbc.update("DELETE FROM category WHERE id > 1");
    }

    // ===== 公开端点 =====

    @Test
    @DisplayName("GET /articles 公开列表 — 默认返回 published 文章且脱敏（无 createdAt/updatedAt/contentMd，含 status）")
    void list_publicArticles() throws Exception {
        mockMvc.perform(get(BASE + "/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].updatedAt").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].status").value(1))
                .andExpect(jsonPath("$.data.records[0].contentMd").doesNotExist());
    }

    @Test
    @DisplayName("GET /articles page/size 边界校验")
    void list_invalidPageSize_400() throws Exception {
        mockMvc.perform(get(BASE + "/articles").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get(BASE + "/articles").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET /articles/{slug} 已发布文章详情 — 包含 contentMd/status 且无 createdAt/updatedAt")
    void detail_publishedArticle_ok() throws Exception {
        mockMvc.perform(get(BASE + "/articles/test-article"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("test-article"))
                .andExpect(jsonPath("$.data.contentMd").exists())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createdAt").doesNotExist())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("GET /articles/{slug} 不存在文章 — 1001")
    void detail_notFound_1001() throws Exception {
        mockMvc.perform(get(BASE + "/articles/non-existent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("GET /articles/archives 归档列表 — 无 createdAt/updatedAt/contentMd，含 status")
    void archives_returnsPublished() throws Exception {
        mockMvc.perform(get(BASE + "/articles/archives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].updatedAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].status").value(1))
                .andExpect(jsonPath("$.data[0].contentMd").doesNotExist());
    }

    @Test
    @DisplayName("仅链接可见文章 (status=3) — 访客首页隐藏，管理员首页可见，直接链接可访问")
    void unlistedArticle_visibilityTest() throws Exception {
        // 创建仅链接可见文章 status=3
        jdbc.update("INSERT INTO article (id, title, slug, content_md, status, deleted, category_id, created_at, updated_at) " +
                "VALUES (200, '仅链接测试文章', 'unlisted-test', '私密内容', 3, 0, 1, datetime('now'), datetime('now'))");

        // 1. 访客匿名访问文章列表 -> 不包含 status=3
        mockMvc.perform(get(BASE + "/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records", hasSize(1)))
                .andExpect(jsonPath("$.data.records[0].slug").value("test-article"));

        // 2. 管理员带 Token 访问文章列表 -> 包含 status=3
        mockMvc.perform(get(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records", hasSize(2)));

        // 3. 访客匿名直接通过 URL 访问详情 -> 可正常读取并返回 status=3
        mockMvc.perform(get(BASE + "/articles/unlisted-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("unlisted-test"))
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.contentMd").value("私密内容"));

        // 4. 公开归档接口不包含 status=3
        mockMvc.perform(get(BASE + "/articles/archives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].slug").value("test-article"));

        // 5. SEO 直出渲染支持 status=3 并包含 noindex, nofollow
        mockMvc.perform(get(BASE + "/seo/post/unlisted-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"robots\" content=\"noindex, nofollow\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("私密内容")));
    }

    @Test
    @DisplayName("GET /articles/{id}/attachment 附件下载 — 草稿或软删文章拦截返回 404")
    void attachment_draftOrDeleted_404() throws Exception {
        // 创建草稿文章 (status=0) 并关联附件
        jdbc.update("INSERT INTO article (id, title, slug, content_md, status, deleted, created_at, updated_at) " +
                "VALUES (100, '草稿文章', 'draft-article', 'content', 0, 0, datetime('now'), datetime('now'))");
        jdbc.update("INSERT INTO article_attachment (id, article_id, file_name, file_path, file_size, mime_type, deleted, created_at, updated_at) " +
                "VALUES (100, 100, 'secret.zip', '2026/08/secret.zip', 1024, 'application/zip', 0, datetime('now'), datetime('now'))");

        mockMvc.perform(get(BASE + "/articles/100/attachment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 软删文章 (status=1, deleted=1)
        jdbc.update("UPDATE article SET status = 1, deleted = 1 WHERE id = 100");
        mockMvc.perform(get(BASE + "/articles/100/attachment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET /articles/tags 标签列表含计数")
    void tags_withArticleCount() throws Exception {
        mockMvc.perform(get(BASE + "/articles/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].articleCount").isNumber());
    }

    @Test
    @DisplayName("GET /articles/categories 公开分类（visible=1）")
    void categories_onlyVisible() throws Exception {
        mockMvc.perform(get(BASE + "/articles/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===== Admin CRUD =====

    @Test
    @DisplayName("POST /articles 创建文章 — 成功")
    void createArticle_success() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "集成测试文章");
        body.put("slug", "integration-test-article");
        body.put("contentMd", "# content");
        body.put("categoryId", 1);

        mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("integration-test-article"));
    }

    @Test
    @DisplayName("POST /articles slug 重复 — 1002")
    void createArticle_duplicateSlug_1002() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "重复 slug");
        body.put("slug", "test-article"); // 已存在
        body.put("contentMd", "# c");
        body.put("categoryId", 1);

        mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    @DisplayName("POST /articles 分类不存在 — 1001")
    void createArticle_categoryNotFound_1001() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "title");
        body.put("slug", "slug-cat-test");
        body.put("contentMd", "# c");
        body.put("categoryId", 9999);

        mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("POST /articles status=3 仅链接文章 — 创建成功")
    void createArticle_unlistedStatus_ok() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "新建仅链接文章");
        body.put("slug", "slug-unlisted-create");
        body.put("contentMd", "# 仅链接");
        body.put("categoryId", 1);
        body.put("status", 3);

        mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    @DisplayName("POST /articles status 非法 — 1010")
    void createArticle_invalidStatus_1010() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "t");
        body.put("slug", "slug-status-test");
        body.put("contentMd", "#");
        body.put("categoryId", 1);
        body.put("status", 99);

        mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1010));
    }

    @Test
    @DisplayName("POST /articles 含 tagIds — tag 关联写入并可查回")
    void createArticle_withTagIds_tagAssociated() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "文章带标签");
        body.put("slug", "article-with-tag");
        body.put("contentMd", "#");
        body.put("categoryId", 1);
        body.put("tagIds", new long[]{1L});

        String resp = mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn().getResponse().getContentAsString();

        // admin 详情应返回 tagIds
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) om.readValue(resp, Map.class).get("data");
        long id = ((Number) data.get("id")).longValue();

        mockMvc.perform(get(BASE + "/articles/admin/detail/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagIds").isArray())
                .andExpect(jsonPath("$.data.tagIds[0]").value(1));
    }

    @Test
    @DisplayName("PUT /articles/{id} 更新文章 — 成功")
    void updateArticle_success() throws Exception {
        Map<String, Object> upd = new HashMap<>();
        upd.put("title", "更新后标题");

        mockMvc.perform(put(BASE + "/articles/1")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(upd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("PUT /articles/{id} 不存在文章 — 1001")
    void updateArticle_notFound_1001() throws Exception {
        Map<String, Object> upd = new HashMap<>();
        upd.put("title", "x");

        mockMvc.perform(put(BASE + "/articles/99999")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(upd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("GET /articles/admin/all admin 列表 — 含草稿")
    void adminList_includesDrafts() throws Exception {
        // 先创建一篇草稿
        Map<String, Object> draft = new HashMap<>();
        draft.put("title", "草稿文章");
        draft.put("slug", "draft-article");
        draft.put("contentMd", "#");
        draft.put("categoryId", 1);
        draft.put("status", 0);

        mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(draft)));

        mockMvc.perform(get(BASE + "/articles/admin/all")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("DELETE /articles/{id} 删除文章 — 成功")
    void deleteArticle_success() throws Exception {
        // 先创建一篇文章再删除
        Map<String, Object> body = new HashMap<>();
        body.put("title", "待删文章");
        body.put("slug", "to-be-deleted");
        body.put("contentMd", "#");
        body.put("categoryId", 1);

        String resp = mockMvc.perform(post(BASE + "/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> delData = (Map<String, Object>) om.readValue(resp, Map.class).get("data");
        long id = ((Number) delData.get("id")).longValue();

        mockMvc.perform(delete(BASE + "/articles/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ===== 分类 CRUD =====

    @Test
    @DisplayName("POST /articles/categories 创建分类 — 成功")
    void createCategory_success() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "新分类");
        body.put("slug", "new-cat");

        mockMvc.perform(post(BASE + "/articles/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("new-cat"));
    }

    @Test
    @DisplayName("DELETE /articles/categories/{id} 有文章时拒删 — 1004")
    void deleteCategory_hasArticles_1004() throws Exception {
        // category id=1 下有 article id=1
        mockMvc.perform(delete(BASE + "/articles/categories/1")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Device-Id", TEST_DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004));
    }
}
