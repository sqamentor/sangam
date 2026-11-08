// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Trusted-proxy handling (SL-18). Client-address and forwarding headers ({@code Forwarded}, {@code X-Forwarded-*}
 * and any operator-configured client-IP header such as an edge's connecting-IP header) are honoured
 * <b>only</b> when the direct peer address lies in {@code sangam.http.trusted-proxies} (CIDR list). From any other
 * peer those headers are removed before the application sees the request, so a client cannot spoof its address,
 * scheme or host. With an empty list (the default) no forwarding header is ever honoured.
 *
 * <p>Spring Boot's own forwarded-header handling is switched off ({@code server.forward-headers-strategy=none}) so
 * this filter is the single place that decides.
 */
public final class TrustedProxyFilter extends OncePerRequestFilter {

    /** Headers always treated as forwarding headers (stripped unless the peer is trusted). */
    static final List<String> FORWARDING = List.of("forwarded", "x-forwarded-for", "x-forwarded-proto",
            "x-forwarded-host", "x-forwarded-port", "x-forwarded-prefix", "x-real-ip");

    private final List<Cidr> trusted;
    private final List<String> clientIpHeaders;
    private final Set<String> stripped;

    /**
     * @param trustedProxies CIDRs (or single addresses) of the reverse proxies / edge in front of the server
     * @param clientIpHeaders headers that carry the client address, in priority order (e.g. an edge's
     *                        connecting-IP header, then {@code X-Forwarded-For}); {@code Forwarded} is always read last
     */
    public TrustedProxyFilter(List<String> trustedProxies, List<String> clientIpHeaders) {
        List<Cidr> t = new ArrayList<>();
        for (String s : trustedProxies == null ? List.<String>of() : trustedProxies) {
            if (s != null && !s.isBlank()) {
                t.add(Cidr.parse(s.trim()));
            }
        }
        this.trusted = List.copyOf(t);
        List<String> h = new ArrayList<>();
        for (String s : clientIpHeaders == null || clientIpHeaders.isEmpty() ? List.of("X-Forwarded-For") : clientIpHeaders) {
            if (s != null && !s.isBlank()) {
                h.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.clientIpHeaders = List.copyOf(h);
        Set<String> all = new TreeSet<>(FORWARDING);
        all.addAll(this.clientIpHeaders);
        this.stripped = Collections.unmodifiableSet(all);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(wrap(req), res);
    }

    /** The request as the application must see it. */
    HttpServletRequest wrap(HttpServletRequest req) {
        if (!isTrusted(req.getRemoteAddr())) {
            return new Stripped(req, stripped, req.getRemoteAddr(), req.getScheme(), req.getServerName(), req.getServerPort());
        }
        String client = clientAddress(req);
        String proto = first(req.getHeader("X-Forwarded-Proto"));
        String host = first(req.getHeader("X-Forwarded-Host"));
        String scheme = proto == null ? req.getScheme() : proto.toLowerCase(Locale.ROOT);
        String name = req.getServerName();
        int port = req.getServerPort();
        if (host != null) {
            int c = host.lastIndexOf(':');
            if (c > 0 && host.indexOf(']') < c) {
                name = host.substring(0, c);
                port = parsePort(host.substring(c + 1), port);
            } else {
                name = host;
                port = "https".equals(scheme) ? 443 : 80;
            }
        } else if (proto != null) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        String fwdPort = first(req.getHeader("X-Forwarded-Port"));
        if (fwdPort != null) {
            port = parsePort(fwdPort, port);
        }
        return new Stripped(req, Set.of(), client == null ? req.getRemoteAddr() : client, scheme, name, port);
    }

    boolean isTrusted(String addr) {
        if (addr == null || trusted.isEmpty()) {
            return false;
        }
        for (Cidr c : trusted) {
            if (c.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /** First client-IP header present wins; for list headers the right-most address that is not a trusted proxy. */
    private String clientAddress(HttpServletRequest req) {
        for (String h : clientIpHeaders) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) {
                String ip = rightmostUntrusted(List.of(v.split(",")));
                if (ip != null) {
                    return ip;
                }
            }
        }
        String fwd = req.getHeader("Forwarded");
        if (fwd != null) {
            List<String> fors = new ArrayList<>();
            for (String elem : fwd.split(",")) {
                for (String pair : elem.split(";")) {
                    String p = pair.trim();
                    if (p.regionMatches(true, 0, "for=", 0, 4)) {
                        String ip = p.substring(4).replace("\"", "");
                        if (ip.startsWith("[")) {
                            ip = ip.substring(1, ip.indexOf(']') > 0 ? ip.indexOf(']') : ip.length());
                        } else if (ip.indexOf(':') > 0 && ip.indexOf(':') == ip.lastIndexOf(':')) {
                            ip = ip.substring(0, ip.indexOf(':'));
                        }
                        fors.add(ip);
                    }
                }
            }
            return rightmostUntrusted(fors);
        }
        return null;
    }

    private String rightmostUntrusted(List<String> chain) {
        String last = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            String ip = chain.get(i).trim();
            if (ip.isEmpty() || !Cidr.isLiteral(ip)) {
                return last;
            }
            last = ip;
            if (!isTrusted(ip)) {
                return ip;
            }
        }
        return last;
    }

    private static String first(String v) {
        if (v == null) {
            return null;
        }
        String s = v.split(",")[0].trim();
        return s.isEmpty() ? null : s;
    }

    private static int parsePort(String s, int dflt) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** IPv4/IPv6 CIDR; literals only, never DNS. */
    record Cidr(BigInteger network, int bits, int width) {
        static Cidr parse(String s) {
            int slash = s.indexOf('/');
            String addr = slash < 0 ? s : s.substring(0, slash);
            if (!isLiteral(addr)) {
                throw new IllegalArgumentException("sangam.http.trusted-proxies: not an IP literal/CIDR: " + s);
            }
            byte[] b = bytes(addr);
            int width = b.length * 8;
            int bits = slash < 0 ? width : Integer.parseInt(s.substring(slash + 1));
            if (bits < 0 || bits > width) {
                throw new IllegalArgumentException("sangam.http.trusted-proxies: bad prefix length: " + s);
            }
            return new Cidr(mask(new BigInteger(1, b), bits, width), bits, width);
        }

        boolean contains(String addr) {
            if (!isLiteral(addr)) {
                return false;
            }
            byte[] b = bytes(addr);
            if (b.length * 8 != width) {
                return false;
            }
            return mask(new BigInteger(1, b), bits, width).equals(network);
        }

        private static BigInteger mask(BigInteger v, int bits, int width) {
            BigInteger m = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE)
                    .xor(BigInteger.ONE.shiftLeft(width - bits).subtract(BigInteger.ONE));
            return v.and(m);
        }

        static boolean isLiteral(String s) {
            return s.matches("[0-9.]+") && s.split("\\.").length == 4 || s.contains(":") && s.matches("[0-9A-Fa-f:.%a-zA-Z]+");
        }

        private static byte[] bytes(String addr) {
            try {
                int pct = addr.indexOf('%');
                return InetAddress.getByName(pct > 0 ? addr.substring(0, pct) : addr).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not an IP literal: " + addr, e);
            }
        }
    }

    /** Request view with forwarding headers removed and the effective address/scheme/host applied. */
    static final class Stripped extends HttpServletRequestWrapper {
        private final Set<String> hidden;
        private final String remote;
        private final String scheme;
        private final String name;
        private final int port;

        Stripped(HttpServletRequest r, Set<String> hidden, String remote, String scheme, String name, int port) {
            super(r);
            this.hidden = hidden;
            this.remote = remote;
            this.scheme = scheme;
            this.name = name;
            this.port = port;
        }

        private boolean hide(String n) {
            return n != null && hidden.contains(n.toLowerCase(Locale.ROOT));
        }

        @Override public String getHeader(String n) { return hide(n) ? null : super.getHeader(n); }
        @Override public Enumeration<String> getHeaders(String n) {
            return hide(n) ? Collections.emptyEnumeration() : super.getHeaders(n);
        }
        @Override public Enumeration<String> getHeaderNames() {
            List<String> out = new ArrayList<>();
            for (Enumeration<String> e = super.getHeaderNames(); e.hasMoreElements(); ) {
                String n = e.nextElement();
                if (!hide(n)) {
                    out.add(n);
                }
            }
            return Collections.enumeration(out);
        }
        @Override public long getDateHeader(String n) { return hide(n) ? -1 : super.getDateHeader(n); }
        @Override public int getIntHeader(String n) { return hide(n) ? -1 : super.getIntHeader(n); }
        @Override public String getRemoteAddr() { return remote; }
        @Override public String getRemoteHost() { return remote; }
        @Override public String getScheme() { return scheme; }
        @Override public boolean isSecure() { return "https".equals(scheme); }
        @Override public String getServerName() { return name; }
        @Override public int getServerPort() { return port; }
        @Override public StringBuffer getRequestURL() {
            StringBuffer sb = new StringBuffer(scheme).append("://").append(name);
            if (!("https".equals(scheme) && port == 443) && !("http".equals(scheme) && port == 80)) {
                sb.append(':').append(port);
            }
            return sb.append(getRequestURI());
        }
    }
}
