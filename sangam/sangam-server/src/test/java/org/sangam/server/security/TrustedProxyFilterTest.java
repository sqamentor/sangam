// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SL-18 (3): forwarding/client-IP headers are honoured only from configured trusted proxy CIDRs. */
class TrustedProxyFilterTest {

    private static MockHttpServletRequest req(String peer) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
        r.setRemoteAddr(peer);
        r.setScheme("http");
        r.setServerName("10.0.0.5");
        r.setServerPort(8080);
        r.addHeader("X-Forwarded-For", "203.0.113.7");
        r.addHeader("X-Forwarded-Proto", "https");
        r.addHeader("X-Forwarded-Host", "workflow.example.org");
        r.addHeader("CF-Connecting-IP", "198.51.100.9");
        r.addHeader("Forwarded", "for=192.0.2.1;proto=https");
        return r;
    }

    @Test
    void untrustedPeerHeadersAreIgnoredAndStripped() {
        TrustedProxyFilter f = new TrustedProxyFilter(List.of("10.10.0.0/16"), List.of("CF-Connecting-IP", "X-Forwarded-For"));
        HttpServletRequest w = f.wrap(req("192.168.1.50"));
        assertEquals("192.168.1.50", w.getRemoteAddr());
        assertEquals("http", w.getScheme());
        assertFalse(w.isSecure());
        assertEquals("10.0.0.5", w.getServerName());
        assertNull(w.getHeader("X-Forwarded-For"));
        assertNull(w.getHeader("cf-connecting-ip"));
        assertNull(w.getHeader("Forwarded"));
        assertFalse(Collections.list(w.getHeaderNames()).stream()
                .anyMatch(n -> n.toLowerCase(Locale.ROOT).contains("forward") || n.equalsIgnoreCase("CF-Connecting-IP")));
    }

    @Test
    void emptyTrustListTrustsNobody() {
        TrustedProxyFilter f = new TrustedProxyFilter(List.of(), List.of());
        HttpServletRequest w = f.wrap(req("127.0.0.1"));
        assertEquals("127.0.0.1", w.getRemoteAddr());
        assertNull(w.getHeader("X-Forwarded-For"));
    }

    @Test
    void trustedPeerHeadersAreHonouredInConfiguredPriority() {
        TrustedProxyFilter f = new TrustedProxyFilter(List.of("10.10.0.0/16"), List.of("CF-Connecting-IP", "X-Forwarded-For"));
        HttpServletRequest w = f.wrap(req("10.10.3.4"));
        assertEquals("198.51.100.9", w.getRemoteAddr());
        assertEquals("https", w.getScheme());
        assertTrue(w.isSecure());
        assertEquals("workflow.example.org", w.getServerName());
        assertEquals(443, w.getServerPort());
        assertEquals("https://workflow.example.org/api/v1/tasks", w.getRequestURL().toString());
    }

    @Test
    void xffChainSkipsTrustedHopsAndForwardedIsFallback() {
        TrustedProxyFilter f = new TrustedProxyFilter(List.of("10.10.0.0/16", "2001:db8::/32"), List.of("X-Forwarded-For"));
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("2001:db8::1");
        r.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7, 10.10.0.9");
        assertEquals("203.0.113.7", f.wrap(r).getRemoteAddr());
        MockHttpServletRequest r2 = new MockHttpServletRequest();
        r2.setRemoteAddr("10.10.0.1");
        r2.addHeader("Forwarded", "for=\"[2001:db8:cafe::17]:4711\";proto=https");
        assertEquals("2001:db8:cafe::17", f.wrap(r2).getRemoteAddr());
    }

    @Test
    void invalidCidrIsRejectedAtStartUp() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyFilter(List.of("proxy.example.org"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyFilter(List.of("10.0.0.0/40"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyFilter(List.of("10.0.0.999/8"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyFilter(List.of("abc:xyz"), List.of()));
    }

    // ---- code scanning #38 / #39: the address parser is text only (no resolver, no exception) -------------------------

    @Test
    void thingsThatAreNotAddressesAreNotLiterals() {
        for (String s : List.of("1.2.3.999", "1..2.3", "abc:xyz", "", "1.2.3", "1.2.3.4.5", "1.2.3.4.", ".1.2.3.4",
                "256.1.1.1", "1.2.3.0004", "01.2.3.4", "1.2.3.-4", "1.2.3.4 ", "localhost", "proxy.example.org",
                "example.org:80", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1::2::3", ":::", ":1::2", "1::2:", "12345::1",
                "g::1", "::1.2.3.999", "1.2.3.4::", "::1.2.3.4:5", "1:2:3:4:5:6:7::8", "fe80::1%", "fe80::1%eth0/x",
                "1.2.3.4%eth0", "１.2.3.4", "::１")) {
            assertFalse(TrustedProxyFilter.Cidr.isLiteral(s), "'" + s + "'");
            assertNull(TrustedProxyFilter.Cidr.literal(s), "'" + s + "'");
        }
        assertFalse(TrustedProxyFilter.Cidr.isLiteral(null));
        assertFalse(TrustedProxyFilter.Cidr.isLiteral("1".repeat(200) + "::1"), "an over-long entry is not parsed");
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    @Test
    void validLiteralsParseToTheirBytes() throws Exception {
        assertArrayEquals(bytes(203, 0, 113, 7), TrustedProxyFilter.Cidr.literal("203.0.113.7"));
        assertArrayEquals(bytes(0, 0, 0, 0), TrustedProxyFilter.Cidr.literal("0.0.0.0"));
        assertArrayEquals(bytes(255, 255, 255, 255), TrustedProxyFilter.Cidr.literal("255.255.255.255"));
        assertArrayEquals(bytes(0x20, 0x01, 0x0d, 0xb8, 0x85, 0xa3, 0, 0, 0, 0, 0x8a, 0x2e, 0x03, 0x70, 0x73, 0x34),
                TrustedProxyFilter.Cidr.literal("2001:0db8:85a3:0000:0000:8a2e:0370:7334"), "full IPv6");
        assertArrayEquals(bytes(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
                TrustedProxyFilter.Cidr.literal("2001:db8::1"), "compressed IPv6");
        assertArrayEquals(new byte[16], TrustedProxyFilter.Cidr.literal("::"));
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), TrustedProxyFilter.Cidr.literal("::1"));
        assertArrayEquals(bytes(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 0),
                TrustedProxyFilter.Cidr.literal("1:2:3:4:5:6:7::"), ":: for one group");
        assertArrayEquals(bytes(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xAB, 0xCD),
                TrustedProxyFilter.Cidr.literal("FE80::abCD%eth0"), "zoned IPv6: the zone is checked and dropped");
        assertArrayEquals(bytes(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 192, 0, 2, 33),
                TrustedProxyFilter.Cidr.literal("64:ff9b::192.0.2.33"), "an embedded IPv4 is the last 32 bits");
        // an IPv4-mapped address is the IPv4 address (the JDK's address type says the same)
        assertArrayEquals(bytes(192, 0, 2, 1), TrustedProxyFilter.Cidr.literal("::ffff:192.0.2.1"));
        assertArrayEquals(bytes(192, 0, 2, 1), TrustedProxyFilter.Cidr.literal("::FFFF:c000:201"));
        assertArrayEquals(bytes(192, 0, 2, 1), TrustedProxyFilter.Cidr.literal("0:0:0:0:0:ffff:192.0.2.1"));
        // the JDK's own parser as the oracle; every text here is a valid literal, which it never resolves
        for (String s : List.of("203.0.113.7", "10.10.3.4", "2001:db8::1", "::", "::1", "1:2:3:4:5:6:7:8",
                "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8", "2001:db8:cafe::17", "64:ff9b::192.0.2.33", "::ffff:192.0.2.1",
                "::ffff:c000:201", "fe80::1")) {
            assertArrayEquals(java.net.InetAddress.getByName(s).getAddress(), TrustedProxyFilter.Cidr.literal(s), s);
        }
    }

    @Test
    void cidrContainmentWorksOnTheParsedBytes() {
        TrustedProxyFilter.Cidr v4 = TrustedProxyFilter.Cidr.parse("10.10.0.0/16");
        assertTrue(v4.contains("10.10.255.254"));
        assertFalse(v4.contains("10.11.0.1"));
        assertTrue(v4.contains("::ffff:10.10.3.4"), "the IPv4-mapped form of a contained address");
        assertFalse(v4.contains("2001:db8::1"), "another address family");
        assertFalse(v4.contains("10.10.0.999"));
        assertFalse(v4.contains("not-an-address"));
        TrustedProxyFilter.Cidr v6 = TrustedProxyFilter.Cidr.parse("2001:db8::/32");
        assertTrue(v6.contains("2001:db8:ffff::1"));
        assertTrue(v6.contains("2001:db8::1%eth0"));
        assertFalse(v6.contains("2001:db9::1"));
        assertFalse(v6.contains("10.10.0.1"));
        assertFalse(v6.contains("abc:xyz"));
        TrustedProxyFilter.Cidr single = TrustedProxyFilter.Cidr.parse("192.0.2.7");
        assertTrue(single.contains("192.0.2.7"));
        assertFalse(single.contains("192.0.2.8"));
        assertTrue(TrustedProxyFilter.Cidr.parse("0.0.0.0/0").contains("198.51.100.1"));
    }

    @Test
    void aMalformedForwardedEntryFromATrustedPeerIsIgnoredWithoutAnException() {
        TrustedProxyFilter f = new TrustedProxyFilter(List.of("10.10.0.0/16"), List.of("X-Forwarded-For"));
        for (String forged : List.of("999.1.1.1", "1.2.3.999", "abc:xyz", "evil.example.org", "1..2.3")) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
            r.setRemoteAddr("10.10.3.4");
            r.addHeader("X-Forwarded-For", forged);
            assertEquals("10.10.3.4", f.wrap(r).getRemoteAddr(), "the peer address stands for '" + forged + "'");
        }
        MockHttpServletRequest fwd = new MockHttpServletRequest("GET", "/api/v1/tasks");
        fwd.setRemoteAddr("10.10.3.4");
        fwd.addHeader("Forwarded", "for=999.1.1.1");
        assertEquals("10.10.3.4", f.wrap(fwd).getRemoteAddr());
        assertFalse(f.isTrusted("999.1.1.1"));
        assertFalse(f.isTrusted("abc:xyz"));
        assertFalse(f.isTrusted(""));
    }
}
