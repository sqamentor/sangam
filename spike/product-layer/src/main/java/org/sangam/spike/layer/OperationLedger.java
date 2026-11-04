package org.sangam.spike.layer;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Business-operation identity and its ledger (SANGAM.md VI.3 rule 4–5, R36, A-1, A-2, A-8).
 *
 * <ul>
 *   <li>{@code operationId} is a UUIDv5 of engine identity only (tenant, root, activity instance, loop counter and an
 *       optional retry-safe key). It is stable across retries, redelivery, lease expiry, migration and restore, and is
 *       never derived from a client idempotency key (X.2.2.1).</li>
 *   <li>{@code attemptId} is separate and changes per lease. Every write is fenced by {@code lockVersion}: a stale lock
 *       version gets {@link StaleLeaseException} (409 STALE_LEASE) and changes nothing.</li>
 *   <li>Lease expiry follows the {@link CapabilityClass}: (a) retry with the same operationId, (b) look up then retry,
 *       (c, the default) UNKNOWN_OUTCOME with a reconciliation item, never an automatic repeat.</li>
 * </ul>
 * All methods run inside the caller's (engine) transaction and lock only the ledger row (an L4-level row).
 */
public final class OperationLedger {

    /** Namespace for operation ids: UUIDv5(URL namespace, "urn:sangam:operation-id:v1"). */
    public static final UUID NAMESPACE = Hashes.uuidV5(UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"),
            "urn:sangam:operation-id:v1");

    public enum State { INTENT, SUCCEEDED, FAILED, UNKNOWN_OUTCOME }

    public record OperationRef(String tenantId, String rootId, String activityInstanceId, int loopCounter) {
        public UUID operationId() {
            return OperationLedger.operationId(tenantId, rootId, activityInstanceId, loopCounter);
        }
    }

    public record Entry(UUID operationId, String tenantId, String rootId, String activityInstanceId, int loopCounter,
                        CapabilityClass capabilityClass, byte[] requestFingerprint, State state, String attemptId,
                        long lockVersion, int attempts, String outcome) {
    }

    /** Result of {@link #recordIntent}: whether the caller may dispatch, and with which identity. */
    public record Intent(UUID operationId, boolean firstAttempt, State state, int attempts, String storedOutcome) {
        /** Dispatch (or, for class b, look up then dispatch) only while the operation is still an intent. */
        public boolean mayDispatch() {
            return state == State.INTENT;
        }
    }

    /** 409 STALE_LEASE (VI.3 rule 2). */
    public static final class StaleLeaseException extends RuntimeException {
        public StaleLeaseException(String message) {
            super("409 STALE_LEASE: " + message);
        }
    }

    /** The same operationId arrived with a different request: a new business action needs a new operationId (A-2). */
    public static final class FingerprintMismatchException extends RuntimeException {
        public FingerprintMismatchException(UUID op) {
            super("operation " + op + " already recorded with a different request fingerprint");
        }
    }

    private final Dialect dialect;
    private final ReconciliationQueue reconciliation;

    public OperationLedger(Dialect dialect, ReconciliationQueue reconciliation) {
        this.dialect = dialect;
        this.reconciliation = reconciliation;
    }

    // ------------------------------------------------------------------ identity

    public static UUID operationId(String tenant, String rootId, String activityInstanceId, int loopCounter) {
        return operationId(tenant, rootId, activityInstanceId, loopCounter, null);
    }

    /** R36: UUIDv5(tenant, rootId, activityInstanceId, loopCounter, retrySafeKey). Components are length-prefixed. */
    public static UUID operationId(String tenant, String rootId, String activityInstanceId, int loopCounter, String retrySafeKey) {
        StringBuilder name = new StringBuilder("op-v1");
        for (String part : new String[]{tenant, rootId, activityInstanceId, Integer.toString(loopCounter),
                retrySafeKey == null ? "" : retrySafeKey}) {
            if (part == null) {
                throw new IllegalArgumentException("operation identity component is null");
            }
            name.append('|').append(part.length()).append(':').append(part);
        }
        return Hashes.uuidV5(NAMESPACE, name.toString());
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Records a call intent before dispatch (R37). The first call inserts the row; a later attempt (higher lockVersion,
     * new attemptId) reuses the same operationId. A lower lockVersion is stale.
     *
     * @param declaredClass the topic's capability class; null means the A-8 default (c)
     */
    public Intent recordIntent(Connection c, OperationRef ref, CapabilityClass declaredClass, byte[] requestFingerprint,
                               String attemptId, long lockVersion) {
        UUID op = ref.operationId();
        CapabilityClass cls = declaredClass == null ? CapabilityClass.C_NEITHER : declaredClass;
        boolean inserted = Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO OPERATION_LEDGER(operation_id, tenant_id, root_id, activity_instance_id, loop_counter, capability_class,"
                        + " request_fingerprint, state, attempt_id, lock_version, attempts, outcome, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,'INTENT',?,?,1,NULL," + dialect.now() + "," + dialect.now() + ")",
                op, ref.tenantId(), ref.rootId(), ref.activityInstanceId(), ref.loopCounter(), cls, requestFingerprint,
                attemptId, lockVersion);
        if (inserted) {
            return new Intent(op, true, State.INTENT, 1, null);
        }
        Entry e = lock(c, op).orElseThrow();
        if (e.requestFingerprint() != null && requestFingerprint != null && !Arrays.equals(e.requestFingerprint(), requestFingerprint)) {
            throw new FingerprintMismatchException(op);
        }
        if (e.state() != State.INTENT) {
            return new Intent(op, false, e.state(), e.attempts(), e.outcome());   // settled or awaiting reconciliation
        }
        if (lockVersion < e.lockVersion()) {
            throw new StaleLeaseException("lockVersion " + lockVersion + " < " + e.lockVersion());
        }
        // a higher lockVersion, or the version the lease-expiry fence already moved to with a new attemptId, is a new attempt
        if (lockVersion > e.lockVersion() || !java.util.Objects.equals(attemptId, e.attemptId())) {
            Jdbc.update(c, "UPDATE OPERATION_LEDGER SET attempt_id=?, lock_version=?, attempts=attempts+1, updated_at=" + dialect.now()
                    + " WHERE operation_id=?", attemptId, lockVersion, op);
            return new Intent(op, false, State.INTENT, e.attempts() + 1, null);
        }
        return new Intent(op, false, State.INTENT, e.attempts(), null);
    }

    /** Records a definite outcome, fenced by lockVersion. Repeating the identical outcome is a no-op. */
    public void recordOutcome(Connection c, UUID operationId, long lockVersion, boolean success, String outcomeJson) {
        State target = success ? State.SUCCEEDED : State.FAILED;
        int n = Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state=?, outcome=?, updated_at=" + dialect.now()
                + " WHERE operation_id=? AND lock_version=? AND state='INTENT'", target, outcomeJson, operationId, lockVersion);
        if (n == 1) {
            return;
        }
        Entry e = find(c, operationId).orElseThrow(() -> new IllegalStateException("unknown operation " + operationId));
        if (e.lockVersion() == lockVersion && e.state() == target && java.util.Objects.equals(e.outcome(), outcomeJson)) {
            return;   // identical repeat
        }
        throw new StaleLeaseException("operation " + operationId + " is " + e.state() + " at lockVersion " + e.lockVersion());
    }

