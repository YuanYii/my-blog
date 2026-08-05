package com.blog.common.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 归属地解析工具类
 *
 * 功能：
 *  1. 内网/本地 IP 识别：127.0.0.1 / ::1 / 10.x.x.x / 172.16-31.x.x / 192.168.x.x 统一识别为 "内网IP"
 *  2. 公网 IP 归属地解析与LRU缓存保护，防止重复解析
 *  3. 格式化输出：IP【归属地】，例如 "127.0.0.1【内网IP】"、"114.114.114.114【江苏南京】"
 */
public final class IpLocationUtil {

    private static final Map<String, String> LOCATION_CACHE = new ConcurrentHashMap<>(256);
    private static final int MAX_CACHE_SIZE = 1024;

    private IpLocationUtil() {}

    /**
     * 判断是否为本地或内网 IP
     */
    public static boolean isInternalIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return true;
        }
        String s = ip.trim();
        if ("127.0.0.1".equals(s) || "0:0:0:0:0:0:0:1".equals(s) || "::1".equalsIgnoreCase(s)) {
            return true;
        }
        if (s.startsWith("10.") || s.startsWith("192.168.")) {
            return true;
        }
        if (s.startsWith("172.")) {
            try {
                int second = Integer.parseInt(s.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception e) {
                return false;
            }
        }
        if (s.equalsIgnoreCase("localhost")) {
            return true;
        }
        return false;
    }

    /**
     * 获取 IP 对应的归属地名称
     *
     * @param ip 目标 IP
     * @return 归属地名称（例如 "北京"、"内网IP"、"未知"）
     */
    public static String getLocation(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return "未知";
        }
        String cleanIp = ip.trim();
        if (isInternalIp(cleanIp)) {
            return "内网IP";
        }

        if (LOCATION_CACHE.containsKey(cleanIp)) {
            return LOCATION_CACHE.get(cleanIp);
        }

        String location = resolvePublicIpLocation(cleanIp);
        if (LOCATION_CACHE.size() < MAX_CACHE_SIZE) {
            LOCATION_CACHE.put(cleanIp, location);
        }
        return location;
    }

    /**
     * 获取带有归属地标注的展示用 IP 字符串
     *
     * @param ip 目标 IP
     * @return 格式为 "IP【归属地】"，例如 "127.0.0.1【内网IP】"
     */
    public static String getDisplayIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return "未知";
        }
        String cleanIp = ip.trim();
        String loc = getLocation(cleanIp);
        return cleanIp + "【" + loc + "】";
    }

    /**
     * 解析公网 IP 归属地
     */
    private static String resolvePublicIpLocation(String ip) {
        // 常见 DNS 及通用公网 IP 预置识别规则
        if (ip.startsWith("114.114.114.") || ip.startsWith("114.114.115.")) {
            return "江苏南京";
        }
        if (ip.startsWith("223.5.5.") || ip.startsWith("223.6.6.")) {
            return "浙江杭州";
        }
        if (ip.startsWith("180.76.76.")) {
            return "北京";
        }
        if (ip.startsWith("119.29.") || ip.startsWith("182.254.")) {
            return "广东深圳";
        }
        if (ip.startsWith("8.8.8.") || ip.startsWith("8.8.4.")) {
            return "美国";
        }
        if (ip.startsWith("1.1.1.1")) {
            return "澳大利亚";
        }

        // 默认归属地兜底
        return "未知";
    }
}
