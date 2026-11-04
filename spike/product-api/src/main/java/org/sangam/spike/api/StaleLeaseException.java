package org.sangam.spike.api;

/**
 * A worker call does not hold the live lease it claims (SANGAM.md VI.3.2 rule 2). The product layer maps it to
 * {@code 409 STALE_LEASE}. When it is thrown, nothing has changed in the engine or in the lease table.
 *
 * <p>Thrown by {@link EngineAdapter#extendLock}, {@link EngineAdapter#completeExternal},
 * {@link EngineAdapter#failExternal}, {@link EngineAdapter#bpmnErrorExternal} and
 * {@link EngineAdapter#unlockExternal} when any of these holds:
 * <ul>
 *   <li>the work does not exist, or has no lease that was granted by {@link EngineAdapter#fetchAndLock};</li>
 *   <li>the caller is not the lease owner ({@link Reason#NOT_OWNER});</li>
 *   <li>the {@code lockVersion} is not the current one ({@link Reason#STALE_VERSION});</li>
 *   <li>the lease has expired at the engine clock, even if nobody has re-acquired the work ({@link Reason#EXPIRED}).</li>
 * </ul>
 */
public class StaleLeaseException extends RuntimeException {

    /** Why the call was rejected. */
    public enum Reason { NO_LEASE, NOT_OWNER, STALE_VERSION, EXPIRED }

    private final String workId;
    private final Reason reason;

    public StaleLeaseException(String workId, Reason reason, String message) {
        super("STALE_LEASE (" + reason + ") on " + workId + ": " + message);
        this.workId = workId;
        this.reason = reason;
    }

    public String workId() {
        return workId;
    }

    public Reason reason() {
        return reason;
    }
}
