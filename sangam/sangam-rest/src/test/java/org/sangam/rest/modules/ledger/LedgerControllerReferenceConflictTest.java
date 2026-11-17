// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.ledger.LedgerDefinition;
import org.sangam.ledger.LedgerService;
import org.sangam.ledger.LedgerStore;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;

/** Owner decision 9: POST /ledger/by-ledger/operations answers 409 REFERENCE_CONFLICT for a reused reference. */
class LedgerControllerReferenceConflictTest {

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "DEV_TOKEN");

    static final PermissionPort ALL = new PermissionPort() {
        @Override
        public boolean has(RequestContext ctx, String permission) {
            return true;
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return Set.of(LedgerController.READ, LedgerController.WRITE, LedgerController.ADMIN);
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    };

    static LedgerController.OperationRequest allocate(String amount, String reference) {
        return new LedgerController.OperationRequest("ALLOCATE",
                new LedgerController.AccountRef("2026-27", "2054-00-001", "DDO-1", "RELEASE", null), null, amount, "INR",
                null, reference, null, null, false, false, false, Map.of());
    }

    @Test
    void reusedReferenceWithAnotherAmountIs409AndAnExactReplayIs200() {
        LedgerService ledger = new LedgerService(new LedgerStore.InMemory(), null);
        ledger.define("t1", List.of(LedgerDefinition.from(Map.of("id", "BUDGET", "kind", "LIMIT", "currency", "INR",
                "stages", List.of("ALLOCATION", "RELEASE")))));
        LedgerController c = new LedgerController(new LedgerTenantDefinitions(ledger, null), ALL);

        assertTrue(c.apply(CTX, "BUDGET", allocate("100.00", "ref-1")).getBody().applied());
        LedgerController.ResultView replay = c.apply(CTX, "BUDGET", allocate("100.00", "ref-1")).getBody();
        assertTrue(replay.applied() && replay.duplicate());

        ApiProblem p = assertThrows(ApiProblem.class, () -> c.apply(CTX, "BUDGET", allocate("250.00", "ref-1")));
        assertEquals(409, p.status());
        assertEquals("REFERENCE_CONFLICT", p.code());
    }
}
