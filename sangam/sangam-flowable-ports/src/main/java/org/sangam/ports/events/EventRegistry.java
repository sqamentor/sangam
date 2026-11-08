// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.events.model.EventDefinition;
import org.sangam.ports.support.Json;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned repository of event and channel definitions (FLOWABLE-INTO-OPERATON FC-02/FLOWABLE-INTO-OPERATON FC-03; tables {@code EVT_EVENT_DEF},
 * {@code EVT_CHANNEL_DEF}). Deploying identical content again is a no-op (same SHA-256); changed content becomes the
 * next version. Lookups return the latest <em>active</em> version and are cached briefly per node.
 *
 * <p>Lifecycle per version: {@code ACTIVE -> RETIRED -> (deleted)}; {@code RETIRED -> ACTIVE} by restore. Retire and
 * delete are refused while events of the key are in flight (inbox rows not yet processed, outbox deliveries not yet
 * done); delete is refused for an active version. Version numbers are never reused.
 */
public class EventRegistry {

    /** A deployed channel with its tenant binding and version. */
    public record DeployedChannel(String tenantId, int version, ChannelDefinition definition) {
    }

    public record DeployedEvent(String tenantId, int version, EventDefinition definition) {
    }

    private record Cached<T>(T value, long loadedAtNanos) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final long cacheTtlNanos;
    private final Map<String, Cached<Optional<DeployedEvent>>> eventCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<Optional<DeployedChannel>>> channelCache = new ConcurrentHashMap<>();

    public EventRegistry(Dialect dialect, Transactions tx, Duration cacheTtl) {
        this.dialect = dialect;
        this.tx = tx;
        this.cacheTtlNanos = cacheTtl.toNanos();
    }

    public DeployedEvent deployEvent(String tenantId, String json, String appDeploymentId) {
        String tenant = tenantId == null ? "" : tenantId;
        EventDefinition def = Json.read(json, EventDefinition.class);
        validate(def);
        String canonical = Json.write(def);
        int v = tx.inTransaction(c -> deploy(c, "EVT_EVENT_DEF", "event_key", tenant, def.key(), canonical, appDeploymentId));
        eventCache.remove(tenant + "|" + def.key());
        return new DeployedEvent(tenant, v, def);
    }

    public DeployedChannel deployChannel(String tenantId, String json, String appDeploymentId) {
        String tenant = tenantId == null ? "" : tenantId;
        ChannelDefinition def = Json.read(json, ChannelDefinition.class);
        validate(def);
        String canonical = Json.write(def);
        int v = tx.inTransaction(c -> deploy(c, "EVT_CHANNEL_DEF", "channel_key", tenant, def.key(), canonical, appDeploymentId));
        channelCache.remove(tenant + "|" + def.key());
        return new DeployedChannel(tenant, v, def);
    }

    public Optional<DeployedEvent> event(String tenantId, String key) {
        String tenant = tenantId == null ? "" : tenantId;
        return cached(eventCache, tenant + "|" + key, () -> tx.inTransaction(c -> latest(c, "EVT_EVENT_DEF", "event_key", tenant, key)
                .map(r -> new DeployedEvent(tenant, r.version, Json.read(r.json, EventDefinition.class)))));
    }

    public Optional<DeployedChannel> channel(String tenantId, String key) {
        String tenant = tenantId == null ? "" : tenantId;
        return cached(channelCache, tenant + "|" + key, () -> tx.inTransaction(c -> latest(c, "EVT_CHANNEL_DEF", "channel_key", tenant, key)
                .map(r -> new DeployedChannel(tenant, r.version, Json.read(r.json, ChannelDefinition.class)))));
    }

    /** Latest version of every channel in every tenant (adapter start-up). */
    public List<DeployedChannel> allChannels() {
        return tx.inTransaction(c -> Jdbc.query(c,
                "SELECT d.tenant_id, d.version, d.definition FROM EVT_CHANNEL_DEF d WHERE d.state = 'ACTIVE' AND d.version = "
                        + "(SELECT MAX(x.version) FROM EVT_CHANNEL_DEF x WHERE x.tenant_id = d.tenant_id AND x.channel_key = d.channel_key"
                        + " AND x.state = 'ACTIVE')",
                rs -> new DeployedChannel(rs.getString(1), rs.getInt(2), Json.read(rs.getString(3), ChannelDefinition.class))));
    }

