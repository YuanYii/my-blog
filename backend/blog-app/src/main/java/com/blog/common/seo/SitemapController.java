package com.blog.common.seo;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.article.entity.Article;
import com.blog.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 动态 Sitemap 生成器：返回包含所有已发布文章的 sitemap.xml。
 *
 * 路由：GET /sitemap.xml
 * 返回：application/xml，包含首页 + 静态页 + 所有已发布文章。
 */
@RestController
@RequiredArgsConstructor
public class SitemapController {

    private final ArticleMapper articleMapper;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String BASE_URL = "https://blog.coreyai.cn";

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("status", 1).eq("deleted", 0)
          .select("slug", "updated_at")
          .orderByDesc("updated_at");
        List<Article> articles = articleMapper.selectList(qw);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // 首页
        appendUrl(xml, "/", "daily", "1.0", null);

        // 静态页
        appendUrl(xml, "/about", "monthly", "0.8", null);
        appendUrl(xml, "/archives", "weekly", "0.6", null);
        appendUrl(xml, "/tags", "weekly", "0.6", null);

        // 所有已发布文章
        for (Article article : articles) {
            String lastmod = article.getUpdatedAt() != null
                    ? article.getUpdatedAt().format(DATE_FMT)
                    : null;
            appendUrl(xml, "/post/" + article.getSlug(), "monthly", "0.7", lastmod);
        }

        xml.append("</urlset>");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(xml.toString());
    }

    private void appendUrl(StringBuilder xml, String path, String changefreq, String priority, String lastmod) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(BASE_URL).append(path).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        }
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }
}
