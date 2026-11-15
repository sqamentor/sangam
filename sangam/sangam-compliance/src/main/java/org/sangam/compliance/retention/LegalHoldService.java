// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.retention;

import java.sql.Connection;
import java.util.List;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.ports.CompliancePorts.ActiveHold;
import org.sangam.compliance.ports.CompliancePorts.LegalHoldPort;
import org.sangam.compliance.ports.CompliancePorts.PersonalDataPort;
import org.sangam.compliance.support.Sql;

/**
 * Owner decision 361: a legal hold placed on (and released from) a process root through the API, over the existing
 * legal-hold store ({@link LegalHoldPort}, integration {@code LegalHoldStore}, which {@code ErasureService} and the
 * retention scheduler already honour). SANGAM.md X.2.3.5 #5: the kind is one of {@link Kind}; placing and releasing
 * are audited on the root's chain ({@code LEGAL_HOLD_SET}, {@code LEGAL_HOLD_RELEASED}, SANGAM.md X.4.0.3 Records);
 * release needs a different person from the one who placed the hold. A root must be a process root of the tenant
 * ({@link PersonalDataPort#isProcessRoot}); without the port nothing can be held (fail closed).
 */
public final class LegalHoldService {

    /** SANGAM.md X.2.3.5 #5. */
    public enum Kind { RTI, AUDIT, LITIGATION, VIGILANCE, OTHER }

    public static final String LEGAL_HOLD_SET = "LEGAL_HOLD_SET";
    public static final String LEGAL_HOLD_RELEASED = "LEGAL_HOLD_RELEASED";

    private final ComplianceAudit audit;
    private final LegalHoldPort holds;
    private final PersonalDataPort personalData;

    public LegalHoldService(ComplianceAudit audit, LegalHoldPort holds, PersonalDataPort personalData) {
        this.audit = audit;
        this.holds = holds;
        this.personalData = personalData;
    }

    /** Places a hold; returns it. The stored reason is {@code KIND reference: reason}. */
    public ActiveHold place(Connection c, String tenantId, String rootId, String kind, String reference, String reason,
                            String actor) {
        Sql.requireText(rootId, "rootId");
        Kind k = kind(kind);
        Sql.requireText(reason, "reason");
        Sql.requireText(actor, "actor");
        requireRoot(c, tenantId, rootId);
        String holdId = Sql.newId();
        String ref = reference == null || reference.isBlank() ? "" : " " + reference.strip();
        String stored = k.name() + ref + ": " + reason.strip();
        holds.place(c, tenantId, rootId, holdId, stored, actor);
        audit.root(c, tenantId, rootId, LEGAL_HOLD_SET, Sql.kv("hold", holdId, "kind", k.name(),
                "reference", reference == null ? "" : reference.strip(), "reason", reason.strip(), "actor", actor));
        return active(c, tenantId, rootId).stream().filter(h -> h.holdId().equals(holdId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("the hold store did not keep hold " + holdId));
    }

    public List<ActiveHold> active(Connection c, String tenantId, String rootId) {
        Sql.requireText(rootId, "rootId");
        requireRoot(c, tenantId, rootId);
        return holds.active(c, tenantId, rootId);
    }

    /** Releases an active hold; the releaser must not be the person who placed it (four eyes). */
    public void release(Connection c, String tenantId, String rootId, String holdId, String reason, String actor) {
        Sql.requireText(holdId, "holdId");
        Sql.requireText(reason, "reason");
        Sql.requireText(actor, "actor");
        ActiveHold h = active(c, tenantId, rootId).stream().filter(x -> x.holdId().equals(holdId)).findFirst()
                .orElseThrow(() -> ComplianceException.notFound("active legal hold", holdId));
        if (h.heldBy() == null) {
            throw new ComplianceException(Code.FOUR_EYES_REQUIRED, "the hold store does not record who placed hold " + holdId
                    + ", so a different person cannot be checked");
        }
        if (h.heldBy().equals(actor)) {
            throw new ComplianceException(Code.FOUR_EYES_REQUIRED, "a legal hold is released by a different person from the one"
                    + " who placed it");
        }
        if (!holds.release(c, tenantId, rootId, holdId, actor)) {
            throw new ComplianceException(Code.INVALID_STATE, "legal hold " + holdId + " is no longer active");
        }
        audit.root(c, tenantId, rootId, LEGAL_HOLD_RELEASED, Sql.kv("hold", holdId, "reason", reason.strip(),
                "placedBy", h.heldBy(), "actor", actor));
    }

    private void requireRoot(Connection c, String tenantId, String rootId) {
        if (personalData == null || !personalData.isProcessRoot(c, tenantId, rootId)) {
            throw ComplianceException.notFound("process root", rootId);
        }
    }

    static Kind kind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw ComplianceException.invalid("kind is required: one of " + List.of(Kind.values()));
        }
        try {
            return Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw ComplianceException.invalid("kind must be one of " + List.of(Kind.values()));
        }
    }

}
