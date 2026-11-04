// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.util.List;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.migration.MigrationPlan;
import org.operaton.bpm.engine.repository.ProcessDefinition;

/**
 * Migration of running cases to a new pack version (GP-20: revised procedure after a GFR amendment). Uses Operaton's
 * migration API: activities with the same id are mapped (pack models keep ids stable across versions for this
 * reason), the migration runs as an engine batch, and nothing in the product layer changes. A case keeps its
 * {@code packVersion} variable until migrated; the migration writes {@code INSTANCE_MODIFIED} through the engine
 * history.
 */
public final class PackMigrator {

    private final ProcessEngine engine;

    public PackMigrator(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Plan from version {@code fromVersion} to {@code toVersion} of {@code processKey} (tenant may be null). */
    public MigrationPlan plan(String processKey, int fromVersion, int toVersion, String tenantId) {
        String src = definition(processKey, fromVersion, tenantId).getId();
        String tgt = definition(processKey, toVersion, tenantId).getId();
        return engine.getRuntimeService().createMigrationPlan(src, tgt).mapEqualActivities()
                .updateEventTriggers().build();
    }

    /** Migrates every running instance of the source version asynchronously; returns the engine batch. */
    public Batch migrateAll(String processKey, int fromVersion, int toVersion, String tenantId) {
        MigrationPlan plan = plan(processKey, fromVersion, toVersion, tenantId);
        return engine.getRuntimeService().newMigration(plan)
                .processInstanceQuery(engine.getRuntimeService().createProcessInstanceQuery()
                        .processDefinitionId(plan.getSourceProcessDefinitionId()))
                .skipCustomListeners().executeAsync();
    }

    /** Migrates the listed instances synchronously (small sets, tests). */
    public void migrate(String processKey, int fromVersion, int toVersion, String tenantId, List<String> instanceIds) {
        engine.getRuntimeService().newMigration(plan(processKey, fromVersion, toVersion, tenantId))
                .processInstanceIds(instanceIds).execute();
    }

    private ProcessDefinition definition(String key, int version, String tenantId) {
        RepositoryService repo = engine.getRepositoryService();
        var q = repo.createProcessDefinitionQuery().processDefinitionKey(key).processDefinitionVersion(version);
        q = tenantId == null ? q.withoutTenantId() : q.tenantIdIn(tenantId);
        ProcessDefinition d = q.singleResult();
        if (d == null) {
            throw new IllegalArgumentException("no definition " + key + " v" + version + " for tenant " + tenantId);
        }
        return d;
    }
}
