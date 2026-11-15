// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineComponents;
import org.sangam.engine.SangamProcessEnginePlugin;

/**
 * FLOWABLE-INTO-OPERATON FC-18, owner decision 258: moves the history of one ended process instance to a later version
 * of its process definition. Only the process-definition id and key of the instance's history rows change, in one
 * engine transaction, together with one {@value #EVENT} audit event (old and new definition ids, actor, reason, row
 * counts). Operaton has no API for this (its migration moves running instances only).
 *
 * <p>Rules, all checked inside the transaction after the root lock is held:
 * <ul>
 *   <li>{@code reason} 1..1000 characters, a non-system actor with a person id ({@value #VALIDATION_FAILED});</li>
 *   <li>the instance exists in {@code ACT_HI_PROCINST} and belongs to the actor's tenant; an untenanted instance only to
 *       a platform identity (group {@value #PLATFORM_MARKER}); otherwise {@value #NOT_FOUND} (no existence leak);</li>
 *   <li>it has ended ({@code END_TIME_} set: {@value #NOT_ENDED} otherwise) and has no removal time
 *       ({@code REMOVAL_TIME_}: {@value #REMOVAL_TIME_SET});</li>
 *   <li>no active legal hold ({@code LEGAL_HOLD} row with {@code released_at IS NULL}) on its root process instance, in
 *       any tenant ({@value #LEGAL_HOLD_ACTIVE});</li>
 *   <li>the target is a deployed definition of the same key, in the same tenant (both untenanted, or the same tenant
 *       id), with a higher version than the instance's current definition ({@value #TARGET_INVALID}; also when the
 *       current definition is no longer deployed, since its version cannot be compared).</li>
 * </ul>
 * Rows changed (each update also requires the old definition id, so a row of another definition is never touched):
 * {@link #TABLES} by the instance id, and {@code ACT_HI_IDENTITYLINK} through the instance's history tasks (the table
 * has no process instance column; definition-level identity links have no task and are not touched).
 * {@code ACT_HI_COMMENT}, {@code ACT_HI_ATTACHMENT}, {@code ACT_HI_DEC_IN}, {@code ACT_HI_DEC_OUT} and
 * {@code ACT_HI_BATCH} have no process-definition column. Called-instance histories keep their own definitions.
 *
 * <p>The root lock is the L2 lock of the root process instance with the adapter's key namespace (tenant id, or
 * {@code defaultTenantId} for an untenanted root: PATCHES B-42), so the move serialises with any other work on the
 * hierarchy. The audit event goes to the instance's tenant, or the system audit scope for an untenanted instance, on
 * the chain of the root process instance.
 */
public final class HistoricInstanceMigration {

    /** Audit event (class B; name sent to INT-H2 for the audit catalogue). */
    public static final String EVENT = "PROCESS_HISTORY_MIGRATED";

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_ENDED = "HISTORY_INSTANCE_NOT_ENDED";
    public static final String REMOVAL_TIME_SET = "HISTORY_REMOVAL_TIME_SET";
    public static final String LEGAL_HOLD_ACTIVE = "LEGAL_HOLD_ACTIVE";
    public static final String TARGET_INVALID = "HISTORY_MIGRATION_TARGET_INVALID";

    /** Platform-issuer marker group (equal to {@code SharedDeployments.PLATFORM_MARKER}). */
    public static final String PLATFORM_MARKER = "sangam-platform";

    static final int MAX_REASON = 1000;

    /** A history table with its definition id, definition key and instance id columns. */
    public record Table(String name, String definitionId, String definitionKey, String instanceId) {
    }

    /** The tables updated by instance id (Operaton 2.2.0 {@code activiti.*.create.history.sql} and decision history). */
    public static final List<Table> TABLES = List.of(
            new Table("ACT_HI_PROCINST", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_ACTINST", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_TASKINST", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_VARINST", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_DETAIL", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_INCIDENT", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_OP_LOG", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_EXT_TASK_LOG", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_DECINST", "PROC_DEF_ID_", "PROC_DEF_KEY_", "PROC_INST_ID_"),
            new Table("ACT_HI_JOB_LOG", "PROCESS_DEF_ID_", "PROCESS_DEF_KEY_", "PROCESS_INSTANCE_ID_"));

    /** The identity-link table, updated through the instance's history tasks. */
    public static final String IDENTITY_LINKS = "ACT_HI_IDENTITYLINK";

    /** A refused move; {@link #code()} is the REST problem code. */
    public static final class HistoryMigrationException extends RuntimeException {
        private final String code;

        public HistoryMigrationException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The outcome, field for field the audit payload: the definitions, their versions and the rows per table. */
    public record Result(String processInstanceId, String fromDefinitionId, String toDefinitionId, int fromVersion,
                         int toVersion, Map<String, Integer> rowsByTable) {
    }

    private record Instance(String tenantId, String root, String definitionId, Instant endTime, Instant removalTime) {
    }

    private record Definition(String id, String key, int version, String tenantId) {
    }

    private final OperatonEngineAdapter adapter;
    private final SangamEngineComponents components;

    public HistoricInstanceMigration(OperatonEngineAdapter adapter) {
        this.adapter = adapter;
        this.components = componentsOf(adapter.processEngine());
    }

    private static SangamEngineComponents componentsOf(ProcessEngine engine) {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        return cfg.getProcessEnginePlugins().stream().filter(SangamProcessEnginePlugin.class::isInstance)
                .map(p -> ((SangamProcessEnginePlugin) p).components()).findFirst()
                .orElseThrow(() -> new IllegalStateException("FLOWABLE-INTO-OPERATON FC-18: not a Sangam engine (no SangamProcessEnginePlugin)"));
    }

