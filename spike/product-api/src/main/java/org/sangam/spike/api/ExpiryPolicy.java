package org.sangam.spike.api;

import java.sql.Connection;
import java.time.Instant;

/**
 * Lease-expiry hook (SANGAM.md XI.10.3 A-10). When a worker lease expires, the adapter calls
 * {@link #onExpired(ExpiredWork, Connection)} <b>in the same engine transaction</b> that ends the lease, and
 * <b>before</b> the expired work can be fetched again: inside {@link EngineAdapter#fetchAndLock} (before the engine's own
 * fetch query runs, and again for any work the engine itself re-offers because its lock ran out) and inside
 * {@link EngineAdapter#executeDueJobs}.
 *
 * <ul>
 *   <li>{@link Decision#REOFFER}: the lease ends (lock_version + 1) and the work becomes AVAILABLE again.</li>
 *   <li>{@link Decision#HOLD}: the lease ends (lock_version + 1) and the work is <b>parked</b>: it is not offered to
 *       any worker until the product releases it through the engine (on Operaton: {@code setRetries(id, n)} on the
 *       external task, which also resolves the incident the hold created).</li>
 * </ul>
 *
 * <p>The connection is the engine command's connection, so the product can record the expiry (A-8: class (c) work to
 * {@code UNKNOWN_OUTCOME}) atomically with the lease change. The policy must not commit or close it, and must not
 * call remote systems (VI.1 rule 5). An exception thrown by the policy rolls the whole command back.
 */
@FunctionalInterface
public interface ExpiryPolicy {

    /** What happens to expired work. */
    enum Decision { REOFFER, HOLD }

    /**
     * The expired lease and the engine identity of its work.
     *
     * @param workId             engine work id (Operaton external task id)
     * @param workerId           the worker whose lease expired
     * @param lockVersion        the expired lease's version (the next lease, if any, gets a higher one)
     * @param attemptId          the expired lease's attemptId
     * @param expiredAt          the lease expiry (engine clock)
     * @param activityInstanceId the operationId input (VI.3.2 rule 4); may be null if the engine has no such id
     */
    record ExpiredWork(String workId, String workerId, long lockVersion, String attemptId, Instant expiredAt,
                       String topic, String processInstanceId, String activityId, String activityInstanceId) {
    }

    /** Default: expired work is offered again (the behaviour before A-10). */
    ExpiryPolicy ALLOW_REOFFER = (work, connection) -> Decision.REOFFER;

    Decision onExpired(ExpiredWork work, Connection connection);
}
