// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.DeadLetterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production inbox (SANGAM.md VI.2.6, R38, T09, T35; Operaton D-33; Flowable FC-02/FC-04) on top of the proven
 * {@link Inbox} primitive.
 *
 * <ol>
 *   <li><b>Receive</b> ({@link #receive}): back-pressure check (503 at ≥ 80%, R40), tenant from the
 *       {@link ChannelBinding} only, insert-if-absent on (tenant, source, messageId), commit; the caller acks the broker
 *       <b>after</b> this returns. A duplicate is reported as {@link Inbox.ReceiveResult#DUPLICATE}: ack and drop.</li>
 *   <li><b>Process</b> ({@link #processOnce}): skip-locked claims with epochs; one processing transaction per message,
 *       whose last write is PROCESSED/BUFFERED (fenced). A failing handler leaves the message BUFFERED for a later retry
 *       instead of blocking the batch.</li>
 *   <li><b>Expire</b>: BUFFERED rows past their TTL become EXPIRED with an audit entry on the tenant's security chain;
 *       policy {@code reject} expires at once; expired messages are copied to the dead-letter store.</li>
 *   <li><b>Sweep</b>: expired claims return to RECEIVED.</li>
 * </ol>
 * Stored payload: an envelope {@code {eventType, principal, data}} so the handler can be chosen per event type.
 * This class is the inbox plug-in point for WS-PORTS channel adapters: they call {@link #receive} and register
 * handlers with {@link #registerHandler}.
 */
public final class InboxService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InboxService.class);

    public record Settings(String node, int batchSize, Duration claimTtl, Duration bufferRetry, Duration pollInterval,
                           Duration maintenanceInterval) {
        public static Settings defaults(String node) {
            return new Settings(node, 100, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(30));
        }
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final Inbox inbox;
    private final Settings settings;
    private final EventDefinitionPolicy.Registry policies;
    private final InboxHandler defaultHandler;
    private final Map<String, InboxHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Inbox.Handler> sourceHandlers = new ConcurrentHashMap<>();
    private final BackpressureGate gate;
    private final DeadLetterStore deadLetters;
    private final ScheduledExecutorService loop;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long lastMaintenance;

    public InboxService(Dialect dialect, Transactions tx, Settings settings, AuditChain auditOrNull,
                        EventDefinitionPolicy.Registry policies, InboxHandler defaultHandler, BackpressureGate gate,
                        DeadLetterStore deadLettersOrNull) {
        this.dialect = dialect;
        this.tx = tx;
        this.settings = settings;
        this.inbox = new Inbox(dialect, tx, settings.node(), settings.claimTtl(), settings.bufferRetry(), auditOrNull);
        this.policies = policies;
        this.defaultHandler = defaultHandler;
        this.gate = gate == null ? BackpressureGate.open() : gate;
        this.deadLetters = deadLettersOrNull;
        this.loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-inbox-" + settings.node());
            t.setDaemon(true);
            return t;
        });
    }

    public InboxService registerHandler(String eventType, InboxHandler handler) {
        handlers.put(eventType, handler);
        return this;
    }

    /**
     * Routes inbox rows whose {@code source} starts with {@code sourcePrefix} to a raw core {@link Inbox.Handler}, which
     * receives the stored message unchanged (no envelope decoding). Used for rows another module writes in its own
     * payload format, e.g. WS-PORTS' event registry ({@code evt:*} → {@code EventDispatcher}). The handler runs in this
     * service's processing transaction, which must be the engine transaction; returning false keeps the row BUFFERED.
     */
    public InboxService registerSourceHandler(String sourcePrefix, Inbox.Handler handler) {
        sourceHandlers.put(sourcePrefix, handler);
        return this;
    }

    private Inbox.Handler sourceHandler(String source) {
        Inbox.Handler best = null;
        int bestLen = -1;
        for (Map.Entry<String, Inbox.Handler> e : sourceHandlers.entrySet()) {
            if (source != null && source.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best;
    }

    public EventDefinitionPolicy.Registry policies() {
        return policies;
    }

    /**
     * Step 1. Commit happens before return; ack the broker afterwards. Throws
     * {@link PoisonInbound.PoisonMessageException} for a payload that is not valid JSON (never transient: the consumer
     * dead-letters and acks it).
     */
    public Inbox.ReceiveResult receive(ChannelBinding binding, InboundMessage m) {
        gate.check(BackpressureGate.Category.INBOUND_EVENT);
        String tenant = binding.tenantFor(m.claimedTenant());
        if (!PoisonInbound.isWellFormedJson(m.payloadJson())) {
            throw new PoisonInbound.PoisonMessageException("payload of " + binding.source() + "/" + m.messageId() + " is not valid JSON");
        }
        EventDefinitionPolicy p = policies.get(m.eventType());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventType", m.eventType());
        env.put("principal", binding.principal());
        env.put("data", m.payloadJson() == null ? null : Json.MAPPER.convertValue(Json.tree(m.payloadJson()), Object.class));
        return inbox.receive(tenant, binding.source(), m.messageId(), m.correlationKey(), Json.write(env), p.ttl());
    }

    /** Step 2: claims up to {@code batchSize} due messages and processes each in its own transaction. */
    public int processOnce() {
        int processed = 0;
        for (Inbox.Message m : inbox.claim(settings.batchSize())) {
            Inbox.Handler raw = sourceHandler(m.source());
            InboxHandler.Envelope env = raw == null ? decode(m) : null;
            InboxHandler h = raw == null ? handlers.getOrDefault(env.eventType(), defaultHandler) : null;
            String label = raw == null ? env.eventType() : m.source();
            try {
                boolean done = tx.inTransaction(c -> {
                    if (raw != null) {
                        boolean matched = raw.handle(c, m);
                        if (matched) {
                            inbox.markProcessed(c, m);
                        } else {
                            inbox.markBuffered(c, m);
                        }
                        return matched;
                    }
                    InboxHandler.Disposition d = h.handle(c, env);
                    switch (d) {
                        case PROCESSED -> {
                            inbox.markProcessed(c, m);
                            return true;
                        }
                        case REJECTED -> {
                            Jdbc.update(c, "UPDATE INBOX SET expires_at=" + dialect.now()
                                    + " WHERE tenant_id=? AND source=? AND message_id=?", m.tenantId(), m.source(), m.messageId());
                            inbox.markBuffered(c, m);
                            return false;
                        }
                        default -> {
                            inbox.markBuffered(c, m);
                            return false;
                        }
                    }
                });
                if (done) {
                    processed++;
                }
            } catch (Inbox.ClaimLostException e) {
                LOG.debug("inbox claim lost for {}/{}", m.source(), m.messageId());
            } catch (IllegalArgumentException e) {
                if (raw != null) {
                    // Audit PR-7 (FW-28, FW-32): a source handler (e.g. ports' EventDispatcher for evt:*) that rejects
                    // the message with an IllegalArgumentException (ports' PortsProblem) has a permanent error:
                    // retrying until the TTL cannot succeed. Park it after this first failure.
                    park(m, label, e);
                } else {
                    buffer(m, label, e);
                }
            } catch (RuntimeException e) {
                // The engine transaction may wrap the handler's exception: look for a permanent cause.
                IllegalArgumentException permanent = raw == null ? null : permanentCause(e);
                if (permanent != null) {
                    park(m, label, permanent);
                } else {
                    buffer(m, label, e);
                }
            }
        }
        return processed;
    }

    private static IllegalArgumentException permanentCause(Throwable t) {
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof IllegalArgumentException iae) {
                return iae;
            }
        }
        return null;
    }

    /** Transient handler failure: back to BUFFERED for a later retry (the TTL path). */
    private void buffer(Inbox.Message m, String label, RuntimeException e) {
        LOG.warn("inbox handler failed for {}/{} ({}); buffered for retry", m.source(), m.messageId(), label, e);
        try {
            tx.inTransaction(c -> {
                inbox.markBuffered(c, m);
                return null;
            });
        } catch (RuntimeException ignored) {
            // the sweeper resets the claim after its TTL
        }
    }

    /**
     * Permanent handler failure: the row is expired at once (state EXPIRED, claim released) and copied to the
     * dead-letter store with the error code, in one transaction, so an operator can inspect, requeue or discard it
     * (the same parking mechanism the outbox relay uses). The {@code maintain()} dead-letter pass skips it afterwards
     * (NOT EXISTS on the dead-letter ref).
     */
    private void park(Inbox.Message m, String label, IllegalArgumentException e) {
        String code = problemCode(e);
        LOG.warn("inbox handler rejected {}/{} ({}) permanently with {}; parked", m.source(), m.messageId(), label, code);
        try {
            tx.inTransaction(c -> {
                Jdbc.update(c, "UPDATE INBOX SET state='EXPIRED', expires_at=" + dialect.now()
                                + ", claim_owner=NULL, claim_expires_at=NULL WHERE tenant_id=? AND source=? AND message_id=?",
                        m.tenantId(), m.source(), m.messageId());
                if (deadLetters != null) {
                    deadLetters.record(c, m.tenantId(), DeadLetterStore.Kind.INBOX, m.source() + "/" + m.messageId(),
                            m.source(), null, label, m.payload(), "PERMANENT " + code + ": " + e.getMessage());
                }
                return null;
            });
        } catch (RuntimeException parkFailed) {
            // Parking itself failed (database): treat as transient so nothing is lost; retried on the next claim.
            buffer(m, label, parkFailed);
        }
    }

    /**
     * The stable code of a ports {@code PortsProblem} ({@code code()}), read reflectively because sangam-flowable-ports
     * is an optional dependency of this module; any other {@code IllegalArgumentException} is {@code INVALID_INPUT}.
     */
    private static String problemCode(IllegalArgumentException e) {
        try {
            Object code = e.getClass().getMethod("code").invoke(e);
            if (code != null) {
                return code.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not a PortsProblem
        }
        return "INVALID_INPUT";
    }

    /** Steps 3 and 4: sweep expired claims, expire past-TTL messages, dead-letter expired ones. */
    public int maintain() {
        int swept = inbox.sweep();
        int expired = inbox.expire();
        if (deadLetters != null && expired > 0) {
            tx.inTransaction(c -> {
                List<Object[]> rows = Jdbc.query(c, "SELECT i.tenant_id, i.source, i.message_id, i.correlation_key, i.payload FROM INBOX i"
                                + " WHERE i.state='EXPIRED' AND NOT EXISTS (SELECT 1 FROM INTEGRATION_DEAD_LETTER d WHERE d.kind='INBOX'"
                                + " AND d.tenant_id=i.tenant_id AND d.ref_id = " + concat("i.source", "'/'", "i.message_id") + ")",
                        rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
                for (Object[] r : rows) {
                    String eventType = String.valueOf(Json.readMap((String) r[4]).get("eventType"));
                    if (policies.get(eventType).deadLetter()) {
                        deadLetters.record(c, (String) r[0], DeadLetterStore.Kind.INBOX, r[1] + "/" + r[2], (String) r[1], null,
                                eventType, (String) r[4], "EXPIRED without matching subscription (correlation " + r[3] + ")");
                    }
                }
                return null;
            });
        }
        return swept + expired;
    }

    private String concat(String a, String b, String c) {
        return switch (dialect) {
            case MYSQL -> "CONCAT(" + a + "," + b + "," + c + ")";
            case SQLSERVER -> a + " + " + b + " + " + c;
            default -> a + " || " + b + " || " + c;
        };
    }

    /** Starts the processing loop (poll every {@code pollInterval}; maintenance every {@code maintenanceInterval}). */
    public void start() {
        if (running.compareAndSet(false, true)) {
            loop.execute(this::tick);
        }
    }

    private void tick() {
        if (!running.get()) {
            return;
        }
        long delay = settings.pollInterval().toMillis();
        try {
            if (System.currentTimeMillis() - lastMaintenance >= settings.maintenanceInterval().toMillis()) {
                maintain();
                lastMaintenance = System.currentTimeMillis();
            }
            if (processOnce() >= settings.batchSize()) {
                delay = 0;
            }
        } catch (RuntimeException e) {
            LOG.warn("inbox cycle failed", e);
        }
        if (running.get()) {
            loop.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
        }
    }

    static InboxHandler.Envelope decode(Inbox.Message m) {
        Map<String, Object> env = Json.readMap(m.payload());
        Object data = env.get("data");
        return new InboxHandler.Envelope(m.tenantId(), m.source(), m.messageId(), String.valueOf(env.getOrDefault("eventType", "unknown")),
                m.correlationKey(), (String) env.get("principal"), data == null ? null : Json.write(data));
    }

    @Override
    public void close() {
        running.set(false);
        loop.shutdown();
    }
}