    public List<DeployedEvent> events(String tenantId) {
        String tenant = tenantId == null ? "" : tenantId;
        return tx.inTransaction(c -> Jdbc.query(c,
                "SELECT d.version, d.definition FROM EVT_EVENT_DEF d WHERE d.tenant_id = ? AND d.state = 'ACTIVE' AND d.version = "
                        + "(SELECT MAX(x.version) FROM EVT_EVENT_DEF x WHERE x.tenant_id = d.tenant_id AND x.event_key = d.event_key"
                        + " AND x.state = 'ACTIVE')",
                rs -> new DeployedEvent(tenant, rs.getInt(1), Json.read(rs.getString(2), EventDefinition.class)), tenant));
    }

    // ------------------------------------------------------------------ versions, retire, delete

    /** One stored version of an event or channel definition. */
    public record VersionInfo(String tenantId, String key, int version, String state, String sha256, String appDeploymentId,
                              Instant createdAt, String retiredBy, Instant retiredAt) {
    }

    /** Events still on their way; retire and delete refuse while any exist. */
    public record InFlight(long inbox, long outbox) {
        public boolean any() {
            return inbox > 0 || outbox > 0;
        }
    }

    /** Raised when a definition is retired or deleted while events that use it are still buffered or undelivered (REST: 409). */
    public static final class InFlightException extends IllegalStateException {
        private final transient InFlight inFlight;

        public InFlightException(String what, InFlight f) {
            super(what + " has events in flight (inbox " + f.inbox() + ", outbox " + f.outbox() + "); retry after they settle");
            this.inFlight = f;
        }

        public InFlight inFlight() {
            return inFlight;
        }
    }

    public List<VersionInfo> eventVersions(String tenantId, String key) {
        return versions("EVT_EVENT_DEF", "event_key", t(tenantId), key);
    }

    public List<VersionInfo> channelVersions(String tenantId, String key) {
        return versions("EVT_CHANNEL_DEF", "channel_key", t(tenantId), key);
    }

    /** Inbox rows not yet processed for this event key, and outbox deliveries not yet done for its event type. */
    public InFlight eventInFlight(String tenantId, String key) {
        String tenant = t(tenantId);
        return tx.inTransaction(c -> new InFlight(
                Jdbc.one(c, "SELECT COUNT(*) FROM INBOX WHERE tenant_id = ? AND correlation_key = ? AND source LIKE 'evt:%'"
                        + " AND state IN ('RECEIVED','CLAIMED','BUFFERED')", rs -> rs.getLong(1), tenant, key).orElse(0L),
                Jdbc.one(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY d JOIN OUTBOX o ON o.id = d.outbox_id WHERE o.tenant_id = ?"
                        + " AND o.event_type = ? AND d.state IN ('PENDING','CLAIMED','PARKED')", rs -> rs.getLong(1),
                        tenant, OutboundEventService.EVENT_TYPE_PREFIX + key).orElse(0L)));
    }

    /** Inbox rows received on the channel and not yet processed, and outbox deliveries pending to it. */
    public InFlight channelInFlight(String tenantId, String key) {
        String tenant = t(tenantId);
        return tx.inTransaction(c -> new InFlight(
                Jdbc.one(c, "SELECT COUNT(*) FROM INBOX WHERE tenant_id = ? AND source = ? AND state IN ('RECEIVED','CLAIMED','BUFFERED')",
                        rs -> rs.getLong(1), tenant, InboundEventProcessor.SOURCE_PREFIX + key).orElse(0L),
                Jdbc.one(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE tenant_id = ? AND destination = ?"
                        + " AND state IN ('PENDING','CLAIMED','PARKED')", rs -> rs.getLong(1),
                        tenant, OutboundEventService.DESTINATION_PREFIX + key).orElse(0L)));
    }

    /**
     * Retires one version ({@code version} null = every active version) of an event definition. A retired version is
     * no longer resolved; an older active version, if any, becomes current again. Refused with
     * {@link InFlightException} while events of this key are buffered or undelivered.
     */
    public int retireEvent(String tenantId, String key, Integer version, String actor) {
        InFlight f = eventInFlight(tenantId, key);
        if (f.any()) {
            throw new InFlightException("event " + key, f);
        }
        int n = retire("EVT_EVENT_DEF", "event_key", t(tenantId), key, version, actor);
        eventCache.remove(t(tenantId) + "|" + key);
        return n;
    }

    public int retireChannel(String tenantId, String key, Integer version, String actor) {
        InFlight f = channelInFlight(tenantId, key);
        if (f.any()) {
            throw new InFlightException("channel " + key, f);
        }
        int n = retire("EVT_CHANNEL_DEF", "channel_key", t(tenantId), key, version, actor);
        channelCache.remove(t(tenantId) + "|" + key);
        return n;
    }

