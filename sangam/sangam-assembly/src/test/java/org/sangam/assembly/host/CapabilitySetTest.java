// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** M-144 fail-closed capability decisions and the catalogue. Written for the test phase (not run). */
class CapabilitySetTest {

    @Test
    void everyMissingRequiredCapabilityRefusesTheStartAndIsNamed() {
        CapabilitySet set = CapabilitySet.resolve(Capabilities.required(), List.of(Capabilities.CONFIG_SOURCE));
        CapabilityMissingException e = assertThrows(CapabilityMissingException.class, set::refuseStartIfMissing);
        assertTrue(e.getMessage().startsWith(CapabilityMissingException.CODE + ": start-up refused"), e.getMessage());
        assertEquals(Capabilities.required().size() - 1, e.missing().size());
        assertFalse(e.missing().contains(Capabilities.CONFIG_SOURCE));
        for (Capability c : e.missing()) {
            assertTrue(e.getMessage().contains(c.id() + " (" + c.whenMissing() + ")"), c.id());
        }
        assertEquals(e.missing().stream().map(Capability::id).sorted().toList(),
                e.missing().stream().map(Capability::id).toList(), "sorted by id");
    }

    @Test
    void allRequiredSuppliedStartsAndMissingConveniencesAreOnlyReported() {
        List<Capability> needed = new java.util.ArrayList<>(Capabilities.required());
        needed.add(Capabilities.HTTP);
        CapabilitySet set = CapabilitySet.resolve(needed, Capabilities.required());
        assertDoesNotThrow(set::refuseStartIfMissing);
        assertEquals(List.of(Capabilities.HTTP), set.missingConveniences());
        assertTrue(set.report().contains("http CONVENIENCE MISSING - " + Capabilities.HTTP.whenMissing()));
        assertTrue(set.report().contains("audit-chain REQUIRED supplied"));
    }

    @Test
    void anOperationNeedingAMissingCapabilityIsRefused() {
        CapabilitySet set = CapabilitySet.resolve(List.of(), List.of(Capabilities.AUDIT_CHAIN));
        assertDoesNotThrow(() -> set.require(Capabilities.AUDIT_CHAIN, "append"));
        CapabilityMissingException e = assertThrows(CapabilityMissingException.class,
                () -> set.require(Capabilities.IDEMPOTENCY_STORE, "idempotent command"));
        assertEquals(List.of(Capabilities.IDEMPOTENCY_STORE), e.missing());
        assertTrue(e.getMessage().startsWith("CAPABILITY_MISSING: idempotent command refused"), e.getMessage());
    }

    @Test
    void aRefusalAlwaysNamesACapability() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMissingException("x", List.of()));
    }

    @Test
    void theCatalogueCoversTheMatrixAndTheSeams() {
        Set<String> ids = new HashSet<>();
        for (Capability c : Capabilities.all()) {
            assertTrue(ids.add(c.id()), "unique id " + c.id());
            assertEquals(c, Capabilities.byId(c.id()).orElseThrow());
        }
        // design note 3.1 (required rows) and 10.1 (required seams)
        for (String id : List.of("utc-jvm", "database-checks", "schema-before-engine", "shared-transaction",
                "tenant-security-plugin", "authorization", "audit-chain", "idempotency-store", "outbox-relay-inbox",
                "job-leases", "jackson-feel-compatibility", "money-invariants", "config-source", "lifecycle",
                "security-context", "scheduling")) {
            assertTrue(Capabilities.byId(id).orElseThrow().required(), id);
        }
        for (String id : List.of("http", "oidc-http", "metrics-tracing", "defaults-source", "health", "web-extras")) {
            assertFalse(Capabilities.byId(id).orElseThrow().required(), id);
        }
        assertEquals(Capabilities.all().size(), Capabilities.required().size() + Capabilities.conveniences().size());
    }

    @Test
    void aCapabilityIsWellFormed() {
        assertThrows(IllegalArgumentException.class,
                () -> new Capability("Bad Id", Capability.Kind.REQUIRED, "s", "refused"));
        assertThrows(IllegalArgumentException.class, () -> new Capability("x", Capability.Kind.REQUIRED, " ", "refused"));
        assertThrows(IllegalArgumentException.class, () -> new Capability("x", Capability.Kind.REQUIRED, "s", ""));
        assertThrows(NullPointerException.class, () -> new Capability("x", null, "s", "refused"));
    }

    /** Decision 278: HTTP and OIDC over HTTP are Spring-only in 0.1.0, and only conveniences can be Spring-only. */
    @Test
    void httpAndOidcOverHttpAreSpringOnly() {
        assertEquals(Set.of(Capabilities.HTTP, Capabilities.OIDC_HTTP), Capabilities.SPRING_ONLY);
        assertTrue(Capabilities.springOnly(Capabilities.HTTP));
        assertFalse(Capabilities.springOnly(Capabilities.SHARED_TRANSACTION));
        for (Capability c : Capabilities.SPRING_ONLY) {
            assertFalse(c.required(), c.id() + ": a required capability can never be host-specific");
        }
    }
}
