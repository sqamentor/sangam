// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Optional;
import java.util.function.Supplier;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Jdbc;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.rest.support.DocumentSlots;
import org.sangam.rest.support.TxSupport;

/**
 * Owner decisions 267 to 269 on Operaton: the engine side of {@link DocumentSlots}. The pack of a process instance is
 * the pack whose deployment holds its definition; {@code PackDeployer} names that deployment {@code pack:<id>@<version>}
 * (source {@code sangam-packs}). The slot pairs are the pack manifest's {@code documentSlots}
 * ({@link org.sangam.packs.spi.PackDescriptor#slotFor(String)}); there is no server table. A definition outside a pack,
 * an ended or unknown instance, or another tenant's instance has no slot. The slot is a process-instance variable, the
 * scope the DSC gate reads it from. The lock is a row lock on the instance's execution row, in the transaction the
 * engine writes the variable in.
 */
public final class OperatonDocumentSlots implements DocumentSlots.Instances {

    static final String NAME_PREFIX = "pack:";
    static final String SOURCE = org.sangam.packs.spi.PackDeployer.SOURCE;

    private final RuntimeService runtime;
    private final TaskService tasks;
    private final RepositoryService repository;
    private final PackRegistry packs;
    private final TxSupport tx;

    public OperatonDocumentSlots(RuntimeService runtime, TaskService tasks, RepositoryService repository,
                                 PackRegistry packs, TxSupport tx) {
        this.runtime = runtime;
        this.tasks = tasks;
        this.repository = repository;
        this.packs = packs;
        this.tx = tx;
    }

    @Override
    public Optional<String> slotFor(String tenantId, String processInstanceId, String documentType) {
        ProcessInstance pi = runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (pi == null || tenantId == null || !tenantId.equals(pi.getTenantId())) {
            return Optional.empty();
        }
        ProcessDefinition def = repository.getProcessDefinition(pi.getProcessDefinitionId());
        Deployment dep = def == null ? null
                : repository.createDeploymentQuery().deploymentId(def.getDeploymentId()).singleResult();
        if (dep == null || !SOURCE.equals(dep.getSource())) {
            return Optional.empty();
        }
        return packId(dep.getName()).flatMap(packs::find).flatMap(p -> p.descriptor().slotFor(documentType));
    }

    /** The pack id of a deployment name {@code pack:<id>@<version>}; empty for any other name. */
    static Optional<String> packId(String deploymentName) {
        if (deploymentName == null || !deploymentName.startsWith(NAME_PREFIX)) {
            return Optional.empty();
        }
        String coordinates = deploymentName.substring(NAME_PREFIX.length());
        int at = coordinates.lastIndexOf('@');
        return at <= 0 ? Optional.empty() : Optional.of(coordinates.substring(0, at));
    }

    @Override
    public String processInstanceOfTask(String tenantId, String taskId) {
        Task t = tasks.createTaskQuery().taskId(taskId).singleResult();
        return t == null || tenantId == null || !tenantId.equals(t.getTenantId()) ? null : t.getProcessInstanceId();
    }

    @Override
    public boolean holdsOpenTask(String tenantId, String processInstanceId, String principalId) {
        if (tenantId == null || principalId == null) {
            return false;
        }
        return tasks.createTaskQuery().processInstanceId(processInstanceId).tenantIdIn(tenantId)
                .taskAssignee(principalId).active().count() > 0;
    }

    @Override
    public Optional<String> slotValue(String processInstanceId, String slot) {
        Object v = runtime.getVariable(processInstanceId, slot);
        return v == null || v.toString().isBlank() ? Optional.empty() : Optional.of(v.toString());
    }

    @Override
    public void setSlot(String processInstanceId, String slot, String documentId) {
        runtime.setVariable(processInstanceId, slot, documentId);
    }

    @Override
    public void lock(String processInstanceId) {
        tx.withConnection(c -> Jdbc.query(c, tx.dialect().lockingSelect("ID_", "ACT_RU_EXECUTION", "ID_ = ?"),
                rs -> rs.getString(1), processInstanceId));
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return tx.inTx(work);
    }
}