    /** Re-activates a retired version (undo of a retire). */
    public void restoreEvent(String tenantId, String key, int version) {
        restore("EVT_EVENT_DEF", "event_key", t(tenantId), key, version);
        eventCache.remove(t(tenantId) + "|" + key);
    }

    public void restoreChannel(String tenantId, String key, int version) {
        restore("EVT_CHANNEL_DEF", "channel_key", t(tenantId), key, version);
        channelCache.remove(t(tenantId) + "|" + key);
    }

    /**
     * Deletes one version ({@code version} null = all retired versions). Only retired versions can be deleted (retire
     * first: an active definition may be referenced by running models), never while events are in flight. Returns the
     * number of versions removed.
     */
    public int deleteEvent(String tenantId, String key, Integer version) {
        InFlight f = eventInFlight(tenantId, key);
        if (f.any()) {
            throw new InFlightException("event " + key, f);
        }
        int n = delete("EVT_EVENT_DEF", "event_key", t(tenantId), key, version);
        eventCache.remove(t(tenantId) + "|" + key);
        return n;
    }

    public int deleteChannel(String tenantId, String key, Integer version) {
        InFlight f = channelInFlight(tenantId, key);
        if (f.any()) {
            throw new InFlightException("channel " + key, f);
        }
        int n = delete("EVT_CHANNEL_DEF", "channel_key", t(tenantId), key, version);
        channelCache.remove(t(tenantId) + "|" + key);
        return n;
    }

