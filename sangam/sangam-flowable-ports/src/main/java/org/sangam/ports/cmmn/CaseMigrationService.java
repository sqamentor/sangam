// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cmmn.entity.repository.CaseDefinitionEntity;
import org.operaton.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.operaton.bpm.engine.impl.cmmn.entity.runtime.CaseSentryPartEntity;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnCaseDefinition;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.TenantKey;
import org.sangam.ports.support.EngineTx;

/**
 * PATCHES.md P-5 / FLOWABLE-INTO-OPERATON FC-19 native: CMMN case instance migration, integrator ruling A1.
 * <p>
 * Operaton 2.2.0 has no CMMN migration. Its own update statements (mapping {@code updateCaseExecution},
 * {@code updateCaseSentryPart}, {@code updateTask}) persist a changed case definition id of a case execution and a
 * task, but never a changed plan item id ({@code ACT_ID_}) or sentry id. A1 therefore migrates only when every
 * active plan item and every sentry part of the case exists, unchanged in id, kind and parent stage, in the target
 * definition. The whole migration is one engine command:
 * <ol>
 *   <li>validate actor, reason, same case key and tenant, then every plan item and sentry part; any problem rejects
 *       the call with the full per-item list before anything changes;</li>
 *   <li>rebind every case execution ({@link CaseExecutionEntity#setCaseDefinition}, {@link CaseExecutionEntity#setActivity})
 *       and every task of the case ({@link TaskEntity#setCaseDefinitionId}) through the engine entities, flushed by
 *       Operaton's own mapping; no engine table is written by Sangam SQL;</li>
 *   <li>write the audit row {@code PORT_CASE_MIGRATION} (who, from, to, why) in the same transaction.</li>
 * </ol>
 * History rows written before the migration keep the source definition id: they record what ran under it.
 * Internal API used: PATCHES.md I-4; upgrade guard {@code CaseMigrationEngineContractTest}.
 */
public class CaseMigrationService {

    public static final String TABLE = "PORT_CASE_MIGRATION";

    public record MigrationRecord(String id, String tenantId, String caseInstanceId, String sourceDefinitionId,
                                  String targetDefinitionId, String migratedBy, String reason, int executions,
                                  Instant createdAt) {
    }

    public static final class CaseMigrationException extends RuntimeException {
        private final List<String> problems;

