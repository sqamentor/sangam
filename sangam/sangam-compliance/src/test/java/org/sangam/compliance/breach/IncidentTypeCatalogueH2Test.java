// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 344: an incident type is a code of the authority's catalogue in the jurisdiction pack (IN: the 20
 * types of CERT-In Directions 28-04-2022, Annexure I, codes {@code i} to {@code xx}), picked from a list; free text is
 * refused with {@code INCIDENT_TYPE_NOT_IN_CATALOGUE}, for an incident and for the incident a breach registration opens,
 * before anything is written. The refusal does not echo the value.
 */
class IncidentTypeCatalogueH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();
    static final String FREE_TEXT = "ransomware on the file server";

    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d344" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    SangamCompliance compliance(String pack) {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", pack);
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("the tests pass their own connection");
            }
        };
        // no workflow starter: no process, so no seat is needed
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        return new SangamCompliance(D, tx, config, ports);
    }

    Connection caller() throws Exception {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(false);
        return c;
    }

    static long rows(Connection c, String table) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", rs -> rs.getLong(1), TENANT).orElse(0L);
    }

    static void assertRefused(Connection c, Runnable action, String why) {
        ComplianceException e = assertThrows(ComplianceException.class, action::run, why);
        assertEquals(ComplianceException.Code.INCIDENT_TYPE_NOT_IN_CATALOGUE, e.code(), why + ": " + e.getMessage());
        assertEquals(422, e.httpStatus());
        assertFalse(e.getMessage().contains(FREE_TEXT), "the refused value is not echoed: " + e.getMessage());
        assertEquals(0, rows(c, "CMP_INCIDENT"), why + ": no incident row");
        assertEquals(0, rows(c, "CMP_BREACH"), why + ": no breach row");
    }

    @Test
    void theCertInCatalogueIsTheTwentyAnnexureITypes() {
        // pack data: IN pack.json authorities[CERT-IN].catalogue, cited "CERT-In Directions 28-04-2022 para (ii), Annexure I"
        JurisdictionPack.Authority certIn = PACKS.require("IN").authoritiesWithRole("INCIDENT").get(0);
        assertEquals("CERT-IN", certIn.code());
        List<String> roman = List.of("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv",
                "xv", "xvi", "xvii", "xviii", "xix", "xx");
        IntStream.range(0, roman.size()).forEach(i ->
                assertFalse(certIn.incidentType(roman.get(i)).isEmpty(), "Annexure I type " + roman.get(i)));
        assertEquals(20, roman.stream().filter(code -> certIn.incidentType(code).isPresent()).count());
        assertEquals(true, certIn.incidentType("xxi").isEmpty(), "no 21st type");
    }

    @Test
    void freeTextIncidentTypeIsRefusedByName() throws Exception {
        SangamCompliance cmp = compliance("IN");
        try (Connection c = caller()) {
            assertRefused(c, () -> cmp.incidents.register(c, TENANT, "CERT-IN", FREE_TEXT, null, null, List.of("portal"),
                    Map.of(), "attack", null, "ciso-1"), "incident with free text");
            assertEquals("x", cmp.incidents.register(c, TENANT, "CERT-IN", "x", null, null, List.of("portal"), Map.of(),
                    "attack", null, "ciso-1").type(), "a catalogue code is accepted");
        }
    }

    @Test
    void aBreachWithAFreeTextIncidentTypeIsRefusedBeforeTheBreachIsWritten() throws Exception {
        SangamCompliance cmp = compliance("IN"); // linked authority CERT-IN
        try (Connection c = caller()) {
            assertRefused(c, () -> cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null,
                    FREE_TEXT, List.of("portal"), "officer-1"), "IN breach with free text");
        }
    }

    @Test
    void withoutALinkedAuthorityATypeNoCatalogueHasIsRefusedNotSilentlyDropped() throws Exception {
        SangamCompliance cmp = compliance("US-HIPAA"); // no linked authority: every INCIDENT authority whose catalogue has it
        try (Connection c = caller()) {
            assertRefused(c, () -> cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null,
                    FREE_TEXT, List.of("portal"), "officer-1"), "US breach with free text");
            BreachIncidentService.Breach b = cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null,
                    null, null, "EMTALA_SCREENING_REFUSAL", List.of("portal"), "officer-1");
            assertEquals(1, b.incidentIds().size(), "a catalogue code opens the authority's incident");
        }
    }
}
