package com.blog.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.blog.article.entity.Article;
import com.blog.article.entity.Category;
import com.blog.article.entity.ImportRecord;
import com.blog.article.entity.Tag;
import com.blog.article.mapper.ArticleMapper;
import com.blog.article.mapper.CategoryMapper;
import com.blog.article.mapper.ImportRecordMapper;
import com.blog.article.mapper.TagMapper;
import com.blog.article.util.MarkdownImporter;
import com.blog.common.BusinessException;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文章导入服务（2026-06-24 DEV-002）
 *
 * 主流程：
 *  1. enqueueImport(MultipartFile)：建 PENDING 记录,异步执行
 *  2. runImportAsync(recordId, zipBytes)：解 ZIP → 校验 → 解析每篇 md → 创建 article
 *  3. 进度回写 import_record（success_count / fail_count）
 *
 * ZIP 安全：
 *  - 5MB ZIP 上限（前端 + 后端双校验）
 *  - 解压后总大小 5MB 上限（防压缩炸弹）
 *  - Zip Slip 防护：拒绝 `..` / 绝对路径条目
 *  - 文件类型白名单：仅 .md + .png
 *
 * 图片处理：
 *  - 保存到 `{uploadDir}/yyyy/MM/yyyyMMdd-{uuid}.png`（与 UploadController 路径一致）
 *  - 不去重（同图多次引用各自上传一份）
 *  - 替换 Markdown 中相对路径为上传后 URL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleImportService {

    private final ImportRecordMapper importRecordMapper;
    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final JdbcTemplate jdbc;

    @Autowired
    @Lazy
    private ArticleImportService self;

    @Value("${blog.upload.local.dir:/opt/myblog/uploads}")
    private String uploadDir;

    /** ZIP 大小上限（5MB） */
    public static final long MAX_ZIP_SIZE = 5L * 1024 * 1024;

    /** 解压后总大小上限（5MB,防压缩炸弹） */
    public static final long MAX_EXTRACTED_SIZE = 5L * 1024 * 1024;

    /** 触发导入：上传校验 → 落 ZIP 字节 → 建 PENDING → 异步跑 */
    public Long enqueueImport(MultipartFile file, HttpServletRequest request) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "import.zip";
        if (!fileName.toLowerCase().endsWith(".zip")) {
            throw new BusinessException(400, "仅支持 .zip 文件");
        }
        if (file.getSize() > MAX_ZIP_SIZE) {
            throw new BusinessException(400, "文件大小不能超过 5MB");
        }

        byte[] zipBytes = file.getBytes();

        ImportRecord record = new ImportRecord();
        record.setFileName(fileName);
        record.setStatus("PENDING");
        record.setTotalCount(0);
        record.setSuccessCount(0);
        record.setFailCount(0);
        record.setStartedAt(LocalDateTime.now());

        Object uid = AuthContext.uid(request);
        if (uid != null) {
            try { record.setOperatorId(Long.parseLong(uid.toString())); } catch (NumberFormatException ignored) { }
        }
        String username = AuthContext.username(request);
        record.setOperatorName(username != null && !username.isEmpty() ? username
            : (uid != null ? "uid:" + uid : null));
        importRecordMapper.insert(record);

        log.info("文章导入任务创建: id={} fileName={} size={} operator={}",
            record.getId(), fileName, file.getSize(), record.getOperatorName());

        // 异步执行（self 走代理 @Async 才生效）
        self.runImportAsync(record.getId(), zipBytes);
        return record.getId();
    }

    /** 异步执行导入。zipBytes 在调用方读完就传过来,避免持有 MultipartFile */
    @Async("articleImportExecutor")
    public void runImportAsync(Long recordId, byte[] zipBytes) {
        ImportRecord record = importRecordMapper.selectById(recordId);
        if (record == null) {
            log.error("导入记录 {} 不存在", recordId);
            return;
        }
        record.setStatus("RUNNING");
        importRecordMapper.updateById(record);

        try {
            ImportContext ctx = parseZip(zipBytes);
            record.setTotalCount(ctx.markdownEntries.size());
            importRecordMapper.updateById(record);

            List<String> errors = new ArrayList<>();
            int succ = 0;
            int fail = 0;

            for (Map.Entry<String, byte[]> entry : ctx.markdownEntries.entrySet()) {
                try {
                    importOneArticle(entry.getKey(), entry.getValue(), ctx);
                    succ++;
                } catch (Exception e) {
                    fail++;
                    String msg = "[" + entry.getKey() + "] " + e.getMessage();
                    errors.add(msg);
                    log.warn("[ArticleImport] 单篇导入失败: {}", msg);
                }
                record.setSuccessCount(succ);
                record.setFailCount(fail);
                importRecordMapper.updateById(record);
            }

            record.setStatus("SUCCESS");
            record.setFinishedAt(LocalDateTime.now());
            if (!errors.isEmpty()) {
                record.setErrorMessage(truncate(String.join("\n", errors), 500));
            }
            importRecordMapper.updateById(record);
            log.info("[ArticleImport] 导入完成: id={} total={} success={} fail={}",
                recordId, ctx.markdownEntries.size(), succ, fail);
        } catch (BusinessException be) {
            record.setStatus("FAILED");
            record.setFinishedAt(LocalDateTime.now());
            record.setErrorMessage(truncate(be.getMessage(), 500));
            importRecordMapper.updateById(record);
            log.warn("[ArticleImport] 导入异常中止: id={} msg={}", recordId, be.getMessage());
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setFinishedAt(LocalDateTime.now());
            record.setErrorMessage(truncate("内部错误: " + e.getClass().getSimpleName()
                + ": " + (e.getMessage() == null ? "" : e.getMessage()), 500));
            importRecordMapper.updateById(record);
            log.error("[ArticleImport] 导入异常: id={}", recordId, e);
        }
    }

    /**
     * 解 ZIP：校验 + 入内存
     * 返回 markdown 文件列表 + 图片字节映射（按 ZIP 内相对路径索引）
     */
    private ImportContext parseZip(byte[] zipBytes) throws IOException {
        ImportContext ctx = new ImportContext();
        long totalExtracted = 0L;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (rawName == null || rawName.isEmpty()) continue;
                // Zip Slip 防护
                if (rawName.contains("..") || rawName.startsWith("/") || rawName.startsWith("\\")) {
                    throw new BusinessException(400, "ZIP 包路径不合法（含 .. 或绝对路径）: " + rawName);
                }
                if (entry.isDirectory()) continue;

                String lower = rawName.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".md") && !lower.endsWith(".png")) {
                    throw new BusinessException(400, "仅支持 .md 和 .png 文件，发现非法类型: " + rawName);
                }

                byte[] data = readZipEntry(zis);
                totalExtracted += data.length;
                if (totalExtracted > MAX_EXTRACTED_SIZE) {
                    throw new BusinessException(400, "解压后文件总大小不能超过 5MB");
                }

                if (lower.endsWith(".md")) {
                    ctx.markdownEntries.put(rawName, data);
                } else {
                    ctx.imageEntries.put(rawName, data);
                }
            }
        }

        if (ctx.markdownEntries.isEmpty()) {
            throw new BusinessException(400, "ZIP 包中未找到任何 .md 文件");
        }
        return ctx;
    }

    private byte[] readZipEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = zis.read(buf)) > 0) {
            out.write(buf, 0, len);
            if (out.size() > MAX_EXTRACTED_SIZE) {
                throw new BusinessException(400, "ZIP 单文件解压超过 5MB 上限");
            }
        }
        return out.toByteArray();
    }

    /**
     * 导入单篇文章
     */
    private void importOneArticle(String mdPath, byte[] mdBytes, ImportContext ctx) throws IOException {
        String raw = new String(mdBytes, StandardCharsets.UTF_8);
        MarkdownImporter.ParsedMarkdown parsed = MarkdownImporter.parse(raw);

        Map<String, Object> fm = parsed.frontMatter == null ? new HashMap<>() : parsed.frontMatter;
        String body = parsed.body == null ? "" : parsed.body;

        // 标题以文件名为主（忽略 front matter title）
        String title = MarkdownImporter.filenameToTitle(mdPath);
        if (title.isEmpty()) title = "untitled-" + System.currentTimeMillis();
        if (title.length() > 200) title = title.substring(0, 200);

        // slug
        String baseSlug = MarkdownImporter.filenameToSlug(mdPath);
        if (baseSlug.isEmpty()) baseSlug = "import-" + System.currentTimeMillis();
        String slug = ensureUniqueSlug(baseSlug);
        if (slug.length() > 200) slug = slug.substring(0, 200);

        // 处理图片：提取相对路径 → 解析 ZIP 内绝对路径 → 上传 → 替换
        Map<String, String> pathMap = new HashMap<>();
        List<String> refs = MarkdownImporter.extractImageRefs(body);
        for (String ref : refs) {
            String resolved = resolveZipPath(mdPath, ref);
            byte[] imgBytes = findImageBytes(ctx, resolved);
            if (imgBytes == null) {
                log.warn("[ArticleImport] 图片引用未在 ZIP 内找到: md={} ref={} (跳过)", mdPath, ref);
                continue;
            }
            String url = saveImage(imgBytes);
            pathMap.put(ref, url);
        }
        String rewrittenBody = MarkdownImporter.rewriteImagePaths(body, pathMap);

        // 构造 article
        Article article = new Article();
        article.setTitle(title);
        article.setSlug(slug);
        article.setContentMd(rewrittenBody);
        article.setViewCount(0);
        article.setDeleted(0);

        // front matter 解析
        Integer status = MarkdownImporter.parseStatus(fm.get("status"));
        article.setStatus(status);

        Object dateObj = fm.get("date");
        if (dateObj != null) {
            LocalDateTime publishedAt = parseDateTime(dateObj.toString());
            if (publishedAt != null) article.setPublishedAt(publishedAt);
        }
        if (Integer.valueOf(1).equals(status) && article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }

        Object vc = fm.get("view_count");
        if (vc != null) {
            try {
                article.setViewCount(Integer.parseInt(vc.toString().trim()));
            } catch (NumberFormatException ignored) { }
        }

        Object summary = fm.get("summary");
        if (summary != null && !summary.toString().trim().isEmpty()) {
            article.setSummary(summary.toString().trim());
        }

        Object cover = fm.get("cover");
        if (cover != null && !cover.toString().trim().isEmpty()) {
            String coverRaw = cover.toString().trim();
            if (coverRaw.startsWith("http://") || coverRaw.startsWith("https://")) {
                article.setCoverUrl(coverRaw);
            } else {
                String resolved = resolveZipPath(mdPath, coverRaw);
                byte[] imgBytes = findImageBytes(ctx, resolved);
                if (imgBytes != null) {
                    article.setCoverUrl(saveImage(imgBytes));
                }
            }
        }

        // categories (单值) → categoryId
        List<String> cats = MarkdownImporter.toStringList(fm.get("categories"));
        if (cats.isEmpty()) {
            // 兜底：用默认分类「未分类」
            cats = new ArrayList<>();
            cats.add("未分类");
        }
        String catName = cats.get(0);
        Category category = findOrCreateCategory(catName);
        article.setCategoryId(category.getId());

        // tags
        List<String> tagNames = MarkdownImporter.toStringList(fm.get("tags"));
        List<Long> tagIds = new ArrayList<>();
        for (String tn : tagNames) {
            Tag tag = findOrCreateTag(tn);
            if (tag != null) tagIds.add(tag.getId());
        }

        articleMapper.insert(article);
        for (Long tid : tagIds) {
            try {
                jdbc.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)",
                    article.getId(), tid);
            } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                // 同标签重复 INSERT 幂等吞掉
            }
        }
    }

    /** 大小写不敏感 + trim 后查找或新建分类 */
    private Category findOrCreateCategory(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) name = "未分类";
        List<Category> list = categoryMapper.selectList(
            new QueryWrapper<Category>().apply("LOWER(name) = {0}", name.toLowerCase()));
        if (!list.isEmpty()) return list.get(0);
        // 创建
        Category c = new Category();
        c.setName(name);
        c.setSlug(slugifyCN(name));
        c.setVisible(1);
        c.setSort(0);
        // slug 冲突兜底
        if (categoryMapper.selectOne(new QueryWrapper<Category>().eq("slug", c.getSlug())) != null) {
            c.setSlug(c.getSlug() + "-" + System.currentTimeMillis());
        }
        categoryMapper.insert(c);
        return c;
    }

    /** 大小写不敏感 + trim 后查找或新建标签 */
    private Tag findOrCreateTag(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return null;
        List<Tag> list = tagMapper.selectList(
            new QueryWrapper<Tag>().apply("LOWER(name) = {0}", name.toLowerCase()));
        if (!list.isEmpty()) return list.get(0);
        Tag t = new Tag();
        t.setName(name);
        t.setSlug(slugifyCN(name));
        if (tagMapper.selectOne(new QueryWrapper<Tag>().eq("slug", t.getSlug())) != null) {
            t.setSlug(t.getSlug() + "-" + System.currentTimeMillis());
        }
        tagMapper.insert(t);
        return t;
    }

    private String slugifyCN(String name) {
        String s = name.toLowerCase()
            .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
            .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "tag-" + System.currentTimeMillis() : s;
    }

    /**
     * 保证 article.slug 唯一（同名加 -{n} 后缀）
     */
    private String ensureUniqueSlug(String base) {
        String slug = base;
        int n = 2;
        while (articleMapper.selectOne(new QueryWrapper<Article>().eq("slug", slug)) != null) {
            slug = base + "-" + n;
            n++;
            if (n > 100) {
                slug = base + "-" + System.currentTimeMillis();
                break;
            }
        }
        return slug;
    }

    /**
     * 按 md 文件所在目录解析图片相对路径
     * 例：md = "posts/a.md", ref = "images/x.png" → "posts/images/x.png"
     */
    private String resolveZipPath(String mdPath, String ref) {
        if (ref == null) return "";
        if (ref.startsWith("/")) return ref.substring(1);
        int slash = mdPath.lastIndexOf('/');
        String dir = slash >= 0 ? mdPath.substring(0, slash + 1) : "";
        return normalizeRelative(dir + ref);
    }

    /** 简化路径：解析 ./ 和 ../，但拒绝跳出根目录 */
    static String normalizeRelative(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                // 跳出根目录的 .. 静默忽略（防 Zip Slip）
            } else {
                stack.add(p);
            }
        }
        return String.join("/", stack);
    }

    /** 在 ctx.imageEntries 中找匹配图片字节（精确匹配 + 兜底按文件名匹配） */
    private byte[] findImageBytes(ImportContext ctx, String resolvedPath) {
        if (ctx.imageEntries.containsKey(resolvedPath)) return ctx.imageEntries.get(resolvedPath);
        // 兜底：按文件名匹配（用户可能写错相对路径）
        String basename = resolvedPath;
        int slash = basename.lastIndexOf('/');
        if (slash >= 0) basename = basename.substring(slash + 1);
        for (Map.Entry<String, byte[]> e : ctx.imageEntries.entrySet()) {
            String k = e.getKey();
            int s2 = k.lastIndexOf('/');
            String kb = s2 >= 0 ? k.substring(s2 + 1) : k;
            if (kb.equalsIgnoreCase(basename)) return e.getValue();
        }
        return null;
    }

    /**
     * 保存图片到 uploadDir/yyyy/MM/yyyyMMdd-{uuid}.png 并返回相对 URL（/uploads/yyyy/MM/...）
     * 路径格式与 UploadController.upload 对齐
     */
    private String saveImage(byte[] bytes) throws IOException {
        LocalDate today = LocalDate.now();
        String yearMonth = String.format("%d/%02d", today.getYear(), today.getMonthValue());
        String name = String.format("%s-%s.png", today.toString().replace("-", ""),
            UUID.randomUUID().toString().substring(0, 8));
        File dir = new File(uploadDir, yearMonth);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建上传目录: " + dir.getAbsolutePath());
        }
        File target = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(bytes);
        }
        return "/uploads/" + yearMonth + "/" + name;
    }

    /** 解析 YYYY-MM-DD 或 YYYY-MM-DD HH:mm:ss → LocalDateTime */
    private LocalDateTime parseDateTime(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) { }
        try {
            return LocalDate.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(t);
        } catch (Exception ignored) { }
        return null;
    }

    // ============ 查询接口 ============

    public ImportRecord getRecord(Long id) {
        return importRecordMapper.selectById(id);
    }

    public List<ImportRecord> listRecent(int limit) {
        return importRecordMapper.selectList(new QueryWrapper<ImportRecord>()
            .orderByDesc("started_at")
            .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

    /** 读取 import-template.zip 模板字节流 */
    public byte[] readTemplate() throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("templates/import-template.zip")) {
            if (is == null) throw new IOException("模板文件不存在: templates/import-template.zip");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) out.write(buf, 0, len);
            return out.toByteArray();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    static class ImportContext {
        /** 保持 ZIP 内顺序的 markdown 文件列表 (path → bytes) */
        final Map<String, byte[]> markdownEntries = new LinkedHashMap<>();
        /** 图片 (path → bytes) */
        final Map<String, byte[]> imageEntries = new LinkedHashMap<>();
    }
}
