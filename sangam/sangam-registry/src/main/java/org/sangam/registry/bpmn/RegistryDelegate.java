// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.registry.RegistryModule;
import org.sangam.registry.instrument.InstrumentService;
import org.sangam.registry.kernel.RegisteredObject;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.registry.register.RegisterBookService;
import org.sangam.registry.support.Maps;

import java.util.Map;

/**
 * BPMN building block for the kernel, numbering, register books and instruments. Field {@code op}:
 * <ul>
 *   <li>{@code ALLOCATE_NUMBER}: fields {@code series}, {@code scope}; sets {@code registryNumber};</li>
 *   <li>{@code CREATE_OBJECT}: field {@code request} (a variable holding the create map); sets {@code registryObjectId},
 *       {@code registryObjectNumber};</li>
 *   <li>{@code TRANSITION}: fields {@code objectId}, {@code transition}, {@code reason}; sets {@code registryObjectState};</li>
 *   <li>{@code REGISTER_ENTRY}: fields {@code register}, {@code scope}, {@code request} (columns map); sets
 *       {@code registerEntryNumber}, {@code registerBookId}, {@code registerEntryNo};</li>
 *   <li>{@code ISSUE}: field {@code request} (an issue map, e.g. built by the application's closing step); sets
 *       {@code instrumentId}, {@code instrumentNumber}, {@code verificationCode}, {@code qrToken}, {@code documentRef};</li>
 *   <li>{@code RENEW}, {@code SUSPEND}, {@code REINSTATE}, {@code REVOKE}, {@code SURRENDER}, {@code EXPIRE},
 *       {@code TRANSFER_HOLDER}, {@code AMEND}, {@code DUPLICATE}, {@code DEEMED_RENEWAL}: field {@code objectId},
 *       plus {@code reason} / {@code request} as needed;</li>
 *   <li>{@code EXPIRE_DUE}, {@code RENEWAL_DUE}, {@code REINSTATE_DUE}: sweep jobs (timer start events) with
 *       optional {@code typeKey} and {@code limit}; set {@code sweepProcessed}.</li>
 * </ul>
 * Field values may name process variables (the variable's value is used).
 */
public class RegistryDelegate implements JavaDelegate {

