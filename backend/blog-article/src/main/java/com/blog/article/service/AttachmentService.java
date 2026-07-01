package com.blog.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.article.entity.Attachment;
import com.blog.article.mapper.AttachmentMapper;
import com.blog.common.BusinessException;
import com.blog.common.PageResult;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文章附件 Service（2026-07-01 DEV-001）
 *
 * 设计要点：
 *  - 5MB 业务上限 + multipart 10MB 基础设施上限（application.yml 配置）
 *  - 仅允许 .zip（场景是"批量导入/导出源码包"——见 ArticleImportService）
 *  - 一文一附件：article_id UNIQUE 约束（DB 层兜底），并发竞态用 try-insert + catch UNIQUE
 *  - 二段删除：softDelete (deleted=1, 文件保留) → hardDelete (先删文件后删 DB)
 *  - 公开下载走 AttachmentController.streamDownload，文件用 InputStreamResource 流式响应（不读 byte[]，避 OOM）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentMapper attachmentMapper;
    private final JdbcTemplate jdbc;

    /** 业务上限 5MB，multipart 10MB（基础设施）在 application.yml 配 */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    @Value("${blog.attachment.local.dir}")
    private String attachmentDir;

    // ==================== Upload ====================

    /**
     * 上传附件。一文一附件：article_id 已存在附件时返 400。
     * 并发竞态：两个 POST 同时过 attachmentMapper.selectCount → 同时 insert → 第二个撞 UNIQUE。
     * 用 try-insert + catch DataIntegrityViolationException 兜底（跨 SQLite/MySQL 一致）。
     */
    @Transactional
    public Attachment upload(Long articleId, MultipartFile file, HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            log.warn("附件上传失败：文件为空 articleId={} operator={}", articleId, AuthContext.uid(request));
            throw new BusinessException(400, "文件不能为空");
        }
        // 校验文章存在（业务层防"上传到不存在的文章"，不走循环注入——JdbcTemplate 直查）
        Integer articleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article WHERE id = ? AND deleted = 0", Integer.class, articleId);
        if (articleCount == null || articleCount == 0) {
            log.warn("附件上传失败：文章不存在 articleId={} operator={}", articleId, AuthContext.uid(request));
            throw new BusinessException(1001, "文章不存在");
        }
        if (file.getSize() > MAX_SIZE) {
            log.warn("附件上传失败：超过 5MB 上限 size={} articleId={} operator={}",
                    file.getSize(), articleId, AuthContext.uid(request));
            throw new BusinessException(400, "文件超过 5MB 上限");
        }
        // 仅允许 .zip（与 ArticleImportService 一致——附件场景是"补充包/源码导出"）
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file.zip";
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!".zip".equals(ext)) {
            log.warn("附件上传失败：类型不符 ext={} articleId={} operator={}", ext, articleId, AuthContext.uid(request));
            throw new BusinessException(400, "仅允许 zip 格式");
        }

        // 路径：attachments/yyyy/MM/yyyyMMdd-{uuid}.zip
        LocalDate today = LocalDate.now();
        String yearMonth = String.format("%d/%02d", today.getYear(), today.getMonthValue());
        String name = String.format("%s-%s%s", today.toString().replace("-", ""),
                UUID.randomUUID().toString().substring(0, 8), ext);
        File dir = new File(attachmentDir, yearMonth);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("附件上传失败：无法创建目录 dir={} operator={}", dir.getAbsolutePath(), AuthContext.uid(request));
            throw new BusinessException(500, "无法创建附件目录: " + dir.getAbsolutePath());
        }
        File target = new File(dir, name);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            log.error("附件上传失败：IO 异常 articleId={} operator={}", articleId, AuthContext.uid(request), e);
            throw new BusinessException(500, "文件保存失败");
        }

        // 2026-07-01 DEV-001 fix：用 JdbcTemplate 直查直插，绕过 MyBatis-Plus 全局 logic-delete
        //   与 article_tag 业务层去重（ArticleService.insertArticleTagIfNotExists）同模式，
        //   catch DataIntegrityViolationException 是 Spring 翻译 SQLite/MySQL UNIQUE 异常的统一种子
        LocalDateTime now = LocalDateTime.now();
        Attachment a = new Attachment();
        a.setArticleId(articleId);
        a.setFileName(original);
        a.setFilePath(yearMonth + "/" + name);
        a.setFileSize(file.getSize());
        a.setMimeType(file.getContentType() != null ? file.getContentType() : "application/zip");
        a.setDeleted(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        try {
            jdbc.update(
                    "INSERT INTO article_attachment (article_id, file_name, file_path, file_size, mime_type, deleted, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    a.getArticleId(), a.getFileName(), a.getFilePath(), a.getFileSize(),
                    a.getMimeType(), a.getDeleted(), a.getCreatedAt(), a.getUpdatedAt());
        } catch (Exception e) {
            // 2026-07-01 DEV-001 fix：SQLite JDBC 的 UNIQUE 冲突被 Spring 翻译成
            //   UncategorizedSQLException（嵌套 SQLiteException），不是 DataIntegrityViolationException。
            //   直接 catch Exception 检查嵌套消息里有没有 "UNIQUE constraint failed" 字串。
            String msg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause.getMessage() != null) msg += " | " + cause.getMessage();
                cause = cause.getCause();
            }
            if (msg.contains("UNIQUE constraint failed") || msg.contains("SQLITE_CONSTRAINT_UNIQUE")) {
                // 并发竞态：另一线程已 insert → UNIQUE 冲突 → 回滚本次的文件落盘
                if (target.exists() && !target.delete()) {
                    log.warn("附件上传竞态：删除落盘文件失败 path={}", target.getAbsolutePath());
                }
                log.warn("附件上传竞态：articleId 已有附件 articleId={} operator={}", articleId, AuthContext.uid(request));
                throw new BusinessException(400, "请先删除旧附件");
            }
            throw e;  // 非 UNIQUE 异常继续上抛
        }
        // 取回自增 ID（SQLite last_insert_rowid）
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        a.setId(newId);
        log.info("附件上传成功：id={} articleId={} fileName={} size={} operator={}",
                a.getId(), articleId, original, file.getSize(), AuthContext.uid(request));
        return a;
    }

    // ==================== Read ====================

    public Attachment getByArticleId(Long articleId) {
        QueryWrapper<Attachment> qw = new QueryWrapper<>();
        qw.eq("article_id", articleId).eq("deleted", 0);
        return attachmentMapper.selectOne(qw);
    }

    /**
     * 包含已删除的查询（公开下载用——软删时仍查找，由 resolveFile 返 410）
     * 2026-07-01 DEV-001 fix：绕过 MyBatis-Plus 全局 logic-delete 自动过滤（见 list() 注释）
     */
    public Attachment getByArticleIdIncludeDeleted(Long articleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, article_id, file_name, file_path, file_size, mime_type, deleted, created_at, updated_at "
                            + "FROM article_attachment WHERE article_id = ?",
                    (rs, rowNum) -> mapAttachmentRow(rs),
                    articleId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Attachment getById(Long id) {
        // 2026-07-01 DEV-001 fix：绕过 MyBatis-Plus 全局 logic-delete 自动过滤（见 list() 注释）
        //   selectById 默认会加 `deleted=0`，但 restore/hardDelete 需要找软删记录
        try {
            return jdbc.queryForObject(
                    "SELECT id, article_id, file_name, file_path, file_size, mime_type, deleted, created_at, updated_at "
                            + "FROM article_attachment WHERE id = ?",
                    (rs, rowNum) -> mapAttachmentRow(rs),
                    id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ==================== Soft Delete ====================

    /**
     * 软删：deleted=1，磁盘文件保留（用户在公开页可见"已删除"提示）。
     * 二段删除第一段，配合 hardDelete 完成彻底清理。
     */
    @Transactional
    public void softDelete(Long articleId, HttpServletRequest request) {
        Attachment a = getByArticleId(articleId);
        if (a == null) {
            log.warn("附件软删跳过：无附件 articleId={} operator={}", articleId, AuthContext.uid(request));
            return;
        }
        UpdateWrapper<Attachment> uw = new UpdateWrapper<>();
        uw.eq("id", a.getId()).set("deleted", 1).set("updated_at", java.time.LocalDateTime.now());
        attachmentMapper.update(null, uw);
        log.info("附件软删：id={} articleId={} fileName={} operator={}",
                a.getId(), articleId, a.getFileName(), AuthContext.uid(request));
    }

    /**
     * 按文章 ID 软删（关联删除——ArticleService.delete 调用，文件保留）。
     * 公开页显示"原附件已被作者删除"。
     */
    @Transactional
    public void softDeleteByArticleId(Long articleId) {
        UpdateWrapper<Attachment> uw = new UpdateWrapper<>();
        uw.eq("article_id", articleId).eq("deleted", 0)
                .set("deleted", 1)
                .set("updated_at", java.time.LocalDateTime.now());
        int n = attachmentMapper.update(null, uw);
        if (n > 0) {
            log.info("附件按文章软删：articleId={} count={}", articleId, n);
        }
    }

    // ==================== Hard Delete ====================

    /**
     * 硬删（后台管理 / 列表页"硬删除"按钮）：**先删文件后删 DB**。
     * 文件不存在时仍删 DB（容错——可能是上次硬删文件后 DB 失败留下的孤儿）。
     *
     * 2026-07-01 DEV-001 fix：绕过 MyBatis-Plus 全局 logic-delete 自动过滤
     *   （见 list() 注释）：selectById / deleteById 默认会加 `deleted=0`，
     *   软删的记录硬删不到。改用 JdbcTemplate。
     */
    @Transactional
    public void hardDelete(Long id, HttpServletRequest request) {
        Attachment a = getById(id);
        if (a == null) {
            throw new BusinessException(1001, "附件不存在");
        }
        Path p = Paths.get(attachmentDir).resolve(a.getFilePath());
        try {
            boolean deleted = Files.deleteIfExists(p);
            if (!deleted) {
                log.warn("硬删时文件已不存在：path={}（容错：仍删 DB）", p);
            }
        } catch (IOException e) {
            log.error("硬删失败：文件删除 IO 异常 id={} path={} operator={}",
                    id, p, AuthContext.uid(request), e);
            throw new BusinessException(500, "文件删除失败");
        }
        jdbc.update("DELETE FROM article_attachment WHERE id = ?", id);
        log.info("附件硬删：id={} articleId={} fileName={} operator={}",
                id, a.getArticleId(), a.getFileName(), AuthContext.uid(request));
    }

    // ==================== Restore ====================

    /**
     * 恢复：deleted=0。**不**自动上传文件——若文件已被硬删则下载会 404，让用户重新上传。
     *
     * 2026-07-01 二次修复：原来用 `attachmentMapper.update(null, uw)`，但 MyBatis-Plus 全局
     *   logic-delete 会给 UPDATE 自动加 `AND deleted=0` WHERE。restore 是 1→0 的转换，
     *   WHERE 永远不匹配 → API 返 200 但 DB 实际 0 行更新。改用 JdbcTemplate 直 UPDATE 绕过。
     *   同样的修复也适用于 softDelete 段（0→1 转换虽然能匹配，但语义上绕过更显式）。
     */
    @Transactional
    public void restore(Long id, HttpServletRequest request) {
        Attachment a = getById(id);
        if (a == null) throw new BusinessException(1001, "附件不存在");
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE article_attachment SET deleted = 0, updated_at = ? WHERE id = ?",
                now, id);
        if (updated == 0) {
            log.warn("附件恢复无更新：id={} (可能 record 已不存在)", id);
        }
        log.info("附件恢复：id={} articleId={} fileName={} operator={}",
                id, a.getArticleId(), a.getFileName(), AuthContext.uid(request));
    }

    // ==================== List (Admin) ====================

    /**
     * 后台列表：deleted=0/1/all 三种状态筛选。
     *
     * 2026-07-01 DEV-001 fix：绕过 MyBatis-Plus 全局 logic-delete（application.yml 里
     *   `mybatis-plus.global-config.db-config.logic-delete-field: deleted` 会让带 deleted
     *   字段的实体自动加 `AND deleted=0`，无法查询软删记录）。改用 JdbcTemplate 直查。
     *
     * 2026-07-01 OPT-002/OPT-003：加 LEFT JOIN article 取 articleTitle/articleDeleted，
     *   前端 attachments.vue 用这两个字段：
     *   - 「关联文章」列
     *   - 已删除文章对应附件的"恢复/查看文章"按钮控制（OPT-002）
     *   返回类型 PageResult<Map>（每个 record 是 {id, file_name, file_size, deleted, article_title, article_deleted, ...}）。
     */
    public PageResult<Map<String, Object>> list(int page, int size, String deletedFilter) {
        long offset = (long) (page - 1) * size;
        String deletedCondition;
        if ("all".equalsIgnoreCase(deletedFilter)) {
            deletedCondition = "";  // 不过滤
        } else if ("1".equals(deletedFilter)) {
            deletedCondition = " WHERE att.deleted = 1";
        } else {
            deletedCondition = " WHERE att.deleted = 0";
        }
        // OPT-003：附件删除时间列直接用 att.updated_at（softDelete 时已显式 set 为软删时刻）
        String listSql = "SELECT att.id, att.article_id, att.file_name, att.file_path, att.file_size, "
                + "att.mime_type, att.deleted, att.created_at, att.updated_at, "
                + "a.title AS article_title, a.deleted AS article_deleted "
                + "FROM article_attachment att LEFT JOIN article a ON a.id = att.article_id"
                + deletedCondition
                + " ORDER BY att.updated_at DESC LIMIT ? OFFSET ?";
        String countSql = "SELECT COUNT(*) FROM article_attachment att" + deletedCondition;
        Long total = jdbc.queryForObject(countSql, Long.class);
        List<Map<String, Object>> records = jdbc.queryForList(listSql, size, offset);
        // 转换 key 命名：snake_case → camelCase（前端 item.fileName 用法一致）
        List<Map<String, Object>> normalized = records.stream().map(this::normalizeRow).collect(Collectors.toList());
        return PageResult.of(normalized, total != null ? total : 0L, page, size);
    }

    /**
     * 2026-07-01 OPT-002/OPT-003：列名 snake_case → camelCase，
     * 类型转换（Number → Long/Integer）便于前端 typed 读。
     * 字段：id, articleId, fileName, fileSize, mimeType, deleted, createdAt, updatedAt,
     *      articleTitle, articleDeleted（0/1 来自 JOIN）
     */
    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", ((Number) row.get("id")).longValue());
        m.put("articleId", ((Number) row.get("article_id")).longValue());
        m.put("fileName", row.get("file_name"));
        m.put("filePath", row.get("file_path"));
        m.put("fileSize", ((Number) row.get("file_size")).longValue());
        m.put("mimeType", row.get("mime_type"));
        m.put("deleted", ((Number) row.get("deleted")).intValue());
        m.put("createdAt", row.get("created_at"));
        m.put("updatedAt", row.get("updated_at"));
        // article_title: LEFT JOIN 找不到对应文章（极少见，孤儿附件）时为 null → 前端显示「已删除文章」灰条
        m.put("articleTitle", row.get("article_title"));
        m.put("articleDeleted", row.get("article_deleted") != null
                ? ((Number) row.get("article_deleted")).intValue() : 1);  // 孤儿附件按"已删除"处理
        return m;
    }

    /**
     * 2026-07-01 DEV-001 fix：SQLite JDBC 不支持 ISO 8601 时间格式（"yyyy-MM-dd'T'HH:mm:ss.SSS"），
     * getTimestamp 会抛 ParseException。改用 getString + LocalDateTime.parse，
     * 兼容 ISO 8601 和 "yyyy-MM-dd HH:mm:ss" 两种格式（项目历史数据混存）。
     */
    private static Attachment mapAttachmentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Attachment a = new Attachment();
        a.setId(rs.getLong("id"));
        a.setArticleId(rs.getLong("article_id"));
        a.setFileName(rs.getString("file_name"));
        a.setFilePath(rs.getString("file_path"));
        a.setFileSize(rs.getLong("file_size"));
        a.setMimeType(rs.getString("mime_type"));
        a.setDeleted(rs.getInt("deleted"));
        a.setCreatedAt(parseTimestamp(rs.getString("created_at")));
        a.setUpdatedAt(parseTimestamp(rs.getString("updated_at")));
        return a;
    }

    private static LocalDateTime parseTimestamp(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s);  // ISO 8601: 2026-07-01T10:37:09.968
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s.replace("T", " ").replace(" ", " "));  // yyyy-MM-dd HH:mm:ss
            } catch (Exception e2) {
                return null;
            }
        }
    }

    // ==================== Stream Download ====================

    /**
     * 公开下载：返回磁盘绝对路径（用于 AttachmentController 流式响应）。
     * 软删返 410，硬删（文件不在磁盘）返 410。
     */
    public Path resolveFile(Attachment a) {
        if (a == null) {
            throw new BusinessException(1001, "附件不存在");
        }
        if (a.getDeleted() != null && a.getDeleted() == 1) {
            throw new BusinessException(410, "附件已删除");
        }
        Path p = Paths.get(attachmentDir).resolve(a.getFilePath()).normalize();
        // 防路径穿越：resolved 必须仍在 attachmentDir 下
        if (!p.startsWith(Paths.get(attachmentDir).normalize())) {
            log.error("附件下载路径异常：filePath={} 已逃逸 attachmentDir", a.getFilePath());
            throw new BusinessException(400, "附件路径非法");
        }
        if (!Files.exists(p)) {
            log.warn("附件下载：文件不存在 id={} path={}", a.getId(), p);
            throw new BusinessException(410, "附件文件已丢失");
        }
        return p;
    }
}