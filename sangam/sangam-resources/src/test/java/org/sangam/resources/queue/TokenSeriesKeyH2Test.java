// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.queue;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sangam.core.layer.AbsentKey;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.ResourcesSchema;
import org.sangam.resources.dialect.ResourcesDialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression SL-84: a token issued with no series (no routing row, no {@code seriesBySkill}) locked and created its
 * RES_TOKEN_SEQ row with {@code series = ''}. Oracle stores '' as NULL, so the NOT NULL primary-key column refused it
 * (ORA-01400) and no walk-in token could be issued. H2's Oracle mode has the same empty-string rule; the other modes
 * prove the gap-free numbering and the displayed number are unchanged.
 */
class TokenSeriesKeyH2Test {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Oracle", "PostgreSQL", "MSSQLServer", "MySQL", "Regular"})
    void tokensWithoutASeriesAreNumberedGapFreeAndDisplayedUnchanged(String mode) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sl84-queue-" + mode + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            ResourcesSchema.create(c, ResourcesDialect.H2);
            QueueService queue = new QueueService(ResourcesContext.builder(ResourcesDialect.H2).deliverDirectly(true)
                    .withoutAuthorization(QueueNestedSettingsH2Test.DEVELOPMENT).build());
            SecurityIdentity actor = SecurityIdentity.system("t1", null);
            queue.defineServicePoint(c, actor, "SP1", "Front desk", null, null, "A", 0, null, null, Map.of());

            List<String> shown = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {   // ORA-01400 on the first one before the fix
                QueueModel.Token tok = queue.issue(c, actor,
                        new QueueModel.IssueRequest("SP1", null, null, null, null, null, null, null));
                shown.add(tok.displayNo());
                numbers.add(tok.tokenNo());
            }
            assertEquals(List.of("A001", "A002", "A003"), shown, mode + ": displayed number unchanged (no series shown)");
            assertEquals(List.of(1, 2, 3), numbers, mode + ": gap-free per service point, day and series");

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT series, last_no FROM RES_TOKEN_SEQ")) {
                rs.next();
                assertEquals(AbsentKey.NONE, rs.getString(1), mode + ": the no-series key is the reserved non-empty value");
                assertEquals(3, rs.getLong(2));
                assertFalse(rs.next(), mode + ": one sequence row");
            }
        }
    }
}
