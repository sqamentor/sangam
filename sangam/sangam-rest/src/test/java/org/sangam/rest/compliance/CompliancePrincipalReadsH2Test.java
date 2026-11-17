// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Owner decisions 338 and 348 at the compliance REST layer ({@link ComplianceController} over {@link ComplianceApiImpl},
 * H2 in PostgreSQL mode, IN pack, no workflow starter):
 * <ul>
 *   <li>338: a principal's consents and rights requests are searched with {@code POST .../search} and the reference in
 *       the body; the {@code ?subject=} form is withdrawn and refused by name ({@code SUBJECT_QUERY_WITHDRAWN}), never
 *       answered with the caller's own records;</li>
 *   <li>348: a read of another principal's consents or rights requests by an officer (compliance.read) writes
 *       {@code PERSONAL_DATA_READ} on that principal's chain, in the transaction of the read; a principal reading her own
 *       records writes nothing; a non-officer is refused (403 for a search, 404 for one request) and nothing is
 *       written;</li>
 *   <li>335: the rights intake takes the signed-in identity the server authenticated, never one from the body.</li>
 * </ul>
 */
class CompliancePrincipalReadsH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String CITIZEN = "citizen-1";
    static final String OFFICER = "dpo-1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    String url;
    Connection keepAlive;
    ComplianceController controller;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:d348" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", "IN");
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        PermissionPort permissions = new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return OFFICER.equals(ctx.principalId()) && Permissions.COMPLIANCE_READ.equals(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return OFFICER.equals(ctx.principalId()) ? Set.of(Permissions.COMPLIANCE_READ) : Set.of();
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
        controller = new ComplianceController(new ComplianceApiImpl(new SangamCompliance(D, tx, config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]))), permissions);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    static RequestContext ctx(String who) {
        return new RequestContext(TENANT, who, Set.of(), null, null, null, "trace-1", "127.0.0.1", "OIDC");
    }

    /** A rights request submitted by the signed-in citizen (decision 335). */
    String citizenRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/compliance/rights-requests");
        req.setAttribute(org.sangam.rest.port.SangamPrincipal.ATTRIBUTE,
                new org.sangam.rest.port.SangamPrincipal(TENANT, CITIZEN, Set.of(), "OIDC", Map.of()));
        DataSubjectRightsService.Submission s = new DataSubjectRightsService.Submission(null, "ACCESS",
                DataSubjectRightsService.SIGNED_IN, null, Map.of(), null, "PORTAL", Map.of());
        DataSubjectRightsService.RightsRequest r = controller.submitRightsRequest(req, s, null, null).getBody();
        assertEquals(CITIZEN, r.principalRef(), "decision 335: the requester is the signed-in identity");
        return r.requestId();
    }

    List<Map<String, Object>> reads(String principalRef) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            return Jdbc.query(c, "SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e ON e.event_id=p.event_id"
                            + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq", rs -> Sql.map(rs.getString(1)),
                    TENANT, ComplianceAudit.principalChain(TENANT, principalRef), ComplianceAudit.PERSONAL_DATA_READ);
        }
    }

    long allReads() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            return Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?", rs -> rs.getLong(1), TENANT,
                    ComplianceAudit.PERSONAL_DATA_READ).orElse(0L);
        }
    }

    @Test
    void anAnonymousSignedInRequestIsRefused() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/compliance/rights-requests");
        DataSubjectRightsService.Submission s = new DataSubjectRightsService.Submission(CITIZEN, "ACCESS",
                DataSubjectRightsService.SIGNED_IN, CITIZEN, Map.of(), null, "PORTAL", Map.of());
        ComplianceException e = assertThrows(ComplianceException.class, () -> controller.submitRightsRequest(req, s, TENANT, null));
        assertEquals(ComplianceException.Code.PRINCIPAL_NOT_AUTHENTICATED, e.code(), "a body naming the citizen is not a sign-in");
    }

    @Test
    void anOfficerSearchOfAnotherPrincipalIsAuditedOnThatPrincipalsChain() throws Exception {
        String id = citizenRequest();

        List<DataSubjectRightsService.RightsRequest> found = controller.searchRightsRequests(ctx(OFFICER),
                new ComplianceRequests.SubjectSearch(CITIZEN)).getBody();
        assertEquals(List.of(id), found.stream().map(DataSubjectRightsService.RightsRequest::requestId).toList());
        controller.searchConsents(ctx(OFFICER), new ComplianceRequests.SubjectSearch(" " + CITIZEN + " "));
        controller.rightsRequest(ctx(OFFICER), id);

        List<Map<String, Object>> reads = reads(CITIZEN);
        assertEquals(3, reads.size(), reads.toString());
        assertEquals("rights-requests", reads.get(0).get("records"));
        assertEquals(1, ((Number) reads.get(0).get("count")).intValue());
        assertEquals(OFFICER, reads.get(0).get("actor"));
        assertEquals(org.sangam.compliance.PrincipalKey.of(new byte[32]).token(TENANT, CITIZEN), reads.get(0).get("principal"),
                "the keyed token (owner decision 369 (e))");
        assertEquals("consents", reads.get(1).get("records"));
        assertEquals(0, ((Number) reads.get(1).get("count")).intValue(), "a search that finds nothing is still a read");
        assertEquals(id, reads.get(2).get("recordId"), "one request read by its id");
        assertTrue(reads.stream().noneMatch(p -> p.toString().contains(CITIZEN)), "no principal reference in the payload");
    }

    @Test
    void thePrincipalsOwnReadsWriteNothing() throws Exception {
        String id = citizenRequest();
        assertEquals(1, controller.rightsRequests(ctx(CITIZEN), new MockHttpServletRequest()).getBody().size());
        assertEquals(1, controller.searchRightsRequests(ctx(CITIZEN), null).getBody().size(), "blank search: her own");
        assertEquals(1, controller.searchRightsRequests(ctx(CITIZEN), new ComplianceRequests.SubjectSearch(CITIZEN)).getBody().size());
        controller.consents(ctx(CITIZEN), new MockHttpServletRequest());
        assertEquals(id, controller.rightsRequest(ctx(CITIZEN), id).requestId());
        assertEquals(0, allReads());
    }

    @Test
    void aNonOfficerCannotReadAnotherPrincipalAndNothingIsWritten() throws Exception {
        String id = citizenRequest();
        ApiProblem search = assertThrows(ApiProblem.class,
                () -> controller.searchRightsRequests(ctx("citizen-2"), new ComplianceRequests.SubjectSearch(CITIZEN)));
        assertEquals(403, search.status());
        assertThrows(ApiProblem.class, () -> controller.searchConsents(ctx("citizen-2"), new ComplianceRequests.SubjectSearch(CITIZEN)));
        ComplianceException one = assertThrows(ComplianceException.class, () -> controller.rightsRequest(ctx("citizen-2"), id));
        assertEquals(404, one.httpStatus(), "no existence leak");
        assertEquals(0, allReads());
    }

    @Test
    void theSubjectQueryFormIsWithdrawnByName() {
        MockHttpServletRequest withSubject = new MockHttpServletRequest();
        withSubject.setParameter("subject", CITIZEN);
        for (Runnable call : List.<Runnable>of(() -> controller.consents(ctx(OFFICER), withSubject),
                () -> controller.rightsRequests(ctx(OFFICER), withSubject))) {
            ApiProblem p = assertThrows(ApiProblem.class, call::run);
            assertEquals(400, p.status());
            assertEquals("SUBJECT_QUERY_WITHDRAWN", p.code());
        }
    }
}
