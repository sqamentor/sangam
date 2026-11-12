// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SL-73: the Operaton REST resources ({@code /engine-rest/**}) a <b>tenant</b> identity may call. Fail closed: a
 * resource family is listed only when Operaton's tenant check covers it (its MyBatis queries carry the tenant check, and
 * its by-id commands go through {@code TenantCommandChecker}); everything else answers 403 for a tenant identity. Not
 * listed, because they have no tenant (Operaton 2.2 mappings {@code Authorization.xml}, {@code Filter.xml},
 * {@code User.xml}, {@code Group.xml}, {@code Membership.xml}, {@code TenantMembership.xml}, {@code Metrics.xml},
 * {@code SchemaLogEntry.xml}, {@code TaskMetrics.xml} have no tenant check) or act on the whole engine: authorizations,
 * filters, users, groups, tenants, identity, metrics, telemetry, schema log, history cleanup and the cleanable-history
 * reports, the node's registered deployments, the task report, external-task topic names. Platform identities
 * (SL-70, {@link PlatformIssuers}) are not limited by this list.
 *
 * <p>The path is decoded before it is matched (Jersey routes the decoded path), and Operaton's named-engine prefix
 * {@code /engine/{name}} is removed first; the bare {@code /engine} (the list of engine names) is allowed.
 */
public final class EngineRestTenantScope {

    /** Top-level resource families of a tenant identity (first path segment after {@code /engine-rest}). */
    public static final Set<String> FAMILIES = Set.of("process-definition", "decision-definition",
            "decision-requirements-definition", "case-definition", "deployment", "process-instance", "execution", "task",
            "variable-instance", "event-subscription", "external-task", "job", "job-definition", "incident", "batch",
            "migration", "modification", "case-instance", "case-execution", "message", "signal");

    /** {@code /engine-rest/history/<family>} of a tenant identity. */
    public static final Set<String> HISTORY_FAMILIES = Set.of("activity-instance", "batch", "case-activity-instance",
            "case-instance", "decision-instance", "detail", "external-task-log", "identity-link-log", "incident", "job-log",
            "process-instance", "task", "variable-instance", "user-operation");

    /** Sub-resources of a listed family that act on the whole engine or have no tenant check: refused. */
    static final List<List<String>> EXCLUDED = List.of(List.of("deployment", "registered"), List.of("task", "report"),
            List.of("external-task", "topic-names"));

    private EngineRestTenantScope() {
    }

    /**
     * True when a tenant identity may call {@code path}, the request path after {@code /engine-rest} (raw, as in the
     * request URI). A path that cannot be decoded is refused.
     */
    public static boolean allowed(String path) {
        List<String> s = segments(path);
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.get(0).equals("engine")) {
            if (s.size() == 1) {
                return true; // the engine names
            }
            s = s.subList(2, s.size()); // /engine/{name}/<resource>
            if (s.isEmpty()) {
                return false;
            }
        }
        for (List<String> x : EXCLUDED) {
            if (s.size() >= x.size() && s.subList(0, x.size()).equals(x)) {
                return false;
            }
        }
        if (s.stream().anyMatch(p -> p.startsWith("cleanable-"))) {
            return false; // the cleanable-history reports serve the engine-wide history cleanup
        }
        if (s.get(0).equals("history")) {
            return s.size() >= 2 && HISTORY_FAMILIES.contains(s.get(1));
        }
        return FAMILIES.contains(s.get(0));
    }

    /** The decoded, non-empty path segments; {@code null} when the path is not a valid URI path. */
    static List<String> segments(String path) {
        if (path == null) {
            return List.of();
        }
        String decoded;
        try {
            // the raw (percent-encoded) path, decoded exactly as a URI path; '?' or '#' cannot be part of it
            if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
                return null;
            }
            decoded = new URI("http://engine-rest" + (path.startsWith("/") ? path : "/" + path)).getPath();
        } catch (URISyntaxException e) {
            return null;
        }
        if (decoded == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String p : decoded.split("/")) {
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals(".") || p.equals("..") || p.indexOf(';') >= 0 || p.indexOf('\\') >= 0) {
                return null; // never normalised here: refused
            }
            out.add(p);
        }
        return out;
    }
}
