// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;

/**
 * Starts a <b>linked</b> instance rather than a sub-process: appeal or reopen after closure (GP-22), part file /
 * volume (GP-54), discrepancy case from a verification (GP-55), continue-as-new of a long-lived entity (UP-08),
 * renewal of a licence, a revised sanction (GP-42). The new instance carries {@code parentBusinessKey},
 * {@code linkType} and the inherited variables, and the link is recorded on both sides for history lookup.
 *
 * <p>Fields: {@code processKey}, {@code linkType}, {@code businessKeySuffix} (default {@code linkType} + counter),
 * {@code inherit} (comma list; {@code *} = all), {@code resultVar} (default {@code <linkType>InstanceId}).
 */
public class LinkedInstanceDelegate implements JavaDelegate {

    private Expression processKey;
    private Expression linkType;
    private Expression businessKeySuffix;
    private Expression inherit;
    private Expression resultVar;

    @Override
    public void execute(DelegateExecution ex) {
        String key = Vars.field(processKey, ex);
        String type = Vars.field(linkType, ex, "link");
        int n = Vars.intVal(ex, type + "Count", 0) + 1;
        ex.setVariable(type + "Count", n);
        String parent = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        String bk = parent + "/" + Vars.field(businessKeySuffix, ex, type + "-" + n);
        Map<String, Object> vars = new HashMap<>();
        String inh = Vars.field(inherit, ex, "");
        if ("*".equals(inh)) {
            vars.putAll(ex.getVariables());
        } else {
            for (String v : Vars.csv(inh)) {
                vars.put(v, ex.getVariable(v));
            }
        }
        vars.put("parentBusinessKey", parent);
        vars.put("parentProcessInstanceId", ex.getProcessInstanceId());
        vars.put("linkType", type);
        if (Vars.tenant(ex) != null) {
            vars.put("tenantId", Vars.tenant(ex));
        }
        ProcessInstantiationBuilder b = ex.getProcessEngineServices().getRuntimeService()
                .createProcessInstanceByKey(key).businessKey(bk).setVariables(vars);
        if (ex.getTenantId() != null) {
            b = b.processDefinitionTenantId(ex.getTenantId());
        }
        ProcessInstance pi = b.execute();
        ex.setVariable(Vars.field(resultVar, ex, type + "InstanceId"), pi.getId());
        ex.setVariable(type + "BusinessKey", bk);
        Vars.audit(ex, "CASE_LINKED", Vars.map("linkType", type, "processKey", key, "businessKey", bk));
    }
}
