// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owner decision 305: the per-tenant DLT registration (principal-entity id, headers, content templates, whitelisted URL
 * prefixes) is stored ({@code DLT_REGISTRATION}, {@code DLT_REGISTRATION_ITEM}) and changed only through this class,
 * which the permission-checked admin route calls. Every change that alters the registration writes
 * {@code DLT_TEMPLATE_REGISTERED} on the tenant's chain {@value #CHAIN} in the same transaction and moves the tenant's
 * revision; registering what is already registered changes nothing and writes nothing. Start-up configuration only
 * seeds ({@link #seed}): it adds what is absent and never overwrites a stored entry. The {@link DltRegistry} the
 * connector validates against is loaded from the store after each change on this node and, on the others, by
 * {@link #refresh} when the revision moved.
 *
 * <p>Checks ({@link InvalidRegistration#reason()}): a header is 6 letters or digits with an optional TRAI suffix
 * {@code -P}, {@code -S}, {@code -T} or {@code -G} ({@code HEADER_INVALID}); a template has an id, a valid header and a
 * body of at most {@value #MAX_BODY} characters whose URLs never start with a variable, so a URL cannot be free text
 * ({@code TEMPLATE_INVALID}); a URL prefix is an http(s) URL with a host ({@code URL_INVALID}); an entity id is 1 to 64
 * letters or digits ({@code ENTITY_INVALID}).
 */
public final class DltRegistrationStore {

    public static final String EVENT = "DLT_TEMPLATE_REGISTERED";
    public static final String CHAIN = "dlt:registry";
    public static final String SEED_ACTOR = "system:config";
    public static final int MAX_BODY = 2000;

    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9]{6}(?:-[PSTG])?");
    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ENTITY_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern URL_PREFIX = Pattern.compile("(?i)https?://[^\\s/{}#?]+[^\\s{}]*");
    private static final Pattern VARIABLE_URL = Pattern.compile("(?i)(?:https?://|www\\.)" + Pattern.quote(DltRegistry.VAR));

    /** Kinds of registration item. */
    public enum Kind { HEADER, TEMPLATE, URL }

    /** A registration refused by the checks; {@link #reason()} is the code the route answers with (422). */
    public static final class InvalidRegistration extends IllegalArgumentException {
        private final String reason;

        public InvalidRegistration(String reason, String message) {
            super(reason + ": " + message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** One template as registered. */
    public record TemplateEntry(String templateId, String header, String body) {
    }

    /** A tenant's registration as stored. */
    public record Snapshot(String tenantId, String entityId, long revision, List<String> headers, List<TemplateEntry> templates,
                           List<String> urlPrefixes) {
    }

    /** The answer of a change: whether it altered the registration, and the tenant's revision after it. */
    public record Change(boolean changed, long revision) {
    }

    /** Start-up configuration of one tenant: seeds only. */
    public record Seed(String tenantId, String entityId, List<String> headers, List<TemplateEntry> templates, List<String> urls) {
        public Seed {
            headers = headers == null ? List.of() : List.copyOf(headers);
            templates = templates == null ? List.of() : List.copyOf(templates);
            urls = urls == null ? List.of() : List.copyOf(urls);
        }
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final DltRegistry registry = new DltRegistry();
    private final Map<String, Long> loaded = new ConcurrentHashMap<>();

    public DltRegistrationStore(Dialect dialect, Transactions tx, AuditChain audit) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audit = Objects.requireNonNull(audit, "audit: DLT_TEMPLATE_REGISTERED must be written");
    }

    /** The registry the DLT SMS connector validates against, kept in step with the store. */
    public DltRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------ changes

    public Change registerEntity(String tenantId, String actor, String entityId) {
        if (entityId == null || !ENTITY_ID.matcher(entityId).matches()) {
            throw new InvalidRegistration("ENTITY_INVALID", "the principal-entity id is 1 to 64 letters or digits");
        }
        return change(tenantId, actor, "ADMIN", c -> {
            String current = storedEntityId(c, tenantId);
            if (entityId.equals(current)) {
                return null;
            }
            Jdbc.update(c, "UPDATE DLT_REGISTRATION SET entity_id=? WHERE tenant_id=?", entityId, tenantId);
            return event("ENTITY", entityId, current != null, Map.of("entityId", entityId));
        });
    }

    public Change registerHeader(String tenantId, String actor, String header) {
        checkHeader(header);
        return item(tenantId, actor, "ADMIN", Kind.HEADER, header, null, null, true);
    }

    public Change registerTemplate(String tenantId, String actor, String templateId, String header, String body) {
        checkTemplate(templateId, header, body);
        return item(tenantId, actor, "ADMIN", Kind.TEMPLATE, templateId, header, body, true);
    }

    public Change registerUrl(String tenantId, String actor, String prefix) {
        checkUrl(prefix);
        return item(tenantId, actor, "ADMIN", Kind.URL, prefix.toLowerCase(java.util.Locale.ROOT), null, null, true);
    }

    /**
     * Start-up configuration: adds each entry that is not stored yet (an entity id only where none is stored), each
     * with its event as {@value #SEED_ACTOR}; a stored entry is never overwritten. Returns the entries added.
     *
     * @throws InvalidRegistration when the configuration holds an entry the checks refuse (start-up stops)
     */
    public int seed(List<Seed> seeds) {
        int added = 0;
        for (Seed s : seeds == null ? List.<Seed>of() : seeds) {
            Objects.requireNonNull(s.tenantId(), "seed tenantId");
            s.headers().forEach(DltRegistrationStore::checkHeader);
            s.templates().forEach(t -> checkTemplate(t.templateId(), t.header(), t.body()));
            s.urls().forEach(DltRegistrationStore::checkUrl);
            if (s.entityId() != null && !ENTITY_ID.matcher(s.entityId()).matches()) {
                throw new InvalidRegistration("ENTITY_INVALID", "seed of tenant " + s.tenantId());
            }
            if (s.entityId() != null && change(s.tenantId(), SEED_ACTOR, "SEED", c -> {
                boolean none = storedEntityId(c, s.tenantId()) == null;
                if (!none) {
                    return null;
                }
                Jdbc.update(c, "UPDATE DLT_REGISTRATION SET entity_id=? WHERE tenant_id=?", s.entityId(), s.tenantId());
                return event("ENTITY", s.entityId(), false, Map.of("entityId", s.entityId()));
            }).changed()) {
                added++;
            }
            for (String h : s.headers()) {
                added += item(s.tenantId(), SEED_ACTOR, "SEED", Kind.HEADER, h, null, null, false).changed() ? 1 : 0;
            }
            for (TemplateEntry t : s.templates()) {
                added += item(s.tenantId(), SEED_ACTOR, "SEED", Kind.TEMPLATE, t.templateId(), t.header(), t.body(), false).changed() ? 1 : 0;
            }
            for (String u : s.urls()) {
                added += item(s.tenantId(), SEED_ACTOR, "SEED", Kind.URL, u.toLowerCase(java.util.Locale.ROOT), null, null, false)
                        .changed() ? 1 : 0;
            }
            load(s.tenantId());
        }
        return added;
    }

    /** Adds or (when {@code replace}) changes one item; unchanged content is no change. */
    private Change item(String tenantId, String actor, String source, Kind kind, String key, String header, String body, boolean replace) {
        return change(tenantId, actor, source, c -> {
            Optional<String[]> current = Jdbc.one(c, "SELECT header, body FROM DLT_REGISTRATION_ITEM WHERE tenant_id=? AND kind=? AND item_key=?",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, tenantId, kind.name(), key);
            Integer vars = body == null ? null : variables(body);
            if (current.isPresent()) {
                if (!replace || (Objects.equals(current.get()[0], header) && Objects.equals(current.get()[1], body))) {
                    return null;
                }
                Jdbc.update(c, "UPDATE DLT_REGISTRATION_ITEM SET header=?, body=?, variable_count=?, registered_by=?, updated_at="
                        + dialect.now() + " WHERE tenant_id=? AND kind=? AND item_key=?", header, body, vars, actor, tenantId, kind.name(), key);
            } else {
                Jdbc.update(c, "INSERT INTO DLT_REGISTRATION_ITEM(tenant_id, kind, item_key, header, body, variable_count, registered_by,"
                        + " registered_at, updated_at) VALUES (?,?,?,?,?,?,?," + dialect.now() + "," + dialect.now() + ")",
                        tenantId, kind.name(), key, header, body, vars, actor);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            if (kind == Kind.TEMPLATE) {
                details.put("templateId", key);
                details.put("header", header);
                details.put("body", body);
                details.put("bodySha256", Hashes.hex(Hashes.sha256(body)));
                details.put("variableCount", vars);
            } else if (kind == Kind.HEADER) {
                details.put("header", key);
            } else {
                details.put("urlPrefix", key);
            }
            return event(kind.name(), key, current.isPresent(), details);
        });
    }

    /**
     * The tenant's stored principal-entity id, or null when none is registered yet. {@link #change} creates the
     * registration row with {@code entity_id} NULL, so NULL is a normal state here ("row, no entity yet"), and
     * {@link Jdbc#one} refuses a row whose mapped value is null; the column is therefore mapped to an {@link Optional}.
     */
    private static String storedEntityId(Connection c, String tenantId) {
        return Jdbc.one(c, "SELECT entity_id FROM DLT_REGISTRATION WHERE tenant_id=?",
                rs -> Optional.ofNullable(rs.getString(1)), tenantId).flatMap(e -> e).orElse(null);
    }

    private record Event(String registration, String key, boolean replaced, Map<String, Object> details) {
    }

    private static Event event(String registration, String key, boolean replaced, Map<String, Object> details) {
        return new Event(registration, key, replaced, details);
    }

    /**
     * One change in one transaction under the tenant's registration lock: the work returns the event to write, or null
     * when nothing changed. The revision moves and {@code DLT_TEMPLATE_REGISTERED} is written in the same transaction.
     */
    private Change change(String tenantId, String actor, String source, java.util.function.Function<Connection, Event> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("a DLT registration change needs an actor");
        }
        Change done = tx.inTransaction(c -> {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO DLT_REGISTRATION(tenant_id, entity_id, revision, updated_by, updated_at)"
                    + " VALUES (?,NULL,0,NULL," + dialect.now() + ")", tenantId);
            long revision = Jdbc.one(c, dialect.lockingSelect("revision", "DLT_REGISTRATION", "tenant_id=?"), rs -> rs.getLong(1), tenantId)
                    .orElseThrow();
            Event e = work.apply(c);
            if (e == null) {
                return new Change(false, revision);
            }
            long next = revision + 1;
            Jdbc.update(c, "UPDATE DLT_REGISTRATION SET revision=?, updated_by=?, updated_at=" + dialect.now() + " WHERE tenant_id=?",
                    next, actor, tenantId);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("registration", e.registration());
            p.put("key", e.key());
            p.putAll(e.details());
            p.put("replaced", e.replaced());
            p.put("source", source);
            p.put("revision", next);
            p.put("actor", actor);
            audit.append(c, tenantId, CHAIN, "DLT_TEMPLATE_REGISTERED", p);
            return new Change(true, next);
        });
        if (done.changed()) {
            load(tenantId);
        }
        return done;
    }

    // ------------------------------------------------------------------ reads

    public Optional<Snapshot> snapshot(String tenantId) {
        return tx.read(c -> snapshot(c, tenantId));
    }

    private Optional<Snapshot> snapshot(Connection c, String tenantId) {
        Optional<Object[]> head = Jdbc.one(c, "SELECT entity_id, revision FROM DLT_REGISTRATION WHERE tenant_id=?",
                rs -> new Object[]{rs.getString(1), rs.getLong(2)}, tenantId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<String[]> items = Jdbc.query(c, "SELECT kind, item_key, header, body FROM DLT_REGISTRATION_ITEM WHERE tenant_id=?"
                + " ORDER BY kind, item_key", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, tenantId);
        List<String> headers = items.stream().filter(i -> Kind.HEADER.name().equals(i[0])).map(i -> i[1]).toList();
        List<TemplateEntry> templates = items.stream().filter(i -> Kind.TEMPLATE.name().equals(i[0]))
                .map(i -> new TemplateEntry(i[1], i[2], i[3])).toList();
        List<String> urls = items.stream().filter(i -> Kind.URL.name().equals(i[0])).map(i -> i[1]).toList();
        return Optional.of(new Snapshot(tenantId, (String) head.get()[0], (Long) head.get()[1], headers, templates, urls));
    }

    /** Loads every tenant whose stored revision differs from the loaded one (other nodes' changes). Returns the tenants loaded. */
    public int refresh() {
        Map<String, Long> stored = new LinkedHashMap<>();
        tx.inTransaction(c -> Jdbc.query(c, "SELECT tenant_id, revision FROM DLT_REGISTRATION", rs -> {
            stored.put(rs.getString(1), rs.getLong(2));
            return null;
        }));
        int n = 0;
        for (Map.Entry<String, Long> e : stored.entrySet()) {
            if (!e.getValue().equals(loaded.get(e.getKey()))) {
                load(e.getKey());
                n++;
            }
        }
        return n;
    }

    private void load(String tenantId) {
        snapshot(tenantId).ifPresent(s -> {
            registry.replaceTenant(tenantId, t -> {
                if (s.entityId() != null) {
                    t.entityId(s.entityId());
                }
                s.headers().forEach(t::header);
                s.templates().forEach(x -> t.template(x.templateId(), x.header(), x.body()));
                s.urlPrefixes().forEach(t::whitelistUrl);
            });
            loaded.put(tenantId, s.revision());
        });
    }

    // ------------------------------------------------------------------ checks

    static void checkHeader(String header) {
        if (header == null || !HEADER.matcher(header).matches()) {
            throw new InvalidRegistration("HEADER_INVALID", "a header is 6 letters or digits, optionally with -P, -S, -T or -G");
        }
    }

    static void checkTemplate(String templateId, String header, String body) {
        if (templateId == null || !TEMPLATE_ID.matcher(templateId).matches()) {
            throw new InvalidRegistration("TEMPLATE_INVALID", "a template id is 1 to 64 letters, digits, '-' or '_'");
        }
        checkHeader(header);
        if (body == null || body.isBlank() || body.length() > MAX_BODY) {
            throw new InvalidRegistration("TEMPLATE_INVALID", "a template body has 1 to " + MAX_BODY + " characters");
        }
        if (VARIABLE_URL.matcher(body).find()) {
            throw new InvalidRegistration("TEMPLATE_INVALID", "a URL in a template must start with its fixed, whitelisted part");
        }
    }

    static void checkUrl(String prefix) {
        if (prefix == null || prefix.length() > 255 || !URL_PREFIX.matcher(prefix).matches()) {
            throw new InvalidRegistration("URL_INVALID", "a whitelisted prefix is an http(s) URL with a host");
        }
    }

    private static int variables(String body) {
        int n = 0;
        int at = 0;
        while ((at = body.indexOf(DltRegistry.VAR, at)) >= 0) {
            n++;
            at += DltRegistry.VAR.length();
        }
        return n;
    }
}