    private Expression op;
    private Expression series;
    private Expression scope;
    private Expression request;
    private Expression objectId;
    private Expression transition;
    private Expression reason;
    private Expression register;
    private Expression typeKey;
    private Expression limit;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = DelegateSupport.field(op, ex, "");
        RegistryModule m = DelegateSupport.module();
        DelegateSupport.run(ex, (c, ctx) -> {
            switch (operation) {
                case "ALLOCATE_NUMBER" -> {
                    NumberingService.Allocation a = m.numbering().allocate(c, ctx, val(series, ex), val(scope, ex), null,
                            Map.of(), "process", ex.getProcessInstanceId());
                    ex.setVariable("registryNumber", a.formatted());
                }
                case "CREATE_OBJECT" -> {
                    RegisteredObject o = m.objects().create(c, ctx, RegisteredObjectService.CreateRequest.from(req(ex)));
                    ex.setVariable("registryObjectId", o.id());
                    ex.setVariable("registryObjectNumber", o.number());
                }
                case "TRANSITION" -> {
                    RegisteredObject o = m.objects().transition(c, ctx, val(objectId, ex), null, val(transition, ex),
                            val(reason, ex), null, null);
                    ex.setVariable("registryObjectState", o.state());
                }
                case "REGISTER_ENTRY" -> {
                    Map<String, Object> r = req(ex);
                    RegisterBookService.Entry e = m.registers().append(c, ctx, val(register, ex), val(scope, ex),
                            Maps.date(r, "date"), r.containsKey("columns") ? Maps.map(r, "columns") : r,
                            Maps.str(r, "refType", "process"), Maps.str(r, "refId", ex.getProcessBusinessKey()));
                    ex.setVariable("registerEntryNumber", e.entryNumber());
                    ex.setVariable("registerBookId", e.bookId());
                    ex.setVariable("registerEntryNo", e.entryNo());
                }
                case "ISSUE" -> issued(ex, m.instruments().issue(c, ctx, InstrumentService.IssueRequest.from(req(ex))));
                case "RENEW" -> {
                    Map<String, Object> r = req(ex);
                    issued(ex, m.instruments().renew(c, ctx, val(objectId, ex), null, Maps.date(r, "validTo"),
                            Maps.map(r, "attributes"), val(reason, ex), Maps.bool(r, "force", false)));
                }
                case "DEEMED_RENEWAL" -> {
                    InstrumentService.Issued i = m.instruments().deemedRenewal(c, ctx, val(objectId, ex));
                    ex.setVariable("deemedRenewed", i != null);
                    if (i != null) {
                        issued(ex, i);
                    }
                }
                case "SUSPEND" -> state(ex, m.instruments().suspend(c, ctx, val(objectId, ex), null, val(reason, ex),
                        Maps.instant(req(ex), "until")));
                case "REINSTATE" -> state(ex, m.instruments().reinstate(c, ctx, val(objectId, ex), null, val(reason, ex)));
                case "REVOKE" -> state(ex, m.instruments().revoke(c, ctx, val(objectId, ex), null, val(reason, ex),
                        Maps.str(req(ex), "countersignedBy")));
                case "SURRENDER" -> state(ex, m.instruments().surrender(c, ctx, val(objectId, ex), null, val(reason, ex)));
                case "EXPIRE" -> state(ex, m.instruments().expire(c, ctx, val(objectId, ex)));
                case "TRANSFER_HOLDER" -> issued(ex, m.instruments().transferHolder(c, ctx, val(objectId, ex), null,
                        Maps.required(req(ex), "newHolderPartyId"), val(reason, ex)));
                case "AMEND" -> {
                    Map<String, Object> r = req(ex);
                    issued(ex, m.instruments().amend(c, ctx, val(objectId, ex), null, Maps.map(r, "attributes"),
                            Maps.required(r, "endorsement")));
                }
                case "DUPLICATE" -> issued(ex, m.instruments().duplicate(c, ctx, val(objectId, ex), val(reason, ex)));
                case "EXPIRE_DUE" -> ex.setVariable("sweepProcessed",
                        m.instruments().expireDue(c, ctx, val(typeKey, ex), lim(ex)).processed());
                case "RENEWAL_DUE" -> ex.setVariable("sweepProcessed",
                        m.instruments().renewalDue(c, ctx, val(typeKey, ex), lim(ex)).processed());
                case "REINSTATE_DUE" -> ex.setVariable("sweepProcessed",
                        m.instruments().reinstateDue(c, ctx, val(typeKey, ex), lim(ex)).processed());
                default -> throw new ProcessEngineException("unknown registry op: " + operation);
            }
            return null;
        });
    }

    private void issued(DelegateExecution ex, InstrumentService.Issued i) {
        ex.setVariable("instrumentId", i.instrument().id());
        ex.setVariable("instrumentNumber", i.instrument().number());
        ex.setVariable("instrumentState", i.instrument().state());
        ex.setVariable("instrumentValidTo", i.instrument().validTo() == null ? null : i.instrument().validTo().toString());
        if (i.verificationCode() != null) {
            ex.setVariable("verificationCode", i.verificationCode());
        }
        if (i.qrToken() != null) {
            ex.setVariable("qrToken", i.qrToken());
        }
        if (i.documentRef() != null) {
            ex.setVariable("documentRef", i.documentRef());
        }
    }

    private void state(DelegateExecution ex, RegisteredObject o) {
        ex.setVariable("instrumentState", o.state());
    }

    private Map<String, Object> req(DelegateExecution ex) {
        Object v = DelegateSupport.valueOf(request, ex);
        if (v instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) v;
            return new java.util.LinkedHashMap<>(mm);
        }
        if (v instanceof String s && s.trim().startsWith("{")) {
            return org.sangam.registry.support.Json.map(s);
        }
        return new java.util.LinkedHashMap<>();
    }

    private static String val(Expression e, DelegateExecution ex) {
        Object v = DelegateSupport.valueOf(e, ex);
        return v == null ? null : v.toString();
    }

    private int lim(DelegateExecution ex) {
        String l = val(limit, ex);
        return l == null ? 500 : Integer.parseInt(l);
    }
}
