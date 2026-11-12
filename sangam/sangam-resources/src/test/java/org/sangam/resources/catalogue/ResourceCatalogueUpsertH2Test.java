// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.ResourcesSchema;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.resources.support.ResourcesException;

/**
 * M-130: {@link ResourceCatalogue#upsert} is the idempotent bulk entry point. A repeat of the same draft writes
 * nothing; a changed field is patched; type and code cannot change; every call is checked by the per-object guard as
 * the caller.
 */
class ResourceCatalogueUpsertH2Test {

    private static final SecurityIdentity ALICE = new SecurityIdentity("t1", "alice", "user:alice", Set.of(),
            Set.of("loader"), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());

    /** Every guard call, as {@code personId|permission|id}. */
    private final List<String> checks = new CopyOnWriteArrayList<>();
    private volatile String deny;
    private Connection c;
    private ResourceCatalogue catalogue;

    @BeforeEach
    void setUp() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:m130" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        ResourcesDialect d = ResourcesDialect.detect(c);
        ResourcesSchema.create(c, d);
        ResourcesContext ctx = ResourcesContext.builder(d).deliverDirectly(true).guard((cc, actor, rt, p, id) -> {
            checks.add(actor.personId() + "|" + p + "|" + id);
            if (id.equals(deny)) {
                throw ResourcesException.notEntitled("DENIED", "denied " + id);
            }
        }).build();
        catalogue = new ResourceCatalogue(ctx);
        catalogue.loadTypes(c, ALICE, List.of(Map.of("typeId", "ROOM", "name", "Room")));
        checks.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        c.close();
    }

    private static Resource.Draft draft(String name, Integer capacity) {
        return new Resource.Draft("r1", "ROOM", "R-1", name, null, null, null, null, null, capacity, null, null, null);
    }

    private int events() {
        return Jdbc.one(c, "SELECT COUNT(*) FROM RES_EVENT WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?", rs -> rs.getInt(1), "t1", "RESOURCE", "r1")
                .orElse(0);
    }

    @Test
    void aNewIdIsCreatedAndARepeatChangesNothing() {
        Resource first = catalogue.upsert(c, ALICE, draft("Hall", 10));
        int afterCreate = events();
        Resource again = catalogue.upsert(c, ALICE, draft("Hall", 10));

        assertEquals(first.version(), again.version(), "an identical draft writes nothing");
        assertEquals(afterCreate, events(), "an identical draft writes no event");
        assertTrue(checks.contains("alice|MODIFY|r1"), "the repeat is still checked on the object as the caller: " + checks);
    }

    @Test
    void aChangedFieldIsPatchedAsTheCaller() {
        catalogue.upsert(c, ALICE, draft("Hall", 10));
        Resource changed = catalogue.upsert(c, ALICE, draft("Main hall", 12));
        assertEquals("Main hall", changed.name());
        assertEquals(12, changed.capacity());
        assertEquals(2L, changed.version());
    }

    @Test
    void typeAndCodeCannotChange() {
        catalogue.upsert(c, ALICE, draft("Hall", 10));
        catalogue.loadTypes(c, ALICE, List.of(Map.of("typeId", "DESK")));
        ResourcesException type = assertThrows(ResourcesException.class, () -> catalogue.upsert(c, ALICE,
                new Resource.Draft("r1", "DESK", "R-1", "Hall", null, null, null, null, null, 10, null, null, null)));
        assertEquals("TYPE_CHANGED", type.reason());
        ResourcesException code = assertThrows(ResourcesException.class, () -> catalogue.upsert(c, ALICE,
                new Resource.Draft("r1", "ROOM", "R-2", "Hall", null, null, null, null, null, 10, null, null, null)));
        assertEquals("CODE_CHANGED", code.reason());
    }

    @Test
    void theGuardRefusesTheCallerOnTheObject() {
        catalogue.upsert(c, ALICE, draft("Hall", 10));
        deny = "r1";
        assertThrows(ResourcesException.class, () -> catalogue.upsert(c, ALICE, draft("Hall", 10)),
                "an unchanged row is refused too when the caller may not modify the object");
        assertThrows(ResourcesException.class, () -> catalogue.upsert(c, ALICE, draft("Other", 10)));
    }

    @Test
    void anUpsertNeedsTheId() {
        assertThrows(ResourcesException.class, () -> catalogue.upsert(c, ALICE,
                new Resource.Draft(null, "ROOM", "R-1", "Hall", null, null, null, null, null, 10, null, null, null)));
    }
}