    private List<VersionInfo> versions(String table, String keyCol, String tenant, String key) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT version, state, sha256, app_deployment_id, created_at, retired_by, retired_at FROM "
                        + table + " WHERE tenant_id = ? AND " + keyCol + " = ? ORDER BY version DESC",
                rs -> new VersionInfo(tenant, key, rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Jdbc.instant(rs, "created_at"), rs.getString(6), Jdbc.instant(rs, "retired_at")), tenant, key));
    }

    private int retire(String table, String keyCol, String tenant, String key, Integer version, String actor) {
        return tx.inTransaction(c -> {
            String where = " WHERE tenant_id = ? AND " + keyCol + " = ? AND state = 'ACTIVE'" + (version == null ? "" : " AND version = ?");
            Object[] p = version == null ? new Object[]{actor, tenant, key} : new Object[]{actor, tenant, key, version};
            int n = Jdbc.update(c, "UPDATE " + table + " SET state = 'RETIRED', retired_by = ?, retired_at = " + dialect.now() + where, p);
            if (n == 0) {
                throw new IllegalArgumentException(key + (version == null ? "" : " v" + version) + " has no active version in tenant " + tenant);
            }
            return n;
        });
    }

    private void restore(String table, String keyCol, String tenant, String key, int version) {
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE " + table + " SET state = 'ACTIVE', retired_by = NULL, retired_at = NULL WHERE tenant_id = ? AND "
                    + keyCol + " = ? AND version = ? AND state = 'RETIRED'", tenant, key, version);
            if (n == 0) {
                throw new IllegalArgumentException(key + " v" + version + " is not retired");
            }
            return null;
        });
    }

    private int delete(String table, String keyCol, String tenant, String key, Integer version) {
        return tx.inTransaction(c -> {
            String active = "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND " + keyCol + " = ? AND state = 'ACTIVE'"
                    + (version == null ? "" : " AND version = ?");
            long a = version == null ? Jdbc.one(c, active, rs -> rs.getLong(1), tenant, key).orElse(0L)
                    : Jdbc.one(c, active, rs -> rs.getLong(1), tenant, key, version).orElse(0L);
            if (a > 0) {
                throw new IllegalStateException(key + (version == null ? "" : " v" + version) + " is active; retire it before deleting");
            }
            return version == null
                    ? Jdbc.update(c, "DELETE FROM " + table + " WHERE tenant_id = ? AND " + keyCol + " = ? AND state = 'RETIRED'", tenant, key)
                    : Jdbc.update(c, "DELETE FROM " + table + " WHERE tenant_id = ? AND " + keyCol + " = ? AND version = ? AND state = 'RETIRED'",
                    tenant, key, version);
        });
    }

    private static String t(String tenant) {
        return tenant == null ? "" : tenant;
    }


    public void invalidate() {
        eventCache.clear();
        channelCache.clear();
    }

    // ------------------------------------------------------------------ internals

    private record Row(int version, String json, String sha) {
    }

    private int deploy(Connection c, String table, String keyCol, String tenant, String key, String json, String appDeploymentId) {
        String sha = Hashes.hex(Hashes.sha256(json.getBytes(StandardCharsets.UTF_8)));
        Optional<Row> latest = latest(c, table, keyCol, tenant, key);
        if (latest.isPresent() && latest.get().sha.equals(sha)) {
            return latest.get().version;
        }
        int next = Jdbc.one(c, "SELECT MAX(version) FROM " + table + " WHERE tenant_id = ? AND " + keyCol + " = ?",
                rs -> rs.getInt(1), tenant, key).orElse(0) + 1;
        Jdbc.update(c, "INSERT INTO " + table + "(tenant_id, " + keyCol + ", version, definition, sha256, app_deployment_id, state, created_at)"
                + " VALUES (?,?,?,?,?,?,'ACTIVE'," + dialect.now() + ")", tenant, key, next, json, sha, appDeploymentId);
        return next;
    }

    private Optional<Row> latest(Connection c, String table, String keyCol, String tenant, String key) {
        List<Row> rows = Jdbc.query(c, "SELECT version, definition, sha256 FROM " + table + " WHERE tenant_id = ? AND " + keyCol
                + " = ? AND state = 'ACTIVE' ORDER BY version DESC", rs -> new Row(rs.getInt(1), rs.getString(2), rs.getString(3)), tenant, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private <T> Optional<T> cached(Map<String, Cached<Optional<T>>> cache, String k, java.util.function.Supplier<Optional<T>> loader) {
        Cached<Optional<T>> c = cache.get(k);
        long now = System.nanoTime();
        if (c != null && now - c.loadedAtNanos < cacheTtlNanos) {
            return c.value;
        }
        Optional<T> v = loader.get();
        cache.put(k, new Cached<>(v, now));
        return v;
    }

    /** Keys of events and channels: also BPMN message names and inbox sources, so they are bounded and plain. */
    static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    static final int MAX_FIELDS = 200;
    static final long MAX_TTL_SECONDS = 400L * 86_400;

    static void validate(EventDefinition def) {
        List<String> errors = new ArrayList<>();
        if (!KEY.matcher(def.key()).matches()) {
            errors.add("key must match " + KEY.pattern());
        }
        if (def.payload().size() + def.correlationParameters().size() > MAX_FIELDS) {
            errors.add("more than " + MAX_FIELDS + " payload/correlation fields");
        }
        if (def.ttlSeconds() != null && def.ttlSeconds() > MAX_TTL_SECONDS) {
            errors.add("ttlSeconds exceeds " + MAX_TTL_SECONDS);
        }
        for (EventDefinition.Field f : def.payload()) {
            if (f.name() == null || f.name().isBlank()) {
                errors.add("payload field without a name");
            } else if (org.sangam.ports.support.PortsProblem.isReservedVariable(f.name())) {
                errors.add("payload field '" + f.name() + "' uses a reserved variable name (FW-30)");
            }
        }
        for (EventDefinition.Field f : def.correlationParameters()) {
            if (f.name() == null || f.name().isBlank()) {
                errors.add("correlation parameter without a name");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("event " + def.key() + ": " + String.join("; ", errors));
        }
        if (def.startUniqueReference() != null && def.correlationParameters().stream().noneMatch(f -> f.name().equals(def.startUniqueReference()))) {
            errors.add("startUniqueReference '" + def.startUniqueReference() + "' is not a correlation parameter");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("event " + def.key() + ": " + String.join("; ", errors));
        }
    }

    static void validate(ChannelDefinition def) {
        if (!KEY.matcher(def.key()).matches()) {
            throw new IllegalArgumentException("channel key must match " + KEY.pattern());
        }
        if (def.payloadTenantAllowed() && def.property("allowedTenants", "").isBlank()) {
            throw new IllegalArgumentException("channel " + def.key() + ": allowPayloadTenant=true needs properties.allowedTenants"
                    + " (comma list of tenants, or * as an explicit choice) (FW-29, R38)");
        }
        String t = def.adapterType();
        if (!List.of("http", "webhook", "jms", "kafka", "rabbitmq").contains(t)) {
            throw new IllegalArgumentException("channel " + def.key() + ": unknown channelType '" + def.channelType() + "'");
        }
        if (def.direction() == ChannelDefinition.Direction.INBOUND && "http".equals(t)) {
            throw new IllegalArgumentException("channel " + def.key() + ": inbound HTTP channels use channelType 'webhook'");
        }
        if (def.direction() == ChannelDefinition.Direction.OUTBOUND && "webhook".equals(t) && def.url() == null) {
            throw new IllegalArgumentException("channel " + def.key() + ": outbound webhook needs a url");
        }
        if ("kafka".equals(t) && def.direction() == ChannelDefinition.Direction.INBOUND && def.topics().isEmpty()) {
            throw new IllegalArgumentException("channel " + def.key() + ": inbound kafka channel needs topics");
        }
    }
}
