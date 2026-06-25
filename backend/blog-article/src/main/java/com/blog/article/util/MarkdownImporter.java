package com.blog.article.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 导入工具（2026-06-24 DEV-002）
 *
 * 职责：
 *  - 拆分 YAML front matter + Markdown body
 *  - 解析 front matter 的浅层 key-value（不依赖 snakeyaml 额外依赖,字段固定）
 *  - 提取 Markdown body 中所有 ![alt](path) 形式的图片相对路径
 *  - 将相对路径替换为上传后的 URL
 *  - 从文件名生成文章标题（去 .md / YYYY-MM-DD- 前缀 / - 换空格）
 *
 * 支持的 front matter 字段（均可选）：
 *  date / view_count / categories / tags / status / summary / cover
 */
public final class MarkdownImporter {

    private static final Pattern FRONT_MATTER =
        Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*(?:\\R|$)", Pattern.DOTALL);

    /** 匹配 ![alt](path) — 支持 alt 含中英文/空格 */
    private static final Pattern IMG_PATTERN =
        Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    /** 文件名开头 YYYY-MM-DD- 日期前缀 */
    private static final Pattern DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}-");

    private MarkdownImporter() {}

    /**
     * 拆分原始内容为 {frontMatter, body}
     * frontMatter 为 null 表示无 front matter
     */
    public static ParsedMarkdown parse(String raw) {
        ParsedMarkdown p = new ParsedMarkdown();
        if (raw == null) {
            p.body = "";
            return p;
        }
        Matcher m = FRONT_MATTER.matcher(raw);
        if (m.find()) {
            p.frontMatter = parseFrontMatter(m.group(1));
            p.body = raw.substring(m.end());
        } else {
            p.body = raw;
        }
        return p;
    }

    /**
     * 解析 front matter 浅层 key-value
     * 支持：
     *   key: value          → String
     *   key: [a, b, c]      → List<String>（数组形式）
     *   key:                → List<String>（缩进 - 形式）
     *     - a
     *     - b
     *   `# 注释` 行直接跳过
     */
    static Map<String, Object> parseFrontMatter(String yaml) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (yaml == null || yaml.isEmpty()) return out;
        String[] lines = yaml.split("\\R");

        String currentListKey = null;
        List<String> currentList = null;

        for (String rawLine : lines) {
            String line = rawLine;
            // 跳过空行与注释
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (currentListKey != null) {
                    out.put(currentListKey, currentList);
                    currentListKey = null;
                    currentList = null;
                }
                continue;
            }

            // 缩进 `- item` 列表项
            if (currentListKey != null && (line.startsWith(" ") || line.startsWith("\t"))) {
                String item = trimmed;
                if (item.startsWith("- ")) item = item.substring(2).trim();
                else if (item.startsWith("-")) item = item.substring(1).trim();
                if (!item.isEmpty()) currentList.add(stripQuotes(item));
                continue;
            }

            // 收尾上一组列表
            if (currentListKey != null) {
                out.put(currentListKey, currentList);
                currentListKey = null;
                currentList = null;
            }

            // `key: value` 形式
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                // 开启缩进列表
                currentListKey = key;
                currentList = new ArrayList<>();
            } else if (value.startsWith("[") && value.endsWith("]")) {
                // 数组形式：[a, b, c]
                String inner = value.substring(1, value.length() - 1).trim();
                List<String> arr = new ArrayList<>();
                if (!inner.isEmpty()) {
                    for (String it : inner.split(",")) {
                        String t = stripQuotes(it.trim());
                        if (!t.isEmpty()) arr.add(t);
                    }
                }
                out.put(key, arr);
            } else {
                out.put(key, stripQuotes(value));
            }
        }
        // 收尾
        if (currentListKey != null) {
            out.put(currentListKey, currentList);
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 提取 markdown body 中所有图片相对路径（去重保持出现顺序）
     * 跳过绝对 URL（http:// https:// 开头）和锚点（# 开头）
     */
    public static List<String> extractImageRefs(String body) {
        List<String> refs = new ArrayList<>();
        if (body == null || body.isEmpty()) return refs;
        Matcher m = IMG_PATTERN.matcher(body);
        while (m.find()) {
            String path = m.group(2).trim();
            if (path.isEmpty()) continue;
            if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//")) {
                continue;
            }
            if (path.startsWith("#") || path.startsWith("data:")) continue;
            if (!refs.contains(path)) refs.add(path);
        }
        return refs;
    }

    /**
     * 用 pathMap 中的 originPath → uploadedUrl 映射替换 body 中所有图片引用
     * 不在 map 中的相对路径保持原样
     */
    public static String rewriteImagePaths(String body, Map<String, String> pathMap) {
        if (body == null || body.isEmpty() || pathMap == null || pathMap.isEmpty()) return body;
        Matcher m = IMG_PATTERN.matcher(body);
        StringBuffer sb = new StringBuffer(body.length() + 128);
        while (m.find()) {
            String alt = m.group(1);
            String path = m.group(2).trim();
            String url = pathMap.get(path);
            String replacement = "![" + alt + "](" + (url != null ? url : path) + ")";
            // appendReplacement 需要 escape $ \
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从 .md 文件名生成文章标题
     *  - 去 .md 扩展
     *  - 去 YYYY-MM-DD- 日期前缀
     *  - - 替换为空格
     *  - 多余空白合并
     */
    public static String filenameToTitle(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase().endsWith(".md")) name = name.substring(0, name.length() - 3);
        name = DATE_PREFIX.matcher(name).replaceFirst("");
        name = name.replace('-', ' ').trim();
        // 合并多余空白
        name = name.replaceAll("\\s+", " ");
        return name;
    }

    /**
     * 从 .md 路径生成 slug（用于 article.slug 字段）
     *  - 文件名去 .md 扩展
     *  - 去 YYYY-MM-DD- 前缀
     *  - 非法字符（除字母数字中文-）替换为 -
     *  - 末尾去 -
     */
    public static String filenameToSlug(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase().endsWith(".md")) name = name.substring(0, name.length() - 3);
        name = DATE_PREFIX.matcher(name).replaceFirst("");
        name = name.toLowerCase()
            .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\-]+", "-")
            .replaceAll("^-+|-+$", "");
        return name;
    }

    /**
     * 解析 status 字符串/数字 → 0/1/2
     * 默认 0（草稿）
     */
    public static Integer parseStatus(Object raw) {
        if (raw == null) return 0;
        String s = raw.toString().trim().toLowerCase();
        if (s.isEmpty()) return 0;
        switch (s) {
            case "0":
            case "draft":
                return 0;
            case "1":
            case "published":
                return 1;
            case "2":
            case "archived":
                return 2;
            default:
                return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> toStringList(Object raw) {
        if (raw == null) return new ArrayList<>();
        if (raw instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object x : (List<Object>) raw) {
                if (x != null) {
                    String t = x.toString().trim();
                    if (!t.isEmpty()) out.add(t);
                }
            }
            return out;
        }
        // 单值 → 单元素列表
        String s = raw.toString().trim();
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s));
    }

    public static class ParsedMarkdown {
        public Map<String, Object> frontMatter;  // 可能为 null
        public String body = "";
    }
}
