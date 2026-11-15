// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.adapters.IntegrationLegalHoldAdapter;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.compliance.ports.CompliancePorts.ActiveHold;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.lifecycle.LegalHoldStore;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Owner decision 361: a legal hold is placed on a process root through the API (over the existing legal-hold store),
 * placing and releasing are audited on the root's chain (LEGAL_HOLD_SET, LEGAL_HOLD_RELEASED), release needs a
 * different person (FOUR_EYES_REQUIRED), and a hold overrides erasure: the held root is never handed to the erasure
 * port, the refusal is audited on its chain with the hold named, and the request closes naming LEGAL_HOLD.
 * H2 in PostgreSQL mode, IN pack.
 */
class LegalHoldErasureH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String CITIZEN = "citizen-1";
    static final String HELD = "root-held";
    static final String FREE = "root-free";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    String url;
    Connection keep;
    final List<String> erasedRoots = new ArrayList<>();

    /** The principal's data: two process roots of the tenant. */
    final CompliancePorts.PersonalDataPort data = new CompliancePorts.PersonalDataPort() {
        @Override
        public Map<String, Object> summarise(Connection c, String tenantId, String principalRef) {
            return Map.of();
        }

        @Override
        public List<String> correct(Connection c, String tenantId, String principalRef, Map<String, Object> corrections, String actor) {
            return List.of();
        }

        @Override
        public List<String> rootsOf(Connection c, String tenantId, String principalRef) {
            return TENANT.equals(tenantId) && CITIZEN.equals(principalRef) ? List.of(HELD, FREE) : List.of();
        }

        @Override
        public List<String> correlationKeysOf(Connection c, String tenantId, String principalRef) {
            return List.of("bk-1");
        }

        @Override
        public boolean isProcessRoot(Connection c, String tenantId, String rootId) {
            return TENANT.equals(tenantId) && (HELD.equals(rootId) || FREE.equals(rootId));
        }
    };

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d361" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keep = DriverManager.getConnection(url, "sa", "");
        Schema.create(keep, D);
        IntegrationSchema.create(keep, D);
        ComplianceSchema.create(keep, D);
    }

    @AfterEach
    void close() throws Exception {
        keep.close();
    }

    SangamCompliance compliance(CompliancePorts.LegalHoldPort holds, CompliancePorts.PersonalDataPort personal) {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", "IN");
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T out = work.apply(c);
                        c.commit();
                        return out;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        CompliancePorts.ErasurePort erasure = (t, root, keys, actor, why) -> {
            erasedRoots.add(root);
            return new CompliancePorts.ErasureOutcome(CompliancePorts.ErasureStatus.ERASED, Map.of("engine", 1), null);
        };
        SangamCompliance.Ports ports = new SangamCompliance.Ports(holds, erasure, null, null, null, personal, null, null, null,
                null, null, null, null, null, null, null, null);
        SangamCompliance cmp = new SangamCompliance(D, tx, config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]));
        tx.inTransaction(c -> {
            cmp.contacts.set(c, TENANT, new ContactDirectory.Contact("DPO", "Data Protection Officer", "Joint Secretary",
                    "Test Department", "dpo@example.gov.in", null, null, null, true, null), "admin-1");
            return null;
        });
        return cmp;
    }

    SangamCompliance compliance() {
        return compliance(new IntegrationLegalHoldAdapter(new LegalHoldStore(D)), data);
    }

    <T> T in(SangamCompliance cmp, Function<Connection, T> work) {
        return cmp.tx.inTransaction(work);
    }

    long events(String chain, String type) {
        return Jdbc.one(keep, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                rs -> rs.getLong(1), TENANT, chain, type).orElse(0L);
    }

    @Test
    void aHoldIsPlacedOnAProcessRootAndAuditedOnItsChain() {
        SangamCompliance cmp = compliance();
        ActiveHold h = in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, "RTI", "RTI/2026/17", "reply pending", "officer-1"));
        assertEquals(HELD, h.rootId());
        assertEquals("officer-1", h.heldBy());
        assertEquals("RTI RTI/2026/17: reply pending", h.reason());
        assertEquals(List.of(h), in(cmp, c -> cmp.legalHolds.active(c, TENANT, HELD)));
        assertEquals(1, events(HELD, LegalHoldService.LEGAL_HOLD_SET));
        assertTrue(in(cmp, c -> cmp.legalHolds.active(c, TENANT, FREE)).isEmpty());
    }

    @Test
    void aKindOutsideTheListOrNoReasonIsRefusedByName() {
        SangamCompliance cmp = compliance();
        ComplianceException k = assertThrows(ComplianceException.class,
                () -> in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, "rti", null, "why", "officer-1")));
        assertEquals(Code.INVALID_INPUT, k.code());
        assertTrue(k.getMessage().contains("kind"), k.getMessage());
        assertThrows(ComplianceException.class, () -> in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, null, null, "why", "o")));
        ComplianceException r = assertThrows(ComplianceException.class,
                () -> in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, "AUDIT", null, " ", "officer-1")));
        assertTrue(r.getMessage().contains("reason"), r.getMessage());
        assertEquals(0, events(HELD, LegalHoldService.LEGAL_HOLD_SET));
    }

    @Test
    void onlyAProcessRootOfTheTenantCanBeHeld() {
        SangamCompliance cmp = compliance();
        ComplianceException unknown = assertThrows(ComplianceException.class,
                () -> in(cmp, c -> cmp.legalHolds.place(c, TENANT, "not-a-root", "AUDIT", null, "why", "officer-1")));
        assertEquals(Code.NOT_FOUND, unknown.code());
        ComplianceException other = assertThrows(ComplianceException.class,
                () -> in(cmp, c -> cmp.legalHolds.place(c, "t2", HELD, "AUDIT", null, "why", "officer-1")));
        assertEquals(Code.NOT_FOUND, other.code(), "another tenant's root is not found");
        SangamCompliance noPort = compliance(new IntegrationLegalHoldAdapter(new LegalHoldStore(D)), null);
        assertEquals(Code.NOT_FOUND, assertThrows(ComplianceException.class,
                () -> in(noPort, c -> noPort.legalHolds.place(c, TENANT, HELD, "AUDIT", null, "why", "officer-1"))).code(),
                "without a personal-data port nothing can be held (fail closed)");
    }

    @Test
    void releaseNeedsADifferentPersonAndIsAudited() {
        SangamCompliance cmp = compliance();
        ActiveHold h = in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, "LITIGATION", "WP 12/2026", "stay order", "officer-1"));
        ComplianceException same = assertThrows(ComplianceException.class,
                () -> in(cmp, c -> { cmp.legalHolds.release(c, TENANT, HELD, h.holdId(), "order vacated", "officer-1"); return null; }));
        assertEquals(Code.FOUR_EYES_REQUIRED, same.code());
        assertEquals(1, in(cmp, c -> cmp.legalHolds.active(c, TENANT, HELD)).size(), "still held");
        assertThrows(ComplianceException.class,
                () -> in(cmp, c -> { cmp.legalHolds.release(c, TENANT, HELD, h.holdId(), " ", "officer-2"); return null; }));
        in(cmp, c -> { cmp.legalHolds.release(c, TENANT, HELD, h.holdId(), "order vacated", "officer-2"); return null; });
        assertTrue(in(cmp, c -> cmp.legalHolds.active(c, TENANT, HELD)).isEmpty());
        assertEquals(1, events(HELD, LegalHoldService.LEGAL_HOLD_RELEASED));
        assertEquals(Code.NOT_FOUND, assertThrows(ComplianceException.class,
                () -> in(cmp, c -> { cmp.legalHolds.release(c, TENANT, HELD, h.holdId(), "again", "officer-3"); return null; }))
                .code(), "a released hold is no longer active");
    }

    @Test
    void aStoreThatDoesNotKnowThePlacerCannotRelease() {
        CompliancePorts.LegalHoldPort anonymous = new CompliancePorts.LegalHoldPort() {
            final IntegrationLegalHoldAdapter real = new IntegrationLegalHoldAdapter(new LegalHoldStore(D));

            @Override
            public boolean isHeld(Connection c, String tenantId, String rootId) {
                return real.isHeld(c, tenantId, rootId);
            }

            @Override
            public List<String> activeHoldIds(Connection c, String tenantId, String rootId) {
                return real.activeHoldIds(c, tenantId, rootId);
            }

            @Override
            public void place(Connection c, String tenantId, String rootId, String holdId, String reason, String actor) {
                real.place(c, tenantId, rootId, holdId, reason, actor);
            }

            @Override
            public boolean release(Connection c, String tenantId, String rootId, String holdId, String actor) {
                return real.release(c, tenantId, rootId, holdId, actor);
            }
        };
        SangamCompliance cmp = compliance(anonymous, data);
        ActiveHold h = in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, "VIGILANCE", null, "inquiry", "officer-1"));
        assertEquals(Code.FOUR_EYES_REQUIRED, assertThrows(ComplianceException.class,
                () -> in(cmp, c -> { cmp.legalHolds.release(c, TENANT, HELD, h.holdId(), "closed", "officer-2"); return null; }))
                .code());
    }

    @Test
    void aHoldOverridesErasureAndTheRefusalNamesIt() {
        SangamCompliance cmp = compliance();
        ActiveHold h = in(cmp, c -> cmp.legalHolds.place(c, TENANT, HELD, "AUDIT", "CAG/2026/3", "audit para open", "officer-1"));
        DataSubjectRightsService.RightsRequest r = in(cmp, c -> cmp.rights.submit(c, TENANT,
                new DataSubjectRightsService.Submission(CITIZEN, "ERASURE", DataSubjectRightsService.SIGNED_IN, null, Map.of(),
                        null, "PORTAL", Map.of()), CITIZEN));
        DataSubjectRightsService.ErasureReport report = cmp.rights.executeErasure(TENANT, r.requestId(), "records-officer-1");
        assertEquals(List.of(FREE), erasedRoots, "the held root is never handed to the erasure port");
        assertEquals(List.of(FREE), report.erased());
        assertTrue(report.retained().get(HELD).startsWith("LEGAL_HOLD"), report.retained().toString());
        assertTrue(report.retained().get(HELD).contains(h.holdId()), "the refusal names the hold");
        assertEquals(1, events(HELD, ComplianceAudit.RETENTION_HELD), "the refusal is audited on the held root's chain");
        assertEquals(0, events(FREE, ComplianceAudit.RETENTION_HELD));
        DataSubjectRightsService.RightsRequest closed = in(cmp, c -> cmp.rights.get(c, TENANT, r.requestId()));
        assertEquals("PARTIALLY_RESOLVED", closed.status().name());
        assertTrue(closed.outcome().contains("LEGAL_HOLD"), closed.outcome());
        assertFalse(closed.outcome().contains(CITIZEN), "no personal data in the outcome");
    }
}
