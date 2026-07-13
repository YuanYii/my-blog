package com.blog.common.seo;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.article.entity.Article;
import com.blog.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @Value("${seo.frontend-path:/opt/myblog/frontend}")
    private String frontendPath;

    private List<String> cssLinks = new ArrayList<>();
    private String jsLink = "";
    private String buildId = "";

    @PostConstruct
    public void init() {
        scanFrontendAssets();
    }

    private void scanFrontendAssets() {
        Path nuxtDir = Paths.get(frontendPath, "_nuxt");
        if (!Files.isDirectory(nuxtDir)) {
            log.warn("[SEO] Frontend _nuxt directory not found: {}", nuxtDir);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(nuxtDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".css")) {
                    // 跳过 admin 专用 CSS（以 Admin/backup/edit/restore/attachments 开头）
                    if (name.startsWith("Admin") || name.startsWith("backup")
                            || name.startsWith("edit") || name.startsWith("restore")
                            || name.startsWith("attachments")) {
                        continue;
                    }
                    cssLinks.add("/_nuxt/" + name);
                }
            }
            log.info("[SEO] Scanned {} CSS from {}", cssLinks.size(), nuxtDir);
        } catch (IOException e) {
            log.error("[SEO] Failed to scan frontend assets: {}", e.getMessage());
        }

        // 从静态 HTML 中提取正确的入口 JS（DirectoryStream 顺序不确定，不能随机选）
        jsLink = extractEntryJs();
        log.info("[SEO] Entry JS: {}", jsLink);
    }

    /**
     * 从 index.html / 200.html 中提取 Nuxt 入口 JS 和 buildId。
     * 匹配 <script type="module" src="/_nuxt/xxx.js"> 和 buildId:"xxx"
     */
    private String extractEntryJs() {
        String[] tryFiles = {"index.html", "200.html"};
        for (String fileName : tryFiles) {
            Path htmlPath = Paths.get(frontendPath, fileName);
            if (!Files.exists(htmlPath)) continue;
            try {
                String html = new String(Files.readAllBytes(htmlPath));
                // 提取入口 JS
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("src=\"/_nuxt/([^\"]+\\.js)\"")
                        .matcher(html);
                if (m.find()) {
                    jsLink = "/_nuxt/" + m.group(1);
                }
                // 提取 buildId
                java.util.regex.Matcher bm = java.util.regex.Pattern
                        .compile("buildId:\"([^\"]+)\"")
                        .matcher(html);
                if (bm.find()) {
                    buildId = bm.group(1);
                }
                if (!jsLink.isEmpty()) break;
            } catch (IOException e) {
                log.warn("[SEO] Failed to read {}: {}", fileName, e.getMessage());
            }
        }
        log.info("[SEO] Entry JS: {}, buildId: {}", jsLink, buildId);
        return jsLink;
    }

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

        // 动态注入 Nuxt 前端构建产物的 CSS/JS
        String cssTags = cssLinks.stream()
                .map(link -> "    <link rel=\"stylesheet\" href=\"" + link + "\">")
                .collect(Collectors.joining("\n"));
        String jsTag = jsLink.isEmpty()
                ? ""
                : "    <script type=\"module\" src=\"" + jsLink + "\"></script>";

        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n"
                + "<head>\n"
                + "    <meta charset=\"UTF-8\">\n"
                + "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "    <title>" + title + " | blog.coreyai.cn</title>\n"
                + "    <meta name=\"description\" content=\"" + summary + "\">\n"
                + "    <meta property=\"og:title\" content=\"" + title + "\">\n"
                + "    <meta property=\"og:description\" content=\"" + summary + "\">\n"
                + "    <meta property=\"og:type\" content=\"article\">\n"
                + "    <meta property=\"og:url\" content=\"" + url + "\">\n"
                + "    " + ogImage + "\n"
                + "    <link rel=\"canonical\" href=\"" + url + "\">\n"
                + (cssTags.isEmpty() ? "" : cssTags + "\n")
                + "    <style>\n"
                + "        /* SEO 内容样式（仅作用于 #seo-content，Vue 加载后隐藏不影响 SPA） */\n"
                + "        #seo-content { max-width: 720px; margin: 0 auto; padding: 20px; font-family: system-ui, -apple-system, sans-serif; line-height: 1.8; color: #333; }\n"
                + "        #seo-content h1 { font-size: 1.8em; margin-bottom: 0.5em; }\n"
                + "        #seo-content .summary { color: #666; font-size: 14px; margin-bottom: 2em; border-bottom: 1px solid #eee; padding-bottom: 1em; }\n"
                + "        #seo-content pre { background: #f5f5f5; padding: 12px; border-radius: 4px; overflow-x: auto; }\n"
                + "        #seo-content code { background: #f5f5f5; padding: 2px 4px; border-radius: 2px; font-size: 0.9em; }\n"
                + "        #seo-content pre code { background: none; padding: 0; }\n"
                + "        #seo-content a { color: #e85d4a; text-decoration: none; }\n"
                + "        #seo-content a:hover { text-decoration: underline; }\n"
                + "        #seo-content blockquote { border-left: 3px solid #e85d4a; margin-left: 0; padding-left: 1em; color: #666; }\n"
                + "        #seo-content img { max-width: 100%; border-radius: 4px; }\n"
                + "        #seo-content table { border-collapse: collapse; width: 100%; margin: 1em 0; }\n"
                + "        #seo-content th, #seo-content td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }\n"
                + "        #seo-content th { background: #f5f5f5; }\n"
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
                + "    <div id=\"teleports\"></div>\n"
                + "\n"
                + "    <!-- Nuxt 初始化数据 -->\n"
                + "    <script type=\"application/json\" data-nuxt-data=\"nuxt-app\" data-ssr=\"false\" id=\"__NUXT_DATA__\">[{\"prerenderedAt\":1,\"serverRendered\":2}," + System.currentTimeMillis() + ",false]</script>\n"
                + "    <script>window.__NUXT__={};window.__NUXT__.config={public:{apiBase:\"/api/v1\"},app:{baseURL:\"/\",buildId:\"" + buildId + "\",buildAssetsDir:\"/_nuxt/\",cdnURL:\"\"}}</script>\n"
                + "\n"
                + "    <!-- Vue SPA 脚本 -->\n"
                + "    " + jsTag + "\n"
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
