// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.rest;

import org.sangam.core.security.SecurityIdentity;

/**
 * Shared types of the case module's REST ports. WS-REST mounts the ports under {@code /api/v1} (route table in the
 * module README) with the ARCHITECTURE §4 conventions: JSON, ISO-8601 UTC, {@code Idempotency-Key} on mutations (R41,
 * the REST layer's idempotency store), {@code page/size} with {@code X-Total-Count} / {@code X-Total-Count-Capped}
 * (A-16), {@code CaseFileException.status()} → RFC 9457 problem+json, optimistic {@code rowVersion} → 409. The tenant
 * always comes from the identity (VI.4 rule 6).
 */
public final class CaseApi {

    private CaseApi() {
    }

    /** VI.4 resource types the module registers with WS-SECURITY's {@code ResourceRegistry}. */
    public static final String CASE_FILE = "CASE_FILE";
    public static final String CASE_REFERENCE = "CASE_REFERENCE";
    public static final String COMMITTEE = "COMMITTEE";
    /** Resource id standing for "the whole module" (create, configuration, registers). */
    public static final String ALL = "*";

    /** The authenticated caller. */
    public record ApiContext(SecurityIdentity identity) {
        public String tenant() {
            return identity.tenantId();
        }

        public String actor() {
            return identity.personId();
        }
    }
}
