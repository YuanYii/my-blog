package com.blog.common.seo;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import org.springframework.stereotype.Component;

/**
 * Markdown → HTML 渲染器（SEO 页面用）。
 * 与前端 marked 库行为基本一致，供 SeoController 生成搜索引擎可索引的 HTML。
 */
@Component
public class MarkdownRenderer {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Document document = parser.parse(markdown);
        return renderer.render(document);
    }
}
