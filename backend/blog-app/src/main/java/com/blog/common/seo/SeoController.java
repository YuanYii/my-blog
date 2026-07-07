package com.blog.common.seo;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.article.entity.Article;
import com.blog.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * SEO 页面控制器：返回含文章内容的 HTML 页面（渐进增强方案）。
 *
 * 路由：GET /seo/post/{slug}
 * 返回：text/html，包含静态内容层（搜索引擎读）+ Vue SPA 入口（用户看）。
 *
 * 与 ArticleController.detail 的区别：
 *  - 不累加 view_count（爬虫访问不计入浏览量）
 *  - 返回 HTML 而非 JSON
 *  - 不走 AdminAuthFilter
 */
@Slf4j
@RestController
@RequestMapping("/seo")
@RequiredArgsConstructor
public class SeoController {

    private final ArticleMapper articleMapper;
    private final MarkdownRenderer markdownRenderer;

    @GetMapping(value = "/post/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> articlePage(@PathVariable String slug) {
        QueryWrapper<Article> qw = new QueryWrapper<>();
        qw.eq("slug", slug).eq("status", 1).eq("deleted", 0);
        Article article = articleMapper.selectOne(qw);

        if (article == null) {
            return ResponseEntity.notFound().build();
        }

        String contentHtml = markdownRenderer.render(article.getContentMd());
        String html = buildHtmlPage(article, contentHtml, slug);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(html);
    }

    private String buildHtmlPage(Article article, String contentHtml, String slug) {
        String title = escapeHtml(article.getTitle());
        String summary = escapeHtml(article.getSummary() != null ? article.getSummary() : article.getTitle());
        String url = "https://blog.coreyai.cn/post/" + slug;
        String ogImage = article.getCoverUrl() != null
                ? "<meta property=\"og:image\" content=\"" + escapeHtml(article.getCoverUrl()) + "\">"
                : "";

        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n"
                + "<head>\n"
                + "    <meta charset=\"UTF-8\">\n"
                + "    <title>" + title + " | blog.coreyai.cn</title>\n"
                + "    <meta name=\"description\" content=\"" + summary + "\">\n"
                + "    <meta property=\"og:title\" content=\"" + title + "\">\n"
                + "    <meta property=\"og:description\" content=\"" + summary + "\">\n"
                + "    <meta property=\"og:type\" content=\"article\">\n"
                + "    <meta property=\"og:url\" content=\"" + url + "\">\n"
                + "    " + ogImage + "\n"
                + "    <link rel=\"canonical\" href=\"" + url + "\">\n"
                + "    <style>\n"
                + "        body { max-width: 720px; margin: 0 auto; padding: 20px; font-family: system-ui, -apple-system, sans-serif; line-height: 1.8; color: #333; }\n"
                + "        h1 { font-size: 1.8em; margin-bottom: 0.5em; }\n"
                + "        .summary { color: #666; font-size: 14px; margin-bottom: 2em; border-bottom: 1px solid #eee; padding-bottom: 1em; }\n"
                + "        pre { background: #f5f5f5; padding: 12px; border-radius: 4px; overflow-x: auto; }\n"
                + "        code { background: #f5f5f5; padding: 2px 4px; border-radius: 2px; font-size: 0.9em; }\n"
                + "        pre code { background: none; padding: 0; }\n"
                + "        a { color: #e85d4a; text-decoration: none; }\n"
                + "        a:hover { text-decoration: underline; }\n"
                + "        blockquote { border-left: 3px solid #e85d4a; margin-left: 0; padding-left: 1em; color: #666; }\n"
                + "        img { max-width: 100%; border-radius: 4px; }\n"
                + "        table { border-collapse: collapse; width: 100%; margin: 1em 0; }\n"
                + "        th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }\n"
                + "        th { background: #f5f5f5; }\n"
                + "        .seo-hidden { display: none !important; }\n"
                + "    </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "    <!-- 层 1：静态内容（搜索引擎读这里） -->\n"
                + "    <div id=\"seo-content\">\n"
                + "        <h1>" + title + "</h1>\n"
                + "        <div class=\"summary\">" + summary + "</div>\n"
                + "        <article>" + contentHtml + "</article>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- 层 2：Vue SPA 挂载点（用户看到这里） -->\n"
                + "    <div id=\"__nuxt\"></div>\n"
                + "\n"
                + "    <!-- Vue SPA 脚本 -->\n"
                + "    <script src=\"/_nuxt/app.js\"></script>\n"
                + "</body>\n"
                + "</html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
