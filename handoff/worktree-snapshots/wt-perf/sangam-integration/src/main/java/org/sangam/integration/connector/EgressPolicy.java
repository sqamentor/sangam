// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connector egress control (SANGAM.md R27, T23/T47, SSRF).
 *
 * <ul>
 *   <li><b>Tenant-scoped allow-list.</b> A destination must match an allow-list entry of its tenant (or of the
 *       platform, tenant {@code *}). Entries are {@code scheme://host[:port][/pathPrefix]}; a host may start with
 *       {@code *.} for one or more sub-domain levels.</li>
 *   <li><b>Private and metadata ranges blocked.</b> Every address the host resolves to is checked: loopback,
 *       link-local (169.254/16 including the cloud metadata address), site-local/RFC 1918, CGNAT 100.64/10, unique-local
 *       fc00::/7, multicast, the unspecified address and IPv4-mapped forms of all of them. Approved internal enterprise
 *       endpoints (an IFMS inside the State Data Centre) are allowed only through an explicit entry with
 *       {@code allowPrivate=true}.</li>
 *   <li><b>DNS pinning.</b> {@link #check} resolves once and returns the vetted addresses; the transport connects
 *       only after the check, and redirects are re-checked hop by hop (bounded by {@link #maxRedirects()}).</li>
 *   <li><b>Bounded responses.</b> {@link #maxResponseBytes()} caps what a transport reads.</li>
 * </ul>
 * The simulator profile uses {@link #simulatorLoopback()}, which allows only the loopback interface.
 */
public final class EgressPolicy {

    /** One allow-list entry. */
    public record Rule(String tenantId, String scheme, String hostPattern, int port, String pathPrefix, boolean allowPrivate) {
        public Rule {
            Objects.requireNonNull(tenantId, "tenantId");
            scheme = scheme.toLowerCase(Locale.ROOT);
            hostPattern = hostPattern.toLowerCase(Locale.ROOT);
            pathPrefix = pathPrefix == null || pathPrefix.isEmpty() ? "/" : pathPrefix;
        }

        /** Parses {@code scheme://host[:port][/path]}; port -1 means the scheme default. */
        public static Rule parse(String tenantId, String spec, boolean allowPrivate) {
            URI u = URI.create(spec);
            if (u.getScheme() == null || u.getHost() == null) {
                throw new IllegalArgumentException("egress rule needs scheme://host: " + spec);
            }
            return new Rule(tenantId, u.getScheme(), u.getHost(), u.getPort(), u.getPath(), allowPrivate);
        }

        boolean matches(String tenant, URI uri) {
            if (!(tenantId.equals("*") || tenantId.equals(tenant))) {
                return false;
            }
            if (!scheme.equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean hostOk = hostPattern.startsWith("*.")
                    ? host.endsWith(hostPattern.substring(1)) && host.length() > hostPattern.length() - 1
                    : host.equals(hostPattern);
            if (!hostOk) {
                return false;
            }
            if (port != -1 && effectivePort(uri) != port) {
                return false;
            }
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            return path.startsWith(pathPrefix);
        }
    }

    /** A vetted destination: the URI plus the addresses it resolved to at check time. */
    public record Vetted(URI uri, List<InetAddress> addresses, boolean privateAllowed) {
    }

    /** Refusal of a destination (R27). Mapped by callers to a definite no-effect failure. */
    public static final class EgressDeniedException extends RuntimeException {
        public EgressDeniedException(String message) {
            super("EGRESS_DENIED: " + message);
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final boolean loopbackOnly;
    private final int maxRedirects;
    private final long maxResponseBytes;
    private final Map<String, List<InetAddress>> pinned = new ConcurrentHashMap<>();

    private EgressPolicy(List<Rule> rules, boolean loopbackOnly, int maxRedirects, long maxResponseBytes) {
        this.rules.addAll(rules);
        this.loopbackOnly = loopbackOnly;
        this.maxRedirects = maxRedirects;
        this.maxResponseBytes = maxResponseBytes;
    }

    /** Air-gapped default: nothing is allowed until rules are configured (ARCHITECTURE.md §1.9). */
    public static EgressPolicy denyAll() {
        return new EgressPolicy(List.of(), false, 3, 8L * 1024 * 1024);
    }

    public static EgressPolicy of(List<Rule> rules, int maxRedirects, long maxResponseBytes) {
        return new EgressPolicy(rules, false, maxRedirects, maxResponseBytes);
    }

    /** Simulator profile: only loopback destinations (the in-process simulators). */
    public static EgressPolicy simulatorLoopback() {
        return new EgressPolicy(List.of(), true, 3, 64L * 1024 * 1024);
    }

    public int maxRedirects() {
        return maxRedirects;
    }

    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    public EgressPolicy withRule(Rule rule) {
        List<Rule> all = new ArrayList<>(rules);
        all.add(rule);
        return new EgressPolicy(all, loopbackOnly, maxRedirects, maxResponseBytes);
    }

    /**
     * Checks a destination for a tenant and pins its resolved addresses. Throws {@link EgressDeniedException} when the
     * destination is not allowed. The check never causes an effect, so a refusal is always a no-effect failure.
     */
    public Vetted check(String tenantId, URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new EgressDeniedException("destination must be an absolute URI with a host: " + uri);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("http", "https", "sftp", "smtp", "smtps").contains(scheme)) {
            throw new EgressDeniedException("scheme not allowed: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new EgressDeniedException("credentials in the URI are not allowed");
        }
        List<InetAddress> addrs = resolve(uri.getHost());
        if (loopbackOnly) {
            for (InetAddress a : addrs) {
                if (!a.isLoopbackAddress()) {
                    throw new EgressDeniedException("simulator profile allows loopback only, got " + a.getHostAddress());
                }
            }
            return new Vetted(uri, addrs, true);
        }
        Rule rule = rules.stream().filter(r -> r.matches(tenantId, uri)).findFirst()
                .orElseThrow(() -> new EgressDeniedException("no allow-list entry for tenant " + tenantId + ": "
                        + uri.getScheme() + "://" + uri.getHost() + ":" + effectivePort(uri) + uri.getPath()));
        if (!rule.allowPrivate()) {
            for (InetAddress a : addrs) {
                if (isBlocked(a)) {
                    throw new EgressDeniedException(uri.getHost() + " resolves to a blocked address " + a.getHostAddress());
                }
            }
        }
        pinned.put(tenantId + "|" + uri.getHost().toLowerCase(Locale.ROOT), addrs);
        return new Vetted(uri, addrs, rule.allowPrivate());
    }

    /**
     * Re-check after connect: the address the transport actually used must be one of the pinned ones (DNS rebinding
     * between check and connect is refused).
     */
    public void verifyPinned(String tenantId, String host, InetAddress used) {
        List<InetAddress> p = pinned.get(tenantId + "|" + host.toLowerCase(Locale.ROOT));
        if (p != null && used != null && !p.contains(used)) {
            throw new EgressDeniedException("DNS answer changed for " + host + " (pinned " + p + ", used " + used + ")");
        }
    }

    /** True for loopback, link-local/metadata, private, CGNAT, ULA, multicast and unspecified addresses. */
    public static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()
                || a.isAnyLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0 || b0 == 10 || b0 == 127 || (b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 169 && b1 == 254)
                    || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168) || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0)
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 224;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) {
                return true;   // fc00::/7 unique local
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<InetAddress> resolve(String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            throw new EgressDeniedException("cannot resolve " + host);
        }
    }

    static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "https" -> 443;
            case "http" -> 80;
            case "sftp" -> 22;
            case "smtp" -> 25;
            case "smtps" -> 465;
            default -> -1;
        };
    }
}
