package com.blog.comment.service;

import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.common.BusinessException;
import com.blog.common.TrustedProxyUtil;
import com.blog.common.web.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评论业务逻辑（B1a：从 CommentController 物理搬迁，控制流不变，保留 Result.error 风格）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final JdbcTemplate jdbc;

    private static final int COMMENT_LIMIT = 10;
    private static final int COMMENT_WINDOW_SECONDS = 60;
    private static final ConcurrentHashMap<String, long[]> COMMENT_LIMIT_MAP = new ConcurrentHashMap<>();

    public Result<PageResult<Map<String, Object>>> list(Long articleId, long page, long size) {
        if (articleId == null) throw new BusinessException(400, "articleId 不能为空");
        if (page < 1) throw new BusinessException(400, "page 必须 >= 1");
        if (size < 1 || size > 100) throw new BusinessException(400, "size 必须在 1-100 之间");
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE article_id = ? AND status = 1", Long.class, articleId);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT c.id, c.article_id AS articleId, c.parent_id AS parentId, " +
                        "c.nickname, c.website, c.content, c.status, " +
                        "c.created_at AS createdAt, " +
                        "a.title AS articleTitle, a.slug AS articleSlug " +
                        "FROM comment c LEFT JOIN article a ON c.article_id = a.id " +
                        "WHERE c.article_id = ? AND c.status = 1 " +
                        "ORDER BY c.created_at DESC LIMIT ? OFFSET ?",
                articleId, size, (page - 1) * size);
        return Result.success(PageResult.of(records, total, page, size));
    }

    public Result<Map<String, Object>> create(Map<String, Object> body, HttpServletRequest request) {
        String ip = TrustedProxyUtil.resolveClientIp(request);
        if (isCommentLimited(ip)) {
            throw new BusinessException(429, "评论过于频繁，请 1 分钟后再试");
        }
        Object articleIdObj = body.get("articleId");
        if (articleIdObj == null) throw new BusinessException(400, "articleId 不能为空");
        Long articleId = ((Number) articleIdObj).longValue();
        String nickname = (String) body.get("nickname");
        String content = (String) body.get("content");
        if (nickname == null || nickname.trim().isEmpty()) throw new BusinessException(400, "昵称不能为空");
        if (nickname.length() > 50) throw new BusinessException(400, "昵称长度不能超过 50 字符");
        if (content == null || content.trim().isEmpty()) throw new BusinessException(400, "评论内容不能为空");
        if (content.length() > 2000) throw new BusinessException(400, "评论内容长度不能超过 2000 字符");
        Long parentId = body.get("parentId") != null ? ((Number) body.get("parentId")).longValue() : 0L;
        String email = (String) body.get("email");
        String website = (String) body.get("website");
        if (email != null && email.length() > 100) throw new BusinessException(400, "邮箱长度不能超过 100 字符");
        if (website != null && website.length() > 200) throw new BusinessException(400, "网站 URL 长度不能超过 200 字符");

        List<Map<String, Object>> articleRows = jdbc.queryForList(
                "SELECT slug FROM article WHERE id = ? AND status = 1 AND deleted = 0", articleId);
        if (articleRows.isEmpty()) throw new BusinessException(1001, "文章不存在或未发布");
        String articleSlug = (String) articleRows.get(0).get("slug");

        final Long fArticleId = articleId;
        final Long fParentId = parentId;
        final String fNickname = nickname;
        final String fEmail = email;
        final String fWebsite = website;
        final String fContent = content;
        final String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO comment (article_id, parent_id, nickname, email, website, content, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, fArticleId);
            ps.setLong(2, fParentId);
            ps.setString(3, fNickname);
            ps.setString(4, fEmail);
            ps.setString(5, fWebsite);
            ps.setString(6, fContent);
            ps.setString(7, now);
            return ps;
        }, keyHolder);
        // 2026-06-22 v4.x polish：SQLite JDBC 不同版本对 RETURN_GENERATED_KEYS 行为不一致——
        //   xerial sqlite-jdbc 3.x 部分小版本 getGeneratedKeys() 返回空 result set，
        //   keyHolder.getKey() 直接 null。原代码下一步 key.longValue() 直接 NPE。
        //   兜底：用 SQLite last_insert_rowid() 主动查一次（MySQL 走 LAST_INSERT_ID()，
        //   但 SQL 标准 SQLite 函数 last_insert_rowid() 在 MySQL 8.0+ 也支持）。
        //   当前 profile 主用 SQLite，所以直接用 last_insert_rowid()；切 MySQL 时改成 LAST_INSERT_ID()。
        Number key = keyHolder.getKey();
        Long newId = key != null ? key.longValue() : jdbc.queryForObject(
                "SELECT last_insert_rowid()", Long.class);
        if (newId == null) {
            // last_insert_rowid() 仍返 null（极罕见,可能是 RETURN_GENERATED_KEYS 之外整体失败）
            // 不抛异常——评论已提交,只是 newId 拿不到,业务能跑
            log.warn("评论提交后拿不到 newId（INSERT 可能成功也可能失败）articleSlug={}", articleSlug);
            newId = -1L;
        }

        log.info("访客评论提交：commentId={} articleSlug={} ip={} ua=\"{}\"",
                newId, articleSlug, ip, request.getHeader("User-Agent"));
        Map<String, Object> data = new HashMap<>();
        data.put("id", newId);
        data.put("message", "评论已提交，待审核");
        return Result.success(data);
    }

    public Result<PageResult<Map<String, Object>>> adminList(int status, long page, long size) {
        if (page < 1) throw new BusinessException(400, "page 必须 >= 1");
        if (size < 1 || size > 100) throw new BusinessException(400, "size 必须在 1-100 之间");
        if (status < 0 || status > 2) throw new BusinessException(400, "status 必须是 0(待审)/1(通过)/2(拒绝)");
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE status = ?", Integer.class, status);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT id, article_id AS articleId, parent_id AS parentId, " +
                        "nickname, email, website, content, status, " +
                        "created_at AS createdAt " +
                        "FROM comment WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                status, size, (page - 1) * size);
        return Result.success(PageResult.of(records, total, page, size));
    }

    public Result<Void> updateStatus(Long id, Map<String, Integer> body, HttpServletRequest request) {
        if (body == null) throw new BusinessException(400, "请求体不能为空");
        Integer status = body.get("status");
        if (status == null) throw new BusinessException(400, "status 不能为空");
        if (status < 0 || status > 2) throw new BusinessException(400, "status 必须是 0(待审)/1(通过)/2(拒绝)");
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE id = ?", Long.class, id);
        if (exists == null || exists == 0) throw new BusinessException(1001, "评论不存在");
        jdbc.update("UPDATE comment SET status = ? WHERE id = ?", status, id);
        log.info("评论审核：commentId={} status={} operator={}", id, status, AuthContext.uid(request));
        return Result.success();
    }

    public Result<Void> delete(Long id, HttpServletRequest request) {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE id = ?", Long.class, id);
        if (exists == null || exists == 0) throw new BusinessException(1001, "评论不存在");
        jdbc.update("DELETE FROM comment WHERE id = ?", id);
        log.info("评论删除：commentId={} operator={}", id, AuthContext.uid(request));
        return Result.success();
    }

    // 固定窗口限流，in-memory（单实例）。如启用 MySQL 多实例需迁 Redis（基础设施已具备）
    private boolean isCommentLimited(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        long now = Instant.now().getEpochSecond();
        boolean[] limited = new boolean[]{false};
        COMMENT_LIMIT_MAP.compute(ip, (k, old) -> {
            if (old == null || now - old[1] > COMMENT_WINDOW_SECONDS) {
                return new long[]{1L, now};
            }
            if (old[0] >= COMMENT_LIMIT) {
                limited[0] = true;
                return old;
            }
            old[0]++;
            return old;
        });
        return limited[0];
    }

    /**
     * 2026-06-22 v4.x polish：COMMENT_LIMIT_MAP 周期清理（防内存泄漏）
     * 原实现只在 isCommentLimited 路径上累加 entry,无清理逻辑。
     * 公网可触发的评论接口下,攻击者用大量随机 IP 刷即可让 map 持续膨胀。
     * 修法：每 60s 扫一遍,删掉窗口已过期的 entry（windowStart < now - WINDOW）。
     * 攻击者只能贡献"存活 60s 内"的 entry——增长受 IP 限流 + 时间窗口双重抑制。
     */
    @Scheduled(fixedDelay = 60 * 1000L, initialDelay = 60 * 1000L)
    void evictExpiredCommentLimit() {
        long now = Instant.now().getEpochSecond();
        int n = 0;
        java.util.Iterator<java.util.Map.Entry<String, long[]>> it = COMMENT_LIMIT_MAP.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, long[]> e = it.next();
            if (now - e.getValue()[1] > COMMENT_WINDOW_SECONDS) {
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            log.info("[CommentService] COMMENT_LIMIT_MAP 周期清理: removed={} remaining={}", n, COMMENT_LIMIT_MAP.size());
        }
    }
}
