// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import java.time.Instant;
import java.util.Set;

/**
 * Integration hook for a registered consent manager / consent-management intermediary (IN: Consent Manager under DPDP
 * s.6(7)-(9), r.4, First Schedule; CMP-010, CMP-011, CMP-050, CMP-051). One implementation per Consent
 * Manager platform; {@link ConsentManagerGateway} accepts artefacts only from managers on the tenant's allow-list of
 * Board-registered managers ({@code sangam.compliance.consent-managers.registered}).
 *
 * <p>The First Schedule requires the manager, not the fiduciary, to keep its own records for seven years; Sangam keeps
 * the fiduciary-side record (artefact id, status changes) in the consent ledger.
 */
public interface ConsentManagerPort {

    /** Board registration number of this Consent Manager. */
    String registrationId();

    /**
     * A consent artefact as exchanged with the manager (interoperable, machine readable).
     *
     * @param signature detached signature over the canonical artefact, verified by {@link #verify}
     */
    record ConsentArtefact(String consentManagerId, String artefactId, String principalRef, String fiduciaryId,
                           String noticeKey, int noticeVersion, String languageCode, Set<String> purposeCodes,
                           Instant issuedAt, Instant expiresAt, String signature) {
    }

    /** Verifies authenticity and integrity of an artefact (signature, issuer, not expired). */
    boolean verify(ConsentArtefact artefact);

    /** Pushes a fiduciary-side status change (withdrawal through the fiduciary's own channel, erasure, expiry). */
    void notifyStatus(String tenantId, String artefactId, String status, Set<String> purposeCodes, Instant at);
}
