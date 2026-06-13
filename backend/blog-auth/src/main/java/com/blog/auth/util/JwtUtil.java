package com.blog.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类（HS256）
 */
@Component
public class JwtUtil {

    @Value("${blog.jwt.secret:blog-default-secret-key-please-change-in-production-32bytes}")
    private String secret;

    @Value("${blog.jwt.expiration:86400000}")  // 默认 24 小时（**安全加固 2026-06-07**：原 7 天过长，prod yml 已配 24h）
    private long expiration;

    /**
     * 启动时校验 JWT secret 强度（修复 2026-06-07：原不校验，弱 secret 会导致签名不安全）
     * - 长度 < 32 字节（256 bits，HS256 要求）→ 启动失败
     * - dev 默认值长度足够（65 字节），正常通过
     * - prod 没设 JWT_SECRET env → Spring 占位符解析失败（更早的 fail-fast）
     */
    @javax.annotation.PostConstruct
    public void validateSecret() {
        if (secret == null) {
            throw new IllegalStateException("JWT secret is null. Set JWT_SECRET env (e.g., `openssl rand -hex 32`).");
        }
        int byteLength = secret.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 32) {
            throw new IllegalStateException(
                "JWT secret too short: " + byteLength + " bytes (min 32 bytes / 256 bits for HS256). " +
                "Set JWT_SECRET env (e.g., `openssl rand -hex 32`)."
            );
        }
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token（含 deviceId claim）
     * deviceId 用于"token 借用"防护：AdminAuthFilter 验证 token.claim.deviceId == header.X-Device-Id
     */
    public String generate(Long userId, String username, String role, String deviceId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("role", role);
        claims.put("deviceId", deviceId);
        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expiration))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 兼容老调用（不传 deviceId）—— 仅用于单元测试或特殊场景 */
    public String generate(Long userId, String username, String role) {
        return generate(userId, username, role, "");
    }

    /** 解析 token，返回 Claims；失败抛异常 */
    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
