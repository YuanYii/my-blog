package com.blog.common.util;

/**
 * Markdown 字数统计工具
 *
 * 与仪表盘与前台公开统计共享：
 * 1) 过滤 Markdown 标记字符（代码块内容保留，标点链接等语法保留可读文本）
 * 2) 统计有效字符数（CJK 字符按 1 字，ASCII 按 1 字符，忽略空白换行）
 */
public final class MarkdownWordCountUtil {

    private MarkdownWordCountUtil() {}

    /**
     * Markdown 标记去除（轻量版——按"读者看到的"字符为准）
     */
    public static String stripMarkdown(String md) {
        if (md == null || md.isEmpty()) return "";
        String s = md;
        // 1) 代码块：保留内部内容
        s = s.replaceAll("```[\\s\\S]*?```", " ");
        // 2) 行内代码：保留内部内容
        s = s.replaceAll("`([^`]+)`", "$1");
        // 3) 图片：保留 alt
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 4) 链接：保留 text
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        // 5) 标题前缀（行首的 #）
        s = s.replaceAll("(?m)^#+\\s*", "");
        // 6) 引用前缀
        s = s.replaceAll("(?m)^>\\s*", "");
        // 7) 列表标记（无序 - * +，有序 1. 2.）
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        s = s.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        // 8) 粗体 / 斜体 / 删除线
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        s = s.replaceAll("\\*([^*]+)\\*", "$1");
        s = s.replaceAll("~~([^~]+)~~", "$1");
        return s;
    }

    /**
     * 字符数统计
     * - CJK 字符每个 1 字
     * - ASCII 字符每个 1 字符
     * - 空白/换行不算
     */
    public static int countChars(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            // 跳过空白字符（含空格 \n \r \t 全角空格）
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                n++;
            }
            i += Character.charCount(cp);
        }
        return n;
    }

    /**
     * 计算单篇 Markdown 的实际有效字符数
     */
    public static long countMarkdown(String md) {
        if (md == null || md.isEmpty()) return 0;
        return countChars(stripMarkdown(md));
    }
}
