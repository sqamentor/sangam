// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.time.Instant;
import java.util.Date;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;

import org.sangam.packs.port.PackRuntime;

/**
 * Process start listener of every pack's main process (DRAFT to SUBMITTED, X.4.2.1).
 *
 * <ul>
 *   <li>resolves the case's tenant from the engine tenant only, failing closed (decision 145);</li>
 *   <li>binds the pack id and version to the case (R43, K10) as {@code packVersion = id@version};</li>
 *   <li>records the initiator person and seat for segregation of duties (K5): the REST layer passes
 *       {@code initiatorPerson} (or Operaton's {@code operaton:initiator}) and {@code actingSeat};</li>
 *   <li>stores the material field list and the submitted payload hash (K4) and starts {@code caseVersion} at 1;</li>
 *   <li>starts the case-level statutory clock {@code caseDueAt} when {@code caseSla} is given (R31, GP-35);</li>
 *   <li>writes {@code CASE_CREATED} (decision 299: the case exists, with its bound package version and initial
 *       state), then the audit event of entering the initial state ({@code CASE_SUBMITTED} by default).</li>
 * </ul>
 */
public class PackStartListener implements ExecutionListener {

    private Expression packId;
    private Expression packVersion;
    private Expression materialFields;
    private Expression caseSla;
    private Expression initialState;

    @Override
    public void notify(DelegateExecution ex) {
        // decision 145: the engine tenant, resolved before anything is written; a forged tenantId start variable or a
        // case without an engine tenant stops here
        String tenant = Vars.tenant(ex);
        String id = Vars.field(packId, ex, "unknown");
        String ver = Vars.field(packVersion, ex, "0");
        ex.setVariable(Vars.PACK_VERSION, id + "@" + ver);
        ex.setVariable("packId", id);
        if (ex.getVariable(Vars.TENANT_VARIABLE) == null) {
            // business data for forms and reports, copied from the engine tenant; never read as the tenant
            ex.setVariable(Vars.TENANT_VARIABLE, tenant);
        }
        if (ex.getVariable("profileVersion") == null) {
            // configuration chain in force at start (ADR-D-20); kept for the life of the case (GP-19 semantics)
            String binding = org.sangam.packs.port.ProfileBindings.bind(Vars.tenant(ex), id, ex.getProcessBusinessKey());
            if (binding != null) {
                ex.setVariable("profileVersion", binding);
                Vars.audit(ex, "PROFILE_BOUND", Vars.map("profileVersion", binding));
            }
        }
        String mf = Vars.field(materialFields, ex);
        if (mf != null && ex.getVariable(Vars.MATERIAL_FIELDS) == null) {
            ex.setVariable(Vars.MATERIAL_FIELDS, mf);
        }
        if (ex.getVariable(Vars.INITIATOR_PERSON) == null) {
            Object init = ex.getVariable("initiator");
            ex.setVariable(Vars.INITIATOR_PERSON, init == null ? "system" : init.toString());
        }
        if (ex.getVariable(Vars.INITIATOR_SEAT) == null) {
            ex.setVariable(Vars.INITIATOR_SEAT, Vars.str(ex, "actingSeat", "citizen"));
        }
        ex.setVariable("lastActorPerson", Vars.str(ex, Vars.INITIATOR_PERSON));
        ex.setVariable("lastActorSeat", Vars.str(ex, Vars.INITIATOR_SEAT));
        ex.setVariable(Vars.CASE_VERSION, 1);
        Vars.setJsonDocument(ex, Vars.ACTOR_LOG, java.util.List.of(Vars.map("step", "initiate",
                "person", Vars.str(ex, Vars.INITIATOR_PERSON), "seat", Vars.str(ex, Vars.INITIATOR_SEAT),
                "at", Vars.now().toString())));
        ex.setVariable("submittedPayloadHash", Vars.materialHash(ex));
        String sla = Vars.field(caseSla, ex);
        if (sla == null) {
            sla = Vars.str(ex, "caseSla");
        }
        if (sla != null && ex.getVariable(Vars.CASE_DUE_AT) == null) {
            Instant due = PackRuntime.calendar().due(Vars.tenant(ex), Vars.now(), sla);
            ex.setVariable("caseSla", sla);
            ex.setVariable(Vars.CASE_DUE_AT, Date.from(due));
            Vars.audit(ex, "SLA_STARTED", Vars.map("clock", "case", "spec", sla, "dueAt", due.toString()));
        }
        CaseState st = CaseState.valueOf(Vars.field(initialState, ex, "SUBMITTED"));
        ex.setVariable(Vars.CASE_STATE, st.name());
        Vars.audit(ex, "CASE_CREATED", Vars.map("package", id + "@" + ver, "initialState", st.name(),
                "profileVersion", ex.getVariable("profileVersion")));
        Vars.audit(ex, st.auditEvent(), Vars.map("package", id + "@" + ver,
                "payloadHash", Vars.str(ex, "submittedPayloadHash")));
    }
}
