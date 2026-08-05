package com.blog.common.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IpLocationUtilTest {

    @Test
    public void testInternalIp() {
        assertTrue(IpLocationUtil.isInternalIp("127.0.0.1"));
        assertTrue(IpLocationUtil.isInternalIp("::1"));
        assertTrue(IpLocationUtil.isInternalIp("0:0:0:0:0:0:0:1"));
        assertTrue(IpLocationUtil.isInternalIp("192.168.1.100"));
        assertTrue(IpLocationUtil.isInternalIp("10.0.0.1"));
        assertTrue(IpLocationUtil.isInternalIp("172.16.0.1"));

        assertEquals("内网IP", IpLocationUtil.getLocation("127.0.0.1"));
        assertEquals("127.0.0.1【内网IP】", IpLocationUtil.getDisplayIp("127.0.0.1"));
    }

    @Test
    public void testPublicIp() {
        assertFalse(IpLocationUtil.isInternalIp("114.114.114.114"));
        assertEquals("114.114.114.114【江苏南京】", IpLocationUtil.getDisplayIp("114.114.114.114"));
    }
}
