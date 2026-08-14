package com.blog.common;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 网络与主机 IP 工具类
 * 用于自动识别当前服务器的局域网 IPv4 地址（供跨设备二维码分享等场景使用）
 */
@Slf4j
public final class NetworkUtil {

    private NetworkUtil() {}

    /**
     * 获取本机局域网 IPv4 地址（排除 loopback 与 docker 虚拟网卡）
     * 优先匹配 192.168.x.x / 10.x.x.x / 172.16-31.x.x
     */
    public static String getLocalLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String candidate = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                // 忽略未启用、回环与虚拟网卡
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName().toLowerCase();
                if (name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-")
                        || name.startsWith("vmnet") || name.startsWith("vbox")) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (isSiteLocalAddress(ip)) {
                            // 优先 192.168.x.x 或 10.x.x.x 常见局域网段
                            if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                                return ip;
                            }
                            if (candidate == null) {
                                candidate = ip;
                            }
                        }
                    }
                }
            }
            if (candidate != null) return candidate;
        } catch (Exception e) {
            log.warn("获取本机局域网 IP 异常: {}", e.getMessage());
        }
        return "127.0.0.1";
    }

    private static boolean isSiteLocalAddress(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }
}
