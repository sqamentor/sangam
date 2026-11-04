// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.compliance.support.Sql;

import java.sql.Connection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Child safeguards (IN: DPDP s.9(2)-(3), r.12, Fourth Schedule; CMP-030, CMP-031, CMP-071, CMP-072):
 * processing detrimental to a child's well-being and targeted advertising are always refused; tracking and
 * behavioural monitoring are refused unless the purpose declares a pack child exemption whose {@code covers} includes
 * the flag. An unknown category is treated as a possible child when the operation carries a restricted flag (fail
 * closed), so age is established first.
 */
public final class ChildDataGuard {

    private final ComplianceAudit audit;

    public ChildDataGuard(ComplianceAudit audit) {
        this.audit = audit;
    }

    /** Whether the guardian-consent requirement is lifted for this purpose by a pack child exemption. */
    public static boolean guardianConsentExempt(JurisdictionPack pack, PurposeDefinition purpose) {
        return purpose.childExemptions().stream().anyMatch(e -> pack.childExemption(e)
                .map(JurisdictionPack.ChildExemption::exemptsGuardianConsent).orElse(false));
    }

    public void check(Connection c, JurisdictionPack pack, String tenantId, String principalRef, PrincipalCategory category,
                      PurposeDefinition purpose, Set<ProcessingFlag> flags) {
        Set<ProcessingFlag> all = EnumSet.noneOf(ProcessingFlag.class);
        all.addAll(purpose.flags());
        if (flags != null) {
            all.addAll(flags);
        }
        boolean mayBeChild = category == PrincipalCategory.CHILD || category == PrincipalCategory.UNKNOWN;
        if (!mayBeChild || all.isEmpty()) {
            return;
        }
        if (all.contains(ProcessingFlag.DETRIMENTAL_TO_WELLBEING)) {
            block(c, tenantId, principalRef, purpose, ProcessingFlag.DETRIMENTAL_TO_WELLBEING);
        }
        if (all.contains(ProcessingFlag.TARGETED_ADVERTISING)) {
            block(c, tenantId, principalRef, purpose, ProcessingFlag.TARGETED_ADVERTISING);
        }
        Set<String> allowed = new java.util.HashSet<>();
        for (String e : purpose.childExemptions()) {
            pack.childExemption(e).ifPresent(x -> allowed.addAll(x.covers()));
        }
        for (ProcessingFlag f : all) {
            if (!allowed.contains(f.name())) {
                block(c, tenantId, principalRef, purpose, f);
            }
        }
    }

    private void block(Connection c, String tenantId, String principalRef, PurposeDefinition purpose, ProcessingFlag flag) {
        audit.principal(c, tenantId, principalRef, ComplianceAudit.CHILD_PROCESSING_BLOCKED, Sql.kv("purpose", purpose.code(),
                "flag", flag.name()));
        throw new ComplianceException(Code.CHILD_PROCESSING_FORBIDDEN, flag.name() + " of a child is not permitted for purpose "
                + purpose.code());
    }
}