        public CaseMigrationException(List<String> problems) {
            super("case migration rejected: " + problems);
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private static final String COLS = "id_, tenant_id, case_inst_id, source_def_id, target_def_id, migrated_by, reason,"
            + " executions, created_at";

    private final ProcessEngine engine;

    public CaseMigrationService(ProcessEngine engine) {
        this.engine = engine;
    }

    public MigrationRecord migrate(String caseInstanceId, String targetCaseDefinitionId, String user, String reason) {
        List<String> early = new ArrayList<>();
        if (user == null || user.isBlank()) {
            early.add("migratedBy is required");
        }
        if (reason == null || reason.isBlank()) {
            early.add("reason is required");
        }
        if (caseInstanceId == null || targetCaseDefinitionId == null) {
            early.add("caseInstanceId and targetCaseDefinitionId are required");
        }
        if (!early.isEmpty()) {
            throw new CaseMigrationException(early);
        }
        return EngineTx.execute(engine, ctx -> run(ctx, caseInstanceId, targetCaseDefinitionId, user.trim(), reason.trim()));
    }

    private MigrationRecord run(CommandContext ctx, String caseInstanceId, String targetId, String user, String reason) {
        List<String> problems = new ArrayList<>();
        CaseExecutionEntity instance = ctx.getCaseExecutionManager().findCaseExecutionById(caseInstanceId);
        if (instance == null || !instance.isCaseInstanceExecution()) {
            throw new CaseMigrationException(List.of("case instance " + caseInstanceId + " not found"));
        }
        CaseDefinitionEntity target = ctx.getProcessEngineConfiguration().getDeploymentCache()
                .findDeployedCaseDefinitionById(targetId);
        CaseDefinitionEntity source = ctx.getProcessEngineConfiguration().getDeploymentCache()
                .findDeployedCaseDefinitionById(instance.getCaseDefinitionId());
        if (target == null) {
            throw new CaseMigrationException(List.of("target case definition " + targetId + " not found"));
        }
        if (!Objects.equals(source.getKey(), target.getKey())) {
            problems.add("target key '" + target.getKey() + "' differs from the case key '" + source.getKey() + "'");
        }
        if (!Objects.equals(source.getTenantId(), target.getTenantId())) {
            problems.add("target tenant differs from the case tenant");
        }
        if (source.getId().equals(target.getId())) {
            problems.add("case already runs on " + targetId);
        }

        Map<String, CaseExecutionEntity> all = new LinkedHashMap<>();
        all.put(instance.getId(), instance);
        for (CaseExecutionEntity e : ctx.getCaseExecutionManager().findChildCaseExecutionsByCaseInstanceId(caseInstanceId)) {
            all.putIfAbsent(e.getId(), e);
        }
        Map<CaseExecutionEntity, CmmnActivity> rebind = new LinkedHashMap<>();
        for (CaseExecutionEntity e : all.values()) {
            String actId = e.getActivityId();
            CmmnActivity from = source.findActivity(actId);
            CmmnActivity to = target.findActivity(actId);
            if (to == null) {
                problems.add("plan item '" + actId + "' (" + e.getCurrentState() + ") does not exist in the target");
                continue;
            }
            if (from != null && from.getActivityBehavior() != null && to.getActivityBehavior() != null
                    && from.getActivityBehavior().getClass() != to.getActivityBehavior().getClass()) {
                problems.add("plan item '" + actId + "' changes kind: " + from.getActivityBehavior().getClass().getSimpleName()
                        + " -> " + to.getActivityBehavior().getClass().getSimpleName());
            }
            String fromParent = from == null ? null : stageId(from);
            String toParent = stageId(to);
            if (!Objects.equals(fromParent, toParent)) {
                problems.add("plan item '" + actId + "' moves from stage '" + fromParent + "' to '" + toParent + "'");
            }
            for (CaseSentryPartEntity part : e.getCaseSentryParts()) {
                if (to.getSentry(part.getSentryId()) == null) {
                    problems.add("sentry '" + part.getSentryId() + "' of '" + actId + "' does not exist in the target");
                }
                if (part.getSource() != null && target.findActivity(part.getSource()) == null) {
                    problems.add("sentry '" + part.getSentryId() + "' source '" + part.getSource() + "' does not exist in the target");
                }
            }
            rebind.put(e, to);
        }
        if (!problems.isEmpty()) {
            throw new CaseMigrationException(problems);
        }

        for (Map.Entry<CaseExecutionEntity, CmmnActivity> r : rebind.entrySet()) {
            r.getKey().setCaseDefinition(target);
            r.getKey().setActivity(r.getValue());
            r.getKey().forceUpdate();
        }
        for (Task t : engine.getTaskService().createTaskQuery().caseInstanceId(caseInstanceId).list()) {
            TaskEntity te = ctx.getTaskManager().findTaskById(t.getId());
            te.setCaseDefinitionId(target.getId());
        }

        MigrationRecord rec = new MigrationRecord(UUID.randomUUID().toString(), TenantKey.of(instance.getTenantId()),
                caseInstanceId, source.getId(), target.getId(), user, reason, all.size(),
                ClockUtil.getCurrentTime().toInstant());
        Jdbc.update(EngineTx.connection(), "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?)",
                rec.id(), rec.tenantId(), rec.caseInstanceId(), rec.sourceDefinitionId(), rec.targetDefinitionId(),
                rec.migratedBy(), truncate(rec.reason()), rec.executions(), rec.createdAt());
        return rec;
    }

    /** Audit rows of one case instance, oldest first (raw rows for the caller's tenant predicate). */
    public List<MigrationRecord> history(String caseInstanceId) {
        return EngineTx.transactions(engine).inTransaction(c -> Jdbc.query(c,
                "SELECT " + COLS + " FROM " + TABLE + " WHERE case_inst_id = ? ORDER BY created_at, id_",
                rs -> new MigrationRecord(rs.getString("id_"), rs.getString("tenant_id"), rs.getString("case_inst_id"),
                        rs.getString("source_def_id"), rs.getString("target_def_id"), rs.getString("migrated_by"),
                        rs.getString("reason"), rs.getInt("executions"), Jdbc.instant(rs, "created_at")),
                caseInstanceId));
    }

    /** Id of the enclosing stage; null at the top (the parent is the case definition, whose id is per version). */
    private static String stageId(CmmnActivity a) {
        CmmnActivity p = a.getParent();
        return p == null || p instanceof CmmnCaseDefinition ? null : p.getId();
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
