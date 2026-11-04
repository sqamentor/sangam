// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import java.util.List;
import java.util.Map;

/**
 * Port to the payee / beneficiary golden record (GA-12, IFS-056): one party per real payee across payroll, pension,
 * vendor and DBT payments, with bank-account identifiers, verification state, lock after verification and detection
 * of several beneficiaries on one account. The platform implementation delegates to the party registry of
 * {@code sangam-registry} (no second registry); see {@code org.sangam.billing.party.RegistryPayeeDirectory}.
 */
public interface PayeeDirectory {

    /**
     * Result of a resolution.
     *
     * @param partyId               the golden-record party (null if none)
     * @param status                ACTIVE / INACTIVE / MERGED / NOT_FOUND
     * @param verified              bank account verified (and therefore locked for edits)
     * @param multipleBeneficiaries the account is held by more than one party (review required)
     * @param matches               candidate matches (partyId, score, decision)
     */
    record Resolution(String partyId, String status, boolean verified, boolean multipleBeneficiaries,
                      List<Map<String, Object>> matches) {
    }

    /** Resolves a payee: by {@code partyId}, else by the account identifier and name. Read-only. */
    Resolution resolve(String tenant, Map<String, Object> payee);

    /** Registers the payee if no party holds the account yet (idempotent on the account identifier). */
    Resolution register(String tenant, Map<String, Object> payee, String operationId, String actor);
}
