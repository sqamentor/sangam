// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstance;

/**
 * Message to another instance of the <b>same tenant</b> (child to parent in the per-item pattern, part file to main
 * file, waitlist promotion to an applicant). Cross-tenant traffic never uses this: it goes through the
 * {@code referral.send} connector to the other tenant's inbox (AC-09, J-P2-E09).
 *
 * <p>Fields: {@code messageName}; {@code targetBusinessKey} (expression; default the {@code parentBusinessKey});
 * {@code variables} (comma list copied as message variables, {@code name:alias} renames); {@code optional} (true:
 * no waiting receiver is not an error).
 */
public class CorrelationDelegate implements JavaDelegate {

    private Expression messageName;
    private Expression targetBusinessKey;
    private Expression variables;
    private Expression optional;

    @Override
    public void execute(DelegateExecution ex) {
        String msg = Vars.field(messageName, ex);
        String bk = Vars.field(targetBusinessKey, ex, Vars.str(ex, "parentBusinessKey"));
        if (bk == null) {
            if (Vars.fieldBool(optional, ex, false)) {
                return; // started standalone: nobody to tell
            }
            throw new ProcessEngineException("no target business key for message " + msg);
        }
        Map<String, Object> vars = new HashMap<>();
        for (String v : Vars.csv(Vars.field(variables, ex))) {
            String[] p = v.split(":");
            vars.put(p.length > 1 ? p[1] : p[0], ex.getVariable(p[0]));
        }
        MessageCorrelationBuilder b = ex.getProcessEngineServices().getRuntimeService()
                .createMessageCorrelation(msg).processInstanceBusinessKey(bk).setVariables(vars);
        if (ex.getTenantId() != null) {
            b = b.tenantId(ex.getTenantId());
        }
        try {
            b.correlateWithResult();
        } catch (org.operaton.bpm.engine.MismatchingMessageCorrelationException e) {
            if (!Vars.fieldBool(optional, ex, false)) {
                throw e;
            }
        }
        Vars.audit(ex, "MESSAGE_SENT", Vars.map("message", msg, "to", bk));
    }

    /** Helper for Java callers: is any instance with this business key still running? */
    public static boolean running(DelegateExecution ex, String businessKey) {
        ProcessInstance pi = ex.getProcessEngineServices().getRuntimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).active().listPage(0, 1).stream().findFirst().orElse(null);
        return pi != null;
    }
}
