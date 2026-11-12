// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.core.layer.AuditChain;
import org.sangam.rest.adapter.jdbc.DenialAudit;
import org.sangam.security.audit.SecurityAuditService;

/**
 * SL-132: one denial-chain formula. {@link AuditChain#denialChain} (sangam-core) is the source of truth; the
 * sangam-security and sangam-rest copies must return byte-identical ids until they delegate to it. Lives in
 * sangam-server, the only module that sees all three.
 */
class DenialChainFormulaTest {

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"dev, 5f2c1a7e-0000-4000-8000-000000000001", "RJ, sec:RJ", "'-', root-1", "राजस्थान, प्रकरण-१",
            "t, ''"})
    void theCoreFormulaEqualsBothCopies(String tenant, String chain) throws Exception {
        String core = AuditChain.denialChain(tenant, chain);
        assertEquals(SecurityAuditService.denialChain(tenant, chain), core, "sangam-security copy");
        assertEquals(DenialAudit.chainOf(tenant, chain), core, "sangam-rest copy");
        assertEquals(SecurityAuditService.DENIAL_PREFIX, AuditChain.DENIAL_PREFIX);

        byte[] h = MessageDigest.getInstance("SHA-256").digest((tenant + "\u0000" + chain).getBytes(StandardCharsets.UTF_8));
        assertEquals("sec:d:" + HexFormat.of().formatHex(h).substring(0, 32), core, "the specified formula");
        assertEquals(38, core.length());
        assertTrue(core.startsWith("sec:"), "a sec: chain carries no root_id in its envelope");
    }

    /** X1-06: one generic formula; denialChain and the compliance principal chains are prefixes of it. */
    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"dev, principal-17", "राजस्थान, आवेदक-१", "t, ''"})
    void hashedChainIsTheOneFormula(String tenant, String key) {
        assertEquals(AuditChain.denialChain(tenant, key), AuditChain.hashedChain("sec:d:", tenant, key),
                "denialChain = hashedChain(sec:d:)");
        assertEquals(ComplianceAudit.denialChain(tenant, key), AuditChain.hashedChain("cmp:d:", tenant, key),
                "sangam-compliance cmp:d: copy");
        assertEquals(ComplianceAudit.principalChain(tenant, key), AuditChain.hashedChain("cmp:p:", tenant, key),
                "sangam-compliance cmp:p: copy");
    }

    @org.junit.jupiter.api.Test
    void theSeparatorKeepsTenantAndChainApart() {
        assertNotEquals(AuditChain.denialChain("a", "bc"), AuditChain.denialChain("ab", "c"));
    }
}
