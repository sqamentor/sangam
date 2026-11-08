// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.task;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * Task and instance lifecycle fields Operaton lacks (FLOWABLE-INTO-OPERATON FC-52, Flowable {@code TaskInfo}: {@code claimTime/claimedBy},
 * {@code inProgressStartTime/By}, {@code suspendedTime}; instance {@code businessStatus}). Tables
 * {@code TASK_LIFECYCLE} and {@code INSTANCE_BUSINESS_STATUS}, written in the engine transaction by
 * {@link TaskEventDispatcher} or by the product API (R42/R65 read model).
 */
public class TaskLifecycleService {

    public record TaskLifecycle(String taskId, String tenantId, Instant claimTime, String claimedBy, Instant inProgressStart,
                                String inProgressBy, Instant suspendedTime, String lastEvent, Instant updatedAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;

    public TaskLifecycleService(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    public void claimed(Connection c, String taskId, String tenant, String user) {
        ensure(c, taskId, tenant);
        Jdbc.update(c, "UPDATE TASK_LIFECYCLE SET claim_time = " + dialect.now() + ", claimed_by = ?, last_event = 'claim', updated_at = "
                + dialect.now() + " WHERE task_id = ?", user, taskId);
    }

    public void unclaimed(Connection c, String taskId, String tenant) {
        ensure(c, taskId, tenant);
        Jdbc.update(c, "UPDATE TASK_LIFECYCLE SET claim_time = NULL, claimed_by = NULL, in_progress_start = NULL, in_progress_by = NULL,"
                + " last_event = 'unclaim', updated_at = " + dialect.now() + " WHERE task_id = ?", taskId);
    }

    public void suspended(Connection c, String taskId, String tenant, boolean suspended) {
        ensure(c, taskId, tenant);
        Jdbc.update(c, "UPDATE TASK_LIFECYCLE SET suspended_time = " + (suspended ? dialect.now() : "NULL") + ", last_event = ?, updated_at = "
                + dialect.now() + " WHERE task_id = ?", suspended ? "suspend" : "activate", taskId);
    }

    public void event(Connection c, String taskId, String tenant, String event) {
        ensure(c, taskId, tenant);
        Jdbc.update(c, "UPDATE TASK_LIFECYCLE SET last_event = ?, updated_at = " + dialect.now() + " WHERE task_id = ?", event, taskId);
    }

    /** Flowable {@code TaskService.startProgress}: the assignee starts working on the task. */
    public void startProgress(String taskId, String tenant, String user) {
        tx.inTransaction(c -> {
            ensure(c, taskId, tenant);
            Jdbc.update(c, "UPDATE TASK_LIFECYCLE SET in_progress_start = " + dialect.now() + ", in_progress_by = ?, last_event = 'in-progress',"
                    + " updated_at = " + dialect.now() + " WHERE task_id = ?", user, taskId);
            return null;
        });
    }

    public Optional<TaskLifecycle> get(String taskId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT task_id, tenant_id, claim_time, claimed_by, in_progress_start, in_progress_by,"
                        + " suspended_time, last_event, updated_at FROM TASK_LIFECYCLE WHERE task_id = ?",
                rs -> new TaskLifecycle(rs.getString(1), rs.getString(2), Jdbc.instant(rs, "claim_time"), rs.getString(4),
                        Jdbc.instant(rs, "in_progress_start"), rs.getString(6), Jdbc.instant(rs, "suspended_time"), rs.getString(8),
                        Jdbc.instant(rs, "updated_at")), taskId));
    }

    /** Flowable {@code businessStatus} on a process or case instance. */
    public void setBusinessStatus(String instanceId, String tenant, String status, String user) {
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE INSTANCE_BUSINESS_STATUS SET business_status = ?, updated_by = ?, updated_at = " + dialect.now()
                    + " WHERE instance_id = ? AND tenant_id = ?", status, user, instanceId, t(tenant));
            if (n == 0) {
                // the row of an instance of another tenant is never overwritten (tenant isolation, VI.4)
                if (Jdbc.one(c, "SELECT tenant_id FROM INSTANCE_BUSINESS_STATUS WHERE instance_id = ?", rs -> rs.getString(1),
                        instanceId).isPresent()) {
                    throw new IllegalArgumentException("instance " + instanceId + " is not in tenant " + t(tenant));
                }
                Jdbc.update(c, "INSERT INTO INSTANCE_BUSINESS_STATUS(instance_id, tenant_id, business_status, updated_by, updated_at)"
                        + " VALUES (?,?,?,?," + dialect.now() + ")", instanceId, t(tenant), status, user);
            }
            return null;
        });
    }

    /** The business status of an instance of {@code tenant}; empty for an instance of another tenant (VI.4). */
    public Optional<String> businessStatus(String instanceId, String tenant) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT business_status FROM INSTANCE_BUSINESS_STATUS WHERE instance_id = ? AND tenant_id = ?",
                rs -> rs.getString(1), instanceId, t(tenant)));
    }

    private static String t(String tenant) {
        return tenant == null ? "" : tenant;
    }

    private void ensure(Connection c, String taskId, String tenant) {
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO TASK_LIFECYCLE(task_id, tenant_id, last_event, updated_at) VALUES (?,?,'create',"
                + dialect.now() + ")", taskId, tenant);
    }
}
