// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.indicator;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.ProgrammeSchema;
import org.sangam.programme.rest.ProgrammeRestAdapter;
import org.sangam.programme.rest.ProgrammeRestPort.ApiContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C3-09 ({@code GET /programme-config}): the current published configuration is read back as it was published, with the
 * version and content hash the publish answered; nothing published is NOT_FOUND; another tenant's configuration is never
 * returned; the read needs ADMIN on the programme root, like the publish and the history.
 */
class ProgrammeCurrentConfigH2Test {

    /** Records every check and allows it, unless {@code deny} is set. */
    private static final class Recording implements SecurityPorts.Authorization {
        final List<String> checks = new ArrayList<>();
        boolean deny;

        @Override
        public SqlPredicate visible(Connection c, SecurityIdentity identity, String resourceType, String permission, String alias) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean allow(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId) {
            return !deny;
        }

        @Override
        public void require(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId) {
            checks.add(resourceType + ":" + permission + ":" + resourceId);
            if (deny) {
                throw new SecurityException("ACTION_NOT_PERMITTED");
            }
        }

        @Override
        public long policyVersion(Connection c, String tenantId) {
            return 1;
        }
    }

    private static final ApiContext T1 = new ApiContext(SecurityIdentity.system("T1", null));
    private static final ApiContext T2 = new ApiContext(SecurityIdentity.system("T2", null));

    private static ProgrammeRestAdapter adapter(Recording auth) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            ProgrammeSchema.create(c, Dialect.POSTGRESQL);
        }
        ProgrammeModule module = new ProgrammeModule(Dialect.POSTGRESQL, Transactions.of(ds), new Outbox(Dialect.POSTGRESQL),
                ProgrammeModule.Ports.none());
        return new ProgrammeRestAdapter(module, auth);
    }

    @Test
    void theCurrentConfigurationIsReadBackAsPublished() throws Exception {
        ProgrammeRestAdapter a = adapter(new Recording());
        IndicatorRegistry.Published first = a.publishConfig(T1, Map.of("fiscalYearStartMonth", 4));
        IndicatorRegistry.Published second = a.publishConfig(T1, Map.of("fiscalYearStartMonth", 7, "fiscalStartDay", 15));
        IndicatorRegistry.Current cur = a.publishedConfig(T1);
        assertEquals(second.version(), cur.version());
        assertEquals(first.version() + 1, cur.version());
        assertEquals(second.contentHash(), cur.contentHash());
        assertEquals(7, ((Number) cur.config().get("fiscalYearStartMonth")).intValue());
        assertEquals(15, ((Number) cur.config().get("fiscalStartDay")).intValue());
        assertEquals(2, cur.config().size(), "exactly the published keys: " + cur.config());
        assertNotNull(cur.definedAt());
        // the history names the same current version and hash
        Map<String, Object> top = a.configHistory(T1).get(0);
        assertEquals(cur.version(), top.get("version"));
        assertEquals(cur.contentHash(), top.get("contentHash"));
        assertEquals(Boolean.TRUE, top.get("current"));
    }

    @Test
    void nothingPublishedIsNotFoundAndAnotherTenantsConfigurationIsNeverReturned() throws Exception {
        ProgrammeRestAdapter a = adapter(new Recording());
        ProgrammeException none = assertThrows(ProgrammeException.class, () -> a.publishedConfig(T1));
        assertEquals(ProgrammeException.Code.NOT_FOUND, none.code());
        a.publishConfig(T1, Map.of("fiscalYearStartMonth", 4));
        ProgrammeException other = assertThrows(ProgrammeException.class, () -> a.publishedConfig(T2));
        assertEquals(ProgrammeException.Code.NOT_FOUND, other.code());
    }

    @Test
    void theReadNeedsAdminOnTheProgrammeRoot() throws Exception {
        Recording auth = new Recording();
        ProgrammeRestAdapter a = adapter(auth);
        a.publishConfig(T1, Map.of("fiscalYearStartMonth", 4));
        auth.checks.clear();
        a.publishedConfig(T1);
        assertEquals(1, auth.checks.size(), auth.checks.toString());
        assertEquals(true, auth.checks.get(0).contains(":ADMIN:"), auth.checks.toString());
        auth.deny = true;
        assertThrows(SecurityException.class, () -> a.publishedConfig(T1));
    }
}
