// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consent Manager integration (DPDP s.6(7)-(9), Rule 4, First Schedule; CMP-010, CMP-011, CMP-050, CMP-051):
 * <ul>
 *   <li>{@link #ingest}: an artefact pushed by a manager becomes a consent record, after the manager is checked
 *       against the Board-registered allow-list and the artefact signature is verified by its {@link ConsentManagerPort};</li>
 *   <li>{@link #withdrawnAtManager}: a withdrawal made at the manager withdraws the fiduciary-side consent;</li>
 *   <li>a withdrawal made through the fiduciary's own channel is pushed back to the manager (registered as a
 *       {@link ConsentService.WithdrawalListener}).</li>
 * </ul>
 */
public final class ConsentManagerGateway implements ConsentService.WithdrawalListener {

    private final ComplianceConfig.Provider config;
    private final ConsentService consents;
    private final Map<String, ConsentManagerPort> managers = new ConcurrentHashMap<>();

    public ConsentManagerGateway(ComplianceConfig.Provider config, ConsentService consents) {
        this.config = config;
        this.consents = consents;
        consents.addWithdrawalListener(this);
    }

    public void register(ConsentManagerPort manager) {
        managers.put(manager.registrationId(), manager);
    }

    public ConsentService.Consent ingest(Connection c, String tenantId, ConsentManagerPort.ConsentArtefact a) {
        ConsentManagerPort m = manager(tenantId, a.consentManagerId());
        if (!m.verify(a)) {
            throw new ComplianceException(Code.CONSENT_REQUIRED, "consent artefact " + a.artefactId() + " failed verification");
        }
        Optional<ConsentService.Consent> existing = consents.byArtefact(c, tenantId, a.consentManagerId(), a.artefactId());
        if (existing.isPresent()) {
            return existing.get();
        }
        return consents.give(c, tenantId, new ConsentService.GiveConsent(a.principalRef(), a.noticeKey(), a.noticeVersion(),
                config.forTenant(tenantId).pack.requireLanguage(a.languageCode()), a.purposeCodes(), true, "consent-manager:" + a.consentManagerId(),
                a.principalRef(), null, a.consentManagerId(), a.artefactId()));
    }

    public ConsentService.Consent withdrawnAtManager(Connection c, String tenantId, String consentManagerId, String artefactId,
                                                     Set<String> purposeCodes) {
        manager(tenantId, consentManagerId);
        ConsentService.Consent k = consents.byArtefact(c, tenantId, consentManagerId, artefactId)
                .orElseThrow(() -> ComplianceException.notFound("consent artefact", artefactId));
        return consents.withdraw(c, tenantId, k.principalRef(), k.consentId(), purposeCodes, "consent-manager:" + consentManagerId,
                k.principalRef());
    }

    @Override
    public void onWithdrawn(Connection c, String tenantId, String principalRef, String consentId, Set<String> purposeCodes,
                            ConsentService.Consent consent, Instant at) {
        if (consent.consentManagerId() == null) {
            return;
        }
        ConsentManagerPort m = managers.get(consent.consentManagerId());
        if (m != null) {
            m.notifyStatus(tenantId, consent.cmArtefactId(), "WITHDRAWN", purposeCodes, at);
        }
    }

    private ConsentManagerPort manager(String tenantId, String id) {
        if (!config.forTenant(tenantId).registeredConsentManagers.contains(id)) {
            throw new ComplianceException(Code.CONSENT_MANAGER_NOT_REGISTERED, "consent manager " + id + " is not on the registered allow-list");
        }
        ConsentManagerPort m = managers.get(id);
        if (m == null) {
            throw new ComplianceException(Code.CONSENT_MANAGER_NOT_REGISTERED, "no integration configured for consent manager " + id);
        }
        return m;
    }
}
