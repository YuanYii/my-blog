package com.blog.comment.controller;

import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.common.TrustedProxyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评论（简版：用 JdbcTemplate 直接写 SQL，避免 entity 复杂度）
 */
@Slf4j
@RestController
@RequestMapping("/comments")
@RequiredArgsConstructor
public class CommentController {

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 公开评论限流：同 IP 10 次/分钟（防垃圾评论刷）
     * 2026-06-12 新增：原接口无任何限流，实测 10 并发全入库
     * 与 AuthController.login 限流是 in-memory 实现（多实例需换 Redis，但单机够用）
     */
    private static final int COMMENT_LIMIT = 10;
    private static final int COMMENT_WINDOW_SECONDS = 60;
    private static final ConcurrentHashMap<String, long[]> COMMENT_LIMIT_MAP = new ConcurrentHashMap<>();

    /** 公开：评论列表（按文章 ID） */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(required = false) Long articleId,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size) {
        // 2026-06-12 修复：articleId 缺失抛 MissingServletRequestParameterException → 500
        if (articleId == null) {
            return Result.error(400, "articleId 不能为空");
        }
        // 2026-06-12 修复：补 page/size 边界校验（与 article 保持一致：1-100）
        if (page < 1) return Result.error(400, "page 必须 >= 1");
        if (size < 1 || size > 100) return Result.error(400, "size 必须在 1-100 之间");
        // 2026-06-13 修复（BUG-055 配套）：JOIN article 拿 title + slug，前端不用再调 /articles 配 articleId → slug
        // ——也避免 admin 后台"未知文章"问题（之前 size=100 截断）
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE article_id = ? AND status = 1",
                Long.class, articleId);
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

    /** 公开：提交评论（默认 status=0 待审） */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        // 2026-06-12 新增：IP 限流 10 次/分钟
        String ip = clientIp(request);
        if (isCommentLimited(ip)) {
            return Result.error(429, "评论过于频繁，请 1 分钟后再试");
        }
        Object articleIdObj = body.get("articleId");
        if (articleIdObj == null) {
            return Result.error(400, "articleId 不能为空");
        }
        Long articleId = ((Number) articleIdObj).longValue();
        String nickname = (String) body.get("nickname");
        String content = (String) body.get("content");
        if (nickname == null || nickname.trim().isEmpty()) {
            return Result.error(400, "昵称不能为空");
        }
        if (nickname.length() > 50) {
            // 2026-06-12 修复：原逻辑没校验 nickname 长度，超长直接 500（DB column varchar(50)）
            return Result.error(400, "昵称长度不能超过 50 字符");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "评论内容不能为空");
        }
        if (content.length() > 2000) {
            // 2026-06-12 修复：content text 上限约 65535 字节，但太长影响存储和阅读，做个合理上限
            return Result.error(400, "评论内容长度不能超过 2000 字符");
        }
        Long parentId = body.get("parentId") != null ? ((Number) body.get("parentId")).longValue() : 0L;
        String email = (String) body.get("email");
        String website = (String) body.get("website");
        // 邮箱长度 / website 长度也防一下
        if (email != null && email.length() > 100) {
            return Result.error(400, "邮箱长度不能超过 100 字符");
        }
        if (website != null && website.length() > 200) {
            return Result.error(400, "网站 URL 长度不能超过 200 字符");
        }

        // 验证文章存在且已发布
        Long articleExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article WHERE id = ? AND status = 1 AND deleted = 0",
                Long.class, articleId);
        if (articleExists == null || articleExists == 0) {
            return Result.error(1001, "文章不存在或未发布");
        }

        // 2026-06-12 修复：原 INSERT 后用独立 `SELECT LAST_INSERT_ID()` 取自增 ID，
        // 但 JdbcTemplate 不在事务里时两次调用从连接池拿到的可能不是同一条 Connection，
        // LAST_INSERT_ID 是 connection 级 session 变量，会取到 0 或其它请求最近一次自增 ID（数据错乱）。
        // 修复：用 GeneratedKeyHolder + Statement.RETURN_GENERATED_KEYS 单次取回，连接安全。
        final Long fArticleId = articleId;
        final Long fParentId = parentId;
        final String fNickname = nickname;
        final String fEmail = email;
        final String fWebsite = website;
        final String fContent = content;
        final Timestamp now = Timestamp.valueOf(LocalDateTime.now());
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
            ps.setTimestamp(7, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long newId = key != null ? key.longValue() : null;

        Map<String, Object> data = new HashMap<>();
        data.put("id", newId);
        data.put("message", "评论已提交，待审核");
        return Result.success(data);
    }

    /** admin：评论列表（含待审） */
    @GetMapping("/admin")
    public Result<PageResult<Map<String, Object>>> adminList(
            @RequestParam(defaultValue = "0") int status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 2026-06-12 修复：与公开 list 一样补 page/size 上限
        if (page < 1) return Result.error(400, "page 必须 >= 1");
        if (size < 1 || size > 100) return Result.error(400, "size 必须在 1-100 之间");
        if (status < 0 || status > 2) return Result.error(400, "status 必须是 0(待审)/1(通过)/2(拒绝)");
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE status = ?", Integer.class, status);
        // 显式列名 + AS 别名（驼峰），避免 SELECT * 把 snake_case 暴露给前端
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT id, article_id AS articleId, parent_id AS parentId, " +
                        "nickname, email, website, content, status, " +
                        "created_at AS createdAt " +
                        "FROM comment WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                status, size, (page - 1) * size);
        return Result.success(PageResult.of(records, total, page, size));
    }

    /** admin：通过 / 屏蔽 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody(required = false) Map<String, Integer> body) {
        if (body == null) return Result.error(400, "请求体不能为空");
        Integer status = body.get("status");
        if (status == null) return Result.error(400, "status 不能为空");
        // 2026-06-12 修复：原逻辑 status 没白名单（0/1/2），传 99 也能更新
        if (status < 0 || status > 2) {
            return Result.error(400, "status 必须是 0(待审)/1(通过)/2(拒绝)");
        }
        // 校验评论存在
        Long exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE id = ?", Long.class, id);
        if (exists == null || exists == 0) {
            return Result.error(1001, "评论不存在");
        }
        jdbc.update("UPDATE comment SET status = ? WHERE id = ?", status, id);
        return Result.success();
    }

    /** admin：物理删除评论 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        // 校验评论存在
        Long exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE id = ?", Long.class, id);
        if (exists == null || exists == 0) {
            return Result.error(1001, "评论不存在");
        }
        jdbc.update("DELETE FROM comment WHERE id = ?", id);
        return Result.success();
    }

    // ============ 限流工具方法 ============

    private boolean isCommentLimited(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        long now = Instant.now().getEpochSecond();
        // 2026-06-12 修复：原实现 get → 检查 → entry[0]++ 不是原子操作，
        // 两个并发线程都读到 count=9 时会双双通过检查 → 实际入库 +2 而非 +1，
        // 高并发下能轻松突破 10 次/分钟限制。
        // 修复：用 ConcurrentHashMap.compute 让整个 read-modify-write 在同一锁段内执行。
        // 返回值用 boolean[1] 承载——compute 内只能改 entry 数组本身。
        boolean[] limited = new boolean[]{false};
        COMMENT_LIMIT_MAP.compute(ip, (k, old) -> {
            if (old == null || now - old[1] > COMMENT_WINDOW_SECONDS) {
                // 新窗口或过期
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

    private String clientIp(HttpServletRequest req) {
        // 2026-06-12 修复：原本只信任 IPv4 loopback。docker compose 生产部署 backend.req.getRemoteAddr()
        // 是 nginx 容器的私网 IP（172.x），命中不了 loopback → X-Real-IP / X-Forwarded-For 都被忽略 →
        // 整站访客的评论限流退化为"所有人共用一个 IP"，10 条/分钟瞬间被一两个垃圾刷掉。
        // 改走共享工具 TrustedProxyUtil（loopback + 私网 + IPv6 ULA / link-local 都视为可信反代）。
        return TrustedProxyUtil.resolveClientIp(req);
    }
}
