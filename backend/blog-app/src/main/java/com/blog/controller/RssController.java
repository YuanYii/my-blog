package com.blog.controller;

import com.blog.article.service.ArticleService;
import com.blog.settings.service.AdvancedSettingsAccessor;
import com.blog.settings.service.SiteSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-06-27 DEV-003：RSS Feed 公开端点。
 *
 * 路由：GET /rss 与 GET /rss.xml（context-path /api/v1 → 实际 /api/v1/rss[.xml]）
 *
 * 行为：
 *  - 高级开关 enableRss=false → 返 404
 *  - true → 返最近 N 篇 published 文章的 RSS 2.0 XML
 *
 * 不做：原子分类（atom 1.0）、subscriber 追踪、RSS Hub 推送。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RssController {

    private static final int RSS_LIMIT = 20;
    private static final DateTimeFormatter RFC822 =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final ArticleService articleService;
    private final AdvancedSettingsAccessor advancedSettings;
    private final SiteSettingsService siteSettingsService;

    // 2026-06-27 DEV-003：同时暴露 /rss 与 /rss.xml；
    // Spring MVC 默认对 .xml 走 contentNegotiation 剥后缀，所以显式写两条 path
    @GetMapping(value = {"/rss", "/rss.xml"}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> rss() { return generate(); }

    private ResponseEntity<String> generate() {
        if (!advancedSettings.rssEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("");
        }
        Map<String, Object> blog = siteSettingsService.get(SiteSettingsService.SECTION_BLOG);
        String title = blog == null ? "Blog" : String.valueOf(blog.getOrDefault("title", "Blog"));
        String description = blog == null ? "" : String.valueOf(blog.getOrDefault("description", ""));
        String siteLink = "/";

        // 取首页前 RSS_LIMIT 篇（公开列表已按 published_at desc + status=1 过滤）
        List<Map<String, Object>> items =
            articleService.list(1L, (long) RSS_LIMIT, null, null, null)
                .getData().getRecords();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<rss version=\"2.0\"><channel>");
        sb.append("<title>").append(xmlEscape(title)).append("</title>");
        sb.append("<link>").append(xmlEscape(siteLink)).append("</link>");
        sb.append("<description>").append(xmlEscape(description)).append("</description>");
        for (Map<String, Object> a : items) {
            String aTitle = String.valueOf(a.getOrDefault("title", ""));
            String aSlug = String.valueOf(a.getOrDefault("slug", ""));
            String aSummary = String.valueOf(a.getOrDefault("summary", ""));
            Object pubObj = a.get("publishedAt");
            String pubDate = formatPubDate(pubObj);
            sb.append("<item>");
            sb.append("<title>").append(xmlEscape(aTitle)).append("</title>");
            sb.append("<link>").append("/articles/").append(xmlEscape(aSlug)).append("</link>");
            sb.append("<guid isPermaLink=\"false\">").append(xmlEscape(aSlug)).append("</guid>");
            sb.append("<description>").append(xmlEscape(aSummary)).append("</description>");
            if (pubDate != null) sb.append("<pubDate>").append(pubDate).append("</pubDate>");
            sb.append("</item>");
        }
        sb.append("</channel></rss>");
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(sb.toString());
    }

    private String formatPubDate(Object pub) {
        if (pub == null) return null;
        try {
            LocalDateTime t = pub instanceof LocalDateTime ? (LocalDateTime) pub
                : LocalDateTime.parse(String.valueOf(pub).replace(' ', 'T'));
            return RFC822.format(t.atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
