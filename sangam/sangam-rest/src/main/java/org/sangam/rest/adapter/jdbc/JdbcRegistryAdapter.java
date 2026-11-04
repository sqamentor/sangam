// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.invalid;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Declarative registries (R71 service registry, R79 SLA models, R82 routing strategies, R74 templates, R81
 * conversation channels, R72 agents, R29/R61 retention classes), persisted in {@code REST_DOCUMENT} under kind
 * {@code registry:<kind>}: {@code doc_key} = the entry key (unique per tenant and kind, {@code 409 DUPLICATE_KEY}),
 * {@code state} = the entry state, and the body's {@code revision} mirrors the document revision (one write per
 * change). Every change is a class-B event {@code REGISTRY_<KIND>_CHANGED} on the tenant security chain, committed with
 * the change.
 */
public final class JdbcRegistryAdapter implements RegistryPort {

    private static final Set<String> SERVER_FIELDS = Set.of("id", "kind", "revision", "createdBy", "createdAt",
            "updatedBy", "updatedAt", "tenantId");
    private static final Set<String> STRATEGIES = Set.of("ROUND_ROBIN", "LEAST_LOADED", "SKILLS", "SLA_PRIORITY",
            "PULL_QUEUE");
    private static final Set<String> FORMATS = Set.of("HTML", "PDFA", "DOCX");
    private static final Set<String> CHANNELS = Set.of("MATRIX", "TEAMS", "SLACK", "WHATSAPP");
    private static final Set<String> CATEGORIES = Set.of("A", "B", "C");

    private final DocumentStore store;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;

    public JdbcRegistryAdapter(DocumentStore store, AuditPort audit, RestProperties props) {
        this.store = store;
        this.tx = store.tx();
        this.audit = audit;
        this.props = props;
    }

    @Override
    public Page<Map<String, Object>> list(RequestContext ctx, String kind, String q, Page.PageRequest page) {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> all = bucket(ctx, kind).values().stream()
                .filter(e -> needle == null || text(e.get("key")).contains(needle) || text(e.get("name")).contains(needle))
                .sorted(Comparator.comparing(e -> String.valueOf(e.get("key"))))
                .map(JdbcRegistryAdapter::copy).toList();
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Map<String, Object> get(RequestContext ctx, String kind, String id) {
        return copy(entry(ctx, kind, id));
    }

    @Override
    public synchronized Map<String, Object> create(RequestContext ctx, String kind, Map<String, Object> in) {
        return tx.inTx(() -> {
            DocMap<Map<String, Object>>.View b = bucket(ctx, kind);
            Map<String, Object> e = clean(in);
            validate(kind, e);
            String key = String.valueOf(e.get("key"));
            if (!b.ids(DocumentStore.Filter.key(key)).isEmpty()) {
                throw ApiProblem.conflict("DUPLICATE_KEY", kind + " entry with key " + key + " already exists");
            }
            Instant now = Instant.now();
            String id = JdbcSupport.newId();
            e.put("id", id);
            e.put("kind", kind);
            e.put("revision", 1L);
            e.putIfAbsent("state", "DRAFT");
            e.put("createdBy", ctx.principalId());
            e.put("createdAt", now.toString());
            e.put("updatedAt", now.toString());
            b.put(id, e);
            record(ctx, kind, "create", e, null);
            return copy(e);
        });
    }

    @Override
    public synchronized Map<String, Object> update(RequestContext ctx, String kind, String id, Map<String, Object> in) {
        return tx.inTx(() -> {
            Map<String, Object> cur = entry(ctx, kind, id);
            long current = ((Number) cur.get("revision")).longValue();
            Object rev = in == null ? null : in.get("revision");
            if (rev != null) {
                long expected;
                try {
                    expected = rev instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(rev));
                } catch (NumberFormatException ex) {
                    throw invalid("revision", "revision must be a number");
                }
                JdbcSupport.checkRevision(expected, current, kind + " entry", id);
            }
            Map<String, Object> e = clean(in);
            e.putIfAbsent("key", cur.get("key"));
            if (!String.valueOf(cur.get("key")).equals(String.valueOf(e.get("key")))) {
                throw invalid("key", "the key of a registry entry cannot change");
            }
            validate(kind, e);
            e.putIfAbsent("state", cur.get("state"));
            e.put("id", id);
            e.put("kind", kind);
            e.put("revision", current + 1);
            e.put("createdBy", cur.get("createdBy"));
            e.put("createdAt", cur.get("createdAt"));
            e.put("updatedBy", ctx.principalId());
            e.put("updatedAt", Instant.now().toString());
            bucket(ctx, kind).put(id, e);
            record(ctx, kind, "update", e, null);
            return copy(e);
        });
    }

