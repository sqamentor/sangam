// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.sangam.core.layer.Dialect;
import org.sangam.rest.adapter.operaton.OperatonPortalAdapter;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PortalCompliancePort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.BusinessDates;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Owner decision 350: a grievance filed through the portal is due after the grievance period of the tenant's
 * jurisdiction pack ({@link PortalCompliancePort#grievanceDueDays}), not after a period fixed in the adapter (it was
 * {@code now + 30 days} for every tenant). Without the data-protection module, or for a tenant without a jurisdiction,
 * no period is published and no due date is promised. H2 (PostgreSQL mode) for the grievance documents; no engine
 * process is deployed, so the grievance is only filed.
 */
class PortalGrievanceDueH2Test {

    static final RequestContext CITIZEN = new RequestContext("t1", "citizen-1", Set.of(), null, null, null, null, null, "token");

    TxSupport tx;
    DocumentStore docs;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d2grv" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // REST_DOCUMENT as in sangam-persistence (changeset 0016-01-rest_document)
            s.execute("CREATE TABLE REST_DOCUMENT (tenant_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,"
                    + " doc_id VARCHAR(64) NOT NULL, doc_key VARCHAR(255), revision BIGINT NOT NULL, state VARCHAR(32),"
                    + " owner_id VARCHAR(255), ref_id VARCHAR(255), body CHARACTER LARGE OBJECT NOT NULL, created_by VARCHAR(255),"
                    + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " CONSTRAINT PK_REST_DOCUMENT PRIMARY KEY (tenant_id, kind, doc_id))");
        }
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        docs = new DocumentStore(tx);
    }

    /** No "grievance" process is deployed: the definition query counts 0. */
    static RepositoryService noGrievanceProcess() {
        ProcessDefinitionQuery q = (ProcessDefinitionQuery) Proxy.newProxyInstance(PortalGrievanceDueH2Test.class.getClassLoader(),
                new Class<?>[] {ProcessDefinitionQuery.class}, (proxy, m, args) -> m.getName().equals("count") ? 0L : proxy);
        return (RepositoryService) Proxy.newProxyInstance(PortalGrievanceDueH2Test.class.getClassLoader(),
                new Class<?>[] {RepositoryService.class}, (proxy, m, args) -> {
                    if (m.getName().equals("createProcessDefinitionQuery")) {
                        return q;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    static AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(PortalGrievanceDueH2Test.class.getClassLoader(), new Class<?>[] {AuditPort.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("record")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    static PortalCompliancePort period(OptionalInt days) {
        return (PortalCompliancePort) Proxy.newProxyInstance(PortalGrievanceDueH2Test.class.getClassLoader(),
                new Class<?>[] {PortalCompliancePort.class}, (proxy, m, args) -> {
                    if (m.getName().equals("grievanceDueDays")) {
                        return days;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    PortalPort.Grievance file(Supplier<PortalCompliancePort> compliance) {
        OperatonPortalAdapter adapter = new OperatonPortalAdapter(null, null, null, null, noGrievanceProcess(), null, null,
                audit(), new RestProperties(), tx, docs, BusinessDates.utc(), compliance);
        String reference = adapter.fileGrievance(CITIZEN, new PortalPort.GrievanceRequest("Delay", "No reply yet", "GRIEVANCE"))
                .reference();
        List<PortalPort.Grievance> mine = adapter.grievances(CITIZEN);
        assertEquals(1, mine.size());
        assertEquals(reference, mine.get(0).reference());
        return mine.get(0);
    }

    @Test
    void theDueDateIsTheFilingTimePlusThePacksGrievancePeriod() {
        PortalPort.Grievance g = file(() -> period(OptionalInt.of(15)));
        assertEquals("FILED", g.status());
        assertEquals(Duration.ofDays(15), Duration.between(g.filedAt(), g.dueAt()), "the pack's period, not 30 days");
    }

    @Test
    void withoutAPublishedPeriodNoDueDateIsPromised() {
        assertNull(file(() -> period(OptionalInt.empty())).dueAt(), "tenant without a jurisdiction");
    }

    @Test
    void withoutTheDataProtectionModuleNoDueDateIsPromised() {
        assertNull(file(() -> null).dueAt());
    }
}
