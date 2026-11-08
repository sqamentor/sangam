// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.migration;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.migration.MigrationInstructionBuilder;
import org.operaton.bpm.engine.migration.MigrationInstructionsBuilder;
import org.operaton.bpm.engine.migration.MigrationPlan;
import org.operaton.bpm.engine.migration.MigrationPlanBuilder;
import org.operaton.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Executes {@link MigrationDocument}s on Operaton's migration API (FLOWABLE-INTO-OPERATON FC-18, R48):
 * <ul>
 *   <li><b>Dry run</b> ({@link #dryRun}): builds and validates the plan (Operaton's validators) and counts the
 *       instances per source activity and per affected mapping, without changing anything.</li>
 *   <li><b>1:1 and N:1</b> map directly to Operaton instructions. <b>1:N</b>: the first target is a normal
 *       instruction; the extra targets are started by a modification right after the migration, in the same
 *       transaction, for each instance that had the source activity active.</li>
 *   <li><b>Pre/post hooks</b>: named {@link Hook}s run per instance before and after migration in the same
 *       transaction (Flowable pre/post upgrade script or delegate; scripts are not run in-engine, R6).</li>
 *   <li><b>Variables</b>: set on each migrated instance.</li>
 * </ul>
 * Per-instance transactions keep one failure from blocking the rest; large sets are run through
 * {@code ProductBatchService} (type {@code migration-document}) by {@link #asBatchHandler}. Flowable's historic-instance
 * migration is not available: Operaton history stays bound to the original definition (documented gap).
 */
public class MigrationDocumentService {

    /** Per-instance hook, Flowable pre/post upgrade delegate. */
    @FunctionalInterface
    public interface Hook {
        void run(ProcessEngine engine, String processInstanceId, MigrationDocument document);
    }

    public record DryRunReport(String sourceDefinitionId, String targetDefinitionId, boolean valid, List<String> errors,
                               long instances, Map<String, Long> instancesPerSourceActivity) {
    }

    public record Result(String processInstanceId, boolean migrated, String error) {
    }

    private final ProcessEngine engine;
    private final Function<String, Hook> hooks;

    public MigrationDocumentService(ProcessEngine engine, Function<String, Hook> hooks) {
        this.engine = engine;
        this.hooks = hooks;
    }

    public MigrationDocument parse(String json) {
        return Json.read(json, MigrationDocument.class);
    }

    public DryRunReport dryRun(MigrationDocument doc) {
        String src = resolve(doc.source());
        String tgt = resolve(doc.target());
        List<String> errors = new ArrayList<>();
        try {
            plan(doc, src, tgt);
        } catch (ProcessEngineException | IllegalArgumentException e) {
            errors.add(e.getMessage());   // invalid mappings (N:M, empty from/to) are a report finding, not a crash
        }
        Map<String, Long> perActivity = new LinkedHashMap<>();
        for (MigrationDocument.Mapping m : doc.mappings()) {
            for (String a : m.from()) {
                perActivity.put(a, engine.getRuntimeService().createProcessInstanceQuery().processDefinitionId(src).activityIdIn(a).count());
            }
        }
        long instances = doc.processInstanceIds().isEmpty()
                ? engine.getRuntimeService().createProcessInstanceQuery().processDefinitionId(src).count()
                : doc.processInstanceIds().size();
        return new DryRunReport(src, tgt, errors.isEmpty(), errors, instances, perActivity);
    }

    /** Migrates the document's instances (or every instance of the source definition), one transaction each. */
    public List<Result> execute(MigrationDocument doc) {
        String src = resolve(doc.source());
        String tgt = resolve(doc.target());
        MigrationPlan plan = plan(doc, src, tgt);
        List<String> ids = doc.processInstanceIds().isEmpty()
                ? engine.getRuntimeService().createProcessInstanceQuery().processDefinitionId(src).list().stream().map(ProcessInstance::getId).toList()
                : doc.processInstanceIds();
        List<Result> out = new ArrayList<>();
        for (String id : ids) {
            try {
                migrateOne(doc, plan, id);
                out.add(new Result(id, true, null));
            } catch (RuntimeException e) {
                out.add(new Result(id, false, e.getMessage()));
            }
        }
        return out;
    }

    public void migrateOne(MigrationDocument doc, MigrationPlan plan, String processInstanceId) {
        EngineTx.execute(engine, cc -> {
            Map<String, Boolean> activeBefore = new LinkedHashMap<>();
            for (MigrationDocument.Mapping m : doc.mappings()) {
                if (m.to().size() > 1) {
                    for (String a : m.from()) {
                        activeBefore.put(a, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId)
                                .activityIdIn(a).count() > 0);
                    }
                }
            }
            if (doc.preHook() != null) {
                hook(doc.preHook()).run(engine, processInstanceId, doc);
            }
            MigrationPlanExecutionBuilder exec = engine.getRuntimeService().newMigration(plan).processInstanceIds(processInstanceId);
            if (Boolean.TRUE.equals(doc.skipCustomListeners())) {
                exec.skipCustomListeners();
            }
            if (Boolean.TRUE.equals(doc.skipIoMappings())) {
                exec.skipIoMappings();
            }
            exec.execute();
            for (MigrationDocument.Mapping m : doc.mappings()) {
                if (m.to().size() > 1 && m.from().stream().anyMatch(a -> Boolean.TRUE.equals(activeBefore.get(a)))) {
                    var mod = engine.getRuntimeService().createProcessInstanceModification(processInstanceId);
                    for (String extra : m.to().subList(1, m.to().size())) {
                        mod.startBeforeActivity(extra);
                    }
                    mod.setAnnotation("migration 1:N " + m.from() + " -> " + m.to()).execute();
                }
            }
            if (!doc.variables().isEmpty()) {
                engine.getRuntimeService().setVariables(processInstanceId, doc.variables());
            }
            if (doc.postHook() != null) {
                hook(doc.postHook()).run(engine, processInstanceId, doc);
            }
            return null;
        });
    }

    /** Batch handler: item = process instance id, config {@code document} (the JSON). */
    public org.sangam.ports.batch.ProductBatchService.ItemHandler asBatchHandler() {
        return (tenant, ref, cfg) -> {
            MigrationDocument doc = parse(String.valueOf(cfg.get("document")));
            migrateOne(doc, plan(doc, resolve(doc.source()), resolve(doc.target())), ref);
            return "migrated";
        };
    }

    MigrationPlan plan(MigrationDocument doc, String src, String tgt) {
        MigrationPlanBuilder b = engine.getRuntimeService().createMigrationPlan(src, tgt);
        boolean events = Boolean.TRUE.equals(doc.updateEventTriggers());
        if (!Boolean.FALSE.equals(doc.mapEqualActivities())) {
            MigrationInstructionsBuilder eq = b.mapEqualActivities();
            if (events) {
                eq.updateEventTriggers();
            }
            b = eq;
        }
        for (MigrationDocument.Mapping m : doc.mappings()) {
            if (m.from() == null || m.from().isEmpty() || m.to() == null || m.to().isEmpty()) {
                throw new IllegalArgumentException("mapping needs from and to");
            }
            if (m.from().size() > 1 && m.to().size() > 1) {
                throw new IllegalArgumentException("N:M mappings are not supported: " + m);
            }
            for (String from : m.from()) {
                MigrationInstructionBuilder ib = b.mapActivities(from, m.to().get(0));
                if (events) {
                    ib.updateEventTrigger();
                }
                b = ib;
            }
        }
        return b.build();
    }

    String resolve(MigrationDocument.DefinitionRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("source and target are required");
        }
        if (ref.id() != null) {
            return ref.id();
        }
        var q = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(ref.key());
        if (ref.tenantId() != null) {
            q.tenantIdIn(ref.tenantId());
        }
        ProcessDefinition d = ref.version() == null ? q.latestVersion().singleResult() : q.processDefinitionVersion(ref.version()).singleResult();
        if (d == null) {
            throw new ProcessEngineException("process definition " + ref.key() + " v" + ref.version() + " not found");
        }
        return d.getId();
    }

    private Hook hook(String name) {
        Hook h = hooks == null ? null : hooks.apply(name);
        if (h == null) {
            throw new ProcessEngineException("migration hook '" + name + "' is not registered");
        }
        return h;
    }
}
