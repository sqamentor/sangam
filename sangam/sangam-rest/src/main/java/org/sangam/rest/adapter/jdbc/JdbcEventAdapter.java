// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.checkRevision;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.invalid;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.oneOf;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * Event registry (FC-02, FC-03, FC-04; R38, R83) persisted in the versioned product tables {@code EVT_CHANNEL_DEF} and
 * {@code EVT_EVENT_DEF}, with the database inbox for de-duplication. <b>Module-disabled default</b>: when
 * sangam-flowable-ports is bound, its {@code EventRegistry} owns these tables (Kafka/JMS/AMQP channels, key and tenant
 * detectors, BPMN event-registry elements). Row format: {@code definition} = JSON
 * {@code {"format":"sangam-rest/event-registry/1","deleted":bool,"value":<Channel|EventDefinition>}}, one new
 * {@code version} per change (history kept); the highest {@code ACTIVE} version wins. Retire / restore use the
 * {@code state} / {@code retired_by} / {@code retired_at} columns (changesets 0017-01/02), as ports' registry does;
 * delete retires every version and then removes the retired rows (rows written with the older {@code deleted} flag are
 * still skipped on read). The JSON formats are not interchangeable: rows of the other format are ignored by each side.
 * Events are processed synchronously here, so there is no in-flight window to guard (the ports registry refuses
 * retire/delete with {@code 409 EVENT_IN_FLIGHT} while events are buffered).
 *
 * <p>Publication of a CloudEvent on an INBOUND channel:
 * <ol>
 *   <li>the inbox row {@code (tenant, source, id)} is inserted in the same transaction as the effect, so a duplicate
 *       delivery is reported {@code DUPLICATE} and changes nothing;</li>
 *   <li>the event definition whose key equals the event {@code type} (or the channel's only definition) decides the
 *       effect: a process start ({@code uniqueStart} on the business key, R38) or a message correlation;</li>
 *   <li>on success the inbox row is marked {@code PROCESSED} as part of the same commit;</li>
 *   <li>if a message finds no waiting subscription, the engine change is rolled back and the inbox row is kept in
 *       state {@code RECEIVED} in its own transaction: the result is {@code BUFFERED}. Re-delivering buffered
 *       messages to late subscriptions is the inbox processor of sangam-integration (R38 buffering).</li>
 * </ol>
 * Channel keys {@code channels} and {@code definitions} are reserved by the REST paths.
 */
public final class JdbcEventAdapter implements EventPort {

    private static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND");
    private static final Set<String> TRANSPORTS = Set.of("REST", "WEBHOOK", "KAFKA", "JMS", "AMQP");
    private static final Set<String> RESERVED = Set.of("channels", "definitions");
    private static final Duration INBOX_TTL = Duration.ofHours(24);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProcessPort processes;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final Inbox inbox;

    private final Defs<Channel> channels;
    private final Defs<EventDefinition> definitions;

    public JdbcEventAdapter(ProcessPort processes, TxSupport tx, AuditPort audit, RestProperties props) {
        this.processes = processes;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.inbox = new Inbox(tx.dialect(), tx.newTransactions(), props.getNode(), Duration.ofSeconds(30),
                Duration.ofSeconds(10), null);
        this.channels = new Defs<>("EVT_CHANNEL_DEF", "channel_key", Channel.class);
        this.definitions = new Defs<>("EVT_EVENT_DEF", "event_key", EventDefinition.class);
    }

    // ------------------------------------------------------------------ versioned definition rows

    /** Format marker of the rows this adapter writes (the {@code definition} JSON). */
    static final String ROW_FORMAT = "sangam-rest/event-registry/1";

    /**
     * Latest-version view of one versioned definition table. Each change inserts version n+1 (the key's history is
     * kept); a delete inserts a version with {@code "deleted": true}; reads take the highest version of a key and skip
     * deleted ones. Rows written by another format (sangam-flowable-ports' {@code EventRegistry}) are ignored.
     */
    final class Defs<V> {
        private final String table;
        private final String keyCol;
        private final Class<V> type;

        Defs(String table, String keyCol, Class<V> type) {
            this.table = table;
            this.keyCol = keyCol;
            this.type = type;
        }

        View in(String tenant) {
            return new View(tenant);
        }

        final class View {
            private final String tenant;

            View(String tenant) {
                this.tenant = tenant;
            }

            /** The current definition: the highest ACTIVE version (retired versions are skipped). */
            V get(String key) {
                if (key == null) {
                    return null;
                }
                return tx.withConnection(c -> Jdbc.one(c, "SELECT definition FROM " + table + " WHERE tenant_id=? AND "
                                + keyCol + "=? AND state='ACTIVE' AND version=(SELECT MAX(version) FROM " + table
                                + " WHERE tenant_id=? AND " + keyCol + "=? AND state='ACTIVE')", rs -> rs.getString(1),
                        tenant, key, tenant, key))
                        .map(this::decode).orElse(null);
            }

            /** The highest version whatever its state (to report what was retired). */
            V latestAnyState(String key) {
                return tx.withConnection(c -> Jdbc.one(c, "SELECT definition FROM " + table + " WHERE tenant_id=? AND "
                                + keyCol + "=? AND version=(SELECT MAX(version) FROM " + table + " WHERE tenant_id=? AND "
                                + keyCol + "=?)", rs -> rs.getString(1), tenant, key, tenant, key))
                        .map(this::decode).orElse(null);
            }

            boolean containsKey(String key) {
                return get(key) != null;
            }

            /** Marks every ACTIVE version RETIRED (EVT_* state, retired_by, retired_at; changeset 0017-01/02). */
            int retire(String key, String actor) {
                return tx.withConnection(c -> Jdbc.update(c, "UPDATE " + table + " SET state='RETIRED', retired_by=?,"
                                + " retired_at=" + tx.dialect().now() + " WHERE tenant_id=? AND " + keyCol + "=? AND state='ACTIVE'",
                        actor, tenant, key));
            }

            /** The newest RETIRED version, or null. */
            Integer newestRetired(String key) {
                return tx.withConnection(c -> Jdbc.one(c, "SELECT MAX(version) FROM " + table + " WHERE tenant_id=? AND "
                        + keyCol + "=? AND state='RETIRED'", rs -> {
                            int v = rs.getInt(1);
                            return rs.wasNull() ? null : v;
                        }, tenant, key).orElse(null));
            }

            void restore(String key, int version) {
                tx.withConnection(c -> Jdbc.update(c, "UPDATE " + table + " SET state='ACTIVE', retired_by=NULL,"
                        + " retired_at=NULL WHERE tenant_id=? AND " + keyCol + "=? AND version=?", tenant, key, version));
            }

            /** Removes the RETIRED versions of a key (delete = retire, then purge). */
            int purgeRetired(String key) {
                return tx.withConnection(c -> Jdbc.update(c, "DELETE FROM " + table + " WHERE tenant_id=? AND " + keyCol
                        + "=? AND state='RETIRED'", tenant, key));
            }

            List<V> values() {
                List<String[]> rows = tx.withConnection(c -> Jdbc.query(c, "SELECT " + keyCol + ", version, definition FROM "
                                + table + " WHERE tenant_id=? AND state='ACTIVE' ORDER BY " + keyCol + ", version DESC",
                        rs -> new String[]{rs.getString(1), rs.getString(3)}, tenant));
                Map<String, V> latest = new LinkedHashMap<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String[] r : rows) {
                    if (seen.add(r[0])) {
                        V v = decode(r[1]);
                        if (v != null) {
                            latest.put(r[0], v);
                        }
                    }
                }
                return new ArrayList<>(latest.values());
            }

            void put(String key, V value) {
                write(key, value, false);
            }

            /** Delete: every version of the key is retired and then removed. */
            void remove(String key, String actor) {
                retire(key, actor);
                purgeRetired(key);
            }

            private void write(String key, V value, boolean deleted) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("format", ROW_FORMAT);
                doc.put("deleted", deleted);
                doc.put("value", value);
                String json;
                try {
                    json = JSON.writeValueAsString(doc);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw invalid("definition", "definition is not serialisable: " + e.getOriginalMessage());
                }
                String sha = org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(json));
                tx.withConnection(c -> {
                    int version = Jdbc.one(c, "SELECT MAX(version) FROM " + table + " WHERE tenant_id=? AND " + keyCol
                            + "=?", rs -> rs.getInt(1), tenant, key).orElse(0) + 1;
                    return Jdbc.update(c, "INSERT INTO " + table + "(tenant_id, " + keyCol + ", version, definition, sha256,"
                                    + " state, created_at) VALUES (?,?,?,?,?,'ACTIVE'," + tx.dialect().now() + ")",
                            tenant, key, version, json, sha);
                });
            }

            private V decode(String json) {
                try {
                    JsonNode n = JSON.readTree(json);
                    if (!ROW_FORMAT.equals(n.path("format").asText()) || n.path("deleted").asBoolean(false)) {
                        return null;
                    }
                    return JSON.treeToValue(n.get("value"), type);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    return null;
                }
            }
        }
    }

    // ------------------------------------------------------------------ channels

    @Override
    public Page<Channel> channels(RequestContext ctx, Page.PageRequest page) {
        List<Channel> all = new ArrayList<>(channels.in(JdbcSupport.tenant(ctx)).values());
        all.sort(Comparator.comparing(Channel::key));
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Channel channel(RequestContext ctx, String key) {
        Channel c = channels.in(JdbcSupport.tenant(ctx)).get(key);
        if (c == null) {
            throw ApiProblem.notFound("Channel", key);
        }
        return c;
    }

    @Override
    public synchronized Channel createChannel(RequestContext ctx, Channel in) {
        return tx.inTx(() -> {
            String t = JdbcSupport.tenant(ctx);
            validateChannel(t, in);
            if (channels.in(t).containsKey(in.key())) {
                throw ApiProblem.conflict("ALREADY_EXISTS", "Channel " + in.key() + " exists");
            }
            Channel c = normalise(t, in, 1);
            channels.in(t).put(c.key(), c);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_CHANNEL_CHANGED",
                    payload("change", "CREATED", "channel", c.key(), "direction", c.direction(), "transport", c.transport(),
                            "actor", ctx.actor()));
            return c;
        });
    }

    @Override
    public synchronized Channel updateChannel(RequestContext ctx, String key, Channel in) {
        return tx.inTx(() -> {
            String t = JdbcSupport.tenant(ctx);
            Channel cur = channel(ctx, key);
            checkRevision(in.revision(), cur.revision(), "Channel", key);
            Channel merged = new Channel(key, t, in.name(), in.direction(), in.transport(), in.destination(),
                    in.eventDefinitionKeys(), in.keyDetector(), in.tenantDetector(), in.dedupWindowSeconds(), in.config(),
                    in.active(), in.revision());
            validateChannel(t, merged);
            Channel c = normalise(t, merged, cur.revision() + 1);
            channels.in(t).put(key, c);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_CHANNEL_CHANGED",
                    payload("change", "UPDATED", "channel", key, "active", c.active(), "actor", ctx.actor()));
            return c;
        });
    }

    @Override
    public synchronized void deleteChannel(RequestContext ctx, String key, String reason) {
        tx.runInTx(() -> {
            if (channels.in(JdbcSupport.tenant(ctx)).latestAnyState(key) == null) {
                throw ApiProblem.notFound("Channel", key);
            }
            channels.in(JdbcSupport.tenant(ctx)).remove(key, ctx.principalId());
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_CHANNEL_CHANGED",
                    payload("change", "DELETED", "channel", key, "reason", reason, "actor", ctx.actor()));
        });
    }

    @Override
    public synchronized Channel retireChannel(RequestContext ctx, String key, String reason) {
        required(reason, "reason");
        return tx.inTx(() -> {
            Channel cur = channel(ctx, key);
            channels.in(JdbcSupport.tenant(ctx)).retire(key, ctx.principalId());
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_CHANNEL_RETIRED",
                    payload("channel", key, "reason", reason, "actor", ctx.actor()));
            return new Channel(cur.key(), cur.tenantId(), cur.name(), cur.direction(), cur.transport(), cur.destination(),
                    cur.eventDefinitionKeys(), cur.keyDetector(), cur.tenantDetector(), cur.dedupWindowSeconds(),
                    cur.config(), false, cur.revision());
        });
    }

    @Override
    public synchronized Channel restoreChannel(RequestContext ctx, String key, String reason) {
        required(reason, "reason");
        return tx.inTx(() -> {
            Defs<Channel>.View view = channels.in(JdbcSupport.tenant(ctx));
            Integer version = view.newestRetired(key);
            if (version == null) {
                if (view.latestAnyState(key) == null) {
                    throw ApiProblem.notFound("Channel", key);
                }
                throw ApiProblem.conflict("NOT_RETIRED", "Channel " + key + " has no retired version to restore");
            }
            view.restore(key, version);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_CHANNEL_RESTORED",
                    payload("channel", key, "version", version, "reason", reason, "actor", ctx.actor()));
            return channel(ctx, key);
        });
    }

    @Override
    public synchronized EventDefinition retireDefinition(RequestContext ctx, String key, String reason) {
        required(reason, "reason");
        return tx.inTx(() -> {
            EventDefinition cur = definition(ctx, key);
            definitions.in(JdbcSupport.tenant(ctx)).retire(key, ctx.principalId());
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_DEFINITION_RETIRED",
                    payload("eventDefinition", key, "reason", reason, "actor", ctx.actor()));
            return cur;
        });
    }

    @Override
    public synchronized EventDefinition restoreDefinition(RequestContext ctx, String key, String reason) {
        required(reason, "reason");
        return tx.inTx(() -> {
            Defs<EventDefinition>.View view = definitions.in(JdbcSupport.tenant(ctx));
            Integer version = view.newestRetired(key);
            if (version == null) {
                if (view.latestAnyState(key) == null) {
                    throw ApiProblem.notFound("EventDefinition", key);
                }
                throw ApiProblem.conflict("NOT_RETIRED", "Event definition " + key + " has no retired version to restore");
            }
            view.restore(key, version);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_DEFINITION_RESTORED",
                    payload("eventDefinition", key, "version", version, "reason", reason, "actor", ctx.actor()));
            return definition(ctx, key);
        });
    }

    private void validateChannel(String t, Channel in) {
        required(in.key(), "key");
        if (RESERVED.contains(in.key()) || !in.key().matches("[A-Za-z0-9._-]{1,64}")) {
            throw invalid("key", "channel key must be 1..64 of [A-Za-z0-9._-] and not 'channels' or 'definitions'");
        }
        oneOf(in.direction(), "direction", DIRECTIONS);
        oneOf(in.transport(), "transport", TRANSPORTS);
        if (in.dedupWindowSeconds() != null && in.dedupWindowSeconds() < 0) {
            throw invalid("dedupWindowSeconds", "dedupWindowSeconds must be >= 0");
        }
        if (in.eventDefinitionKeys() != null) {
            for (String k : in.eventDefinitionKeys()) {
                if (!definitions.in(t).containsKey(k)) {
                    throw invalid("eventDefinitionKeys", "event definition " + k + " does not exist");
                }
            }
        }
    }

    private static Channel normalise(String t, Channel in, long revision) {
        return new Channel(in.key(), t, in.name() == null ? in.key() : in.name(), in.direction(), in.transport(),
                in.destination(), in.eventDefinitionKeys() == null ? List.of() : List.copyOf(in.eventDefinitionKeys()),
                in.keyDetector(), in.tenantDetector(), in.dedupWindowSeconds(), in.config(), in.active(), revision);
    }

    // ------------------------------------------------------------------ definitions

    @Override
    public Page<EventDefinition> definitions(RequestContext ctx, Page.PageRequest page) {
        List<EventDefinition> all = new ArrayList<>(definitions.in(JdbcSupport.tenant(ctx)).values());
        all.sort(Comparator.comparing(EventDefinition::key));
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public EventDefinition definition(RequestContext ctx, String key) {
        EventDefinition d = definitions.in(JdbcSupport.tenant(ctx)).get(key);
        if (d == null) {
            throw ApiProblem.notFound("EventDefinition", key);
        }
        return d;
    }

    @Override
    public synchronized EventDefinition createDefinition(RequestContext ctx, EventDefinition in) {
        return tx.inTx(() -> {
            String t = JdbcSupport.tenant(ctx);
            required(in.key(), "key");
            if (RESERVED.contains(in.key())) {
                throw invalid("key", "event definition key is reserved");
            }
            boolean start = in.startProcessKey() != null && !in.startProcessKey().isBlank();
            boolean message = in.messageName() != null && !in.messageName().isBlank();
            if (start == message) {
                throw invalid("startProcessKey", "exactly one of startProcessKey and messageName is required");
            }
            List<String> params = in.correlationParameters() == null ? List.of() : List.copyOf(in.correlationParameters());
            for (String p : params) {
                if (p == null || !p.startsWith("/")) {
                    throw invalid("correlationParameters", "correlation parameters are JSON pointers starting with '/'");
                }
            }
            if (in.businessKeyPointer() != null && !in.businessKeyPointer().startsWith("/")) {
                throw invalid("businessKeyPointer", "businessKeyPointer is a JSON pointer starting with '/'");
            }
            if (in.uniqueStart() && (!start || in.businessKeyPointer() == null)) {
                throw invalid("uniqueStart", "uniqueStart needs startProcessKey and businessKeyPointer");
            }
            EventDefinition prev = definitions.in(t).get(in.key());
            int version = prev == null ? 1 : prev.version() + 1;
            EventDefinition d = new EventDefinition(in.key(), t, in.name() == null ? in.key() : in.name(), version,
                    in.payloadSchema(), params, in.businessKeyPointer(), in.startProcessKey(), in.messageName(),
                    in.uniqueStart(), prev == null ? 1 : prev.revision() + 1);
            definitions.in(t).put(d.key(), d);
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_DEFINITION_CHANGED",
                    payload("change", prev == null ? "CREATED" : "NEW_VERSION", "eventDefinition", d.key(),
                            "version", version, "actor", ctx.actor()));
            return d;
        });
    }

    @Override
    public synchronized void deleteDefinition(RequestContext ctx, String key, String reason) {
        tx.runInTx(() -> {
            String t = JdbcSupport.tenant(ctx);
            definition(ctx, key);
            boolean used = channels.in(t).values().stream()
                    .anyMatch(c -> c.eventDefinitionKeys().contains(key));
            if (used) {
                throw ApiProblem.conflict("IN_USE", "Event definition " + key + " is bound to a channel");
            }
            definitions.in(t).remove(key, ctx.principalId());
            audit.record(ctx, JdbcSupport.securityChain(ctx), "EVENT_DEFINITION_CHANGED",
                    payload("change", "DELETED", "eventDefinition", key, "reason", reason, "actor", ctx.actor()));
        });
    }

    // ------------------------------------------------------------------ publish

    @Override
    public PublishResult publish(RequestContext ctx, String channelKey, CloudEvent event) {
        String t = JdbcSupport.tenant(ctx);
        Channel channel = channel(ctx, channelKey);
        if (!channel.active()) {
            throw ApiProblem.unprocessable("CHANNEL_INACTIVE", "Channel " + channelKey + " is not active");
        }
        if (!"INBOUND".equals(channel.direction())) {
            throw ApiProblem.unprocessable("CHANNEL_NOT_INBOUND", "Channel " + channelKey + " is not an inbound channel");
        }
        if (event == null) {
            throw ApiProblem.badRequest("INVALID_CLOUDEVENT", "a CloudEvent body is required");
        }
        required(event.id(), "id");
        required(event.source(), "source");
        required(event.type(), "type");
        if (!"1.0".equals(event.specversion())) {
            throw invalid("specversion", "specversion must be \"1.0\"");
        }
        EventDefinition def = chooseDefinition(t, channel, event.type());
        JsonNode data = event.data() == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                : JSON.valueToTree(event.data());
        String businessKey = def.businessKeyPointer() == null ? null : text(data.at(def.businessKeyPointer()));
        Map<String, Object> correlation = new LinkedHashMap<>();
        for (String p : def.correlationParameters()) {
            JsonNode n = data.at(p);
            if (!n.isMissingNode() && !n.isNull()) {
                correlation.put(paramName(p), n.isValueNode() ? (n.isNumber() ? n.decimalValue() : n.asText())
                        : JSON.convertValue(n, Object.class));
            }
        }
        String payloadJson = CanonicalJson.write(data.isMissingNode() ? Map.of() : JSON.convertValue(data, Object.class));
        String chain = businessKey == null ? "sec:" + t : businessKey;

        try {
            // Own transaction: the inbox row is the de-duplication record of this event, and a "no subscription"
            // rollback must not poison an enclosing request transaction.
            return tx.inNewTx(() -> tx.withConnection(c -> {
                Inbox.ReceiveResult r = inbox.receive(c, t, event.source(), event.id(), businessKey, payloadJson, INBOX_TTL);
                if (r == Inbox.ReceiveResult.DUPLICATE) {
                    return new PublishResult(event.id(), "DUPLICATE", def.key(), List.of(), List.of());
                }
                PublishResult result = def.startProcessKey() != null
                        ? start(ctx, def, event, data, businessKey, correlation)
                        : correlate(ctx, def, event, data, businessKey, correlation);
                Jdbc.update(c, "UPDATE INBOX SET state='PROCESSED' WHERE tenant_id=? AND source=? AND message_id=?",
                        t, event.source(), event.id());
                audit.record(ctx, chain, "EVENT_RECEIVED",
                        payload("eventId", event.id(), "source", event.source(), "type", event.type(), "channel", channelKey,
                                "eventDefinition", def.key(), "status", result.status(), "businessKey", businessKey,
                                "started", result.startedInstanceIds(), "correlated", result.correlatedInstanceIds(),
                                "actor", ctx.actor()));
                return result;
            }));
        } catch (ApiProblem p) {
            if (def.messageName() == null || (p.status() != 404 && p.status() != 422)) {
                throw p;
            }
            // No waiting subscription: engine change rolled back; keep the message RECEIVED for the inbox processor.
            return tx.inNewTx(() -> tx.withConnection(c -> {
                Inbox.ReceiveResult r = inbox.receive(c, t, event.source(), event.id(), businessKey, payloadJson, INBOX_TTL);
                if (r == Inbox.ReceiveResult.DUPLICATE) {
                    return new PublishResult(event.id(), "DUPLICATE", def.key(), List.of(), List.of());
                }
                audit.record(ctx, chain, "EVENT_RECEIVED",
                        payload("eventId", event.id(), "source", event.source(), "type", event.type(), "channel", channelKey,
                                "eventDefinition", def.key(), "status", "BUFFERED", "businessKey", businessKey,
                                "reason", p.code(), "actor", ctx.actor()));
                return new PublishResult(event.id(), "BUFFERED", def.key(), List.of(), List.of());
            }));
        }
    }

    private PublishResult start(RequestContext ctx, EventDefinition def, CloudEvent event, JsonNode data,
                                String businessKey, Map<String, Object> correlation) {
        if (def.uniqueStart() && businessKey != null) {
            Page<ProcessPort.ProcessInstance> running = processes.list(ctx,
                    new ProcessPort.ProcessFilter(def.startProcessKey(), businessKey, "ACTIVE", null, null, null, null),
                    new Page.PageRequest(0, 1));
            if (!running.items().isEmpty()) {
                return new PublishResult(event.id(), "ACCEPTED", def.key(), List.of(),
                        List.of(running.items().get(0).id()));
            }
        }
        ProcessPort.ProcessInstance pi = processes.start(ctx, new ProcessPort.StartProcessRequest(def.startProcessKey(),
                businessKey, variables(event, data, correlation), null, null, null, null));
        return new PublishResult(event.id(), "ACCEPTED", def.key(), List.of(pi.id()), List.of());
    }

    private PublishResult correlate(RequestContext ctx, EventDefinition def, CloudEvent event, JsonNode data,
                                    String businessKey, Map<String, Object> correlation) {
        ProcessPort.MessageResult r = processes.correlate(ctx, new ProcessPort.MessageRequest(def.messageName(),
                businessKey, null, correlation.isEmpty() ? null : correlation, variables(event, data, correlation), true));
        if (r.processInstanceIds() == null || r.processInstanceIds().isEmpty()) {
            throw ApiProblem.unprocessable("NO_SUBSCRIPTION", "no instance waits for message " + def.messageName());
        }
        return new PublishResult(event.id(), "ACCEPTED", def.key(), List.of(), List.copyOf(r.processInstanceIds()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variables(CloudEvent event, JsonNode data, Map<String, Object> correlation) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (data.isObject()) {
            vars.putAll(TypedVariables.fromJson(JSON.convertValue(data, Map.class)));
        }
        String eventJson = CanonicalJson.write(data.isMissingNode() ? Map.of() : JSON.convertValue(data, Object.class));
        if (eventJson.length() < 4000) {
            vars.put("event", eventJson);
        }
        vars.putAll(correlation);
        vars.put("sangamEventId", event.id());
        vars.put("sangamEventType", event.type());
        vars.put("sangamEventSource", event.source());
        return vars;
    }

    private EventDefinition chooseDefinition(String t, Channel channel, String type) {
        List<String> keys = channel.eventDefinitionKeys();
        Defs<EventDefinition>.View defs = definitions.in(t);
        String key = keys.contains(type) ? type : keys.size() == 1 ? keys.get(0) : null;
        EventDefinition d = key == null ? null : defs.get(key);
        if (d == null) {
            throw ApiProblem.unprocessable("UNKNOWN_EVENT_TYPE",
                    "Channel " + channel.key() + " has no event definition for type " + type);
        }
        return d;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String paramName(String pointer) {
        String last = pointer.substring(pointer.lastIndexOf('/') + 1);
        return last.replace("~1", "/").replace("~0", "~");
    }
}
