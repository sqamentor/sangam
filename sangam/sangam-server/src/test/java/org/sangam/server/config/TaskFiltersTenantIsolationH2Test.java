// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.jdbc.JdbcTaskFilterAdapter;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.TaskFilterPort;
import org.sangam.rest.port.TaskFilterPort.TaskFilter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Owner decision 404: shared task lists in {@code TEAM_TASK_FILTER} on the generated H2 changelog (0047-01, applied by
 * {@link SangamSchema#apply}). Tenant A never lists or reads tenant B's list, also not by its id.
 */
class TaskFiltersTenantIsolationH2Test {

    private TaskFilterPort filters;

    @BeforeEach
    void schema() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:ttf" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        filters = new JdbcTaskFilterAdapter(new SpringTxSupport(h2, new DataSourceTransactionManager(h2),
                Dialect.POSTGRESQL));
    }

    @Test
    void tenantANeverListsOrReadsTenantBsList() {
        TaskFilter a = filters.create("tenant-a", "sup-a", "Clerks", null, Map.of("candidateGroup", "clerks"), null);
        TaskFilter b = filters.create("tenant-b", "sup-b", "B secret desk", "desk-lead",
                Map.of("candidateGroup", "b-secret-desk"), Map.of("priority", 3));

        assertEquals(List.of(a.id()), filters.list("tenant-a").stream().map(TaskFilter::id).toList(),
                "tenant A lists only its own list");
        assertEquals(List.of(b.id()), filters.list("tenant-b").stream().map(TaskFilter::id).toList(),
                "tenant B lists only its own list");
        assertTrue(filters.get("tenant-a", b.id()).isEmpty(), "tenant A cannot read tenant B's list by id");
        assertTrue(filters.get("tenant-b", a.id()).isEmpty(), "tenant B cannot read tenant A's list by id");
    }

    @Test
    void aListRoundTripsAsStored() {
        TaskFilter b = filters.create("tenant-b", "sup-b", "B desk", "desk-lead", Map.of("candidateGroup", "desk",
                "assigned", false), Map.of("priority", 3, "refresh", true));
        TaskFilter read = filters.get("tenant-b", b.id()).orElseThrow();
        assertEquals(b, read);
        assertEquals("B desk", read.name());
        assertEquals("desk-lead", read.owner());
        assertEquals(Map.of("candidateGroup", "desk", "assigned", false), read.query());
        assertEquals(Map.of("priority", 3, "refresh", true), read.properties());
        assertEquals("sup-b", read.createdBy());
        assertEquals(1, read.version());
        assertTrue(read.createdAt() != null);
    }

    @Test
    void noTenantIsRefusedBeforeAnyStatement() {
        assertThrows(IllegalArgumentException.class, () -> filters.list(null));
        assertThrows(IllegalArgumentException.class, () -> filters.list(" "));
        assertThrows(IllegalArgumentException.class, () -> filters.get(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> filters.create("", "u", "n", null, Map.of(), null));
    }
}
