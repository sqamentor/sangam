// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;

/**
 * SL-157 (TM-5): two tenants use the same topic name. A worker of one tenant leases, sees and settles only its own
 * tenant's work; the other tenant's work is never handed out, never left locked, and its work id is refused (not
 * found) on every worker call.
 */
class WorkerTenantIsolationH2Test {

    WorkerEngineFixture fx;

    @BeforeEach
    void setUp() {
        fx = new WorkerEngineFixture("sl157");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static WorkPort.FetchAndLockRequest fetch(String worker, String topic) {
        return new WorkPort.FetchAndLockRequest(worker, List.of(new WorkPort.TopicRequest(topic, 60_000L, null, null,
                null, null)), 10, 0L, null);
    }

    @Test
    void aWorkerFetchesOnlyItsOwnTenantsWorkOnASharedTopic() {
        String a1 = fx.start("tA", "A-1");
        String b1 = fx.start("tB", "B-1");
        String a2 = fx.start("tA", "A-2");

        List<WorkPort.ExternalWork> forA = fx.work.fetchAndLock(WorkerEngineFixture.worker("tA", "wa"), fetch("wa",
                WorkerEngineFixture.TOPIC));
        assertEquals(Set.of(a1, a2), forA.stream().map(WorkPort.ExternalWork::processInstanceId).collect(Collectors.toSet()));
        assertTrue(forA.stream().allMatch(w -> "tA".equals(w.tenantId())));

        // tenant B's item was not taken by A's fetch: B's worker gets it now
        List<WorkPort.ExternalWork> forB = fx.work.fetchAndLock(WorkerEngineFixture.worker("tB", "wb"), fetch("wb",
                WorkerEngineFixture.TOPIC));
        assertEquals(List.of(b1), forB.stream().map(WorkPort.ExternalWork::processInstanceId).toList());
        assertEquals("tB", forB.get(0).tenantId());
    }

    @Test
    void theEngineFetchLimitedToATenantNeverLeasesAnotherTenantsWork() {
        fx.start("tB", "B-9");
        List<SangamEngineAdapter.LeasedWork> got = fx.engine.fetchAndLockWork("wa", WorkerEngineFixture.TOPIC, 10,
                Duration.ofMinutes(1), List.of("tA"));
        assertTrue(got.isEmpty(), "tenant B's work is not leased to tenant A");
        long leased = fx.tx.withConnection(c -> org.sangam.core.layer.Jdbc.one(c,
                "SELECT COUNT(*) FROM SANGAM_LEASE", rs -> rs.getLong(1)).orElse(0L));
        assertEquals(0, leased, "no lease row for the other tenant's item");
        assertEquals(1, fx.engine.fetchAndLockWork("wb", WorkerEngineFixture.TOPIC, 10, Duration.ofMinutes(1),
                List.of("tB")).size());
    }

    @Test
    void anotherTenantsWorkIdIsNotFoundOnEveryWorkerCall() {
        fx.start("tB", "B-2");
        WorkPort.ExternalWork b = fx.work.fetchAndLock(WorkerEngineFixture.worker("tB", "wb"),
                fetch("wb", WorkerEngineFixture.TOPIC)).get(0);
        RequestContext a = WorkerEngineFixture.worker("tA", "wa");
        ApiProblem complete = assertThrows(ApiProblem.class, () -> fx.work.complete(a, b.id(),
                new WorkPort.CompleteRequest("wb", b.lockVersion(), b.attemptId(), Map.of(), null, null)));
        assertEquals(404, complete.status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> fx.work.fail(a, b.id(), new WorkPort.FailRequest("wb",
                b.lockVersion(), b.attemptId(), "x", null, 0, 0L))).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> fx.work.extendLock(a, b.id(),
                new WorkPort.ExtendLockRequest("wb", b.lockVersion(), b.attemptId(), 60_000L))).status());
        // the owner still completes it
        fx.work.complete(WorkerEngineFixture.worker("tB", "wb"), b.id(),
                new WorkPort.CompleteRequest("wb", b.lockVersion(), b.attemptId(), Map.of(), null, null));
    }
}