    /**
     * Moves the history of {@code processInstanceId} to {@code targetDefinitionId}.
     *
     * @throws HistoryMigrationException a refusal; nothing is changed
     */
    public Result migrate(SecurityIdentity actor, String processInstanceId, String targetDefinitionId, String reason) {
        if (actor == null || actor.system() || actor.personId() == null || actor.personId().isBlank()) {
            throw new HistoryMigrationException(VALIDATION_FAILED, "a person actor is required");
        }
        requireLength("processInstanceId", processInstanceId, 64);
        requireLength("targetDefinitionId", targetDefinitionId, 64);
        requireLength("reason", reason, MAX_REASON);
        return adapter.inEngineTransaction(c -> {
            Instance first = instance(c, processInstanceId);
            requireVisible(actor, first);
            String tenant = ExecutionTenantPolicy.normalise(first.tenantId());
            components.rootLock().lockOne(c, tenant == null ? components.settings().getDefaultTenantId() : tenant,
                    first.root());
            Instance in = instance(c, processInstanceId); // re-read under the lock
            if (!Objects.equals(first.tenantId(), in.tenantId()) || !Objects.equals(first.root(), in.root())) {
                throw new HistoryMigrationException(NOT_FOUND, "the history instance changed while it was locked");
            }
            if (in.endTime() == null) {
                throw new HistoryMigrationException(NOT_ENDED, "only an ended instance's history is moved");
            }
            if (in.removalTime() != null) {
                throw new HistoryMigrationException(REMOVAL_TIME_SET, "the history has a removal time");
            }
            if (Jdbc.one(c, "SELECT hold_id FROM LEGAL_HOLD WHERE root_id = ? AND released_at IS NULL",
                    rs -> rs.getString(1), in.root()).isPresent()) {
                throw new HistoryMigrationException(LEGAL_HOLD_ACTIVE, "the root process instance is under legal hold");
            }
            Definition from = definition(c, in.definitionId());
            Definition to = definition(c, targetDefinitionId);
            if (from == null || to == null || !from.key().equals(to.key())
                    || !Objects.equals(ExecutionTenantPolicy.normalise(from.tenantId()), tenant)
                    || !Objects.equals(ExecutionTenantPolicy.normalise(to.tenantId()), tenant)
                    || to.version() <= from.version()) {
                throw new HistoryMigrationException(TARGET_INVALID,
                        "the target must be a later version of the same key in the same tenant");
            }
            Map<String, Integer> rows = new LinkedHashMap<>();
            // identity links first: the task subquery reads ACT_HI_TASKINST by instance id, which never changes
            rows.put(IDENTITY_LINKS, Jdbc.update(c, "UPDATE " + IDENTITY_LINKS + " SET PROC_DEF_ID_ = ?, PROC_DEF_KEY_ = ?"
                    + " WHERE PROC_DEF_ID_ = ? AND TASK_ID_ IN (SELECT ID_ FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = ?)",
                    to.id(), to.key(), from.id(), processInstanceId));
            for (Table t : TABLES) {
                rows.put(t.name(), Jdbc.update(c, "UPDATE " + t.name() + " SET " + t.definitionId() + " = ?, "
                        + t.definitionKey() + " = ? WHERE " + t.instanceId() + " = ? AND " + t.definitionId() + " = ?",
                        to.id(), to.key(), processInstanceId, from.id()));
            }
            if (rows.get("ACT_HI_PROCINST") != 1) {
                throw new IllegalStateException("FLOWABLE-INTO-OPERATON FC-18: the history instance row was not moved");
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("processInstanceId", processInstanceId);
            p.put("rootProcessInstanceId", in.root());
            p.put("fromDefinitionId", from.id());
            p.put("toDefinitionId", to.id());
            p.put("fromVersion", from.version());
            p.put("toVersion", to.version());
            p.put("actor", actor.personId());
            p.put("reason", reason);
            p.put("rowsByTable", rows);
            p.put("migratedAt", components.clock().now().toString());
            components.auditSink().append(c, tenant == null ? components.workTenants().systemAuditScope() : tenant,
                    in.root(), EVENT, p);
            return new Result(processInstanceId, from.id(), to.id(), from.version(), to.version(), Map.copyOf(rows));
        });
    }

    private static Instance instance(Connection c, String processInstanceId) {
        return Jdbc.one(c, "SELECT TENANT_ID_, ROOT_PROC_INST_ID_, PROC_DEF_ID_, END_TIME_, REMOVAL_TIME_"
                        + " FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = ?",
                rs -> new Instance(rs.getString(1), rs.getString(2) != null ? rs.getString(2) : processInstanceId,
                        rs.getString(3), Jdbc.instant(rs, "END_TIME_"), Jdbc.instant(rs, "REMOVAL_TIME_")),
                processInstanceId).orElseThrow(() -> new HistoryMigrationException(NOT_FOUND, "no such history instance"));
    }

    private static Definition definition(Connection c, String id) {
        return Jdbc.one(c, "SELECT ID_, KEY_, VERSION_, TENANT_ID_ FROM ACT_RE_PROCDEF WHERE ID_ = ?",
                rs -> new Definition(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), id).orElse(null);
    }

    private static void requireVisible(SecurityIdentity actor, Instance in) {
        String tenant = ExecutionTenantPolicy.normalise(in.tenantId());
        boolean visible = tenant == null ? actor.groups() != null && actor.groups().containsAll(Set.of(PLATFORM_MARKER))
                : tenant.equals(ExecutionTenantPolicy.normalise(actor.tenantId()));
        if (!visible) {
            throw new HistoryMigrationException(NOT_FOUND, "no such history instance");
        }
    }

    private static void requireLength(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new HistoryMigrationException(VALIDATION_FAILED, field + " must be 1.." + max + " characters");
        }
    }
}
