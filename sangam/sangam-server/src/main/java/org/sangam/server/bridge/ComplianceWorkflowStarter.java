// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.engine.DocumentSlotNames;

/**
 * {@link CompliancePorts.WorkflowStarter} on the engine: starts the compliance pack's processes (rights requests,
 * breaches, incidents, lawful requests). The caller's transaction is the engine transaction
 * ({@code Transactions.of(engineAdapter)}), so the start commits with the compliance rows. A tenant-specific
 * definition is preferred; otherwise the shared ({@code tenantId = null}) pack definition is used.
 *
 * <p>Owner decision 336: the processes come only from the installed compliance pack ({@code sangam.compliance},
 * deployed for the tenant by the pack installation, {@code PacksTenantDeployer}; the server never deploys class-path
 * models on its own, {@code operaton.bpm.auto-deployment-enabled: false}). When neither a tenant nor a shared
 * definition of the key exists, the start is refused with {@code COMPLIANCE_PACK_NOT_INSTALLED} before the engine is
 * asked, so the caller's transaction rolls back with a named code instead of the engine's "no processes deployed".
 */
public final class ComplianceWorkflowStarter implements CompliancePorts.WorkflowStarter {

    /** The pack whose processes this starter starts (sangam-compliance {@code packs/compliance/pack.json}). */
    public static final String PACK_ID = "sangam.compliance";

    private final ProcessEngine engine;

    public ComplianceWorkflowStarter(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public String start(Connection c, String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        var definitions = engine.getRepositoryService();
        boolean tenantDefinition = tenantId != null && definitions.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).tenantIdIn(tenantId).latestVersion().count() > 0;
        if (!tenantDefinition && definitions.createProcessDefinitionQuery().processDefinitionKey(processKey)
                .withoutTenantId().latestVersion().count() == 0) {
            throw new ComplianceException(ComplianceException.Code.COMPLIANCE_PACK_NOT_INSTALLED, "process " + processKey
                    + " is not installed for the tenant: install the compliance pack " + PACK_ID
                    + " through the tenant's pack installation");
        }
        DocumentSlotNames.refuse(variables == null ? null : variables.keySet()); // decision 385 (2)
        var builder = engine.getRuntimeService().createProcessInstanceByKey(processKey);
        builder = tenantDefinition ? builder.processDefinitionTenantId(tenantId) : builder.processDefinitionWithoutTenantId();
        return builder.businessKey(businessKey).setVariables(variables == null ? Map.of() : variables).execute().getId();
    }
}
