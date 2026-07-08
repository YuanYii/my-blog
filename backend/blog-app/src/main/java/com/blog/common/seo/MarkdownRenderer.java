package com.blog.common.seo;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.toc.TocExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Markdown → HTML 渲染器（SEO 页面用）。
 * 启用 GFM 扩展：表格、删除线、TOC。
 * ==highlight== 由正则预处理（flexmark 无原生支持）。
 * 任务列表 [x] 由正则预处理（flexmark 0.42 无 TaskListExtension）。
 */
@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        this.parser = Parser.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(),
                        StrikethroughExtension.create(),
                        TocExtension.create()
                ))
                .build();
        this.renderer = HtmlRenderer.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(),
                        StrikethroughExtension.create(),
                        TocExtension.create()
                ))
                .build();
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String processed = markdown;
        // ==highlight== 预处理（flexmark 无原生支持，与前端 marked 行为对齐）
        processed = processed.replaceAll("==([^=\\n]+)==", "<mark>$1</mark>");
        // - [x] / - [ ] 任务列表预处理（flexmark 0.42 无 TaskListExtension）
        processed = processed.replaceAll("(?m)^([-*] )\\[x\\]\\s+", "$1<input type=\"checkbox\" checked disabled> ");
        processed = processed.replaceAll("(?m)^([-*] )\\[ \\]\\s+", "$1<input type=\"checkbox\" disabled> ");
        Document document = parser.parse(processed);
        return renderer.render(document);
    }
}
