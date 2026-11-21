// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.time.Instant;
import java.util.Date;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;

import org.operaton.bpm.engine.ProcessEngineException;
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
 *   <li>gives a case without an {@code officeId} the office of the starting seat, audited as
 *       {@code CASE_OFFICE_ASSIGNED} (IX.5 rule 7, {@link #assignStarterOffice});</li>
 *   <li>stores the material field list and the submitted payload hash (K4) and starts {@code caseVersion} at 1;</li>
 *   <li>starts the case-level statutory clock {@code caseDueAt} when {@code caseSla} is given (R31, GP-35);</li>
 *   <li>decision 394: refuses the start ({@code 422 VALIDATION_FAILED}) when a field named in {@code durationFields}
 *       (the start form's fields of type {@code duration}, written by packgen) has a value that is not a positive
 *       ISO-8601 duration, read with the parser Operaton's timers use ({@link javax.xml.datatype.DatatypeFactory});
 *       an absent field is left to the model's default;</li>
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
    private Expression durationFields;

    @Override
    public void notify(DelegateExecution ex) {
        // decision 145: the engine tenant, resolved before anything is written; a forged tenantId start variable or a
        // case without an engine tenant stops here
        String tenant = Vars.tenant(ex);
        requireDurations(ex, Vars.field(durationFields, ex));
        String id = Vars.field(packId, ex, "unknown");
        String ver = Vars.field(packVersion, ex, "0");
        // IFT-6: a pack process called by another pack for the same case (same business key, e.g. bill -> PAO chain,
        // a bill family -> the bill) joins the caller's case: the case was created by the caller, so its package,
        // version, state, actor log and submitted hash are kept (they flow back to the caller through the call's
        // out-mapping, and the audit rows of this case keep naming the caller's package); CASE_CREATED is written once
        boolean joinsCase = joinsCallersCase(ex);
        if (!joinsCase) {
            ex.setVariable(Vars.PACK_VERSION, id + "@" + ver);
            ex.setVariable("packId", id);
        }
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
        if (joinsCase) {
            return;
        }
        assignStarterOffice(ex, tenant);
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

    /**
     * SANGAM.md IX.5 rule 7 (owner decision 2026-11-21, "Starter seat's office"): a case started without an
     * {@code officeId} takes the office (org unit) of the seat that starts it, the seat acted with (the REST layer's
     * {@code X-Sangam-Seat} or acted-for seat), and the assignment is audited. A supplied office is kept. A starter
     * without a seat, or with a seat the directory gives no office, leaves the case without one: nothing is invented,
     * and the first seat step is refused ({@code 422 SEAT_UNRESOLVED}) unless the pack itself sets the office.
     */
    static void assignStarterOffice(DelegateExecution ex, String tenant) {
        String office = Vars.str(ex, "officeId");
        if (office != null && !office.isBlank()) {
            return;
        }
        String seat = Vars.str(ex, "actingSeat");
        if (seat == null || seat.isBlank()) {
            seat = Vars.str(ex, Vars.INITIATOR_SEAT);
        }
        if (seat == null || seat.isBlank()) {
            return;
        }
        String starterOffice = PackRuntime.seats().officeOf(tenant, seat).filter(o -> !o.isBlank()).orElse(null);
        if (starterOffice == null) {
            return;
        }
        ex.setVariable("officeId", starterOffice);
        Vars.audit(ex, "CASE_OFFICE_ASSIGNED", Vars.map("officeId", starterOffice, "seat", seat,
                "basis", "starter seat"));
    }

    /** IFT-6: started by a call activity of another process instance with the same business key (the same case). */
    static boolean joinsCallersCase(DelegateExecution ex) {
        DelegateExecution sup = ex.getSuperExecution();
        return sup != null && ex.getProcessBusinessKey() != null
                && ex.getProcessBusinessKey().equals(sup.getProcessBusinessKey());
    }

    /** Decision 394: every named field that has a value holds a positive ISO-8601 duration, else the start is refused. */
    static void requireDurations(DelegateExecution ex, String names) {
        if (names == null) {
            return;
        }
        for (String name : names.split(",")) {
            String n = name.trim();
            Object v = n.isEmpty() ? null : ex.getVariable(n);
            if (v == null || v.toString().isBlank()) {
                continue;
            }
            if (!isPositiveDuration(v.toString().trim())) {
                throw new ProcessEngineException("422 VALIDATION_FAILED: " + n + " must be an ISO-8601 duration counted "
                        + "from the start, for example P15D");
            }
        }
    }

    static boolean isPositiveDuration(String s) {
        if (s.length() > 64 || !s.startsWith("P")) {
            return false;
        }
        try {
            return DatatypeFactory.newInstance().newDuration(s).getSign() > 0;
        } catch (IllegalArgumentException | DatatypeConfigurationException e) {
            return false;
        }
    }
}
