package com.blog.common;

import javax.servlet.http.HttpServletRequest;

/**
 * 解析"真实客户端 IP"的共用工具
 *
 * 设计假设：
 *  - backend 不直接对公网暴露（docker-compose 没有 ports: 8080:8080，只通过 nginx 反代）
 *  - 所以"从 loopback / 私网 IP 来的请求"必然是反代过来的——读 X-Forwarded-For / X-Real-IP 是安全的
 *  - 任何来自非 loopback / 非私网的请求都视为"直连"，X-Forwarded-For 被忽略（防止伪造头绕过限流）
 *
 * 2026-06-12 抽出原因：原 AuthController.clientIp + CommentController.clientIp 重复实现，
 * 且都只信任 IPv4 loopback。docker compose 生产部署 backend.req.getRemoteAddr() 是
 * nginx 容器的私网 IP（如 172.20.0.5），命中不了 loopback → X-Real-IP 被丢 → 限流退化为"全站共一个 IP"。
 */
public final class TrustedProxyUtil {

    private TrustedProxyUtil() {}

    public static String resolveClientIp(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty() && !"unknown".equalsIgnoreCase(xff)) {
                return normalizeToIpv4(xff.split(",")[0].trim());
            }
            String xri = req.getHeader("X-Real-IP");
            if (xri != null && !xri.isEmpty()) return normalizeToIpv4(xri);
        }
        return normalizeToIpv4(remoteAddr);
    }

    /**
     * 2026-06-18 新增：把 IPv6 形态的 loopback / IPv4-mapped IPv6 转成纯 IPv4
     *
     * 触发场景：
     *   - dev 本机 curl localhost → req.getRemoteAddr() 在 macOS 默认返回 "0:0:0:0:0:0:0:1" (IPv6 loopback)
     *     即使是 IPv4 客户端（curl http://127.0.0.1:8080），servlet 容器也可能返回 IPv6 loopback
     *   - nginx 反代后端时偶尔也会用 IPv4-mapped IPv6 形式（::ffff:1.2.3.4）
     *
     * 不转的：纯 IPv6 公网地址（如 "2001:db8::1"）—— 这种是真实客户端 IPv6，不应该丢信息，
     * 限流按 IPv6 维度进行也没问题（admin_device / ip_ban 列宽 45 都装得下）。
     *
     * @param ip 任意形态的 IP 字符串
     * @return 优先 IPv4；纯 IPv6 保留原样；null/空原样返回
     */
    static String normalizeToIpv4(String ip) {
        if (ip == null || ip.isEmpty()) return ip;
        String s = ip.trim();
        // IPv6 loopback 两种写法 → 127.0.0.1
        if ("::1".equals(s) || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s)) {
            return "127.0.0.1";
        }
        // IPv4-mapped IPv6（::ffff:1.2.3.4）→ 1.2.3.4
        if (s.regionMatches(true, 0, "::ffff:", 0, 7)) {
            String tail = s.substring(7);
            // 防止出现 ::ffff:1.2.3.4.5 之类非法形态——只取第一段有效 IPv4
            // 简单按字符过滤：若 tail 含 ":" 说明不是 IPv4（IPv4-mapped 必须 7 字节完整）
            if (!tail.contains(":")) return tail;
        }
        return s;
    }

    /** loopback + RFC 1918 私网 + IPv6 loopback / ULA / link-local 都视为受信任反代 */
    static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String s = ip.trim();

        // IPv4 loopback
        if (s.startsWith("127.")) return true;

        // IPv6 loopback
        if (s.equals("0:0:0:0:0:0:0:1") || s.equals("::1")) return true;

        // IPv6 link-local fe80::/10
        if (s.toLowerCase().startsWith("fe80:")) return true;

        // IPv6 unique local fc00::/7（包含 fc / fd）
        String lower = s.toLowerCase();
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true;

        // IPv4 RFC 1918 私网
        // 10.0.0.0/8
        if (s.startsWith("10.")) return true;
        // 192.168.0.0/16
        if (s.startsWith("192.168.")) return true;
        // 172.16.0.0/12 → 172.16.0.0 ~ 172.31.255.255
        if (s.startsWith("172.")) {
            try {
                int second = Integer.parseInt(s.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
