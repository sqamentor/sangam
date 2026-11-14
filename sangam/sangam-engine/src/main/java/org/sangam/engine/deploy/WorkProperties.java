// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;

/**
 * Decision 159 glue (W4-H REQUEST 2026-11-12T20:15:17Z): the {@code properties} of a fetched
 * {@link org.sangam.core.api.EngineAdapter.ExternalWork}. The activity's extension properties as Operaton fetched them
 * ({@code includeExtensionProperties}), plus:
 * <ul>
 *   <li>{@value SangamBpmnParseListener#OPERATION_REF_PROPERTY}: the pinned {@code contractId@version#operationId} of a
 *       {@code sangam:operationRef} activity (M-068), read from the deployed definition (never resolved again);</li>
 *   <li>{@value #PROCESS_DEFINITION_ID} and {@value #DEPLOYMENT_ID} of the task's definition.</li>
 * </ul>
 * An extension property cannot override these keys: they are written last. Runs inside the fetch command (deployment
 * cache access needs the command context).
 */
public final class WorkProperties {

    public static final String PROCESS_DEFINITION_ID = "sangam.processDefinitionId";
    public static final String DEPLOYMENT_ID = "sangam.deploymentId";

    private WorkProperties() {
    }

    public static Map<String, String> of(ProcessEngineConfigurationImpl cfg, LockedExternalTask t, Map<String, String> ext) {
        Map<String, String> out = new LinkedHashMap<>(ext == null ? Map.of() : ext);
        out.remove(SangamBpmnParseListener.OPERATION_REF_PROPERTY);
        out.remove(PROCESS_DEFINITION_ID);
        out.remove(DEPLOYMENT_ID);
        String defId = t.getProcessDefinitionId();
        if (defId != null) {
            out.put(PROCESS_DEFINITION_ID, defId);
            ProcessDefinitionEntity def = cfg.getDeploymentCache().findDeployedProcessDefinitionById(defId);
            if (def != null) {
                if (def.getDeploymentId() != null) {
                    out.put(DEPLOYMENT_ID, def.getDeploymentId());
                }
                ActivityImpl a = t.getActivityId() == null ? null : def.findActivity(t.getActivityId());
                Object ref = a == null ? null : a.getProperty(SangamBpmnParseListener.OPERATION_REF_PROPERTY);
                if (ref != null) {
                    out.put(SangamBpmnParseListener.OPERATION_REF_PROPERTY, ref.toString());
                }
            }
        }
        out.values().removeIf(java.util.Objects::isNull);
        return Map.copyOf(out);
    }
}