    @Override
    public synchronized void delete(RequestContext ctx, String kind, String id, String reason) {
        tx.runInTx(() -> {
            if (reason == null || reason.isBlank()) {
                throw ApiProblem.badRequest("REASON_REQUIRED", "Deleting a registry entry requires a reason");
            }
            Map<String, Object> cur = entry(ctx, kind, id);
            bucket(ctx, kind).remove(id);
            record(ctx, kind, "delete", cur, reason);
        });
    }

    @Override
    public synchronized Map<String, Object> action(RequestContext ctx, String kind, String id, String action,
                                                   Map<String, Object> body) {
        return tx.inTx(() -> {
            Map<String, Object> cur = entry(ctx, kind, id);
            String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
            String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
            switch (a) {
                case "activate", "retire" -> {
                    Map<String, Object> e = new LinkedHashMap<>(cur);
                    e.put("state", "activate".equals(a) ? "ACTIVE" : "RETIRED");
                    e.put("revision", ((Number) cur.get("revision")).longValue() + 1);
                    e.put("updatedBy", ctx.principalId());
                    e.put("updatedAt", Instant.now().toString());
                    bucket(ctx, kind).put(id, e);
                    record(ctx, kind, a, e, reason);
                    return copy(e);
                }
                case "test" -> {
                    if (!"templates".equals(kind) && !"services".equals(kind)) {
                        throw ApiProblem.unprocessable("ACTION_NOT_SUPPORTED", "test is defined for templates and services");
                    }
                    validate(kind, new LinkedHashMap<>(cur));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ok", true);
                    r.put("id", id);
                    r.put("message", kind + " entry " + cur.get("key") + " is valid");
                    return r;
                }
                default -> throw invalid("action", "action must be activate, retire or test");
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void validate(String kind, Map<String, Object> e) {
        req(e, "key");
        switch (kind) {
            case "services", "agents" -> {
                req(e, "name");
                if ("agents".equals(kind)) {
                    req(e, "provider");
                }
            }
            case "sla-models" -> {
                req(e, "name");
                positive(e, "targetHours");
            }
            case "routing-strategies" -> JdbcSupport.oneOf(str(e, "strategy"), "strategy", STRATEGIES);
            case "templates" -> {
                req(e, "name");
                JdbcSupport.oneOf(str(e, "format"), "format", FORMATS);
                if (!(e.get("languages") instanceof Collection<?> c) || c.isEmpty()) {
                    throw invalid("languages", "languages must be a non-empty list");
                }
            }
            case "conversation-channels" -> JdbcSupport.oneOf(str(e, "channel"), "channel", CHANNELS);
            case "retention-classes" -> {
                JdbcSupport.oneOf(str(e, "category"), "category", CATEGORIES);
                positive(e, "retentionYears");
            }
            default -> throw ApiProblem.notFound("Registry", kind);
        }
    }

    private static void req(Map<String, Object> e, String field) {
        JdbcSupport.required(str(e, field), field);
    }

    private static String str(Map<String, Object> e, String field) {
        Object v = e.get(field);
        return v == null ? null : String.valueOf(v);
    }

    private static void positive(Map<String, Object> e, String field) {
        req(e, field);
        try {
            if (new BigDecimal(str(e, field)).signum() <= 0) {
                throw invalid(field, field + " must be positive");
            }
        } catch (NumberFormatException ex) {
            throw invalid(field, field + " must be a number");
        }
    }

    private static Map<String, Object> clean(Map<String, Object> in) {
        Map<String, Object> e = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (!SERVER_FIELDS.contains(k)) {
                    e.put(k, v);
                }
            });
        }
        return e;
    }

    /** Null-tolerant read-only copy (JSON entries may hold null values). */
    private static Map<String, Object> copy(Map<String, Object> e) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(e));
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    private void record(RequestContext ctx, String kind, String change, Map<String, Object> e, String reason) {
        String type = "REGISTRY_" + kind.toUpperCase(Locale.ROOT).replace('-', '_') + "_CHANGED";
        audit.record(ctx, JdbcSupport.securityChain(ctx), type, payload("change", change, "id", e.get("id"),
                "key", e.get("key"), "revision", e.get("revision"), "state", e.get("state"), "reason", reason,
                "actor", ctx.actor()));
    }

    private DocMap<Map<String, Object>>.View bucket(RequestContext ctx, String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw ApiProblem.notFound("Registry", kind);
        }
        DocMap<Map<String, Object>> map = new DocMap<>(store, "registry:" + kind, body -> body,
                e -> DocMap.ix(e.get("key") == null ? null : String.valueOf(e.get("key")), null, null,
                        e.get("state") == null ? null : String.valueOf(e.get("state"))));
        return map.in(JdbcSupport.tenant(ctx));
    }

    private Map<String, Object> entry(RequestContext ctx, String kind, String id) {
        Map<String, Object> e = id == null ? null : bucket(ctx, kind).get(id);
        if (e == null) {
            throw ApiProblem.notFound(kind + " entry", id);
        }
        return e;
    }
}
