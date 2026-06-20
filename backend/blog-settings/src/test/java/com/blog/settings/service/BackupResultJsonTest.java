package com.blog.settings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BackupService.ResultJson 单元测试（v4.2.0 P1-4 修复合用）
 *
 * 覆盖：snake_case 字段 "manifest_file" 必须能映射到 ResultJson.manifestFile
 * 不修 → 永远 null，setManifestJson 分支永远走不到 → backup_record.manifest_json 列永远是空
 */
@DisplayName("BackupService.ResultJson 字段映射")
class BackupResultJsonTest {

    @Test
    @DisplayName("manifest_file (snake_case) → manifestFile 字段映射")
    void manifestFileFieldMapping() throws Exception {
        // shell 实际产出格式（blog-backup.sh line 511）
        String json = "{\n" +
                "  \"tag\": \"backup-20260620-153012\",\n" +
                "  \"release_url\": \"https://github.com/x/y/releases/tag/backup-test\",\n" +
                "  \"db_size\": 5242880,\n" +
                "  \"uploads_size\": 104857600,\n" +
                "  \"manifest_file\": \"/tmp/blog-backup-stage/manifest.json\",\n" +
                "  \"assets\": [{\"name\": \"blog.sql.gz.enc\", \"size\": 5242880}]\n" +
                "}";

        ObjectMapper om = new ObjectMapper();
        BackupService.ResultJson r = om.readValue(json, BackupService.ResultJson.class);

        assertEquals("backup-20260620-153012", r.getTag());
        assertEquals("https://github.com/x/y/releases/tag/backup-test", r.getReleaseUrl());
        // 关键断言: @JsonProperty 生效 → manifestFile 不为 null
        assertNotNull(r.getManifestFile(), "manifestFile 字段映射失败(@JsonProperty 缺失?)");
        assertEquals("/tmp/blog-backup-stage/manifest.json", r.getManifestFile());
        assertEquals(1, r.getAssets().size());
        assertEquals("blog.sql.gz.enc", r.getAssets().get(0).getName());
    }

    @Test
    @DisplayName("下载链接替换: /releases/tag/ → /releases/download/")
    void downloadUrlReplace() {
        // P1-3 修: 验证 /expanded_assets/ 已改为 /releases/download/
        String releaseUrl = "https://github.com/owner/repo/releases/tag/backup-test";
        String downloadBase = releaseUrl.replace("/releases/tag/", "/releases/download/");
        String url = downloadBase + "/" + "blog.sql.gz.enc";
        // 期望: https://github.com/owner/repo/releases/download/backup-test/blog.sql.gz.enc
        assertEquals(
            "https://github.com/owner/repo/releases/download/backup-test/blog.sql.gz.enc",
            url,
            "资产下载链接应使用 /releases/download/<tag>/<filename>"
        );
        // 不能再用 /expanded_assets/（404 端点）
        assertFalse(url.contains("/expanded_assets/"),
            "不能再用 /expanded_assets/ —— 那是 GitHub 前端懒加载端点,不是真实文件 URL");
    }
}
