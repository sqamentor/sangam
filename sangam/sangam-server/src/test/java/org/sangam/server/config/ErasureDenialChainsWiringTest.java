// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.jdbc.DenialAudit;
import org.sangam.security.audit.SecurityAuditService;

/**
 * SL-132 erasure: the server's ErasureService erases an instance's denial chain with the instance's own chain. The
 * chain comes from the single formula in sangam-core ({@code AuditChain.denialChain}), the chain both the security
 * denials ({@code SecurityAuditService.denialChain}) and the REST denials ({@code DenialAudit.chainOf}) write to.
 */
class ErasureDenialChainsWiringTest {

    @Test
    void theErasedInstancesDenialChainIsRelated() {
        List<String> related = IntegrationWiring.denialChains().of("T1", "root-42");
        assertEquals(List.of(org.sangam.core.layer.AuditChain.denialChain("T1", "root-42")), related, "the core formula");
        assertEquals(SecurityAuditService.denialChain("T1", "root-42"), related.get(0), "the chain the security denials write to");
        assertTrue(related.get(0).startsWith("sec:d:"), related.get(0));
        assertEquals(DenialAudit.chainOf("T1", "root-42"), related.get(0), "the chain the REST denials write to");
    }
}
