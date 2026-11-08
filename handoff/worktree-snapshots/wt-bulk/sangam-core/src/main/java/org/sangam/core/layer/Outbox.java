package org.sangam.core.layer;

import java.sql.Connection;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional outbox write path (SANGAM.md VI.2.2).
 *
 * <p>{@link #append} runs inside the command transaction. It allocates {@code root_seq} from the root's
 * {@code ROOT_SEQ} row, locked {@code FOR UPDATE} (the L2 surrogate), so {@code root_seq} order equals commit order per
 * root, and inserts one {@code OUTBOX} row plus one {@code PENDING} {@code OUTBOX_DELIVERY} row per destination.
 * {@code OUTBOX.id} is a plain identity and carries no ordering guarantee across transactions; the relay never uses it as
 * a watermark.
 */
public final class Outbox {

    /** A subscribed destination; {@code ordered=true} is the default (history projector, ordered channels). */
    public record Destination(String name, boolean ordered) {
        public static Destination ordered(String name) {
            return new Destination(name, true);
        }

        public static Destination unordered(String name) {
            return new Destination(name, false);
        }
    }

    public record Appended(long outboxId, UUID eventId, long rootSeq) {
    }

    /** The event as published; the CloudEvents {@code id} is {@code eventId}. */
    public record OutboxEvent(long outboxId, UUID eventId, String tenantId, String rootId, long rootSeq, String eventType,
                              String payload) {
    }

    private final Dialect dialect;

    public Outbox(Dialect dialect) {
        this.dialect = dialect;
    }

    public Appended append(Connection c, String tenant, String rootId, String eventType, String payloadJson,
                           Collection<Destination> destinations) {
        Set<String> seen = new LinkedHashSet<>();
        for (Destination d : destinations) {
            if (!seen.add(d.name())) {
                throw new IllegalArgumentException("duplicate destination " + d.name());
            }
        }
        long rootSeq = RootSeq.nextOutboxSeq(c, dialect, tenant, rootId);
        UUID eventId = Hashes.uuidV7();
        long id = Jdbc.insertReturningId(c, dialect,
                "INSERT INTO OUTBOX(tenant_id, root_id, root_seq, event_type, event_id, payload, created_at) VALUES (?,?,?,?,?,?,"
                        + dialect.now() + ")",
                "id", tenant, rootId, rootSeq, eventType, eventId, payloadJson);
        for (Destination d : destinations) {
            Jdbc.update(c, "INSERT INTO OUTBOX_DELIVERY(outbox_id, destination, tenant_id, root_id, root_seq, ordered_flag, state,"
                            + " attempts, next_attempt_at, claim_owner, claim_epoch, claim_expires_at, last_error)"
                            + " VALUES (?,?,?,?,?,?,'PENDING',0," + dialect.now() + ",NULL,0,NULL,NULL)",
                    id, d.name(), tenant, rootId, rootSeq, d.ordered() ? 1 : 0);
        }
        return new Appended(id, eventId, rootSeq);
    }
}
