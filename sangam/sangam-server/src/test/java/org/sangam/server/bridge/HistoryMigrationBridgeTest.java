// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.history.HistoricInstanceMigration;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;

/**
 * Decision 258 (FC-18): the bridge hands the authenticated identity to the engine, returns the engine result field for
 * field (versions included), and maps every engine code to the status of routes/history_migration.py.
 */
class HistoryMigrationBridgeTest {

    static final RequestContext CTX = new RequestContext("t1", "admin-1", Set.of("sangam-tenant-admin"), null, null,
            "key-1", null, "127.0.0.1", "OIDC");

    final List<SecurityIdentity> actors = new ArrayList<>();

    HistoryMigrationBridge bridge(RuntimeException failure) {
        return new HistoryMigrationBridge((actor, pi, target, reason) -> {
            actors.add(actor);
            if (failure != null) {
                throw failure;
            }
            return new HistoricInstanceMigration.Result(pi, "p:1:a", target, 1, 3,
                    Map.of("ACT_HI_PROCINST", 1, "ACT_HI_ACTINST", 4, "ACT_HI_DETAIL", 0));
        });
    }

    @Test
    void theActorIsTheAuthenticatedIdentityAndTheResultKeepsEveryField() {
        HistoryMigrationBridge.HistoryInstanceMigrationResult r = bridge(null).migrate(CTX, "pi-1", "p:3:c",
                "definition fix");
        assertEquals("pi-1", r.processInstanceId());
        assertEquals("p:1:a", r.fromDefinitionId());
        assertEquals("p:3:c", r.toDefinitionId());
        assertEquals(1, r.fromVersion());
        assertEquals(3, r.toVersion());
        assertEquals(Map.of("ACT_HI_PROCINST", 1, "ACT_HI_ACTINST", 4, "ACT_HI_DETAIL", 0), r.rowsByTable());
        SecurityIdentity actor = actors.get(0);
        assertEquals("t1", actor.tenantId());
        assertEquals("admin-1", actor.personId());
        assertFalse(actor.system());
    }

    static ApiProblem refusal(HistoryMigrationBridge b, String code) {
        return assertThrows(ApiProblem.class, () -> b.migrate(CTX, "pi-1", "p:3:c", "r"), code);
    }

    @Test
    void everyEngineCodeMapsToTheContractStatus() {
        Map<String, Integer> expected = Map.of(
                HistoricInstanceMigration.VALIDATION_FAILED, 400,
                HistoricInstanceMigration.NOT_FOUND, 404,
                HistoricInstanceMigration.NOT_ENDED, 409,
                HistoricInstanceMigration.REMOVAL_TIME_SET, 409,
                HistoricInstanceMigration.LEGAL_HOLD_ACTIVE, 409,
                HistoricInstanceMigration.TARGET_INVALID, 422);
        expected.forEach((code, status) -> {
            ApiProblem p = refusal(bridge(new HistoricInstanceMigration.HistoryMigrationException(code,
                    code + ": detail text")), code);
            assertEquals(status, p.status(), code);
            assertEquals(code, p.code(), code);
        });
        assertEquals("HISTORY_INSTANCE_NOT_ENDED", HistoricInstanceMigration.NOT_ENDED);
        assertEquals("HISTORY_REMOVAL_TIME_SET", HistoricInstanceMigration.REMOVAL_TIME_SET);
        assertEquals("LEGAL_HOLD_ACTIVE", HistoricInstanceMigration.LEGAL_HOLD_ACTIVE);
        assertEquals("HISTORY_MIGRATION_TARGET_INVALID", HistoricInstanceMigration.TARGET_INVALID);
    }

    @Test
    void aWrappedRefusalMapsTheSameAndAnUnknownCodeStaysAServerError() {
        RuntimeException wrapped = new RuntimeException("engine command failed",
                new HistoricInstanceMigration.HistoryMigrationException(HistoricInstanceMigration.LEGAL_HOLD_ACTIVE,
                        "held"));
        assertEquals(409, refusal(bridge(wrapped), "wrapped").status());

        IllegalStateException unknown = assertThrows(IllegalStateException.class, () -> bridge(
                new HistoricInstanceMigration.HistoryMigrationException("SOMETHING_NEW", "x"))
                .migrate(CTX, "pi-1", "p:3:c", "r"));
        assertEquals(true, unknown.getMessage().contains("SOMETHING_NEW"));

        IllegalArgumentException other = new IllegalArgumentException("bug");
        assertSame(other, assertThrows(IllegalArgumentException.class,
                () -> bridge(other).migrate(CTX, "pi-1", "p:3:c", "r")), "anything else is not turned into a 4xx");
    }
}