    /** The worker cannot know whether the downstream call succeeded (VI.3 rule 4): opens an UNKNOWN_OUTCOME item. */
    public long markUnknown(Connection c, UUID operationId, long lockVersion, String reason) {
        Entry e = lock(c, operationId).orElseThrow(() -> new IllegalStateException("unknown operation " + operationId));
        if (e.lockVersion() != lockVersion || (e.state() != State.INTENT && e.state() != State.UNKNOWN_OUTCOME)) {
            throw new StaleLeaseException("operation " + operationId + " is " + e.state() + " at lockVersion " + e.lockVersion());
        }
        return toUnknown(c, e, reason);
    }

    /**
     * Lease expiry for the task carrying this operation (VI.3.1, A-8). Fences the expired attempt by moving the ledger to
     * {@code newLockVersion}, then applies the capability class.
     */
    public CapabilityClass.LeaseExpiryAction onLeaseExpired(Connection c, UUID operationId, long newLockVersion) {
        Entry e = lock(c, operationId).orElseThrow(() -> new IllegalStateException("unknown operation " + operationId));
        if (e.state() != State.INTENT) {
            return e.state() == State.UNKNOWN_OUTCOME ? CapabilityClass.LeaseExpiryAction.UNKNOWN_OUTCOME
                    : CapabilityClass.LeaseExpiryAction.RETRY_SAME_OPERATION;
        }
        if (newLockVersion <= e.lockVersion()) {
            throw new StaleLeaseException("new lockVersion must exceed " + e.lockVersion());
        }
        Jdbc.update(c, "UPDATE OPERATION_LEDGER SET lock_version=?, updated_at=" + dialect.now() + " WHERE operation_id=?",
                newLockVersion, operationId);
        CapabilityClass.LeaseExpiryAction action = e.capabilityClass().onLeaseExpired();
        if (action == CapabilityClass.LeaseExpiryAction.UNKNOWN_OUTCOME) {
            toUnknown(c, e, "lease expired; capability class " + e.capabilityClass() + " forbids automatic repeat");
        }
        return action;
    }

    public Optional<Entry> find(Connection c, UUID operationId) {
        return Jdbc.one(c, "SELECT * FROM OPERATION_LEDGER WHERE operation_id=?", OperationLedger::map, operationId);
    }

    private Optional<Entry> lock(Connection c, UUID operationId) {
        return Jdbc.one(c, dialect.lockingSelect("*", "OPERATION_LEDGER", "operation_id=?"), OperationLedger::map, operationId);
    }

    private long toUnknown(Connection c, Entry e, String reason) {
        Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state='UNKNOWN_OUTCOME', updated_at=" + dialect.now() + " WHERE operation_id=?",
                e.operationId());
        return reconciliation.open(c, e.tenantId(), ReconciliationQueue.Kind.UNKNOWN_OUTCOME, e.operationId(), e.rootId(), null,
                CanonicalJson.write(java.util.Map.of("reason", reason, "capabilityClass", e.capabilityClass().name())));
    }

    private static Entry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(UUID.fromString(rs.getString("operation_id")), rs.getString("tenant_id"), rs.getString("root_id"),
                rs.getString("activity_instance_id"), rs.getInt("loop_counter"),
                CapabilityClass.valueOf(rs.getString("capability_class")), rs.getBytes("request_fingerprint"),
                State.valueOf(rs.getString("state")), rs.getString("attempt_id"), rs.getLong("lock_version"),
                rs.getInt("attempts"), rs.getString("outcome"));
    }
}
