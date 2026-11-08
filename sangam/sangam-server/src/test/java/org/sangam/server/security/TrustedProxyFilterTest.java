// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    }
}
