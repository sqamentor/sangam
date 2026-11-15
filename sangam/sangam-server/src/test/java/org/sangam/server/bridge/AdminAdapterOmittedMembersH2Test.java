// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.jdbc.JdbcAdminAdapter;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.AdminPort.OrgUnit;
import org.sangam.rest.port.AdminPort.Seat;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Lane D5 item 1 (SL-81): {@code active} and {@code revision} of an org unit or seat are server-assigned, so a request
 * may leave them out (AdminPort.OrgUnit/Seat.fromRequest). The module-disabled admin adapter then applies them in one
 * place: create assigns {@code active=true} and revision 1; an update without {@code active} keeps the current value
 * (as for {@code level}), never a guessed false; an update without {@code revision} (0, none read) is refused with 409
 * REVISION_CONFLICT, never applied blind.
 */
class AdminAdapterOmittedMembersH2Test {

    private static final RequestContext ADMIN = new RequestContext("T1", "user:admin", Set.of("admins"), "seat-admin",
            null, null, null, null, "OIDC");

    private JdbcAdminAdapter admin;

    @BeforeEach
    void wire() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d5adm" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        SangamSchema.apply(ds);
        TxSupport tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        RestProperties props = new RestProperties();
        admin = new JdbcAdminAdapter(new DocumentStore(tx), tx, new JdbcAuditAdapter(tx, null, null, props), props);
    }

    private static OrgUnit unit(String name, Boolean active, Long revision) {
        return OrgUnit.fromRequest(null, null, null, "DIST-01", name, null, "DISTRICT", active, null, revision);
    }

    @Test
    void anOrgUnitUpdateWithoutActiveKeepsTheCurrentValue() {
        OrgUnit created = admin.createOrgUnit(ADMIN, unit("District office", null, null));
        assertEquals(Boolean.TRUE, created.active(), "server-assigned on create");
        assertEquals(1L, created.revision());

        OrgUnit renamed = admin.updateOrgUnit(ADMIN, created.id(), unit("District office (north)", null, created.revision()));
        assertEquals(Boolean.TRUE, renamed.active(), "an omitted active is not a deactivation");
        assertEquals(2L, renamed.revision());

        OrgUnit closed = admin.updateOrgUnit(ADMIN, created.id(), unit("District office (north)", false, renamed.revision()));
        assertEquals(Boolean.FALSE, closed.active(), "an explicit value is applied");
        OrgUnit still = admin.updateOrgUnit(ADMIN, created.id(), unit("District office (north)", null, closed.revision()));
        assertEquals(Boolean.FALSE, still.active(), "and kept by a later update that leaves it out");
    }

    @Test
    void anOrgUnitUpdateWithoutRevisionIsAConflict() {
        OrgUnit created = admin.createOrgUnit(ADMIN, unit("District office", null, null));
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> admin.updateOrgUnit(ADMIN, created.id(), unit("Renamed", null, null)));
        assertEquals(409, p.status());
        assertEquals("REVISION_CONFLICT", p.code());
        assertEquals("District office", admin.orgUnit(ADMIN, created.id()).name(), "nothing applied");
    }

    @Test
    void aSeatUpdateWithoutActiveKeepsTheCurrentValue() {
        OrgUnit ou = admin.createOrgUnit(ADMIN, unit("District office", null, null));
        Seat s = admin.createSeat(ADMIN, Seat.fromRequest(null, null, ou.id(), "S1", "Clerk", "Clerk 1", null, null,
                null, null, null, null));
        assertEquals(Boolean.TRUE, s.active());
        Seat renamed = admin.updateSeat(ADMIN, s.id(), Seat.fromRequest(null, null, ou.id(), "S1", "Clerk", "Clerk one",
                null, null, null, null, null, s.revision()));
        assertEquals(Boolean.TRUE, renamed.active());
        assertEquals("Clerk one", renamed.name());
        ApiProblem p = assertThrows(ApiProblem.class, () -> admin.updateSeat(ADMIN, s.id(), Seat.fromRequest(null, null,
                ou.id(), "S1", "Clerk", "Clerk two", null, null, null, null, null, null)));
        assertEquals("REVISION_CONFLICT", p.code());
    }
}
