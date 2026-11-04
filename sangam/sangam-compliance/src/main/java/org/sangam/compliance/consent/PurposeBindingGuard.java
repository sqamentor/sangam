// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.compliance.ports.CompliancePorts.PrincipalDirectory;
import org.sangam.compliance.support.Sql;

import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Purpose binding: the single check every workflow step that touches personal data calls before it processes.
 * Order: the purpose exists and is active; the data categories are itemised for it (minimisation); a consent purpose
 * (unless an exemption disapplies consent) needs an active consent; an objectable basis stops after an objection; the
 * child safeguards run last. A refusal writes {@code CMP_PURPOSE_BINDING_DENIED} on the subject's chain.
 * IN: DPDP s.4, s.6(1), s.6(5), s.7(a), s.9 (CMP-001, CMP-005, CMP-009, CMP-013, CMP-030, CMP-031).
 */
public final class PurposeBindingGuard {

    public record Decision(String purposeCode, String basis, String citation, Set<String> exemptions, int purposeVersion) {
    }

    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final LegitimateUseRegistry purposes;
    private final ConsentService consents;
    private final ChildDataGuard children;
    private final PrincipalDirectory principals;

    public PurposeBindingGuard(ComplianceAudit audit, ComplianceConfig.Provider config, LegitimateUseRegistry purposes,
                               ConsentService consents, ChildDataGuard children, PrincipalDirectory principals) {
        this.audit = audit;
        this.config = config;
        this.purposes = purposes;
        this.consents = consents;
        this.children = children;
        this.principals = principals == null ? PrincipalDirectory.NONE : principals;
    }

    public Decision require(Connection c, String tenantId, String principalRef, String purposeCode, Set<String> dataCategories,
                            Set<ProcessingFlag> flags) {
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        PurposeDefinition p = purposes.find(c, tenantId, purposeCode).orElse(null);
        if (p == null || !p.active()) {
            deny(c, tenantId, principalRef, purposeCode, "purpose not registered or inactive");
            throw new ComplianceException(Code.PURPOSE_NOT_PERMITTED, "purpose " + purposeCode + " is not registered");
        }
        if (dataCategories != null && !p.dataCategoryCodes().containsAll(dataCategories)) {
            Set<String> extra = new LinkedHashSet<>(dataCategories);
            extra.removeAll(p.dataCategoryCodes());
            deny(c, tenantId, principalRef, purposeCode, "data categories not itemised: " + extra);
            throw new ComplianceException(Code.PURPOSE_NOT_PERMITTED, "data categories " + extra + " are not itemised for purpose " + purposeCode);
        }
        JurisdictionPack.LegalBasis basis = pack.legalBasis(p.basis()).orElseThrow(() ->
                new ComplianceException(Code.PURPOSE_NOT_PERMITTED, "basis " + p.basis() + " unknown to jurisdiction " + pack.code()));
        if (basis.consent() && !p.noticeAndConsentDisapplied(pack)) {
            if (!consents.isConsented(c, tenantId, principalRef, purposeCode)) {
                deny(c, tenantId, principalRef, purposeCode, "no active consent");
                throw new ComplianceException(Code.CONSENT_REQUIRED, "no active consent for purpose " + purposeCode);
            }
        } else if (basis.objectable() && purposes.hasObjected(c, tenantId, principalRef, purposeCode)) {
            deny(c, tenantId, principalRef, purposeCode, "objection recorded");
            throw new ComplianceException(Code.PURPOSE_NOT_PERMITTED, "the data subject objected to purpose " + purposeCode);
        }
        PrincipalCategory category = principals.category(c, tenantId, principalRef);
        children.check(c, pack, tenantId, principalRef, category, p, flags);
        return new Decision(p.code(), p.basis(), basis.citation(), p.exemptions(), p.version());
    }

    public boolean allowed(Connection c, String tenantId, String principalRef, String purposeCode, Set<String> dataCategories) {
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        PurposeDefinition p = purposes.find(c, tenantId, purposeCode).orElse(null);
        if (p == null || !p.active() || dataCategories != null && !p.dataCategoryCodes().containsAll(dataCategories)) {
            return false;
        }
        JurisdictionPack.LegalBasis basis = pack.legalBasis(p.basis()).orElse(null);
        if (basis == null) {
            return false;
        }
        if (basis.consent() && !p.noticeAndConsentDisapplied(pack)) {
            return consents.isConsented(c, tenantId, principalRef, purposeCode);
        }
        return !(basis.objectable() && purposes.hasObjected(c, tenantId, principalRef, purposeCode));
    }

    private void deny(Connection c, String tenantId, String principalRef, String purposeCode, String reason) {
        audit.principal(c, tenantId, principalRef, ComplianceAudit.PURPOSE_BINDING_DENIED, Sql.kv("purpose", purposeCode, "reason", reason));
    }
}
